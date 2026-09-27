package com.example.mangareader.ui.bookshelf

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.example.mangareader.data.CredentialsStore
import com.example.mangareader.data.LibraryPreferences
import com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel
import com.example.mangareader.ui.bookshelf.model.MangaVolumeUiModel

/**
 * 书架页的路由层：把「书架网格」和「分卷抽屉」两个纯展示组件接起来，并负责加载卷列表。
 *
 * 拆分的理由：展示组件（`BambooBookshelfScreen` / `BambooVolumeDrawerSheet`）不持有任何
 * 加载逻辑，可以单独 Preview 和复用；数据获取（Room / WebDAV）全部收敛在这里。
 *
 * @param loadVolumesForSeries 按 seriesId 拉取该套的所有卷。可以是挂起函数
 *                             （Room 查询或 WebDAV 列目录）；切换套书时上一次的加载会被自动取消。
 * @param navigateToReader 打开阅读器并定位到指定卷
 * @param navigateToLastRead 打开阅读器并定位到该套书上次读到的位置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambooBookshelfRoute(
    allSeries: List<MangaSeriesUiModel>,
    /** 已通过 SAF 授权进来的目录，用于在顶栏「导入目录」菜单里展示和撤销 */
    roots: List<LibraryPreferences.Root>,
    /** WebDAV 账号，用于按账号显示分类标签 */
    davAccounts: List<CredentialsStore.DavAccount>,
    loadVolumesForSeries: suspend (seriesId: String) -> List<MangaVolumeUiModel>,
    /** 抽屉里每一卷自动抽出来的封面：volumeId -> 本地绝对路径 */
    volumeCovers: Map<String, Any?> = emptyMap(),
    navigateToReader: (seriesId: String, volumeId: String) -> Unit,
    navigateToLastRead: (seriesId: String) -> Unit,
    onOpenWebDavSync: () -> Unit,
    onImportDirectory: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 调试入口：直接按源内相对路径打开一卷（长按书架标题触发） */
    onDebugOpenPath: (String) -> Unit = {},
    /** 从 kzz.moe 详情页链接抓封面 */
    onSetCoverFromUrl: (seriesId: String, comicUrl: String) -> Unit = { _, _ -> },
    /** 从相册 / 文件选择器选一张图当封面 */
    onSetCoverFromUri: (seriesId: String, uri: Uri) -> Unit = { _, _ -> },
    /** 取消手动封面 */
    onClearCover: (seriesId: String) -> Unit = {},
    /** 扫描过程中各来源的失败原因，空书架时展示给用户 */
    scanProblems: List<String> = emptyList(),
    /** 重新扫描书架 */
    onRetryScan: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedSeries by remember { mutableStateOf<MangaSeriesUiModel?>(null) }
    var seriesVolumes by remember { mutableStateOf<List<MangaVolumeUiModel>>(emptyList()) }
    var isLoadingVolumes by remember { mutableStateOf(false) }
    var debugDialogVisible by remember { mutableStateOf(false) }
    var debugPath by remember { mutableStateOf("") }

    // 主书架网格
    BambooBookshelfScreen(
        seriesList = allSeries,
        onSeriesClick = { clickedSeries ->
            selectedSeries = clickedSeries
            // 注意：不在这里同步加载卷列表 —— 见下方 LaunchedEffect
        },
        roots = roots,
        davAccounts = davAccounts,
        onOpenWebDavSync = onOpenWebDavSync,
        onImportDirectory = onImportDirectory,
        onRemoveRoot = onRemoveRoot,
        onOpenSettings = onOpenSettings,
        onTitleLongClick = { debugDialogVisible = true },
        scanProblems = scanProblems,
        onRetryScan = onRetryScan,
        modifier = modifier,
    )

    // 调试：直接打开指定目录，省得为了测阅读器先导入一整套书
    if (debugDialogVisible) {
        DebugOpenPathDialog(
            path = debugPath,
            onPathChange = { debugPath = it },
            onDismiss = { debugDialogVisible = false },
            onConfirm = {
                debugDialogVisible = false
                if (debugPath.isNotBlank()) onDebugOpenPath(debugPath.trim())
            },
        )
    }

    // 抽屉弹出层
    selectedSeries?.let { currentSeries ->
        // 以 seriesId 为 key：切换套书或关闭抽屉时，上一次未完成的加载会被取消，
        // 避免慢请求（WebDAV）后到并覆盖掉新选中的套书数据。
        LaunchedEffect(currentSeries.id) {
            isLoadingVolumes = true
            seriesVolumes = emptyList() // 先清空，避免上一套的卷在抽屉里残留一帧
            seriesVolumes = runCatching { loadVolumesForSeries(currentSeries.id) }
                // TODO: 失败目前只是静默留空，后续应把 error 抛给上层弹 Snackbar
                .getOrDefault(emptyList())
            isLoadingVolumes = false
        }

        BambooVolumeDrawerSheet(
            series = currentSeries,
            volumes = seriesVolumes,
            volumeCovers = volumeCovers,
            isLoading = isLoadingVolumes,
            hasManualCover = currentSeries.hasManualCover,
            onSetCoverFromUrl = { comicUrl -> onSetCoverFromUrl(currentSeries.id, comicUrl) },
            onSetCoverFromUri = { uri -> onSetCoverFromUri(currentSeries.id, uri) },
            onClearCover = { onClearCover(currentSeries.id) },
            onVolumeClick = { volume ->
                // 先收抽屉再跳转：从阅读器返回时不会看到还挂着的抽屉
                selectedSeries = null
                navigateToReader(currentSeries.id, volume.id)
            },
            onContinueReadClick = {
                selectedSeries = null
                navigateToLastRead(currentSeries.id)
            },
            // 关闭时不主动清空 seriesVolumes：清了会让抽屉在收起动画途中闪一下空白，
            // 下次打开由 LaunchedEffect 负责重置。
            onDismissRequest = {
                selectedSeries = null
            },
        )
    }
}

/** 调试用：输入源内相对路径，直接开卷 */
@Composable
private fun DebugOpenPathDialog(
    path: String,
    onPathChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调试：直接打开") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("相对 manga 根目录的路径") },
                placeholder = { Text("OnePiece/第01话") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("打开") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
