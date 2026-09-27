package com.example.mangareader.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兜底的诊断日志：除了写 logcat，再往 `files/diagnostics/diag.log` 追加一份。
 *
 * **为什么需要它**：这台测试机（vivo / ColorOS）上，release 包（非 debuggable）的
 * logcat 输出会被系统吞掉 —— 同一批 `android.util.Log` 在 debug 包里能看到，
 * 打进 release 装上就一条都没有，导致「App 在跑、界面也正常，但日志全是空的」，
 * 没法判断流程走到了哪一步。写文件不受这个限制，也能直接 pull 回电脑分析。
 *
 * 只保留最近 [MAX_LINES] 行，避免长期把用户手机存储写满。
 */
object Diag {

    private const val MAX_LINES = 2000

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    /** 诊断目录：与崩溃报告同目录，用户用文件管理器就能翻 */
    fun diagDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }

    private fun target(context: Context): File = File(diagDir(context), "diag.log")

    private fun append(context: Context, line: String) {
        runCatching {
            val file = target(context)
            file.appendText("$line\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                val head = lines.size - MAX_LINES
                file.writeText(lines.subList(head, lines.size).joinToString("\n") + "\n")
            }
        }
    }

    fun i(context: Context, tag: String, message: String) =
        append(context, "${timeFormat.format(Date())} I/$tag: $message")

    fun d(context: Context, tag: String, message: String) =
        append(context, "${timeFormat.format(Date())} D/$tag: $message")

    fun w(context: Context, tag: String, message: String) =
        append(context, "${timeFormat.format(Date())} W/$tag: $message")

    fun e(context: Context, tag: String, message: String, error: Throwable? = null) =
        append(
            context,
            "${timeFormat.format(Date())} E/$tag: $message" +
                (if (error == null) "" else "\n" + error.stackTraceToString().take(2000)),
        )
}
