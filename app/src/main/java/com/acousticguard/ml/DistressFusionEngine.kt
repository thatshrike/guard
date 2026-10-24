package com.acousticguard.ml

/**
 * Converts local model scores into a fail-closed decision.
 * A single loud sound never dispatches. The same marker must be corroborated across multiple
 * short windows, and impact events also require inertial evidence.
 */
class DistressFusionEngine(
    private val requiredConsecutiveWindows: Int = 3,
    private val confirmationWindowMs: Long = 8_000L,
    private val cooldownMs: Long = 60_000L,
) {
    private val streaks = mutableMapOf<DistressMarker, MarkerStreak>()
    private var lastConfirmedAt = Long.MIN_VALUE

    fun update(inference: DistressInference, motion: MotionSnapshot, nowMs: Long): FusionDecision? {
        val candidate = strongestCandidate(inference, motion)
        if (candidate == null) {
            decay(nowMs)
            return null
        }

        val current = streaks[candidate.marker]
        val nextStreak = if (current == null || nowMs - current.lastAt > confirmationWindowMs) {
            MarkerStreak(1, nowMs)
        } else {
            MarkerStreak(current.count + 1, nowMs)
        }
        streaks[candidate.marker] = nextStreak

        val cooldownElapsed = lastConfirmedAt == Long.MIN_VALUE ||
            (nowMs >= lastConfirmedAt && nowMs - lastConfirmedAt >= cooldownMs)
        val confirmed = nextStreak.count >= requiredConsecutiveWindows && cooldownElapsed
        if (confirmed) {
            lastConfirmedAt = nowMs
            streaks.clear()
        }

        return candidate.copy(
            consecutiveWindows = nextStreak.count,
            confirmed = confirmed,
        )
    }

    private fun strongestCandidate(
        inference: DistressInference,
        motion: MotionSnapshot,
    ): FusionDecision? {
        if (inference.uncertainty >= MAX_ACCEPTED_UNCERTAINTY ||
            inference.audioQuality < MIN_ACCEPTED_AUDIO_QUALITY
        ) {
            return null
        }

        var strongest: FusionDecision? = null

        // Vocal distress is already corroborated by the acoustic model plus the sustained
        // multi-window streak below. Do not require the phone to move: a person can shout for
        // help while the device is on a table, in a pocket, or held completely still.
        if (inference.screamProbability >= SCREAM_THRESHOLD) {
            strongest = FusionDecision(
                marker = DistressMarker.SCREAM,
                riskScore = inference.screamProbability,
                confidence = inference.screamProbability,
                rationale = "Sustained vocal distress detected acoustically",
            )
        }
        if (inference.respiratoryProbability >= RESPIRATORY_THRESHOLD &&
            (motion.energy >= 0.08f || inference.respiratoryProbability >= 0.94f)
        ) {
            val respiratory = FusionDecision(
                marker = DistressMarker.RESPIRATORY,
                riskScore = inference.respiratoryProbability,
                confidence = inference.respiratoryProbability,
                rationale = "Respiratory pattern sustained across local windows",
            )
            if (isStronger(strongest, respiratory)) strongest = respiratory
        }
        if (inference.impactProbability >= IMPACT_THRESHOLD && motion.jerk >= IMPACT_JERK_THRESHOLD) {
            val impact = FusionDecision(
                marker = DistressMarker.IMPACT,
                riskScore = (inference.impactProbability + motion.jerk) / 2f,
                confidence = inference.impactProbability,
                rationale = "Acoustic transient and inertial impulse corroboration",
            )
            if (isStronger(strongest, impact)) strongest = impact
        }
        return strongest
    }

    private fun isStronger(current: FusionDecision?, candidate: FusionDecision): Boolean =
        current == null || candidate.riskScore > current.riskScore

    private fun decay(nowMs: Long) {
        streaks.entries.removeIf { nowMs - it.value.lastAt > confirmationWindowMs }
    }

    private data class MarkerStreak(val count: Int, val lastAt: Long)

    private companion object {
        const val SCREAM_THRESHOLD = 0.72f
        const val RESPIRATORY_THRESHOLD = 0.80f
        const val IMPACT_THRESHOLD = 0.74f
        const val IMPACT_JERK_THRESHOLD = 0.42f
        const val MAX_ACCEPTED_UNCERTAINTY = 0.85f
        const val MIN_ACCEPTED_AUDIO_QUALITY = 0.10f
    }
}

data class FusionDecision(
    val marker: DistressMarker,
    val riskScore: Float,
    val confidence: Float,
    val rationale: String,
    val consecutiveWindows: Int = 0,
    val confirmed: Boolean = false,
)
