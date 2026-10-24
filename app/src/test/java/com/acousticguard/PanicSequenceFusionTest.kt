package com.acousticguard

import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.MotionSnapshot
import org.junit.Assert.assertFalse
import org.junit.Test

class PanicSequenceFusionTest {
    @Test
    fun interleavedMarkersDoNotBypassPerMarkerTemporalConfirmation() {
        val engine = DistressFusionEngine()
        val scream = DistressInference(0.90f, 0f, 0f, "test")
        val impact = DistressInference(0f, 0f, 0.90f, "test")

        engine.update(scream, MotionSnapshot(), nowMs = 0L)
        engine.update(impact, MotionSnapshot(jerk = 0.9f), nowMs = 1_000L)
        val decision = engine.update(scream, MotionSnapshot(), nowMs = 2_000L)

        assertFalse(decision?.confirmed == true)
    }
}
