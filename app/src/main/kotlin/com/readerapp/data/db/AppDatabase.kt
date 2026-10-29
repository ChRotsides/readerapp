package com.readerapp.data.db

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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterTextDao(): ChapterTextDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun chapterSummaryDao(): ChapterSummaryDao
    abstract fun adapterDao(): AdapterDao
}
