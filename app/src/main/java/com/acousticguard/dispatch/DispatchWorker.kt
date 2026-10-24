package com.acousticguard.dispatch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface DispatchClaim {
    data class Send(val record: StoredIncident) : DispatchClaim

    data object Skip : DispatchClaim
}

class DispatchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(DispatchWorkScheduler.EVENT_ID_INPUT)
            ?: return Result.failure()
        val queue = EncryptedIncidentQueue(applicationContext)
        val claim = claim(queue, eventId, System.currentTimeMillis())
            .getOrElse { return Result.failure() }
        if (claim !is DispatchClaim.Send) return Result.success()

        val record = claim.record
        return try {
            val sendResult = try {
                withContext(Dispatchers.IO) { DispatchTransport().send(record) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DispatchSendResult.Retryable(ioFailure = true)
            }
            finish(queue, record.id, sendResult, System.currentTimeMillis())
        } finally {
            // Completion is persisted while the claim is still represented by SENDING. The
            // in-memory marker is removed only after the durable transition has completed.
            DispatchRuntime.activeSends.remove(record.id)
        }
    }

    private fun claim(queue: EncryptedIncidentQueue, eventId: String, nowEpochMs: Long): kotlin.Result<DispatchClaim> =
        synchronized(DispatchRepositoryLock.monitor) {
            val state = queue.loadLocked().getOrElse { return kotlin.Result.failure(it) }
            val record = state.records.firstOrNull { it.id == eventId } ?: return kotlin.Result.success(DispatchClaim.Skip)
            if (DispatchPolicy.isExpired(record.createdAtEpochMs, nowEpochMs)) {
                queue.saveLocked(state.copy(records = state.records.filterNot { it.id == eventId }))
                    .getOrElse { return kotlin.Result.failure(it) }
                DispatchCoordinator.emitChange()
                return kotlin.Result.success(DispatchClaim.Skip)
            }
            if (!state.settings.enabled || !record.remoteOptIn) {
                if (record.status == DispatchStatus.PENDING) {
                    queue.saveLocked(
                        state.copy(
                            records = state.records.map {
                                if (it.id == eventId) it.copy(status = DispatchStatus.PAUSED, nextAttemptAtEpochMs = 0L) else it
                            },
                        ),
                    ).getOrElse { return kotlin.Result.failure(it) }
                    DispatchCoordinator.emitChange()
                }
                return kotlin.Result.success(DispatchClaim.Skip)
            }
            if (record.status == DispatchStatus.ACCEPTED || record.status == DispatchStatus.FAILED ||
                record.status == DispatchStatus.LOCAL_ONLY || record.status == DispatchStatus.EXPIRED
            ) {
                return kotlin.Result.success(DispatchClaim.Skip)
            }
            if (record.nextAttemptAtEpochMs > nowEpochMs) return kotlin.Result.success(DispatchClaim.Skip)
            if (record.status == DispatchStatus.SENDING && DispatchRuntime.activeSends.contains(eventId)) {
                return kotlin.Result.success(DispatchClaim.Skip)
            }
            if (DispatchValidation.validateSettings(
                    DispatchSettings(true, record.endpoint, record.publicKeyPem, record.bearerToken),
                ).isFailure
            ) {
                val failed = record.copy(status = DispatchStatus.FAILED, lastError = "Stored dispatch configuration is invalid.")
                queue.saveLocked(state.copy(records = state.records.map { if (it.id == eventId) failed else it }))
                    .getOrElse { return kotlin.Result.failure(it) }
                DispatchCoordinator.emitChange()
                return kotlin.Result.success(DispatchClaim.Skip)
            }

            DispatchRuntime.activeSends.add(eventId)
            val sending = record.copy(
                status = DispatchStatus.SENDING,
                sendingStartedAtEpochMs = nowEpochMs,
                lastError = null,
            )
            queue.saveLocked(state.copy(records = state.records.map { if (it.id == eventId) sending else it }))
                .getOrElse {
                    DispatchRuntime.activeSends.remove(eventId)
                    return kotlin.Result.failure(it)
                }
            DispatchCoordinator.emitChange()
            kotlin.Result.success(DispatchClaim.Send(sending))
        }

    private fun finish(
        queue: EncryptedIncidentQueue,
        eventId: String,
        sendResult: DispatchSendResult,
        nowEpochMs: Long,
    ): Result {
        synchronized(DispatchRepositoryLock.monitor) {
            val state = queue.loadLocked().getOrElse { return Result.failure() }
            val record = state.records.firstOrNull { it.id == eventId } ?: return Result.success()
            if (record.status != DispatchStatus.SENDING) return Result.success()

            val replacement = when (sendResult) {
                DispatchSendResult.Accepted -> record.copy(
                    status = DispatchStatus.ACCEPTED,
                    sendingStartedAtEpochMs = 0L,
                    nextAttemptAtEpochMs = 0L,
                    lastError = null,
                )
                is DispatchSendResult.Terminal -> record.copy(
                    status = DispatchStatus.FAILED,
                    sendingStartedAtEpochMs = 0L,
                    nextAttemptAtEpochMs = 0L,
                    lastError = sendResult.reason.take(MAX_ERROR_LENGTH),
                )
                is DispatchSendResult.Retryable -> {
                    val attemptCount = record.attemptCount + 1
                    val decision = DispatchPolicy.retryDecision(
                        failureKind = if (sendResult.ioFailure) DispatchFailureKind.IO else DispatchFailureKind.HTTP,
                        httpStatus = sendResult.httpStatus,
                        attemptCountAfterFailure = attemptCount,
                        createdAtEpochMs = record.createdAtEpochMs,
                        nowEpochMs = nowEpochMs,
                    )
                    if (!state.settings.enabled) {
                        record.copy(
                            status = DispatchStatus.PAUSED,
                            attemptCount = attemptCount.coerceAtMost(DispatchPolicy.MAX_ATTEMPTS),
                            sendingStartedAtEpochMs = 0L,
                            nextAttemptAtEpochMs = 0L,
                            lastError = "Dispatch is disabled.",
                        )
                    } else if (decision.retry) {
                        record.copy(
                            status = DispatchStatus.PENDING,
                            attemptCount = attemptCount,
                            sendingStartedAtEpochMs = 0L,
                            nextAttemptAtEpochMs = decision.nextAttemptAtEpochMs,
                            lastError = "The dispatch attempt will be retried.",
                        )
                    } else {
                        record.copy(
                            status = decision.terminalStatus ?: DispatchStatus.FAILED,
                            attemptCount = attemptCount.coerceAtMost(DispatchPolicy.MAX_ATTEMPTS),
                            sendingStartedAtEpochMs = 0L,
                            nextAttemptAtEpochMs = 0L,
                            lastError = "The dispatch attempt was not accepted.",
                        )
                    }
                }
            }
            queue.saveLocked(state.copy(records = state.records.map { if (it.id == eventId) replacement else it }))
                .getOrElse { return Result.failure() }
            DispatchCoordinator.emitChange()
            if (replacement.status == DispatchStatus.PENDING) {
                runCatching {
                    DispatchWorkScheduler.schedule(
                        applicationContext,
                        eventId,
                        (replacement.nextAttemptAtEpochMs - nowEpochMs).coerceAtLeast(0L),
                    )
                }
            }
            return when (replacement.status) {
                DispatchStatus.PENDING -> Result.retry()
                DispatchStatus.FAILED -> Result.failure()
                else -> Result.success()
            }
        }
    }
}
