package com.acousticguard

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.MotionSnapshot
import com.acousticguard.monitoring.InferenceScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceSchedulerTest {
    @Test
    fun quietAudioUsesAPeriodicScanInsteadOfDisablingInferenceForever() {
        val scheduler = InferenceScheduler(quietScanIntervalMs = 2_000L)
        val quiet = AcousticFeatureVector(
            loudness = 0f,
            highFrequencyRatio = 0f,
            spectralFlux = 0f,
            crestFactor = 0f,
            lowFrequencyRatio = 1f,
            silenceRatio = 1f,
            envelopeVariation = 0f,
            motionEnergy = 0f,
            motionJerk = 0f,
        )

        assertTrue(scheduler.quietScanDue(0L))
        scheduler.recordInference(0L, confidentInference())
        assertFalse(scheduler.shouldRunImmediately(quiet, MotionSnapshot()))
        assertFalse(scheduler.quietScanDue(1_000L))
        assertTrue(scheduler.quietScanDue(2_000L))
    }

    @Test
    fun highEnergyTransientRequestsImmediateInference() {
        val scheduler = InferenceScheduler()
        val transient = AcousticFeatureVector(
            loudness = 0.9f,
            highFrequencyRatio = 0.4f,
            spectralFlux = 0.8f,
            crestFactor = 0.9f,
            lowFrequencyRatio = 0.2f,
            silenceRatio = 0.1f,
            envelopeVariation = 0.7f,
            motionEnergy = 0.1f,
            motionJerk = 0.1f,
        )

        assertTrue(scheduler.shouldRunImmediately(transient, MotionSnapshot()))
    }

    @Test
    fun uncertainResultsKeepTheExpensiveModelHotForTheNextWindow() {
        val scheduler = InferenceScheduler()
        scheduler.recordInference(
            nowMs = 0L,
            inference = confidentInference().copy(uncertainty = 0.9f),
        )

        assertTrue(scheduler.previousResultWasUncertain())
    }

    private fun confidentInference() = DistressInference(
        screamProbability = 0f,
        respiratoryProbability = 0f,
        impactProbability = 0f,
        modelVersion = "test",
        normalProbability = 1f,
        uncertainty = 0.1f,
    )
}
