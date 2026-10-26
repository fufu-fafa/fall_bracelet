package com.fallbracelet.data

import android.content.Context
import com.fallbracelet.R
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
    /** The Pi relay, e.g. http://192.168.0.104:8787; blank = no relay. */
    val relayUrl: String = "",
    /** This phone's key as a wearer on the relay, once registered. */
    val wearerToken: String? = null,
    /** This phone's key as a caregiver on the relay, once linked. */
    val caregiverToken: String? = null,
    val caregiverName: String = "",
    /** Newest acknowledgement the wearer phone has seen, so heartbeats only fetch newer ones. */
    val acksSince: Long = 0,
    /** Last caregiver stream event id, so a reconnect replays what was missed. */
    val lastStreamEventId: Long = 0,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    /** Debug builds point at the Pi on the home network; release builds start with no relay. */
    private val defaultRelayUrl = context.getString(R.string.default_relay_url)

    private object Keys {
        val mode = stringPreferencesKey("app_mode")
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val wearer = stringPreferencesKey("wearer_name")
        val offline = intPreferencesKey("offline_alert_min")
        val lastSeen = longPreferencesKey("last_seen_at")
        val relayUrl = stringPreferencesKey("relay_url")
        val wearerToken = stringPreferencesKey("wearer_token")
        val caregiverToken = stringPreferencesKey("caregiver_token")
        val caregiverName = stringPreferencesKey("caregiver_name")
        val acksSince = longPreferencesKey("acks_since")
        val lastStreamEventId = longPreferencesKey("last_stream_event_id")

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
            relayUrl = p[Keys.relayUrl] ?: defaultRelayUrl,
            wearerToken = p[Keys.wearerToken],
            caregiverToken = p[Keys.caregiverToken],
            caregiverName = p[Keys.caregiverName] ?: "",
            acksSince = p[Keys.acksSince] ?: 0,
            lastStreamEventId = p[Keys.lastStreamEventId] ?: 0,
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

    /** A different relay means different accounts, so the old keys go. */
    suspend fun setRelayUrl(url: String) = edit {
        if (url.trim() != (it[Keys.relayUrl] ?: defaultRelayUrl)) {
            it.remove(Keys.wearerToken); it.remove(Keys.caregiverToken); it.remove(Keys.acksSince); it.remove(Keys.lastStreamEventId)
        }
        it[Keys.relayUrl] = url.trim()
    }
    suspend fun setWearerToken(token: String) = edit { it[Keys.wearerToken] = token }
    suspend fun setCaregiver(token: String?, name: String) = edit {
        if (token != null) it[Keys.caregiverToken] = token
        it[Keys.caregiverName] = name
    }
    suspend fun setAcksSince(at: Long) = edit { it[Keys.acksSince] = at }
    suspend fun setLastStreamEventId(id: Long) = edit { it[Keys.lastStreamEventId] = id }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit {
            it.remove(Keys.oldCaregivers)
            it.remove(Keys.oldSms)
            it.remove(Keys.oldSmsCheckins)
            block(it)
        }
    }
}
