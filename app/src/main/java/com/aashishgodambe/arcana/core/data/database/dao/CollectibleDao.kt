package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithDetails
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithSeries
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectibleDao {

    @Insert
    suspend fun insert(collectible: CollectibleEntity): Long

    @Query("SELECT * FROM collectibles")
    fun getAll(): Flow<List<CollectibleEntity>>

    @Query("SELECT COUNT(*) FROM collectibles")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(estimatedValueCents), 0) FROM collectibles")
    fun observeTotalValueCents(): Flow<Int>

    @Query(
        """
        SELECT listName AS name, COUNT(*) AS itemCount, COALESCE(SUM(estimatedValueCents), 0) AS valueCents
        FROM collectibles
        WHERE listName IS NOT NULL AND listName != ''
        GROUP BY listName
        ORDER BY valueCents DESC
        """,
    )
    fun observeListBreakdown(): Flow<List<CollectionGroup>>

    @Transaction
    @Query("SELECT * FROM collectibles WHERE listName = :listName ORDER BY estimatedValueCents DESC")
    fun observeByListWithDetails(listName: String): Flow<List<CollectibleWithDetails>>

    @Query("SELECT * FROM collectibles WHERE localId = :id")
    suspend fun getById(id: Long): CollectibleEntity?

    @Query("SELECT * FROM collectibles ORDER BY estimatedValueCents DESC LIMIT :limit")
    suspend fun getMostValuable(limit: Int): List<CollectibleEntity>

    @Transaction
    @Query("SELECT * FROM collectibles WHERE localId = :id")
    suspend fun getWithSeries(id: Long): CollectibleWithSeries?

    @Transaction
    @Query("SELECT * FROM collectibles ORDER BY estimatedValueCents DESC")
    fun observeAllWithDetails(): Flow<List<CollectibleWithDetails>>

    @Transaction
    @Query("SELECT * FROM collectibles WHERE localId = :id")
    suspend fun getWithDetails(id: Long): CollectibleWithDetails?
}
