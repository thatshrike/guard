package com.acousticguard.safety

import android.content.Context
import android.content.Intent

enum class NativeSafetyHandoffRequestResult {
    NOT_REQUESTED,
    DISABLED,
    ACCESSIBILITY_REQUIRED,
    REQUESTED,
}

/** Coordinates explicit user opt-in and the one-shot request sent to the bound accessibility service. */
object NativeSafetyHandoffController {
    const val ACTION_REQUEST_NATIVE_HANDOFF = "com.acousticguard.action.REQUEST_NATIVE_HANDOFF"
    const val EXTRA_INCIDENT_ID = "com.acousticguard.extra.INCIDENT_ID"
    const val EXTRA_MARKER = "com.acousticguard.extra.MARKER"

    fun setEnabled(context: Context, enabled: Boolean) {
        NativeSafetyHandoffPreferences.setEnabled(context, enabled)
        if (!enabled) NativeSafetyPendingRequestStore.clear(context)
        refreshAvailability(context)
    }

    fun refreshAvailability(context: Context) {
        val appContext = context.applicationContext
        when {
            !NativeSafetyHandoffPreferences.isEnabled(appContext) -> {
                NativeSafetyHandoffStatusStore.update(
                    NativeSafetyHandoffState(
                        phase = NativeSafetyHandoffPhase.DISABLED,
                        message = "Native safety handoff is disabled.",
                    ),
                )
            }

            !AccessibilityServiceAvailability.isEnabled(appContext) -> {
                NativeSafetyHandoffStatusStore.update(
                    NativeSafetyHandoffState(
                        phase = NativeSafetyHandoffPhase.ACCESSIBILITY_REQUIRED,
                        message = "Enable the AcousticGuard accessibility service in Android Settings before a native handoff can run.",
                    ),
                )
            }

            NativeSafetyHandoffStatusStore.state.value.phase in setOf(
                NativeSafetyHandoffPhase.DISABLED,
                NativeSafetyHandoffPhase.ACCESSIBILITY_REQUIRED,
                NativeSafetyHandoffPhase.FAILED,
            ) -> {
                NativeSafetyHandoffStatusStore.update(
                    NativeSafetyHandoffState(
                        phase = NativeSafetyHandoffPhase.READY,
                        message = "Native handoff is armed for confirmed alerts only.",
                    ),
                )
            }
        }
    }

    fun request(
        context: Context,
        incidentId: String?,
        marker: String,
    ): NativeSafetyHandoffRequestResult {
        val appContext = context.applicationContext
        if (!NativeSafetyHandoffPreferences.isEnabled(appContext)) {
            NativeSafetyHandoffStatusStore.update(
                NativeSafetyHandoffState(
                    phase = NativeSafetyHandoffPhase.DISABLED,
                    incidentId = incidentId,
                    message = "Confirmed alert stored, but native safety handoff is disabled.",
                ),
            )
            return NativeSafetyHandoffRequestResult.DISABLED
        }
        if (!AccessibilityServiceAvailability.isEnabled(appContext)) {
            NativeSafetyHandoffStatusStore.update(
                NativeSafetyHandoffState(
                    phase = NativeSafetyHandoffPhase.ACCESSIBILITY_REQUIRED,
                    incidentId = incidentId,
                    message = "Confirmed alert stored, but Android accessibility access is not enabled.",
                ),
            )
            return NativeSafetyHandoffRequestResult.ACCESSIBILITY_REQUIRED
        }

        val request = Intent(ACTION_REQUEST_NATIVE_HANDOFF)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_INCIDENT_ID, incidentId)
            .putExtra(EXTRA_MARKER, marker.take(128))
        NativeSafetyPendingRequestStore.save(
            appContext,
            PendingNativeSafetyRequest(
                incidentId = incidentId,
                marker = marker,
            ),
        )
        appContext.sendBroadcast(request)
        NativeSafetyHandoffStatusStore.update(
            NativeSafetyHandoffState(
                phase = NativeSafetyHandoffPhase.HANDOFF_REQUESTED,
                incidentId = incidentId,
                message = "Requesting the Android emergency interface. Complete any system confirmation shown on screen.",
            ),
        )
        return NativeSafetyHandoffRequestResult.REQUESTED
    }
}
