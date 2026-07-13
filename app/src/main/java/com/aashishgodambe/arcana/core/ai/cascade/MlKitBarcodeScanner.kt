package com.aashishgodambe.arcana.core.ai.cascade

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ML Kit Barcode Scanning impl of [BarcodeScanner] (bundled model, offline). Restricts to the retail
 * product formats on a Funko box (UPC-A/E, EAN-13/8) and returns the first decoded value. The client is
 * created and closed per call, matching the cascade's other ML Kit stages.
 */
class MlKitBarcodeScanner @Inject constructor() : BarcodeScanner {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
        )
        .build()

    override suspend fun scan(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val scanner = BarcodeScanning.getClient(options)
        try {
            Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)))
                .firstNotNullOfOrNull { it.rawValue }
        } finally {
            scanner.close()
        }
    }
}
