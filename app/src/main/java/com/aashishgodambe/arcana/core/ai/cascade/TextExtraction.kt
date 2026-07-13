package com.aashishgodambe.arcana.core.ai.cascade

/**
 * Pure geometry for a recognized text region — no android/ML Kit types, so [PopNumberParser] and its
 * tests stay JVM-only. Height is the load-bearing field: the Pop number is the visually largest number
 * on a Funko box, so line height is how the parser tells `406` from the year, edition size, and age
 * warnings that also appear as digits.
 */
data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        val EMPTY = BoundingBox(0, 0, 0, 0)
    }
}

/** One line of recognized text with where it sits on the image. */
data class RecognizedLine(val text: String, val box: BoundingBox)

/**
 * Raw output of the OCR stage: the full recognized text plus per-line text + geometry. Provider-agnostic
 * (no ML Kit types) so features, the parser, and the cascade depend on this — never on ML Kit directly.
 */
data class TextExtraction(val fullText: String, val lines: List<RecognizedLine>) {
    companion object {
        val EMPTY = TextExtraction("", emptyList())
    }
}
