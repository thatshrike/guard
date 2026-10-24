package com.acousticguard

import com.acousticguard.monitoring.DetectionMetricsTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionMetricsTest {
    @Test
    fun tracksOnlyScalarCountersAndLatencyPercentiles() {
        val metrics = DetectionMetricsTracker()
        metrics.recordAudioWindowRead()
        metrics.recordAudioWindowRead()
        metrics.recordSkippedModelInvocation()
        metrics.recordDroppedWindow()
        metrics.recordModelInvocation(latencyMs = 12L, modelVersion = "student")
        metrics.recordModelInvocation(latencyMs = 40L, modelVersion = "student")

        val snapshot = metrics.snapshot()

        assertEquals(2L, snapshot.audioWindowsRead)
        assertEquals(2L, snapshot.modelInvocations)
        assertEquals(1L, snapshot.skippedModelInvocations)
        assertEquals(1L, snapshot.droppedWindows)
        assertEquals(12L, snapshot.inferenceLatencyP50Ms)
        assertEquals(40L, snapshot.inferenceLatencyP95Ms)
        assertEquals("student", snapshot.lastModelVersion)
    }
}
