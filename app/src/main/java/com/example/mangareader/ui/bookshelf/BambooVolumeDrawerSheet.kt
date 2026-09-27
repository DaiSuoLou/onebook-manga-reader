package com.example.mangareader.ui.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mangareader.ui.bookshelf.components.BambooDrawerHandle
import com.example.mangareader.ui.bookshelf.components.VolumeBookItem
import com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel
import com.example.mangareader.ui.bookshelf.model.MangaVolumeUiModel
import com.example.mangareader.ui.bookshelf.theme.BambooDrawerPalette
import com.example.mangareader.ui.bookshelf.theme.BambooPalette

/**
 * 从书架底部拉出的「竹匣抽屉」：展示一部作品的全部分卷。
 *
 * 三段结构：
 * 1. 匣顶 —— [BambooDrawerHandle] 铜拉手 + `drawBehind` 画的 8dp 竹框条（上缘高光 → 木色 → 暗部），
 *    把 ModalBottomSheet 的顶部伪装成一块从书架里抽出来的木板。
 * 2. [DrawerSeriesHeader] —— 套书封面 + 标题 + 卷数 + 「续读」按钮。
 * 3. 分卷网格 —— [VolumeBookItem]，[GridCells.Adaptive] 手机约 3 列、展开态自动到 5~6 列。
 *
 * @param sheetState 外部可传入以控制展开/收起；默认保留半展开态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambooVolumeDrawerSheet(
    series: MangaSeriesUiModel,
    volumes: List<MangaVolumeUiModel>,
    onVolumeClick: (MangaVolumeUiModel) -> Unit,
    onContinueReadClick: () -> Unit,
    onFetchCover: () -> Unit = {},
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    /** 卷列表正在拉取中（Room 会很快，WebDAV 可能要几秒）；仅在网络未返回时显示转圈，不阻塞已有数据的展示 */
    isLoading: Boolean = false,
    /** 该作品已手动指定过封面（kzz.moe 抓的 / 相册选的），决定「移除封面」按钮是否可用 */
    hasManualCover: Boolean = false,
    /** 抽屉里每一卷自动抽出来的封面：volumeId -> 本地绝对路径 */
    volumeCovers: Map<String, Any?> = emptyMap(),
    /** 从 kzz.moe 详情页链接抓封面 */
    onSetCoverFromUrl: (String) -> Unit = {},
    /** 从相册 / 文件选择器选一张图当封面 */
    onSetCoverFromUri: (Uri) -> Unit = {},
    /** 取消手动封面 */
    onClearCover: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = BambooDrawerPalette.DrawerInnerLining,
        dragHandle = {
            // 匣顶仿古黄铜暗槽拉手
            BambooDrawerHandle()
        },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier
            // 顶层竹匣边框质感与合页立体阴影
            .drawBehind {
                val w = size.width
                // 顶部竹框条高光与木质层
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            BambooPalette.BambooLight,
                            BambooDrawerPalette.DrawerWoodBase,
                            BambooDrawerPalette.DrawerWoodDark
                        ),
                        startY = 0f,
                        endY = 8.dp.toPx()
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, 8.dp.toPx())
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f) // 抽屉最高占用屏幕 82%
        ) {
            // 顶部简报区：封面 + 书名 + 继续阅读按钮
            DrawerSeriesHeader(
                series = series,
                volumes = volumes,
                onContinueReadClick = onContinueReadClick,
                hasManualCover = hasManualCover,
                onSetCoverFromUrl = onSetCoverFromUrl,
                onSetCoverFromUri = onSetCoverFromUri,
                onClearCover = onClearCover,
            )

            // 抽屉内槽隔线
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(BambooPalette.BambooDark.copy(alpha = 0.25f))
            )

            // 匣内分卷网格：手机屏幕 3 列，折叠屏内屏自适应展开为 5~6 列
            if (isLoading && volumes.isEmpty()) {
                // 首次拉取（尚无数据）时给个反馈，避免抽屉开出来是个空匣子
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = BambooDrawerPalette.BrassBase,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(volumes, key = { it.id }) { volume ->
                        VolumeBookItem(
                            volume = volume,
                            coverOverride = volumeCovers[volume.id],
                            onClick = { onVolumeClick(volume) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSeriesHeader(
    series: MangaSeriesUiModel,
    volumes: List<MangaVolumeUiModel>,
    onContinueReadClick: () -> Unit,
    hasManualCover: Boolean,
    onSetCoverFromUrl: (String) -> Unit,
    onSetCoverFromUri: (Uri) -> Unit,
    onClearCover: () -> Unit,
) {
    // LocalContext.current 是 @Composable，必须在 remember 外面取
    val context = LocalContext.current

    var showCoverDialog by remember { mutableStateOf(false) }

    // 从相册 / 文件里挑一张图当封面
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onSetCoverFromUri(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主套书微缩封面
        Box(
            modifier = Modifier
                .width(58.dp)
                .aspectRatio(0.72f)
                .shadow(6.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
        ) {
            AsyncImage(
                model = remember(series.coverUrl, context) {
                    ImageRequest.Builder(context)
                        .data(series.coverUrl)
                        .crossfade(true)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 漫画标题与信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
                color = BambooPalette.TextDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "全套共 ${volumes.size} 卷",
                fontSize = 12.sp,
                color = BambooPalette.TextDark.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // "继续阅读" 竹刻木纹按钮
            Button(
                onClick = onContinueReadClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BambooPalette.TagWood,
                    contentColor = BambooPalette.BambooLight
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "续读", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 设置封面：kzz.moe 详情页链接 / 相册图片
            TextButton(
                onClick = { showCoverDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "封面",
                    fontSize = 12.sp,
                    color = BambooPalette.TextDark.copy(alpha = 0.7f),
                )
            }
        }
    }

    if (showCoverDialog) {
        SetCoverDialog(
            hasManualCover = hasManualCover,
            onSetCoverFromUrl = { url ->
                onSetCoverFromUrl(url)
                showCoverDialog = false
            },
            onPickFromGallery = { pickCover.launch("image/*") },
            onClearCover = {
                onClearCover()
                showCoverDialog = false
            },
            onDismiss = { showCoverDialog = false },
        )
    }
}

/**
 * 设置封面对话框。
 *
 * kzz.moe 的搜索接口要登录，没法按书名自动搜；但详情页是公开的，
 * 所以走「浏览器打开漫画详情页 → 复制链接 → 粘到这里」这条路拿 og:image。
 */
@Composable
private fun SetCoverDialog(
    hasManualCover: Boolean,
    onSetCoverFromUrl: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onClearCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置封面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("kzz.moe 漫画详情页链接") },
                    placeholder = { Text("https://kzz.moe/c/12345.htm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "在浏览器里打开该漫画的详情页，复制链接粘贴到这里；也可以从相册选一张本地图片。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSetCoverFromUrl(url.trim()) }) { Text("抓取封面") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPickFromGallery) { Text("相册") }
                if (hasManualCover) {
                    TextButton(onClick = onClearCover) { Text("移除") }
                }
            }
        },
    )
}
