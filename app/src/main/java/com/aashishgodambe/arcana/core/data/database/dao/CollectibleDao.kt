package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithDetails
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleWithSeries
import com.aashishgodambe.arcana.core.data.database.entity.CollectionGroup
import com.aashishgodambe.arcana.core.data.database.entity.ValueSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface CollectibleDao {

    @Insert
    suspend fun insert(collectible: CollectibleEntity): Long

    @Query("SELECT * FROM collectibles")
    fun getAll(): Flow<List<CollectibleEntity>>

    /** One-shot count for the seed guard (avoids observing). */
    @Query("SELECT COUNT(*) FROM collectibles")
    suspend fun count(): Int

    /** Updates the cached "current" value after a price snapshot; drives the delta line and headline. */
    @Query(
        "UPDATE collectibles SET lastKnownValueCents = :cents, lastKnownValueSource = :source, " +
            "lastKnownValueAt = :at WHERE localId = :id",
    )
    suspend fun updateLastKnownValue(id: Long, cents: Int, source: ValueSource, at: Instant)

    @Query("SELECT COUNT(*) FROM collectibles")
    fun observeCount(): Flow<Int>

    // Value counts every copy: an item held in quantity N contributes N × its unit value, so the
    // total matches HobbyDB's "incl. duplicates" figure rather than a per-entry sum. Uses the tracked
    // current value (lastKnownValueCents) when price sync has set it, falling back to the import estimate.
    @Query("SELECT COALESCE(SUM(COALESCE(lastKnownValueCents, estimatedValueCents) * quantity), 0) FROM collectibles")
    fun observeTotalValueCents(): Flow<Int>

    /** Total copies incl. duplicates (Σ quantity) — HobbyDB's "Collectible Entries (incl. Duplicates)". */
    @Query("SELECT COALESCE(SUM(quantity), 0) FROM collectibles")
    fun observeCopyCount(): Flow<Int>

    @Query(
        """
        SELECT listName AS name, COUNT(*) AS itemCount,
               COALESCE(SUM(COALESCE(lastKnownValueCents, estimatedValueCents) * quantity), 0) AS valueCents
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

    /** "Add another" — bump the owned copy count; duplicate-aware valuation already multiplies by it. */
    @Query("UPDATE collectibles SET quantity = quantity + 1 WHERE localId = :id")
    suspend fun incrementQuantity(id: Long)

    /** Delete a collectible; its funko_metadata row cascades (FK), snapshots/series links do not. */
    @Query("DELETE FROM collectibles WHERE localId = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT quantity FROM collectibles WHERE localId = :id")
    suspend fun quantityOf(id: Long): Int

    /** Distinct non-empty list names, for the capture save-to-list picker. */
    @Query(
        "SELECT DISTINCT listName FROM collectibles WHERE listName IS NOT NULL AND listName != '' " +
            "ORDER BY listName COLLATE NOCASE",
    )
    suspend fun listNames(): List<String>

    @Query("SELECT * FROM collectibles ORDER BY estimatedValueCents DESC LIMIT :limit")
    suspend fun getMostValuable(limit: Int): List<CollectibleEntity>

    @Transaction
    @Query("SELECT * FROM collectibles ORDER BY estimatedValueCents DESC LIMIT :limit")
    suspend fun getMostValuableWithDetails(limit: Int): List<CollectibleWithDetails>

    /**
     * Keyword search across name, brand, list name, and series (joined), most valuable first.
     * [like] is a full LIKE pattern (e.g. "%avatar%"). Grounds AI answers on the *relevant* subset
     * of the collection rather than only the most valuable items.
     */
    @Transaction
    @Query(
        """
        SELECT DISTINCT c.* FROM collectibles c
        LEFT JOIN collectible_series x ON x.collectibleLocalId = c.localId
        LEFT JOIN series s ON s.id = x.seriesId
        WHERE c.name LIKE :like OR c.brand LIKE :like OR c.listName LIKE :like OR s.name LIKE :like
        ORDER BY c.estimatedValueCents DESC
        LIMIT :limit
        """,
    )
    suspend fun searchWithDetails(like: String, limit: Int): List<CollectibleWithDetails>

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
