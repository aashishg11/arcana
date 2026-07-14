package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.database.dao.VectorDao
import com.aashishgodambe.arcana.core.data.database.entity.VectorEntity
import javax.inject.Inject

/**
 * The RAG vector store: persists one embedding per collectible and answers nearest-neighbour queries by
 * **brute-force cosine** over the whole table. At 504 items × a few hundred dims that's a sub-millisecond
 * scan, so there's deliberately no ANN index or vector DB — over-engineering the project keeps catching.
 *
 * Vectors are stored at EmbeddingGemma's native 768 dim; [topK] truncates both the query and the corpus to
 * the shipping [dim] (MRL) at read time, so the dimension can be re-tuned without re-embedding.
 */
class CollectionVectorStore @Inject constructor(
    private val vectorDao: VectorDao,
) {
    suspend fun upsert(collectibleId: Long, vector: FloatArray, docHash: Int) {
        vectorDao.upsert(VectorEntity(collectibleId, vector.size, vector, docHash))
    }

    suspend fun count(): Int = vectorDao.count()

    /** `collectibleId -> docHash` for indexed rows — the indexer's staleness check. */
    suspend fun indexedHashes(): Map<Long, Int> = vectorDao.indexedHashes().associate { it.id to it.hash }

    suspend fun clear() = vectorDao.clear()

    /**
     * The [k] most similar collectible ids to [queryVector] (a native-dim query embedding), most similar
     * first, comparing at [dim] via MRL truncation. Returns `(collectibleId, cosine)` pairs.
     */
    suspend fun topK(
        queryVector: FloatArray,
        k: Int,
        dim: Int = EmbeddingMath.SHIPPING_DIMENSION,
    ): List<EmbeddingMath.Scored<Long>> {
        val query = EmbeddingMath.truncate(queryVector, dim)
        val corpus = vectorDao.getAll().map { it.collectibleLocalId to EmbeddingMath.truncate(it.vector, dim) }
        return EmbeddingMath.topK(query, corpus, k)
    }
}
