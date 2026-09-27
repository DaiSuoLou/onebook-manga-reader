package com.example.mangareader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mangareader.MangaApplication
import com.example.mangareader.data.ReadingPreferences
import kotlinx.coroutines.launch

/**
 * 阅读设置页：所有开关都直接读写 [ReadingPreferences]，改完即持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { (context.applicationContext as MangaApplication).readingPreferences }
    val settings by prefs.settings.collectAsState(initial = ReadingPreferences.Settings())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 缓存挪到最上面：它原本压在页面最底下，得滑到底才够得着，
            // 而「清除缓存」又是最不该误触的按钮，放顶上一眼看得见、也离手够远。
            Text("缓存", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "WebDAV 已下载文件占用的空间，超过上限会自动淘汰最早的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            val (cacheManager, cachePrefs) = rememberCachePair()
            CacheSection(cacheManager = cacheManager, cachePrefs = cachePrefs)
            HorizontalDivider()

            Text("阅读方向", style = MaterialTheme.typography.titleSmall)
            DirectionRow(
                selected = settings.direction,
                onSelect = { scope.launch { prefs.setDirection(it) } },
            )
            HorizontalDivider()

            SettingsSwitch(
                title = "双页模式",
                subtitle = "大屏 / 折叠屏书本态默认开启",
                checked = settings.doublePage,
                onCheckedChange = { scope.launch { prefs.setDoublePage(it) } },
            )
            SettingsSwitch(
                title = "屏幕常亮",
                subtitle = "阅读时保持屏幕不熄灭",
                checked = settings.keepScreenOn,
                onCheckedChange = { scope.launch { prefs.setKeepScreenOn(it) } },
            )
            SettingsSwitch(
                title = "裁剪白边",
                subtitle = "自动裁掉页面四周的均匀留白",
                checked = settings.cropBorders,
                onCheckedChange = { scope.launch { prefs.setCropBorders(it) } },
            )

            Text("缩放适配", style = MaterialTheme.typography.titleSmall)
            ScaleRow(
                scaleToFit = settings.scaleToFit,
                onSelect = { scope.launch { prefs.setScaleToFit(it) } },
            )
            HorizontalDivider()

            Text("方向锁定", style = MaterialTheme.typography.titleSmall)
            OrientationRow(
                selected = settings.orientation,
                onSelect = { scope.launch { prefs.setOrientation(it) } },
            )
            HorizontalDivider()

            // 翻页操作设置：音量键 + 点按区域
            Text("音量键翻页", style = MaterialTheme.typography.titleSmall)
            VolumeKeyRow(
                selected = settings.volumeKeyMode,
                onSelect = { scope.launch { prefs.setVolumeKeyMode(it) } },
            )
            HorizontalDivider()

            Text("点按翻页区域", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "屏幕左右三等分区域单击翻页，中间呼出控制栏；双击放大 200%，长按放大 150% 松手还原",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TapZoneRow(
                selected = settings.tapZoneMode,
                onSelect = { scope.launch { prefs.setTapZoneMode(it) } },
            )
            HorizontalDivider()

            Text(
                text = "屏幕亮度" + if (settings.brightness < 0f) "（跟随系统）" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Slider(
                    value = if (settings.brightness < 0f) 1f else settings.brightness,
                    onValueChange = { scope.launch { prefs.setBrightness(it) } },
                    valueRange = 0.02f..1f,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launch { prefs.setBrightness(-1f) } },
                    enabled = settings.brightness >= 0f,
                ) { Text("自动") }
            }

            HorizontalDivider()

            // 底部留出屏幕 1/10 的空白，确保最下面的选项不被系统手势条 / 导航条遮挡
            val bottomBlank = LocalConfiguration.current.screenHeightDp.dp / 10
            Spacer(modifier = Modifier.height(bottomBlank))
        }
    }
}

@Composable
private fun DirectionRow(
    selected: ReadingPreferences.Direction,
    onSelect: (ReadingPreferences.Direction) -> Unit,
) {
    Column {
        ReadingPreferences.Direction.entries.forEach { dir ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == dir,
                        onClick = { onSelect(dir) },
                    )
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(
                    selected = selected == dir,
                    onClick = null,
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = when (dir) {
                            ReadingPreferences.Direction.LTR -> "从左向右"
                            ReadingPreferences.Direction.RTL -> "从右向左 (日漫)"
                            ReadingPreferences.Direction.VERTICAL -> "竖向滚动 (条漫)"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrientationRow(
    selected: ReadingPreferences.ScreenOrientation,
    onSelect: (ReadingPreferences.ScreenOrientation) -> Unit,
) {
    Column {
        ReadingPreferences.ScreenOrientation.entries.forEach { o ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == o,
                        onClick = { onSelect(o) },
                    )
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(selected = selected == o, onClick = null)
                Text(
                    text = when (o) {
                        ReadingPreferences.ScreenOrientation.AUTO -> "跟随系统"
                        ReadingPreferences.ScreenOrientation.PORTRAIT -> "锁定竖屏"
                        ReadingPreferences.ScreenOrientation.LANDSCAPE -> "锁定横屏"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ScaleRow(
    scaleToFit: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = scaleToFit, onClick = { onSelect(true) })
                .padding(vertical = 8.dp),
        ) {
            RadioButton(selected = scaleToFit, onClick = null)
            Text("适应整页 (Fit)", modifier = Modifier.padding(start = 8.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = !scaleToFit, onClick = { onSelect(false) })
                .padding(vertical = 8.dp),
        ) {
            RadioButton(selected = !scaleToFit, onClick = null)
            Text("铺满屏幕 (Fill)", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun VolumeKeyRow(
    selected: ReadingPreferences.VolumeKeyMode,
    onSelect: (ReadingPreferences.VolumeKeyMode) -> Unit,
) {
    Column {
        ReadingPreferences.VolumeKeyMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Text(
                    text = when (mode) {
                        ReadingPreferences.VolumeKeyMode.OFF -> "关闭（音量键正常调节音量）"
                        ReadingPreferences.VolumeKeyMode.DOWN_IS_NEXT -> "音量− = 下一页，音量+ = 上一页"
                        ReadingPreferences.VolumeKeyMode.UP_IS_NEXT -> "音量+ = 下一页，音量− = 上一页"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TapZoneRow(
    selected: ReadingPreferences.TapZoneMode,
    onSelect: (ReadingPreferences.TapZoneMode) -> Unit,
) {
    Column {
        ReadingPreferences.TapZoneMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Text(
                    text = when (mode) {
                        ReadingPreferences.TapZoneMode.FOLLOW_DIRECTION -> "跟随阅读方向"
                        ReadingPreferences.TapZoneMode.LEFT_IS_NEXT -> "左侧点击 = 下一页"
                        ReadingPreferences.TapZoneMode.RIGHT_IS_NEXT -> "右侧点击 = 下一页"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
