package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aashishgodambe.arcana.core.data.database.entity.FunkoMetadataEntity

@Dao
interface FunkoMetadataDao {

    @Insert
    suspend fun insert(metadata: FunkoMetadataEntity)

    @Query("SELECT * FROM funko_metadata WHERE collectibleLocalId = :collectibleId")
    suspend fun getByCollectibleId(collectibleId: Long): FunkoMetadataEntity?

    @Query("SELECT * FROM funko_metadata WHERE upc = :upc LIMIT 1")
    suspend fun getByUpc(upc: String): FunkoMetadataEntity?

    @Query("SELECT COUNT(*) FROM funko_metadata WHERE isNftRedeemable = 1")
    suspend fun countNftRedeemable(): Int
}
