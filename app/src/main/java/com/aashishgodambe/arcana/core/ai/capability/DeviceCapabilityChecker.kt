package com.aashishgodambe.arcana.core.ai.capability

import kotlinx.coroutines.flow.Flow

/** On-device (Gemini Nano) model readiness — app-level mirror of the SDK's `OnDeviceModelStatus`. */
enum class ModelReadiness {
    /** Provisioned and ready to serve on-device inference. */
    Available,

    /** Supported but not yet downloaded — call [DeviceCapabilityChecker.downloadOnDeviceModel]. */
    Downloadable,

    /** A download is in progress. */
    Downloading,

    /** Not supported on this device. */
    Unavailable,

    /** Status not yet determined (or the check failed). */
    Unknown,
}

/** Progress of an on-device model download. */
sealed interface ProvisioningProgress {
    /** [megabytes] downloaded so far (on-device provisioning bundles are hundreds of MB). */
    data class Downloading(val megabytes: Long) : ProvisioningProgress
    data object Completed : ProvisioningProgress
    data class Failed(val message: String) : ProvisioningProgress
}

/**
 * The seam features cross to ask "can this device run inference on-device, and if not, provision it?" — wraps
 * the Firebase on-device provisioning surface so features never touch SDK types. Backs the benchmark's
 * on-device readiness gate and (Week-4 Day-4) the Settings on-device-AI status readout.
 *
 * On-device provisioning and inference are both **foreground-gated**, so call these from a foreground scope.
 */
interface DeviceCapabilityChecker {
    /** One-shot current readiness of the on-device model. */
    suspend fun onDeviceReadiness(): ModelReadiness

    /**
     * Triggers a download of the on-device model, emitting progress until [ProvisioningProgress.Completed] or
     * [ProvisioningProgress.Failed]. `PREFER_ON_DEVICE` never triggers this itself — it silently uses cloud —
     * so this explicit call is the only way to (re)provision after the model un-provisions across updates.
     */
    fun downloadOnDeviceModel(): Flow<ProvisioningProgress>
}
