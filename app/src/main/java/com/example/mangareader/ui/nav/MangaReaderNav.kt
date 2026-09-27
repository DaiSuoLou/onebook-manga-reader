package com.example.mangareader.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.example.mangareader.BuildConfig
import kotlinx.coroutines.launch
import com.example.mangareader.ui.bookshelf.BambooBookshelfRoute
import com.example.mangareader.ui.bookshelf.BookshelfViewModel
import com.example.mangareader.ui.reader.MangaReaderScreen
import com.example.mangareader.ui.reader.ReaderViewModel
import com.example.mangareader.ui.settings.SettingsScreen
import com.example.mangareader.ui.settings.WebDavSettingsScreen

/**
 * 全App 的界面切换：书架 <-> 阅读器。
 *
 * 没有引入 Navigation 库 —— 只有两个页面，且阅读器必须拿到一个已打开的
 * [com.example.mangareader.core.document.MangaDocument] 对象（不是可序列化的 route 参数），
 * 用 Navigation 反而要把文档塞进 SavedStateHandle，得不偿失。
 *
 * @param onFirstScreenReady 首屏数据加载完成时回调，用于通知开屏可以撤掉
 * @param onImportDirectory 触发 SAF 选目录（具体 uri 由 Activity 的 launcher 接住）
 */
@Composable
fun MangaReaderNav(
    onFirstScreenReady: () -> Unit = {},
    onImportDirectory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bookshelfViewModel: BookshelfViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()

    val allSeries by bookshelfViewModel.series.collectAsState()
    val roots by bookshelfViewModel.roots.collectAsState()
    val davAccounts by bookshelfViewModel.davAccounts.collectAsState()
    val scanProblems by bookshelfViewModel.scanProblems.collectAsState()
    val volumeCovers by bookshelfViewModel.volumeCovers.collectAsState()
    val session by readerViewModel.session.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    var webDavOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bookshelfViewModel.refresh()
        onFirstScreenReady()
    }

    // 导入 / 移除目录的结果反馈：SharedFlow 逐条消费，多条提示会排队而不是互相覆盖
    LaunchedEffect(Unit) {
        bookshelfViewModel.hints.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // 阅读器打开失败提示（格式不支持 / 文件损坏 / WebDAV 未缓存等）
    LaunchedEffect(Unit) {
        readerViewModel.openError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // 返回键拦截：设置页 / WebDAV 页打开时优先关闭这些页面；
    // 阅读器打开时关闭阅读器。否则系统返回可能直接 finish Activity。
    BackHandler(enabled = settingsOpen || webDavOpen || session != null) {
        when {
            settingsOpen -> settingsOpen = false
            webDavOpen -> webDavOpen = false
            session != null -> readerViewModel.closeReader()
        }
    }

    val currentSession = session
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier = Modifier.padding(paddingValues),
        ) {
            when {
                settingsOpen -> SettingsScreen(onBack = { settingsOpen = false })
                webDavOpen -> WebDavSettingsScreen(
                    onBack = { webDavOpen = false },
                    // 账号有增删后重建 WebDAV 源并重扫书架
                    onConfigChanged = { bookshelfViewModel.reloadWebDavSources() },
                )
                currentSession == null ->                 BambooBookshelfRoute(
                    allSeries = allSeries,
                    roots = roots,
                    davAccounts = davAccounts,
                    scanProblems = scanProblems,
                    volumeCovers = volumeCovers,
                    loadVolumesForSeries = { seriesId -> bookshelfViewModel.loadVolumes(seriesId) },
                    navigateToReader = { seriesId, volumeId ->
                        val volume = bookshelfViewModel.findVolume(seriesId, volumeId) ?: return@BambooBookshelfRoute
                        readerViewModel.openChapter(
                            chapterId = volume.id,
                            path = bookshelfViewModel.resolvePath(volume.id),
                            title = volume.title,
                            // lastReadPage 是「已读页数」，正好等于下一页的 0 起下标
                            startPage = volume.lastReadPage,
                        )
                    },
                    navigateToLastRead = { seriesId ->
                        val volume = bookshelfViewModel.lastReadVolume(seriesId) ?: return@BambooBookshelfRoute
                        readerViewModel.openChapter(
                            chapterId = volume.id,
                            path = bookshelfViewModel.resolvePath(volume.id),
                            title = volume.title,
                            startPage = volume.lastReadPage,
                        )
                    },
                    onOpenWebDavSync = { webDavOpen = true },
                    onImportDirectory = onImportDirectory,
                    onRemoveRoot = { uri -> bookshelfViewModel.removeRoot(uri) },
                    onOpenSettings = { settingsOpen = true },
                    onSetCoverFromUrl = { seriesId, comicUrl ->
                        bookshelfViewModel.setCoverFromUrl(seriesId, comicUrl)
                    },
                    onSetCoverFromUri = { seriesId, uri ->
                        bookshelfViewModel.setCoverFromUri(seriesId, uri)
                    },
                    onClearCover = { seriesId -> bookshelfViewModel.clearCover(seriesId) },
                    // refresh() 是挂起函数，而这里处于普通 lambda 里，
                    // 必须自己开一个协程，不能直接在 onClick 里调
                    onRetryScan = {
                        bookshelfViewModel.viewModelScope.launch { bookshelfViewModel.refresh() }
                    },
                    // 调试入口：只在 debug 构建里生效，release 上长按标题不会有任何反应
                    onDebugOpenPath = { relativePath ->
                        if (!BuildConfig.DEBUG) return@BambooBookshelfRoute
                        readerViewModel.openChapter(
                            chapterId = relativePath,
                            path = bookshelfViewModel.resolvePath(relativePath),
                            title = relativePath.substringAfterLast('/'),
                            startPage = 0,
                        )
                    },
                )
                else -> MangaReaderScreen(
                    title = currentSession.title,
                    document = currentSession.document,
                    initialPage = currentSession.initialPage,
                    onPageChanged = { pageIndex -> readerViewModel.onPageChanged(pageIndex) },
                    onNavigateBack = { readerViewModel.closeReader() },
                )
            }
        }
    }
}
