package com.yourpackage.cleanforge.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cleanforge.prefs")

class Preferences(private val context: Context) {
    private object Keys {
        val Verses = booleanPreferencesKey("verses")
        val Dark = booleanPreferencesKey("dark")
    }

    /** Defaults: verses ON, dark OFF — matches web app. */
    val verses: Flow<Boolean> = context.dataStore.data.map { it[Keys.Verses] ?: true }
    val dark: Flow<Boolean> = context.dataStore.data.map { it[Keys.Dark] ?: false }

    suspend fun setVerses(enabled: Boolean) = context.dataStore.edit { it[Keys.Verses] = enabled }
    suspend fun setDark(enabled: Boolean) = context.dataStore.edit { it[Keys.Dark] = enabled }
}
