package com.example.mangareader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 冷启动状态：只负责回答「开屏还能不能撤」。
 *
 * 撤开屏需要**两个条件都满足**（不是谁先到算谁，否则慢数据时开屏先撤、露出空网格）：
 * 1. `init` 里的冷启动任务跑完 —— 现在是打开 Room 数据库并把阅读历史指针读进内存；
 * 2. 界面首屏数据就绪后回调 [onFirstScreenReady]
 *    （由 [com.example.mangareader.ui.nav.MangaReaderNav] 在 BookshelfViewModel.refresh() 返回后调用）。
 *
 * [SPLASH_TIMEOUT_MS] 是兜底：改成「两者都满足」之后，如果某个慢源（WebDAV）一直不返回，
 * 开屏会永久停留，必须有超时强行放行。
 *
 * 注意：真正的书架扫描不在本 ViewModel 里，避免和 BookshelfViewModel 重复列目录。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val progressRepository = (application as MangaApplication).progressRepository

    private val bootTasksDone = MutableStateFlow(false)
    private val firstScreenReady = MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> =
        combine(bootTasksDone, firstScreenReady) { boot, ready -> !(boot && ready) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            // 冷启动关键任务：建库 + 载入阅读历史指针（失败也不能卡住启动，故吞异常）
            runCatching { progressRepository.loadFromDisk() }
            bootTasksDone.value = true
        }

        viewModelScope.launch {
            // 兜底超时：慢源不返回时强行放行，避免开屏永久停留
            delay(SPLASH_TIMEOUT_MS)
            bootTasksDone.value = true
            firstScreenReady.value = true
        }
    }

    /** 界面首屏（书架目录）数据就绪时调用 */
    fun onFirstScreenReady() {
        firstScreenReady.value = true
    }

    private companion object {
        const val SPLASH_TIMEOUT_MS = 5000L
    }
}
