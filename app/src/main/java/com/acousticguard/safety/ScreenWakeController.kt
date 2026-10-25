package com.acousticguard.safety

import android.content.Context
import android.os.PowerManager

/** Best-effort screen wake for the short handoff window. Android/OEM policy may still limit it. */
object ScreenWakeController {
    private const val WAKE_LOCK_TAG_SUFFIX = ":native-safety-handoff"
    private const val WAKE_DURATION_MS = 10_000L

    @Suppress("DEPRECATION")
    fun request(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        if (powerManager.isInteractive) return true

        return runCatching {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                context.packageName + WAKE_LOCK_TAG_SUFFIX,
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(WAKE_DURATION_MS)
            true
        }.getOrDefault(false)
    }
}
