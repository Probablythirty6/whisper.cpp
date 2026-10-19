package com.whispercppdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.decodeWaveFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "VoiceServer"
private const val PORT = 8080
private const val MODEL_NAME = "ggml-tiny.en.bin"
private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
private const val CHANNEL_ID = "voice_server"

class VoiceServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HttpServer? = null
    private var whisper: WhisperContext? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var status = "starting"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice server", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DFR1221 voice server")
            .setContentText("Listening on port $PORT")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        if (server == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceserver:wake").apply { acquire() }
            val s = HttpServer()
            server = s
            scope.launch {
                try {
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                } catch (e: Exception) {
                    Log.e(TAG, "server start failed", e)
                    status = "error: ${e.message}"
                    return@launch
                }
                loadModel()
            }
        }
        return START_STICKY
    }

    private fun loadModel() {
        try {
            val model = File(filesDir, MODEL_NAME)
            if (!model.exists()) {
                status = "downloading model"
                val tmp = File(filesDir, "$MODEL_NAME.part")
                URL(MODEL_URL).openStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (tmp.length() < 50_000_000L) throw RuntimeException("model download incomplete")
                tmp.renameTo(model)
            }
            status = "loading model"
            whisper = WhisperContext.createContextFromFile(model.absolutePath)
            status = "ready"
        } catch (e: Exception) {
            Log.e(TAG, "model load failed", e)
            status = "error: ${e.message}"
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        whisper?.let { runBlocking { it.release() } }
        whisper = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class HttpServer : NanoHTTPD(PORT) {
        private fun json(st: Response.Status, obj: JSONObject): Response =
            newFixedLengthResponse(st, "application/json", obj.toString())

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET) {
                return json(Response.Status.OK, JSONObject().put("status", status))
            }
            if (session.method != Method.POST) {
                return json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "use POST with a WAV body"))
            }
            val ctx = whisper
                ?: return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "model not ready").put("status", status))
            val len = session.headers["content-length"]?.toIntOrNull()
            if (len == null || len < 44 || len > 10_000_000) {
                return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad or missing content-length"))
            }
            val body = ByteArray(len)
            var off = 0
            val input = session.inputStream
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) break
                off += n
            }
            if (off != len) {
                return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "incomplete body"))
            }
            val hdr = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val riff = String(body, 0, 4, Charsets.US_ASCII)
            val wave = String(body, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE" || hdr.getShort(22).toInt() != 1 ||
                hdr.getInt(24) != 16000 || hdr.getShort(34).toInt() != 16
            ) {
                return json(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "need 16 kHz mono 16-bit PCM WAV with a 44-byte header")
                )
            }
            val tmp = File.createTempFile("clip", ".wav", cacheDir)
            return try {
                tmp.writeBytes(body)
                val samples = decodeWaveFile(tmp)
                val text = runBlocking { ctx.transcribeData(samples, false) }.trim()
                json(Response.Status.OK, JSONObject().put("text", text))
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed", e)
                json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "transcribe failed"))
            } finally {
                tmp.delete()
            }
        }
    }
}
