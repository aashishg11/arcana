package com.aashishgodambe.arcana

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ArcanaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** On-demand WorkManager init so the worker can be @HiltWorker-injected (SyncAllPrices). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        scheduleWeeklyPriceSync()
    }

    private fun scheduleWeeklyPriceSync() {
        val request = PeriodicWorkRequestBuilder<WeeklyPriceSyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            )
            .build()
        // KEEP: don't reset the schedule on every launch. (A Settings toggle to cancel is a later add.)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WeeklyPriceSyncWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
