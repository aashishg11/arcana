package com.aashishgodambe.arcana.core.data.di

import android.content.Context
import androidx.room.Room
import com.aashishgodambe.arcana.core.data.database.ArcanaDatabase
import com.aashishgodambe.arcana.core.data.database.dao.CollectibleDao
import com.aashishgodambe.arcana.core.data.database.dao.FunkoMetadataDao
import com.aashishgodambe.arcana.core.data.database.dao.SeriesDao
import com.aashishgodambe.arcana.core.data.database.dao.ValueSnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ArcanaDatabase =
        Room.databaseBuilder(context, ArcanaDatabase::class.java, "arcana.db")
            // Week-2 dev convenience: schema still churning, no released data to preserve.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideCollectibleDao(db: ArcanaDatabase): CollectibleDao = db.collectibleDao()

    @Provides fun provideFunkoMetadataDao(db: ArcanaDatabase): FunkoMetadataDao = db.funkoMetadataDao()

    @Provides fun provideSeriesDao(db: ArcanaDatabase): SeriesDao = db.seriesDao()

    @Provides fun provideValueSnapshotDao(db: ArcanaDatabase): ValueSnapshotDao = db.valueSnapshotDao()
}
