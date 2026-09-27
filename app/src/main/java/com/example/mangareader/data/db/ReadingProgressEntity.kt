package com.example.mangareader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 阅读进度表。
 *
 * 主键用 `chapterId`（源内相对路径）：本地源和 WebDAV 源都是这个 id，
 * 换源/改名不会错位，且天然可持久化。
 */
@Entity(
    tableName = "reading_progress",
    primaryKeys = ["chapterId"],
)
data class ReadingProgressEntity(
    @ColumnInfo(name = "chapterId") val chapterId: String,
    /** 已读页数（1 起）：0 = 未读，等于 totalPages = 读完 */
    @ColumnInfo(name = "lastReadPage") val lastReadPage: Int = 0,
    /** 打开过才知道；未打开时为 0 */
    @ColumnInfo(name = "totalPages") val totalPages: Int = 0,
    /**
     * 合并跨页模式（按源前缀的「这本书」维度持久化）。
     * 0 = 关闭，1 = 与上一页合并，2 = 与下一页合并（对应 ReadingPreferences.SpreadJoinMode.ordinal）
     */
    @ColumnInfo(name = "spreadJoinMode") val spreadJoinMode: Int = 0,
    /** 合并跨页锚点页码（0 起）；-1 表示未设置（关闭态） */
    @ColumnInfo(name = "spreadJoinAnchor") val spreadJoinAnchor: Int = -1,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = System.currentTimeMillis(),
)
