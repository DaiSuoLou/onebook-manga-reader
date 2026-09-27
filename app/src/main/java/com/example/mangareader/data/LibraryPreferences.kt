package com.example.mangareader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(name = "library_roots")

/**
 * 用户通过 SAF（ACTION_OPEN_DOCUMENT_TREE）授权进来的漫画目录。
 *
 * 只存 uri 字符串，读取时按需派生 [Root.sourceId]。之所以不额外存一个自增 id：
 * 自增 id 会在「删掉中间某个目录」之后发生漂移，导致旧的阅读进度串到别的作品上；
 * 而 uri 到 sourceId 的映射是纯函数，重启 App、增删目录都不会变。
 *
 * 每条记录存成 `uri|label`：key 用 stringSet 免得自己维护顺序，
 * label 只是给人看的目录名（比如「漫画」），取第一个 `|` 切分即可，label 里再出现 `|` 也无所谓。
 */
class LibraryPreferences(private val context: Context) {

    data class Root(
        /** SAF 树 uri，形如 content://com.android.externalstorage.documents/tree/primary%3AManga */
        val uri: String,
        /** 展示名，取授权目录的最后一段 */
        val label: String,
    ) {
        val sourceId: String get() = sourceIdOf(uri)
    }

    /** 已授权目录。App 重启后依然可读，不需要用户重新授权。 */
    val roots: Flow<List<Root>> = context.libraryDataStore.data.map { prefs ->
        (prefs[KEY_ROOTS] ?: emptySet()).mapNotNull(::parseRoot)
    }

    suspend fun addRoot(uri: String, label: String) {
        context.libraryDataStore.edit { prefs ->
            val set = prefs[KEY_ROOTS].orEmpty().toMutableSet()
            set.add(encodeRoot(Root(uri, label)))
            prefs[KEY_ROOTS] = set
        }
    }

    suspend fun removeRoot(uri: String) {
        context.libraryDataStore.edit { prefs ->
            val set = prefs[KEY_ROOTS].orEmpty().toMutableSet()
            set.removeAll(set.filter { it.substringBefore('|') == uri })
            prefs[KEY_ROOTS] = set
        }
    }

    companion object {
        /** 本地私有目录源的固定 id，与 SAF 源前缀区分开 */
        const val LOCAL_SOURCE_ID = "local"

        /**
         * SAF 树 uri -> 稳定短 id（如 `saf:3f9a1c07`）。
         *
         * 取 SHA-256 的前 4 字节转 hex：8 位十六进制约 42 亿种，同一个设备上不会撞；
         * 关键是它只由 uri 决定，删掉别的目录不会让它的编号发生漂移，
         * 阅读进度就能一直挂在同一个 key 上。
         */
        fun sourceIdOf(treeUri: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(treeUri.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val hex = digest.copyOfRange(0, 4).joinToString("") { b -> b.toInt().and(0xff).toString(16).padStart(2, '0') }
            return "saf:$hex"
        }

        /** 源 id + 源内相对路径 = 全局唯一的 mangaId / chapterId */
        fun itemIdOf(sourceId: String, relativePath: String): String =
            "$sourceId/${relativePath.trim('/')}"

        fun encodeRoot(r: Root) = "${r.uri}|${r.label}"

        private val KEY_ROOTS = stringSetPreferencesKey("saf_roots")

        fun parseRoot(entry: String): Root? {
            val idx = entry.indexOf('|')
            if (idx <= 0) return null
            return Root(uri = entry.substring(0, idx), label = entry.substring(idx + 1))
        }
    }
}
