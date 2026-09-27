package com.example.mangareader.ui.reader.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.collectLatest

/**
 * 设备折叠状态与折痕信息
 */
data class FoldableDeviceState(
    /** 是否处于像书本一样半展开的竖直折叠状态（Book Posture） */
    val isBookPosture: Boolean = false,
    /** 物理铰链是否产生有效遮挡或分离了屏幕两侧 */
    val isSeparating: Boolean = false,
    /** 物理折痕/铰链的实际宽度（Dp） */
    val hingeWidthDp: Dp = 0.dp,
    /** 折痕在物理屏幕上的像素矩形 */
    val hingeBounds: Rect? = null,
) {
    /** 是否存在可用的铰链特征（非折叠设备恒为 false） */
    val hasHinge: Boolean get() = hingeBounds != null
}

@Composable
fun rememberFoldableState(): FoldableDeviceState {
    val context = LocalContext.current
    val activity = context as? Activity
    val density = LocalDensity.current
    // 旋转 / 内外屏切换都会改 configuration，把它放进 key，
    // 保证换了屏幕后一定能重新采集一次（不然 folding 状态可能还停在旧屏幕上）。
    val configuration = LocalConfiguration.current

    var state by remember { mutableStateOf(FoldableDeviceState()) }

    LaunchedEffect(activity, density, configuration) {
        if (activity == null) {
            state = FoldableDeviceState()
            return@LaunchedEffect
        }

        // androidx.window 的折叠信息来自厂商 OEM 扩展库，缺库/版本不对时在部分机型上
        // 会直接抛异常。这里是在协程里抛的 —— 界面捕获不到，默认协程异常处理器会
        // 走 Thread 的 uncaught handler，结果是「整个进程被带崩」，
        // 表现就是切换内外屏时闪退。所以整段包起来，拿不到折叠信息就按普通大屏处理。
        val tracker = try {
            WindowInfoTracker.getOrCreate(activity)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "折叠信息不可用（厂商扩展缺失），退回按屏幕宽度判定", t)
            null
        }

        if (tracker == null) {
            state = FoldableDeviceState()
            return@LaunchedEffect
        }

        runCatching {
            tracker.windowLayoutInfo(activity).collectLatest { layoutInfo ->
                // 筛选出垂直方向的折叠特征（左右分屏阅读场景）
                val foldingFeature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }

                if (foldingFeature != null) {
                    val bounds = foldingFeature.bounds
                    // 悬停半开状态（角度约 80°~120°），即官方定义的书本模式
                    val isHalfOpened = foldingFeature.state == FoldingFeature.State.HALF_OPENED
                    // 将像素跨度转换为 Dp 尺寸
                    val hingeWidth = with(density) { bounds.width().toDp() }
                    state = FoldableDeviceState(
                        isBookPosture = isHalfOpened,
                        isSeparating = foldingFeature.isSeparating,
                        // 如果物理铰链有厚度则取真实宽度，否则平整展开时保留 0
                        hingeWidthDp = hingeWidth,
                        hingeBounds = bounds,
                    )
                } else {
                    state = FoldableDeviceState()
                }
            }
        }.onFailure { t ->
            android.util.Log.w(TAG, "采集折叠状态失败，退回按屏幕宽度判定", t)
            state = FoldableDeviceState()
        }
    }

    return state
}

private const val TAG = "FoldableDeviceState"
