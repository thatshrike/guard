package com.acousticguard.monitoring

import com.acousticguard.ml.AcousticFeatureVector
import com.acousticguard.ml.AudioSignalProcessor
import com.acousticguard.ml.DistressClassifier
import com.acousticguard.ml.DistressFusionEngine
import com.acousticguard.ml.DistressInference
import com.acousticguard.ml.FusionDecision
import com.acousticguard.ml.MotionSnapshot
import java.io.Closeable

/**
 * Android-independent detector orchestration for one normalized audio window.
 *
 * The pipeline owns scheduling, feature extraction, inference, and temporal fusion, but it never
 * retains the supplied PCM array. A service, replay harness, or test fixture can feed the same
 * path without constructing an Android foreground service.
 */
class DetectionPipeline(
    private val classifier: DistressClassifier,
    private val featureProcessor: AudioSignalProcessor = AudioSignalProcessor(),
    private val scheduler: InferenceScheduler = InferenceScheduler(),
    private val fusionEngine: DistressFusionEngine = DistressFusionEngine(),
    private val clock: DetectionClock = SystemDetectionClock,
) : Closeable {
    fun process(
        samples: FloatArray,
        motion: MotionSnapshot,
    ): DetectionPipelineResult = processAt(samples, motion, clock.nowMs())

    fun processAt(
        samples: FloatArray,
        motion: MotionSnapshot,
        nowMs: Long,
    ): DetectionPipelineResult {
        require(samples.size == classifier.requiredInputBufferSize) {
            "The detector requires ${classifier.requiredInputBufferSize} samples, received ${samples.size}."
        }

        val features = featureProcessor.extract(samples, samples.size, motion)
        val shouldRunModel = scheduler.shouldRunImmediately(features, motion) ||
            scheduler.quietScanDue(nowMs) ||
            scheduler.previousResultWasUncertain()

        if (!shouldRunModel) {
            fusionEngine.resetCandidateStreak()
            return DetectionPipelineResult(
                timestampMs = nowMs,
                features = features,
                modelInvoked = false,
                inference = null,
                decision = null,
            )
        }

        val inferenceStartedAt = System.nanoTime()
        val inference = classifier.infer(samples, motion, features)
        val modelLatencyMs = (System.nanoTime() - inferenceStartedAt)
            .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        scheduler.recordInference(nowMs, inference)
        return DetectionPipelineResult(
            timestampMs = nowMs,
            features = features,
            modelInvoked = true,
            inference = inference,
            decision = fusionEngine.update(inference, motion, nowMs),
            modelLatencyMs = modelLatencyMs,
        )
    }

    fun resetCandidateStreak() {
        fusionEngine.resetCandidateStreak()
    }

    override fun close() {
        classifier.close()
    }
}

data class DetectionPipelineResult(
    val timestampMs: Long,
    val features: AcousticFeatureVector,
    val modelInvoked: Boolean,
    val inference: DistressInference?,
    val decision: FusionDecision?,
    val modelLatencyMs: Long = 0L,
)

private const val NANOS_PER_MILLISECOND = 1_000_000L
