package com.example.mangareader.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.example.mangareader.core.document.IMAGE_EXT
import com.example.mangareader.core.document.MangaFormats
import com.example.mangareader.core.document.isChapterFile
import com.example.mangareader.core.document.isChapterMime
import com.example.mangareader.core.util.naturalSortedBy
import com.example.mangareader.data.LibraryPreferences.Companion.itemIdOf
import com.example.mangareader.data.LibraryPreferences.Companion.sourceIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * SAF 授权目录漫画源 —— 与 [LocalMangaSource] 目录约定完全一致的另一种实现。
 *
 *   root/                    <- 用户通过 ACTION_OPEN_DOCUMENT_TREE 授权进来的目录
 *     作品名/
 *       第01话/              <- 章节 = 图片文件夹
 *       第02话.cbz           <- 或章节 = 压缩包
 *
 * **为什么目录结构要复用 [LocalMangaSource]**：书架的层级浏览（分类 → 作品 → 分卷）
 * 是 UI 层写死的约定，两套源若结构不同，书架就得为每种源写一套展示逻辑。
 *
 * **id 存储的是完整 documentId**：SAF 云盘 provider 返回的子文件 documentId 不一定
 * 是树 root 的简单路径拼接（有些 provider 用内部 hash），所以不再自己拼相对路径，
 * 而是把 provider 给的 documentId 原样存进 chapterId / mangaId。解析时直接用
 * [DocumentsContract.buildDocumentUriUsingTree] 还原成 content uri。
 *
 * 注意本类不参与取页 —— 阅读器拿到的是章节 content uri，直接交给
 * [com.example.mangareader.core.document.MangaDocumentFactory] 开文档。
 * [getPageStream] 保留只是为了让 [MangaSource] 接口完整。
 */
class SafeMangaSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : MangaSource {

    private val sourceId: String = sourceIdOf(treeUri.toString())
    private val rootDocumentId: String by lazy { DocumentUris.rootDocumentId(treeUri) }

    override suspend fun getMangaList(path: String): List<MangaItem> = withContext(Dispatchers.IO) {
        val docId = documentIdOf(path)
        val children = listChildren(docId)
        val subDirs = children.filter { it.isDirectory }

        // 正常结构：root 下有若干子目录，每个子目录是一部作品。
        if (subDirs.isNotEmpty()) {
            return@withContext subDirs
                .naturalSortedBy { it.displayName }
                .map { node ->
                    MangaItem(
                        id = itemIdOf(sourceId, node.documentId),
                        title = node.displayName,
                        // 封面给 content uri：Coil 自带 content:// 解码路径，不用额外注册
                        coverUrl = findCoverUri(node.documentId)?.toString(),
                        // mainFile 要指向能抽出第一页的文件：优先封面图，其次第一章节（子目录/压缩包）
                        mainFile = firstCoverableUri(node.documentId)?.toString()
                            ?: DocumentUris.documentUri(treeUri, node.documentId).toString(),
                    )
                }
        }

        // 兜底：用户可能导入一个直接放漫画文件的目录（如 123云盘里的作品目录）。
        // 此时 root 下没有子目录，但存在章节文件，把 root 本身作为一部作品返回。
        val hasChapters = children.any {
            !it.isDirectory && (it.displayName.isChapterFile() || it.mimeType.isChapterMime() != null)
        }
        if (hasChapters) {
            val title = rootDocumentId
                .substringAfterLast(':', rootDocumentId)
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() } ?: "导入目录"
            return@withContext listOf(
                MangaItem(
                    id = itemIdOf(sourceId, docId),
                    title = title,
                    coverUrl = findCoverUri(docId)?.toString(),
                    mainFile = firstCoverableUri(docId)?.toString()
                        ?: DocumentUris.documentUri(treeUri, docId).toString(),
                ),
            )
        }

        return@withContext emptyList()
    }

    override suspend fun getChapters(mangaId: String): List<ChapterItem> = withContext(Dispatchers.IO) {
        val all = listChildren(documentIdOf(mangaId))

        // 正常判据：文件夹 / 扩展名在白名单 / MIME type 在白名单。
        // 云盘 provider 常把扩展名从 displayName 里抹掉，所以 MIME 是第二道保险。
        val recognized = all.filter {
            it.isDirectory || it.displayName.isChapterFile() || it.mimeType.isChapterMime() != null
        }

        // 兜底：如果正常判据一个都没认出来，就把所有非目录、非隐藏文件都当成章节显示。
        // 这样即使扩展名/MIME 都认不出，用户也能看到"这里到底有什么"，而不是一句 0 卷。
        val chapters = recognized.ifEmpty {
            Log.w(TAG, "作品 $mangaId 下没有按格式认出的文件；把 ${all.size} 个非目录项都显示出来供诊断")
            all.filter { !it.isDirectory && !it.displayName.startsWith('.') }
        }

        if (chapters.isEmpty()) {
            Log.w(TAG, "作品 $mangaId 下没有任何子项（共 ${all.size} 个）")
        }

        chapters
            .naturalSortedBy { it.displayName }
            .mapIndexed { i, node ->
                ChapterItem(
                    id = itemIdOf(sourceId, node.documentId),
                    title = node.displayName,
                    index = i,
                    // 页数：文件夹一次 children 查询；容器是 zip 的格式顺序流 skip 数条目。
                    // rar/7z/pdf/epub/mobi 在书架先给 0，书籍打开后由真实页数接管。
                    pageCount = when {
                        node.isDirectory -> runCatching { listImages(node.documentId).size }.getOrDefault(0)
                        node.displayName.substringAfterLast('.', "").lowercase() in
                            MangaFormats.STREAM_ARCHIVE + MangaFormats.EPUB -> countSafArchivePages(node.documentId)
                        else -> 0
                    },
                )
            }
    }

    /**
     * SAF 上「容器是 zip」的章节数页数：SAF 只给顺序流，没有 ZipFile 的随机访问，
     * 用 [java.util.zip.ZipInputStream] 逐条目 skip 数据（不解压）数条目。
     * 典型一卷几十 MB，本地存储上一两秒；结果被 volumesCache 缓存，一次会话只算一遍。
     */
    private fun countSafArchivePages(documentId: String): Int {
        val uri = DocumentUris.documentUri(treeUri, documentId)
        return runCatching {
            DocumentUris.openDocument(resolver, uri)?.use { raw ->
                java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory &&
                            entry.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
                        ) count++
                        zip.closeEntry()
                    }
                    count
                }
            } ?: 0
        }.getOrDefault(0)
    }

    override suspend fun getPageStream(chapterId: String, pageIndex: Int): InputStream =
        withContext(Dispatchers.IO) {
            val images = listImages(documentIdOf(chapterId))
            require(pageIndex in images.indices) {
                "pageIndex $pageIndex 超出范围 (共 ${images.size} 页)"
            }
            val uri = DocumentUris.documentUri(treeUri, images[pageIndex].documentId)
            DocumentUris.openDocument(resolver, uri)
                ?: error("打不开图片: ${images[pageIndex].displayName}")
        }

    /** path 为空 -> root；否则把 path 当完整 documentId 用 */
    private fun documentIdOf(path: String): String =
        if (path.isEmpty() || path == sourceId) rootDocumentId else path

    /** 列目录下所有子项（含目录），空列表表示目录不存在或权限已失效 */
    private fun listChildren(documentId: String): List<DocumentUris.Child> {
        val childrenUri = DocumentUris.childrenUri(treeUri, documentId)
        return DocumentUris.listChildren(resolver, childrenUri)
    }

    /**
     * 列目录下的图片。
     *
     * 优先让 provider 端用 mime 过滤（省掉一次全量传输）；部分第三方 provider 不支持
     * LIKE，查不到就退回全量取回再本地筛 —— 结果一致，只是多花一次 IPC。
     */
    private fun listImages(documentId: String): List<DocumentUris.Child> {
        val childrenUri = DocumentUris.childrenUri(treeUri, documentId)

        val filter = runCatching {
            resolver.query(
                childrenUri,
                DocumentUris.PROJECTION,
                "${Document.COLUMN_MIME_TYPE} LIKE ?",
                arrayOf("image/%"),
                null,
            )?.use { cursor ->
                val list = ArrayList<DocumentUris.Child>(cursor.count)
                val idIdx = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                if (idIdx < 0 || nameIdx < 0) return@use null
                while (cursor.moveToNext()) {
                    list.add(
                        DocumentUris.Child(
                            documentId = cursor.getString(idIdx),
                            displayName = cursor.getString(nameIdx) ?: "",
                            mimeType = cursor.getString(mimeIdx),
                        )
                    )
                }
                list
            }?.filter { it.displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
                ?: emptyList()
        }.getOrNull()

        return if (filter.isNullOrEmpty()) {
            listChildren(documentId)
                .filter { !it.isDirectory && it.displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
        } else {
            filter
        }
    }

    /** 在该节点下找一张能当封面的图：优先 cover/folder/poster，其次任意第一张图 */
    private fun findCoverUri(documentId: String): Uri? {
        val preferred = listImagesFrom(documentId, setOf("cover", "folder", "poster"))
        if (preferred != null) return preferred
        return listImages(documentId).firstOrNull()
            ?.let { DocumentUris.documentUri(treeUri, it.documentId) }
    }

    private fun listImagesFrom(documentId: String, wantedNames: Set<String>): Uri? =
        listChildren(documentId).firstOrNull { node ->
            !node.isDirectory && node.displayName.substringBefore('.').lowercase() in wantedNames
        }?.let { DocumentUris.documentUri(treeUri, it.documentId) }

    /**
     * 找作品目录下第一个能抽出第一页的子项：
     * 优先 cover/folder/poster 图片，其次任意图片，最后第一个章节（文件夹或压缩包）。
     * 返回它的 content uri，给 [CoverExtractor] 直接开文档。
     */
    private fun firstCoverableUri(documentId: String): Uri? {
        val preferred = listImagesFrom(documentId, setOf("cover", "folder", "poster"))
        if (preferred != null) return preferred
        listImages(documentId).firstOrNull()?.let { return DocumentUris.documentUri(treeUri, it.documentId) }
        // 优先章节文件，其次非空子目录：避免选到空目录导致封面提取失败
        val chapter = listChildren(documentId)
            .filter { !it.displayName.startsWith('.') && (it.displayName.isChapterFile() || it.mimeType.isChapterMime() != null) }
            .naturalSortedBy { it.displayName }
            .firstOrNull()
        if (chapter != null) return DocumentUris.documentUri(treeUri, chapter.documentId)
        return listChildren(documentId)
            .filter { !it.displayName.startsWith('.') && it.isDirectory }
            .naturalSortedBy { it.displayName }
            .firstOrNull { listChildren(it.documentId).any { child -> !child.displayName.startsWith('.') } }
            ?.let { DocumentUris.documentUri(treeUri, it.documentId) }
    }

    private companion object {
        const val TAG = "SafeMangaSource"
    }
}
