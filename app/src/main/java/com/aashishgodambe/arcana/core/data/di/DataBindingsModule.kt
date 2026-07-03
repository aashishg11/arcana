package com.aashishgodambe.arcana.core.data.di

import com.aashishgodambe.arcana.core.data.importer.CollectionImporter
import com.aashishgodambe.arcana.core.data.importer.HobbyDbCsvImporter
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {

    @Binds
    abstract fun bindCollectibleRepository(impl: CollectibleRepositoryImpl): CollectibleRepository

    @Binds
    abstract fun bindCollectionImporter(impl: HobbyDbCsvImporter): CollectionImporter
}
