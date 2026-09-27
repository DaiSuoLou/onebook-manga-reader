package com.example.mangareader.ui.bookshelf.model

/**
 * 书架格子里的一「部」作品（下面挂若干 [MangaVolumeUiModel]）。
 *
 * 可直接由 [com.example.mangareader.core.model] 或数据库实体映射而来。
 */
data class MangaSeriesUiModel(
    val id: String,
    val title: String,
    val coverUrl: Any?,
    val volumeCount: Int,
    /** 是否手动指定过封面（kzz.moe 抓的 / 相册选的）；决定抽屉里「移除封面」按钮是否出现 */
    val hasManualCover: Boolean = false,
)
