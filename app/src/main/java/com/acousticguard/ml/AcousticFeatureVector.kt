package com.acousticguard.ml

/**
 * Features extracted from one short microphone window. This object contains no raw samples.
 * All values are normalized to 0..1 so the local classifier stays deterministic and auditable.
 */
data class AcousticFeatureVector(
    val loudness: Float,
    val highFrequencyRatio: Float,
    val spectralFlux: Float,
    val crestFactor: Float,
    val lowFrequencyRatio: Float,
    val silenceRatio: Float,
    val envelopeVariation: Float,
    val motionEnergy: Float,
    val motionJerk: Float,
)

data class MotionSnapshot(
    val energy: Float = 0f,
    val jerk: Float = 0f,
)
