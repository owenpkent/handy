package com.handyvoice.v2.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "handy_v2_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_CONNECTION_KEY = stringPreferencesKey("connection_key")
        private val KEY_LISTENING_MODE = stringPreferencesKey("listening_mode")
        private val KEY_GLOBAL_MAX_SPEED = intPreferencesKey("global_max_speed")
        private val KEY_MATCH_THRESHOLD = floatPreferencesKey("match_threshold")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_GLOBAL_MIN_STROKE = intPreferencesKey("global_min_stroke")
        private val KEY_GLOBAL_MAX_STROKE = intPreferencesKey("global_max_stroke")
        private val KEY_SPEED_LIMIT = intPreferencesKey("speed_limit")

        const val MODE_BUTTON = "button"
        const val MODE_ALWAYS_ON = "always_on"

        const val DEFAULT_GLOBAL_MAX_SPEED = 30          // Owen's preference.
        const val DEFAULT_THRESHOLD: Float = 30f          // path-normalized DTW units.
        const val DEFAULT_GLOBAL_MIN_STROKE = 0          // stroke-position floor (presets squashed into [min,max]).
        const val DEFAULT_GLOBAL_MAX_STROKE = 100        // stroke-position ceiling.
        const val DEFAULT_SPEED_LIMIT = 100              // hard cap on velocity sent to device (applied after globalMaxSpeed scaling).
    }

    val connectionKey: Flow<String> = context.dataStore.data
        .map { it[KEY_CONNECTION_KEY] ?: "" }

    val listeningMode: Flow<String> = context.dataStore.data
        .map { it[KEY_LISTENING_MODE] ?: MODE_BUTTON }

    val globalMaxSpeed: Flow<Int> = context.dataStore.data
        .map { it[KEY_GLOBAL_MAX_SPEED] ?: DEFAULT_GLOBAL_MAX_SPEED }

    val matchThreshold: Flow<Float> = context.dataStore.data
        .map { it[KEY_MATCH_THRESHOLD] ?: DEFAULT_THRESHOLD }

    val onboarded: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ONBOARDED] ?: false }

    val globalMinStroke: Flow<Int> = context.dataStore.data
        .map { it[KEY_GLOBAL_MIN_STROKE] ?: DEFAULT_GLOBAL_MIN_STROKE }

    val globalMaxStroke: Flow<Int> = context.dataStore.data
        .map { it[KEY_GLOBAL_MAX_STROKE] ?: DEFAULT_GLOBAL_MAX_STROKE }

    val speedLimit: Flow<Int> = context.dataStore.data
        .map { it[KEY_SPEED_LIMIT] ?: DEFAULT_SPEED_LIMIT }

    suspend fun setConnectionKey(value: String) = edit { it[KEY_CONNECTION_KEY] = value }
    suspend fun setListeningMode(value: String) = edit { it[KEY_LISTENING_MODE] = value }
    suspend fun setGlobalMaxSpeed(value: Int) = edit { it[KEY_GLOBAL_MAX_SPEED] = value.coerceIn(1, 100) }
    suspend fun setMatchThreshold(value: Float) = edit { it[KEY_MATCH_THRESHOLD] = value }
    suspend fun setOnboarded(value: Boolean) = edit { it[KEY_ONBOARDED] = value }
    suspend fun setGlobalMinStroke(value: Int) = edit { it[KEY_GLOBAL_MIN_STROKE] = value.coerceIn(0, 100) }
    suspend fun setGlobalMaxStroke(value: Int) = edit { it[KEY_GLOBAL_MAX_STROKE] = value.coerceIn(0, 100) }
    suspend fun setSpeedLimit(value: Int) = edit { it[KEY_SPEED_LIMIT] = value.coerceIn(1, 100) }

    suspend fun snapshot(): Snapshot = context.dataStore.data.first().let {
        Snapshot(
            connectionKey = it[KEY_CONNECTION_KEY] ?: "",
            listeningMode = it[KEY_LISTENING_MODE] ?: MODE_BUTTON,
            globalMaxSpeed = it[KEY_GLOBAL_MAX_SPEED] ?: DEFAULT_GLOBAL_MAX_SPEED,
            matchThreshold = it[KEY_MATCH_THRESHOLD] ?: DEFAULT_THRESHOLD,
            onboarded = it[KEY_ONBOARDED] ?: false,
            globalMinStroke = it[KEY_GLOBAL_MIN_STROKE] ?: DEFAULT_GLOBAL_MIN_STROKE,
            globalMaxStroke = it[KEY_GLOBAL_MAX_STROKE] ?: DEFAULT_GLOBAL_MAX_STROKE,
            speedLimit = it[KEY_SPEED_LIMIT] ?: DEFAULT_SPEED_LIMIT,
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    data class Snapshot(
        val connectionKey: String,
        val listeningMode: String,
        val globalMaxSpeed: Int,
        val matchThreshold: Float,
        val onboarded: Boolean,
        val globalMinStroke: Int,
        val globalMaxStroke: Int,
        val speedLimit: Int,
    )
}
