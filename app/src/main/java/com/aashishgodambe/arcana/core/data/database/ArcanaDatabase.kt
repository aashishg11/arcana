package com.aashishgodambe.arcana.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aashishgodambe.arcana.core.data.database.dao.CollectibleDao
import com.aashishgodambe.arcana.core.data.database.dao.FunkoMetadataDao
import com.aashishgodambe.arcana.core.data.database.dao.SeriesDao
import com.aashishgodambe.arcana.core.data.database.dao.ValueSnapshotDao
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleEntity
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleSeriesCrossRef
import com.aashishgodambe.arcana.core.data.database.entity.FunkoMetadataEntity
import com.aashishgodambe.arcana.core.data.database.entity.SeriesEntity
import com.aashishgodambe.arcana.core.data.database.entity.ValueSnapshotEntity

@Database(
    entities = [
        CollectibleEntity::class,
        FunkoMetadataEntity::class,
        SeriesEntity::class,
        CollectibleSeriesCrossRef::class,
        ValueSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(ArcanaConverters::class)
abstract class ArcanaDatabase : RoomDatabase() {
    abstract fun collectibleDao(): CollectibleDao
    abstract fun funkoMetadataDao(): FunkoMetadataDao
    abstract fun seriesDao(): SeriesDao
    abstract fun valueSnapshotDao(): ValueSnapshotDao
}
