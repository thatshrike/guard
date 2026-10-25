package com.acousticguard.monitoring

import java.io.Closeable

/**
 * Source of fixed-size normalized mono PCM windows. Production and replay sources share this
 * boundary so the detector can be exercised without a microphone or an Android service.
 */
interface AudioWindowSource : Closeable {
    val sampleRateHz: Int
    val windowSamples: Int

    /** Copies the next window into [destination], returning false when the source is exhausted. */
    fun readWindow(destination: FloatArray): Boolean
}

/** Deterministic source for replay tests and benchmark fixtures. */
class ReplayAudioWindowSource(
    private val windows: List<FloatArray>,
    override val sampleRateHz: Int = 16_000,
) : AudioWindowSource {
    init {
        require(windows.isNotEmpty()) { "A replay source needs at least one window." }
        require(windows.map(FloatArray::size).distinct().size == 1) {
            "All replay windows must have the same sample count."
        }
    }

    override val windowSamples: Int = windows.first().size
    private var nextIndex = 0

    override fun readWindow(destination: FloatArray): Boolean {
        require(destination.size >= windowSamples) {
            "The destination is too small for the replay window."
        }
        val window = windows.getOrNull(nextIndex++) ?: return false
        window.copyInto(destination, endIndex = windowSamples)
        if (destination.size > windowSamples) destination.fill(0f, windowSamples, destination.size)
        return true
    }

    override fun close() {
        nextIndex = windows.size
    }
}
