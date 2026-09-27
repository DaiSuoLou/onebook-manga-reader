package com.example.mangareader.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mangareader.MangaApplication
import com.example.mangareader.MainActivity
import com.example.mangareader.core.document.MangaDocument
import com.example.mangareader.data.Diag
import com.example.mangareader.data.ReadingPreferences
import com.example.mangareader.ui.reader.model.MangaSpread
import com.example.mangareader.ui.reader.model.SpreadHelper
import com.example.mangareader.ui.reader.model.spreadIndexFor
import com.example.mangareader.ui.reader.util.FoldableDeviceState
import com.example.mangareader.ui.reader.util.rememberFoldableState
import com.example.mangareader.ui.reader.util.rememberIsExpandedScreen
import kotlinx.coroutines.launch

/**
 * 漫画阅读器核心页面。
 *
 * **设置来源**：所有阅读偏好都来自 [ReadingPreferences] 的 DataStore flow，是唯一的真源。
 * 页面内点击切换方向/双页会写回 DataStore，因此重启 App 后依然保留。
 *
 * **两种翻页形态**：
 * - LTR/RTL -> 按「展页 Spread」横向翻页（[SpreadReaderBody]）
 * - VERTICAL -> 条漫竖向滚动，单页一屏（[VerticalReaderBody]），不适用双页/镜像
 *
 * @param title 漫画标题
 * @param document 已打开的漫画文档对象
 * @param initialPage 初始页码 (0-indexed)，会自动换算成所属展页
 * @param onPageChanged 页码切换回调；双页时回传阅读顺序上的第一页
 * @param onNavigateBack 点击返回回调
 */
@Composable
fun MangaReaderScreen(
    title: String,
    document: MangaDocument,
    initialPage: Int = 0,
    onPageChanged: (pageIndex: Int) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as MangaApplication).readingPreferences
    val settings by prefs.settings.collectAsState(initial = ReadingPreferences.Settings())

    val coroutineScope = rememberCoroutineScope()
    val isExpandedScreen = rememberIsExpandedScreen()
    val foldable = rememberFoldableState()

    // 智能判定：展开态大屏、或折叠屏半开书本状态，均支持双页
    val isDualPageCapable = isExpandedScreen || foldable.isBookPosture

    // 偏好驱动：方向/双页直接由 DataStore 派生；折叠屏书本态强制双页
    val isReadingRtl = settings.direction == ReadingPreferences.Direction.RTL
    val isVertical = settings.direction == ReadingPreferences.Direction.VERTICAL
    val effectiveDualPage = !isVertical &&
        (foldable.isBookPosture || (settings.doublePage && isDualPageCapable))

    var isControlsVisible by remember { mutableStateOf(false) }

    // 当前页（供底栏显示）+ 跳页命令（底栏滑条 -> 阅读器 body 消费）
    var currentPage by remember { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }

    // 手动合并跨页时的锚点页码。用户点击合并按钮那一刻，把当前页记录下来，
    // 后续 spreads 只在该锚点位置显示双页；翻离该位置后自动关闭合并模式。
    var manualSpreadAnchor by remember { mutableIntStateOf(-1) }

    // 手动合并跨页：根据当前锚点和模式生成一个双页 spread，边界保护。
    val manualSpread = remember(settings.spreadJoinMode, manualSpreadAnchor, currentPage, document.pageCount) {
        val anchor = if (manualSpreadAnchor >= 0) manualSpreadAnchor else currentPage
        when (settings.spreadJoinMode) {
            ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS -> {
                if (anchor > 0) {
                    MangaSpread(leftPageIndex = anchor - 1, rightPageIndex = anchor)
                } else {
                    MangaSpread(leftPageIndex = anchor, rightPageIndex = null)
                }
            }
            ReadingPreferences.SpreadJoinMode.JOIN_NEXT -> {
                if (anchor < document.pageCount - 1) {
                    MangaSpread(leftPageIndex = anchor, rightPageIndex = anchor + 1)
                } else {
                    MangaSpread(leftPageIndex = anchor, rightPageIndex = null)
                }
            }
            ReadingPreferences.SpreadJoinMode.OFF -> null
        }
    }

    // 从 DataStore 恢复合并模式时若锚点未设置，自动用当前页做锚点。
    LaunchedEffect(settings.spreadJoinMode, currentPage) {
        if (settings.spreadJoinMode != ReadingPreferences.SpreadJoinMode.OFF && manualSpreadAnchor < 0) {
            manualSpreadAnchor = currentPage
        }
    }

    // 翻页命令（音量键 / 点按区域 -> 阅读器 body 消费）：+1 下一页，-1 上一页
    var turnRequest by remember { mutableStateOf<Int?>(null) }
    val turnPage: (Int) -> Unit = { delta -> turnRequest = delta }

    // 页码变化：既刷底栏显示，也回传给 ViewModel 保存进度。
    // 手动合并跨页时，一旦翻到合并 spread 之外的页，就自动关闭合并模式。
    val handlePageChanged: (Int) -> Unit = { pageIndex ->
        currentPage = pageIndex
        onPageChanged(pageIndex)
        if (settings.spreadJoinMode != ReadingPreferences.SpreadJoinMode.OFF &&
            manualSpreadAnchor >= 0
        ) {
            val inManualSpread = when (settings.spreadJoinMode) {
                ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS ->
                    pageIndex == manualSpreadAnchor || pageIndex == manualSpreadAnchor - 1
                ReadingPreferences.SpreadJoinMode.JOIN_NEXT ->
                    pageIndex == manualSpreadAnchor || pageIndex == manualSpreadAnchor + 1
                else -> false
            }
            if (!inManualSpread) {
                manualSpreadAnchor = -1
                coroutineScope.launch { prefs.setSpreadJoinMode(ReadingPreferences.SpreadJoinMode.OFF) }
            }
        }
    }

    // 音量键翻页：翻页方向由设置决定。阅读器销毁时必须摘掉钩子，
    // 否则 Activity 级别的单例回调会把离开阅读器后的音量键也吃掉。
    // 注意 Activity 要从 LocalView 的 context 拿：LocalContext 在 NavHost 里
    // 可能被包成 ContextThemeWrapper，直接 `as? MainActivity` 会失败，
    // 结果就是钩子永远注册不上、音量键静默失效（实测踩过）。
    val view = LocalView.current
    DisposableEffect(settings.volumeKeyMode) {
        val mode = settings.volumeKeyMode
        val activity = view.context as? MainActivity
        Diag.d(context, "VolKey", "注册钩子 mode=$mode activity=${activity != null}")
        if (mode != ReadingPreferences.VolumeKeyMode.OFF && activity != null) {
            activity.volumeKeyHandler = { keyCode ->
                val isNext = when (mode) {
                    ReadingPreferences.VolumeKeyMode.DOWN_IS_NEXT ->
                        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    ReadingPreferences.VolumeKeyMode.UP_IS_NEXT ->
                        keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    ReadingPreferences.VolumeKeyMode.OFF -> false
                }
                com.example.mangareader.data.Diag.d(
                    context,
                    "VolKey",
                    "hook 收到 code=$keyCode mode=$mode isNext=$isNext",
                )
                turnPage(if (isNext) 1 else -1)
                true
            }
            onDispose { activity.volumeKeyHandler = null }
        } else {
            onDispose { }
        }
    }

    // 三等分点按区域：左右两侧翻页（方向由设置决定），中间呼出/隐藏控制栏
    val handleTapSide: (isLeft: Boolean) -> Unit = { isLeft ->
        val nextIsLeft = when (settings.tapZoneMode) {
            ReadingPreferences.TapZoneMode.LEFT_IS_NEXT -> true
            ReadingPreferences.TapZoneMode.RIGHT_IS_NEXT -> false
            ReadingPreferences.TapZoneMode.FOLLOW_DIRECTION -> isReadingRtl
        }
        turnPage(if (isLeft == nextIsLeft) 1 else -1)
    }
    val handleTapCenter: () -> Unit = { isControlsVisible = !isControlsVisible }

    // 屏幕常亮：直接作用在 View 上，离开阅读器恢复
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 进入阅读器：漫画是黑底，系统栏图标必须切浅色才看得清；并隐藏状态栏做真沉浸。
    // 离开阅读器时（onDispose）恢复成书架的默认浅色主题样式。
    val window = (context as? Activity)?.window
    val insetsController = remember(window, view) {
        window?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(insetsController) {
        val controller = insetsController ?: return@DisposableEffect onDispose {}
        val prevStatusLight = controller.isAppearanceLightStatusBars
        val prevNavLight = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        // 隐藏状态栏后，只有上滑手势才会临时呼出，避免一碰屏幕系统栏就闪现
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.isAppearanceLightStatusBars = prevStatusLight
            controller.isAppearanceLightNavigationBars = prevNavLight
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
    // 看漫画（控制栏隐藏）时一并隐藏状态栏；点出控制栏时再显示
    LaunchedEffect(isControlsVisible, insetsController) {
        val controller = insetsController ?: return@LaunchedEffect
        val statusBar = WindowInsetsCompat.Type.statusBars()
        if (isControlsVisible) controller.show(statusBar) else controller.hide(statusBar)
    }

    // 亮度调节：-1 跟随系统，否则写 window.screenBrightness（离开阅读器自动恢复系统值）
    LaunchedEffect(settings.brightness, window) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (settings.brightness < 0f) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    settings.brightness.coerceIn(0.02f, 1f)
                }
            }
        }
    }
    DisposableEffect(window) {
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // 方向锁：AUTO 跟随系统；离开阅读器恢复
    DisposableEffect(settings.orientation) {
        val activity = context as? Activity
        activity?.requestedOrientation = when (settings.orientation) {
            ReadingPreferences.ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReadingPreferences.ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ReadingPreferences.ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val edgeWidthPx = with(density) { EDGE_BACK_WIDTH.dp.roundToPx() }

        // 把左右边缘条带的实际屏幕坐标报给系统，让系统手势在这些区域失效，
        // 否则全面屏手势会优先触发系统返回，导致直接回到桌面。
        var leftStripRect by remember { mutableStateOf<Rect?>(null) }
        var rightStripRect by remember { mutableStateOf<Rect?>(null) }

        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rects = listOfNotNull(leftStripRect, rightStripRect)
                if (rects.isNotEmpty()) {
                    window?.setSystemGestureExclusionRects(rects)
                }
            }
        }

        DisposableEffect(window) {
            onDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window?.setSystemGestureExclusionRects(emptyList())
                }
            }
        }

        // 阅读主体。注意 EdgeBackStrip 在 ReaderBody 之后声明，因此叠在上层，
        // 边缘 24dp 区域的手势能先被返回条带消费，而不是被翻页器拿走。
        if (isVertical) {
            VerticalReaderBody(
                document = document,
                initialPage = initialPage,
                cropBorders = settings.cropBorders,
                scaleToFit = settings.scaleToFit,
                onTapSide = handleTapSide,
                onTapCenter = handleTapCenter,
                onPageChanged = handlePageChanged,
                jumpTarget = jumpTarget,
                onJumpHandled = { jumpTarget = null },
                turnRequest = turnRequest,
                onTurnHandled = { turnRequest = null },
            )
        } else {
            // currentPage 就是"此刻正在看的那页"，单/双页切换时用它重新定位，
            // 否则展页列表一变，PagerState 还停在旧的展页下标上，就跳到别处了。
            SpreadReaderBody(
                document = document,
                anchorPage = currentPage,
                isReadingRtl = isReadingRtl,
                effectiveDualPage = effectiveDualPage,
                manualSpread = manualSpread,
                foldable = foldable,
                cropBorders = settings.cropBorders,
                scaleToFit = settings.scaleToFit,
                onTapSide = handleTapSide,
                onTapCenter = handleTapCenter,
                onPageChanged = handlePageChanged,
                jumpTarget = jumpTarget,
                onJumpHandled = { jumpTarget = null },
                turnRequest = turnRequest,
                onTurnHandled = { turnRequest = null },
            )
        }

        // 左右边缘内划返回：在阅读器内容两侧各留一条透明感应区，
        // 从左边缘向右滑、或从右边缘向左滑（即向屏幕中心滑）时触发返回。
        // 24dp 的宽度足够识别，又不会遮挡中间主体内容的点击/缩放。
        EdgeBackStrip(
            alignment = Alignment.CenterStart,
            onSwipeInward = onNavigateBack,
            onRectUpdate = { coords ->
                val b = coords.boundsInWindow()
                leftStripRect = Rect(
                    b.left.toInt(),
                    b.top.toInt(),
                    b.right.toInt(),
                    b.bottom.toInt(),
                )
            },
        )
        EdgeBackStrip(
            alignment = Alignment.CenterEnd,
            onSwipeInward = onNavigateBack,
            onRectUpdate = { coords ->
                val b = coords.boundsInWindow()
                rightStripRect = Rect(
                    b.left.toInt(),
                    b.top.toInt(),
                    b.right.toInt(),
                    b.bottom.toInt(),
                )
            },
        )

        // 顶部栏（含手动合并跨页 / 方向锁 / 双页切换）
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = title,
                onNavigateBack = onNavigateBack,
                isRtl = isReadingRtl,
                spreadJoinMode = settings.spreadJoinMode,
                onCycleSpreadJoinMode = {
                    val next = when (settings.spreadJoinMode) {
                        ReadingPreferences.SpreadJoinMode.OFF ->
                            ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS
                        ReadingPreferences.SpreadJoinMode.JOIN_PREVIOUS ->
                            ReadingPreferences.SpreadJoinMode.JOIN_NEXT
                        ReadingPreferences.SpreadJoinMode.JOIN_NEXT ->
                            ReadingPreferences.SpreadJoinMode.OFF
                    }
                    manualSpreadAnchor = if (next == ReadingPreferences.SpreadJoinMode.OFF) -1 else currentPage
                    coroutineScope.launch { prefs.setSpreadJoinMode(next) }
                },
                orientation = settings.orientation,
                onCycleOrientation = {
                    coroutineScope.launch {
                        val next = when (settings.orientation) {
                            ReadingPreferences.ScreenOrientation.AUTO ->
                                ReadingPreferences.ScreenOrientation.PORTRAIT
                            ReadingPreferences.ScreenOrientation.PORTRAIT ->
                                ReadingPreferences.ScreenOrientation.LANDSCAPE
                            ReadingPreferences.ScreenOrientation.LANDSCAPE ->
                                ReadingPreferences.ScreenOrientation.AUTO
                        }
                        prefs.setOrientation(next)
                    }
                },
                isDualPage = effectiveDualPage,
                canToggleDualPage = isDualPageCapable && !isVertical,
                onToggleDualPage = {
                    coroutineScope.launch { prefs.setDoublePage(!settings.doublePage) }
                },
            )
        }

        // 底部栏：页码 + 跳页滑条 + 亮度
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                currentPage = currentPage,
                totalPages = document.pageCount,
                isRtl = isReadingRtl,
                onPageSelected = { targetPage -> jumpTarget = targetPage },
                brightness = settings.brightness,
                onBrightnessChange = { value ->
                    coroutineScope.launch { prefs.setBrightness(value) }
                },
            )
        }
    }
}

/**
 * 阅读器边缘返回感应条。
 *
 * @param alignment 贴左 ([Alignment.CenterStart]) 或贴右 ([Alignment.CenterEnd])
 * @param onSwipeInward 向屏幕中心滑动时的回调
 * @param onRectUpdate 把条带在窗口中的实际坐标报给父级，用于设置系统手势排除区
 */
@Composable
private fun EdgeBackStrip(
    alignment: Alignment,
    onSwipeInward: () -> Unit,
    onRectUpdate: (LayoutCoordinates) -> Unit = {},
) {
    val isLeft = alignment == Alignment.CenterStart
    val density = LocalDensity.current
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }
    val thresholdPx = with(density) { EDGE_BACK_DRAG_THRESHOLD.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(EDGE_BACK_WIDTH.dp)
            .onGloballyPositioned(onRectUpdate)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                        triggered = false
                    },
                    onHorizontalDrag = { change, amount ->
                        totalDrag += amount
                        val inward = if (isLeft) totalDrag > 0 else totalDrag < 0
                        if (inward) change.consume()
                    },
                    onDragEnd = {
                        if (!triggered) {
                            val inward = if (isLeft) {
                                totalDrag > thresholdPx
                            } else {
                                totalDrag < -thresholdPx
                            }
                            if (inward) {
                                triggered = true
                                onSwipeInward()
                            }
                        }
                    },
                    onDragCancel = {
                        totalDrag = 0f
                        triggered = false
                    },
                )
            },
        contentAlignment = alignment,
    ) {}
}

private const val EDGE_BACK_WIDTH = 24
private const val EDGE_BACK_DRAG_THRESHOLD = 16

/**
 * 横向展页阅读（LTR/RTL）：按 Spread 翻页，支持单/双页、折叠屏书本态与手动合并跨页。
 */
@Composable
private fun SpreadReaderBody(
    document: MangaDocument,
    /** 此刻正在看的页码（0 起）。单/双页切换时用它在新的展页表里重新定位 */
    anchorPage: Int,
    isReadingRtl: Boolean,
    effectiveDualPage: Boolean,
    /** 手动合并跨页 spread；不为 null 时在该位置强制显示双页，其余页保持单页 */
    manualSpread: MangaSpread? = null,
    foldable: FoldableDeviceState,
    cropBorders: Boolean,
    scaleToFit: Boolean,
    onTapSide: (isLeft: Boolean) -> Unit,
    onTapCenter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    jumpTarget: Int?,
    onJumpHandled: () -> Unit,
    turnRequest: Int?,
    onTurnHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 重新编排展页数据；manualSpread 变化时要重建整个 Pager，确保重新定位到新的展页。
    val spreads = remember(document.pageCount, effectiveDualPage, isReadingRtl, manualSpread) {
        SpreadHelper.createSpreads(
            totalPages = document.pageCount,
            isDualPage = effectiveDualPage,
            isRtl = isReadingRtl,
            firstPageCover = true,
            manualSpread = manualSpread,
        )
    }

    // 布局形态（单/双页、阅读方向、手动合并 spread）一变，展页表就重排了：
    // 单页 N 个展页 ≈ 双页 N/2 个，同一个页码在两个形态里落在不同展页上。
    // PagerState 的 initialPage 只在创建时算一次，光靠它会一直停在旧的展页下标。
    // 用 key 把整块内容按「形态」隔离：形态一变就重建，initialPage 得以按 anchorPage
    // （此刻正在看的那页）重新定位。下方再兜一层校正，双保险。
    key(effectiveDualPage to isReadingRtl to manualSpread) {
        val pagerState = rememberPagerState(
            initialPage = spreads.spreadIndexFor(anchorPage),
            pageCount = { spreads.size },
        )

        // 展页变动 -> 回传阅读顺序上的第一页（供 ViewModel 保存进度 + 底栏显示）
        LaunchedEffect(pagerState, spreads) {
            snapshotFlow { pagerState.currentPage }.collect { spreadIndex ->
                val spread = spreads.getOrNull(spreadIndex) ?: return@collect
                val first = spread.orderedIndices(isReadingRtl).firstOrNull() ?: return@collect
                onPageChanged(first)
            }
        }

        // 底栏滑条跳页：页码换算回所属展页
        LaunchedEffect(pagerState, spreads, jumpTarget) {
            val target = jumpTarget ?: return@LaunchedEffect
            pagerState.scrollToPage(spreads.spreadIndexFor(target))
            onJumpHandled()
        }

        // 音量键 / 点按区域的翻页请求。HorizontalPager 开了 reverseLayout（日漫）时
        // currentPage+1 在视觉上向左翻，但「阅读顺序的下一页」始终是下标 +1，方向不用自己管。
        LaunchedEffect(pagerState, spreads, turnRequest) {
            val delta = turnRequest ?: return@LaunchedEffect
            val target = (pagerState.currentPage + delta).coerceIn(0, spreads.size - 1)
            pagerState.animateScrollToPage(target)
            onTurnHandled()
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = isReadingRtl,
            beyondViewportPageCount = 1,
            modifier = modifier.fillMaxSize(),
        ) { spreadIndex ->
            val currentSpread = spreads[spreadIndex]
            // 双页 spread：自动双页模式 or 手动合并跨页。优先按 spread 本身是否含两页判断。
            val isDualSpread = currentSpread.leftPageIndex != null && currentSpread.rightPageIndex != null
            if (isDualSpread) {
                DualPageSpreadView(
                    document = document,
                    spread = currentSpread,
                    foldableState = foldable,
                    onTapSide = onTapSide,
                    onTapCenter = onTapCenter,
                    cropBorders = cropBorders,
                    scaleToFit = scaleToFit,
                )
            } else {
                MangaPageView(
                    document = document,
                    pageIndex = currentSpread.leftPageIndex
                        ?: currentSpread.rightPageIndex
                        ?: return@HorizontalPager,
                    onTapSide = onTapSide,
                    onTapCenter = onTapCenter,
                    cropBorders = cropBorders,
                    scaleToFit = scaleToFit,
                )
            }
        }
    }
}

/**
 * 条漫竖向滚动（VERTICAL）：单页一屏纵向滚动，无镜像、无双页。
 */
@Composable
private fun VerticalReaderBody(
    document: MangaDocument,
    initialPage: Int,
    cropBorders: Boolean,
    scaleToFit: Boolean,
    onTapSide: (isLeft: Boolean) -> Unit,
    onTapCenter: () -> Unit,
    onPageChanged: (Int) -> Unit,
    jumpTarget: Int?,
    onJumpHandled: () -> Unit,
    turnRequest: Int?,
    onTurnHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastPage = (document.pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, lastPage),
        pageCount = { document.pageCount },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    // 底栏滑条跳页：竖向模式下页码即展页
    LaunchedEffect(pagerState, jumpTarget) {
        val target = jumpTarget ?: return@LaunchedEffect
        pagerState.scrollToPage(target.coerceIn(0, lastPage))
        onJumpHandled()
    }

    // 音量键 / 点按区域的翻页请求：竖向模式「下一页」就是向下滑一格
    LaunchedEffect(pagerState, turnRequest) {
        val delta = turnRequest ?: return@LaunchedEffect
        val target = (pagerState.currentPage + delta).coerceIn(0, lastPage)
        pagerState.animateScrollToPage(target)
        onTurnHandled()
    }

    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = modifier.fillMaxSize(),
    ) { page ->
        MangaPageView(
            document = document,
            pageIndex = page,
            onTapSide = onTapSide,
            onTapCenter = onTapCenter,
            cropBorders = cropBorders,
            scaleToFit = scaleToFit,
        )
    }
}
