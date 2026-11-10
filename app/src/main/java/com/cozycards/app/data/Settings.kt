package com.cozycards.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class EffectsQuality(val label: String) { AUTO("Auto"), LOW("Low"), MEDIUM("Medium"), HIGH("High") }

data class AppSettings(
    val musicVolume: Float = 0.6f,
    val sfxVolume: Float = 0.8f,
    val muted: Boolean = false,
    val quality: EffectsQuality = EffectsQuality.AUTO,
    val gyroscope: Boolean = true,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Player preferences in DataStore (kept separate from game progress in Room). */
class SettingsRepository(private val context: Context) {
    private object K {
        val music = floatPreferencesKey("music")
        val sfx = floatPreferencesKey("sfx")
        val muted = booleanPreferencesKey("muted")
        val quality = stringPreferencesKey("quality")
        val gyro = booleanPreferencesKey("gyro")
        val haptics = booleanPreferencesKey("haptics")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            musicVolume = p[K.music] ?: 0.6f,
            sfxVolume = p[K.sfx] ?: 0.8f,
            muted = p[K.muted] ?: false,
            quality = p[K.quality]?.let { runCatching { EffectsQuality.valueOf(it) }.getOrNull() } ?: EffectsQuality.AUTO,
            gyroscope = p[K.gyro] ?: true,
            haptics = p[K.haptics] ?: true,
            reducedMotion = p[K.reducedMotion] ?: false,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(
                musicVolume = p[K.music] ?: 0.6f,
                sfxVolume = p[K.sfx] ?: 0.8f,
                muted = p[K.muted] ?: false,
                quality = p[K.quality]?.let { runCatching { EffectsQuality.valueOf(it) }.getOrNull() } ?: EffectsQuality.AUTO,
                gyroscope = p[K.gyro] ?: true,
                haptics = p[K.haptics] ?: true,
                reducedMotion = p[K.reducedMotion] ?: false,
            )
            val n = transform(cur)
            p[K.music] = n.musicVolume.coerceIn(0f, 1f)
            p[K.sfx] = n.sfxVolume.coerceIn(0f, 1f)
            p[K.muted] = n.muted
            p[K.quality] = n.quality.name
            p[K.gyro] = n.gyroscope
            p[K.haptics] = n.haptics
            p[K.reducedMotion] = n.reducedMotion
        }
    }
}
