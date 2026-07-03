package com.aashishgodambe.arcana.core.data.importer

import android.net.Uri
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult

/**
 * Parses an external collection export into [ImportResult]. One impl per source format.
 * Failure is lenient: a single bad row is skipped with a warning; the rest succeed.
 */
interface CollectionImporter {
    val sourceName: String
    val supportedMimeTypes: Set<String>

    suspend fun parse(uri: Uri): ImportResult
}
