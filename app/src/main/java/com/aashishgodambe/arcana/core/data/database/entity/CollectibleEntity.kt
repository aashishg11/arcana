package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "collectibles")
data class CollectibleEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val category: CollectibleCategory,
    val origin: CollectibleOrigin,
    val sourceId: String?,
    val sourceName: String?,
    val name: String,
    val brand: String,
    val imageUrl: String?,
    val itemCondition: String,
    val packagingCondition: String,
    val quantity: Int,
    val estimatedValueCents: Int,
    val lastKnownValueCents: Int?,
    val lastKnownValueSource: ValueSource?,
    val lastKnownValueAt: Instant?,
    val pricePaidCents: Int?,
    val acquiredFrom: String?,
    val datePurchased: LocalDate?,
    val dateAdded: LocalDate,
    val storageLocation: String?,
    val privateNotes: String?,
)
