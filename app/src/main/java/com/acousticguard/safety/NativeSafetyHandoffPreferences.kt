package com.acousticguard.safety

import android.content.Context

/** Stores only the user's explicit native-handoff opt-in. */
object NativeSafetyHandoffPreferences {
    private const val FILE_NAME = "native_safety_handoff"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
