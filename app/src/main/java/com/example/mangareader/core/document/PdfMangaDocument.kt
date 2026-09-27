package com.example.mangareader.core.document

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * PDF 章节：用系统 PdfRenderer 逐页渲染成 JPEG 并缓存到磁盘。
 *
 * PdfRenderer 非线程安全，且同一时刻每页只能存在一个 Page 实例，
 * 因此所有渲染串行执行。
 */
class PdfMangaDocument(
    private val file: File,
    private val cacheDir: File,
    private val renderWidth: Int = 1600,
    private val quality: Int = 88,
) : MangaDocument {

    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()

    private val outDir: File = File(cacheDir, "pdf/${file.nameWithoutExtension}-${file.length()}")
        .apply { mkdirs() }

    override val pageCount: Int get() = renderer.pageCount

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(pageFile(index))
    }

    override fun getPageIdentifier(index: Int): String {
        require(index in 0 until pageCount) { "页码越界: $index / 共 $pageCount 页" }
        return "${file.name}#page-$index"
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }

    private suspend fun pageFile(index: Int): File {
        require(index in 0 until pageCount) { "页码越界: $index / 共 $pageCount 页" }
        val out = File(outDir, "page-$index.jpg")
        if (out.exists()) return out

        lock.withLock {
            if (out.exists()) return out
            renderer.openPage(index).use { page ->
                val scale = renderWidth.toFloat() / page.width
                val w = renderWidth
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val tmp = File(out.absolutePath + ".part")
                    tmp.outputStream().use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                    }
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        error("PDF 页面渲染失败: $index")
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return out
    }
}
