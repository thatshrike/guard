package com.acousticguard.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime state for the optional native emergency-interface handoff.
 *
 * This is deliberately separate from the encrypted incident outbox. The handoff state is
 * transient UI/runtime state and never contains audio, location, contacts, or credentials.
 */
enum class NativeSafetyHandoffPhase {
    DISABLED,
    ACCESSIBILITY_REQUIRED,
    READY,
    HANDOFF_REQUESTED,
    EMERGENCY_INTERFACE_OPENED,
    USER_ACTION_REQUIRED,
    FAILED,
}

data class NativeSafetyHandoffState(
    val phase: NativeSafetyHandoffPhase = NativeSafetyHandoffPhase.DISABLED,
    val incidentId: String? = null,
    val message: String = "Native safety handoff is disabled.",
)

object NativeSafetyHandoffStatusStore {
    private val mutableState = MutableStateFlow(NativeSafetyHandoffState())
    val state: StateFlow<NativeSafetyHandoffState> = mutableState.asStateFlow()

    fun update(value: NativeSafetyHandoffState) {
        mutableState.value = value
    }

    fun reset() {
        mutableState.value = NativeSafetyHandoffState()
    }
}
