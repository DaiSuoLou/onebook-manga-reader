package com.example.mangareader.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mangareader.MangaApplication
import com.example.mangareader.core.document.MangaDocument
import com.example.mangareader.core.document.MangaDocumentFactory
import com.example.mangareader.data.CredentialsStore
import com.example.mangareader.data.WebDavMangaDocument
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * WebDAV 章节句柄前缀（BookshelfViewModel.resolvePath 生成，此处消费）。
 *
 * 句柄形如 `dav:作品/第01话`（单个 WebDAV 的老格式），
 * 或 `dav:dav:d3f9a1c07/作品/第01话`（多账号时带上源 id）。
 * 源 id 本身也以 `dav:` 开头，所以剥掉句柄前缀后还要按第一个 `/` 再切一次。
 */
private const val DAV_HANDLE_PREFIX = "dav:"

/**
 * 阅读会话的持有者。
 *
 * **为什么必须由 ViewModel 持有 [MangaDocument]**：Coil 是异步解码的，翻页时它会拿着
 * [MangaDocument] 去 `getPageStream()`。如果文档跟着 Composable 的生命周期走（比如放在
 * `remember` 里），离开阅读页时 ZipFile 被关闭，而解码任务还在后台跑，就会读到已关闭的
 * 句柄而崩。所以文档的生命周期挂在 ViewModel 上，只在 [closeReader] / [onCleared] 才 close。
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    /** 进度仓库（由 Application 持有，避免每个 ViewModel 各自建一套内存缓存） */
    private val progressRepository =
        (application as MangaApplication).progressRepository

    /** WebDAV 账号存储（与设置页共用同一份加密存储） */
    private val credentialsStore = CredentialsStore(application)

    /**
     * 已展开过的账号列表。
     *
     * **为什么要缓存**：每次翻到 WebDAV 章节都要按源 id 反查账号，
     * 而读加密 SharedPreferences 要访问 Keystore，反复解会明显拖慢开页。
     */
    @Volatile
    private var cachedAccounts: List<CredentialsStore.DavAccount>? = null

    private suspend fun accounts(): List<CredentialsStore.DavAccount> =
        cachedAccounts ?: credentialsStore.accounts().also { cachedAccounts = it }

    /** 一次阅读会话：某卷 + 已打开的文档 + 起始页 */
    data class ReaderSession(
        val chapterId: String,
        val title: String,
        val document: MangaDocument,
        val initialPage: Int,
    )

    private val _session = MutableStateFlow<ReaderSession?>(null)
    val session: StateFlow<ReaderSession?> = _session.asStateFlow()

    /** 打开失败时的提示信息（由 UI 收集并显示 Snackbar） */
    private val _openError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openError: SharedFlow<String> = _openError.asSharedFlow()

    /** 当前打开的文档，独立于 session 记录，便于可靠关闭 */
    private var openedDocument: MangaDocument? = null

    /**
     * @param chapterId 带源前缀的章节 id（如 `saf:3f9a1c07/海贼王/第01话`）
     * @param path 可打开句柄：本机绝对路径，或 SAF 的 content uri，两者 [MangaDocumentFactory] 都认
     * @param startPage 起始页（0 起）；传已读页数即可，内部会夹到合法范围
     */
    fun openChapter(
        chapterId: String,
        path: String,
        title: String,
        startPage: Int,
    ) {
        viewModelScope.launch {
            closeDocument()   // 切卷时先关掉上一本，避免同时挂着多个 ZipFile

            val app = getApplication<Application>()
            val document = runCatching {
                if (path.startsWith(DAV_HANDLE_PREFIX)) {
                    // WebDAV 章节：句柄形如 `dav:作品/第01话`，
                    // 得按里面带的源 id 找到对应那套账号，构建源后桥接成文档
                    val (sourceId, relativePath) = parseDavHandle(path)
                    val account = accounts().firstOrNull { it.sourceId == sourceId }
                        ?: error("WebDAV「$sourceId」未配置，请到设置里检查账号")
                    val source = credentialsStore.buildSource(account, davCacheDir(app))
                        ?: error("WebDAV「${account.name}」未配置，请到设置里检查账号")
                    WebDavMangaDocument.open(source, relativePath, app.cacheDir, app)
                } else {
                    MangaDocumentFactory.open(path, app.cacheDir, app)
                }
            }.getOrElse { error ->
                val message = error.message ?: error.javaClass.simpleName
                android.util.Log.e("ReaderViewModel", "打开失败: $message", error)
                _openError.tryEmit("打开失败：$message")
                return@launch
            }

            openedDocument = document
            progressRepository.recordPageCount(chapterId, document.pageCount)

            _session.value = ReaderSession(
                chapterId = chapterId,
                title = title,
                document = document,
                initialPage = startPage.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)),
            )
        }
    }

    private fun davCacheDir(app: Application): File =
        File(app.cacheDir, "dav").apply { mkdirs() }

    /**
     * 解析 WebDAV 句柄 → (源 id, 源内相对路径)。
     *
     * 句柄是 `dav:` 开头，而多账号的源 id 也是 `dav:` 开头，所以剥掉句柄前缀后
     * 还得再判一次：出现第二个 `:` 说明带源 id，否则按老格式当 [CredentialsStore.LEGACY_SOURCE_ID]。
     */
    private fun parseDavHandle(path: String): Pair<String, String> {
        val body = path.removePrefix(DAV_HANDLE_PREFIX)
        val colon = body.indexOf(':')
        val slash = body.indexOf('/')
        return if (colon > 0 && slash > colon) {
            body.substring(0, colon) to body.substring(slash + 1)
        } else {
            CredentialsStore.LEGACY_SOURCE_ID to body
        }
    }

    /** 阅读器回传当前页（0 起），转成「已读页数」落库 */
    fun onPageChanged(pageIndex: Int) {
        val chapterId = _session.value?.chapterId ?: return
        progressRepository.recordPage(chapterId, pageIndex)
    }

    fun closeReader() {
        _session.value = null
        closeDocument()
    }

    private fun closeDocument() {
        // Closeable.close() 会抛 IOException，关闭失败不应影响退出
        openedDocument?.let { runCatching { it.close() } }
        openedDocument = null
    }

    override fun onCleared() {
        closeDocument()
        super.onCleared()
    }
}
