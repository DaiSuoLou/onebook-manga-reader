package com.example.mangareader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    isRtl: Boolean,
    onPageSelected: (Int) -> Unit,
    /** 屏幕亮度；-1 = 跟随系统 */
    brightness: Float = -1f,
    onBrightnessChange: (Float) -> Unit = {},
) {
    var sliderValue by remember(currentPage) {
        mutableFloatStateOf(currentPage.toFloat())
    }

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 页码文本
            Text(
                text = "${currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 翻页进度滑动条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        onPageSelected(sliderValue.toInt().coerceIn(0, totalPages - 1))
                    },
                    valueRange = 0f..maxOf(0f, (totalPages - 1).toFloat()),
                    steps = if (totalPages > 2) totalPages - 2 else 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 亮度调节：拖动即离开"跟随系统"；点"自动"恢复系统亮度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Brightness6,
                    contentDescription = "亮度",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
                Slider(
                    value = if (brightness < 0f) 1f else brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.02f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                TextButton(
                    onClick = { onBrightnessChange(-1f) },
                    // 已经是"跟随系统"时按钮置灰，避免无效点击
                    enabled = brightness >= 0f,
                ) {
                    Text(
                        text = if (brightness < 0f) {
                            "自动"
                        } else {
                            "${(brightness * 100).roundToInt()}%"
                        },
                    )
                }
            }
        }
    }
}
