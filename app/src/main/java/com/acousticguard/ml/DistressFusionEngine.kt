package com.acousticguard.ml

import kotlin.math.max

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
        val candidates = buildList {
            if (inference.screamProbability >= SCREAM_THRESHOLD &&
                (motion.energy >= 0.16f || inference.screamProbability >= 0.93f)
            ) {
                add(
                    FusionDecision(
                        marker = DistressMarker.SCREAM,
                        riskScore = max(inference.screamProbability, motion.energy),
                        confidence = inference.screamProbability,
                        rationale = "Vocal friction and inertial corroboration",
                    ),
                )
            }
            if (inference.respiratoryProbability >= RESPIRATORY_THRESHOLD &&
                (motion.energy >= 0.08f || inference.respiratoryProbability >= 0.94f)
            ) {
                add(
                    FusionDecision(
                        marker = DistressMarker.RESPIRATORY,
                        riskScore = inference.respiratoryProbability,
                        confidence = inference.respiratoryProbability,
                        rationale = "Respiratory pattern sustained across local windows",
                    ),
                )
            }
            if (inference.impactProbability >= IMPACT_THRESHOLD && motion.jerk >= IMPACT_JERK_THRESHOLD) {
                add(
                    FusionDecision(
                        marker = DistressMarker.IMPACT,
                        riskScore = (inference.impactProbability + motion.jerk) / 2f,
                        confidence = inference.impactProbability,
                        rationale = "Acoustic transient and inertial impulse corroboration",
                    ),
                )
            }
        }
        return candidates.maxByOrNull { it.riskScore }
    }

    private fun decay(nowMs: Long) {
        streaks.entries.removeIf { nowMs - it.value.lastAt > confirmationWindowMs }
    }

    private data class MarkerStreak(val count: Int, val lastAt: Long)

    private companion object {
        const val SCREAM_THRESHOLD = 0.78f
        const val RESPIRATORY_THRESHOLD = 0.80f
        const val IMPACT_THRESHOLD = 0.74f
        const val IMPACT_JERK_THRESHOLD = 0.42f
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
