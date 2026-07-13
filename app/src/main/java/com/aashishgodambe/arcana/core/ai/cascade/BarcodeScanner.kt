package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap

/**
 * Cascade barcode seam: decode a product barcode (UPC/EAN) from a frame. The demoted fallback path — its
 * value feeds the catalog chain's UPC lookup directly, skipping segmentation, description, and OCR.
 * Returns null when no barcode is found.
 */
interface BarcodeScanner {
    suspend fun scan(bitmap: Bitmap): String?
}
