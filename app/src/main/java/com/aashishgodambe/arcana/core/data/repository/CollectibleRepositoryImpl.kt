package com.aashishgodambe.arcana.core.data.repository

import androidx.room.withTransaction
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.database.dao.CollectibleDao
import com.aashishgodambe.arcana.core.data.database.dao.FunkoMetadataDao
import com.aashishgodambe.arcana.core.data.database.dao.SeriesDao
import com.aashishgodambe.arcana.core.data.database.dao.ValueSnapshotDao
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleOrigin
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleSeriesCrossRef
import com.aashishgodambe.arcana.core.data.database.entity.FunkoMetadataEntity
import com.aashishgodambe.arcana.core.data.database.entity.SnapshotTrigger
import com.aashishgodambe.arcana.core.data.database.entity.ValueSnapshotEntity
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import com.aashishgodambe.arcana.core.data.importer.model.FunkoImportMetadata
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
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

    override suspend fun importFrom(items: List<ImportedItem>): Int = db.withTransaction {
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
        }
        inserted
    }
}

private fun ImportedItem.toEntity() = CollectibleEntity(
    category = category,
    origin = CollectibleOrigin.HobbyDbImport,
    sourceId = sourceId.ifBlank { null },
    sourceName = sourceName,
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
