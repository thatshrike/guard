package com.acousticguard

import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.DistressMarker
import com.acousticguard.ml.MotionSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistressFusionEngineTest {
    private val scream = DistressInference(
        screamProbability = 0.92f,
        respiratoryProbability = 0.1f,
        impactProbability = 0.1f,
        modelVersion = "test",
    )

    @Test
    fun oneWindowDoesNotConfirmAnEvent() {
        val engine = DistressFusionEngine()

        val decision = engine.update(scream, MotionSnapshot(energy = 0.5f), nowMs = 0L)

        assertNotNull(decision)
        assertFalse(decision!!.confirmed)
    }

    @Test
    fun repeatedCorroboratedWindowsConfirmTheStrongestMarker() {
        val engine = DistressFusionEngine()
        val motion = MotionSnapshot(energy = 0.5f, jerk = 0.1f)

        engine.update(scream, motion, nowMs = 0L)
        engine.update(scream, motion, nowMs = 1_000L)
        val decision = engine.update(scream, motion, nowMs = 2_000L)

        assertNotNull(decision)
        assertTrue(decision!!.confirmed)
        assertTrue(decision.marker == DistressMarker.SCREAM)
    }
}
