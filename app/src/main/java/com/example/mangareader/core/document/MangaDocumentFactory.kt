package com.example.mangareader.core.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import com.example.mangareader.data.DocumentUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream

object MangaDocumentFactory {

    /**
     * 打开一卷。
     *
     * @param path 可打开句柄，两种形态：
     *             1) 本机绝对路径（[File] 路线，本地私有目录用这个）
     *             2) `content://` SAF uri（用户授权导入的目录用这个）
     * @param cacheDir 压缩包解压缓存
     * @param context 取 ContentResolver 用；SAF 分支必需，File 分支用不到
     */
    suspend fun open(path: String, cacheDir: File, context: Context): MangaDocument =
        withContext(Dispatchers.IO) {
            if (path.startsWith("content://")) {
                return@withContext openContent(path, cacheDir, context)
            }
            val f = File(path)
            when {
                // DirectoryMangaDocument 的构造是私有的，必须走 suspend open()（扫描目录是 IO）
                f.isDirectory -> DirectoryMangaDocument.open(f)
                else -> openByExtension(f, cacheDir)
            }
        }

    private suspend fun openByExtension(f: File, cacheDir: File): MangaDocument {
        val ext = f.extension.lowercase()
        // 扩展名是主判据（也支持扩展名缺失/不对、但内容是常见格式的文件），认不出再按魔数兜底。
        // 认得却打开失败时保留原始异常，否则用户只会看到一句没头没尾的"不支持的格式"。
        var openingError: Throwable? = null
        try {
            return openByKnownFormat(f, ext, cacheDir)
        } catch (e: Throwable) {
            openingError = e
        }
        return when (magicOf(f)) {
            "%PDF" -> PdfMangaDocument(f, cacheDir)
            "PK" -> ZipMangaDocument.open(f)
            "RAR" -> ArchiveMangaDocument.open(f, cacheDir)
            "7Z" -> ArchiveMangaDocument.open(f, cacheDir)
            else -> throw IllegalStateException(openingError?.message ?: "不支持的格式: ${f.name}", openingError)
        }
    }

    private suspend fun openByKnownFormat(f: File, ext: String, cacheDir: File): MangaDocument =
        when (ext) {
            in MangaFormats.PDF -> PdfMangaDocument(f, cacheDir)
            in MangaFormats.EPUB -> EpubMangaDocument(f)
            in MangaFormats.MOBI -> MobiMangaDocument(f)
            // ZipMangaDocument 有随机访问，比顺序流的 ArchiveMangaDocument 更省事，zip/cbz 走它
            in setOf("zip", "cbz") -> ZipMangaDocument.open(f)
            in MangaFormats.STREAM_ARCHIVE,
            in MangaFormats.RAR -> ArchiveMangaDocument.open(f, cacheDir)
            else -> error("不支持的格式: ${f.name}")
        }

    /**
     * SAF 分支。
     *
     * 只支持「图片文件夹」和「压缩包」两类章节 —— 但 PDF/EPUB/MOBI 也先整本复制到内部缓存
     * 再走本地那条随机访问路线：[PdfRenderer]、[ZipFile]、seek 读记录都需要随机访问，
     * 而 SAF 的 openInputStream 一次只给一条顺序流，绕不过去。
     */
    private suspend fun openContent(path: String, cacheDir: File, context: Context): MangaDocument =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(path)
            val resolver = context.contentResolver

            if (DocumentUris.mimeOf(resolver, uri) == Document.MIME_TYPE_DIR) {
                return@withContext SafeDirectoryMangaDocument.open(context, uri)
            }

            // SAF 的 lastPathSegment 往往是 documentId（含整段路径），不一定能当文件名。
            // 用 DocumentsContract 取 displayName；再拿 MIME type 兜底，防止云盘把扩展名抹掉。
            val displayName = DocumentUris.nameOf(resolver, uri)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment.orEmpty()
            val mime = DocumentUris.mimeOf(resolver, uri)
            val extFromName = displayName.substringAfterLast('.', "").lowercase()
            val extFromMime = mime?.isChapterMime()
            val ext = extFromName.takeIf { it.isNotEmpty() } ?: extFromMime ?: ""

            if (ext in MangaFormats.STREAM_ARCHIVE || ext in MangaFormats.RAR) {
                return@withContext ArchiveMangaDocument.openContent(resolver, uri, cacheDir)
            }
            if (ext in MangaFormats.PDF + MangaFormats.EPUB + MangaFormats.MOBI) {
                // 缓存文件必须有正确扩展名，否则 openByExtension 不认识
                val safeName = displayName.takeIf { it.contains('.') }
                    ?: "$displayName.$ext"
                val cached = cacheToLocal(resolver, uri, cacheDir, safeName)
                // 缓存是本机绝对路径，下面这次会走 File 分支，不会再绕回 openContent
                return@withContext openByExtension(cached, cacheDir)
            }
            error("暂不支持该格式: $displayName（mime=$mime，目前支持图片文件夹与 zip/cbz/rar/7z/pdf/epub/mobi）")
        }

    /**
     * 把 SAF 上的整本文件复制到内部缓存。
     * 已存在且大小与 provider 报告的一致时直接复用，避免每次翻书都重传一遍。
     */
    private fun cacheToLocal(
        resolver: android.content.ContentResolver,
        uri: Uri,
        cacheDir: File,
        targetName: String,
    ): File {
        val out = File(cacheDir, "safcache/$targetName")
        val reported = querySize(resolver, uri)
        if (out.exists() && (reported < 0L || out.length() == reported)) return out

        out.parentFile?.mkdirs()
        val tmp = File(out.absolutePath + ".part")
        resolver.openInputStream(uri)?.buffered()?.use { src ->
            tmp.outputStream().buffered().use { dst -> src.copyTo(dst) }
        } ?: error("读取失败: $targetName")
        if (!tmp.renameTo(out)) {
            tmp.delete()
            error("缓存失败: $targetName")
        }
        return out
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long {
        val cols = arrayOf(android.provider.OpenableColumns.SIZE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) c.getLong(i) else -1L
        } ?: -1L
    }

    private fun magicOf(f: File): String? {
        val head = ByteArray(8)
        f.inputStream().use { it.read(head) }
        return when {
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> "PK"           // zip
            head[0] == 0x25.toByte() && head[1] == 0x50.toByte() -> "%PDF"        // %PDF
            head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                head[2] == 0x72.toByte() && head[3] == 0x21.toByte() -> "RAR"     // Rar!
            head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() &&
                head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() -> "7Z"
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------
// 文档层公共工具
// ---------------------------------------------------------------------------

internal val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

internal fun newParser(input: InputStream): XmlPullParser =
    XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().also {
        it.setInput(input, "UTF-8")
    }
