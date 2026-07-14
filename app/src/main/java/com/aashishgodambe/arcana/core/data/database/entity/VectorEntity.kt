package com.aashishgodambe.arcana.core.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The on-device embedding of one collectible — the RAG vector store's row. One vector per collectible
 * (the [collectibleLocalId] is the PK), the **native 768-dim** EmbeddingGemma output stored as a float
 * BLOB so the shipping dimension stays re-benchmarkable without re-embedding. [docHash] is the hash of the
 * embedded document text, letting the indexer skip items whose descriptor hasn't changed.
 *
 * `ForeignKey CASCADE` on the collectible means a deleted item's vector goes with it automatically (like
 * `funko_metadata`), so the index can't orphan.
 */
@Entity(
    tableName = "collectible_vectors",
    foreignKeys = [
        ForeignKey(
            entity = CollectibleEntity::class,
            parentColumns = ["localId"],
            childColumns = ["collectibleLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VectorEntity(
    @PrimaryKey val collectibleLocalId: Long,
    val dim: Int,
    val vector: FloatArray,
    val docHash: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is VectorEntity &&
            collectibleLocalId == other.collectibleLocalId &&
            dim == other.dim &&
            docHash == other.docHash &&
            vector.contentEquals(other.vector)

    override fun hashCode(): Int {
        var result = collectibleLocalId.hashCode()
        result = 31 * result + dim
        result = 31 * result + docHash
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
