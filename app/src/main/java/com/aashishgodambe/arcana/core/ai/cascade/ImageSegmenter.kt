package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap

/**
 * Cascade segmentation seam: isolates the collectible from its background, producing the masked subject
 * bitmap the classification stage sees and the Week-9 capture UI draws an outline around. Features and
 * the cascade depend on this interface; the v1 impl [MlKitImageSegmenter] is the only place ML Kit
 * subject-segmentation types are imported.
 */
interface ImageSegmenter {
    suspend fun segment(bitmap: Bitmap): SegmentationResult
}

/**
 * Result of segmentation. [subjectBitmap] is the input with the background removed (transparent), or
 * null when no salient subject was found — the cascade must treat "no subject" as a normal outcome
 * (e.g. a cluttered shelf) and fall back to the full frame, not an error.
 */
data class SegmentationResult(val subjectBitmap: Bitmap?) {
    val hasSubject: Boolean get() = subjectBitmap != null
}
