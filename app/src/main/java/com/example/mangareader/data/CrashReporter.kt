package com.example.mangareader.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获。
 *
 * 之前装完 APK 从安装器点「打开」就闪退，但没有 adb 连不上手机就永远看不到日志，
 * 只能靠猜 —— 这几次连续猜错就是这么来的。所以把栈顶写进文件，并在下一次启动时
 * 显示出来，用户截图/口述即可定位。
 *
 * **两条硬规矩（踩过的坑）：**
 * 1. 只捕获「未处理异常」。协程里的异常若没人 catch 会走 CoroutineExceptionHandler，
 *    这里兜不到，所以关键路径（网络扫描、设置页测试）在业务代码里都要自己 try/catch。
 * 2. **这里绝对不能直接弹 Dialog/Toast**。第一版是在 MainActivity.onCreate 里
 *    `runOnUiThread { AlertDialog.show() }`，一旦 Activity 已经 finish/destroy（例如从
 *    安装器启动后立刻被回收），show() 会抛 `BadTokenException`，于是「崩溃 → 弹窗失败 → 再崩溃」
 *    形成死循环，用户看到的就是「怎么开都闪退、什么信息都拿不到」。
 *    所以展示交给 Compose 的 [com.example.mangareader.ui.CrashDiagnosisScreen]，
 *    它不需要 window token，不可能因为展示而二次崩溃。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val MAX_FILES = 20
    private const val MAX_STACK = 6000
    private const val PREF_NAME = "diagnostics"
    private const val KEY_LAST_CRASH = "last_crash_at"

    /** 距上次崩溃多久以内算「连锁崩溃」，期间启动直接进诊断页而不是主界面 */
    const val SAFE_MODE_WINDOW_MS = 60_000L

    /** 下一次启动给界面展示用的摘要；UI 取走即清空，避免每次启动都糊一个弹窗 */
    @Volatile
    private var pending: String? = null

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(context, thread, throwable) }
                .onFailure { Log.e(TAG, "写崩溃日志失败", it) }
            try {
                defaultHandler?.uncaughtException(thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "转发给默认 handler 失败", t)
            }
        }
    }

    /** 取出并清空上一次启动的崩溃摘要；没有则 null */
    fun consumeReport(): String? {
        val report = pending
        if (report != null) pending = null
        return report
    }

    /**
     * 上次崩溃距今是否很近。
     *
     * 「启动即崩」的连锁反应：进程刚起来就挂，用户连按钮都点不到，
     * 这时直接进诊断页（能滚动、能截图、能选择重置），比先进主界面再崩一次有用得多。
     */
    fun shouldStartSafeMode(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CRASH, 0L)
            .let { last -> last != 0L && now - last < SAFE_MODE_WINDOW_MS }

    /** 用户选择「忽略并继续」后清掉连锁标记，下次正常进主界面 */
    fun clearSafeMode(context: Context) = runCatching {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_CRASH)
            .apply()
    }

    /**
     * 崩溃日志目录。放在外部私有目录（Android/data/<包名>/files/diagnostics），
     * 用户用文件管理器就能打开，不用连 adb。
     */
    fun crashDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val text = buildString {
            append("时间：$stamp\n")
            append("线程：${thread.name}\n")
            append("包名：${context.packageName}\n")
            append("系统版本：Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append(throwable.stackTraceToString().take(MAX_STACK))
        }
        synchronized(this) { pending = text }

        runCatching {
            val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
            File(crashDir(context), "crash-$name.txt").writeText(text)
            // 只留最近若干份，避免反复崩溃把内部存储塞满
            val dir = crashDir(context)
            val old = dir.listFiles().orEmpty().sortedBy { it.name }
            if (old.size > MAX_FILES) old.take(old.size - MAX_FILES).forEach { it.delete() }
        }

        // 记下崩溃时刻：下次启动据此判断是否躲进诊断页
        runCatching {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CRASH, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 「清空数据并重启」：清掉本机 DataStore 偏好与所有缓存。
     * 用于诊断页里「实在打不开」时的兜底 —— 阅读设置、导入目录、缓存上限、自定义封面全丢，
     * 但 Room 的阅读进度不动（它在 databases/ 下）。
     */
    fun resetApp(context: Context) {
        runCatching {
            context.cacheDir?.deleteRecursively()
            File(context.filesDir, "datastore").deleteRecursively()
            for (f in context.filesDir.listFiles().orEmpty()) {
                if (f.name.contains("preferences") || f.name.startsWith("cov_")) f.delete()
            }
            clearSafeMode(context)
        }
    }
}
