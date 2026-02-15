package com.handyvoice.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "handy_prefs")

class PreferencesManager(private val context: Context) {
    
    companion object {
        private val API_KEY = stringPreferencesKey("api_key")
        private val CONNECTION_KEY = stringPreferencesKey("connection_key")
        private val DEEPGRAM_API_KEY = stringPreferencesKey("deepgram_api_key")
        private val VOICE_ENGINE = stringPreferencesKey("voice_engine")
    }
    
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[API_KEY] ?: ""
    }
    
    val connectionKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CONNECTION_KEY] ?: ""
    }
    
    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = key
        }
    }
    
    suspend fun saveConnectionKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[CONNECTION_KEY] = key
        }
    }
    
    suspend fun saveCredentials(apiKey: String, connectionKey: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = apiKey
            prefs[CONNECTION_KEY] = connectionKey
        }
    }
    
    val deepgramApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEEPGRAM_API_KEY] ?: ""
    }
    
    val voiceEngine: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[VOICE_ENGINE] ?: "vosk"
    }
    
    suspend fun saveDeepgramApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[DEEPGRAM_API_KEY] = key
        }
    }
    
    suspend fun saveVoiceEngine(engine: String) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_ENGINE] = engine
        }
    }
}
