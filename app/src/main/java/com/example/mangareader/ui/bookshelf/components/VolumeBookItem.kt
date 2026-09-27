package com.example.mangareader.ui.bookshelf.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mangareader.ui.bookshelf.model.MangaVolumeUiModel
import com.example.mangareader.ui.bookshelf.theme.BambooDrawerPalette
import com.example.mangareader.ui.bookshelf.theme.BambooPalette

/**
 * 抽屉里的一册：微缩封皮 + 卷名 + 进度/页数。
 *
 * 状态角标优先级：已读完（[Icons.Filled.CheckCircle]）> 云端未下载（[Icons.Filled.CloudDownload]），
 * 即"读完了"比"没缓存"更值得展示；其余情况右上角留空。
 *
 * 底部的进度条只在「读过但没读完」时出现（[MangaVolumeUiModel.progress] > 0 且未完结），
 * 未读和已读完都退化成页数 `${totalPages}P` —— 避免出现一条 0% 或 100% 的空进度条。
 *
 * @param onClick 点击进入阅读器
 */
@Composable
fun VolumeBookItem(
    volume: MangaVolumeUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverOverride: Any? = null,
) {
    // LocalContext.current 是 @Composable，必须在 remember 外面取
    val context = LocalContext.current
    val effectiveCover = coverOverride ?: volume.coverUrl

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 单卷微缩立体封皮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .shadow(4.dp, RoundedCornerShape(3.dp))
                .background(Color(0xFF2C2C34), RoundedCornerShape(3.dp))
        ) {
            AsyncImage(
                model = remember(effectiveCover, context) {
                    ImageRequest.Builder(context)
                        .data(effectiveCover)
                        .crossfade(true)
                        .build()
                },
                contentDescription = volume.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 左侧装订折痕反光
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color.White.copy(alpha = 0.3f),
                            0.06f to Color.Transparent
                        )
                    )
            )

            // 状态角标（已读完或 WebDAV 待缓存）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                if (volume.isFinished) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已读完",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                } else if (!volume.isDownloaded) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = "云端未下载",
                        tint = BambooDrawerPalette.BrassLight,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 卷名：把行距拉开，确保长书名至少能显示 3 行
        Text(
            text = volume.title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = BambooPalette.TextDark
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 细线阅读进度条
        if (volume.progress > 0f && !volume.isFinished) {
            LinearProgressIndicator(
                progress = volume.progress,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = BambooDrawerPalette.ProgressFill,
                trackColor = Color(0x228B5A2B)
            )
        } else {
            // 页数提示
            Text(
                text = "${volume.totalPages}P",
                fontSize = 10.sp,
                color = BambooPalette.TextDark.copy(alpha = 0.6f)
            )
        }
    }
}
