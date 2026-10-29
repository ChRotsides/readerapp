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
        SiteAdapter::class,
        AdapterFixture::class,
        DomainState::class,
        AiUsage::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterTextDao(): ChapterTextDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun chapterSummaryDao(): ChapterSummaryDao
    abstract fun adapterDao(): AdapterDao
    abstract fun domainStateDao(): DomainStateDao
    abstract fun aiUsageDao(): AiUsageDao
}
