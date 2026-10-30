package com.readerapp.di

import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibrarySource
import com.readerapp.search.NovelPageStore
import com.readerapp.search.RoomLibrarySource
import com.readerapp.search.RoomNovelPageStore
import com.readerapp.search.SecureServerConfig
import com.readerapp.search.ServerApi
import com.readerapp.search.ServerConfigProvider
import com.readerapp.search.TocImporterRunner
import com.readerapp.search.TocRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryBindings {
    @Binds abstract fun serverConfig(impl: SecureServerConfig): ServerConfigProvider
    @Binds abstract fun library(impl: RoomLibrarySource): LibrarySource
    @Binds abstract fun pageStore(impl: RoomNovelPageStore): NovelPageStore
    @Binds abstract fun tocRunner(impl: TocImporterRunner): TocRunner

    companion object {
        @Provides
        @Singleton
        fun discoveryApi(config: ServerConfigProvider): DiscoveryApi = ServerApi(config)
    }
}
