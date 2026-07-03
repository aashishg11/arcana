package com.aashishgodambe.arcana.core.data.repository

import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem

interface CollectibleRepository {

    /**
     * Persists imported items: each becomes a collectible row + (for Funko) a metadata row +
     * series junction rows (canonical, deduped) + one import-time value snapshot. Returns the
     * number of collectibles inserted.
     */
    suspend fun importFrom(items: List<ImportedItem>): Int
}
