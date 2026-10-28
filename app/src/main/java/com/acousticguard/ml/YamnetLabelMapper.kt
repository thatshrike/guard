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
        val suppressorScore = maximumScore(categories, SUPPRESSOR_LABELS)
        val backgroundSuppression = (1f - suppressorScore).coerceIn(0f, 1f)

        val scream = if (acousticFeatures == null) {
            screamLabelScore * backgroundSuppression
        } else {
            // YAMNet does not transcribe the spoken word and does not have a language-specific
            // distress label. When speech is recognized, only escalate to distress if vocal
            // intensity is acute (shouting/yelling) rather than ordinary conversational volume.
            val vocalEnergy = (
                (acousticFeatures.loudness - 0.25f).coerceAtLeast(0f) / 0.75f * 0.70f +
                    acousticFeatures.highFrequencyRatio * 0.15f +
                    (1f - acousticFeatures.silenceRatio) * 0.15f
                ).coerceIn(0f, 1f)
            val vocalScaling = if (vocalEnergy < 0.40f) {
                0.20f * (vocalEnergy / 0.40f)
            } else {
                0.20f + 0.80f * ((vocalEnergy - 0.40f) / 0.60f)
            }
            val loudSpeechScore = speechLabelScore * vocalScaling
            maxOf(screamLabelScore, loudSpeechScore).coerceIn(0f, 1f) * backgroundSuppression
        }
        val respiratory = if (acousticFeatures == null) {
            respiratoryLabelScore * backgroundSuppression
        } else {
            (respiratoryLabelScore * (0.70f + 0.30f * (1f - acousticFeatures.silenceRatio))).coerceIn(0f, 1f) *
                backgroundSuppression
        }
        val impact = if (acousticImpact > 0f) {
            val acousticImpulse = acousticFeatures?.let {
                (it.spectralFlux * 0.55f + it.crestFactor * 0.45f).coerceIn(0f, 1f)
            } ?: acousticImpact
            (acousticImpact * 0.55f + acousticImpulse * 0.20f + motion.jerk * 0.25f).coerceIn(0f, 1f) *
                backgroundSuppression
        } else {
            0f
        }

        val strongestDistressProbability = maxOf(scream, respiratory, impact)
        val normal = (1f - strongestDistressProbability).coerceIn(0f, 1f)
        val uncertainty = when {
            // When benign background sound (music, TV, laughter) strongly co-exists with distress labels,
            // the window is ambiguous and should be rejected rather than advanced as a distress candidate.
            suppressorScore >= 0.35f && strongestDistressProbability >= 0.25f -> 0.90f
            // An absence of allow-listed distress labels is a useful, low-cost negative signal for
            // the scheduler even though YAMNet has no calibrated "normal" output head.
            strongestDistressProbability <= 0.20f -> 0.05f
            strongestDistressProbability >= 0.50f -> (1f - strongestDistressProbability).coerceIn(0f, 1f)
            else -> 0.70f
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

    // Labels are taken from the YAMNet AudioSet class map. The mapping intentionally uses an
    // auditable allow-list of recognized human distress and vocalisations.
    private val SCREAM_LABELS = setOf(
        "Screaming",
        "Shout",
        "Yell",
        "Children shouting",
        "Crying, sobbing",
        "Wail, moan",
        "Groan",
        "Whimper",
    )
    private val SPEECH_LABELS = setOf(
        "Speech",
        "Male speech, man speaking",
        "Female speech, woman speaking",
        "Child speech, kid speaking",
    )
    private val RESPIRATORY_LABELS = setOf(
        "Gasp",
        "Pant",
        "Cough",
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

    // Negative AudioSet classes representing benign entertainment, environmental, or social audio.
    // When detected, these actively suppress false distress triggers.
    private val SUPPRESSOR_LABELS = setOf(
        "Music",
        "Musical instrument",
        "Singing",
        "Laughter",
        "Giggle",
        "Snicker",
        "Chuckle",
        "Cheering",
        "Applause",
        "Television",
        "Snoring",
        "Vehicle",
        "Car",
        "Traffic noise, roadway noise",
        "Bark",
        "Dog",
    )
}
