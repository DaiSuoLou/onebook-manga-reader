package com.example.mangareader.data

import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.example.mangareader.core.document.MangaDocumentFactory
import com.example.mangareader.core.document.MangaFormats
import com.example.mangareader.core.document.isChapterFile
import com.example.mangareader.core.util.naturalSortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.zip.ZipFile

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------

data class MangaItem(
    val id: String,          // 唯一标识（源的 path 或 mangaId）
    val title: String,       // 展示名
    val coverUrl: String?,   // 封面（WebDAV 为 url，本地为 file path）
    /**
     * 该作品的代表文件/目录，供「从漫画文件内部抽封面」用（[CoverExtractor]）。
     * 本地给绝对路径，SAF 给 `content://` uri，远程作品（WebDAV）没有本地文件，留 null。
     *
     * 加它的原因：[coverUrl] 只能报出「目录里已经躺着的图片」，一本 zip/pdf 单文件漫画
     * 外面看过去一张图都没有，书架就永远是空白的；有了这个字段才能直接开文档读第一页。
     */
    val mainFile: String? = null,
)

data class ChapterItem(
    val id: String,          // 章节唯一标识（chapterId）
    val title: String,       // 展示名（"第01话" / "Vol.1"）
    val index: Int,          // 排序序号
    val pageCount: Int = 0,  // 页数；0 = 未知（WebDAV 源暂不数，打开过一次后由进度库兜底）
)

private const val TAG = "WebDavMangaSource"

// ---------------------------------------------------------------------------
// 源抽象
// ---------------------------------------------------------------------------

interface MangaSource {
    /** 浏览目录/分类。path 为源内相对路径，根目录传 ""。 */
    suspend fun getMangaList(path: String): List<MangaItem>

    /** 获取章节/卷 */
    suspend fun getChapters(mangaId: String): List<ChapterItem>

    /** 获取图片输入流或本地/缓存路径 */
    suspend fun getPageStream(chapterId: String, pageIndex: Int): InputStream
}

// ---------------------------------------------------------------------------
// 本地源：目录结构约定
//
//   root/
//     分类A/                  <- 可选的中间层级，getMangaList(path) 逐级浏览
//       作品名/
//         第01话/             <- 章节 = 子文件夹（内含 001.jpg ...）
//         第02话/
//         全一话.cbz          <- 或章节 = zip/cbz 压缩包
//
// mangaId / chapterId 都是相对 root 的路径，天然稳定可持久化。
// ---------------------------------------------------------------------------

class LocalMangaSource(
    private val rootDir: File,
) : MangaSource {

    private val archiveExt = setOf("zip", "cbz")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

    override suspend fun getMangaList(path: String): List<MangaItem> =
        withContext(Dispatchers.IO) {
            val dir = resolve(path)
            if (!dir.isDirectory) return@withContext emptyList()

            val subDirs = dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.naturalSortedBy { it.name }

            // 正常结构：root 下有作品子目录。
            if (!subDirs.isNullOrEmpty()) {
                return@withContext subDirs.map { child ->
                    MangaItem(
                        id = relative(child),
                        title = child.name,
                        coverUrl = findCover(child)?.absolutePath,
                        // 封面提取要开文件读第一页：如果目录里只有压缩包，
                        // 直接传目录进去会读到空（DirectoryMangaDocument 只认松散图片）。
                        // 这里把 mainFile 指到「能出第一页」的第一个子文件/子目录上。
                        mainFile = firstCoverableChild(child)?.absolutePath ?: child.absolutePath,
                    )
                }
            }

            // 兜底：root 本身是一部作品，里面直接放章节文件。
            val hasChapters = dir.listFiles().orEmpty().any {
                !it.isDirectory && !it.name.startsWith(".") && it.isChapterFile()
            }
            if (hasChapters) {
                return@withContext listOf(
                           MangaItem(
                    id = "",
                    title = dir.name,
                    coverUrl = findCover(dir)?.absolutePath,
                    mainFile = firstCoverableChild(dir)?.absolutePath ?: dir.absolutePath,
                ),
                )
            }

            return@withContext emptyList()
        }

    override suspend fun getChapters(mangaId: String): List<ChapterItem> =
        withContext(Dispatchers.IO) {
            val mangaDir = resolve(mangaId)
            if (!mangaDir.isDirectory) return@withContext emptyList()

            mangaDir.listFiles().orEmpty()
                .filter { entry ->
                    when {
                        entry.name.startsWith(".") -> false
                        entry.isDirectory -> true          // 文件夹章节
                        entry.isChapterFile() -> true       // zip/cbz/rar/7z/pdf/epub/mobi 章节
                        else -> false
                    }
                }
                .naturalSortedBy { it.name }   // 第2话 排在 第10话 前
                .mapIndexed { i, entry ->
                    ChapterItem(
                        id = relative(entry),
                        title = entry.name,
                        index = i,
                        // 数页数是廉价操作：文件夹数一遍图片，zip 只读中央目录（不解压）
                        pageCount = pageCountOf(entry),
                    )
                }
        }

    /**
     * 章节页数：文件夹 = 图片文件数；zip/cbz = 中央目录里的图片条目数（只读目录，不解包）。
     *
     * rar / 7z / pdf / epub / mobi 数一遍页数要么得顺序流读完整包、要么得开文档渲染，
     * 书架扫描时挨个算太慢，这里统一先给 0 —— 书籍打开后由真实页数接管。
     */
    private fun pageCountOf(entry: File): Int = when {
        entry.isDirectory -> entry.listFiles().orEmpty()
            .count { it.isFile && it.extension.lowercase() in imageExt }

        entry.extension.lowercase() in archiveExt -> runCatching {
            ZipFile(entry).use { zip ->
                zip.entries().toList().count {
                    !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExt
                }
            }
        }.getOrDefault(0)

        else -> 0
    }

    override suspend fun getPageStream(chapterId: String, pageIndex: Int): InputStream =
        withContext(Dispatchers.IO) {
            val chapter = resolve(chapterId)

            if (chapter.isFile) {
                // 压缩包章节：按文件名自然排序取第 pageIndex 张
                ZipFile(chapter).use { zip ->
                    val images = zip.entries().toList()
                        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExt }
                        .naturalSortedBy { it.name }
                    require(pageIndex in images.indices) {
                        "pageIndex $pageIndex 超出范围 (共 ${images.size} 页)"
                    }
                    // 复制到内存，避免 zip 句柄关闭后流失效
                    val entry = images[pageIndex]
                    val bytes = zip.getInputStream(entry).readBytes()
                    bytes.inputStream()
                }
            } else {
                // 文件夹章节：图片按文件名排序
                val images = chapter.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in imageExt }
                    .naturalSortedBy { it.name }
                require(pageIndex in images.indices) {
                    "pageIndex $pageIndex 超出范围 (共 ${images.size} 页)"
                }
                images[pageIndex].inputStream()
            }
        }

    // ---- helpers ----

    private fun resolve(path: String): File {
        val f = File(rootDir, path.trim('/'))
        // 防目录穿越：确保解析结果仍在 root 内
        require(f.canonicalPath.startsWith(rootDir.canonicalPath)) { "非法路径: $path" }
        return f
    }

    private fun relative(f: File) =
        f.relativeTo(rootDir).invariantSeparatorsPath

    /** 在作品目录/其子目录里找一张封面图 */
    private fun findCover(dir: File): File? {
        val direct = dir.listFiles().orEmpty().firstOrNull {
            it.isFile && it.extension.lowercase() in imageExt &&
                it.nameWithoutExtension.lowercase() in setOf("cover", "folder", "poster")
        }
        if (direct != null) return direct
        val firstImage = dir.listFiles().orEmpty().firstOrNull {
            it.isFile && it.extension.lowercase() in imageExt
        }
        if (firstImage != null) return firstImage
        return dir.listFiles().orEmpty().firstOrNull { it.isDirectory }?.let { findCover(it) }
    }

    /**
     * 找目录里第一个能当「封面提取源」的子项：
     * 优先 cover/folder/poster 图片，其次任意第一张图，最后第一个章节文件（压缩包/子目录）。
     * 这样 zip/cbz 漫画的目录本身没有松散图片时，也能抽出第一卷第一页当书架封面。
     */
    private fun firstCoverableChild(dir: File): File? {
        val direct = dir.listFiles().orEmpty().firstOrNull {
            it.isFile && it.extension.lowercase() in imageExt &&
                it.nameWithoutExtension.lowercase() in setOf("cover", "folder", "poster")
        }
        if (direct != null) return direct
        val firstImage = dir.listFiles().orEmpty().firstOrNull {
            it.isFile && it.extension.lowercase() in imageExt
        }
        if (firstImage != null) return firstImage
        // 优先章节文件（zip/cbz/pdf 等）：对「作品/第01话.cbz」这种结构能直接抽到第一页。
        // 目录兜底放到最后，避免选到一个空子目录导致封面提取失败。
        val chapter = dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && it.isChapterFile() }
            .naturalSortedBy { it.name }
            .firstOrNull()
        if (chapter != null) return chapter
        return dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && it.isDirectory }
            .naturalSortedBy { it.name }
            .firstOrNull { it.listFiles().orEmpty().any { f -> !f.name.startsWith(".") } }
    }
}

// ---------------------------------------------------------------------------
// WebDAV 源：目录结构约定与本地一致（root 下 分类/作品/章节）
//
// 章节可以是 WebDAV 上的文件夹（内含图片），也可以是 zip/cbz 等压缩包
//（下载到本地缓存后按 ArchiveMangaDocument 的方式读取）。
// 依赖：sardine-android（封装 PROPFIND / GET），凭证由 CredentialsStore 提供。
// ---------------------------------------------------------------------------

class WebDavMangaSource(
    private val baseUrl: String,   // 如 https://dav.example.com/dav/manga/
    private val username: String,
    private val password: String,
    private val cacheDir: File,    // 外部传入的缓存目录
    /** 打开容器文档（pdf/epub/mobi）时需要，交给 MangaDocumentFactory 用 */
    private val context: android.content.Context,
) : MangaSource {

    // 注意：sardine-android 0.8 里没有 SardineFactory，只有 OkHttpSardine；
    // 而且它自带的 BasicAuthenticator 是包级私有的，所以基础认证要自己接。
    private val sardine: Sardine = OkHttpSardine(
        OkHttpClient.Builder()
            .authenticator(webDavAuthenticator(username, password))
            // sardine 自带的默认 client 没有超时：服务端只握手不回数据时，
            // 「保存并测试」会永远转圈，书架扫描也会一起卡住。这里全部显式设上限。
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    /** 容器扩展名：需要整本下载到缓存后按本地文档打开的格式 */
    private val containerExt = MangaFormats.CHAPTER_FILE
    /** 常见包装目录名：root 下出现这些目录且没有平铺文件时自动展开 */
    private val WRAPPER_NAMES = setOf("webdav", "dav", "comics", "manga", "漫画", "kindle")
    /** 远程文件缓存管理器（带容量上限与 LRU 清理） */
    private val cacheManager = WebDavCacheManager(cacheDir, CachePreferences(context))

    /** 自动展开最多几层。正常结构只需要 1 层，给到 4 层足够挡住死循环 */
    private companion object {
        const val MAX_WRAPPER_DEPTH = 4
    }

    // ---- 目录浏览 ----

    /**
     * 列目录，[id] 为源内相对路径，返回结果不含目录自身。
     *
     * **永不抛异常**：PROPFIND 失败时返回空列表，由上层走兜底分支。
     * 旧实现把异常抛出去，而调用方是用 `getOrDefault(emptyList())` 兜住的，
     * 于是变成「0 卷」黑箱 —— 既没有卷，也看不到任何失败原因。
     */
    private fun safeListDir(id: String): List<DavResource> {
        val url = davUrl(id)
        val listed = runCatching {
            sardine.list(url)
        }.onFailure {
            android.util.Log.w(TAG, "PROPFIND 失败：$id", it)
        }.getOrDefault(emptyList())
        // 第一项是目录自身，但要按路径认而不盲 drop(1)：
        // 有的服务器不回这一项，盲 drop 会把第一个真正的孩子丢掉。
        //
        // **这里必须用「后缀匹配」而不是「全等」**：`r.path` 是去掉 host 的源路径
        // （如 `dav/作品A`），而 [url] 是带 `https://host` 的完整地址，
        // 两者直接 == 永远不相等 —— 旧的比较等于这层防护完全没生效，
        // 目录自身一直混在子项里被当成「第一卷」打开，结果就是 0 页。
        val base = url.trimEnd('/')
        return listed.filterNot { r ->
            val path = runCatching { r.path }.getOrDefault("").trimEnd('/')
            path.isNotEmpty() && (path == base || base.endsWith("/$path"))
        }
    }

    /** 资源名。[DavResource.getName] 解析失败时会返回 null，直接用会 NPE 并让整个列表静默变空 */
    private fun nameOf(r: DavResource): String =
        runCatching { r.name }.getOrDefault("")

    /**
     * 目录判定。
     *
     * **不能只看 `isDirectory()`**：sardine 的实现是
     * `contentType == "httpd/unix-directory"`，而不少 WebDAV 服务端对集合返回的是
     * `application/directory`、`inode/directory`，甚至干脆不回这个属性 ——
     * 结果整棵目录树被当成文件，书架和章节列表就全空了。这里再补 resourcetype 一位。
     */
    private fun DavResource.isCollection(): Boolean {
        val types = runCatching { getResourceTypes() }.getOrNull()
        if (!types.isNullOrEmpty() &&
            types.any { it.localPart.equals("collection", ignoreCase = true) }
        ) return true
        val contentType = runCatching { getContentType() }.getOrNull().orEmpty()
        return contentType.equals("httpd/unix-directory", ignoreCase = true) ||
            contentType.contains("directory", ignoreCase = true)
    }

    /** 拆成「目录」与「非目录文件」两拨；点开头的隐藏项一律丢掉 */
    private fun splitChildren(id: String): Pair<List<DavResource>, List<DavResource>> {
        val children = safeListDir(id).filterNot { isSelfEntry(it, id) }
        val dirs = children.filter { it.isCollection() }
        val files = children.filter { !it.isCollection() && !nameOf(it).startsWith('.') }
        return dirs to files
    }

    /**
     * 这一项是不是 [id] 目录自身。
     *
     * **为什么必须剔掉**：不少 WebDAV 服务在 PROPFIND 一个目录时，会把目录自身也列进子项里
     * （或 `relId` 因 href 与 path 编码不一致算成空串）。它一起被当成「第一卷」后，
     * 打开就是 0 页——书架抽封面表现为「永远没封面」，阅读器表现为多出一个点不开的卷。
     * 这里按展示名 + 空 id 两头拦。
     */
    private fun isSelfEntry(resource: DavResource, id: String): Boolean {
        val own = id.trim('/')
        if (own.isEmpty()) return false
        val rel = relId(resource).trim('/')
        if (rel == own) return true
        // relId 算成空串时退回按展示名比：这种服务连目录自身也报空 id
        return rel.isEmpty() && resource.isCollection() && nameOf(resource) == titleOf(id)
    }

    /** 源内路径 -> 展示名（兜底分支把整目录当一部作品时用） */
    private fun titleOf(path: String): String =
        if (path.trim('/').isNotBlank()) path.trim('/').substringAfterLast('/')
        else baseUrl.trimEnd('/').substringAfterLast('/')

    /** 章节内图片的 DavResource（自然排序） */
    private fun imagesOf(chapterId: String): List<DavResource> =
        safeListDir(chapterId)
            .filter { !it.isCollection() && it.name.substringAfterLast('.', "").lowercase() in imageExt }
            .naturalSortedBy { it.name }

    /** 作品目录内优先当封面的图片：cover/folder/poster > 任意第一张图 */
    private fun coverImageOf(children: List<DavResource>): DavResource? {
        val images = children.filter {
            !it.isCollection() && nameOf(it).substringAfterLast('.', "").lowercase() in imageExt
        }
        val preferredNames = setOf("cover", "folder", "poster")
        return images.firstOrNull {
            nameOf(it).substringBeforeLast('.').lowercase() in preferredNames
        } ?: images.firstOrNull()
    }

    /**
     * 目录项是不是「冗余的包装目录」——书架上一格多余的 `webdav/` 就是它。
     *
     * **判据**：名字像包装目录（webdav/dav/comics/manga/漫画/kindle），
     * 且里面的内容在同级已经出现过一份。以前 root 下既有平铺的作品、
     * 又留着一个整库镜像的 `webdav/`，书架就会莫名多出一整排重复的书。
     * 空壳包装目录（里面什么都没有）同样按冗余处理。
     */
    private fun isRedundantWrapper(resource: DavResource, siblings: List<DavResource>): Boolean {
        val name = nameOf(resource).trimEnd('/')
        if (name.lowercase() !in WRAPPER_NAMES) return false
        val ownId = relId(resource).trim('/')
        val own = safeListDir(ownId).filterNot { isSelfEntry(it, ownId) }
        if (own.isEmpty()) return true
        val siblingNames = siblings.map { nameOf(it).trimEnd('/').lowercase() }
        return own.all { nameOf(it).trimEnd('/').lowercase() in siblingNames }
    }

    /**
     * 只取「已经缓存过」的封面路径，不碰网络。
     *
     * 目录扫描阶段原来直接调 [findCover]，等于每列一个作品就发一个 PROPFIND + 一张图下载，
     * 作品一多书架要等好几分钟才出内容（表现就是「书架一片空白」）。
     * 这里退成读本地缓存：有就用，没有就先不显示，等打开抽屉时再按需抓。
     */
    private fun coverCachePath(mangaId: String): String? =
        File(cacheDir, "covers/${mangaId.hashCode()}.jpg")
            .takeIf { it.exists() }
            ?.absolutePath

    /** 按需补抓一次作品封面（打开抽屉时调用，失败返回 null，不影响已经在书架上的显示） */
    internal suspend fun refreshCover(mangaId: String): String? = findCover(mangaId)

    /**
     * 抓作品封面图并落到统一位置；失败返回 null。
     *
     * **顺序**：先找作品目录里现成的封面图（cover.jpg 之类），
     * 一张都没有就退到「第一话第一页」—— 绝大多数单文件/文件夹漫画都没有单独的封面文件，
     * 只留前者的话 WebDAV 上的书会永远没有封面。
     */
    private suspend fun findCover(mangaId: String): String? =
        withContext(Dispatchers.IO) {
            val cache = coverFile(mangaId)
            if (cache.exists()) return@withContext cache.absolutePath

            val image = runCatching { coverImageOf(safeListDir(mangaId)) }.getOrNull()
            if (image != null) {
                runCatching {
                    cache.parentFile?.mkdirs()
                    val tmp = File(cache.absolutePath + ".part")
                    sardine.get(fileUrl(relId(image))).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!tmp.renameTo(cache)) {
                        tmp.delete()
                        null
                    } else {
                        cache.absolutePath
                    }
                }.getOrNull()?.let { return@withContext it }
            }

            val firstChapter = runCatching { getChapters(mangaId).firstOrNull()?.id }.getOrNull()
            if (firstChapter.isNullOrBlank()) return@withContext null
            runCatching {
                cache.parentFile?.mkdirs()
                val tmp = tmpCopy(getPageStream(firstChapter, 0))
                if (tmp.renameTo(cache)) cache.absolutePath else null
            }.getOrNull()
        }

    /** 封面统一存放位置：[cacheDir]/covers/<hash>.jpg，与 [coverCachePath] 对齐 */
    private fun coverFile(mangaId: String): File =
        File(cacheDir, "covers/${mangaId.hashCode()}.jpg")

    /**
     * 本源抽出来的封面目录。
     *
     * **必须和 [coverCachePath] 读的是同一处**：书架侧原来把抽好的封面写到
     * `cacheDir/dav/covers/`，而读取侧认的是 `cacheDir/covers/`，
     * 于是重启之后一张都认不出来，书架又变回一片深色方块。
     */
    val coverDir: File get() = File(cacheDir, "covers").apply { mkdirs() }

    /**
     * 抽**某一卷**的第一页当封面（抽屉里每卷单独显示）。
     *
     * 与 [extractFirstPageCover] 的区别在取页方式：[extractFirstPageCover] 走章节列表，
     * 而分卷本身既可能是容器文件（mobi/zip），也可能是图片目录；
     * 目录卷要直接取第一张图，走章节列表会碰到「子里套目录、里面没有图片」的情况。
     */
    internal suspend fun extractVolumeCover(volumeId: String, out: File): Boolean =
        runCatching {
            val ext = volumeId.substringAfterLast('.', "").lowercase()
            val stream = if (ext in containerExt) {
                // 容器卷（mobi/zip/pdf/...）：直接走取页管线，别先过 getChapters。
                // getChapters 是列**目录**的孩子，对「文件路径」发 PROPFIND 列不出东西，
                // 返回空列表后旧写法直接 return false —— 抽屉里每一卷都落到作品封面兜底，
                // 看起来就是「所有卷共用同一张封面」。
                Diag.d(context, TAG, "抽分卷封面（容器）：$volumeId")
                getPageStream(volumeId, 0)
            } else {
                // 目录卷：直接取自然排序后的第一张图
                val images = imagesOf(volumeId)
                if (images.isEmpty()) {
                    Diag.w(context, TAG, "抽分卷封面（目录）没有图片：$volumeId")
                    return false
                }
                Diag.d(context, TAG, "抽分卷封面（目录）：$volumeId 首图=${nameOf(images.first())}")
                sardine.get(fileUrl(relId(images.first())))
            }
            stream.use { input ->
                com.example.mangareader.core.document.CoverExtractor.extractFromStream(context, input, out)
            }
        }.onFailure {
            Diag.e(context, TAG, "抽分卷封面异常：$volumeId", it)
        }.getOrDefault(false)

    /** 把流原样落成一个 `.part` 临时文件，交给调用方 rename */
    private suspend fun tmpCopy(stream: InputStream): File {
        val tmp = File(cacheDir, "covers/cover.part")
        tmp.outputStream().buffered().use { output -> stream.copyTo(output) }
        return tmp
    }

    /**
     * 书架扫描时用的「远程抽第一页当封面」入口。
     *
     * 与 [findCover] 不同，这里直接出一张缩略图：远程那张原图动辄几 MB，
     * 直接原样缓存会把 WebDAV 缓存撑爆。缩略图落在 [coverFile] 同一处，
     * 所以下次扫描 [coverCachePath] 就能直接读到，不会重复下载。
     */
    internal suspend fun extractFirstPageCover(mangaId: String, out: File): Boolean {
        // 容器型（mobi / 整包 zip）要先整包下到缓存，一条「开始」日志才能在诊断里
        // 区分开「还在下」和「真失败了」—— 否则封面一直不出来时看不出卡在哪一步。
        Diag.i(context, TAG, "开始抽远程封面：$mangaId -> ${out.name}")
        return runCatching {
            val chapters = getChapters(mangaId)
            // **优先挑带容器扩展名的一话**：作品下常有「外传」这类子目录排在前面，
            // 而子目录里装的又是下一层目录（里面没有直接图片），照着它取页就是 0 页。
            val chapter = chapters.firstOrNull { it.id.substringAfterLast('.', "").lowercase() in containerExt }?.id
                ?: chapters.firstOrNull()?.id
                ?: run {
                    Diag.w(context, TAG, "抽远程封面没有可用章节：$mangaId")
                    return false
                }
            Diag.d(context, TAG, "抽远程封面取页：$chapter")
            getPageStream(chapter, 0).use { stream ->
                com.example.mangareader.core.document.CoverExtractor.extractFromStream(
                    context,
                    stream,
                    out,
                )
            }
        }.onFailure {
            Diag.e(context, TAG, "抽远程封面异常：$mangaId", it)
        }.getOrDefault(false)
    }

    override suspend fun getMangaList(path: String): List<MangaItem> =
        withContext(Dispatchers.IO) { collectMangaList(path, visited = HashSet()) }

    /**
     * [getMangaList] 的实现，多带一个「已经展开过的目录」集合。
     *
     * **为什么要有 visited**：自动展开包装目录是递归的，而 `relId()` 在某些服务端
     * （href 编码与 path 不一致、或目录自身也被列出来）会把包装目录算成空 id，
     * 于是 `getMangaList("")` 又回到 `getMangaList("")` —— 变成无限循环，
     * 每轮还各发一次 PROPFIND：表现就是设置页「保存并测试」永远转圈、书架一直空着。
     * 这里既挡重复 id，也限制展开深度。
     */
    private suspend fun collectMangaList(path: String, visited: HashSet<String>): List<MangaItem> =
        withContext(Dispatchers.IO) {
            if (!visited.add(path) || visited.size > MAX_WRAPPER_DEPTH) {
                android.util.Log.w(TAG, "包装目录展开已去重，停止递归 path=$path")
                return@withContext emptyList()
            }

            val (dirs, files) = splitChildren(path)

            // 自动展开常见的「一层包装目录」：root 下只有一个文件夹、没有直接放文件，
            // 或者 root 下存在名为 webdav/dav/comics/manga/漫画/kindle 这类包装目录且没有平铺文件，
            // 直接把该文件夹的内容当作 root 的内容列出来，避免书架多出一个 webdav/ 之类的包装项。
            if (path.isEmpty() && files.isEmpty()) {
                val wrapperDir = dirs.firstOrNull { nameOf(it).lowercase() in WRAPPER_NAMES }
                    ?: dirs.takeIf { it.size == 1 }?.first()
                if (wrapperDir != null) {
                    val next = relId(wrapperDir).trim('/')
                    // 展开后 id 为空 / 与当前是同一个目录，说明解析不出更深层的内容，直接放弃展开
                    if (next.isNotEmpty() && next != path) {
                        android.util.Log.d(TAG, "root 包装目录 ${nameOf(wrapperDir)} 自动展开")
                        return@withContext collectMangaList(next, visited)
                    }
                    android.util.Log.w(TAG, "包装目录 ${nameOf(wrapperDir)} 解析为空 id，放弃展开")
                }
            }

            if (dirs.isNotEmpty()) {
                val listed = dirs.naturalSortedBy { nameOf(it) }
                val result = ArrayList<MangaItem>(listed.size)
                for (dir in listed.filterNot { isRedundantWrapper(it, listed) }) {
                    val id = relId(dir)
                    result.add(
                        MangaItem(
                            id = id,
                            title = nameOf(dir),
                            // 只认本地已缓存的封面，扫描阶段不发网络请求
                            coverUrl = coverCachePath(id),
                        )
                    )
                }
                return@withContext result
            }

            // 兜底：目录里直接放章节文件、没有作品子目录，把整目录当成一部作品。
            // 与本地源口径一致，避免 WebDAV 上的扁平结构显示成空书架。
            if (files.isEmpty()) return@withContext emptyList()
            listOf(
                MangaItem(
                    id = path.trim('/'),
                    title = titleOf(path),
                    coverUrl = coverCachePath(path.trim('/')),
                )
            )
        }

    override suspend fun getChapters(mangaId: String): List<ChapterItem> =
        withContext(Dispatchers.IO) {
            val (dirs, files) = splitChildren(mangaId)

            // 正常过滤：文件夹，或认得出来的章节文件
            val chapters = (dirs + files.filter { nameOf(it).isChapterFile() })
                .ifEmpty {
                    // 兜底：服务器不返回扩展名 / MIME 时对不上，把所有非目录项都列出来，
                    // 免得变成「全套共 0 卷」的黑箱。
                    files
                }
                .naturalSortedBy { nameOf(it) }
                .mapIndexed { i, r ->
                    ChapterItem(
                        id = relId(r),
                        title = nameOf(r).ifBlank { relId(r) },
                        index = i,
                    )
                }

            if (chapters.isEmpty()) {
                android.util.Log.w(
                    TAG,
                    "作品 $mangaId 下没有可用章节（PROPFIND 返回 ${dirs.size} 个目录 / ${files.size} 个文件）",
                )
            }
            chapters
        }

    override suspend fun getPageStream(chapterId: String, pageIndex: Int): InputStream =
        withContext(Dispatchers.IO) {
            val ext = chapterId.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext in containerExt) {
                // 容器章节：整包下载到缓存后按真实格式取页。
                // 旧实现在这里一律按 zip 打开，mobi/pdf/epub/rar 会直接失败。
                val local = cachedFile(chapterId)
                val document = MangaDocumentFactory.open(local.absolutePath, cacheDir, context)
                // 必须先把流读到内存再 close document，否则 document 关闭后底层句柄失效，
                // 调用方拿到的会是一个已关闭/失效的流，表现为黑屏。
                document.use { doc ->
                    doc.getPageStream(pageIndex).use { stream ->
                        ByteArrayInputStream(stream.readBytes())
                    }
                }
            } else {
                // 文件夹章节：按名字排序后直读该页。页码 -> 文件名的映射走内存缓存，
                // 免得每页都重发一次 PROPFIND。
                val images = imageListOf(chapterId)
                if (images.isEmpty()) {
                    // **为什么要打出来**：书架上一格没封面时，光看「共 0 页」判断不出是
                    // 文件名没扩展名被当成了目录、还是 PROPFIND 本身就没返回内容。
                    // 这里把原始目录项列出来，一眼能看出是哪种。
                    Diag.w(
                        context,
                        TAG,
                        "章节无图片：chapterId=[$chapterId] 目录项=[" +
                            safeListDir(chapterId).take(6).joinToString(", ") { r ->
                                "${nameOf(r)}${if (r.isCollection()) "/" else ""}"
                            } + "]",
                    )
                }
                require(pageIndex in images.indices) { "pageIndex $pageIndex 超出范围 (共 ${images.size} 页)" }
                sardine.get(fileUrl(images[pageIndex]))
            }
        }

    // ---- WebDAV 细节 ----

    /** 容器扩展名集合，供 [WebDavMangaDocument] 判定章节类型 */
    internal val archiveExtensions: Set<String> get() = containerExt

    /**
     * 章节内页图的「源内相对路径」列表（自然排序）。
     *
     * **为什么要缓存**：`getPageStream` 每取一页都得先知道这一页对应的文件名，
     * 不缓存的话打开一话要发 N 个 PROPFIND + N 个 GET，翻页等待被整体放大。
     * 同一会话里目录内容不会变，这里按访问顺序保留最近 16 话。
     */
    private val imageListCache = Collections.synchronizedMap(
        LinkedHashMap<String, List<String>>(16, 0.75f, true)
    )

    internal suspend fun imageListOf(chapterId: String): List<String> =
        imageListCache.getOrPut(chapterId) {
            imagesOf(chapterId).map { relId(it) }
        }

    /**
     * 章节内图片的完整 URL（自然排序）。
     * [WebDavMangaDocument] 用它确定 pageCount 并按序取页。
     */
    internal suspend fun listImageUrls(chapterId: String): List<String> =
        withContext(Dispatchers.IO) {
            imageListOf(chapterId).map { fileUrl(it) }
        }

    /** 打开一个 WebDAV 资源的读取流 */
    internal fun openUrl(url: String): InputStream = sardine.get(url)

    /** 把压缩包章节整包下载到本地缓存（已存在则复用），返回本地文件 */
    internal suspend fun cachedArchive(chapterId: String): File = cachedFile(chapterId)

    /** 源内 id（相对路径）-> 完整 URL（结尾一定带一个 /） */
    private fun davUrl(id: String): String {
        val segments = id.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return buildString {
            append(baseUrl.trimEnd('/'))
            segments.forEach { append('/').append(it) }
            append('/')
        }
    }

    /** 文件 URL（不带结尾斜杠） */
    private fun fileUrl(id: String): String = davUrl(id).trimEnd('/')

    /** DavResource -> 源内 id。用 getPath()（URI 已解码过的路径）比拼 href 字符串更稳 */
    private fun relId(r: DavResource): String {
        val path = runCatching { r.path }.getOrDefault("")
        val basePath = baseUrl.substringAfter("://").substringAfter('/', "")
        val suffix = if (basePath.isBlank()) path else path.substringAfter(basePath, "")
        return suffix.trim('/')
    }

    /** 下载压缩包到缓存，已存在则复用；走 [WebDavCacheManager] 做 LRU 淘汰 */
    private suspend fun cachedFile(chapterId: String): File =
        cacheManager.obtain(chapterId) { sardine.get(fileUrl(chapterId)) }
}

/**
 * WebDAV 基础认证。
 *
 * sardine-android 0.8 既没有 SardineFactory，自带的 BasicAuthenticator 又是包级私有类，
 * 所以只能自己用 OkHttp 的 Authenticator 实现：首次收到 401 时补上 Authorization 头。
 *
 * 已经带过凭据仍被 401 就返回 null（表示放弃），否则 OkHttp 会无限重试。
 */
private fun webDavAuthenticator(username: String, password: String) = Authenticator { _, response ->
    if (response.request.header("Authorization") != null) {
        null
    } else {
        response.request.newBuilder()
            .header("Authorization", Credentials.basic(username, password))
            .build()
    }
}
