package com.acousticguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.acousticguard.monitoring.NotificationChannels
import com.acousticguard.ui.AcousticGuardApp
import com.acousticguard.ui.theme.AcousticGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationChannels.create(this)
        setContent {
            AcousticGuardTheme {
                AcousticGuardApp()
            }
        }
    }
}
