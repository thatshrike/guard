package com.acousticguard.dispatch

import java.util.concurrent.ConcurrentHashMap

/** User-controlled dispatch configuration. Empty fields are allowed while disabled. */
data class DispatchSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val publicKeyPem: String = "",
    val bearerToken: String = "",
)

data class DispatchSnapshot(
    val records: List<IncidentSummary> = emptyList(),
    val error: String? = null,
)

data class IncidentSummary(
    val id: String,
    val createdAtEpochMs: Long,
    val marker: String,
    val status: String,
    val test: Boolean,
)

internal const val MAX_OUTBOX_RECORDS = 64
internal const val INCIDENT_TTL_MS = 24L * 60L * 60L * 1_000L
internal const val MAX_MARKER_LENGTH = 256
internal const val MAX_MODEL_VERSION_LENGTH = 128
internal const val MAX_ENDPOINT_LENGTH = 2_048
internal const val MAX_PUBLIC_KEY_PEM_LENGTH = 16_384
internal const val MAX_BEARER_TOKEN_LENGTH = 4_096
internal const val MAX_ERROR_LENGTH = 512

internal object DispatchStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val PAUSED = "PAUSED"
    const val SENDING = "SENDING"
    const val ACCEPTED = "ACCEPTED"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"

    val all: Set<String> = setOf(LOCAL_ONLY, PENDING, PAUSED, SENDING, ACCEPTED, FAILED, EXPIRED)
}

internal data class StoredIncident(
    val id: String,
    val createdAtEpochMs: Long,
    val marker: String,
    val confidence: Float,
    val modelVersion: String,
    val test: Boolean,
    val status: String,
    val remoteOptIn: Boolean,
    val endpoint: String,
    val publicKeyPem: String,
    val recipientKeyId: String,
    val bearerToken: String,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long = 0L,
    val sendingStartedAtEpochMs: Long = 0L,
    val lastError: String? = null,
) {
    fun summary(): IncidentSummary = IncidentSummary(
        id = id,
        createdAtEpochMs = createdAtEpochMs,
        marker = marker,
        status = status,
        test = test,
    )
}

internal data class QueueState(
    val settings: DispatchSettings = DispatchSettings(),
    val records: List<StoredIncident> = emptyList(),
)

internal object DispatchRepositoryLock {
    val monitor = Any()
}

/** In-memory claims prevent a coordinator created in the same process from reclaiming a live send. */
internal object DispatchRuntime {
    val activeSends: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

internal class DispatchValidationException(message: String) : IllegalArgumentException(message)

internal class DispatchStorageException(message: String) : IllegalStateException(message)

internal class DispatchEnvelopeException(message: String) : IllegalArgumentException(message)
