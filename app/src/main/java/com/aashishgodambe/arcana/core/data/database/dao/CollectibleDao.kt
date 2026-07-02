package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithSeries
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectibleDao {

    @Insert
    suspend fun insert(collectible: CollectibleEntity): Long

    @Query("SELECT * FROM collectibles")
    fun getAll(): Flow<List<CollectibleEntity>>

    @Query("SELECT * FROM collectibles WHERE localId = :id")
    suspend fun getById(id: Long): CollectibleEntity?

    @Query("SELECT * FROM collectibles ORDER BY estimatedValueCents DESC LIMIT :limit")
    suspend fun getMostValuable(limit: Int): List<CollectibleEntity>

    @Transaction
    @Query("SELECT * FROM collectibles WHERE localId = :id")
    suspend fun getWithSeries(id: Long): CollectibleWithSeries?
}
