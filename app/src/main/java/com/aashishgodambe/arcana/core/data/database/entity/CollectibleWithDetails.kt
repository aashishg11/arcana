package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** A collectible with its 1:1 category metadata and many-to-many series resolved — the read shape. */
data class CollectibleWithDetails(
    @Embedded val collectible: CollectibleEntity,
    @Relation(parentColumn = "localId", entityColumn = "collectibleLocalId")
    val funko: FunkoMetadataEntity?,
    @Relation(
        parentColumn = "localId",
        entityColumn = "id",
        associateBy = Junction(
            value = CollectibleSeriesCrossRef::class,
            parentColumn = "collectibleLocalId",
            entityColumn = "seriesId",
        ),
    )
    val series: List<SeriesEntity>,
)
