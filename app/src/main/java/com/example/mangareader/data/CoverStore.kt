package com.example.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.mangareader.data.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用户手动指定的套书封面（seriesId -> 本地封面文件）。
 *
 * 优先级高于目录扫描自动识别的封面（cover.jpg / 第一张图）：
 * 自动识别的结果会随目录内容变化，手动指定的不会。
 *
 * 封面文件统一放在 `cacheDir/covers/` 下，文件名由 seriesId 的哈希决定；
 * 书架的 [com.example.mangareader.ui.bookshelf.model.MangaSeriesUiModel.coverUrl]
 * 直接吃这个绝对路径，Coil 走本地文件加载，不再产生网络请求。
 */
class CoverStore(private val context: Context) {

    companion object {
        private const val TAG = "CoverStore"

        /** DataStore 里的 key 形如 `cov_<seriesId>`：用户手动指定的封面 */
        private const val KEY_PREFIX = "cov_"

        /**
         * key 形如 `acov_<seriesId>`：从漫画文件内部抽出来的封面。
         *
         * 故意跟手动封面分开存：手动那张是用户自己挑的，抽屉里「移除封面」要能单独撤掉它；
         * 自动提取的是从文件里读来的，撤掉也没别的选择，让它跟着下次扫描一起重画就行。
         */
        private const val AUTO_KEY_PREFIX = "acov_"

        /** key 形如 `vcov_<seriesId>|<volumeId>`：抽屉里每一卷的自动封面 */
        private const val VOLUME_KEY_PREFIX = "vcov_"

        /**
         * 封面落盘子目录（放在 filesDir 下，避免被系统按 cache 清理掉）。
         *
         * 之前放在 cacheDir 里，某些 ROM 在存储紧张时会自动清 cache，
         * 导致每次打开书架封面都重新抽一遍。
         */
        fun coverDir(context: Context): File =
            File(context.filesDir, "covers").apply { mkdirs() }
    }

    private val Context.coverDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "series_covers",
    )

    private val store: DataStore<Preferences> get() = context.coverDataStore

    /** 当前所有手动封面：seriesId -> 本地文件绝对路径。文件被删掉的条目自动忽略。 */
    val covers: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(KEY_PREFIX)) return@mapNotNull null
            val path = value as? String ?: return@mapNotNull null
            if (File(path).exists()) key.name.removePrefix(KEY_PREFIX) to path else null
        }.toMap()
    }

    /** 自动提取封面：seriesId -> 本地文件绝对路径，语义同 [covers] */
    val autoCovers: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(AUTO_KEY_PREFIX)) return@mapNotNull null
            val path = value as? String ?: return@mapNotNull null
            if (File(path).exists()) key.name.removePrefix(AUTO_KEY_PREFIX) to path else null
        }.toMap().also {
            Diag.d(context, TAG, "autoCovers 发出 ${it.size} 张：${it.keys}")
        }
    }

    /** 该作品的封面落盘路径 */
    fun destination(seriesId: String): File =
        File(coverDir(context), "${seriesId.hashCode()}.jpg")

    /** 记下这张封面（会先复制一份到标准位置，避免外部文件被移动/删除后封面失效） */
    suspend fun setFile(seriesId: String, file: File) = withContext(Dispatchers.IO) {
        val dest = destination(seriesId)
        if (file != dest) {
            file.parentFile?.mkdirs()
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
        store.edit { prefs ->
            prefs[stringPreferencesKey(KEY_PREFIX + seriesId)] = dest.absolutePath
        }
    }

    /** 从 content uri（相册 / 文件选择器）复制一张图作为封面 */
    suspend fun setFromUri(seriesId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val dest = destination(seriesId)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("读取图片失败")
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            error("写入封面失败")
        }
        setFile(seriesId, dest)
    }

    /** 记下从漫画文件里抽出来的封面 */
    suspend fun setAuto(seriesId: String, file: File) = withContext(Dispatchers.IO) {
        val dest = destination(seriesId)
        if (file != dest) {
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
        Diag.i(context, TAG, "setAuto 写入中：$seriesId -> ${dest.absolutePath} exists=${dest.exists()}")
        store.edit { prefs ->
            prefs[stringPreferencesKey(AUTO_KEY_PREFIX + seriesId)] = dest.absolutePath
        }
        Diag.i(context, TAG, "setAuto 写入完成：$seriesId")
    }

    /** 丢掉某本书的自动封面（文件留着不删，下次还得用它） */
    suspend fun clearAuto(seriesId: String) = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey(AUTO_KEY_PREFIX + seriesId)
        store.edit { prefs -> prefs.remove(key) }
    }

    /** 取消手动封面，退回自动识别的结果 */
    suspend fun clear(seriesId: String) = withContext(Dispatchers.IO) {
        val previous = runCatching { store.data.first()[stringPreferencesKey(KEY_PREFIX + seriesId)] }
            .getOrNull()
        store.edit { prefs -> prefs.remove(stringPreferencesKey(KEY_PREFIX + seriesId)) }
        previous?.let { File(it) }?.takeIf { it.exists() }?.delete()
    }

    // ---------------------------------------------------------------------------
    // 分卷封面（抽屉里每一卷单独显示第一页）
    // ---------------------------------------------------------------------------

    /** 分卷封面的落盘路径：按 seriesId + volumeId 哈希，避免特殊字符当文件名 */
    fun volumeDestination(seriesId: String, volumeId: String): File =
        File(coverDir(context), "v/${seriesId.hashCode()}/${volumeId.hashCode()}.jpg")

    /**
     * 读取某套书下所有已缓存的分卷封面。
     *
     * @return volumeId -> 本地绝对路径
     */
    suspend fun volumeAutoCovers(seriesId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val prefix = VOLUME_KEY_PREFIX + seriesId + "|"
        store.data.first().let { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                if (!key.name.startsWith(prefix)) return@mapNotNull null
                val path = value as? String ?: return@mapNotNull null
                val volumeId = key.name.removePrefix(prefix)
                if (File(path).exists()) volumeId to path else null
            }.toMap()
        }
    }

    /** 记下从某卷里抽出来的封面 */
    suspend fun setVolumeAuto(seriesId: String, volumeId: String, file: File) = withContext(Dispatchers.IO) {
        val dest = volumeDestination(seriesId, volumeId)
        if (file != dest) {
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
        store.edit { prefs ->
            prefs[stringPreferencesKey(VOLUME_KEY_PREFIX + seriesId + "|" + volumeId)] = dest.absolutePath
        }
    }
}
