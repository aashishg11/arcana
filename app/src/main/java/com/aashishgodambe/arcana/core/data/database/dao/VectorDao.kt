package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.aashishgodambe.arcana.core.data.database.entity.VectorEntity

/**
 * The RAG vector store's DAO. Retrieval loads the whole table ([getAll]) and brute-forces cosine in
 * Kotlin — correct and sub-millisecond at 504 rows (the plan's "don't reach for a vector DB" call). The
 * hash/id projections let the indexer embed only what's new or changed.
 */
@Dao
interface VectorDao {

    @Upsert
    suspend fun upsert(vector: VectorEntity)

    @Upsert
    suspend fun upsertAll(vectors: List<VectorEntity>)

    /** The whole index — loaded once per query for brute-force cosine. */
    @Query("SELECT * FROM collectible_vectors")
    suspend fun getAll(): List<VectorEntity>

    @Query("SELECT COUNT(*) FROM collectible_vectors")
    suspend fun count(): Int

    /** `collectibleLocalId -> docHash` for the already-indexed rows, so the indexer can skip unchanged items. */
    @Query("SELECT collectibleLocalId AS id, docHash AS hash FROM collectible_vectors")
    suspend fun indexedHashes(): List<IndexedHash>

    @Query("DELETE FROM collectible_vectors WHERE collectibleLocalId = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM collectible_vectors")
    suspend fun clear()

    /** Projection of an indexed row's id and document hash — the staleness check for incremental re-index. */
    data class IndexedHash(val id: Long, val hash: Int)
}
