package com.readerapp.di

import com.readerapp.favorites.FavoriteStore
import com.readerapp.data.settings.SearchTuning
import com.readerapp.data.settings.SettingsSearchTuning
import com.readerapp.favorites.RoomFavoriteStore
import com.readerapp.offline.HiddenNovelStore
import com.readerapp.offline.NovelJoinHook
import com.readerapp.offline.OfflineDownloads
import com.readerapp.offline.RoomHiddenNovelStore
import com.readerapp.offline.SearchPrefetcher
import com.readerapp.offline.SearchResultPrefetch
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibrarySource
import com.readerapp.search.NovelPageStore
import com.readerapp.search.RoomLibrarySource
import com.readerapp.search.RoomNovelPageStore
import com.readerapp.search.RoomSimilarSource
import com.readerapp.search.SecureServerConfig
import com.readerapp.search.ServerApi
import com.readerapp.search.SimilarFinder
import com.readerapp.search.SimilarSearch
import com.readerapp.search.SimilarSource
import com.readerapp.search.SettingsUploadOption
import com.readerapp.search.UploadOption
import com.readerapp.search.ServerConfigProvider
import com.readerapp.search.TocImporterRunner
import com.readerapp.search.TocRunner
import com.readerapp.search.history.RoomSearchHistoryStore
import com.readerapp.search.history.SearchHistoryStore
import com.readerapp.search.history.SearchHistorySyncRequester
import com.readerapp.search.history.SearchHistorySyncScheduler
import com.readerapp.sync.RoomUploadStore
import com.readerapp.sync.UploadStore
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

    @Binds abstract fun uploadStore(impl: RoomUploadStore): UploadStore
    @Binds abstract fun similarSource(impl: RoomSimilarSource): SimilarSource
    @Binds abstract fun uploadOption(impl: SettingsUploadOption): UploadOption
    @Binds abstract fun similarSearch(impl: SimilarFinder): SimilarSearch
    @Binds abstract fun searchTuning(impl: SettingsSearchTuning): SearchTuning
    @Binds abstract fun pageStore(impl: RoomNovelPageStore): NovelPageStore
    @Binds abstract fun tocRunner(impl: TocImporterRunner): TocRunner
    @Binds abstract fun joinHook(impl: OfflineDownloads): NovelJoinHook
    @Binds abstract fun searchPrefetch(impl: SearchPrefetcher): SearchResultPrefetch
    @Binds abstract fun hiddenStore(impl: RoomHiddenNovelStore): HiddenNovelStore
    @Binds abstract fun favoriteStore(impl: RoomFavoriteStore): FavoriteStore
    @Binds abstract fun searchHistoryStore(impl: RoomSearchHistoryStore): SearchHistoryStore
    @Binds abstract fun searchHistorySyncRequester(impl: SearchHistorySyncScheduler): SearchHistorySyncRequester

    companion object {
        @Provides
        @Singleton
        fun discoveryApi(config: ServerConfigProvider): DiscoveryApi = ServerApi(config)
    }
}
