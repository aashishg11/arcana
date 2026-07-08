package com.aashishgodambe.arcana.core.data.repository

import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface CollectibleRepository {

    /** Reactive stream of the whole collection as domain models, most valuable first. */
    fun observeCollection(): Flow<List<Collectible>>

    /** Items belonging to a given list (HobbyDB "List Name"), as domain models, most valuable first. */
    fun observeByList(listName: String): Flow<List<Collectible>>

    /** Lightweight count for start-destination routing (avoids loading the whole collection). */
    fun observeCount(): Flow<Int>

    /** Total estimated value of the whole collection, in cents (counts duplicate copies). */
    fun observeTotalValueCents(): Flow<Int>

    /** Total copies including duplicates (Σ quantity). */
    fun observeCopyCount(): Flow<Int>

    /** Per-list rollup (count + value), most valuable first, for the Portfolio breakdown. */
    fun observeListBreakdown(): Flow<List<CollectionGroup>>

    /** A single collectible as a domain model, or null if not found. */
    suspend fun getById(localId: Long): Collectible?

    /** The whole collection as domain models (one-shot) — for price sync + history seeding. */
    suspend fun allCollectibles(): List<Collectible>

    /** Reactive per-item value history, oldest first — backs the Detail 90-day chart. */
    fun observeValueHistory(localId: Long): Flow<List<ValueSnapshot>>

    /** Reactive aggregate portfolio value series, oldest first — backs the Portfolio sparkline. */
    fun observePortfolioSeries(): Flow<List<PortfolioPoint>>

    /** Per-list value series (oldest first per list) — feeds the weekly "what moved" delta. */
    suspend fun listValueSeries(): Map<String, List<PortfolioPoint>>

    /** Newest snapshot for an item, or null — the debounce reference for "Snapshot today's price". */
    suspend fun latestSnapshot(localId: Long): ValueSnapshot?

    /** Writes one value snapshot and refreshes the item's cached "current" value. */
    suspend fun recordSnapshot(
        localId: Long,
        valueCents: Int,
        source: ValueSource,
        trigger: SnapshotTrigger,
        at: Instant,
    )

    /** True once a real value history exists (more snapshots than items) — the seed guard. */
    suspend fun isHistorySeeded(): Boolean

    /**
     * Replaces each item's history with the given points in one transaction, and refreshes each item's
     * cached "current" value to its newest point. Used by the Week-3 backdated mock seed; the caller
     * (a domain use case) owns the mock drift, so the data layer stays free of pricing logic.
     */
    suspend fun replaceHistories(histories: Map<Long, List<ValueSnapshot>>)

    /** The [limit] most valuable collectibles as domain models, for grounding AI answers. */
    suspend fun getMostValuable(limit: Int): List<Collectible>

    /**
     * Keyword search over name/brand/list/series for the given free-text [query], most valuable
     * first, for grounding AI answers on the relevant subset. Returns empty when the query has no
     * salient terms.
     */
    suspend fun search(query: String, limit: Int): List<Collectible>

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
