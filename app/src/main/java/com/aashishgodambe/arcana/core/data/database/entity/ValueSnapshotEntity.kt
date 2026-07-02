package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "value_snapshots",
    indices = [Index("collectibleLocalId"), Index("snapshotAt")],
)
data class ValueSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectibleLocalId: Long,
    val valueCents: Int,
    val source: ValueSource,
    val trigger: SnapshotTrigger,
    val snapshotAt: Instant,
)
