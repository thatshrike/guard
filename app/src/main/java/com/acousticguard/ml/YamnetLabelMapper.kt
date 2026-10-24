package com.acousticguard.ml

/** A small, auditable label adapter over the pretrained YAMNet AudioSet vocabulary. */
data class YamnetCategoryScore(
    val label: String,
    val score: Float,
)

object YamnetLabelMapper {
    fun toInference(
        categories: List<YamnetCategoryScore>,
        motion: MotionSnapshot,
        acousticFeatures: AcousticFeatureVector? = null,
        modelVersion: String = MODEL_VERSION,
    ): DistressInference {
        val screamLabelScore = maximumScore(categories, SCREAM_LABELS)
        val respiratoryLabelScore = maximumScore(categories, RESPIRATORY_LABELS)
        val acousticImpact = maximumScore(categories, IMPACT_LABELS)
        val scream = if (acousticFeatures == null) {
            screamLabelScore
        } else {
            (screamLabelScore * (0.55f + 0.45f * acousticFeatures.highFrequencyRatio)).coerceIn(0f, 1f)
        }
        val respiratory = if (acousticFeatures == null) {
            respiratoryLabelScore
        } else {
            (respiratoryLabelScore * (0.70f + 0.30f * (1f - acousticFeatures.silenceRatio))).coerceIn(0f, 1f)
        }
        val impact = if (acousticImpact > 0f) {
            val acousticImpulse = acousticFeatures?.let {
                (it.spectralFlux * 0.55f + it.crestFactor * 0.45f).coerceIn(0f, 1f)
            } ?: acousticImpact
            (acousticImpact * 0.55f + acousticImpulse * 0.20f + motion.jerk * 0.25f).coerceIn(0f, 1f)
        } else {
            0f
        }

        return DistressInference(
            screamProbability = scream,
            respiratoryProbability = respiratory,
            impactProbability = impact,
            modelVersion = modelVersion,
        )
    }

    private fun maximumScore(categories: List<YamnetCategoryScore>, labels: Set<String>): Float =
        categories.asSequence()
            .filter { it.label in labels }
            .map { it.score }
            .filter { it.isFinite() }
            .maxOrNull()
            ?.coerceIn(0f, 1f)
            ?: 0f

    private const val MODEL_VERSION = "yamnet-audioset-1"

    // Labels are taken from the YAMNet AudioSet class map. The mapping intentionally uses a small
    // allow-list instead of treating every loud sound as danger.
    private val SCREAM_LABELS = setOf(
        "Screaming",
        "Shout",
        "Yell",
        "Children shouting",
    )
    private val RESPIRATORY_LABELS = setOf(
        "Breathing",
        "Gasp",
    )
    private val IMPACT_LABELS = setOf(
        "Thump, thud",
        "Slap, smack",
        "Whack, thwack",
        "Smash, crash",
        "Breaking",
        "Knock",
        "Bang",
    )
}
