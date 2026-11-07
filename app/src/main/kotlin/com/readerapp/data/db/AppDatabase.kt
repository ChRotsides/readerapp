package com.readerapp.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Novel::class,
        Chapter::class,
        ChapterText::class,
        GlossaryEntry::class,
        ChapterSummary::class,
        ArcSummary::class,
        SiteAdapter::class,
        AdapterFixture::class,
        DomainState::class,
        AiUsage::class,
        UploadedChapter::class,
        Favorite::class,
        SearchHistoryEntry::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterTextDao(): ChapterTextDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun chapterSummaryDao(): ChapterSummaryDao
    abstract fun arcSummaryDao(): ArcSummaryDao
    abstract fun adapterDao(): AdapterDao
    abstract fun domainStateDao(): DomainStateDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun uploadedChapterDao(): UploadedChapterDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
