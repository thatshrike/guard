package com.acousticguard.monitoring

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.MotionSnapshot

enum class MotionSamplingMode(
    val label: String,
    val samplingPeriodUs: Int,
) {
    LOW_POWER("Low-power", 200_000),
    CANDIDATE("Candidate", 20_000),
    IMPACT("Impact", 5_000),
}

/** Selects the lowest motion sampling cost that still preserves candidate-event recall. */
class MonitoringPowerPolicy {
    fun modeFor(features: AcousticFeatureVector, motion: MotionSnapshot): MotionSamplingMode = when {
        motion.jerk >= IMPACT_JERK_THRESHOLD ||
            (features.spectralFlux >= IMPACT_FLUX_THRESHOLD && features.crestFactor >= IMPACT_CREST_THRESHOLD) ->
            MotionSamplingMode.IMPACT

        features.loudness >= CANDIDATE_LOUDNESS_THRESHOLD ||
            features.spectralFlux >= CANDIDATE_FLUX_THRESHOLD ||
            motion.energy >= CANDIDATE_MOTION_THRESHOLD ->
            MotionSamplingMode.CANDIDATE

        else -> MotionSamplingMode.LOW_POWER
    }

    private companion object {
        const val IMPACT_JERK_THRESHOLD = 0.35f
        const val IMPACT_FLUX_THRESHOLD = 0.65f
        const val IMPACT_CREST_THRESHOLD = 0.65f
        const val CANDIDATE_LOUDNESS_THRESHOLD = 0.55f
        const val CANDIDATE_FLUX_THRESHOLD = 0.45f
        const val CANDIDATE_MOTION_THRESHOLD = 0.30f
    }
}
