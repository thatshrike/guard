package com.acousticguard.dispatch

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object DispatchWorkScheduler {
    const val TAG = "acoustic_guard_dispatch"
    const val EVENT_ID_INPUT = "event_id"

    fun schedule(context: Context, eventId: String, delayMs: Long = 0L) {
        val request = OneTimeWorkRequestBuilder<DispatchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(EVENT_ID_INPUT to eventId))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DispatchPolicy.INITIAL_BACKOFF_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG)
            .apply {
                if (delayMs > 0L) setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName(eventId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
    }

    private fun uniqueName(eventId: String): String = "$TAG-$eventId"
}
