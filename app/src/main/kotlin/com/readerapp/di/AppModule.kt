package com.readerapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.readerapp.data.db.AppDatabase
import com.readerapp.scrape.ChapterExtractor
import com.readerapp.scrape.ExtractorChain
import com.readerapp.scrape.GenericExtractor
import com.readerapp.scrape.adapter.AdapterExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "readerapp.db").build()

    @Provides fun novelDao(db: AppDatabase) = db.novelDao()
    @Provides fun chapterDao(db: AppDatabase) = db.chapterDao()
    @Provides fun chapterTextDao(db: AppDatabase) = db.chapterTextDao()
    @Provides fun glossaryDao(db: AppDatabase) = db.glossaryDao()
    @Provides fun chapterSummaryDao(db: AppDatabase) = db.chapterSummaryDao()
    @Provides fun arcSummaryDao(db: AppDatabase) = db.arcSummaryDao()
    @Provides fun adapterDao(db: AppDatabase) = db.adapterDao()
    @Provides fun domainStateDao(db: AppDatabase) = db.domainStateDao()
    @Provides fun aiUsageDao(db: AppDatabase) = db.aiUsageDao()
    @Provides fun uploadedChapterDao(db: AppDatabase) = db.uploadedChapterDao()
    @Provides fun favoriteDao(db: AppDatabase) = db.favoriteDao()
    @Provides fun searchHistoryDao(db: AppDatabase) = db.searchHistoryDao()

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /** Active site adapter first, Readability-based generic extractor as the fallback. */
    @Provides
    @Singleton
    fun chapterExtractor(adapter: AdapterExtractor, generic: GenericExtractor): ChapterExtractor =
        ExtractorChain(listOf(adapter, generic))
}
