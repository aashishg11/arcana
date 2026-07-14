package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.data.database.dao.VectorDao
import com.aashishgodambe.arcana.core.data.database.entity.VectorEntity

/** In-memory [VectorDao] for device-free store/indexer tests — mirrors the Room upsert-by-PK semantics. */
class FakeVectorDao : VectorDao {
    private val rows = LinkedHashMap<Long, VectorEntity>()

    override suspend fun upsert(vector: VectorEntity) {
        rows[vector.collectibleLocalId] = vector
    }

    override suspend fun upsertAll(vectors: List<VectorEntity>) {
        vectors.forEach { rows[it.collectibleLocalId] = it }
    }

    override suspend fun getAll(): List<VectorEntity> = rows.values.toList()

    override suspend fun count(): Int = rows.size

    override suspend fun indexedHashes(): List<VectorDao.IndexedHash> =
        rows.values.map { VectorDao.IndexedHash(it.collectibleLocalId, it.docHash) }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
    }

    override suspend fun clear() {
        rows.clear()
    }
}
