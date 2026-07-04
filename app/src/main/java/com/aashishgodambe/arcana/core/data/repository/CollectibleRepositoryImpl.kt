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
import kotlinx.coroutines.flow.Flow
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

    override fun observeListBreakdown(): Flow<List<CollectionGroup>> = collectibleDao.observeListBreakdown()

    override suspend fun getById(localId: Long): Collectible? =
        collectibleDao.getWithDetails(localId)?.toDomain()

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
