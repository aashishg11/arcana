package com.aashishgodambe.arcana.core.ai.benchmark

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aashishgodambe.arcana.MainActivity
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provisions the Firebase-AI on-device (base Nano) model via the SDK's own [FirebaseAIOnDevice] surface —
 * `checkStatus()` + `download()`. The model can become un-provisioned across system updates, and
 * `PREFER_ON_DEVICE` then silently falls back to cloud without ever triggering a re-download; only an explicit
 * `download()` restores the on-device path. Run this to (re)provision the benchmark device.
 *
 *   JAVA_HOME="…/jbr" ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.aashishgodambe.arcana.core.ai.benchmark.OnDeviceModelProvisionTest
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelProvisionTest {

    @Test
    fun provisionOnDeviceModelUntilAvailable() {
        // Foreground an Activity — on-device provisioning, like inference, is foreground-gated.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            runBlocking {
                val before = FirebaseAIOnDevice.checkStatus()
                Log.i(TAG, "checkStatus (before) = $before")

                if (before != OnDeviceModelStatus.AVAILABLE) {
                    withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                        FirebaseAIOnDevice.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadStarted ->
                                    Log.i(TAG, "download started · ${status.bytesToDownload / (1024 * 1024)} MB to fetch")
                                is DownloadStatus.DownloadInProgress ->
                                    Log.i(TAG, "downloading · ${status.totalBytesDownloaded / (1024 * 1024)} MB")
                                is DownloadStatus.DownloadCompleted ->
                                    Log.i(TAG, "download completed")
                                is DownloadStatus.DownloadFailed ->
                                    Log.e(TAG, "download failed · $status")
                                else -> Log.i(TAG, "download status · $status")
                            }
                        }
                    } ?: Log.w(TAG, "download did not finish within ${DOWNLOAD_TIMEOUT_MS / 1000}s — may still be in progress")
                }

                val after = FirebaseAIOnDevice.checkStatus()
                Log.i(TAG, "checkStatus (after) = $after")
                assertEquals(
                    "on-device model not AVAILABLE after provisioning (see logcat for download progress)",
                    OnDeviceModelStatus.AVAILABLE,
                    after,
                )
            }
        }
    }

    private companion object {
        const val TAG = "OnDeviceProvision"
        const val DOWNLOAD_TIMEOUT_MS = 8L * 60 * 1000 // 8 min — big model bundle over WiFi
    }
}
