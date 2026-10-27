package com.acousticguard.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchPolicyTest {
    @Test
    fun retryableFailuresUseBoundedExponentialBackoff() {
        val createdAt = 1_000L
        val first = DispatchPolicy.retryDecision(
            failureKind = DispatchFailureKind.IO,
            httpStatus = null,
            attemptCountAfterFailure = 1,
            createdAtEpochMs = createdAt,
            nowEpochMs = createdAt + 10_000L,
        )
        val second = DispatchPolicy.retryDecision(
            failureKind = DispatchFailureKind.HTTP,
            httpStatus = 429,
            attemptCountAfterFailure = 2,
            createdAtEpochMs = createdAt,
            nowEpochMs = createdAt + 10_000L,
        )

        assertTrue(first.retry)
        assertTrue(second.retry)
        assertEquals(DispatchPolicy.INITIAL_BACKOFF_MS, first.nextAttemptAtEpochMs - (createdAt + 10_000L))
        assertEquals(DispatchPolicy.INITIAL_BACKOFF_MS * 2, second.nextAttemptAtEpochMs - (createdAt + 10_000L))
        assertTrue(DispatchPolicy.backoffDelayMs(100) <= DispatchPolicy.MAX_BACKOFF_MS)
    }

    @Test
    fun nonRetryableStatusAndExpiredEventsAreTerminal() {
        val now = 10_000L
        val clientError = DispatchPolicy.retryDecision(
            failureKind = DispatchFailureKind.HTTP,
            httpStatus = 400,
            attemptCountAfterFailure = 1,
            createdAtEpochMs = 0L,
            nowEpochMs = now,
        )
        val expired = DispatchPolicy.retryDecision(
            failureKind = DispatchFailureKind.IO,
            httpStatus = null,
            attemptCountAfterFailure = 1,
            createdAtEpochMs = now - INCIDENT_TTL_MS,
            nowEpochMs = now,
        )

        assertFalse(clientError.retry)
        assertEquals("FAILED", clientError.terminalStatus)
        assertFalse(expired.retry)
        assertEquals("EXPIRED", expired.terminalStatus)
        assertTrue(DispatchPolicy.isExpired(now - INCIDENT_TTL_MS, now))
    }

    @Test
    fun snapshotPendingCountExcludesTerminalRecords() {
        val snapshot = DispatchSnapshot(
            records = listOf(
                IncidentSummary("00000000-0000-0000-0000-000000000001", 1L, "local", DispatchStatus.LOCAL_ONLY, false),
                IncidentSummary("00000000-0000-0000-0000-000000000002", 2L, "accepted", DispatchStatus.ACCEPTED, false),
                IncidentSummary("00000000-0000-0000-0000-000000000003", 3L, "failed", DispatchStatus.FAILED, false),
            ),
        )

        assertEquals(1, snapshot.pendingCount)
    }
}
