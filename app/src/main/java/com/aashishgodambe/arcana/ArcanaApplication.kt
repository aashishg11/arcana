package com.aashishgodambe.arcana

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aashishgodambe.arcana.core.data.settings.SettingsStore
import com.aashishgodambe.arcana.core.data.worker.EmbeddingIndexScheduler
import com.aashishgodambe.arcana.core.data.worker.WeeklyPriceSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ArcanaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var syncScheduler: WeeklyPriceSyncScheduler
    @Inject lateinit var indexScheduler: EmbeddingIndexScheduler

    /** On-demand WorkManager init so the worker can be @HiltWorker-injected (SyncAllPrices). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // App Check gates Firebase AI Logic cloud calls (auto-enforced on the Gemini API since early
        // July 2026). Debug builds install the debug provider here; release ships a no-op twin.
        installAppCheck(this)
        // Respect the user's Settings choice; the toggle schedules/cancels directly when changed.
        if (settings.weeklyWorkerEnabled.value) syncScheduler.schedule() else syncScheduler.cancel()
        // Build/refresh the RAG vector index on launch (incremental → cheap after the first full build).
        indexScheduler.enqueue()
    }
}
