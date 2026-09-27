package com.example.mangareader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 崩溃诊断页：上一次启动是「启动即崩」时顶替主界面显示。
 *
 * **为什么不用 Dialog**：Activity 若在崩溃后被回收，弹窗会抛 BadTokenException 二次崩溃，
 * 结果就是「怎么开都闪退、什么都看不到」（上一版就是这么栽的）。Compose 里显示没有 window
 * token 问题，一定能画出来，用户至少能滚动、能截图、能选择重置。
 */
@Composable
fun CrashDiagnosisScreen(
    report: String?,
    crashDirPath: String,
    onContinue: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        Text(
            text = "上次启动崩溃了",
            style = MaterialTheme.typography.titleLarge,
        )

        Text(
            text = "完整日志已经写到下面的路径，用手机自带的文件管理器就能打开；" +
                "也可以直接把下面这段内容截图/复制发给我，我照着改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )

        Text(
            text = crashDirPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )

        if (report != null) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    // 崩溃栈可能很长，限高 3 行由外层滚动条接管，栈首几行才是最有用的部分
                    .padding(bottom = 16.dp),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        } else {
            Text(
                text = "（没有取到崩溃摘要，但日志文件应该已经写出）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        Row {
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f),
            ) {
                Text("忽略，直接打开书架")
            }
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空数据并重启")
            }
        }

        Text(
            text = "「清空数据」会重置阅读设置、导入的目录、缓存和自定义封面，阅读进度不丢。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
