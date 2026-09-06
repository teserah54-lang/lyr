/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyreon.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lyreonDataStore by preferencesDataStore(name = "lyreon_settings")

enum class AudioQuality(val label: String) {
    HIGH("TERBAIK"),
    BALANCED("SEIMBANG"),
    DATA_SAVER("HEMAT DATA"),
}

data class LyreonSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val reduceMotion: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.BALANCED,
    val autoplayRelated: Boolean = true,
    /** Nama yang disapa di Home (kosong = default "Farizy"). */
    val displayName: String = "",
    /** Warna aksen ARGB kustom (-1 = crimson bawaan). */
    val accentArgb: Int = -1,
    /** Kunci jenis huruf: default / serif / mono / cursive. */
    val fontKey: String = "default",
    /** Material You: ambil palet dari wallpaper (Android 12+). */
    val dynamicColor: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val AUTOPLAY_RELATED = booleanPreferencesKey("autoplay_related")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ACCENT_ARGB = intPreferencesKey("accent_argb")
        val FONT_KEY = stringPreferencesKey("font_key")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<LyreonSettings> = context.lyreonDataStore.data.map { prefs ->
        LyreonSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            audioQuality = prefs[Keys.AUDIO_QUALITY]?.let {
                runCatching { AudioQuality.valueOf(it) }.getOrNull()
            } ?: AudioQuality.BALANCED,
            autoplayRelated = prefs[Keys.AUTOPLAY_RELATED] ?: true,
            displayName = prefs[Keys.DISPLAY_NAME].orEmpty(),
            accentArgb = prefs[Keys.ACCENT_ARGB] ?: -1,
            fontKey = prefs[Keys.FONT_KEY] ?: "default",
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.lyreonDataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setReduceMotion(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.REDUCE_MOTION] = value }
    }

    suspend fun setAudioQuality(value: AudioQuality) {
        context.lyreonDataStore.edit { it[Keys.AUDIO_QUALITY] = value.name }
    }

    suspend fun setAutoplayRelated(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.AUTOPLAY_RELATED] = value }
    }

    suspend fun setDisplayName(value: String) {
        context.lyreonDataStore.edit { it[Keys.DISPLAY_NAME] = value.trim().take(24) }
    }

    suspend fun setAccentArgb(value: Int) {
        context.lyreonDataStore.edit { it[Keys.ACCENT_ARGB] = value }
    }

    suspend fun setFontKey(value: String) {
        context.lyreonDataStore.edit { it[Keys.FONT_KEY] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.DYNAMIC_COLOR] = value }
    }
}
