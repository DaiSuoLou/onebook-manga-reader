package com.example.mangareader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.mangareader.data.ReadingPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    isRtl: Boolean,
    onNavigateBack: () -> Unit,
    /** 当前合并跨页模式；由下拉菜单明确选择，不靠盲循环 */
    spreadJoinMode: ReadingPreferences.SpreadJoinMode = ReadingPreferences.SpreadJoinMode.OFF,
    onSetSpreadJoinMode: (ReadingPreferences.SpreadJoinMode) -> Unit = {},
    /** 当前页是否能与上一页 / 下一页合并（首/末页时对应项禁用） */
    canJoinPrevious: Boolean = true,
    canJoinNext: Boolean = true,
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
            // 手动合并跨页：点开菜单明确选择「关闭 / 与上一页合并 / 与下一页合并」。
            // 选择后状态按「这本书」持久化，不会因翻页或重新打开而消失。
            var showSpreadMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showSpreadMenu = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "合并跨页设置",
                        tint = if (spreadJoinMode == ReadingPreferences.SpreadJoinMode.OFF) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                DropdownMenu(
                    expanded = showSpreadMenu,
                    onDismissRequest = { showSpreadMenu = false },
                ) {
                    val options = listOf(
                        ReadingPreferences.SpreadJoinMode.OFF to "关闭合并",
                        ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS to "与上一页合并",
                        ReadingPreferences.SpreadJoinMode.JOIN_NEXT to "与下一页合并",
                    )
                    options.forEach { (mode, label) ->
                        // 首/末页时对应方向不可合并；已激活的项始终可点（即便当前页不支持，也允许保留/关闭）
                        val enabled = when (mode) {
                            ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS -> canJoinPrevious || spreadJoinMode == mode
                            ReadingPreferences.SpreadJoinMode.JOIN_NEXT -> canJoinNext || spreadJoinMode == mode
                            else -> true
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSetSpreadJoinMode(mode)
                                showSpreadMenu = false
                            },
                            enabled = enabled,
                            leadingIcon = {
                                if (spreadJoinMode == mode) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "当前选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
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
