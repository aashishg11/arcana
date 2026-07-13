package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
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
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val lines = text.textBlocks.flatMap { it.lines }.map { line ->
            val b = line.boundingBox
            RecognizedLine(
                text = line.text,
                box = if (b != null) BoundingBox(b.left, b.top, b.right, b.bottom) else BoundingBox.EMPTY,
            )
        }
        TextExtraction(fullText = text.text, lines = lines)
    }
}
