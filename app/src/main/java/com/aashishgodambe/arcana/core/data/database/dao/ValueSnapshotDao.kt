package com.aashishgodambe.arcana.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aashishgodambe.arcana.core.data.database.entity.ListValuePointRow
import com.aashishgodambe.arcana.core.data.database.entity.PortfolioPointRow
import com.aashishgodambe.arcana.core.data.database.entity.ValueSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ValueSnapshotDao {

    @Insert
    suspend fun insert(snapshot: ValueSnapshotEntity): Long

    @Insert
    suspend fun insertAll(snapshots: List<ValueSnapshotEntity>)

    @Query("SELECT * FROM value_snapshots WHERE collectibleLocalId = :collectibleId ORDER BY snapshotAt")
    suspend fun getForCollectible(collectibleId: Long): List<ValueSnapshotEntity>

    /** Reactive per-item history so charts + the "Snapshot today's price" action update live. */
    @Query("SELECT * FROM value_snapshots WHERE collectibleLocalId = :collectibleId ORDER BY snapshotAt")
    fun observeForCollectible(collectibleId: Long): Flow<List<ValueSnapshotEntity>>

    /** Newest snapshot for an item — the debounce reference for "Snapshot today's price". */
    @Query("SELECT * FROM value_snapshots WHERE collectibleLocalId = :collectibleId ORDER BY snapshotAt DESC LIMIT 1")
    suspend fun latestForCollectible(collectibleId: Long): ValueSnapshotEntity?

    @Query("DELETE FROM value_snapshots WHERE collectibleLocalId = :collectibleId")
    suspend fun deleteForCollectible(collectibleId: Long)

    @Query("SELECT COUNT(*) FROM value_snapshots")
    suspend fun totalCount(): Int

    /**
     * Aggregate portfolio value series: for each snapshot instant, the total value across the whole
     * collection counting duplicate copies (Σ valueCents × quantity). Relies on all items sharing the
     * same set of weekly snapshot instants (the seeder aligns them), so each instant is one portfolio
     * point. Backs the Portfolio sparkline.
     */
    @Query(
        """
        SELECT vs.snapshotAt AS at, SUM(vs.valueCents * c.quantity) AS totalValueCents
        FROM value_snapshots vs
        JOIN collectibles c ON c.localId = vs.collectibleLocalId
        GROUP BY vs.snapshotAt
        ORDER BY vs.snapshotAt
        """,
    )
    fun observePortfolioSeries(): Flow<List<PortfolioPointRow>>

    /**
     * Per-list value series: for each (HobbyDB list, snapshot instant), the list's total value counting
     * duplicate copies. Feeds the weekly per-list delta the on-device summary narrates ("Nft funko led,
     * Star Wars was flat"). Ordered by list then time so the caller can diff consecutive points.
     */
    @Query(
        """
        SELECT c.listName AS name, vs.snapshotAt AS at, SUM(vs.valueCents * c.quantity) AS totalValueCents
        FROM value_snapshots vs
        JOIN collectibles c ON c.localId = vs.collectibleLocalId
        WHERE c.listName IS NOT NULL AND c.listName != ''
        GROUP BY c.listName, vs.snapshotAt
        ORDER BY c.listName, vs.snapshotAt
        """,
    )
    suspend fun listValueSeries(): List<ListValuePointRow>
}
