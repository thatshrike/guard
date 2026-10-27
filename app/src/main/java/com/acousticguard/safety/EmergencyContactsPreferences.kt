package com.acousticguard.safety

import android.content.Context
import android.content.SharedPreferences

data class SosConfig(
    val primaryCallNumber: String = "",
    val autoCallEnabled: Boolean = false,
    val emergencySmsNumbers: String = "",
    val autoSmsEnabled: Boolean = false,
    val sirenEnabled: Boolean = true,
    val customSmsMessage: String = "",
)

object EmergencyContactsPreferences {
    private const val PREF_NAME = "emergency_contacts_config"
    private const val KEY_PRIMARY_CALL_NUMBER = "primary_call_number"
    private const val KEY_AUTO_CALL_ENABLED = "auto_call_enabled"
    private const val KEY_EMERGENCY_SMS_NUMBERS = "emergency_sms_numbers"
    private const val KEY_AUTO_SMS_ENABLED = "auto_sms_enabled"
    private const val KEY_SIREN_ENABLED = "siren_enabled"
    private const val KEY_CUSTOM_SMS_MESSAGE = "custom_sms_message"

    fun load(context: Context): SosConfig {
        val prefs = prefs(context)
        return SosConfig(
            primaryCallNumber = prefs.getString(KEY_PRIMARY_CALL_NUMBER, "") ?: "",
            autoCallEnabled = prefs.getBoolean(KEY_AUTO_CALL_ENABLED, false),
            emergencySmsNumbers = prefs.getString(KEY_EMERGENCY_SMS_NUMBERS, "") ?: "",
            autoSmsEnabled = prefs.getBoolean(KEY_AUTO_SMS_ENABLED, false),
            sirenEnabled = prefs.getBoolean(KEY_SIREN_ENABLED, true),
            customSmsMessage = prefs.getString(KEY_CUSTOM_SMS_MESSAGE, "") ?: "",
        )
    }

    fun save(context: Context, config: SosConfig) {
        prefs(context).edit()
            .putString(KEY_PRIMARY_CALL_NUMBER, config.primaryCallNumber.trim())
            .putBoolean(KEY_AUTO_CALL_ENABLED, config.autoCallEnabled)
            .putString(KEY_EMERGENCY_SMS_NUMBERS, config.emergencySmsNumbers.trim())
            .putBoolean(KEY_AUTO_SMS_ENABLED, config.autoSmsEnabled)
            .putBoolean(KEY_SIREN_ENABLED, config.sirenEnabled)
            .putString(KEY_CUSTOM_SMS_MESSAGE, config.customSmsMessage.trim())
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
