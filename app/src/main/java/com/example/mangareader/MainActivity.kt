package com.example.mangareader

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.mangareader.data.CrashReporter
import com.example.mangareader.data.Diag
import com.example.mangareader.ui.bookshelf.BookshelfViewModel
import com.example.mangareader.ui.nav.MangaReaderNav

class MainActivity : ComponentActivity() {

    // 冷启动状态（扫描本地书架 / 读 WebDAV 缓存期间保持开屏）
    private val mainViewModel: MainViewModel by viewModels()

    // 与 MangaReaderNav 里 viewModel() 取到的是同一个实例（同一 Activity + 同样的默认 key）
    private val bookshelfViewModel: BookshelfViewModel by viewModels()

    /**
     * SAF 选目录。注册必须早于生命周期 CREATED，所以写成属性初始化器而不是 onCreate 里。
     * 拿到的 uri 交给 BookshelfViewModel 去申请持久权限 —— 权限是"永久"的，
     * 用户下次打开 App 无需重新选。
     *
     * 自定义 contract：必须显式加 `FLAG_GRANT_READ_URI_PERMISSION(0x1)` 与
     * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION(0x40)`，否则某些 ROM 的 DocumentsUI
     * 不会在结果 uri 上附带可持久化授权，重启 App 后就读不到目录了。
     */
    private val pickDirectoryLauncher = registerForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: android.content.Context, input: Uri?): Intent {
                return super.createIntent(context, input)
                    .addFlags(BookshelfViewModel.SAF_INTENT_FLAGS)
            }
        },
    ) { uri ->
        if (uri != null) bookshelfViewModel.importRoot(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. 初始化 SplashScreen（必须在 super.onCreate 之前执行）
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1.5 上一次启动的崩溃详情。
        //
        // **这里绝不能直接弹 Dialog**：Activity 在崩溃后可能已被回收，此时 show() 会抛
        // BadTokenException —— 于是「崩溃 → 弹窗二次崩溃 → 再崩溃」死循环，用户看到的就是
        // 「怎么开都闪退、什么信息都拿不到」。第一版正是这么栽的。
        // 改为交给 Compose 渲染：没有 window token，一定能画出来（见 CrashDiagnosisScreen）。
        val crashReport = CrashReporter.consumeReport()
        val safeMode = CrashReporter.shouldStartSafeMode(this)
        val activity = this

        // 2. 状态保持：数据准备完成前保持开屏图不消失
        splashScreen.setKeepOnScreenCondition {
            mainViewModel.isLoading.value
        }

        // 3. 自定义退出动画：开屏图平滑淡出，露出下面的书架。
        //
        // **不要再碰 `splashScreenViewProvider.iconView`** —— 它在部分 ROM / Android 16 上是
        // null，而 `SplashScreenViewProvider.getIconView()` 内部就按非空解引用，
        // 一调用必抛 NPE。上一版「从安装器点打开就闪退」正是死在这里：异常发生在
        // `SplashScreenManagerGlobal.handOverSplashScreenView` 里，开屏还没交出去就崩了，
        // 用户看到的只有闪退。只动整块 view 的透明度，不碰它内部字段。
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            runCatching {
                val fade = ObjectAnimator.ofFloat(splashScreenViewProvider.view, View.ALPHA, 1f, 0f)
                fade.interpolator = AnticipateInterpolator()
                fade.duration = EXIT_ANIM_DURATION_MS
                // 动画结束必须主动 remove，否则开屏 View 会一直盖在界面上
                fade.doOnEnd { splashScreenViewProvider.remove() }
                fade.start()
            }.onFailure {
                // 任何环节出问题都直接把开屏撤掉：宁可动画生硬，也不能把用户卡在开屏上
                runCatching { splashScreenViewProvider.remove() }
                    .onFailure { android.util.Log.w("MainActivity", "开屏退场失败", it) }
            }
        }

        // 4. 进入 Compose 主界面
        setContent {
            if (safeMode) {
                // 上次是「启动即崩」：先顶一个能滚动、能截图的诊断页，
                // 否则用户会以为「怎么开都是闪退」而拿不到任何信息。
                com.example.mangareader.ui.CrashDiagnosisScreen(
                    report = crashReport,
                    crashDirPath = CrashReporter.crashDir(application).absolutePath,
                    onContinue = {
                        CrashReporter.clearSafeMode(activity)
                        activity.recreate()
                    },
                    onReset = {
                        CrashReporter.resetApp(application)
                        restartApp()
                    },
                )
            } else {
                MangaReaderNav(
                    onFirstScreenReady = { mainViewModel.onFirstScreenReady() },
                    // 传的是"触发选目录"，真正的 uri 回调由上面的 launcher 接住再交给 ViewModel
                    onImportDirectory = { pickDirectoryLauncher.launch(null) },
                )
            }
        }
    }

    /** 清空数据后重启：回到桌面图标那条的启动路径，已知可用 */
    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        finish()
        if (intent != null) application.startActivity(intent)
        else finish()
    }

    /**
     * 阅读器在位时注册的音量键回调（音量键翻页）。
     *
     * **为什么不用 Compose 的 onKeyEvent**：音量键只有落到持有焦点的 View 上才走
     * KeyInput 体系，阅读器页面没有焦点时根本收不到；dispatchKeyEvent 是 Activity
     * 收所有按键的第一站，在这里拦最可靠。返回 true 表示已消费，系统不再调音量。
     */
    internal var volumeKeyHandler: ((keyCode: Int) -> Boolean)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val handler = volumeKeyHandler
            if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            ) {
                // 这台 ROM 吞 logcat，音量键翻页是否到达/是否注册只能靠文件埋点诊断
                Diag.d(this, "VolKey", "dispatch code=${event.keyCode} handler=${handler != null}")
                if (handler != null && handler(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val EXIT_ANIM_DURATION_MS = 350L
    }
}
