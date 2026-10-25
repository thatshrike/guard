package com.acousticguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acousticguard.dispatch.DispatchCoordinator
import com.acousticguard.dispatch.DispatchSettings
import com.acousticguard.dispatch.DispatchSnapshot
import com.acousticguard.monitoring.MonitoringController
import com.acousticguard.monitoring.MonitoringStatusStore
import com.acousticguard.safety.AccessibilityServiceAvailability
import com.acousticguard.safety.NativeSafetyHandoffController
import com.acousticguard.safety.NativeSafetyHandoffPreferences
import com.acousticguard.safety.NativeSafetyHandoffStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DispatchUiState(
    val settings: DispatchSettings = DispatchSettings(),
    val snapshot: DispatchSnapshot = DispatchSnapshot(),
    val message: String? = null,
    val saving: Boolean = false,
)

data class NativeSafetyUiState(
    val enabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val monitoringStatus = MonitoringStatusStore.state
    val nativeSafetyStatus = NativeSafetyHandoffStatusStore.state
    private val dispatchCoordinator = DispatchCoordinator(application)
    private val mutableDispatchUi = MutableStateFlow(DispatchUiState())
    val dispatchUi: StateFlow<DispatchUiState> = mutableDispatchUi.asStateFlow()
    private val mutableNativeSafetyUi = MutableStateFlow(NativeSafetyUiState())
    val nativeSafetyUi: StateFlow<NativeSafetyUiState> = mutableNativeSafetyUi.asStateFlow()

    init {
        refreshNativeSafety()
        viewModelScope.launch {
            launch(Dispatchers.IO) { dispatchCoordinator.retry() }
            launch(Dispatchers.IO) { refreshDispatchState() }
            DispatchCoordinator.changes.collect {
                refreshDispatchState()
            }
        }
    }

    fun startMonitoring() {
        MonitoringController.start(getApplication())
    }

    fun stopMonitoring() {
        MonitoringController.stop(getApplication())
    }

    fun setNativeSafetyEnabled(enabled: Boolean) {
        NativeSafetyHandoffController.setEnabled(getApplication(), enabled)
        refreshNativeSafety()
    }

    fun refreshNativeSafety() {
        val context = getApplication<Application>()
        mutableNativeSafetyUi.value = NativeSafetyUiState(
            enabled = NativeSafetyHandoffPreferences.isEnabled(context),
            accessibilityEnabled = AccessibilityServiceAvailability.isEnabled(context),
        )
        NativeSafetyHandoffController.refreshAvailability(context)
    }

    fun saveDispatchSettings(settings: DispatchSettings) {
        mutableDispatchUi.value = mutableDispatchUi.value.copy(saving = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = dispatchCoordinator.saveSettings(settings)
            val updated = DispatchUiState(
                settings = dispatchCoordinator.settings(),
                snapshot = dispatchCoordinator.snapshot(),
                message = result.exceptionOrNull()?.message
                    ?: if (settings.enabled) "Remote delivery enabled for new confirmations." else "Remote delivery is off; events remain local-only.",
                saving = false,
            )
            withContext(Dispatchers.Main.immediate) {
                mutableDispatchUi.value = updated
            }
        }
    }

    fun clearDispatchQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = dispatchCoordinator.clear()
            refreshDispatchState(result.exceptionOrNull()?.message)
        }
    }

    fun queueTestEvent() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelVersion = monitoringStatus.value.modelInfo.modelVersion
                .takeIf { it.isNotBlank() && it != "unavailable" }
                ?: "manual-test"
            val result = dispatchCoordinator.enqueue(
                marker = "Test event",
                confidence = 1f,
                modelVersion = modelVersion,
                test = true,
            )
            val message = result.fold(
                onSuccess = {
                    if (dispatchCoordinator.settings().enabled) {
                        "Test event queued for the configured receiver. A 2xx only means receiver acceptance."
                    } else {
                        "Test event stored local-only because remote delivery is disabled."
                    }
                },
                onFailure = { it.message },
            )
            refreshDispatchState(message)
        }
    }

    private suspend fun refreshDispatchState(message: String? = null) {
        val updated = withContext(Dispatchers.IO) {
            DispatchUiState(
                settings = dispatchCoordinator.settings(),
                snapshot = dispatchCoordinator.snapshot(),
                message = message,
                saving = false,
            )
        }
        withContext(Dispatchers.Main.immediate) {
            mutableDispatchUi.value = updated
        }
    }
}
