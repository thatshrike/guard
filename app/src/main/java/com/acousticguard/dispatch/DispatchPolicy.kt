package com.acousticguard.dispatch

internal enum class DispatchFailureKind {
    IO,
    HTTP,
}

internal data class RetryDecision(
    val retry: Boolean,
    val nextAttemptAtEpochMs: Long = 0L,
    val terminalStatus: String? = null,
)

internal object DispatchPolicy {
    const val MAX_ATTEMPTS = 5
    const val INITIAL_BACKOFF_MS = 30L * 1_000L
    const val MAX_BACKOFF_MS = 6L * 60L * 60L * 1_000L

    fun isExpired(createdAtEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs >= createdAtEpochMs && nowEpochMs - createdAtEpochMs >= INCIDENT_TTL_MS

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    fun backoffDelayMs(attemptCountAfterFailure: Int): Long {
        val exponent = (attemptCountAfterFailure - 1).coerceIn(0, 20)
        val multiplier = 1L shl exponent
        return (INITIAL_BACKOFF_MS * multiplier).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun retryDecision(
        failureKind: DispatchFailureKind,
        httpStatus: Int?,
        attemptCountAfterFailure: Int,
        createdAtEpochMs: Long,
        nowEpochMs: Long,
    ): RetryDecision {
        if (isExpired(createdAtEpochMs, nowEpochMs)) {
            return RetryDecision(retry = false, terminalStatus = DispatchStatus.EXPIRED)
        }

        val retryable = when (failureKind) {
            DispatchFailureKind.IO -> true
            DispatchFailureKind.HTTP -> httpStatus?.let(::isRetryableHttpStatus) == true
        }
        if (!retryable || attemptCountAfterFailure >= MAX_ATTEMPTS) {
            return RetryDecision(retry = false, terminalStatus = DispatchStatus.FAILED)
        }

        val nextAttemptAt = nowEpochMs + backoffDelayMs(attemptCountAfterFailure)
        if (isExpired(createdAtEpochMs, nextAttemptAt)) {
            return RetryDecision(retry = false, terminalStatus = DispatchStatus.EXPIRED)
        }
        return RetryDecision(retry = true, nextAttemptAtEpochMs = nextAttemptAt)
    }
}
