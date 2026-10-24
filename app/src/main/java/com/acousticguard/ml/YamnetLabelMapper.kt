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
        modelSource: ModelSource = ModelSource.YAMNET_BASELINE,
    ): DistressInference {
        val screamLabelScore = maximumScore(categories, SCREAM_LABELS)
        val speechLabelScore = maximumScore(categories, SPEECH_LABELS)
        val respiratoryLabelScore = maximumScore(categories, RESPIRATORY_LABELS)
        val acousticImpact = maximumScore(categories, IMPACT_LABELS)
        val scream = if (acousticFeatures == null) {
            screamLabelScore
        } else {
            // YAMNet does not transcribe the spoken word and does not have a Hindi-specific
            // distress label. It does, however, identify speech/shouting acoustically. Combine
            // that label score with loudness and vocal activity so a shouted "help" or "मदद"
            // can use the same language-independent path as a scream.
            val vocalEnergy = (
                acousticFeatures.loudness * 0.65f +
                    acousticFeatures.highFrequencyRatio * 0.20f +
                    (1f - acousticFeatures.silenceRatio) * 0.15f
                ).coerceIn(0f, 1f)
            val loudSpeechScore = speechLabelScore * (0.35f + 0.65f * vocalEnergy)
            maxOf(screamLabelScore, loudSpeechScore).coerceIn(0f, 1f)
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

        val strongestDistressProbability = maxOf(scream, respiratory, impact)
        val normal = (1f - strongestDistressProbability).coerceIn(0f, 1f)
        val uncertainty = when {
            // An absence of allow-listed distress labels is a useful, low-cost negative signal for
            // the scheduler even though YAMNet has no calibrated "normal" output head.
            strongestDistressProbability <= 0.20f -> 0.05f
            strongestDistressProbability >= 0.72f -> (1f - strongestDistressProbability).coerceIn(0f, 1f)
            else -> 0.80f
        }
        val audioQuality = acousticFeatures?.let {
            (1f - it.silenceRatio).coerceIn(0f, 1f)
        } ?: 1f

        return DistressInference(
            screamProbability = scream,
            respiratoryProbability = respiratory,
            impactProbability = impact,
            modelVersion = modelVersion,
            normalProbability = normal,
            uncertainty = uncertainty,
            audioQuality = audioQuality,
            modelSource = modelSource,
        )
    }

    private fun maximumScore(categories: List<YamnetCategoryScore>, labels: Set<String>): Float {
        var maximum = 0f
        for (category in categories) {
            if (category.label !in labels || !category.score.isFinite()) continue
            maximum = maxOf(maximum, category.score)
        }
        return maximum.coerceIn(0f, 1f)
    }

    private const val MODEL_VERSION = "yamnet-audioset-1"

    // Labels are taken from the YAMNet AudioSet class map. The mapping intentionally uses a small
    // allow-list instead of treating every loud sound as danger.
    private val SCREAM_LABELS = setOf(
        "Screaming",
        "Shout",
        "Yell",
        "Children shouting",
    )
    private val SPEECH_LABELS = setOf(
        "Speech",
        "Male speech, man speaking",
        "Female speech, woman speaking",
        "Child speech, kid speaking",
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
