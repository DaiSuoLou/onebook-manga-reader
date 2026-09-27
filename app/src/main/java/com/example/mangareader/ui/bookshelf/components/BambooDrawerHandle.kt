package com.example.mangareader.ui.bookshelf.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mangareader.ui.bookshelf.theme.BambooDrawerPalette

/**
 * 抽屉面板上的仿古铜拉手。
 *
 * 画法是「先暗槽后铜条」：底层铺一条半透明黑的圆角暗槽（[Color] 0x40 黑）表现面板上
 * 铣出来的凹槽，上面再叠一条内缩 4dp/2dp 的铜条，用 BrassLight → BrassBase → BrassDark
 * 的**竖向**渐变模拟圆柱拉手的上亮下暗（受光在上方）。
 *
 * 铜条内缩的量就是槽壁厚度，改这两个 Offset/Size 就能调槽的宽窄，别直接改外层 size。
 *
 * 外层 Box 保留 [Alignment.Center] 是为了后续可能在拉手中央再挂东西（如卷标），
 * 目前内层直接 fillMaxSize 铺满。
 */
@Composable
fun BambooDrawerHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 10.dp)
            .size(width = 64.dp, height = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // 凹槽阴影
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // 内凹暗槽
                    drawRoundRect(
                        color = Color(0x40000000),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                    // 铜条本体（拉丝黄铜渐变）
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(
                                BambooDrawerPalette.BrassLight,
                                BambooDrawerPalette.BrassBase,
                                BambooDrawerPalette.BrassDark
                            )
                        ),
                        topLeft = Offset(4.dp.toPx(), 2.dp.toPx()),
                        size = Size(size.width - 8.dp.toPx(), size.height - 4.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
        )
    }
}
