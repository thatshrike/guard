package com.acousticguard.monitoring

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object MonitoringController {
    fun start(context: Context) {
        val intent = Intent(context, MonitoringService::class.java)
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { failure ->
            MonitoringStatusStore.update {
                it.copy(
                    engineState = EngineState.ERROR,
                    monitoringMode = MonitoringMode.DEGRADED,
                    message = when (failure) {
                        is SecurityException -> "Android denied microphone monitoring. Review permissions and try again."
                        else -> "Android could not start the monitoring service. Try again while the app is open."
                    },
                )
            }
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MonitoringService::class.java))
        MonitoringStatusStore.reset()
    }
}
