package com.example.mangareader.ui.bookshelf.model

/**
 * 书架/抽屉格子里的「一册」。
 *
 * 和 [MangaSeriesUiModel]（一整部作品的堆叠）是两级粒度：一个 series 下挂若干 volume。
 *
 * 进度约定（容易踩坑，务必按此对齐）：
 * - [lastReadPage] 是**已读页数（1 起）**，不是页码下标。`0` = 未读，`totalPages` = 读完。
 *   所以 [isFinished] 用 `>=` 判断，[progress] 直接相除。
 * - 阅读器的 `onPageChanged` 回传的是 **0 起的 pageIndex**，映射到这里必须 `+1`，
 *   否则刚翻开第一页时 progress 仍是 0，界面会显示成「未读」。
 * - [totalPages] 为 0（尚未解析出页数）时 [progress] 退化为 0f，[isFinished] 为 false。
 */
data class MangaVolumeUiModel(
    val id: String,
    val volumeNumber: Int,
    val title: String,               // 如 "第 01 卷" 或 "第 1~12 话"
    val coverUrl: Any?,
    val totalPages: Int,
    val lastReadPage: Int = 0,       // 已读页码，0 表示未读
    val isDownloaded: Boolean = true // 是否已完全缓存/为本地文件
) {
    val progress: Float
        get() = if (totalPages > 0) (lastReadPage.toFloat() / totalPages).coerceIn(0f, 1f) else 0f

    val isFinished: Boolean
        get() = lastReadPage >= totalPages && totalPages > 0
}
