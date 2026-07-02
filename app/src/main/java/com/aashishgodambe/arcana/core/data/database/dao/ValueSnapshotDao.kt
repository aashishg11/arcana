package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aashishgodambe.arcana.core.data.database.entity.ValueSnapshotEntity

@Dao
interface ValueSnapshotDao {

    @Insert
    suspend fun insert(snapshot: ValueSnapshotEntity): Long

    @Query("SELECT * FROM value_snapshots WHERE collectibleLocalId = :collectibleId ORDER BY snapshotAt")
    suspend fun getForCollectible(collectibleId: Long): List<ValueSnapshotEntity>
}
