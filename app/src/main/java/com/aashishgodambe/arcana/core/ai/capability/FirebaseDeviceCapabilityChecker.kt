package com.aashishgodambe.arcana.core.ai.capability

import android.util.Log
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.ai.ondevice.FirebaseAIOnDevice
import com.google.firebase.ai.ondevice.OnDeviceModelStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [DeviceCapabilityChecker] backed by [FirebaseAIOnDevice] — the SDK's own on-device provisioning surface.
 * Maps SDK status/download types to the app-level [ModelReadiness]/[ProvisioningProgress] so nothing above
 * this seam imports Firebase types.
 */
class FirebaseDeviceCapabilityChecker : DeviceCapabilityChecker {

    override suspend fun onDeviceReadiness(): ModelReadiness =
        try {
            when (FirebaseAIOnDevice.checkStatus()) {
                OnDeviceModelStatus.AVAILABLE -> ModelReadiness.Available
                OnDeviceModelStatus.DOWNLOADABLE -> ModelReadiness.Downloadable
                OnDeviceModelStatus.DOWNLOADING -> ModelReadiness.Downloading
                OnDeviceModelStatus.UNAVAILABLE -> ModelReadiness.Unavailable
                else -> ModelReadiness.Unknown
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkStatus failed", t)
            ModelReadiness.Unknown
        }

    override fun downloadOnDeviceModel(): Flow<ProvisioningProgress> =
        FirebaseAIOnDevice.download()
            .map { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> ProvisioningProgress.Downloading(status.bytesToDownload.toMb())
                    is DownloadStatus.DownloadInProgress -> ProvisioningProgress.Downloading(status.totalBytesDownloaded.toMb())
                    is DownloadStatus.DownloadCompleted -> ProvisioningProgress.Completed
                    is DownloadStatus.DownloadFailed -> ProvisioningProgress.Failed("Download failed")
                    else -> ProvisioningProgress.Downloading(0)
                }
            }
            .catch { t ->
                Log.e(TAG, "download failed", t)
                emit(ProvisioningProgress.Failed(t.message ?: "Download failed"))
            }

    private fun Long.toMb(): Long = this / (1024 * 1024)

    private companion object {
        const val TAG = "DeviceCapability"
    }
}
