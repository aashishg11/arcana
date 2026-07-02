package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "funko_metadata",
    foreignKeys = [
        ForeignKey(
            entity = CollectibleEntity::class,
            parentColumns = ["localId"],
            childColumns = ["collectibleLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FunkoMetadataEntity(
    @PrimaryKey val collectibleLocalId: Long,
    val upc: String,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,
    val scale: String?,
    val releaseDate: LocalDate?,
    val hdbcNumber: String?,
)
