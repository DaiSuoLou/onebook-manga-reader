package com.example.mangareader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从 kzz.moe 作品详情页抓取封面图。
 *
 * **为什么只能贴详情页链接、不能按书名自动搜**：kzz.moe 的搜索接口需要登录态，
 * 匿名访问 `/search` 会直接被弹到登录页；书名里还常带 `[Mox.moe]` 这类前缀，没法精确匹配。
 * 但作品详情页（`/c/12345.htm`）是公开的，页面里的 `og:image` / `twitter:image` 就是封面大图，
 * 所以做成「浏览器打开详情页 → 复制链接 → 粘到书架抽屉的封面入口」。
 *
 * 封面域名 `kmimg.moex.ink` 的链接带 `sign=<expire>-...`，过期后要重新抓。
 */
    object KzzMoeCoverFetcher {

    private val OG_IMAGE = Regex(
        """<meta[^>]*(?:name|property)\s*=\s*["'](?:og:image|twitter:image)["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_IMAGE_REVERSED = Regex(
        """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:name|property)\s*=\s*["'](?:og:image|twitter:image)["']""",
        RegexOption.IGNORE_CASE,
    )
    /** 详情页里常见的封面主图容器：<img class="img_book" src="..."> */
    private val IMG_BOOK = Regex(
        """<img[^>]+class\s*=\s*["'][^"']*img_book[^"']*["'][^>]*src\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val IMG_BOOK_REVERSED = Regex(
        """<img[^>]+src\s*=\s*["']([^"']+)["'][^>]*class\s*=\s*["'][^"']*img_book[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val FIRST_IMG = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val IMAGE_PATH = Regex("""\.(?:jpg|jpeg|png|webp|avif|bmp)(?:\?|$)""", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * @param pageUrl kzz.moe 作品详情页地址，如 https://kzz.moe/c/26878.htm
     * @param out 封面图保存路径
     * @return true 表示成功；失败时 [out] 不会被写成空文件
     */
    suspend fun fetchCover(pageUrl: String, out: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val html = fetchText(pageUrl) ?: return@withContext false

            // og:image 优先，twitter:image / 详情页主图兜底（几站做法不统一，能拿到的都试一遍）
            val coverUrl = extractAttr(html, OG_IMAGE.pattern)
                ?: extractAttr(html, OG_IMAGE_REVERSED.pattern)
                ?: extractAttr(html, IMG_BOOK.pattern)
                ?: extractAttr(html, IMG_BOOK_REVERSED.pattern)
                ?: firstImageSrc(html)
                ?: return@withContext false

            val bytes = fetchBytes(resolveUrl(pageUrl, coverUrl)) ?: return@withContext false
            if (bytes.isEmpty()) return@withContext false

            out.parentFile?.mkdirs()
            val tmp = File(out.absolutePath + ".tmp")
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return@withContext false
            }
            true
        }.getOrDefault(false)
    }

    private fun extractAttr(html: String, pattern: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.trim()

    /** 兜底：抓第一张扩展名确实是图片的 <img>，列表页缩略图之外的主图通常就是封面 */
    private fun firstImageSrc(html: String): String? =
        FIRST_IMG.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { IMAGE_PATH.containsMatchIn(it) }
            ?.replace("&amp;", "&")
            ?.trim()

    /** 详情页里拿到的多为相对地址，这里按 base 还原成绝对地址 */
    private fun resolveUrl(base: String, relative: String): String = when {
        relative.startsWith("http://") || relative.startsWith("https://") -> relative
        relative.startsWith("//") -> "https:$relative"
        relative.startsWith("/") -> {
            val host = base.substringAfter("://").substringBefore('/')
            "https://$host$relative"
        }
        else -> {
            val last = base.lastIndexOf('/')
            if (last > 7) base.substring(0, last + 1) + relative else base
        }
    }

    private fun fetchText(url: String): String? =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }

    private fun fetchBytes(url: String): ByteArray? =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
}
