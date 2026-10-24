package com.acousticguard.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.acousticguard.R

/** All user-visible monitoring channels are owned by the application layer, not the dispatch sender. */
object NotificationChannels {
    const val MONITORING_CHANNEL_ID = "acoustic_guard_monitoring"
    const val ALERT_CHANNEL_ID = "acoustic_guard_alerts"

    fun create(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                MONITORING_CHANNEL_ID,
                context.getString(R.string.monitoring_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.monitoring_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.alert_channel_description)
                enableVibration(true)
            },
        )
    }
}
