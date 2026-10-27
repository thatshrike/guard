package com.acousticguard.ml

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * Immutable, versioned operating policy for temporal distress confirmation.
 *
 * The values are deliberately kept outside [DistressFusionEngine] so calibration can produce a
 * reviewable artifact without changing the detector implementation. A policy is not a safety
 * certification; it is the exact operating point used by a build.
 */
data class DetectionPolicy(
    val policyVersion: String,
    val screamThreshold: Float,
    val respiratoryThreshold: Float,
    val impactThreshold: Float,
    val impactJerkThreshold: Float,
    val uncertaintyThreshold: Float,
    val audioQualityThreshold: Float,
    val requiredConsecutiveWindows: Int,
    val confirmationWindowMs: Long,
    val cooldownMs: Long,
) {
    init {
        require(policyVersion.isNotBlank()) { "The detection policy must have a version." }
        require(screamThreshold in 0f..1f) { "The scream threshold must be between 0 and 1." }
        require(respiratoryThreshold in 0f..1f) { "The respiratory threshold must be between 0 and 1." }
        require(impactThreshold in 0f..1f) { "The impact threshold must be between 0 and 1." }
        require(impactJerkThreshold in 0f..1f) { "The impact jerk threshold must be between 0 and 1." }
        require(uncertaintyThreshold in 0f..1f) { "The uncertainty threshold must be between 0 and 1." }
        require(audioQualityThreshold in 0f..1f) { "The audio-quality threshold must be between 0 and 1." }
        require(requiredConsecutiveWindows > 0) { "At least one qualifying window is required." }
        require(confirmationWindowMs > 0L) { "The confirmation window must be positive." }
        require(cooldownMs >= confirmationWindowMs) {
            "The cooldown must not be shorter than the confirmation window."
        }
    }

    /** Stable representation used for the bundled SHA-256 integrity check. */
    fun canonicalPayload(): String = listOf(
        "policy_version=$policyVersion",
        "scream_threshold=${screamThreshold.canonicalNumber()}",
        "respiratory_threshold=${respiratoryThreshold.canonicalNumber()}",
        "impact_threshold=${impactThreshold.canonicalNumber()}",
        "impact_jerk_threshold=${impactJerkThreshold.canonicalNumber()}",
        "uncertainty_threshold=${uncertaintyThreshold.canonicalNumber()}",
        "audio_quality_threshold=${audioQualityThreshold.canonicalNumber()}",
        "required_consecutive_windows=$requiredConsecutiveWindows",
        "confirmation_window_ms=$confirmationWindowMs",
        "cooldown_ms=$cooldownMs",
    ).joinToString("|")

    fun sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalPayload().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Float.canonicalNumber(): String = String.format(Locale.US, "%.6f", this)

    companion object {
        /** The initial, explicitly uncalibrated policy shipped for prototype and replay tests. */
        val CURRENT = DetectionPolicy(
            policyVersion = "fusion-v1",
            screamThreshold = 0.52f,
            respiratoryThreshold = 0.65f,
            impactThreshold = 0.74f,
            impactJerkThreshold = 0.42f,
            uncertaintyThreshold = 0.85f,
            audioQualityThreshold = 0.10f,
            requiredConsecutiveWindows = 3,
            confirmationWindowMs = 8_000L,
            cooldownMs = 60_000L,
        )

        fun fromJson(jsonText: String): DetectionPolicy {
            val json = JSONObject(jsonText)
            val policy = DetectionPolicy(
                policyVersion = json.optString("policy_version"),
                screamThreshold = json.optDouble("scream_threshold", -1.0).toFloat(),
                respiratoryThreshold = json.optDouble("respiratory_threshold", -1.0).toFloat(),
                impactThreshold = json.optDouble("impact_threshold", -1.0).toFloat(),
                impactJerkThreshold = json.optDouble("impact_jerk_threshold", -1.0).toFloat(),
                uncertaintyThreshold = json.optDouble("uncertainty_threshold", -1.0).toFloat(),
                audioQualityThreshold = json.optDouble("audio_quality_threshold", -1.0).toFloat(),
                requiredConsecutiveWindows = json.optInt("required_consecutive_windows", -1),
                confirmationWindowMs = json.optLong("confirmation_window_ms", -1L),
                cooldownMs = json.optLong("cooldown_ms", -1L),
            )
            val recordedHash = json.optString("sha256")
            require(recordedHash.matches(SHA256_HEX)) {
                "The detection policy contains an invalid SHA-256 digest."
            }
            require(policy.sha256().equals(recordedHash, ignoreCase = true)) {
                "The detection policy failed its integrity check."
            }
            return policy
        }

        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}

/** Reads the immutable policy artifact packaged in the APK. */
object DetectionPolicyAssets {
    const val DEFAULT_ASSET = "detection_policy_v1.json"

    fun load(context: Context, assetName: String = DEFAULT_ASSET): DetectionPolicy =
        context.assets.open(assetName).use { input ->
            DetectionPolicy.fromJson(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
}
