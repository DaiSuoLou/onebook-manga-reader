package com.example.mangareader.core.document

import com.example.mangareader.core.util.naturalSortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 压缩包章节：ZIP / CBZ / CBR / TAR / 7Z / RAR 走各自的 [ArchiveBackend]。
 *
 * 压缩包只能顺序读、无法随机访问指定页，因此策略是：
 * 首次取某页时从头扫描到目标条目并落盘缓存，之后直接读缓存文件。
 * 「扫条目 → 按自然排序 → 落盘缓存」这套算法对所有后端只有一份，
 * 具体怎么读包由 [ArchiveBackend] 决定。
 */
class ArchiveMangaDocument(
    private val backend: ArchiveBackend,
    private val cacheDir: File,
    private val pages: List<String>,
) : MangaDocument {

    private val lock = Mutex()
    private var outDir: File? = null

    override val pageCount: Int get() = pages.size

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(resolvePageFile(index))
    }

    override fun getPageIdentifier(index: Int): String {
        require(index in pages.indices) { "页码越界: $index / 共 $pageCount 页" }
        return "${backend.cacheName}#${pages[index]}"
    }

    override fun close() = Unit

    private suspend fun resolvePageFile(index: Int): File {
        require(index in pages.indices) { "页码越界: $index / 共 $pageCount 页" }

        val dir = lock.withLock {
            outDir ?: File(cacheDir, backend.cacheName)
                .also { outDir = it }
        }

        val name = pages[index]
        // 用序号做前缀，保证自然排序结果稳定且避免同名条目互相覆盖
        val out = File(dir, "${index.toString().padStart(5, '0')}_${name.substringAfterLast('/')}")
        if (out.exists()) return out

        dir.mkdirs()
        lock.withLock {
            if (out.exists()) return out
            // 后端每次都要从条目开头重新写，这里不必重置游标
            val tmp = File(out.absolutePath + ".part")
            try {
                tmp.outputStream().use { out2: java.io.OutputStream -> backend.writeEntry(name, out2) }
                if (!tmp.renameTo(out)) error("解压失败: ${out.name}")
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
        return out
    }

    companion object {

        /** 本地文件版：ZIP / CBZ / CBR / TAR / 7Z / RAR 都能直接读 */
        suspend fun open(archive: File, cacheDir: File): ArchiveMangaDocument =
            withContext(Dispatchers.IO) {
                val backend = if (archive.extension.lowercase() in MangaFormats.RAR) {
                    RarBackend.of(archive)
                } else {
                    CompressBackend.of(archive)
                }
                ArchiveMangaDocument(backend, cacheDir, backend.scanImages())
            }

        /** SAF 版：压缩包内容只能顺序流读，靠上面的缓存机制反复取 */
        suspend fun openContent(
            resolver: android.content.ContentResolver,
            documentUri: android.net.Uri,
            cacheDir: File,
        ): ArchiveMangaDocument = withContext(Dispatchers.IO) {
            val displayName = documentUri.lastPathSegment ?: "archive"
            val backend = if (displayName.substringAfterLast('.', "").lowercase() in MangaFormats.RAR) {
                RarBackend.ofStream(
                    { resolver.openInputStream(documentUri) ?: error("打不开压缩包: $displayName") },
                    displayName,
                )
            } else {
                CompressBackend.ofStream(
                    { resolver.openInputStream(documentUri) ?: error("打不开压缩包: $displayName") },
                    displayName,
                )
            }
            ArchiveMangaDocument(backend, cacheDir, backend.scanImages())
        }
    }
}
