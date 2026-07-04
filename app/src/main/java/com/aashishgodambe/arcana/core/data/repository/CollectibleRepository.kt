package com.aashishgodambe.arcana.core.data.repository

import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.domain.model.Collectible
import kotlinx.coroutines.flow.Flow

interface CollectibleRepository {

    /** Reactive stream of the whole collection as domain models, most valuable first. */
    fun observeCollection(): Flow<List<Collectible>>

    /** Items belonging to a given list (HobbyDB "List Name"), as domain models, most valuable first. */
    fun observeByList(listName: String): Flow<List<Collectible>>

    /** Lightweight count for start-destination routing (avoids loading the whole collection). */
    fun observeCount(): Flow<Int>

    /** Total estimated value of the whole collection, in cents. */
    fun observeTotalValueCents(): Flow<Int>

    /** Per-list rollup (count + value), most valuable first, for the Portfolio breakdown. */
    fun observeListBreakdown(): Flow<List<CollectionGroup>>

    /** A single collectible as a domain model, or null if not found. */
    suspend fun getById(localId: Long): Collectible?

    /**
     * Persists imported items: each becomes a collectible row + (for Funko) a metadata row +
     * series junction rows (canonical, deduped) + one import-time value snapshot. [onProgress] is
     * invoked after each item (written-so-far, the item) for a live import feed. Returns the count.
     */
    suspend fun importFrom(
        items: List<ImportedItem>,
        onProgress: (written: Int, item: ImportedItem) -> Unit = { _, _ -> },
    ): Int
}
