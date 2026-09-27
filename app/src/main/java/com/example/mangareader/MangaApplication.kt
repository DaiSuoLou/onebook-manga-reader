package com.example.mangareader

import android.app.Application
import androidx.room.Room
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.example.mangareader.core.coil.addMangaPageSupport
import com.example.mangareader.data.CrashReporter
import com.example.mangareader.data.ReadingPreferences
import com.example.mangareader.data.ReadingProgressRepository
import com.example.mangareader.data.db.DatabaseMigrations
import com.example.mangareader.data.db.MangaReaderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 全局装配：Coil + 数据库 + 进度仓库。
 *
 * 没有引入 DI（Hilt/Koin），所以这里充当最简单的依赖持有者，
 * ViewModel 通过 `getApplication<Application>() as MangaApplication` 取用。
 * 等依赖变多了再上 DI 也不迟。
 */
class MangaApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: MangaReaderDatabase
        private set

    lateinit var progressRepository: ReadingProgressRepository
        private set

    lateinit var readingPreferences: ReadingPreferences
        private set

    override fun onCreate() {
        super.onCreate()

        // 必须最先装：晚一步就会漏掉 Application 构造期间与首屏的异常
        CrashReporter.install(this)

        readingPreferences = ReadingPreferences(this)

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                // 注册 MangaPageModel -> MangaPageFetcher
                .addMangaPageSupport()
                // 漫画单页解码后体积大，默认 25% 堆偏保守，给到 30%
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.30)
                        .build()
                }
                .build(),
        )

        database = Room.databaseBuilder(this, MangaReaderDatabase::class.java, DATABASE_NAME)
            // 所有迁移登记在 DatabaseMigrations.ALL，改表必须去那里补 Migration
            .addMigrations(*DatabaseMigrations.ALL)
            // 降级（装回旧版本 APK）才允许破坏性重建；升级没写迁移会故意快速崩溃，
            // 开发期就暴露漏写的迁移，而不是静默丢掉用户的阅读进度
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

        progressRepository = ReadingProgressRepository(
            dao = database.readingProgressDao(),
            scope = applicationScope,
        )
    }

    private companion object {
        const val DATABASE_NAME = "mangareader.db"
    }
}
