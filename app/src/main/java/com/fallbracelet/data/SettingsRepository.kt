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

/** Which side of the product this phone is. */
enum class AppMode { WEARER, CAREGIVER }

/** Phone-side settings. The bracelet's own settings (name, countdown, check-in) live on the bracelet. */
data class AppSettings(
    /** Null until the first-launch choice is made. */
    val mode: AppMode? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** Cached so alerts can name the wearer while the bracelet is out of range. */
    val wearerName: String = "",
    /** Warn when the bracelet has been out of range this long; 0 = never. */
    val offlineAlertMin: Int = 30,
    val lastSeenAt: Long = 0,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val mode = stringPreferencesKey("app_mode")
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val wearer = stringPreferencesKey("wearer_name")
        val offline = intPreferencesKey("offline_alert_min")
        val lastSeen = longPreferencesKey("last_seen_at")

        // Left behind by the SMS build; removed on the next write.
        val oldCaregivers = stringPreferencesKey("caregivers")
        val oldSms = booleanPreferencesKey("sms_enabled")
        val oldSmsCheckins = booleanPreferencesKey("sms_checkins")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val address = p[Keys.address]
        AppSettings(
            // A phone paired before the mode choice existed is the wearer's phone.
            mode = p[Keys.mode]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
                ?: if (address != null) AppMode.WEARER else null,
            deviceAddress = address,
            deviceName = p[Keys.name],
            wearerName = p[Keys.wearer] ?: "",
            offlineAlertMin = p[Keys.offline] ?: 30,
            lastSeenAt = p[Keys.lastSeen] ?: 0,
        )
    }

    suspend fun setMode(mode: AppMode) = edit { it[Keys.mode] = mode.name }

    suspend fun setDevice(address: String?, name: String?) = edit {
        if (address == null) { it.remove(Keys.address); it.remove(Keys.name) }
        else { it[Keys.address] = address; it[Keys.name] = name ?: address }
    }

    suspend fun setWearerName(name: String) = edit { it[Keys.wearer] = name }
    suspend fun setOfflineAlertMin(min: Int) = edit { it[Keys.offline] = min }
    suspend fun setLastSeen(at: Long) = edit { it[Keys.lastSeen] = at }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit {
            it.remove(Keys.oldCaregivers)
            it.remove(Keys.oldSms)
            it.remove(Keys.oldSmsCheckins)
            block(it)
        }
    }
}
