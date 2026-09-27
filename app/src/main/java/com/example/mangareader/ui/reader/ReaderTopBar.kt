package com.example.mangareader.ui.reader

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.mangareader.data.ReadingPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    isRtl: Boolean,
    onNavigateBack: () -> Unit,
    onToggleReadingDirection: () -> Unit,
    /** 当前方向锁状态；点按钮在 自动 -> 竖屏 -> 横屏 间循环 */
    orientation: ReadingPreferences.ScreenOrientation = ReadingPreferences.ScreenOrientation.AUTO,
    onCycleOrientation: () -> Unit = {},
    isDualPage: Boolean = false,
    canToggleDualPage: Boolean = false,
    onToggleDualPage: () -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
        },
        actions = {
            // 点击在 "从右往左(日漫)" 和 "从左往右(国漫/美漫)" 之间快速切换
            IconButton(onClick = onToggleReadingDirection) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = if (isRtl) "当前: 从右向左" else "当前: 从左向右",
                    tint = if (isRtl) MaterialTheme.colorScheme.primary else Color.White,
                )
            }

            // 方向锁：自动 -> 竖屏锁定 -> 横屏锁定 循环；非自动态高亮
            IconButton(onClick = onCycleOrientation) {
                Icon(
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = when (orientation) {
                        ReadingPreferences.ScreenOrientation.AUTO -> "方向: 跟随系统"
                        ReadingPreferences.ScreenOrientation.PORTRAIT -> "方向: 竖屏锁定"
                        ReadingPreferences.ScreenOrientation.LANDSCAPE -> "方向: 横屏锁定"
                    },
                    tint = if (orientation == ReadingPreferences.ScreenOrientation.AUTO) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            // 双页切换：只在大屏（折叠屏内屏 / 平板）上提供
            if (canToggleDualPage) {
                IconButton(onClick = onToggleDualPage) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = if (isDualPage) "当前: 双页" else "当前: 单页",
                        tint = if (isDualPage) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.75f),
        ),
        modifier = Modifier.statusBarsPadding(),
    )
}
