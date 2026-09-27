package com.example.mangareader.ui.bookshelf.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 书架的竹木配色。
 *
 * 设计意图：把书架画成一个"内陷的木箱"——背板最深（[CavityBackground] + [CavityShadow]），
 * 隔板次之（[BambooBase] → [BambooLight] 由下往上受光），竹节与边缘用半透明的
 * [JointShadow] / [EdgeHighlight] 勾出圆柱状的体积感，铭牌 [TagWood] 与文字
 * [TextDark] 落在暖色系里避免脏。
 *
 * 这套色板只服务书架界面（背景/隔板/铭牌都是手绘的，不是标准 Material 容器），
 * 所以不走 ColorScheme 的语义槽位；需要往 Material3 组件里塞时再用 [bambooColorScheme]。
 */
object BambooPalette {
    // 书架背板（稍深，呈现箱体内陷的阴影沉降感）
    val CavityBackground = Color(0xFFC79862)
    val CavityShadow = Color(0xFF6B451D)

    // 竹隔板（表面温润光泽）
    val BambooLight = Color(0xFFF1D5A6)
    val BambooBase = Color(0xFFDEB879)
    val BambooDark = Color(0xFFA67538)

    // 细部高光与竹节边缘
    val EdgeHighlight = Color(0xFFFFF1D6)
    val JointShadow = Color(0x55442508)

    // 铭牌与文字
    val TagWood = Color(0xFF8B5A2B)
    val TextDark = Color(0xFF2E1C0C)
}

/**
 * 把竹木色板映射成 Material3 的浅色 ColorScheme。
 *
 * 只在书架界面确实要用到 Material3 组件（Dialog / Snackbar / Text 默认色）时才需要；
 * 纯手绘部分直接用 [BambooPalette] 的具名颜色，语义更清楚。
 */
fun bambooColorScheme(): ColorScheme = lightColorScheme(
    primary = BambooPalette.BambooDark,
    onPrimary = BambooPalette.EdgeHighlight,
    primaryContainer = BambooPalette.BambooBase,
    onPrimaryContainer = BambooPalette.TextDark,
    secondary = BambooPalette.TagWood,
    onSecondary = BambooPalette.EdgeHighlight,
    background = BambooPalette.CavityBackground,
    onBackground = BambooPalette.TextDark,
    surface = BambooPalette.BambooLight,
    onSurface = BambooPalette.TextDark,
    surfaceVariant = BambooPalette.BambooBase,
    onSurfaceVariant = BambooPalette.TextDark,
    outline = BambooPalette.CavityShadow,
    outlineVariant = BambooPalette.JointShadow,
    scrim = BambooPalette.TextDark,
)
