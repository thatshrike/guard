package com.acousticguard.safety

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Checks the system accessibility toggle for this app's service without requesting a runtime permission. */
object AccessibilityServiceAvailability {
    fun isEnabled(context: Context): Boolean = runCatching {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return@runCatching false
        val expected = ComponentName(context, NativeSafetyAccessibilityService::class.java)
        enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }.getOrDefault(false)
}
