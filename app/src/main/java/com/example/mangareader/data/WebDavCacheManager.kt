package com.example.mangareader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * WebDAV 文件缓存管理器。
 *
 * - 下载的容器章节按 chapterId 哈希 + 扩展名存放到缓存目录；
 * - 每次写入/命中时更新文件最后访问时间，用于 LRU 淘汰；
 * - 写入新文件后如果总大小超过 [CachePreferences] 设置的上限，按时间由旧到新删除，
 *   直到总大小低于上限的 90%（留出一点余量，避免反复删写）。
 */
class WebDavCacheManager(
    private val cacheDir: File,
    private val cachePreferences: CachePreferences,
) {

    companion object {
        /** 统一的 WebDAV 缓存目录：各处都从这里取，避免有人再拼出 dav/dav 这种双层 */
        fun davCacheDir(context: android.content.Context): File =
            File(context.cacheDir, "dav").apply { mkdirs() }
    }

    /**
     * WebDAV 容器缓存目录。
     *
     * 这里直接用调用方传进来的 [cacheDir]，**不再追加一层 "dav"** ——
     * 外面传进来的已经是 `cacheDir/dav`，再拼一层会变成 `dav/dav`，
     * 结果是设置页统计到 0MB、点「清除缓存」也没反应。
     */
    private val davCacheDir: File get() = cacheDir.apply { mkdirs() }

    /** 当前缓存总大小（字节） */
    suspend fun currentSizeBytes(): Long = withContext(Dispatchers.IO) {
        davCacheDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
    }

    /**
     * 返回缓存文件（已存在且有效则直接返回，否则用 [download] 拉取）。
     *
     * @param key 唯一键，建议用 chapterId（含扩展名）
     * @param download 真正发起网络请求并返回输入流的 lambda
     */
    suspend fun obtain(key: String, download: suspend () -> InputStream): File =
        withContext(Dispatchers.IO) {
            val ext = key.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "bin"
            val out = File(davCacheDir, "${key.hashCode()}.$ext")

            if (out.exists() && out.length() > 0) {
                // 命中缓存：更新时间戳，LRU 不会把它当旧的删掉
                out.setLastModified(System.currentTimeMillis())
                return@withContext out
            }

            out.parentFile?.mkdirs()
            val tmp = File(out.absolutePath + ".part")
            download().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }

            if (!tmp.renameTo(out)) {
                tmp.delete()
                error("WebDAV 缓存写入失败: $key")
            }
            out.setLastModified(System.currentTimeMillis())

            trimCache()
            out
        }

    /** 清空缓存 */
    suspend fun clearCache(): Unit = withContext(Dispatchers.IO) {
        davCacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /**
     * 按 LRU 淘汰缓存。
     *
     * 策略：总大小超过上限时，按最后修改时间由旧到新删，直到低于上限的 90%。
     * 保留 .part 临时文件不参与计算与删除。
     */
    private suspend fun trimCache() = withContext(Dispatchers.IO) {
        val maxBytes = (cachePreferences.settings.first().maxSizeMb * 1_000_000L).coerceAtLeast(1)
        val files = davCacheDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.lastModified() }

        var total = files.sumOf { it.length() }
        val target = (maxBytes * 0.9).toLong()
        if (total <= maxBytes) return@withContext

        for (f in files) {
            if (total <= target) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
