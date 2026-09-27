package com.example.mangareader.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mangareader.core.document.MangaDocument
import com.example.mangareader.ui.reader.model.MangaSpread
import com.example.mangareader.ui.reader.util.FoldableDeviceState

@Composable
fun DualPageSpreadView(
    document: MangaDocument,
    spread: MangaSpread,
    foldableState: FoldableDeviceState,
    onTapSide: (isLeft: Boolean) -> Unit = {},
    onTapCenter: () -> Unit = {},
    cropBorders: Boolean = false,
    scaleToFit: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 只有一侧有页 → 单页（封面 / 末页落单）。
    // 注意 RTL 下 MangaSpread.single() 把页码放在 rightPageIndex，
    // 只判断 right == null 会让日漫的单页走进双页分支、只占半屏。
    val singlePage = when {
        spread.leftPageIndex == null -> spread.rightPageIndex
        spread.rightPageIndex == null -> spread.leftPageIndex
        else -> null
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (singlePage != null) {
            // 单页（如封面）居中渲染
            MangaPageView(
                document = document,
                pageIndex = singlePage,
                onTapSide = onTapSide,
                onTapCenter = onTapCenter,
                cropBorders = cropBorders,
                scaleToFit = scaleToFit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 计算中缝间隔：物理铰链宽度或默认装订线宽度
            val targetSpacerWidth = when {
                foldableState.isSeparating -> maxOf(foldableState.hingeWidthDp, 8.dp)
                foldableState.isBookPosture -> 6.dp
                else -> 2.dp
            }

            // 平滑过渡铰链开合动画
            val animatedSpacerWidth by animateDpAsState(
                targetValue = targetSpacerWidth,
                animationSpec = tween(durationMillis = 200),
                label = "HingeWidthAnimation",
            )

            Row(modifier = Modifier.fillMaxSize()) {
                // 左半页（独立填满左半屏）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    if (spread.leftPageIndex != null) {
                        MangaPageView(
                            document = document,
                            pageIndex = spread.leftPageIndex,
                            onTapSide = onTapSide,
                            onTapCenter = onTapCenter,
                            cropBorders = cropBorders,
                            scaleToFit = scaleToFit,
                        )
                    }
                }

                // 物理铰链避让区与动态立体阴影
                Box(
                    modifier = Modifier
                        .width(animatedSpacerWidth)
                        .fillMaxHeight(),
                ) {
                    // 当处于书本模式时，中缝阴影加深增强 3D 折角感
                    val shadowAlpha = if (foldableState.isBookPosture) 0.7f else 0.35f
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = shadowAlpha),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = shadowAlpha),
                                    ),
                                ),
                            ),
                    )
                }

                // 右半页（独立填满右半屏）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    if (spread.rightPageIndex != null) {
                        MangaPageView(
                            document = document,
                            pageIndex = spread.rightPageIndex,
                            onTapSide = onTapSide,
                            onTapCenter = onTapCenter,
                            cropBorders = cropBorders,
                            scaleToFit = scaleToFit,
                        )
                    }
                }
            }
        }
    }
}
