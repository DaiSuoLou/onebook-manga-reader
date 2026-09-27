package com.example.mangareader.ui.reader.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 判断当前是否处于「展开态」大屏（折叠屏内屏 / 平板 / 横屏）。
 *
 * 用 LocalConfiguration.screenWidthDp：折叠屏开合、旋转、分屏都会触发重组，
 * 不需要额外依赖，也不需要 Activity 引用。
 */
@Composable
fun rememberIsExpandedScreen(): Boolean {
    val configuration = LocalConfiguration.current
    // 展开态折叠屏内屏宽度通常在 600dp ~ 840dp 以上
    return configuration.screenWidthDp >= 600
}

enum class ReaderLayoutMode { SINGLE_PAGE, DUAL_PAGE }

/**
 * 阅读布局决策：
 * - 半开书本模式（铰链两侧各一页）→ 双页
 * - 展开态大屏（screenWidthDp >= 600）→ 双页跨页
 * - 其余 → 单页
 *
 * 折叠信息统一来自 [rememberFoldableState]，宽度统一来自 [rememberIsExpandedScreen]，
 * 全项目只有这一处阈值，避免出现多套判定互相矛盾。
 */
@Composable
fun rememberReaderLayoutMode(): ReaderLayoutMode {
    val foldable = rememberFoldableState()
    val isExpanded = rememberIsExpandedScreen()
    return if (foldable.isBookPosture || isExpanded) {
        ReaderLayoutMode.DUAL_PAGE
    } else {
        ReaderLayoutMode.SINGLE_PAGE
    }
}
