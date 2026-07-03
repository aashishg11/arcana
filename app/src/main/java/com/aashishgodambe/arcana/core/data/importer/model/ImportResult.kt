package com.aashishgodambe.arcana.core.data.importer.model

sealed interface ImportResult {
    data class Success(
        val items: List<ImportedItem>,
        val itemsParsed: Int,
        val itemsSkipped: Int,
        val warnings: List<String>,
    ) : ImportResult

    data class Failed(
        val cause: Throwable,
        val message: String,
    ) : ImportResult
}
