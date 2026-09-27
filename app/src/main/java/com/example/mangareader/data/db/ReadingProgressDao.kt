package com.example.mangareader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {

    /** 全量观察（书架刷新进度条用） */
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    /** 一次性读取，用于冷启动时载入内存 */
    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE chapterId = :chapterId")
    suspend fun get(chapterId: String): ReadingProgressEntity?

    /** 不存在则插入，存在则整行替换（Room 2.5+） */
    @Upsert
    suspend fun upsert(entity: ReadingProgressEntity)
}
