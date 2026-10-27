package com.acousticguard.monitoring

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.MotionSnapshot

/** Decides when the expensive model is worth running after cheap DSP and motion extraction. */
class InferenceScheduler(
    private val quietScanIntervalMs: Long = DEFAULT_QUIET_SCAN_INTERVAL_MS,
    private val uncertainThreshold: Float = DEFAULT_UNCERTAIN_THRESHOLD,
) {
    private var lastInferenceAtMs = Long.MIN_VALUE
    private var previousUncertainty = 1f

    fun shouldRunImmediately(features: AcousticFeatureVector, motion: MotionSnapshot): Boolean =
        features.loudness >= LOUDNESS_GATE ||
            features.spectralFlux >= SPECTRAL_FLUX_GATE ||
            features.crestFactor >= CREST_FACTOR_GATE ||
            features.envelopeVariation >= ENVELOPE_VARIATION_GATE ||
            motion.energy >= MOTION_ENERGY_GATE ||
            motion.jerk >= MOTION_JERK_GATE

    fun quietScanDue(nowMs: Long): Boolean =
        lastInferenceAtMs == Long.MIN_VALUE ||
            (nowMs >= lastInferenceAtMs && nowMs - lastInferenceAtMs >= quietScanIntervalMs)

    fun previousResultWasUncertain(): Boolean = previousUncertainty >= uncertainThreshold

    fun recordInference(nowMs: Long, inference: DistressInference) {
        lastInferenceAtMs = nowMs
        previousUncertainty = inference.uncertainty.coerceIn(0f, 1f)
    }

    fun reset() {
        lastInferenceAtMs = Long.MIN_VALUE
        previousUncertainty = 1f
    }

    private companion object {
        const val DEFAULT_QUIET_SCAN_INTERVAL_MS = 2_000L
        const val DEFAULT_UNCERTAIN_THRESHOLD = 0.75f
        const val LOUDNESS_GATE = 0.45f
        const val SPECTRAL_FLUX_GATE = 0.45f
        const val CREST_FACTOR_GATE = 0.68f
        const val ENVELOPE_VARIATION_GATE = 0.55f
        const val MOTION_ENERGY_GATE = 0.30f
        const val MOTION_JERK_GATE = 0.28f
    }
}
