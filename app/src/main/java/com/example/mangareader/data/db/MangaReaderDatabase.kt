package com.example.mangareader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ReadingProgressEntity::class],
    version = 1,
    exportSchema = true,   // schema 输出到 app/schemas（见 build.gradle.kts 的 ksp arg）
)
abstract class MangaReaderDatabase : RoomDatabase() {

    abstract fun readingProgressDao(): ReadingProgressDao
}
