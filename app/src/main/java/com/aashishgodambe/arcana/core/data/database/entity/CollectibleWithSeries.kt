package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** A collectible with its many-to-many series resolved through the junction table. */
data class CollectibleWithSeries(
    @Embedded val collectible: CollectibleEntity,
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
