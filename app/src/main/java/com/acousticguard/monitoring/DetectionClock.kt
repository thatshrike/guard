package com.acousticguard.monitoring

/** Injectable time source for deterministic replay and confirmation-window tests. */
fun interface DetectionClock {
    fun nowMs(): Long
}

object SystemDetectionClock : DetectionClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
