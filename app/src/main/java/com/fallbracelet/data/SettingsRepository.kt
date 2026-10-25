package com.fallbracelet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Phone-side settings. The bracelet's own settings (name, countdown, check-in) live on the bracelet. */
data class AppSettings(
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** Cached so alerts can name the wearer while the bracelet is out of range. */
    val wearerName: String = "",
    val caregivers: List<String> = emptyList(),
    val smsEnabled: Boolean = false,
    val smsCheckins: Boolean = false,
    /** Warn on this phone when the bracelet has been out of range this long; 0 = never. */
    val offlineAlertMin: Int = 30,
    val lastSeenAt: Long = 0,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val wearer = stringPreferencesKey("wearer_name")
        val caregivers = stringPreferencesKey("caregivers")
        val sms = booleanPreferencesKey("sms_enabled")
        val smsCheckins = booleanPreferencesKey("sms_checkins")
        val offline = intPreferencesKey("offline_alert_min")
        val lastSeen = longPreferencesKey("last_seen_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            deviceAddress = p[Keys.address],
            deviceName = p[Keys.name],
            wearerName = p[Keys.wearer] ?: "",
            caregivers = p[Keys.caregivers].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            smsEnabled = p[Keys.sms] ?: false,
            smsCheckins = p[Keys.smsCheckins] ?: false,
            offlineAlertMin = p[Keys.offline] ?: 30,
            lastSeenAt = p[Keys.lastSeen] ?: 0,
        )
    }

    suspend fun setDevice(address: String?, name: String?) = edit {
        if (address == null) { it.remove(Keys.address); it.remove(Keys.name) }
        else { it[Keys.address] = address; it[Keys.name] = name ?: address }
    }

    suspend fun setWearerName(name: String) = edit { it[Keys.wearer] = name }
    suspend fun setCaregivers(numbers: List<String>) = edit { it[Keys.caregivers] = numbers.joinToString("\n") }
    suspend fun setSmsEnabled(on: Boolean) = edit { it[Keys.sms] = on }
    suspend fun setSmsCheckins(on: Boolean) = edit { it[Keys.smsCheckins] = on }
    suspend fun setOfflineAlertMin(min: Int) = edit { it[Keys.offline] = min }
    suspend fun setLastSeen(at: Long) = edit { it[Keys.lastSeen] = at }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
