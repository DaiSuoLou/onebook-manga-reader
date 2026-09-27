package com.example.mangareader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "cache_prefs")

/**
 * 缓存偏好：WebDAV 等远程文件的最大缓存容量。
 */
class CachePreferences(private val context: Context) {

    data class Settings(
        /** 最大缓存容量，单位 MB；默认 500MB */
        val maxSizeMb: Int = 500,
    )

    val settings: Flow<Settings> = context.cacheDataStore.data.map { p ->
        Settings(
            maxSizeMb = p[KEY_MAX_SIZE_MB] ?: 500,
        )
    }

    suspend fun setMaxSizeMb(value: Int) {
        context.cacheDataStore.edit { it[KEY_MAX_SIZE_MB] = value.coerceIn(50, 4096) }
    }

    private companion object {
        val KEY_MAX_SIZE_MB = intPreferencesKey("max_size_mb")
    }
}
