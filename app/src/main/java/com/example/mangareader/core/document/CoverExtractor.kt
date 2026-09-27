package com.example.mangareader.core.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.mangareader.data.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 从漫画文件**内部**抽第一页，做成一张缩略图当封面。
 *
 * 为什么需要它：目录扫描只能认出「目录里已经躺着的图片」（cover.jpg / 第一张图），
 * 而一本 .zip/.cbz/.pdf/.mobi 单文件漫画，外面看过去一张图都没有 —— 书架就永远是空白的。
 * 这里直接开文档读第一页，把漫画自己的封面拿过来，不需要任何网络请求。
 *
 * 三处刻意的保守：
 * 1. 只取第一页，不整本读；
 * 2. 缩到 [TARGET_WIDTH] 再存 JPEG，一本 2000px 宽的图压出来只有几十 KB；
 * 3. 全程 [withTimeout] + [runCatching] —— 打开一个畸形/加密/冷门格式的文件不该让书架崩掉，
 *    抽不出来就老实返回 false，作品照常显示标题。
 */
object CoverExtractor {

    /** 书架一格大概就这个宽度，再大纯属浪费空间 */
    private const val TARGET_WIDTH = 480

    private const val JPEG_QUALITY = 82

    /** 单个文件最多给这么多时间（PDF 首帧渲染、mobi 翻记录都可能有慢的） */
    private const val TIMEOUT_MS = 20_000L

    private const val TAG = "CoverExtractor"

    /**
     * 把 [path] 指向的漫画的第一页抽出来存到 [out]，成功返回 true。
     *
     * @param path 与 [MangaDocumentFactory.open] 同形：本机绝对路径或 `content://` SAF uri
     * @param cacheDir 压缩包 / pdf 的解压缓存
     */
    suspend fun extractToFile(
        context: Context,
        path: String,
        cacheDir: File,
        out: File,
    ): Boolean = withTimeout(TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                val document = MangaDocumentFactory.open(path, cacheDir, context)
                document.use { doc ->
                    if (doc.pageCount <= 0) {
                        Diag.w(context, TAG, "文档无页：$path")
                        return@use false
                    }
                    doc.getPageStream(0).use { stream ->
                        val full = BitmapFactory.decodeStream(stream.buffered())
                        if (full == null) {
                            Diag.w(context, TAG, "第一页解码失败：$path")
                            return@use false
                        }
                        val scaled = scaleToWidth(full)
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { sink ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink)
                        }
                        if (scaled !== full) scaled.recycle()
                        full.recycle()
                    }
                }
                (out.length() > 0).also { ok ->
                    Diag.d(context, TAG, "抽封面结果 ok=$ok out=${out.absolutePath} length=${out.length()}")
                }
            }.onFailure {
                Diag.e(context, TAG, "抽封面异常：$path", it)
            }.getOrDefault(false)
        }
    }

    /**
     * 把[native] stream 里的第一张图缩成封面存到 [out]。
     *
     * 与 [extractToFile] 的区别是**输入来自网络流**（WebDAV 上没本地文件，
     * 只能远程拉第一话第一页），调用方负责关流。
     */
    suspend fun extractFromStream(
        context: Context,
        stream: java.io.InputStream,
        out: File,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val full = BitmapFactory.decodeStream(stream.buffered())
            if (full == null) {
                Diag.w(context, TAG, "远程封面解码失败：$out")
                return@runCatching false
            }
            val scaled = scaleToWidth(full)
            out.parentFile?.mkdirs()
            out.outputStream().buffered().use { sink ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink)
            }
            if (scaled !== full) scaled.recycle()
            full.recycle()
            (out.length() > 0).also { ok ->
                Diag.d(context, TAG, "远程抽封面 ok=$ok out=${out.absolutePath} length=${out.length()}")
            }
        }.onFailure {
            Diag.e(context, TAG, "远程抽封面异常：$out", it)
        }.getOrDefault(false)
    }

    /** 超宽图按宽度等比缩，小图原样返回（调用方负责 recycle） */
    private fun scaleToWidth(src: Bitmap): Bitmap {
        if (src.width <= TARGET_WIDTH) return src
        val ratio = src.width.toFloat() / TARGET_WIDTH
        return Bitmap.createScaledBitmap(src, TARGET_WIDTH, (src.height / ratio).toInt(), true)
    }
}
