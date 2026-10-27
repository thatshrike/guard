package com.acousticguard.safety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DirectEmergencyResult(
    val callInitiated: Boolean,
    val callTarget: String?,
    val smsSentCount: Int,
    val sirenStarted: Boolean,
    val summary: String,
)

/**
 * Executes direct, hands-free emergency SOS actions when vocal distress is confirmed.
 *
 * Dispatches:
 * 1. Immediate cellular phone call to configured emergency contact (via ACTION_CALL).
 * 2. Instant emergency SMS to emergency contacts with distress type and timestamp.
 * 3. Piercing deterrent siren on the alarm stream.
 * 4. Timed screen wake.
 */
object DirectEmergencyDispatcher {

    fun parseContactNumbers(raw: String): List<String> =
        raw.split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun sanitizePhoneNumber(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }

    fun buildSmsMessage(customMessage: String, marker: String, timeStr: String): String =
        if (customMessage.isNotBlank()) {
            "${customMessage.trim()} [AcousticGuard SOS: $marker at $timeStr]"
        } else {
            "EMERGENCY: AcousticGuard detected severe distress ($marker) from this device at $timeStr. Audio corroborated locally. Please send help!"
        }

    @SuppressLint("MissingPermission")
    fun executeAutonomousSos(
        context: Context,
        marker: String,
        confidence: Float,
    ): DirectEmergencyResult {
        val appContext = context.applicationContext
        val config = EmergencyContactsPreferences.load(appContext)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        var callInitiated = false
        var callTarget: String? = null
        var smsSentCount = 0
        var sirenStarted = false

        // 1. Send Emergency SMS to configured contacts
        if (config.autoSmsEnabled && config.emergencySmsNumbers.isNotBlank()) {
            val hasSmsPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.SEND_SMS,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasSmsPermission) {
                val numbers = parseContactNumbers(config.emergencySmsNumbers)
                val message = buildSmsMessage(config.customSmsMessage, marker, timeStr)

                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appContext.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                for (number in numbers) {
                    runCatching {
                        val parts = smsManager.divideMessage(message)
                        if (parts.size > 1) {
                            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                        } else {
                            smsManager.sendTextMessage(number, null, message, null, null)
                        }
                        smsSentCount++
                    }
                }
            }
        }

        // 2. Direct Emergency Call
        if (config.autoCallEnabled && config.primaryCallNumber.isNotBlank()) {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CALL_PHONE,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCallPermission) {
                val cleanNumber = sanitizePhoneNumber(config.primaryCallNumber)
                if (cleanNumber.isNotBlank()) {
                    runCatching {
                        val callIntent = Intent(Intent.ACTION_CALL).apply {
                            data = Uri.parse("tel:$cleanNumber")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        appContext.startActivity(callIntent)
                        callInitiated = true
                        callTarget = cleanNumber
                    }
                }
            }
        }

        // 3. Deterrent Siren
        if (config.sirenEnabled) {
            EmergencySirenPlayer.start()
            sirenStarted = true
        }

        // 4. Best-effort Screen Wake
        ScreenWakeController.request(appContext)

        // Build human-readable summary
        val summaryParts = mutableListOf<String>()
        if (callInitiated) summaryParts.add("Emergency call started to $callTarget")
        if (smsSentCount > 0) summaryParts.add("Sent $smsSentCount emergency SMS alert(s)")
        if (sirenStarted) summaryParts.add("Emergency siren active")
        if (summaryParts.isEmpty()) summaryParts.add("Autonomous SOS executed (configure contacts in Settings for auto-dial/SMS)")

        val summary = summaryParts.joinToString(". ")

        return DirectEmergencyResult(
            callInitiated = callInitiated,
            callTarget = callTarget,
            smsSentCount = smsSentCount,
            sirenStarted = sirenStarted,
            summary = summary,
        )
    }
}
