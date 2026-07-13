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

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build(),
    )

    override suspend fun segment(bitmap: Bitmap): SegmentationResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        // The segmentation model is an optional Play-services module fetched on first use; process()
        // fails fast with "Waiting for ... module to be downloaded" until it's ready (the download runs
        // in the background). Retry within a bounded window so first-ever use just waits for the one-time
        // download instead of erroring.
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val result = Tasks.await(segmenter.process(image))
                return@withContext SegmentationResult(subjectBitmap = result.foregroundBitmap)
            } catch (e: Exception) {
                if (!isModuleDownloading(e)) throw e
                Log.i(TAG, "segmentation module still downloading (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                delay(RETRY_DELAY_MS)
            }
        }
        // Still not ready after the wait window — treat as "no subject" so the cascade falls back to the
        // full frame rather than failing; the module will be ready on a later capture.
        Log.w(TAG, "segmentation module not ready after ${MAX_ATTEMPTS * RETRY_DELAY_MS}ms; skipping")
        SegmentationResult(subjectBitmap = null)
    }

    private fun isModuleDownloading(e: Throwable): Boolean =
        generateSequence<Throwable>(e) { it.cause }
            .any { it.message?.contains("to be downloaded", ignoreCase = true) == true }

    private companion object {
        const val TAG = "MlKitSegmenter"
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 3000L
    }
}
