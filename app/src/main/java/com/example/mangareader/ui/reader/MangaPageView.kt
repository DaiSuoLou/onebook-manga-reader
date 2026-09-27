package com.example.mangareader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.size.Scale
import com.example.mangareader.core.coil.BorderCropTransformation
import com.example.mangareader.core.document.MangaDocument
import com.example.mangareader.core.model.MangaPageModel
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * 单页渲染 + 统一手势层。
 *
 * **手势全部由覆盖层接管**（[GestureOverlay]），telephoto 只保留捏合缩放/拖动平移：
 * - 单击三等分区域 -> 翻页 / 呼出控制栏（方向由设置决定）
 * - 双击 -> 从双击位置放大 200%，再双击还原
 * - 长按 -> 从长按位置放大 150%，松手立即还原
 *
 * telephoto 自带的双击缩放必须关掉（`doubleClickToZoom = false`），否则两套双击
 * 检测叠加，缩放会被执行两次。它的 `onClick` 也不用：坐标空间文档没写死，不赌。
 */
@Composable
fun MangaPageView(
    document: MangaDocument,
    pageIndex: Int,
    /** 点在屏幕左/右三等分区（翻页区）；方向语义由调用方根据设置决定 */
    onTapSide: (isLeft: Boolean) -> Unit = {},
    /** 点在屏幕中间三等分区（呼出/隐藏控制栏） */
    onTapCenter: () -> Unit = {},
    /** 开启后用 [BorderCropTransformation] 裁掉四周留白 */
    cropBorders: Boolean = false,
    /** true=整页适应(Fit)，false=适应宽度(FillWidth) */
    scaleToFit: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 构造 Coil 挂载数据源
    val pageModel = remember(document, pageIndex) {
        MangaPageModel(document, pageIndex)
    }

    // 用 ImageRequest 包一层：按需挂上裁边变换（变换会并入 Coil 缓存 key，切开关即切缓存）；
    // 缩放适配走 Coil 的 Scale（FIT=整页适应，FILL=铺满屏幕），不依赖阅读器组件自身参数。
    val context = LocalContext.current
    val model = remember(document, pageIndex, cropBorders, scaleToFit) {
        ImageRequest.Builder(context)
            .data(pageModel)
            .scale(if (scaleToFit) Scale.FIT else Scale.FILL)
            .apply { if (cropBorders) transformations(BorderCropTransformation()) }
            .build()
    }

    // 配置手势缩放范围（最大允许放大 4 倍）
    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = 4.0f),
    )
    val zoomableImageState = rememberZoomableImageState(zoomableState)
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Telephoto 核心缩放组件：捏合/平移交给它。
        // 单击/双击/长按全部由上面的覆盖层接管：覆盖层会消费点按类事件，
        // telephoto 自带的双击缩放因此不会被触发，不会两套叠加。
        ZoomableAsyncImage(
            model = model,
            contentDescription = "Page ${pageIndex + 1}",
            state = zoomableImageState,
            modifier = Modifier.fillMaxSize(),
        )

        // 手势覆盖层。位置用「窗口坐标 + 屏幕宽」算三等分：
        // 双页模式下本组件只占半屏，按组件自身宽度分会把中缝当成中央区，所以必须换算到整屏。
        var windowBounds by remember { mutableStateOf(Rect.Zero) }
        val screenWidth = remember { context.resources.displayMetrics.widthPixels.toFloat() }

        var longPressZoomed by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { windowBounds = it.boundsInWindow() }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            longPressZoomed = false
                            try {
                                awaitRelease()
                            } finally {
                                // 长按放大后松手立即还原。放 finally：即便手势被
                                // 捏合抢走（CancellationException）也不能漏掉还原。
                                if (longPressZoomed) {
                                    scope.launch { zoomableState.resetZoom() }
                                }
                            }
                        },
                        onTap = { offset ->
                            val screenX = windowBounds.left + offset.x
                            val third = screenWidth / 3f
                            when {
                                screenX < third -> onTapSide(true)
                                screenX > third * 2f -> onTapSide(false)
                                else -> onTapCenter()
                            }
                        },
                        onDoubleTap = { offset ->
                            // 已放大 -> 还原；未放大 -> 围绕双击位置放大 200%。
                            // 这里消费了双击事件，telephoto 的内置双击不会再执行。
                            scope.launch {
                                val isZoomed = (zoomableState.zoomFraction ?: 0f) > 0.05f
                                if (isZoomed) {
                                    zoomableState.resetZoom()
                                } else {
                                    zoomableState.zoomTo(2f, offset)
                                }
                            }
                        },
                        onLongPress = { offset ->
                            longPressZoomed = true
                            scope.launch { zoomableState.zoomTo(1.5f, offset) }
                        },
                    )
                },
        )

        // 图片未完全加载完成时显示加载圆环
        if (!zoomableImageState.isImageDisplayed) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 3.dp,
            )
        }
    }
}
