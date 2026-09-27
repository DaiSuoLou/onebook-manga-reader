package com.example.mangareader.core.document

import com.example.mangareader.core.util.naturalSortedBy
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 压缩包漫画的「取流后端」——把「怎么枚举图片条目」和「怎么把某个条目写出来」与
 * [ArchiveMangaDocument] 的落盘缓存逻辑解耦，这样 ZIP/TAR/7Z（commons-compress）
 * 和 RAR（junrar）可以共用同一套扫描、排序、缓存代码。
 */
interface ArchiveBackend {

    /** 缓存目录名，同一份压缩包内容变了名字也跟着变 */
    val cacheName: String

    /** 枚举全部图片条目，返回已按自然排序的名字 */
    suspend fun scanImages(): List<String>

    /** 把指定条目完整写入 [out]；每次调用都要从条目开头重新写 */
    suspend fun writeEntry(name: String, out: OutputStream)
}

/**
 * ZIP / CBZ / CBR / TAR / 7Z 等 commons-compress 能顺序流解开的格式。
 *
 * [ArchiveInputStream] 只能顺序读、且每次打开都从头开始，所以「写某一条目」要重开一次流
 * 重新扫描到目标位置 —— 只解目标那一页，不做全量解压。
 */
class CompressBackend(
    private val openStream: () -> InputStream,
    override val cacheName: String,
) : ArchiveBackend {

    override suspend fun scanImages(): List<String> = withContext(Dispatchers.IO) {
        val list = ArrayList<String>()
        openStream().buffered().use { raw ->
            // 1.27 起 createArchiveInputStream 是泛型方法，Kotlin 推断不出 I，必须显式声明
            val ais: ArchiveInputStream<ArchiveEntry> =
                ArchiveStreamFactory().createArchiveInputStream(raw)
            ais.use {
                while (true) {
                    val e = ais.nextEntry ?: break
                    if (e.isDirectory) continue
                    // 与 ZipMangaDocument 保持一致：排除 macOS 垃圾目录与隐藏文件
                    if (e.name.startsWith("__MACOSX/") || e.name.contains("/.")) continue
                    if (e.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT) list.add(e.name)
                }
            }
        }
        list.naturalSortedBy { it.substringAfterLast('/') }
    }

    override suspend fun writeEntry(name: String, out: OutputStream) = withContext(Dispatchers.IO) {
        openStream().buffered().use { raw ->
            val ais: ArchiveInputStream<ArchiveEntry> =
                ArchiveStreamFactory().createArchiveInputStream(raw)
            ais.use {
                while (true) {
                    val e = ais.nextEntry ?: break
                    if (e.name == name) {
                        ais.copyTo(out)
                        // copyTo 返回的是拷贝字节数(Long)，函数声明是 Unit，必须显式收尾
                        return@withContext Unit
                    }
                }
            }
        }
    }

    companion object {
        fun of(file: File) = CompressBackend({ FileInputStream(file) }, "arch/${file.name}-${file.length()}")
        fun ofStream(open: () -> InputStream, displayName: String) =
            CompressBackend(open, "arch/$displayName")
    }
}

/**
 * RAR。commons-compress 自 1.8 起已移除 RAR 支持（专有格式未公开），改用 junrar。
 *
 * junrar 的 [Archive] 支持随机访问文件，也支持直接吃 InputStream（SAF 授权目录里的 rar 走这条），
 * 所以这里每次写条目都新开一个 Archive 实例，避免上一个条目的解码游标影响下一个。
 */
class RarBackend(
    private val openRar: () -> Archive,
    override val cacheName: String,
) : ArchiveBackend {

    override suspend fun scanImages(): List<String> = withContext(Dispatchers.IO) {
        val list = ArrayList<String>()
        openRar().use { ar ->
            for (h in ar.fileHeaders) {
                if (h.isDirectory) continue
                val name = h.fileName
                if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXT) list.add(name)
            }
        }
        list.naturalSortedBy { it.substringAfterLast('/') }
    }

    override suspend fun writeEntry(name: String, out: OutputStream) = withContext(Dispatchers.IO) {
        openRar().use { ar ->
            val header = ar.fileHeaders.firstOrNull { !it.isDirectory && it.fileName == name }
                ?: error("RAR 里找不到条目: $name")
            ar.getInputStream(header).use { it.copyTo(out) }
            Unit
        }
    }

    companion object {
        fun of(file: File) = RarBackend(
            { Archive(file) },
            "rar/${file.name}-${file.length()}",
        )

        fun ofStream(open: () -> InputStream, displayName: String) = RarBackend(
            { Archive(open()) },
            "rar/$displayName",
        )
    }
}
