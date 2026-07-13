package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap

/**
 * Cascade OCR seam: reads text off a captured (or segmented) image. Features and the cascade depend on
 * this interface, never on ML Kit — the v1 impl [MlKitTextExtractor] is the only place ML Kit vision
 * types are imported. Tests bind a fake; the [PopNumberParser] that turns this into a Pop number is a
 * separate, pure step.
 */
interface TextExtractor {
    suspend fun extract(bitmap: Bitmap): TextExtraction
}
