package com.aashishgodambe.arcana.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.domain.usecase.SyncAllPrices
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Weekly background price sync: fetches a fresh value for every item via the price seam and writes a
 * `WeeklySync` snapshot per success. Per-item best-effort lives inside [SyncAllPrices], so one bad fetch
 * can't fail the run. Scheduled as a 7-day [androidx.work.PeriodicWorkRequest] (network + unmetered) from
 * [com.aashishgodambe.arcana.ArcanaApplication].
 *
 * Price fetching is fine in the background; the on-device *summary* is not (Nano is foreground-only), so
 * it is regenerated when the app is next foregrounded — not here.
 */
@HiltWorker
class WeeklyPriceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncAllPrices: SyncAllPrices,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        syncAllPrices(SnapshotTrigger.WeeklySync)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val NAME = "weekly-price-sync"
    }
}
