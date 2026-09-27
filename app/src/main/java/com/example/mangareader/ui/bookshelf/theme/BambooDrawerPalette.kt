package com.example.mangareader.ui.bookshelf.theme

import androidx.compose.ui.graphics.Color

/**
 * 抽屉式收纳盒的配色。
 *
 * 和 [BambooPalette]（书架本体）分开的原因：抽屉是"从书架里拉出来的另一个物件"，
 * 木色要比隔板略暗一档，才显得盒子陷在里面；内衬用 [DrawerInnerLining] 的
 * 仿宣纸米白，把漫画封面从暖木背景里托出来；金属件统一走 [BrassLight] →
 * [BrassBase] → [BrassDark] 三段，画拉手/包角时用同一套渐变就能保持光照一致
 * （左上受光、右下压暗）。
 *
 * 抽屉的木色与 [BambooPalette] 有部分同值复用（例如 [DrawerWoodDark] 等于
 * BambooPalette.BambooDark），是刻意保留的：两套调色板独立演进时不会因为
 * "看起来一样"就被误合并，改动书架隔板不会顺手改坏抽屉。
 */
object BambooDrawerPalette {
    // 抽屉盒体（较书架表面稍暗，表现内盒深度）
    val DrawerWoodBase = Color(0xFFD4A86A)
    val DrawerWoodDark = Color(0xFFA67538)
    val DrawerInnerLining = Color(0xFFF7F3E9) // 匣内仿棉麻/宣纸衬底

    // 仿古黄铜配件（拉手、包角合页）
    val BrassLight = Color(0xFFE5C378)
    val BrassBase = Color(0xFFB88E3E)
    val BrassDark = Color(0xFF6E501A)

    // 状态标记
    val ProgressFill = Color(0xFF8B5A2B)
    val CloudIconTint = Color(0xFF7A6B58)
}
