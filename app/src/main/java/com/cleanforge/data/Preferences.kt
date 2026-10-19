package com.cleanforge.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cleanforge.prefs")

/** System = follow the phone (the default). Light and Dark are explicit choices made in About. */
enum class ThemeMode {
    System, Light, Dark;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

class Preferences(private val context: Context) {
    private object Keys {
        val Verses = booleanPreferencesKey("verses")
        val Theme = intPreferencesKey("theme_mode")
    }

    /** Defaults: verses ON, theme follows the system. */
    val verses: Flow<Boolean> = context.dataStore.data.map { it[Keys.Verses] ?: true }
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.entries.getOrElse(it[Keys.Theme] ?: 0) { ThemeMode.System }
    }

    suspend fun setVerses(enabled: Boolean) {
        context.dataStore.edit { it[Keys.Verses] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.Theme] = mode.ordinal }
    }
}
