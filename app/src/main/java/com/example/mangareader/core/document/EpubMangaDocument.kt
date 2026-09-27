package com.example.mangareader.core.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * EPUB 章节（容器本身也是 ZIP）：container.xml -> OPF -> spine -> 页面资源。
 * - 漫画型 EPUB：spine 直接指向图片
 * - 通用 EPUB：spine 指向 XHTML，取其中第一张 <img>
 */
class EpubMangaDocument(
    private val file: File,
) : MangaDocument {

    private val zip = ZipFile(file)
    private val pages: List<String>     // zip 内资源路径

    init {
        val opfPath = zip.getEntry("META-INF/container.xml")
            ?.let { zip.getInputStream(it).use(::parseContainerXml) }
            ?: error("不是有效的 EPUB: 缺少 container.xml")

        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opfEntry = zip.getEntry(opfPath) ?: error("OPF 不存在: $opfPath")
        val opf = zip.getInputStream(opfEntry).use { parseOpf(it) }

        pages = opf.spine.mapNotNull { idref ->
            val href = opf.manifest[idref] ?: return@mapNotNull null
            val full = resolveRelative(opfDir, href)
            if (full.substringAfterLast('.', "").lowercase() in IMAGE_EXT) full
            else firstImageInXhtml(full)?.let { resolveRelative(full.substringBeforeLast('/', ""), it) }
        }.distinct()
    }

    override val pageCount: Int get() = pages.size

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        val path = pagePath(index)
        val entry = zip.getEntry(path) ?: error("EPUB 资源缺失: $path")
        ByteArrayInputStream(zip.getInputStream(entry).readBytes())
    }

    override fun getPageIdentifier(index: Int): String = "${file.name}#${pagePath(index)}"

    override fun close() = zip.close()

    private fun pagePath(index: Int): String {
        require(index in pages.indices) { "页码越界: $index / 共 $pageCount 页" }
        return pages[index]
    }

    private fun firstImageInXhtml(xhtmlPath: String): String? {
        val entry = zip.getEntry(xhtmlPath) ?: return null
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
    }

    private fun parseContainerXml(input: InputStream): String {
        val parser = newParser(input)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val href = parser.getAttributeValue(null, "full-path")
                if (parser.getAttributeValue(null, "media-type") == "application/oebps-package+xml" && href != null) {
                    return href
                }
            }
        }
        error("container.xml 中没有 rootfile")
    }

    private data class Opf(val manifest: Map<String, String>, val spine: List<String>)

    private fun parseOpf(input: InputStream): Opf {
        val parser = newParser(input)
        val manifest = LinkedHashMap<String, String>()
        val spine = ArrayList<String>()
        var inManifest = false
        var inSpine = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "manifest" -> inManifest = true
                    "spine" -> inSpine = true
                    "item" -> if (inManifest) {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> if (inSpine) {
                        parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "manifest" -> inManifest = false
                    "spine" -> inSpine = false
                }
            }
        }
        return Opf(manifest, spine)
    }

    private fun resolveRelative(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').trimStart('/')
        if (baseDir.isEmpty()) return clean
        val stack = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in clean.split('/')) {
            when (part) {
                "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
