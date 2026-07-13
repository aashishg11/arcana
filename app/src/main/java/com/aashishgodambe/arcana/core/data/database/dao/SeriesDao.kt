package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleSeriesCrossRef
import com.aashishgodambe.arcana.core.data.database.entity.SeriesEntity

@Dao
abstract class SeriesDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(series: SeriesEntity): Long

    @Query("SELECT id FROM series WHERE name = :name")
    abstract suspend fun getIdByName(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCrossRef(crossRef: CollectibleSeriesCrossRef)

    /** Drop a collectible's series links on delete (the cross-ref table has no cascading FK). */
    @Query("DELETE FROM collectible_series WHERE collectibleLocalId = :id")
    abstract suspend fun deleteCrossRefsFor(id: Long)

    /**
     * Insert-or-get the canonical series row, returning its id. The unique index on `name` makes
     * the insert a no-op (rowId -1) when the series already exists, in which case we look it up.
     */
    @Transaction
    open suspend fun getOrInsert(name: String): Long {
        val rowId = insertIgnore(SeriesEntity(name = name))
        return if (rowId != -1L) rowId else getIdByName(name)
            ?: error("Series '$name' missing after insert-ignore")
    }
}
