package com.aashishgodambe.arcana.core.data.importer.model

import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import java.time.LocalDate

/**
 * The contract between a [com.aashishgodambe.arcana.core.data.importer.CollectionImporter] and the
 * repository. Importers map their source format to this; the repository maps this to entities.
 */
data class ImportedItem(
    val sourceId: String,
    val sourceName: String,
    val listName: String?,
    val category: CollectibleCategory,

    // Common fields
    val name: String,
    val brand: String,
    val quantity: Int,
    val estimatedValueCents: Int?,
    val itemCondition: String?,
    val packagingCondition: String?,
    val dateAdded: LocalDate?,
    val imageUrl: String?,
    val series: List<String>,
    val productionTags: List<String>,

    // Category-specific metadata (populated when applicable)
    val funkoMetadata: FunkoImportMetadata?,

    // Gap-fillable fields — usually null on import
    val pricePaidCents: Int? = null,
    val acquiredFrom: String? = null,
    val datePurchased: LocalDate? = null,
    val storageLocation: String? = null,
    val privateNotes: String? = null,
)

data class FunkoImportMetadata(
    val upc: String?,
    val popNumber: String?,
    val exclusiveTo: String?,
    val isNftRedeemable: Boolean,
    val scale: String?,
    val releaseDate: LocalDate?,
    val hdbcNumber: String?,
)
