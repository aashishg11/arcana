package com.aashishgodambe.arcana.feature.capture

import android.graphics.Bitmap
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * The hand-off between the camera and the capture Review screen. A captured frame is a [Bitmap], which
 * can't ride a navigation argument, so the camera stashes the frozen frame(s) here and Review [take]s them
 * on entry. Activity-retained so it survives the camera→review navigation and a config change, but is
 * scoped tightly enough that the (large) bitmaps are released when the capture flow leaves the back stack.
 *
 * [take] is one-shot — it clears the slot so a re-entered Review (e.g. process return) doesn't re-run a
 * stale capture, and so the bitmaps don't linger.
 */
@ActivityRetainedScoped
class CaptureSessionStore @Inject constructor() {
    private var pending: CapturePayload? = null

    fun put(payload: CapturePayload) {
        pending = payload
    }

    fun take(): CapturePayload? = pending.also { pending = null }
}

/** What the camera captured, and therefore which cascade entry point Review should drive. */
sealed interface CapturePayload {
    /**
     * The image path: one or more frozen frames for the vision cascade. [frames] is ordered — the first is
     * the primary; the rest are the escalation burst the engine only consults on a low-confidence read.
     */
    data class Image(val frames: List<Bitmap>) : CapturePayload

    /** The demoted barcode fallback: a single frame decoded for its UPC, skipping the vision stages. */
    data class Barcode(val frame: Bitmap) : CapturePayload
}
