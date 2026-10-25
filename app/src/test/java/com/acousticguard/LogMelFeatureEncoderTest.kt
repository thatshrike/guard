package com.acousticguard

import com.acousticguard.ml.LogMelFeatureEncoder
import com.acousticguard.ml.MotionSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMelFeatureEncoderTest {
    @Test
    fun deterministicMixedSignalMatchesThePreprocessingGoldenVector() {
        val samples = FloatArray(16_000) { index ->
            (
                0.35 * sin(2.0 * PI * 440.0 * index / 16_000.0) +
                    0.10 * sin(2.0 * PI * 3_000.0 * index / 16_000.0)
                ).toFloat()
        }
        val destination = FloatArray(2_050)

        LogMelFeatureEncoder().encode(
            samples = samples,
            motion = MotionSnapshot(energy = 0.25f, jerk = 0.75f),
            destination = destination,
        )

        assertClose(0.0f, destination[0])
        assertClose(1.0f, destination[10])
        assertClose(0.4912166f, destination[200])
        assertClose(0.15752055f, destination[400])
        assertClose(0.09716438f, destination[900])
        assertClose(0.00363246f, destination[1_000])
        assertClose(0.25f, destination[2_048])
        assertClose(0.75f, destination[2_049])
    }

    @Test
    fun silenceMapsToTheLowerContractBoundaryAndPreservesMotionFeatures() {
        val destination = FloatArray(2_050)

        LogMelFeatureEncoder().encode(
            samples = FloatArray(16_000),
            motion = MotionSnapshot(energy = 0.4f, jerk = 0.6f),
            destination = destination,
        )

        assertTrue(destination.sliceArray(0 until 2_048).all { it == 0f })
        assertEquals(0.4f, destination[2_048])
        assertEquals(0.6f, destination[2_049])
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= 1e-4f)
    }
}
