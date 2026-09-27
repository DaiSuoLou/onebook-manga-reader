package com.example.mangareader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readingDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_prefs")

/** 阅读偏好：翻页方向、双页裁切、屏幕常亮、缩放模式、亮度、方向锁 */
class ReadingPreferences(private val context: Context) {

    enum class Direction { LTR, RTL, VERTICAL }

    /** 阅读时的方向锁；AUTO 表示跟随系统旋转 */
    enum class ScreenOrientation { AUTO, PORTRAIT, LANDSCAPE }

    /**
     * 音量键翻页模式。默认「音量−=下一页」：右手握机拇指最容易按到的是音量−，
     * 翻下一页是最高频操作，放它身上最顺手。
     */
    enum class VolumeKeyMode { OFF, DOWN_IS_NEXT, UP_IS_NEXT }

    /**
     * 左右两侧点按区域的方向。
     * FOLLOW_DIRECTION 跟随阅读方向：从左向右读时右侧=下一页；日漫（RTL）则反过来。
     */
    enum class TapZoneMode { FOLLOW_DIRECTION, LEFT_IS_NEXT, RIGHT_IS_NEXT }

    data class Settings(
        val direction: Direction = Direction.LTR,
        val doublePage: Boolean = false,
        val keepScreenOn: Boolean = true,
        val cropBorders: Boolean = false,
        val scaleToFit: Boolean = true,
        /** 屏幕亮度；-1 = 跟随系统，0.01~1.0 = 自定义（阅读器内写 window.screenBrightness） */
        val brightness: Float = -1f,
        val orientation: ScreenOrientation = ScreenOrientation.AUTO,
        val volumeKeyMode: VolumeKeyMode = VolumeKeyMode.DOWN_IS_NEXT,
        val tapZoneMode: TapZoneMode = TapZoneMode.FOLLOW_DIRECTION,
    )

    val settings: Flow<Settings> = context.readingDataStore.data.map { p ->
        Settings(
            direction = Direction.entries.getOrElse(p[KEY_DIRECTION] ?: 0) { Direction.LTR },
            doublePage = p[KEY_DOUBLE_PAGE] ?: false,
            keepScreenOn = p[KEY_KEEP_SCREEN_ON] ?: true,
            cropBorders = p[KEY_CROP_BORDERS] ?: false,
            scaleToFit = p[KEY_SCALE_TO_FIT] ?: true,
            brightness = p[KEY_BRIGHTNESS] ?: -1f,
            orientation = ScreenOrientation.entries.getOrElse(p[KEY_ORIENTATION] ?: 0) { ScreenOrientation.AUTO },
            volumeKeyMode = VolumeKeyMode.entries.getOrElse(p[KEY_VOLUME_KEY_MODE] ?: 0) { VolumeKeyMode.DOWN_IS_NEXT },
            tapZoneMode = TapZoneMode.entries.getOrElse(p[KEY_TAP_ZONE_MODE] ?: 0) { TapZoneMode.FOLLOW_DIRECTION },
        )
    }

    suspend fun setDirection(d: Direction) {
        context.readingDataStore.edit { it[KEY_DIRECTION] = d.ordinal }
    }

    suspend fun setDoublePage(enabled: Boolean) {
        context.readingDataStore.edit { it[KEY_DOUBLE_PAGE] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.readingDataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setCropBorders(enabled: Boolean) {
        context.readingDataStore.edit { it[KEY_CROP_BORDERS] = enabled }
    }

    suspend fun setScaleToFit(enabled: Boolean) {
        context.readingDataStore.edit { it[KEY_SCALE_TO_FIT] = enabled }
    }

    suspend fun setBrightness(value: Float) {
        context.readingDataStore.edit { it[KEY_BRIGHTNESS] = value }
    }

    suspend fun setOrientation(o: ScreenOrientation) {
        context.readingDataStore.edit { it[KEY_ORIENTATION] = o.ordinal }
    }

    suspend fun setVolumeKeyMode(mode: VolumeKeyMode) {
        context.readingDataStore.edit { it[KEY_VOLUME_KEY_MODE] = mode.ordinal }
    }

    suspend fun setTapZoneMode(mode: TapZoneMode) {
        context.readingDataStore.edit { it[KEY_TAP_ZONE_MODE] = mode.ordinal }
    }

    private companion object {
        val KEY_DIRECTION = intPreferencesKey("direction")
        val KEY_DOUBLE_PAGE = booleanPreferencesKey("double_page")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_CROP_BORDERS = booleanPreferencesKey("crop_borders")
        val KEY_SCALE_TO_FIT = booleanPreferencesKey("scale_to_fit")
        val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        val KEY_ORIENTATION = intPreferencesKey("orientation")
        val KEY_VOLUME_KEY_MODE = intPreferencesKey("volume_key_mode")
        val KEY_TAP_ZONE_MODE = intPreferencesKey("tap_zone_mode")
    }
}
