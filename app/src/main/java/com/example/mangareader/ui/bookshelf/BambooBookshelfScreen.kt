package com.example.mangareader.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.example.mangareader.data.CredentialsStore
import com.example.mangareader.data.LibraryPreferences
import com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel
import com.example.mangareader.ui.bookshelf.theme.BambooPalette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BambooBookshelfScreen(
    seriesList: List<MangaSeriesUiModel>,
    roots: List<LibraryPreferences.Root>,
    /** WebDAV 账号（含用户自定义名称），用来给分类标签起名字 */
    davAccounts: List<CredentialsStore.DavAccount>,
    onSeriesClick: (MangaSeriesUiModel) -> Unit,
    onOpenWebDavSync: () -> Unit,
    onImportDirectory: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    /** 打开阅读设置页 */
    onOpenSettings: () -> Unit = {},
    /** 长按标题：调出入口（调试用，正式版可去掉） */
    onTitleLongClick: () -> Unit = {},
    /** 扫描过程中被吞掉的失败原因，空列表时显示出来，别让「书架一片空白」无从下手 */
    scanProblems: List<String> = emptyList(),
    /** 重新扫描书架 */
    onRetryScan: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showImportMenu by remember { mutableStateOf(false) }

    // 搜索 + 来源分类（全部在本组件内推导，roots 已由路由传入）
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 来源筛选：两级。主层选「本地书架」或「WebDAV」，选 WebDAV 后
    // 下方再列出每个具体账号（改了名字的也用名字）。null 表示「全部」。
    var selectedMain by remember { mutableStateOf<String?>(null) }
    var selectedDav by remember { mutableStateOf<String?>(null) }

    /** 主分类：全部 / 本地 / WebDAV */
    val mainCategories = remember {
        listOf(
            ShelfCategory(null, "全部"),
            ShelfCategory("local", "本地书架"),
            ShelfCategory("webdav", "WebDAV"),
        )
    }

    /** WebDAV 子分类：每个已保存的账号一项，外加一个「所有 WebDAV」 */
    val davCategories = remember(davAccounts) {
        buildList {
            add(ShelfCategory(null, "所有 WebDAV"))
            davAccounts.forEach { acc ->
                add(ShelfCategory(acc.sourceId, acc.name.ifBlank { "WebDAV" }))
            }
        }
    }

    val filteredSeries = remember(seriesList, query, selectedMain, selectedDav) {
        seriesList.filter { series ->
            val cat = categoryIdOf(series.id)
            val isWebDav = cat == "dav" || cat.startsWith("dav:")
            val matchMain = when (selectedMain) {
                null -> true
                "local" -> cat == "local"
                "webdav" -> isWebDav
                else -> cat == selectedMain
            }
            val matchDav = if (selectedMain != "webdav") true
            else (selectedDav == null || cat == selectedDav)
            matchMain && matchDav &&
                (query.isBlank() || series.title.contains(query.trim(), ignoreCase = true))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 底衬：竹片排布微纵纹背板色
            .background(BambooPalette.CavityBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 竹木质感顶栏
            TopAppBar(
                title = {
                    Text(
                        text = "我的书架",
                        color = BambooPalette.TextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        modifier = Modifier.combinedClickable(
                            onLongClick = onTitleLongClick,
                            onClick = {},
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = {
                        searchActive = !searchActive
                        if (!searchActive) query = ""
                    }) {
                        Icon(
                            imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = "搜索作品",
                            tint = BambooPalette.TextDark,
                        )
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "阅读设置",
                            tint = BambooPalette.TextDark,
                        )
                    }

                    IconButton(onClick = onOpenWebDavSync) {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = "WebDAV 云端同步",
                            tint = BambooPalette.TextDark,
                        )
                    }

                    // 导入本地漫画：没导入过时点一下直接选目录；导入过则展开列表，可继续加或撤销
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = "导入本地漫画目录",
                                tint = BambooPalette.TextDark,
                            )
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                        ) {
                            if (roots.isEmpty()) {
                                Text(
                                    text = "还没有导入任何目录",
                                    color = BambooPalette.TextDark,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                            roots.forEach { root ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = root.label,
                                        color = BambooPalette.TextDark,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                    TextButton(onClick = {
                                        showImportMenu = false
                                        onRemoveRoot(Uri.parse(root.uri))
                                    }) { Text("移除") }
                                }
                            }
                            HorizontalDivider()
                            // 全宽按钮：DropdownMenu 的 content 是 ColumnScope，
                            // 用 modifier 上的 fillMaxWidth 居中比 align 省事
                            TextButton(
                                onClick = {
                                    showImportMenu = false
                                    onImportDirectory()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("导入新目录") }
                        }
                    }

                    IconButton(onClick = onImportDirectory) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "导入本地漫画包",
                            tint = BambooPalette.TextDark,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BambooPalette.BambooBase,
                ),
                modifier = Modifier
                    .statusBarsPadding()
                    // 顶梁下沿的沉槽立体阴影
                    .drawBehind {
                        drawLine(
                            color = BambooPalette.BambooDark,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 3.dp.toPx(),
                        )
                    },
            )

            // 搜索框（点放大镜展开/收起）
            if (searchActive) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索作品…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // 来源分类：两级筛选。主层「全部 / 本地书架 / WebDAV」，
            // 选中 WebDAV 后下方再展开每个账号（含改名后的名称）。
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(mainCategories) { category ->
                    FilterChip(
                        selected = selectedMain == category.id,
                        onClick = {
                            selectedMain = category.id
                            selectedDav = null
                        },
                        label = { Text(category.label) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (selectedMain == "webdav") {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    items(davCategories) { category ->
                        FilterChip(
                            selected = selectedDav == category.id,
                            onClick = { selectedDav = category.id },
                            label = { Text(category.label) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            // 书架主体网格：列数自适应（外屏 ~2 列，展开态大屏 3~4 列）
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (filteredSeries.isEmpty() && (searchActive || selectedMain != null)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "没有匹配的作品",
                            color = BambooPalette.TextDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        )
                    }
                }

                // 空书架不再只有一个"什么都没有"：把扫描失败的原因挑要紧的说出来，
                // 再给「重新扫描 / 导入目录」两个出口。曾经这里静默失败，用户只看到一片空白。
                if (filteredSeries.isEmpty() && !searchActive && selectedMain == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp),
                        ) {
                            Text(
                                text = if (scanProblems.isEmpty()) "书架还是空的" else "书架没有读到内容",
                                color = BambooPalette.TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            if (scanProblems.isNotEmpty()) {
                                Text(
                                    text = scanProblems.joinToString("\n") { "· $it" },
                                    color = BambooPalette.TextDark,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            Row(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 20.dp),
                            ) {
                                androidx.compose.material3.TextButton(onClick = onRetryScan) {
                                    Text("重新扫描")
                                }
                                androidx.compose.material3.TextButton(onClick = onImportDirectory) {
                                    Text("导入目录")
                                }
                            }
                        }
                    }
                }
                items(filteredSeries, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 每格底部生成一块立体竹横板 (Bamboo Ledge)
                            .drawBehind {
                                val h = size.height
                                val w = size.width
                                val boardThickness = 9.dp.toPx()

                                // 竹横梁顶面微亮受光面
                                drawLine(
                                    color = BambooPalette.EdgeHighlight,
                                    start = Offset(0f, h - boardThickness),
                                    end = Offset(w, h - boardThickness),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                                // 竹横梁实心板体（渐变暖黄）
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            BambooPalette.BambooLight,
                                            BambooPalette.BambooBase,
                                            BambooPalette.BambooDark,
                                        ),
                                        startY = h - boardThickness,
                                        endY = h,
                                    ),
                                    topLeft = Offset(0f, h - boardThickness),
                                    size = Size(w, boardThickness),
                                )
                                // 横梁下方投影（投射到下一层）
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x55000000),
                                            Color.Transparent,
                                        ),
                                        startY = h,
                                        endY = h + 8.dp.toPx(),
                                    ),
                                    topLeft = Offset(0f, h),
                                    size = Size(w, 8.dp.toPx()),
                                )
                            },
                    ) {
                        BambooShelfCell(
                            series = item,
                            onClick = { onSeriesClick(item) },
                        )
                    }
                }
            }
        }
    }
}

/** 书架来源分类：id = null 表示「全部」；其余与 series id 的源前缀对应 */
private data class ShelfCategory(val id: String?, val label: String)

/**
 * series id -> 分类 id。
 *
 * 分类 id 就是作品 id 的源前缀：本机作品是裸路径（归到 `local`），
 * SAF 是 `saf:xxxx`，WebDAV 则是 `dav`（老数据）或 `dav:xxxxxxx`（新增的账号）。
 * **每个 WebDAV 账号各算一个分类**，这样用户可以在书架上只看某一个源的资源。
 */
private fun categoryIdOf(seriesId: String): String = when {
    seriesId.startsWith("dav/") -> "dav"
    seriesId.startsWith("dav:") -> seriesId.substringBefore('/')
    seriesId.startsWith("saf:") -> seriesId.substringBefore('/')
    else -> "local"
}

private fun categoryLabelOf(
    categoryId: String,
    roots: List<LibraryPreferences.Root>,
    davAccounts: List<CredentialsStore.DavAccount>,
): String = when {
    categoryId == "dav" || categoryId.startsWith("dav:") ->
        davAccounts.firstOrNull { it.sourceId == categoryId }?.name?.takeIf { it.isNotBlank() }
            ?: if (categoryId == "dav") "WebDAV" else "WebDAV ${categoryId.removePrefix("dav:")}"
    categoryId == "local" -> "本机"
    else -> roots.firstOrNull { it.sourceId == categoryId }?.label ?: "SAF 目录"
}
