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
 * The manual "Sync now" as a one-time WorkManager job (`trigger = UserRefresh`). Running it here — not in
 * the Portfolio ViewModel's scope — makes the ~500-item eBay sweep **background-safe**: it survives app
 * backgrounding, screen lock, and process death, so it completes whether or not the user stays in the app.
 *
 * Unlike [WeeklyPriceSyncWorker] it does **not** stamp the weekly last-run time (a manual sync isn't a
 * weekly run). The on-device summary refresh stays in the foreground ViewModel (Nano is foreground-only).
 */
@HiltWorker
class SyncNowWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncAllPrices: SyncAllPrices,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        syncAllPrices(SnapshotTrigger.UserRefresh)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val NAME = "manual-price-sync"
    }
}
