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
     * Aggregate portfolio value series, **as-of each batch instant**: for every multi-item snapshot instant
     * (a seed week or a sync), the total value of the *whole* collection at that time — each item valued at
     * its latest snapshot ≤ that instant (Σ valueCents × quantity). Computing it as-of, rather than summing
     * only the items stamped at that exact instant, means a **partial or cancelled sync** shows the real
     * portfolio value (its unsynced items retain their prior value) instead of a misleading subset-sum.
     * `HAVING COUNT(*) > 1` still keeps single-item "Snapshot today's price" instants out of the sparkline.
     */
    @Query(
        """
        SELECT sub.at AS at,
          COALESCE((
            SELECT SUM(ls.valueCents * c.quantity)
            FROM collectibles c
            JOIN value_snapshots ls ON ls.collectibleLocalId = c.localId
            WHERE ls.snapshotAt = (
              SELECT MAX(vs2.snapshotAt) FROM value_snapshots vs2
              WHERE vs2.collectibleLocalId = c.localId AND vs2.snapshotAt <= sub.at
            )
          ), 0) AS totalValueCents
        FROM (SELECT snapshotAt AS at FROM value_snapshots GROUP BY snapshotAt HAVING COUNT(*) > 1) sub
        ORDER BY sub.at
        """,
    )
    fun observePortfolioSeries(): Flow<List<PortfolioPointRow>>

    /**
     * Per-list value series, **as-of each batch instant** (same construction as [observePortfolioSeries]):
     * for each (HobbyDB list, batch instant), the list's total value with every item at its latest snapshot
     * ≤ that instant. As-of so a partial/cancelled sync doesn't hand the weekly summary a subset-sum for a
     * list. Feeds the per-list delta the on-device summary narrates ("Nft funko led, Star Wars was flat").
     */
    @Query(
        """
        SELECT c.listName AS name, sub.at AS at, SUM(ls.valueCents * c.quantity) AS totalValueCents
        FROM (SELECT snapshotAt AS at FROM value_snapshots GROUP BY snapshotAt HAVING COUNT(*) > 1) sub
        CROSS JOIN collectibles c
        JOIN value_snapshots ls ON ls.collectibleLocalId = c.localId
          AND ls.snapshotAt = (
            SELECT MAX(vs2.snapshotAt) FROM value_snapshots vs2
            WHERE vs2.collectibleLocalId = c.localId AND vs2.snapshotAt <= sub.at
          )
        WHERE c.listName IS NOT NULL AND c.listName != ''
        GROUP BY c.listName, sub.at
        ORDER BY c.listName, sub.at
        """,
    )
    suspend fun listValueSeries(): List<ListValuePointRow>
}
