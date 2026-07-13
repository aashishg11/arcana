package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ML Kit Text Recognition impl of [TextExtractor] (Latin script, bundled model — offline, no download).
 * The only place ML Kit vision types are imported; everything downstream sees the provider-agnostic
 * [TextExtraction]. Recognition runs off the main thread; ML Kit vision has no foreground/safety
 * constraints (unlike the AICore GenAI path), so this is safe from a worker or the cascade orchestrator.
 */
class MlKitTextExtractor @Inject constructor() : TextExtractor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun extract(bitmap: Bitmap): TextExtraction = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        // ML Kit occasionally throws a transient "Failed to run text recognizer" — e.g. right after subject
        // segmentation runs in the same process (seen in the cascade). A couple of retries clears it.
        var last: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val text = Tasks.await(recognizer.process(image))
                val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                    val b = line.boundingBox
                    RecognizedLine(
                        text = line.text,
                        box = if (b != null) BoundingBox(b.left, b.top, b.right, b.bottom) else BoundingBox.EMPTY,
                    )
                }
                return@withContext TextExtraction(fullText = text.text, lines = lines)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "text recognition attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
        throw last ?: IllegalStateException("text recognition failed")
    }

    private companion object {
        const val TAG = "MlKitTextExtractor"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 300L
    }
}
