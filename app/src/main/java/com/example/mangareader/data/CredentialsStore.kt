package com.example.mangareader.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * WebDAV 账号存储：使用 AES256-GCM 主密钥加密的 SharedPreferences。
 * 明文密码不落盘，读取走 KeyStore 派生的密钥。
 *
 * 一个 [DavAccount] 就是一个源：书架上的作品 id 形如 `dav:d3f9a1c07/作品/第01话`，
 * 前缀里的 `dav:xxxxxxxx` 就是 [DavAccount.sourceId]，用来把作品、进度、封面
 * 和「该用哪套账号打开」对上号。以前只有一套凭证、前缀写死成 `dav`，
 * 想加第二个 WebDAV 地址时新书会和旧书抢同一个 id。
 *
 * **旧数据兼容**：3.x 之前只存了单条 url/username/password，对应的作品 id 是 `dav/作品`。
 * 首次读取时把它迁成一个 sourceId 固定为 [LEGACY_SOURCE_ID]（`dav`）的账号，
 * 这样老 id 一个都不用变，进度和封面都不会丢。
 */
class CredentialsStore(private val context: Context) {

    /**
     * MasterKey / EncryptedSharedPreferences 的创建会访问 Android Keystore，
     * 在部分 ROM 上首次初始化耗时数秒且可能阻塞主线程，导致开屏卡死。
     * 这里全部 lazy，只在第一次 [load] / [save] 访问时才创建，并且这些访问都走 IO dispatcher。
     */
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 一个 WebDAV 源 */
    data class DavAccount(
        /** 源 id：`dav` 表示老数据，其余形如 `dav:d3f9a1c07` */
        val sourceId: String,
        /** 用户自定义名称（书架上显示的分类名） */
        val name: String,
        val url: String,
        val username: String,
        val password: String,
    )

    /**
     * 已有账号。
     *
     * **必须先调它再读其它接口**：内部会在首次调用时把老的单条凭证迁成账号，
     * 不经过这一步，老用户保存过的内容会看起来「凭空消失」。
     */
    suspend fun accounts(): List<DavAccount> = withContext(Dispatchers.IO) {
        if (prefs.getString(KEY_MIGRATED, null) == null) {
            val legacy = readLegacyRaw(prefs)
            if (legacy != null) {
                writeAccount(
                    DavAccount(
                        sourceId = LEGACY_SOURCE_ID,
                        name = DEFAULT_LEGACY_NAME,
                        url = legacy.first,
                        username = legacy.second,
                        password = legacy.third,
                    ),
                )
            }
            prefs.edit().putString(KEY_MIGRATED, "1").apply()
        }
        readAccounts()
    }

    suspend fun saveAccount(account: DavAccount) = withContext(Dispatchers.IO) {
        writeAccount(account)
    }

    suspend fun removeAccount(sourceId: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(keyOf(sourceId, F_URL))
            .remove(keyOf(sourceId, F_USER))
            .remove(keyOf(sourceId, F_PASS))
            .remove(keyOf(sourceId, F_NAME))
            .apply()
    }

    /** 构建某个源的可直接使用的实例 */
    suspend fun buildSource(account: DavAccount, cacheRoot: File): WebDavMangaSource? {
        if (account.url.isBlank()) return null
        return WebDavMangaSource(
            account.url,
            account.username,
            account.password,
            account.cacheDir(cacheRoot),
            context,
        )
    }

    /**
     * 每个账号一份缓存目录。
     *
     * **不能几个账号共用一份**：缓存文件是按 chapterId 的哈希命名的，
     * 而 chapterId 是源内相对路径——两个 WebDAV 上同名的「/死亡预告/第01卷.mobi」
     * 会写成同一个文件，一边下完另一边直接读到错的。
     */
    fun DavAccount.cacheDir(cacheRoot: File): File =
        File(cacheRoot, tag).apply { mkdirs() }

    /** sourceId -> 可用于目录名的标记（去掉 `:`） */
    private val DavAccount.tag: String get() = sourceId.replace(":", "")

    private fun writeAccount(account: DavAccount) {
        prefs.edit()
            .putString(keyOf(account.sourceId, F_URL), account.url)
            .putString(keyOf(account.sourceId, F_USER), account.username)
            .putString(keyOf(account.sourceId, F_PASS), account.password)
            // 名称必须一起落盘：少这一行的话保存后重读会掉回默认名，
            // 用户在设置里改了「书架上显示的分类名」等于白改。
            .putString(keyOf(account.sourceId, F_NAME), account.name)
            .apply()
    }

    /**
     * 枚举出所有已存的账号。
     *
     * **不能只按 [LEGACY_SOURCE_ID] 读**：那样一旦用户新增了第二个 WebDAV，
     * 再读回来就只剩老的那条，界面上会显示「账号凭空少了一个」。
     * 这里直接把加密库里所有 `acc_<sourceId>_url` 键扫一遍。
     */
    private fun readAccounts(): List<DavAccount> = prefs.all.mapNotNull { (rawKey, value) ->
        val sourceId = sourceIdOfUrlKey(rawKey) ?: return@mapNotNull null
        val url = value as? String ?: return@mapNotNull null
        if (url.isBlank()) return@mapNotNull null
        DavAccount(
            sourceId = sourceId,
            name = prefs.getString(keyOf(sourceId, F_NAME), null)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_LEGACY_NAME,
            url = url,
            username = prefs.getString(keyOf(sourceId, F_USER), "").orEmpty(),
            password = prefs.getString(keyOf(sourceId, F_PASS), "").orEmpty(),
        )
    }

    companion object {
        private const val FILE_NAME = "manga_dav_credentials"
        private const val KEY_MIGRATED = "migrated_to_accounts"

        /** 老数据迁上来之后的名字，用户可在设置里改 */
        private const val DEFAULT_LEGACY_NAME = "WebDAV"

        private const val F_URL = "url"
        private const val F_USER = "username"
        private const val F_PASS = "password"
        private const val F_NAME = "name"

        private fun keyOf(sourceId: String, field: String): String = "acc_${sourceId}_$field"

        /** `acc_<sourceId>_url` -> `<sourceId>`；不是账号键返回 null */
        private fun sourceIdOfUrlKey(rawKey: String?): String? =
            if (rawKey != null && rawKey.startsWith("acc_") && rawKey.endsWith("_url")) {
                rawKey.removeSuffix("_url").removePrefix("acc_")
            } else {
                null
            }?.takeIf { it.isNotBlank() }

        /** 老格式（单条凭证）的读取，仅在迁移阶段使用 */
        private fun readLegacyRaw(prefs: android.content.SharedPreferences): Triple<String, String, String>? {
            val url = prefs.getString(F_URL, null) ?: return null
            return Triple(url, prefs.getString(F_USER, "").orEmpty(), prefs.getString(F_PASS, "").orEmpty())
        }

        /** 老数据专用源 id：作品 id 形如 `dav/作品`，与 [DavAccount.sourceId] 的 `dav:xxx` 区分开 */
        const val LEGACY_SOURCE_ID = "dav"

        /** 新账号的源 id：`dav:` + 8 位十六进制 */
        fun newSourceId(): String {
            val bytes = ByteArray(4)
            SecureRandom().nextBytes(bytes)
            val hex = bytes.joinToString("") { b -> b.toInt().and(0xff).toString(16).padStart(2, '0') }
            return "dav:$hex"
        }
    }
}
