package com.acousticguard.ml

/**
 * Converts local model scores into a fail-closed decision.
 * A single loud sound never dispatches. The same marker must be corroborated across multiple
 * short windows, and impact events also require inertial evidence.
 */
class DistressFusionEngine(
    private val policy: DetectionPolicy = DetectionPolicy.CURRENT,
) {
    private val streaks = mutableMapOf<DistressMarker, MarkerStreak>()
    private var lastConfirmedAt = Long.MIN_VALUE

    fun update(inference: DistressInference, motion: MotionSnapshot, nowMs: Long): FusionDecision? {
        val candidate = strongestCandidate(inference, motion)
        if (candidate == null) {
            // A confirmation streak is made of consecutive qualifying windows. A normal,
            // uncertain, or low-quality window must break it immediately rather than merely
            // allowing the old streak to age out over several seconds.
            streaks.clear()
            return null
        }

        val current = streaks[candidate.marker]
        // Only the same marker in the immediately preceding qualifying window can extend a
        // streak. A switch from vocal/respiratory/impact evidence starts a fresh streak.
        streaks.clear()
        val nextStreak = if (current == null || nowMs - current.lastAt > policy.confirmationWindowMs) {
            MarkerStreak(1, nowMs)
        } else {
            MarkerStreak(current.count + 1, nowMs)
        }
        streaks[candidate.marker] = nextStreak

        val cooldownElapsed = lastConfirmedAt == Long.MIN_VALUE ||
            (nowMs >= lastConfirmedAt && nowMs - lastConfirmedAt >= policy.cooldownMs)
        val confirmed = nextStreak.count >= policy.requiredConsecutiveWindows && cooldownElapsed
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
        if (inference.uncertainty >= policy.uncertaintyThreshold ||
            inference.audioQuality < policy.audioQualityThreshold
        ) {
            return null
        }

        var strongest: FusionDecision? = null

        // Vocal distress is already corroborated by the acoustic model plus the sustained
        // multi-window streak below. Do not require the phone to move: a person can shout for
        // help while the device is on a table, in a pocket, or held completely still.
        if (inference.screamProbability >= policy.screamThreshold) {
            strongest = FusionDecision(
                marker = DistressMarker.SCREAM,
                riskScore = inference.screamProbability,
                confidence = inference.screamProbability,
                rationale = "Sustained vocal distress detected acoustically",
            )
        }
        if (inference.respiratoryProbability >= policy.respiratoryThreshold &&
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
        if (inference.impactProbability >= policy.impactThreshold && motion.jerk >= policy.impactJerkThreshold) {
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

    /** Breaks a candidate streak when the scheduler skips a model invocation for a quiet window. */
    fun resetCandidateStreak() {
        streaks.clear()
    }

    private data class MarkerStreak(val count: Int, val lastAt: Long)

}

data class FusionDecision(
    val marker: DistressMarker,
    val riskScore: Float,
    val confidence: Float,
    val rationale: String,
    val consecutiveWindows: Int = 0,
    val confirmed: Boolean = false,
)
