package com.aashishgodambe.arcana.core.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules or cancels the [WeeklyPriceSyncWorker] — the single place that knows the request's cadence and
 * constraints. Used by [com.aashishgodambe.arcana.ArcanaApplication] on launch (only when the user hasn't
 * disabled it) and by the Settings toggle to turn the background sync on/off.
 */
@Singleton
class WeeklyPriceSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<WeeklyPriceSyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            )
            .build()
        // KEEP: an existing schedule isn't reset on relaunch; only a real enable re-creates it.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeeklyPriceSyncWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WeeklyPriceSyncWorker.NAME)
    }

    // --- Manual "Sync now" as a background job (WorkManager), so it survives lock/background/process death ---

    /** Enqueue the manual "Sync now" as a one-time background job (any connected network). KEEP → a second
     *  tap while it's already running is a no-op, not a stacked duplicate. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncNowWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncNowWorker.NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel a running/enqueued manual sync — a clean stop that WorkManager won't retry (unlike a
     *  force-stop, which leaves the interrupted job to re-run on next launch). Snapshots already written
     *  stay; only the remaining items are skipped. */
    fun cancelSyncNow() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncNowWorker.NAME)
    }

    /** True while the manual "Sync now" job is enqueued or running — backs the Portfolio's "Syncing…" state. */
    fun syncNowRunning(): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncNowWorker.NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    /** Emits once each time a manual "Sync now" reaches SUCCEEDED — the cue to refresh the on-device summary. */
    fun syncNowCompletions(): Flow<Unit> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncNowWorker.NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.SUCCEEDED } }
            .distinctUntilChanged()
            .filter { it }
            .map { }
}
