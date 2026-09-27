package com.example.mangareader.core.document

import com.example.mangareader.core.util.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ZIP / CBZ 章节。
 *
 * 用 java.util.zip.ZipFile 做随机访问：打开时只枚举条目，取页时按需解流，
 * 不需要像 commons-compress 的 ArchiveInputStream 那样整包顺序扫描。
 * 因此 zip/cbz 走这里，tar / 7z 等才走 ArchiveMangaDocument。
 *
 * 注意：返回的 InputStream 绑定在 ZipFile 上，close() 之后流会失效，
 * 调用方应在文档关闭前读完。
 */
class ZipMangaDocument private constructor(
    private val zipFile: ZipFile,
    private val entries: List<ZipEntry>,
) : MangaDocument {

    override val pageCount: Int = entries.size

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        checkBounds(index)
        val entry = entries[index]
        zipFile.getInputStream(entry)
            ?: throw IllegalStateException("Failed to open stream for entry: ${entry.name}")
    }

    override fun getPageIdentifier(index: Int): String {
        checkBounds(index)
        return entries[index].name
    }

    override fun close() {
        try {
            zipFile.close()
        } catch (_: Exception) {
            // 静默释放句柄
        }
    }

    private fun checkBounds(index: Int) {
        if (index !in 0 until pageCount) {
            throw IndexOutOfBoundsException("Page index $index out of bounds (0..${pageCount - 1})")
        }
    }

    companion object {

        /** 异步构建 ZipMangaDocument 实例 */
        suspend fun open(file: File): ZipMangaDocument = withContext(Dispatchers.IO) {
            if (!file.exists()) {
                throw FileNotFoundException("Manga archive not found: ${file.absolutePath}")
            }

            val zip = ZipFile(file)
            val validEntries = mutableListOf<ZipEntry>()

            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue

                val name = entry.name
                // 排除 macOS 系统垃圾目录与隐藏文件
                if (name.startsWith("__MACOSX/") || name.contains("/.")) continue

                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXT) validEntries.add(entry)
            }

            // 使用自然排序规整页码
            validEntries.sortWith { e1, e2 -> NaturalOrderComparator.compare(e1.name, e2.name) }

            ZipMangaDocument(zip, validEntries)
        }
    }
}
