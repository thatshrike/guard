package com.acousticguard.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationManagerCompat
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
        val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alertAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.alert_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(alertUri, alertAudioAttributes)
            },
        )
    }

    /**
     * Monitoring is only safe to start when both the ongoing status notification and the
     * cancellation-alert channel can actually surface notifications. Android lets users block a
     * channel independently of the app-wide notification permission.
     */
    fun areRequiredChannelsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return listOf(MONITORING_CHANNEL_ID, ALERT_CHANNEL_ID).all { channelId ->
            manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
