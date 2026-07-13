package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ML Kit Subject Segmentation impl of [ImageSegmenter]. `enableForegroundBitmap()` yields the subject
 * with a transparent background — exactly the masked bitmap the classification stage and capture UI
 * need. The only place ML Kit segmentation types are imported.
 *
 * Unlike text recognition, this model is **not bundled**: Play services downloads it on first use, so
 * the first call needs a network connection and may block while it downloads. ML Kit vision has no
 * foreground/safety constraints, so this is safe off the main thread.
 */
class MlKitImageSegmenter @Inject constructor() : ImageSegmenter {

    override suspend fun segment(bitmap: Bitmap): SegmentationResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        // Create the client per call and close it before returning: leaving the segmenter open holds a
        // native ML Kit resource that breaks a subsequent text recognizer in the same cascade run.
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build(),
        )
        try {
            // The segmentation model is an optional Play-services module fetched on first use; process()
            // fails fast with "Waiting for ... module to be downloaded" until it's ready (the download runs
            // in the background). Retry within a bounded window so first-ever use waits for the one-time
            // download instead of erroring.
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    val result = Tasks.await(segmenter.process(image))
                    // ML Kit returns the subject on a full-frame-sized transparent canvas, so the subject
                    // is tiny within it. Crop to the opaque bounds so the capture UI shows the item large.
                    val subject = result.foregroundBitmap?.let(::cropToContent)
                    return@withContext SegmentationResult(subjectBitmap = subject)
                } catch (e: Exception) {
                    if (!isModuleDownloading(e)) throw e
                    Log.i(TAG, "segmentation module still downloading (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                    delay(RETRY_DELAY_MS)
                }
            }
            // Not ready after the wait window — treat as "no subject" so the cascade carries on; the module
            // will be ready on a later capture.
            Log.w(TAG, "segmentation module not ready after ${MAX_ATTEMPTS * RETRY_DELAY_MS}ms; skipping")
            SegmentationResult(subjectBitmap = null)
        } finally {
            segmenter.close()
        }
    }

    /**
     * Crop [src] to the bounding box of its non-transparent pixels so the isolated subject fills the frame
     * instead of floating tiny in a full-frame transparent canvas. Bounds are found on a ≤512px downscale
     * (one [Bitmap.getPixels] instead of millions of [Bitmap.getPixel] calls), then mapped back and applied
     * to the full-res original. Returns [src] unchanged if nothing opaque is found.
     */
    private fun cropToContent(src: Bitmap): Bitmap {
        val scale = SCAN_MAX.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1)
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, false)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small != src) small.recycle()

        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if ((px[y * sw + x] ushr 24) != 0) {          // alpha != 0 → part of the subject
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return src

        val inv = 1f / scale
        val padX = ((maxX - minX) * inv * PAD_FRACTION).toInt()
        val padY = ((maxY - minY) * inv * PAD_FRACTION).toInt()
        val left = ((minX * inv).toInt() - padX).coerceAtLeast(0)
        val top = ((minY * inv).toInt() - padY).coerceAtLeast(0)
        val right = (((maxX + 1) * inv).toInt() + padX).coerceAtMost(src.width)
        val bottom = (((maxY + 1) * inv).toInt() + padY).coerceAtMost(src.height)
        return if (right > left && bottom > top) Bitmap.createBitmap(src, left, top, right - left, bottom - top) else src
    }

    private fun isModuleDownloading(e: Throwable): Boolean =
        generateSequence<Throwable>(e) { it.cause }
            .any { it.message?.contains("to be downloaded", ignoreCase = true) == true }

    private companion object {
        const val TAG = "MlKitSegmenter"
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 3000L
        const val SCAN_MAX = 512            // longest edge of the downscale used to find opaque bounds
        const val PAD_FRACTION = 0.04f      // breathing room around the cropped subject
    }
}
