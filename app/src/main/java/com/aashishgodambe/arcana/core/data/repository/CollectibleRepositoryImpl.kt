package com.aashishgodambe.arcana.core.data.repository

import androidx.room.withTransaction
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.database.dao.CollectibleDao
import com.aashishgodambe.arcana.core.data.database.dao.FunkoMetadataDao
import com.aashishgodambe.arcana.core.data.database.dao.SeriesDao
import com.aashishgodambe.arcana.core.data.database.dao.ValueSnapshotDao
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleOrigin
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleSeriesCrossRef
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithDetails
import com.aashishgodambe.arcana.core.data.database.entity.FunkoMetadataEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSnapshotEntity
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.importer.model.FunkoImportMetadata
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import com.aashishgodambe.arcana.core.domain.model.Collectible
import com.aashishgodambe.arcana.core.domain.model.FunkoPop
import com.aashishgodambe.arcana.core.domain.model.PortfolioPoint
import com.aashishgodambe.arcana.core.domain.model.ValueSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

class CollectibleRepositoryImpl @Inject constructor(
    private val db: ArcanaDatabase,
    private val collectibleDao: CollectibleDao,
    private val funkoMetadataDao: FunkoMetadataDao,
    private val seriesDao: SeriesDao,
    private val valueSnapshotDao: ValueSnapshotDao,
) : CollectibleRepository {

    override fun observeCollection(): Flow<List<Collectible>> =
        collectibleDao.observeAllWithDetails().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeByList(listName: String): Flow<List<Collectible>> =
        collectibleDao.observeByListWithDetails(listName).map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeCount(): Flow<Int> = collectibleDao.observeCount()

    override fun observeTotalValueCents(): Flow<Int> = collectibleDao.observeTotalValueCents()

    override fun observeCopyCount(): Flow<Int> = collectibleDao.observeCopyCount()

    override fun observeListBreakdown(): Flow<List<CollectionGroup>> = collectibleDao.observeListBreakdown()

    override suspend fun getById(localId: Long): Collectible? =
        collectibleDao.getWithDetails(localId)?.toDomain()

    override suspend fun allCollectibles(): List<Collectible> =
        collectibleDao.observeAllWithDetails().first().mapNotNull { it.toDomain() }

    override fun observeValueHistory(localId: Long): Flow<List<ValueSnapshot>> =
        valueSnapshotDao.observeForCollectible(localId).map { rows -> rows.map { it.toDomain() } }

    override fun observePortfolioSeries(): Flow<List<PortfolioPoint>> =
        valueSnapshotDao.observePortfolioSeries().map { rows ->
            rows.map { PortfolioPoint(at = it.at, totalValueCents = it.totalValueCents) }
        }

    override suspend fun latestSnapshot(localId: Long): ValueSnapshot? =
        valueSnapshotDao.latestForCollectible(localId)?.toDomain()

    override suspend fun recordSnapshot(
        localId: Long,
        valueCents: Int,
        source: ValueSource,
        trigger: SnapshotTrigger,
        at: Instant,
    ) = db.withTransaction {
        valueSnapshotDao.insert(
            ValueSnapshotEntity(
                collectibleLocalId = localId,
                valueCents = valueCents,
                source = source,
                trigger = trigger,
                snapshotAt = at,
            ),
        )
        collectibleDao.updateLastKnownValue(localId, valueCents, source, at)
    }

    override suspend fun isHistorySeeded(): Boolean =
        valueSnapshotDao.totalCount() > collectibleDao.count()

    override suspend fun replaceHistories(histories: Map<Long, List<ValueSnapshot>>) = db.withTransaction {
        for ((localId, points) in histories) {
            if (points.isEmpty()) continue
            valueSnapshotDao.deleteForCollectible(localId)
            valueSnapshotDao.insertAll(
                points.map {
                    ValueSnapshotEntity(
                        collectibleLocalId = localId,
                        valueCents = it.valueCents,
                        source = it.source,
                        trigger = it.trigger,
                        snapshotAt = it.at,
                    )
                },
            )
            val newest = points.maxBy { it.at }
            collectibleDao.updateLastKnownValue(localId, newest.valueCents, newest.source, newest.at)
        }
    }

    override suspend fun getMostValuable(limit: Int): List<Collectible> =
        collectibleDao.getMostValuableWithDetails(limit).mapNotNull { it.toDomain() }

    override suspend fun search(query: String, limit: Int): List<Collectible> {
        val terms = salientTerms(query)
        if (terms.isEmpty()) return emptyList()
        // AND semantics: an item must match EVERY term, so a multi-word query like "power rangers"
        // returns only items containing both (the "Power Rangers" series) — not everything with
        // "power" in its name (Kenny Powers, Star-Lord with Power Stone). Fetch each term's matches
        // generously, then intersect by id. Single-term queries just return that term's matches.
        val perTerm = terms.map { term ->
            collectibleDao.searchWithDetails("%$term%", CANDIDATE_LIMIT)
                .mapNotNull { it.toDomain() }
                .associateBy { it.localId }
        }
        val commonIds = perTerm.map { it.keys }.reduce { acc, ids -> acc intersect ids }
        return perTerm.first().filterKeys { it in commonIds }.values
            .sortedByDescending { it.estimatedValueCents }
            .take(limit)
    }

    override suspend fun importFrom(
        items: List<ImportedItem>,
        onProgress: (written: Int, item: ImportedItem) -> Unit,
    ): Int = db.withTransaction {
        val now = Instant.now()
        var inserted = 0
        for (item in items) {
            val localId = collectibleDao.insert(item.toEntity())
            item.funkoMetadata?.let { funkoMetadataDao.insert(it.toEntity(localId)) }
            for (seriesName in item.series.distinct()) {
                val seriesId = seriesDao.getOrInsert(seriesName)
                seriesDao.insertCrossRef(CollectibleSeriesCrossRef(localId, seriesId))
            }
            valueSnapshotDao.insert(
                ValueSnapshotEntity(
                    collectibleLocalId = localId,
                    valueCents = item.estimatedValueCents ?: 0,
                    source = ValueSource.HobbyDbImport,
                    trigger = SnapshotTrigger.Import,
                    snapshotAt = now,
                ),
            )
            inserted++
            onProgress(inserted, item)
        }
        inserted
    }
}

/** Per-term fetch ceiling before intersecting — high so a common term (e.g. "power") can't crowd out
 *  a rarer co-term during the AND. Well above any single franchise's size in a personal collection. */
private const val CANDIDATE_LIMIT = 500

/** Stopwords stripped from a question before keyword retrieval — question scaffolding, not subjects. */
private val SEARCH_STOPWORDS = setOf(
    "do", "does", "did", "have", "has", "had", "the", "any", "some", "all",
    "pop", "pops", "funko", "funkos", "vinyl", "figure", "figures", "collectible", "collectibles",
    "item", "items", "how", "many", "much", "what", "whats", "which", "who", "that", "this",
    "you", "your", "for", "with", "from", "own", "get", "got", "are", "was", "were", "and", "but",
    // ranking/intent words — describe the question, not a collection subject, so keep them out of
    // keyword search (otherwise "most valuable item?" searches for "%most%" instead of falling back).
    "most", "valuable", "worth", "value", "expensive", "cost", "costs", "priciest", "top", "best",
    "highest", "lowest", "rare", "rarest", "cheap", "cheapest", "list", "show", "tell", "about",
)

/** Lowercased word tokens ≥3 chars that aren't stopwords — the searchable subjects of a question. */
private fun salientTerms(query: String): List<String> =
    query.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in SEARCH_STOPWORDS }
        .distinct()

private fun ImportedItem.toEntity() = CollectibleEntity(
    category = category,
    origin = CollectibleOrigin.HobbyDbImport,
    sourceId = sourceId.ifBlank { null },
    sourceName = sourceName,
    listName = listName,
    name = name,
    brand = brand,
    imageUrl = imageUrl,
    itemCondition = itemCondition ?: "",
    packagingCondition = packagingCondition ?: "",
    quantity = quantity,
    estimatedValueCents = estimatedValueCents ?: 0,
    lastKnownValueCents = null,
    lastKnownValueSource = null,
    lastKnownValueAt = null,
    pricePaidCents = pricePaidCents,
    acquiredFrom = acquiredFrom,
    datePurchased = datePurchased,
    dateAdded = dateAdded ?: LocalDate.now(),
    storageLocation = storageLocation,
    privateNotes = privateNotes,
)

private fun CollectibleWithDetails.toDomain(): Collectible? = when (collectible.category) {
    CollectibleCategory.Funko -> {
        val f = funko ?: return null
        FunkoPop(
            localId = collectible.localId,
            name = collectible.name,
            brand = collectible.brand,
            imageUrl = collectible.imageUrl,
            estimatedValueCents = collectible.estimatedValueCents,
            lastKnownValueCents = collectible.lastKnownValueCents,
            quantity = collectible.quantity,
            itemCondition = collectible.itemCondition,
            packagingCondition = collectible.packagingCondition,
            series = series.map { it.name },
            productionTags = emptyList(),
            dateAdded = collectible.dateAdded,
            pricePaidCents = collectible.pricePaidCents,
            storageLocation = collectible.storageLocation,
            upc = f.upc,
            popNumber = f.popNumber,
            exclusiveTo = f.exclusiveTo,
            isNftRedeemable = f.isNftRedeemable,
        )
    }
    // v1 ships Funko only; FigPin/Pokemon are documented holes.
    CollectibleCategory.FigPin, CollectibleCategory.Pokemon -> null
}

private fun ValueSnapshotEntity.toDomain() = ValueSnapshot(
    valueCents = valueCents,
    at = snapshotAt,
    source = source,
    trigger = trigger,
)

private fun FunkoImportMetadata.toEntity(localId: Long) = FunkoMetadataEntity(
    collectibleLocalId = localId,
    upc = upc ?: "",
    popNumber = popNumber,
    exclusiveTo = exclusiveTo,
    isNftRedeemable = isNftRedeemable,
    scale = scale,
    releaseDate = releaseDate,
    hdbcNumber = hdbcNumber,
)
