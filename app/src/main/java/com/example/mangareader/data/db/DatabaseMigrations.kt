package com.example.mangareader.data.db

import androidx.room.migration.Migration

/**
 * 数据库迁移登记处 —— 所有 schema 迁移必须在这里登记，别处不允许出现裸 Migration。
 *
 * **约定（必须遵守，否则老用户升级 App 会直接崩）**：
 * 1. `MangaReaderDatabase.version` 每加 1，这里必须补一个 `Migration(旧版本, 新版本)` 并加进 [ALL]。
 * 2. `exportSchema = true` 会把每个版本的 schema JSON 落在 `app/schemas/`，
 *    写迁移前先 diff 新旧两份 JSON 确认真实差异，不要凭 Entity 代码猜。
 * 3. 加列：`ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...` 一步搞定；
 *    改列类型 / 改约束这类破坏性变更才需要走「建新表 -> INSERT SELECT 拷贝 -> DROP 旧表 -> ALTER TABLE RENAME」四步式。
 * 4. 写完自测一次升级：把老版本 APK 的数据目录搬到新包下，或用 Room 的 `MigrationTestHelper`（以后有测试基建再上）。
 *
 * 迁移模板（假设 v1 -> v2 给进度表加 `lastReadAt` 列）：
 *
 * ```
 * private val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL(
 *             "ALTER TABLE reading_progress ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0"
 *         )
 *     }
 * }
 * ```
 *
 * 然后把它加进 [ALL]，并把 `MangaReaderDatabase.version` 提到 2。
 *
 * 当前状态：schema 仍为 version 1，没有历史版本，登记处先立起来。
 * 注意 `MangaApplication` 里配了 `fallbackToDestructiveMigrationOnDowngrade()`——
 * **升级**路径没写迁移会故意快速崩溃（开发期就暴露，而不是静默丢用户进度），
 * 只有**降级**（装回旧 APK）才允许破坏性重建。
 */
object DatabaseMigrations {

    /** 交给 Room 的全部迁移；Room 会自动挑出从当前版本到目标版本所需的那几步 */
    val ALL: Array<Migration> = arrayOf(
        // 目前 version = 1。第一次改表时在这里补 MIGRATION_1_2。
    )
}
