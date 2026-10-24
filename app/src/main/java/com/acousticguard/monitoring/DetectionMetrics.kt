package com.acousticguard.monitoring

import kotlin.math.ceil

/** In-memory scalar diagnostics. Raw audio, spectrograms, and user data are never recorded. */
data class DetectionMetrics(
    val audioWindowsRead: Long = 0,
    val modelInvocations: Long = 0,
    val skippedModelInvocations: Long = 0,
    val droppedWindows: Long = 0,
    val inferenceLatencyP50Ms: Long = 0,
    val inferenceLatencyP95Ms: Long = 0,
    val lastModelVersion: String = "",
)

/** Low-overhead accumulator used by the foreground service. */
class DetectionMetricsTracker {
    private var audioWindowsRead = 0L
    private var modelInvocations = 0L
    private var skippedModelInvocations = 0L
    private var droppedWindows = 0L
    private var lastModelVersion = ""
    private val latencyBuckets = LongArray(MAX_LATENCY_BUCKET_MS + 1)

    @Synchronized
    fun reset() {
        audioWindowsRead = 0L
        modelInvocations = 0L
        skippedModelInvocations = 0L
        droppedWindows = 0L
        lastModelVersion = ""
        latencyBuckets.fill(0L)
    }

    @Synchronized
    fun recordAudioWindowRead() {
        audioWindowsRead++
    }

    @Synchronized
    fun recordModelInvocation(latencyMs: Long, modelVersion: String) {
        modelInvocations++
        lastModelVersion = modelVersion
        latencyBuckets[latencyMs.coerceIn(0L, MAX_LATENCY_BUCKET_MS.toLong()).toInt()]++
    }

    @Synchronized
    fun recordSkippedModelInvocation() {
        skippedModelInvocations++
    }

    @Synchronized
    fun recordDroppedWindow() {
        droppedWindows++
    }

    @Synchronized
    fun snapshot(): DetectionMetrics {
        return DetectionMetrics(
            audioWindowsRead = audioWindowsRead,
            modelInvocations = modelInvocations,
            skippedModelInvocations = skippedModelInvocations,
            droppedWindows = droppedWindows,
            inferenceLatencyP50Ms = percentile(0.50),
            inferenceLatencyP95Ms = percentile(0.95),
            lastModelVersion = lastModelVersion,
        )
    }

    private fun percentile(percentile: Double): Long {
        if (modelInvocations == 0L) return 0L
        val target = ceil(modelInvocations * percentile).toLong().coerceAtLeast(1L)
        var seen = 0L
        for (bucket in latencyBuckets.indices) {
            seen += latencyBuckets[bucket]
            if (seen >= target) return bucket.toLong()
        }
        return MAX_LATENCY_BUCKET_MS.toLong()
    }

    private companion object {
        const val MAX_LATENCY_BUCKET_MS = 2_000
    }
}
