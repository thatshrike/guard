package com.acousticguard.dispatch

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Offline-first metadata outbox coordinator.
 *
 * Callers should invoke this class from an IO dispatcher. It never accepts audio, features,
 * location, or a model decision object; the caller supplies only the post-countdown metadata
 * contract below.
 */
class DispatchCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val queue = EncryptedIncidentQueue(appContext)

    @Volatile
    private var runtimeError: String? = null

    fun settings(): DispatchSettings = synchronized(DispatchRepositoryLock.monitor) {
        val result = queue.loadLocked()
        result.fold(
            onSuccess = { it.settings },
            onFailure = {
                runtimeError = STORAGE_ERROR
                DispatchSettings()
            },
        )
    }

    fun saveSettings(settings: DispatchSettings): Result<Unit> {
        val validated = DispatchValidation.validateSettings(settings)
            .getOrElse { return Result.failure(it) }

        synchronized(DispatchRepositoryLock.monitor) {
            val current = queue.loadLocked().getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            val updatedRecords = current.records.map { record ->
                when {
                    !settings.enabled && record.status == DispatchStatus.PENDING ->
                        record.copy(status = DispatchStatus.PAUSED, nextAttemptAtEpochMs = 0L)
                    settings.enabled && record.status == DispatchStatus.PAUSED && record.remoteOptIn ->
                        record.copy(status = DispatchStatus.PENDING)
                    else -> record
                }
            }
            val updated = current.copy(settings = validated.settings, records = updatedRecords)
            queue.saveLocked(updated).getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            emitChange()
            runtimeError = null

            if (!settings.enabled) {
                // The persisted disabled flag is the authoritative race guard for a worker that
                // has not begun its request yet. In-flight requests cannot be recalled.
                runCatching { DispatchWorkScheduler.cancelAll(appContext) }
            } else {
                schedulePendingLocked(updated)
            }
        }
        return Result.success(Unit)
    }

    fun enqueue(
        marker: String,
        confidence: Float,
        modelVersion: String,
        test: Boolean = false,
    ): Result<String> {
        DispatchValidation.validateEnqueue(marker, confidence, modelVersion)
            .getOrElse { return Result.failure(it) }

        synchronized(DispatchRepositoryLock.monitor) {
            val current = queue.loadLocked().getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            val now = System.currentTimeMillis()
            val retained = current.records.filterNot { DispatchPolicy.isExpired(it.createdAtEpochMs, now) }
            if (retained.size != current.records.size) {
                queue.saveLocked(current.copy(records = retained)).getOrElse {
                    runtimeError = STORAGE_ERROR
                    return Result.failure(DispatchStorageException(STORAGE_ERROR))
                }
                emitChange()
            }
            if (retained.size >= MAX_OUTBOX_RECORDS) {
                return Result.failure(DispatchValidationException("The dispatch outbox is full."))
            }

            val config = DispatchValidation.validateSettings(current.settings).getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            val id = UUID.randomUUID().toString()
            val remoteOptIn = current.settings.enabled
            val incident = StoredIncident(
                id = id,
                createdAtEpochMs = now,
                marker = marker,
                confidence = confidence,
                modelVersion = modelVersion,
                test = test,
                status = if (remoteOptIn) DispatchStatus.PENDING else DispatchStatus.LOCAL_ONLY,
                remoteOptIn = remoteOptIn,
                // Local-only records never retain a disabled receiver's endpoint, key, or token.
                endpoint = if (remoteOptIn) current.settings.endpoint else "",
                publicKeyPem = if (remoteOptIn) current.settings.publicKeyPem else "",
                recipientKeyId = if (remoteOptIn) config.recipientKeyId else "",
                bearerToken = if (remoteOptIn) current.settings.bearerToken else "",
            )
            val updated = current.copy(records = retained + incident)
            queue.saveLocked(updated).getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            emitChange()
            runtimeError = null

            if (remoteOptIn) {
                try {
                    DispatchWorkScheduler.schedule(appContext, id)
                } catch (_: Exception) {
                    // The durable record remains PENDING and retry() will recover it. The enqueue
                    // result describes durable admission, not the best-effort scheduler call.
                    runtimeError = SCHEDULING_ERROR
                }
            }
            return Result.success(id)
        }
    }

    fun snapshot(): DispatchSnapshot = synchronized(DispatchRepositoryLock.monitor) {
        val current = queue.loadLocked().getOrElse {
            runtimeError = STORAGE_ERROR
            return@synchronized DispatchSnapshot(error = STORAGE_ERROR)
        }
        val now = System.currentTimeMillis()
        val retained = current.records.filterNot { DispatchPolicy.isExpired(it.createdAtEpochMs, now) }
        if (retained.size != current.records.size) {
            queue.saveLocked(current.copy(records = retained)).fold(
                onSuccess = {
                    emitChange()
                    runtimeError = null
                },
                onFailure = {
                    runtimeError = STORAGE_ERROR
                    return@synchronized DispatchSnapshot(error = STORAGE_ERROR)
                },
            )
        }
        DispatchSnapshot(
            records = retained.map(StoredIncident::summary),
            error = runtimeError,
        )
    }

    fun clear(): Result<Unit> {
        synchronized(DispatchRepositoryLock.monitor) {
            val current = queue.loadLocked().getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            queue.saveLocked(current.copy(records = emptyList())).getOrElse {
                runtimeError = STORAGE_ERROR
                return Result.failure(DispatchStorageException(STORAGE_ERROR))
            }
            // Workers that race with this call re-read the durable record under the same lock and
            // therefore cannot rewrite a deleted event. A request already on the wire is allowed
            // to finish, but its completion update is ignored when the record is absent.
            runCatching { DispatchWorkScheduler.cancelAll(appContext) }
            emitChange()
            runtimeError = null
            return Result.success(Unit)
        }
    }

    fun retry() {
        synchronized(DispatchRepositoryLock.monitor) {
            val current = queue.loadLocked().getOrElse {
                runtimeError = STORAGE_ERROR
                return
            }
            val now = System.currentTimeMillis()
            val normalized = current.records
                .filterNot { DispatchPolicy.isExpired(it.createdAtEpochMs, now) }
                .map { record ->
                    when {
                        !current.settings.enabled && record.status == DispatchStatus.PENDING ->
                            record.copy(status = DispatchStatus.PAUSED, nextAttemptAtEpochMs = 0L)
                        current.settings.enabled && record.status == DispatchStatus.PAUSED && record.remoteOptIn ->
                            record.copy(status = DispatchStatus.PENDING)
                        record.status == DispatchStatus.SENDING && !DispatchRuntime.activeSends.contains(record.id) ->
                            record.copy(status = DispatchStatus.PENDING, sendingStartedAtEpochMs = 0L)
                        else -> record
                    }
                }
            val changed = normalized != current.records
            val updated = if (changed) current.copy(records = normalized) else current
            if (changed) {
                queue.saveLocked(updated).getOrElse {
                    runtimeError = STORAGE_ERROR
                    return
                }
                emitChange()
            }
            runtimeError = null
            if (!updated.settings.enabled) {
                runCatching { DispatchWorkScheduler.cancelAll(appContext) }
            } else {
                schedulePendingLocked(updated, now)
            }
        }
    }

    private fun schedulePendingLocked(state: QueueState, nowEpochMs: Long = System.currentTimeMillis()) {
        state.records
            .filter { it.remoteOptIn && it.status == DispatchStatus.PENDING }
            .forEach { record ->
                val delay = (record.nextAttemptAtEpochMs - nowEpochMs).coerceAtLeast(0L)
                runCatching { DispatchWorkScheduler.schedule(appContext, record.id, delay) }
                    .onFailure { runtimeError = SCHEDULING_ERROR }
            }
    }

    companion object {
        private const val STORAGE_ERROR = "Encrypted dispatch storage is unavailable."
        private const val SCHEDULING_ERROR = "Dispatch work could not be scheduled; retry will recover it."

        private val changeCounter = AtomicLong(System.currentTimeMillis())
        private val mutableChanges = MutableStateFlow(changeCounter.get())

        val changes: StateFlow<Long> = mutableChanges.asStateFlow()

        internal fun emitChange() {
            mutableChanges.value = changeCounter.incrementAndGet()
        }
    }
}
