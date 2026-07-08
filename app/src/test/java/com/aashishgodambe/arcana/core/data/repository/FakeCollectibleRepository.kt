package com.aashishgodambe.arcana.core.data.repository

import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

/**
 * In-memory [CollectibleRepository] fake extending the Week-2 device-free test pattern to the value
 * layer: it actually stores snapshots, so use cases that read-then-write (debounce, sync, seed) can be
 * exercised end-to-end without Room. Reactive streams emit a single current snapshot.
 */
class FakeCollectibleRepository(items: List<Collectible> = emptyList()) : CollectibleRepository {

    private val byId = items.associateBy { it.localId }.toMutableMap()
    private val snapshots = mutableMapOf<Long, MutableList<ValueSnapshot>>()
    var seeded = false

    /** Directly settable per-list series for delta/summary tests (Collectible carries no list name). */
    var listSeries: Map<String, List<PortfolioPoint>> = emptyMap()

    private val sorted get() = byId.values.sortedByDescending { it.estimatedValueCents }

    fun snapshotsFor(localId: Long): List<ValueSnapshot> = snapshots[localId].orEmpty().sortedBy { it.at }

    override suspend fun getById(localId: Long): Collectible? = byId[localId]
    override suspend fun allCollectibles(): List<Collectible> = sorted
    override suspend fun getMostValuable(limit: Int): List<Collectible> = sorted.take(limit)

    override suspend fun search(query: String, limit: Int): List<Collectible> {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        if (terms.isEmpty()) return emptyList()
        return sorted.filter { c -> terms.all { c.name.contains(it, ignoreCase = true) } }.take(limit)
    }

    override fun observeValueHistory(localId: Long): Flow<List<ValueSnapshot>> = flowOf(snapshotsFor(localId))

    override fun observePortfolioSeries(): Flow<List<PortfolioPoint>> {
        val points = snapshots.entries
            .flatMap { (id, snaps) -> snaps.map { it.at to it.valueCents * (byId[id]?.quantity ?: 1) } }
            .groupBy({ it.first }, { it.second })
            .map { (at, values) -> PortfolioPoint(at, values.sum()) }
            .sortedBy { it.at }
        return flowOf(points)
    }

    override suspend fun listValueSeries(): Map<String, List<PortfolioPoint>> = listSeries

    override suspend fun latestSnapshot(localId: Long): ValueSnapshot? =
        snapshots[localId]?.maxByOrNull { it.at }

    override suspend fun recordSnapshot(
        localId: Long,
        valueCents: Int,
        source: ValueSource,
        trigger: SnapshotTrigger,
        at: Instant,
    ) {
        snapshots.getOrPut(localId) { mutableListOf() }.add(ValueSnapshot(valueCents, at, source, trigger))
    }

    override suspend fun isHistorySeeded(): Boolean = seeded

    override suspend fun replaceHistories(histories: Map<Long, List<ValueSnapshot>>) {
        histories.forEach { (id, points) -> snapshots[id] = points.toMutableList() }
        seeded = true
    }

    override fun observeCollection(): Flow<List<Collectible>> = flowOf(sorted)
    override fun observeByList(listName: String): Flow<List<Collectible>> = flowOf(emptyList())
    override fun observeCount(): Flow<Int> = flowOf(byId.size)
    override fun observeTotalValueCents(): Flow<Int> =
        flowOf(sorted.sumOf { (it.lastKnownValueCents ?: it.estimatedValueCents) * it.quantity })
    override fun observeCopyCount(): Flow<Int> = flowOf(sorted.sumOf { it.quantity })
    override fun observeListBreakdown(): Flow<List<CollectionGroup>> = flowOf(emptyList())

    override suspend fun importFrom(
        items: List<ImportedItem>,
        onProgress: (written: Int, item: ImportedItem) -> Unit,
    ): Int = 0
}
