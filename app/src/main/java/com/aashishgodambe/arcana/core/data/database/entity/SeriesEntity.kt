package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "series",
    indices = [Index(value = ["name"], unique = true)],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)
