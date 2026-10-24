package com.acousticguard.monitoring

import com.acousticguard.ml.MotionSnapshot
import kotlin.math.abs
import kotlin.math.sqrt

/** Converts accelerometer samples into a tiny inertial feature snapshot. */
class MotionFeatureTracker {
    private var previousDelta = 0f
    private var energy = 0f
    private var jerk = 0f

    @Synchronized
    fun update(x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - GRAVITY)
        val currentJerk = abs(delta - previousDelta)
        energy = (energy * 0.78f + (delta / ENERGY_SCALE).coerceIn(0f, 1f) * 0.22f).coerceIn(0f, 1f)
        jerk = (jerk * 0.68f + (currentJerk / JERK_SCALE).coerceIn(0f, 1f) * 0.32f).coerceIn(0f, 1f)
        previousDelta = delta
    }

    @Synchronized
    fun snapshot(): MotionSnapshot = MotionSnapshot(energy = energy, jerk = jerk)

    private companion object {
        const val GRAVITY = 9.81f
        const val ENERGY_SCALE = 12f
        const val JERK_SCALE = 8f
    }
}
