package com.acousticguard.monitoring

import com.acousticguard.ml.DistressMarker
import com.acousticguard.ml.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EngineState {
    OFF,
    STARTING,
    MONITORING,
    BLOCKED,
    ERROR,
}

enum class MonitoringMode(val label: String) {
    NORMAL("Normal"),
    LOW_POWER("Low-power"),
    DEGRADED("Degraded"),
}

data class MonitoringStatus(
    val engineState: EngineState = EngineState.OFF,
    val lastMarker: DistressMarker? = null,
    val lastConfidence: Float = 0f,
    val lastSignalAtEpochMs: Long? = null,
    val pendingIncidentCount: Int = 0,
    val countdownSecondsRemaining: Int? = null,
    val motionReady: Boolean = false,
    val motionSamplingMode: MotionSamplingMode = MotionSamplingMode.LOW_POWER,
    val monitoringMode: MonitoringMode = MonitoringMode.NORMAL,
    val modelInfo: ModelInfo = ModelInfo.UNAVAILABLE,
    val fallbackModelActive: Boolean = false,
    val lastModelError: String? = null,
    val detectionMetrics: DetectionMetrics = DetectionMetrics(),
    val message: String = "Ready when you are.",
) {
    val isMonitoring: Boolean get() = engineState == EngineState.MONITORING
}

object MonitoringStatusStore {
    private val mutableState = MutableStateFlow(MonitoringStatus())
    val state: StateFlow<MonitoringStatus> = mutableState.asStateFlow()

    fun update(transform: (MonitoringStatus) -> MonitoringStatus) {
        mutableState.update(transform)
    }

    fun reset() {
        mutableState.value = MonitoringStatus()
    }
}
