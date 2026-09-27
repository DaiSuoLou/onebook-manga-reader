package com.example.mangareader.core.document

import com.example.mangareader.core.util.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 本地图片文件夹漫画解析器
 *
 * 构造是私有的，必须走 [open] —— 扫描目录是 IO，不能在构造里做。
 *
 * @param directory 漫画文件夹根路径
 * @param imageFiles 经过自然排序后的合法漫画图片文件列表
 */
class DirectoryMangaDocument private constructor(
    val directory: File,
    private val imageFiles: List<File>
) : MangaDocument {

    override val pageCount: Int = imageFiles.size

    /**
     * 获取指定页的图片流
     * 仅在阅读器翻到该页时打开文件流，用完后由外部（如 Coil Fetcher）负责关闭
     */
    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        checkBounds(index)
        val file = imageFiles[index]
        if (!file.exists() || !file.canRead()) {
            throw FileNotFoundException("Image file not accessible: ${file.absolutePath}")
        }
        FileInputStream(file)
    }

    /**
     * 获取该页的稳定标识。
     *
     * **必须用绝对路径而不是文件名**：这个值会被 MangaImageLoader 直接当作 Coil 的
     * memoryCacheKey / diskCacheKey。如果只返回 "01.jpg"，不同章节的同名页会撞 key，
     * 磁盘缓存会把别的章节的图片返回过来。
     */
    override fun getPageIdentifier(index: Int): String {
        checkBounds(index)
        return imageFiles[index].absolutePath
    }

    /**
     * 获取该页的绝对路径（可选工具方法，便于直接调试或通过绝对路径传给部分三方库）
     */
    fun getPageFile(index: Int): File {
        checkBounds(index)
        return imageFiles[index]
    }

    override fun close() {
        // 文件夹解析器基于文件路径按需获取流，无持久常驻文件句柄，此处置空或静默处理即可
    }

    private fun checkBounds(index: Int) {
        if (index !in 0 until pageCount) {
            throw IndexOutOfBoundsException("Page index $index out of bounds (0 until $pageCount)")
        }
    }

    companion object {
        /**
         * 异步扫描并构建 DirectoryMangaDocument 实例
         *
         * @param dir 目标文件夹
         * @return DirectoryMangaDocument
         * @throws IllegalArgumentException 如果传入路径不是目录
         * @throws FileNotFoundException 目录不存在
         */
        suspend fun open(dir: File): DirectoryMangaDocument = withContext(Dispatchers.IO) {
            if (!dir.exists()) {
                throw FileNotFoundException("Directory does not exist: ${dir.absolutePath}")
            }
            if (!dir.isDirectory) {
                throw IllegalArgumentException("Provided path is not a directory: ${dir.absolutePath}")
            }

            // 列出该目录下所有合法图片文件（扩展名复用文档层统一的 IMAGE_EXT，避免两套常量漂移）
            val files = dir.listFiles { file ->
                if (!file.isFile || file.isHidden) {
                    false
                } else {
                    file.extension.lowercase() in IMAGE_EXT
                }
            }?.toMutableList() ?: mutableListOf()

            // 使用自然排序规则排序（解决 1, 10, 2 问题）
            files.sortWith { f1, f2 ->
                NaturalOrderComparator.compare(f1.name, f2.name)
            }

            DirectoryMangaDocument(
                directory = dir,
                imageFiles = files
            )
        }
    }
}
