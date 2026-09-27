package com.example.mangareader.data

import com.example.mangareader.data.db.ReadingProgressDao
import com.example.mangareader.data.db.ReadingProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读进度仓库：内存缓存 + Room 落盘。
 *
 * 两条设计约束：
 * 1. **读走内存**：翻页时书架/进度条要立刻看到变化，不能每次都查库。
 * 2. **写要防抖**：快速滑动时每一页都会回调，直接写库会在几秒内产生上百次 IO。
 *    做法是只把 chapterId 标脏，[WRITE_DEBOUNCE_MS] 后一次性 flush。
 *
 * 口径与 [com.example.mangareader.ui.bookshelf.model.MangaVolumeUiModel] 一致：
 * [ReadingProgress.lastReadPage] 是**已读页数**（1 起），阅读器回传的 0 起 pageIndex 由
 * [recordPage] 内部 `+1` 转换。
 */
// debounce 目前仍是 FlowPreview API，显式标注以免升级协程版本后踩迁移
@OptIn(kotlinx.coroutines.FlowPreview::class)
class ReadingProgressRepository(
    private val dao: ReadingProgressDao,
    private val scope: CoroutineScope,
) {

    data class ReadingProgress(
        val lastReadPage: Int = 0,   // 已读页数（1 起）
        val totalPages: Int = 0,     // 打开过才知道
        /** 合并跨页模式：0=关闭，1=与上一页合并，2=与下一页合并（对应 SpreadJoinMode.ordinal） */
        val spreadJoinMode: Int = 0,
        /** 合并跨页锚点页码（0 起）；-1 表示未设置 */
        val spreadJoinAnchor: Int = -1,
    )

    private val _progress = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ReadingProgress>> = _progress.asStateFlow()

    private val dirty = HashSet<String>()
    private val flushSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            flushSignal
                .debounce(WRITE_DEBOUNCE_MS)
                .collect { flush() }
        }
    }

    /** 冷启动任务：打开数据库并把历史指针读进内存。MainViewModel 会等它完成。 */
    suspend fun loadFromDisk() {
        val list = runCatching { dao.getAll() }.getOrDefault(emptyList())
        _progress.value = list.associate {
            it.chapterId to ReadingProgress(
                lastReadPage = it.lastReadPage,
                totalPages = it.totalPages,
                spreadJoinMode = it.spreadJoinMode,
                spreadJoinAnchor = it.spreadJoinAnchor,
            )
        }
    }

    /** 阅读器翻页时调用（pageIndex 为 0 起） */
    fun recordPage(chapterId: String, pageIndex: Int) {
        val next = pageIndex + 1
        _progress.update { map ->
            val old = map[chapterId]
            // 只前进不回退：往回翻页不该把进度倒退
            if (old != null && old.lastReadPage >= next) return@update map
            map + (chapterId to (old ?: ReadingProgress()).copy(lastReadPage = next))
        }
        markDirty(chapterId)
    }

    /** 打开文档后补记总页数，书架才能算百分比 */
    fun recordPageCount(chapterId: String, totalPages: Int) {
        _progress.update { map ->
            val old = map[chapterId] ?: ReadingProgress()
            if (old.totalPages == totalPages) return@update map
            map + (chapterId to old.copy(totalPages = totalPages))
        }
        markDirty(chapterId)
    }

    fun get(chapterId: String): ReadingProgress = _progress.value[chapterId] ?: ReadingProgress()

    /**
     * 设置某本书的合并跨页状态（按源前缀的 chapterId 维度，随阅读进度一起持久化）。
     * @param mode 对应 ReadingPreferences.SpreadJoinMode.ordinal
     * @param anchor 锚点页码（0 起）；关闭态传 -1
     */
    fun setSpreadJoin(chapterId: String, mode: Int, anchor: Int) {
        _progress.update { map ->
            val old = map[chapterId] ?: ReadingProgress()
            map + (chapterId to old.copy(spreadJoinMode = mode, spreadJoinAnchor = anchor))
        }
        markDirty(chapterId)
    }

    private fun markDirty(chapterId: String) {
        synchronized(dirty) { dirty.add(chapterId) }
        flushSignal.tryEmit(Unit)
    }

    private suspend fun flush() {
        val ids = synchronized(dirty) { dirty.toList().also { dirty.clear() } }
        if (ids.isEmpty()) return
        val snapshot = _progress.value
        ids.forEach { id ->
            val p = snapshot[id] ?: return@forEach
            runCatching {
                dao.upsert(
                    ReadingProgressEntity(
                        chapterId = id,
                        lastReadPage = p.lastReadPage,
                        totalPages = p.totalPages,
                        spreadJoinMode = p.spreadJoinMode,
                        spreadJoinAnchor = p.spreadJoinAnchor,
                    )
                )
            }
        }
    }

    private companion object {
        const val WRITE_DEBOUNCE_MS = 800L
    }
}
