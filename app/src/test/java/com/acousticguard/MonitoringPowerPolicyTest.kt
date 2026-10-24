package com.acousticguard

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.MotionSnapshot
import com.acousticguard.monitoring.MonitoringPowerPolicy
import com.acousticguard.monitoring.MotionSamplingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringPowerPolicyTest {
    private val policy = MonitoringPowerPolicy()

    @Test
    fun quietMonitoringUsesTheLowestSensorRate() {
        assertEquals(MotionSamplingMode.LOW_POWER, policy.modeFor(features(), MotionSnapshot()))
    }

    @Test
    fun candidateAudioRaisesMotionSamplingRate() {
        assertEquals(
            MotionSamplingMode.CANDIDATE,
            policy.modeFor(features(loudness = 0.8f), MotionSnapshot()),
        )
    }

    @Test
    fun inertialImpulseUsesTheFastestPolicy() {
        assertEquals(
            MotionSamplingMode.IMPACT,
            policy.modeFor(features(), MotionSnapshot(jerk = 0.8f)),
        )
    }

    private fun features(loudness: Float = 0f) = AcousticFeatureVector(
        loudness = loudness,
        highFrequencyRatio = 0f,
        spectralFlux = 0f,
        crestFactor = 0f,
        lowFrequencyRatio = 1f,
        silenceRatio = 1f,
        envelopeVariation = 0f,
        motionEnergy = 0f,
        motionJerk = 0f,
    )
}
