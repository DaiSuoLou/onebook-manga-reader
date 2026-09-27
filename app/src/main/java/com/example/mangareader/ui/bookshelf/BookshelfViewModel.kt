package com.example.mangareader.ui.bookshelf

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mangareader.MangaApplication
import com.example.mangareader.core.document.CoverExtractor
import com.example.mangareader.data.CoverStore
import com.example.mangareader.data.CredentialsStore
import com.example.mangareader.data.Diag
import com.example.mangareader.data.DocumentUris
import com.example.mangareader.data.KzzMoeCoverFetcher
import com.example.mangareader.data.WebDavCacheManager
import com.example.mangareader.data.LocalMangaSource
import com.example.mangareader.data.LibraryPreferences
import com.example.mangareader.data.MangaItem
import com.example.mangareader.data.MangaSource
import com.example.mangareader.data.SafeMangaSource
import com.example.mangareader.data.WebDavMangaSource
import com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel
import com.example.mangareader.ui.bookshelf.model.MangaVolumeUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书架页的数据源：本地私有目录 + 用户 SAF 授权的若干个目录，聚合展示。
 *
 * **id 前缀约定**：每个 mangaId / chapterId 都带源标识，形如
 * `local/海贼王/第01话` 或 `saf:3f9a1c07/海贼王/第01话`。
 * 没有前缀的裸路径（比如调试入口手输的）一律按本地私有目录处理。
 * 这样不同来源的同名作品不会在 [volumesCache] 或阅读进度表里撞车。
 */
private const val TAG = "BookshelfViewModel"

/** WebDAV 扫描的最长等待时间，超时就把远程源摘掉先扫本地，别让书架一直空着 */
private const val DAV_SCAN_TIMEOUT_MS = 60_000L

/** 把异常压成一句人话，避免把 java.net.SocketTimeoutException 这类类名直接甩给用户 */
private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val progressRepository = (application as MangaApplication).progressRepository
    private val libraryPreferences = LibraryPreferences(app)
    /** 用户手动指定的套书封面（kzz.moe 抓的 / 从相册选的），优先于目录里自动识别的 */
    private val coverStore = CoverStore(app)

    private val rootDir: File by lazy {
        File(
            app.getExternalFilesDir(null) ?: app.filesDir,
            "manga"
        ).apply { mkdirs() }
    }

    private val localSource: MangaSource = LocalMangaSource(rootDir)

    /** WebDAV 账号存储；源实例在凭证就绪后异步构建 */
    private val credentialsStore = CredentialsStore(app)

    /**
     * 源 id -> 可用的 WebDAV 源实例。
     *
     * **为什么是映射而不是单实例**：用户可以配多个 WebDAV 地址，
     * 作品 id 里带的那段 `dav:xxxxxxxx` 就是用来在这里定位「该用哪套账号打开」的。
     */
    private val davSources = MutableStateFlow<Map<String, WebDavMangaSource>>(emptyMap())

    /** WebDAV 账号列表（含用户自定义名称），供设置页与书架分类标签使用 */
    private val _davAccounts = MutableStateFlow<List<CredentialsStore.DavAccount>>(emptyList())
    val davAccounts: StateFlow<List<CredentialsStore.DavAccount>> = _davAccounts.asStateFlow()

    private val davCacheRoot: File = WebDavCacheManager.davCacheDir(app)

    private val _series = MutableStateFlow<List<MangaSeriesUiModel>>(emptyList())
    val series: StateFlow<List<MangaSeriesUiModel>> = _series.asStateFlow()

    /**
     * 最近一次扫描到的作品列表，是 [emitSeries] 的输入快照。
     *
     * **必须声明在 `init` 块之前。** Kotlin 会把属性初始化器和 init 块按源码顺序
     * 塞进构造函数，而 `init` 里的 `onEach { emitSeries() }.launchIn(viewModelScope)`
     * 对 StateFlow 会**同步**收到当前值 —— 若在那时本字段的初始化器还没跑到，
     * 读到的就是未赋值的 null，`lastItems.map {}` 会直接 NPE。
     * 这个项目上一版「启动页就闪退」就是这么来的（栈：
     * `collectionSizeOrDefault ... parameter <this>`）。
     */
    private var lastItems: List<MangaItem> = emptyList()

    /** 已授权的 SAF 目录，供「管理导入目录」界面与 resolvePath 反查 */
    private val _roots = MutableStateFlow<List<LibraryPreferences.Root>>(emptyList())
    val roots: StateFlow<List<LibraryPreferences.Root>> = _roots.asStateFlow()

    /** 一次性提示（导入成功 / 失败），由界面 collect 后弹 Snackbar */
    private val _hints = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val hints: SharedFlow<String> = _hints.asSharedFlow()

    /** 已加载过的分卷列表，供「续读」定位（路由层的 volumes 是快照，外面拿不到） */
    private val volumesCache = HashMap<String, List<MangaVolumeUiModel>>()

    /** 冷启动扫一次，导入目录又扫一次，串行化避免两个协程同时读同一批目录 */
    private val refreshLock = Mutex()

    /** 手动封面表：seriesId -> 本地绝对路径。只在封面变动时更新，不跟着目录扫描走 */
    private val _covers = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 抽屉里每一卷的自动封面：volumeId（带源前缀） -> 本地绝对路径。
     * 只覆盖当前显示的卷，不跟着目录扫描重算。
     */
    private val _volumeCovers = MutableStateFlow<Map<String, String>>(emptyMap())
    val volumeCovers: StateFlow<Map<String, String>> = _volumeCovers.asStateFlow()

    /** 当前正在后台抽分卷封面的协程，切作品时取消旧的 */
    private var volumeCoverJob: Job? = null

    /**
     * 从漫画文件**内部**抽出来的封面：seriesId -> 本地绝对路径。
     *
     * 放在手动封面之后、目录识别之前：它是这本书自己第一页，最贴近用户眼里的「封面」，
     * 而目录里那张 cover.jpg 未必真是封面。WebDAV 那边走的是它自己的 coverCachePath，
     * mainFile 为 null，这里不会有它的份。
     */
    private val _localCovers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 目录扫描自动识别到的封面：seriesId -> 原始 coverUrl。手动封面优先于它 */
    private val _autoCovers = MutableStateFlow<Map<String, Any?>>(emptyMap())

    /**
     * 已经尝试过抽封面的作品 id，抽失败的也算数。
     *
     * 没有它的话，每次「重新扫描」都会把上次失败的那批再抽一遍 —— 一个个超时 20 秒，
     * 用户会觉得书架越刷越卡。
     */
    private val coverAttempted = HashSet<String>()

    /** 抽封面要开文档（解压 / 渲染），同时最多开 3 个，别把内存吃光 */
    private val coverLimiter = Semaphore(3)

    /** WebDAV 抽封面要走网络，并发压到 2：同时十几路 GET 会把书架刷得一直转圈 */
    private val davCoverLimiter = Semaphore(2)

    /**
     * 已经尝试过「远程抽第一页当封面」的 WebDAV 作品 id，失败的也算数。
     *
     * 没有它的话，每次打开书架都会把上次失败的那批再抓一遍 —— 一次 PROPFIND 加一次图片下载，
     * 十几本就是几十个请求，用户会觉得书架越开越卡。
     */
    private val davCoverAttempted = HashSet<String>()

    /**
     * 扫描过程中被吞掉的失败原因（每个源最多一条）。
     *
     * 旧写法是 `runCatching {}.onFailure { it.printStackTrace() }`：本地目录读不出去、
     * SAF 授权掉了、WebDAV 超时，全都是静默的，用户只看到「书架一片空白」却无从下手。
     * 这里把原因挑最要紧的一条显示在书架空状态上，并给出重新扫描的入口。
     */
    private val _scanProblems = MutableStateFlow<List<String>>(emptyList())
    val scanProblems: StateFlow<List<String>> = _scanProblems.asStateFlow()

    init {
        // 授权目录变了就重扫书架：新增（importRoot）/ 移除（removeRoot）都会走到这里。
        //
        // `.drop(1)` 是因为 DataStore 的 `data` 是热 Flow，collect 时会立刻同步吐出当前值；
        // 不 drop 的话「构造 ViewModel 的这一刻」就会在那条 launchIn 里同步把全量目录
        // （本地 + SAF + WebDAV）扫一遍 —— 主线程 IO，而且紧接着下面还要再扫一次。
        // 首次扫描统一交给下面那个 launch，这里只关心**之后**的变化。
        libraryPreferences.roots
            .drop(1)
            .onEach { roots -> _roots.value = roots; refresh() }
            .launchIn(viewModelScope)

        // 手动封面变了就单独盖到当前作品列表上，不为一张封面重扫全部目录。
        // 这两个 onEach 也会在构造期同步收到空初值，但 [lastItems] 已在此之前初始化，
        // 重算出来是空列表，属于无害的一次多余重组。
        coverStore.covers
            .drop(1)
            .onEach { covers -> _covers.value = covers; emitSeries() }
            .launchIn(viewModelScope)

        // 从漫画文件内部抽出来的封面：只影响当前列表，不惊动扫描
        coverStore.autoCovers
            .drop(1)
            .onEach { covers ->
                Diag.i(app, TAG, "本地封面缓存更新：${covers.size} 张")
                _localCovers.value = covers; emitSeries()
            }
            .launchIn(viewModelScope)

        // 扫描得到的封面表（作品目录下现成的 cover.jpg 之类）有更新就重排列表。
        //
        // **这里不能把收到的值再写回去**：这张表是「扫描结果」和「远程抽出来的封面」
        // 合出来的（后者在 [launchWebDavCoverExtraction] 里往里补），
        // 拿流里那条原始结果覆盖回去，刚补上的远程封面就没了。
        _autoCovers
            .drop(1)
            .onEach { emitSeries() }
            .launchIn(viewModelScope)

        // WebDAV 账号就绪后重扫一次（refreshLock 保证与上面的扫描串行）
        viewModelScope.launch {
            reloadWebDavSources()
        }
    }

    /**
     * 重建所有 WebDAV 源实例并重扫书架。
     *
     * 设置页在「新增 / 保存 / 删除账号」之后调它。
     */
    fun reloadWebDavSources() {
        viewModelScope.launch {
            val accounts = runCatching { credentialsStore.accounts() }.getOrDefault(emptyList())
            _davAccounts.value = accounts
            val next = LinkedHashMap<String, WebDavMangaSource>()
            for (account in accounts) {
                runCatching {
                    credentialsStore.buildSource(account, davCacheRoot)?.let { next[account.sourceId] = it }
                }.onFailure { Diag.e(app, TAG, "构建 WebDAV 源失败：${account.name}", it) }
            }
            davSources.value = next
            refresh()
        }
    }

    suspend fun refresh() {
        refreshLock.withLock { scanAllSources() }
    }

    /** 聚合所有源的根目录列表 */
    private suspend fun scanAllSources() {
        val items = ArrayList<MangaItem>()
        val problems = ArrayList<String>()

        Diag.i(app, TAG, "开始扫描书架")
        runCatching {
            val local = localSource.getMangaList("")
            Diag.i(app, TAG, "本地源扫描到 ${local.size} 个作品")
            items += local
        }.onFailure { it.printStackTrace(); problems += "本地漫画目录读取失败：${describe(it)}" }

        for (root in _roots.value) {
            val found = runCatching {
                SafeMangaSource(app.contentResolver, Uri.parse(root.uri)).getMangaList("")
            }.onFailure {
                it.printStackTrace()
                problems += "「${root.label}」读取失败，可能需要重新导入：${describe(it)}"
            }.getOrDefault(emptyList())
            Diag.i(app, TAG, "SAF 源 ${root.label} 扫描到 ${found.size} 个作品")
            items += found
            // 授权掉了时这里返回空列表：看不出是「目录真的是空的」还是「读不了」，
            // 至少给一句提示，别让用户以为文件凭空消失了。
            if (found.isEmpty()) {
                problems += "「${root.label}」里没有读到任何漫画，可能已失去目录授权，请重新导入"
            }
        }

        // WebDAV：MangaItem.id 是源内裸路径，这里统一加账号前缀防止不同 WebDAV 上的
        // 同名作品撞车。每个账号单独扫、单独记问题，好知道是哪一侧读不动。
        for ((sourceId, dav) in davSources.value) {
            val label = davAccounts.value.firstOrNull { it.sourceId == sourceId }?.name ?: sourceId
            runCatching {
                // 同样加硬超时：服务端不回数据时不能把整个书架拖死在主线程之外
                val found = withTimeout(DAV_SCAN_TIMEOUT_MS) { dav.getMangaList("") }
                Diag.i(app, TAG, "WebDAV「$label」扫描到 ${found.size} 个作品")
                items += found.map { it.copy(id = "$sourceId/${it.id}") }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    problems += "WebDAV「$label」扫描超时（${DAV_SCAN_TIMEOUT_MS / 1000} 秒），已跳过"
                } else {
                    problems += "WebDAV「$label」读取失败：${describe(error)}"
                }
            }
        }

        _scanProblems.value = problems

        // 手动指定的封面优先于目录里自动识别到的（cover.jpg / 第一张图）
        _autoCovers.value = items.associate { it.id to it.coverUrl }
        lastItems = items
        emitSeries()
        Diag.i(app, TAG, "扫描完成：本地+SAF+WebDAV 共 ${items.size} 个作品，开始抽封面")
        launchLocalCoverExtraction(items)
    }

    /**
     * 给「还没有封面」的作品补上从文件里抽出来的封面。
     *
     * 刻意不在扫描这条协程里做：一次扫描几百本，抽封面要开文档、解压、解码位图，
     * 塞进扫描会把书架刷半天。这里逐个丢到后台，限 3 个并发，扫完就慢慢抽。
     */
    private fun launchLocalCoverExtraction(items: List<MangaItem>) {
        // 只要本地有文件，就从文件内部抽封面；抽出来的比目录里随便找的第一张图更可能是真封面。
        val candidates = items.filter { !it.id.isBlank() && it.mainFile != null }
        Diag.i(app, TAG, "封面候选：${candidates.size}/${items.size} 个作品有本地文件可抽")
        for (item in candidates) {
            Diag.d(app, TAG, "封面候选详情：${item.title} mainFile=${item.mainFile}")
            if (!coverAttempted.add(item.id)) continue

            viewModelScope.launch {
                coverLimiter.acquire()   // 同类 Semaphore 在本项目版本没有 withPermit 扩展
                try {
                    val out = coverStore.destination(item.id)
                    Diag.d(app, TAG, "开始抽封面：${item.title} <- ${item.mainFile}")
                    runCatching {
                        if (CoverExtractor.extractToFile(app, item.mainFile!!, app.cacheDir, out)) {
                            coverStore.setAuto(item.id, out)
                            // 顺手更新内存态，不等 DataStore 回灌。
                            //
                            // **为什么不等**：实机抓下来，`autoCovers` 这个流只在进程起来、
                            //  collect 的那一刻把上次持久化的值吐一次（drop(1) 正好把它丢掉），
                            // 之后 `store.edit()` 写入并不会触发新一轮发射 —— 日志里
                            // 「抽封面成功」跟「autoCovers 发出」前后脚，但只各出现一次。
                            // 结果就是封面抽出来了、文件也落盘了，书架那一格却始终是深色底。
                            // 冷启动时读盘那条路是通的（首帧就能拿到封面），所以这里补一笔内存态，
                            // 既修了当次不刷新，也不会丢持久化。
                            _localCovers.value = _localCovers.value + (item.id to out.absolutePath)
                            emitSeries()
                            Diag.i(app, TAG, "抽封面成功：${item.title} -> ${out.absolutePath}")
                        } else {
                            coverStore.clearAuto(item.id)
                            Diag.w(app, TAG, "抽封面无结果：${item.title}")
                        }
                    }.onFailure {
                        Diag.e(app, TAG, "抽封面失败：${item.title}", it)
                    }
                } finally {
                    coverLimiter.release()
                }
            }
        }
        launchWebDavCoverExtraction(items)
    }

    /**
     * 给 WebDAV 上的作品补封面：远程没有本地文件，只能从第一话第一页抽。
     *
     * **为什么要单独一条路**：本地那份走的是「开本地文档读第一页」，远程得自己列目录、
     * 下载第一章第一页，慢得多，也放在同一个 [coverLimiter] 里等着，
     * 所以另开一个 [davCoverLimiter]，只限 2 个并发 —— 一次性给十几本都抓，
     * 书架会被一堆网络请求拖住，用户看到的就是「点开书架半天没反应」。
     *
     * 抽出来的缩略图落在 WebDAV 缓存的 `covers/` 里，下次扫描 [WebDavMangaSource]
     * 直接读缓存，不会重复下载。
     */
    private fun launchWebDavCoverExtraction(items: List<MangaItem>) {
        // 按源分组：候选只收「落在某个已知账号上」的作品，
        // 每个账号的封面落自己那份缓存目录，免得两个 WebDAV 上同名作品抢同一个文件。
        val candidates = items.filter { it.mainFile == null && it.coverUrl.isNullOrBlank() && davCoverAttempted.add(it.id) }
            .filter { davSourceIdOf(it.id) in davSources.value }
            .also { Diag.i(app, TAG, "WebDAV 封面候选（按源分组）：${it.size} 个") }
        if (candidates.isEmpty()) {
            Diag.i(app, TAG, "WebDAV 封面候选：0 个（都已有封面或上次已失败）")
            return
        }
        for (item in candidates) {
            viewModelScope.launch {
                davCoverLimiter.acquire()
                try {
                    val sourceId = davSourceIdOf(item.id) ?: return@launch
                    val source = davSources.value[sourceId] ?: return@launch
                    val seriesIdInSource = item.id.removePrefix("$sourceId/")
                    // 落在该源的封面目录里：读取侧 WebDavMangaSource.coverCachePath 读的就是这里，
                    // 两边必须同一个位置，否则重启后书架上一张封面都认不出来。
                    val out = File(source.coverDir, "${seriesIdInSource.hashCode()}.jpg")
                    val ok = runCatching {
                        source.extractFirstPageCover(seriesIdInSource, out)
                    }.onFailure {
                        Diag.e(app, TAG, "WebDAV 抽封面失败：${item.title}", it)
                    }.getOrDefault(false)
                    if (ok) {
                        val merged = _autoCovers.value.toMutableMap()
                        merged[item.id] = out.absolutePath
                        _autoCovers.value = merged
                        emitSeries()
                        Diag.i(app, TAG, "WebDAV 抽封面成功：${item.title} -> ${out.absolutePath}")
                    }
                } finally {
                    davCoverLimiter.release()
                }
            }
        }
    }

    /** 注意：[lastItems] 声明在 `init` 块之前，别挪到 init 后面 —— 见它的 KDoc */

    /**
     * 按「手动封面 > 自动识别封面 > 扫描结果」的优先级重算书架列表。
     *
     * 抽出来的原因：封面是三个来源合出来的（手动 / 按需补抓 / 扫描），
     * 每来一个就整段重写一遍，免得三个来源互相覆盖。
     */
    private fun emitSeries() {
        val manualCovers = _covers.value
        val items = lastItems
        Diag.d(app, TAG, "emitSeries: items=${items.size} manual=${manualCovers.size} local=${_localCovers.value.size} auto=${_autoCovers.value.size}")
        _series.value = items.map { item ->
            MangaSeriesUiModel(
                id = item.id,
                title = item.title,
                coverUrl = manualCovers[item.id]
                    ?: _localCovers.value[item.id]
                    ?: _autoCovers.value[item.id]
                    ?: item.coverUrl,
                // 册数要逐个作品列目录才知道，首屏不值得为它发 N 个请求；
                // 抽屉里的「全套共 X 卷」用的是实际加载出来的 volumes.size，不受影响。
                volumeCount = 0,
                hasManualCover = manualCovers.containsKey(item.id),
            )
        }
        // 逐条记下最终结果：书架上一格深色方块，只有看这条才知道是「压根没抽到」
        // 还是「抽到了但 Coil 没画出来」
        _series.value.forEach { s ->
            Diag.d(app, TAG, "书格封面：${s.title} coverUrl=${s.coverUrl}")
        }
    }

    suspend fun loadVolumes(seriesId: String): List<MangaVolumeUiModel> {
        val (source, seriesIdInSource) = resolveSource(seriesId)
        val isDav = davSourceIdOf(seriesId) != null

        // 打开抽屉时按需补一次作品封面：书架扫描阶段为了不卡住不下载封面，
        // 这里用后台协程补上，抓到了就刷新书架这一格的显示，抓失败也不影响分卷列表。
        if (isDav) {
            viewModelScope.launch {
                runCatching {
                    val sourceId = davSourceIdOf(seriesId)
                        ?: error("WebDAV 还没有配置")
                    (davSources.value[sourceId] ?: error("WebDAV「$sourceId」不可用")).refreshCover(seriesIdInSource)
                }.onSuccess { path ->
                    if (path != null) {
                        val merged = _autoCovers.value.toMutableMap()
                        merged[seriesId] = path
                        _autoCovers.value = merged
                    }
                }.onFailure { Diag.e(app, TAG, "补抓封面失败：$seriesId", it) }
            }
        }
        // WebDAV 源吐出的章节 id 是源内裸路径，统一补 `dav/` 前缀与书架侧约定对齐
        // 失败不能静默吞掉：旧写法用 getOrDefault(emptyList()) 一兜底，
        // WebDAV 请求一报错就变成「全套共 0 卷」，用户完全看不到失败原因。
        // 注意：抽屉由 LaunchedEffect 发起，离开 composition 时会取消协程；
        // CancellationException 是正常的生命周期事件，不能当错误 snackbar 弹给用户。
        val rawChapters = try {
            source.getChapters(seriesIdInSource)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            Diag.e(app, TAG, "加载分卷失败：$seriesId", error)
            viewModelScope.launch {
                _hints.emit("加载「${seriesId.substringAfterLast('/')}」失败：${error.message ?: error.javaClass.simpleName}")
            }
            emptyList()
        }

        val sourcePrefix = if (isDav) "${davSourceIdOf(seriesId)}/" else ""
        val chapters = rawChapters.map { if (isDav) it.copy(id = "$sourcePrefix${it.id}") else it }

        // 卷列表为空时明确提示一句：WebDAV 目录结构/凭证不对时最容易表现为「0 卷」，
        // 不给提示用户只能看到一个空抽屉，无从判断是服务器没内容还是请求失败了。
        if (chapters.isEmpty()) {
            val title = _series.value.firstOrNull { it.id == seriesId }?.title ?: seriesId
            viewModelScope.launch {
                _hints.emit("「$title」下没有找到章节，请确认目录内容或 WebDAV 路径是否正确")
            }
        }

        // 预读已缓存的分卷封面；没有的进后台一张张抽第一页。
        // WebDAV 没本地文件，直接跳过（它靠作品目录封面）。
        val cachedVolumeCovers = coverStore.volumeAutoCovers(seriesId)
        _volumeCovers.value = _volumeCovers.value + cachedVolumeCovers

        volumeCoverJob?.cancel()
        volumeCoverJob = viewModelScope.launch {
            val sem = Semaphore(3)
            rawChapters.forEach { raw ->
                val finalId = if (isDav) "$sourcePrefix${raw.id}" else raw.id
                if (cachedVolumeCovers.containsKey(finalId)) return@forEach

                launch {
                    sem.acquire()
                    try {
                        val out = coverStore.volumeDestination(seriesId, finalId)
                        val ok = if (isDav) {
                            // 远程卷没有本地文件，得单独走「按卷抽第一页」：
                            // 以前这里被 `if (!isDav)` 跳过了，于是每一卷都落到同一个占位图。
                            val sourceId = davSourceIdOf(seriesId) ?: return@launch
                            val source = davSources.value[sourceId] ?: return@launch
                            runCatching {
                                source.extractVolumeCover(raw.id, out)
                            }.getOrDefault(false)
                        } else {
                            val pathForCover = resolvePath(finalId)
                            if (pathForCover.startsWith("dav:")) return@launch
                            runCatching {
                                CoverExtractor.extractToFile(app, pathForCover, app.cacheDir, out)
                            }.getOrDefault(false)
                        }
                        if (ok) {
                            coverStore.setVolumeAuto(seriesId, finalId, out)
                            _volumeCovers.value = _volumeCovers.value + (finalId to out.absolutePath)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Diag.e(app, TAG, "分卷封面失败：${raw.title}", t)
                    } finally {
                        sem.release()
                    }
                }
            }
        }

        val progress = progressRepository.progress.value
        val seriesCover = _series.value.firstOrNull { it.id == seriesId }?.coverUrl

        val volumes = chapters.map { chapter ->
            val p = progress[chapter.id]
            MangaVolumeUiModel(
                id = chapter.id,
                volumeNumber = chapter.index + 1,
                title = chapter.title,
                // 分卷封面优先用后台抽出来的；没有就用套书封面顶上
                coverUrl = cachedVolumeCovers[chapter.id] ?: seriesCover,
                // 优先用进度库记录的准确页数（文档真实解析结果），没读过则用扫描时数出来的页数
                totalPages = maxOf(p?.totalPages ?: 0, chapter.pageCount),
                lastReadPage = p?.lastReadPage ?: 0,
                isDownloaded = true,               // 本地源恒为已缓存
            )
        }
        volumesCache[seriesId] = volumes
        return volumes
    }

    /**
     * 用 kzz.moe 漫画详情页链接设置封面。
     *
     * kzz.moe 的搜索要登录，所以没法按书名自动搜；详情页是公开的，
     * 让用户从浏览器复制 `https://kzz.moe/c/12345.htm` 这类链接过来最省事。
     */
    fun setCoverFromUrl(seriesId: String, comicUrl: String) {
        viewModelScope.launch {
            val file = coverStore.destination(seriesId)
            val ok = KzzMoeCoverFetcher.fetchCover(comicUrl, file)
            if (!ok) {
                // 抓失败时别把可能已经存在的旧封面覆盖成空文件
                if (file.exists() && file.delete()) Unit
                _hints.emit("封面抓取失败：请确认链接是 kzz.moe 的作品详情页（形如 https://kzz.moe/c/12345.htm）")
                return@launch
            }
            coverStore.setFile(seriesId, file)
            _hints.emit("封面已更新")
        }
    }

    /** 从相册 / 文件选择器选一张本地图片当封面 */
    fun setCoverFromUri(seriesId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                coverStore.setFromUri(seriesId, uri)
            }.onSuccess {
                _hints.emit("封面已更新")
            }.onFailure {
                Diag.e(app, TAG, "设置封面失败：$seriesId", it)
                _hints.emit("设置封面失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** 取消手动封面，退回目录里自动识别的结果 */
    fun clearCover(seriesId: String) {
        viewModelScope.launch {
            runCatching { coverStore.clear(seriesId) }
                .onSuccess { _hints.emit("封面已恢复自动识别") }
                .onFailure { _hints.emit("清除封面失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /**
     * 把「带源前缀的 id」还原成源实例 + 源内相对路径。
     * 拿不到对应源（目录被撤销授权）时退回本地源，至少不会因为一次解析失败就让抽屉空着。
     */
    private fun resolveSource(itemId: String): Pair<MangaSource, String> {
        val slash = itemId.indexOf('/')
        if (slash <= 0) return localSource to itemId

        val sourceId = itemId.substring(0, slash)
        val relativePath = itemId.substring(slash + 1)
        if (sourceId == LibraryPreferences.LOCAL_SOURCE_ID) return localSource to relativePath
        if (davSourceIdOf(itemId) != null) {
            return (davSources.value[sourceId] ?: localSource) to relativePath
        }

        val root = _roots.value.firstOrNull { it.sourceId == sourceId }
            ?: return localSource to relativePath
        return SafeMangaSource(app.contentResolver, Uri.parse(root.uri)) to relativePath
    }

    /**
     * 判断某个 id 属于哪个 WebDAV 账号，返回源 id；不是 WebDAV 作品返回 null。
     *
     * **为什么要单独抽一个函数**：作品 id 形如 `dav:d3f9a1c07/作品/第01话`，
     * 前缀那段可能是老格式 `dav`（没有冒号），解析时得两边都认。
     */
    private fun davSourceIdOf(itemId: String): String? {
        val head = itemId.substringBefore('/')
        return when {
            head == CredentialsStore.LEGACY_SOURCE_ID -> head
            head.startsWith(CredentialsStore.LEGACY_SOURCE_ID + ":") && head.length > 5 -> head
            else -> null
        }
    }

    /**
     * 卷的可打开句柄：本地私有目录给绝对路径，SAF 目录给 content uri。
     * [com.example.mangareader.core.document.MangaDocumentFactory] 两种都认。
     */
    fun resolvePath(chapterId: String): String {
        val slash = chapterId.indexOf('/')
        if (slash <= 0) return File(rootDir, chapterId).absolutePath

        val sourceId = chapterId.substring(0, slash)
        val relativePath = chapterId.substring(slash + 1)

        // WebDAV 章节：句柄交给 ReaderViewModel 的 WebDAV 分支处理。
        // 句柄要带上账号（老数据是 `dav:相对路径`，新增的是 `dav:<sourceId>/相对路径`），
        // 否则同时配了两个 WebDAV 时，阅读器分不清该用哪套账号打开。
        val davId = davSourceIdOf(chapterId)
        if (davId != null) {
            return if (davId == CredentialsStore.LEGACY_SOURCE_ID) {
                "dav:$relativePath"
            } else {
                "dav:$davId/$relativePath"
            }
        }

        if (sourceId == LibraryPreferences.LOCAL_SOURCE_ID) {
            return File(rootDir, relativePath).absolutePath
        }
        // SAF 分支：从 1.2 起把完整 documentId 直接存在 chapterId 里，
        // 不再自己拼相对路径，避免某些云盘 provider 的 documentId 不是简单路径拼接。
        val root = _roots.value.firstOrNull { it.sourceId == sourceId }
        if (root != null) {
            val treeUri = Uri.parse(root.uri)
            return DocumentUris.documentUri(treeUri, relativePath).toString()
        }
        return File(rootDir, relativePath).absolutePath
    }

    /** SAF 授权目录：向系统申请「可持久读取」的权限并记下来，重启 App 后依然有效 */
    fun importRoot(uri: Uri) {
        viewModelScope.launch {
            val label = labelOf(uri)
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, SAF_PERSIST_READ)
                libraryPreferences.addRoot(uri.toString(), label)
                _hints.emit("已导入：$label")
            }.onFailure { _hints.emit("导入失败：$label（${it.message}）") }
        }
    }

    /** 撤销授权：同时把 permanent permission 还回去，否则系统会一直留着 */
    fun removeRoot(uri: Uri) {
        viewModelScope.launch {
            val label = labelOf(uri)
            runCatching {
                app.contentResolver.releasePersistableUriPermission(uri, SAF_PERSIST_READ)
                libraryPreferences.removeRoot(uri.toString())
                _hints.emit("已移除：$label")
            }.onFailure { _hints.emit("移除失败：$label（${it.message}）") }
        }
    }

    /** `tree/primary%3AManga` 这种 uri -> 「Manga」，用于提示文案 */
    private fun labelOf(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
    }.getOrDefault("漫画目录")

    fun findVolume(seriesId: String, volumeId: String): MangaVolumeUiModel? =
        volumesCache[seriesId]?.firstOrNull { it.id == volumeId }

    /** 「续读」的目标：第一条读到一半的卷；没有则回到第一卷 */
    fun lastReadVolume(seriesId: String): MangaVolumeUiModel? {
        val volumes = volumesCache[seriesId] ?: return null
        return volumes.firstOrNull { it.progress > 0f && !it.isFinished } ?: volumes.firstOrNull()
    }

    internal companion object {
        /**
         * SAF 选目录 Intent 的 flags：读取授权 + 可持久化。
         *
         * **为什么写字面量而不是引用常量**：`Intent.FLAG_GRANT_READ_PERSISTABLE` 这个名字在公开 SDK
         * 中不存在（对应的值 0x40 是 AOSP 内部标记），但值本身在运行时可用。
         * `Intent.FLAG_GRANT_READ_URI_PERMISSION`（0x1）是公开常量，此处一并写成字面量避免歧义。
         */
        const val SAF_INTENT_FLAGS = 0x41

        /**
         * 持久化权限的 mode flag。`takePersistableUriPermission` / `releasePersistableUriPermission`
         * 只接受读/写授权标志（0x1 / 0x2 / 0x3），不允许带 0x40 持久化标记；
         * 否则系统抛 `Requested flags 0x41, but only 0x3 are allowed`。
         */
        const val SAF_PERSIST_READ = 0x1
    }
}
