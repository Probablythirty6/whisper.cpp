package com.whispercppdemo

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.whispercppdemo.ui.main.MainScreen
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, VoiceServerService::class.java))
        setContent {
            WhisperCppDemoTheme {
                MainScreen(viewModel)
            }
        }
    }
}