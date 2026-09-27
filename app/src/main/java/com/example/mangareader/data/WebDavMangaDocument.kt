package com.example.mangareader.data

import com.example.mangareader.core.document.MangaDocument
import com.example.mangareader.core.document.MangaDocumentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * WebDAV 章节的 [MangaDocument] 适配层。
 *
 * 阅读器只认 MangaDocument（已打开的文档对象），而 MangaDocumentFactory 只支持
 * 本机文件与 SAF content uri；WebDAV 拿不到可随机访问的句柄，所以在这里桥接：
 *
 * - **压缩包章节**：整包下载到本地缓存（[WebDavMangaSource.cachedArchive]，已存在则复用），
 *   然后复用本地文档实现 —— zip/cbz 走随机访问，其余压缩格式走顺序流解压缓存。
 *   首次打开等于一次完整下载，之后秒开。
 * - **文件夹章节**：一次 PROPFIND 列出图片得到页数，取页时按 URL 直接开流，
 *   无需下载整章。
 *
 * 文档由 [ReaderViewModel][com.example.mangareader.ui.reader.ReaderViewModel] 持有，
 * 生命周期与其它文档一致（切卷/退出才 close）。
 */
object WebDavMangaDocument {

    /**
     * 打开一个 WebDAV 章节。
     *
     * @param source 已用凭证构建好的 WebDAV 源
     * @param chapterId 源内相对路径（不带 `dav/` 前缀）
     */
    suspend fun open(
        source: WebDavMangaSource,
        chapterId: String,
        cacheDir: File,
        context: android.content.Context,
    ): MangaDocument = withContext(Dispatchers.IO) {
        val ext = chapterId.substringAfterLast('.', "").lowercase()
        if (ext in source.archiveExtensions) {
            // 压缩包：下载到缓存后走本地实现
            val local = source.cachedArchive(chapterId)
            MangaDocumentFactory.open(local.absolutePath, cacheDir, context)
        } else {
            FolderWebDavMangaDocument.open(source, chapterId)
        }
    }
}

/** WebDAV 上的图片文件夹章节：流式读图，无需下载整章 */
private class FolderWebDavMangaDocument private constructor(
    private val source: WebDavMangaSource,
    private val chapterId: String,
    private val pageUrls: List<String>,
) : MangaDocument {

    override val pageCount: Int get() = pageUrls.size

    override suspend fun getPageStream(index: Int): InputStream {
        require(index in pageUrls.indices) {
            "pageIndex $index 超出范围 (共 ${pageUrls.size} 页)"
        }
        return source.openUrl(pageUrls[index])
    }

    override fun getPageIdentifier(index: Int): String = "dav:$chapterId#$index"

    /** sardine 客户端无需显式关闭；流由调用方负责关闭 */
    override fun close() {}

    companion object {
        suspend fun open(source: WebDavMangaSource, chapterId: String): FolderWebDavMangaDocument =
            withContext(Dispatchers.IO) {
                FolderWebDavMangaDocument(source, chapterId, source.listImageUrls(chapterId))
            }
    }
}
