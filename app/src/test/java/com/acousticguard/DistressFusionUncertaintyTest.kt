package com.acousticguard

import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.MotionSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DistressFusionUncertaintyTest {
    @Test
    fun uncertainHighScoreDoesNotAdvanceTheConfirmationStreak() {
        val engine = DistressFusionEngine()
        val uncertain = DistressInference(
            screamProbability = 0.99f,
            respiratoryProbability = 0f,
            impactProbability = 0f,
            modelVersion = "student",
            uncertainty = 0.95f,
        )

        assertNull(engine.update(uncertain, MotionSnapshot(), 0L))
        assertNull(engine.update(uncertain, MotionSnapshot(), 1_000L))
        val decision = engine.update(uncertain, MotionSnapshot(), 2_000L)

        assertFalse(decision?.confirmed == true)
    }

    @Test
    fun lowQualityAudioDoesNotBecomeAConfirmedEvent() {
        val engine = DistressFusionEngine()
        val lowQuality = DistressInference(
            screamProbability = 0.99f,
            respiratoryProbability = 0f,
            impactProbability = 0f,
            modelVersion = "student",
            audioQuality = 0.05f,
        )

        assertNull(engine.update(lowQuality, MotionSnapshot(), 0L))
    }
}
