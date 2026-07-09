package com.aashishgodambe.arcana.core.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
}
