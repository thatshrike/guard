package com.acousticguard

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.DistressClassifier
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.DistressMarker
import com.acousticguard.ml.MotionSnapshot
import com.acousticguard.ml.ModelInfo
import com.acousticguard.ml.ModelSource
import com.acousticguard.monitoring.DetectionClock
import com.acousticguard.monitoring.DetectionPipeline
import com.acousticguard.monitoring.InferenceScheduler
import com.acousticguard.monitoring.ReplayAudioWindowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPipelineTest {
    @Test
    fun oneWindowNeverConfirms() {
        val pipeline = pipeline(FakeClassifier { screamInference() })

        val result = pipeline.processAt(FloatArray(16_000), MotionSnapshot(), nowMs = 0L)

        assertTrue(result.modelInvoked)
        assertNotNull(result.decision)
        assertFalse(result.decision!!.confirmed)
    }

    @Test
    fun threeQualifyingWindowsConfirmThroughTheSamePipelineUsedByTheService() {
        val pipeline = pipeline(FakeClassifier { screamInference() })
        val samples = FloatArray(16_000)

        pipeline.processAt(samples, MotionSnapshot(), nowMs = 0L)
        pipeline.processAt(samples, MotionSnapshot(), nowMs = 1_000L)
        val result = pipeline.processAt(samples, MotionSnapshot(), nowMs = 2_000L)

        assertTrue(result.decision?.confirmed == true)
        assertEquals(DistressMarker.SCREAM, result.decision?.marker)
    }

    @Test
    fun aGapOutsideTheConfirmationWindowResetsTheStreak() {
        val pipeline = pipeline(FakeClassifier { screamInference() })
        val samples = FloatArray(16_000)

        pipeline.processAt(samples, MotionSnapshot(), nowMs = 0L)
        pipeline.processAt(samples, MotionSnapshot(), nowMs = 1_000L)
        val result = pipeline.processAt(samples, MotionSnapshot(), nowMs = 10_000L)

        assertEquals(1, result.decision?.consecutiveWindows)
        assertFalse(result.decision?.confirmed == true)
    }

    @Test
    fun uncertaintyAndLowQualityAreRejectedBeforeConfirmation() {
        val uncertainPipeline = pipeline(FakeClassifier {
            screamInference().copy(uncertainty = 0.95f)
        })
        val lowQualityPipeline = pipeline(FakeClassifier {
            screamInference().copy(audioQuality = 0.05f)
        })
        val samples = FloatArray(16_000)

        assertNull(uncertainPipeline.processAt(samples, MotionSnapshot(), 0L).decision)
        assertNull(lowQualityPipeline.processAt(samples, MotionSnapshot(), 0L).decision)
    }

    @Test
    fun stationaryVocalDistressCanConfirmWithoutMotion() {
        val pipeline = pipeline(FakeClassifier { screamInference() })
        val samples = FloatArray(16_000)

        repeat(3) { index ->
            val result = pipeline.processAt(samples, MotionSnapshot(), index * 1_000L)
            if (index == 2) assertTrue(result.decision?.confirmed == true)
        }
    }

    @Test
    fun impactNeedsInertialEvidence() {
        val pipeline = pipeline(FakeClassifier { impactInference() })
        val samples = FloatArray(16_000)

        assertNull(pipeline.processAt(samples, MotionSnapshot(), 0L).decision)
        val result = pipeline.processAt(samples, MotionSnapshot(jerk = 0.9f), 1_000L)

        assertEquals(DistressMarker.IMPACT, result.decision?.marker)
    }

    @Test
    fun injectableClockDrivesProcessTimestamps() {
        val clock = MutableClock(1234L)
        val pipeline = DetectionPipeline(
            classifier = FakeClassifier { screamInference() },
            scheduler = InferenceScheduler(quietScanIntervalMs = 0L),
            clock = clock,
        )

        val result = pipeline.process(FloatArray(16_000), MotionSnapshot())

        assertEquals(1234L, result.timestampMs)
        clock.nowMs = 5678L
        assertEquals(5678L, pipeline.process(FloatArray(16_000), MotionSnapshot()).timestampMs)
    }

    @Test
    fun replaySourceIsExhaustiveAndDoesNotChangeWindowShape() {
        val source = ReplayAudioWindowSource(
            windows = listOf(FloatArray(4) { 1f }, FloatArray(4) { 2f }),
        )
        val destination = FloatArray(6) { -1f }

        assertTrue(source.readWindow(destination))
        assertEquals(1f, destination[0])
        assertEquals(0f, destination[5])
        assertTrue(source.readWindow(destination))
        assertEquals(2f, destination[0])
        assertFalse(source.readWindow(destination))
    }

    private fun pipeline(classifier: FakeClassifier): DetectionPipeline = DetectionPipeline(
        classifier = classifier,
        scheduler = InferenceScheduler(quietScanIntervalMs = 0L),
    )

    private fun screamInference() = DistressInference(
        screamProbability = 0.92f,
        respiratoryProbability = 0.05f,
        impactProbability = 0.05f,
        modelVersion = "test",
        normalProbability = 0.08f,
    )

    private fun impactInference() = DistressInference(
        screamProbability = 0.05f,
        respiratoryProbability = 0.05f,
        impactProbability = 0.95f,
        modelVersion = "test",
        normalProbability = 0.05f,
    )

    private class MutableClock(var nowMs: Long) : DetectionClock {
        override fun nowMs(): Long = nowMs
    }

    private class FakeClassifier(
        private val inferenceFactory: (AcousticFeatureVector?) -> DistressInference,
    ) : DistressClassifier {
        override val modelInfo = ModelInfo(
            modelVersion = "test",
            runtime = "test",
            quantization = "float32",
            inferenceMode = "test",
            source = ModelSource.TINY_STUDENT,
        )
        override val requiredInputBufferSize: Int = 16_000
        override val sampleRateHz: Int = 16_000

        override fun infer(
            samples: FloatArray,
            motion: MotionSnapshot,
            acousticFeatures: AcousticFeatureVector?,
        ): DistressInference = inferenceFactory(acousticFeatures)

        override fun close() = Unit
    }
}
