package com.acousticguard

import com.acousticguard.ml.AudioSignalProcessor
import com.acousticguard.ml.MotionSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalProcessorTest {
    @Test
    fun silenceProducesQuietFeatureVector() {
        val samples = ShortArray(2_048)
        val features = AudioSignalProcessor().extract(samples, samples.size, MotionSnapshot())

        assertTrue(features.loudness < 0.05f)
        assertTrue(features.silenceRatio > 0.95f)
    }

    @Test
    fun transientProducesNonZeroEnergyFeatures() {
        val samples = ShortArray(2_048)
        samples[1_024] = Short.MAX_VALUE
        val features = AudioSignalProcessor().extract(
            samples,
            samples.size,
            MotionSnapshot(energy = 0.8f, jerk = 0.9f),
        )

        assertTrue(features.loudness > 0f)
        assertTrue(features.crestFactor > 0f)
        assertTrue(features.motionJerk > 0.8f)
    }
}
