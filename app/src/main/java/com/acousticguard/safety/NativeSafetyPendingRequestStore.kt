package com.acousticguard.safety

import android.annotation.SuppressLint
import android.content.Context

/**
 * Short-lived same-app handoff buffer used to cover the accessibility-service binding race.
 * It contains only the local incident id and marker, and expires quickly if the service never
 * connects.
 */
data class PendingNativeSafetyRequest(
    val incidentId: String?,
    val marker: String,
)

object NativeSafetyPendingRequestStore {
    private const val FILE_NAME = "native_safety_handoff"
    private const val KEY_INCIDENT_ID = "pending_incident_id"
    private const val KEY_MARKER = "pending_marker"
    private const val KEY_CREATED_AT = "pending_created_at"
    private const val MAX_AGE_MS = 30_000L

    @SuppressLint("ApplySharedPref")
    fun save(context: Context, request: PendingNativeSafetyRequest) {
        preferences(context).edit()
            .putString(KEY_INCIDENT_ID, request.incidentId)
            .putString(KEY_MARKER, request.marker.take(128))
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .commit()
    }

    fun consumeIfFresh(context: Context): PendingNativeSafetyRequest? {
        val prefs = preferences(context)
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        val marker = prefs.getString(KEY_MARKER, null)
        if (marker.isNullOrBlank() || createdAt <= 0L || System.currentTimeMillis() - createdAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        val request = PendingNativeSafetyRequest(
            incidentId = prefs.getString(KEY_INCIDENT_ID, null),
            marker = marker,
        )
        clear(context)
        return request
    }

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(KEY_INCIDENT_ID)
            .remove(KEY_MARKER)
            .remove(KEY_CREATED_AT)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
