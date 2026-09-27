package com.example.mangareader.ui.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel
import com.example.mangareader.ui.bookshelf.theme.BambooPalette

/** 书名占位块高度：3 行 × 17.sp，保证每格高度一致（见下方说明） */
private val TITLE_AREA_HEIGHT = 54.dp

/**
 * 一格竹书架：上方是隔壁板投下的内凹阴影，右侧是立柱接缝，中间放堆叠封面 + 书名。
 *
 * 本身不含背板颜色，背板由外层书架容器铺 [BambooPalette.CavityBackground]。
 */
@Composable
fun BambooShelfCell(
    series: MangaSeriesUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 绘制竹格内凹深陷阴影 + 两侧竖立竹立柱边框
            .drawBehind {
                val w = size.width
                val h = size.height

                // 1. 顶部隔板向下的投影 (落入格内的阴影)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to BambooPalette.CavityShadow.copy(alpha = 0.45f),
                        0.18f to Color.Transparent,
                    ),
                    topLeft = Offset.Zero,
                    size = size,
                )

                // 2. 右侧垂直立柱的接缝阴影
                drawLine(
                    color = BambooPalette.JointShadow,
                    start = Offset(w, 0f),
                    end = Offset(w, h),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 立体堆叠漫画主体
        MangaSeriesStack(
            coverUrl = series.coverUrl,
            totalVolumes = series.volumeCount,
            modifier = Modifier.fillMaxWidth(0.86f),
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 漫画书名（仿烫金/深色雕刻字样）
        //
        // **这块的尺寸必须写死**：封面本身是固定宽高比，唯一会变高的是书名——
        // 短名占一行、长名占两行、超长名被截断，同一行格子高低不一，
        // 底部那根竹横板就对不齐，整排看着是散的。这里统一留三行的位置，
        // 长书名基本能完整显示，短书名也照样占满，横板才连得成一条。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TITLE_AREA_HEIGHT)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = series.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = BambooPalette.TextDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}
