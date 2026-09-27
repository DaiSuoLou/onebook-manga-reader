package com.example.mangareader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mangareader.data.CachePreferences
import com.example.mangareader.data.WebDavCacheManager
import kotlinx.coroutines.launch

/**
 * WebDAV 缓存设置区：当前占用 / 上限 / 清除。
 *
 * 单独抽出来的原因：阅读设置页和 WebDAV 设置页都要用，之前只有前者有，
 * 用户到 WebDAV 页找缓存设置自然找不到。
 */
@Composable
fun CacheSection(
    cacheManager: WebDavCacheManager,
    cachePrefs: CachePreferences,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val cacheSettings by cachePrefs.settings.collectAsState(initial = CachePreferences.Settings())
    var currentSizeMb by remember { mutableIntStateOf(0) }
    var maxSizeMb by remember(cacheSettings.maxSizeMb) { mutableIntStateOf(cacheSettings.maxSizeMb) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 统计失败不能让整页崩掉：读文件出错时退回 0MB，用户至少还能调上限和清缓存
    LaunchedEffect(Unit) {
        currentSizeMb = runCatching {
            (cacheManager.currentSizeBytes() / 1_000_000).toInt()
        }.getOrDefault(0)
    }

    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text(
            text = "当前缓存：${currentSizeMb}MB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "缓存上限",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${maxSizeMb}MB",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = maxSizeMb.toFloat(),
            onValueChange = { maxSizeMb = it.toInt() },
            onValueChangeFinished = {
                scope.launch { runCatching { cachePrefs.setMaxSizeMb(maxSizeMb) } }
            },
            valueRange = 100f..2000f,
            steps = 18,
            // 默认滑块只有 40dp 高，手指粗的时候很难拖准；撑到 56dp 好按
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )

        Button(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清除缓存")
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除缓存") },
            text = { Text("确定要删除所有 WebDAV 缓存文件吗？已下载的漫画需要重新拉取。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { cacheManager.clearCache() }
                                .onFailure { android.util.Log.e("CacheSection", "清缓存失败", it) }
                            currentSizeMb = 0
                        }
                        showClearDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 两处设置页共用的缓存目录，别再各自拼路径拼出 dav/dav */
@Composable
fun rememberCachePair(): Pair<WebDavCacheManager, CachePreferences> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { WebDavCacheManager(WebDavCacheManager.davCacheDir(context), CachePreferences(context)) }
    return manager to remember { CachePreferences(context) }
}

/** 统一给外部用的间距 */
fun Modifier.sectionPadding(): Modifier = this.padding(vertical = 8.dp)
