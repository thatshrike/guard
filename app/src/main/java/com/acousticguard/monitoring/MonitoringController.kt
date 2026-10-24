package com.acousticguard.monitoring

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object MonitoringController {
    fun start(context: Context) {
        val intent = Intent(context, MonitoringService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MonitoringService::class.java))
        MonitoringStatusStore.reset()
    }
}
