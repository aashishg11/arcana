package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "collectible_series",
    primaryKeys = ["collectibleLocalId", "seriesId"],
    indices = [Index("seriesId")],
)
data class CollectibleSeriesCrossRef(
    val collectibleLocalId: Long,
    val seriesId: Long,
)
