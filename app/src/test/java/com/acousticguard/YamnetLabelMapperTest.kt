package com.acousticguard

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.DistressMarker
import com.acousticguard.ml.MotionSnapshot
import com.acousticguard.ml.YamnetCategoryScore
import com.acousticguard.ml.YamnetLabelMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YamnetLabelMapperTest {
    @Test
    fun mapsAudiosetDistressLabelsToTheExpectedMarkerHeads() {
        val inference = YamnetLabelMapper.toInference(
            categories = listOf(
                YamnetCategoryScore("Screaming", 0.91f),
                YamnetCategoryScore("Gasp", 0.42f),
                YamnetCategoryScore("Thump, thud", 0.88f),
            ),
            motion = MotionSnapshot(jerk = 0.9f),
        )

        assertEquals(0.91f, inference.probability(DistressMarker.SCREAM), 0.0001f)
        assertEquals(0.42f, inference.probability(DistressMarker.RESPIRATORY), 0.0001f)
        assertTrue(inference.probability(DistressMarker.IMPACT) > 0.7f)
        assertEquals("yamnet-audioset-1", inference.modelVersion)
    }

    @Test
    fun unknownLabelsDoNotCreateAFalsePositive() {
        val inference = YamnetLabelMapper.toInference(
            categories = listOf(YamnetCategoryScore("Music", 0.99f)),
            motion = MotionSnapshot(energy = 0.99f, jerk = 0.99f),
        )

        assertEquals(0f, inference.screamProbability, 0.0001f)
        assertEquals(0f, inference.respiratoryProbability, 0.0001f)
        assertEquals(0f, inference.impactProbability, 0.0001f)
    }

    @Test
    fun loudSpeechUsesTheLanguageIndependentVocalDistressPath() {
        val inference = YamnetLabelMapper.toInference(
            categories = listOf(YamnetCategoryScore("Speech", 0.90f)),
            motion = MotionSnapshot(),
            acousticFeatures = AcousticFeatureVector(
                loudness = 0.90f,
                highFrequencyRatio = 0.70f,
                spectralFlux = 0.40f,
                crestFactor = 0.50f,
                lowFrequencyRatio = 0.40f,
                silenceRatio = 0.05f,
                envelopeVariation = 0.60f,
                motionEnergy = 0f,
                motionJerk = 0f,
            ),
        )

        assertTrue(inference.screamProbability >= 0.72f)
    }

    @Test
    fun ordinarySpeechDoesNotReachTheVocalDistressThreshold() {
        val inference = YamnetLabelMapper.toInference(
            categories = listOf(YamnetCategoryScore("Speech", 0.90f)),
            motion = MotionSnapshot(),
            acousticFeatures = AcousticFeatureVector(
                loudness = 0.45f,
                highFrequencyRatio = 0.35f,
                spectralFlux = 0.20f,
                crestFactor = 0.40f,
                lowFrequencyRatio = 0.60f,
                silenceRatio = 0.20f,
                envelopeVariation = 0.30f,
                motionEnergy = 0f,
                motionJerk = 0f,
            ),
        )

        assertTrue(inference.screamProbability < 0.72f)
    }
}
