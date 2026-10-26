package com.fallbracelet.caregiver

import android.content.Context
import android.util.Log
import com.fallbracelet.alert.AlertDispatcher
import com.fallbracelet.alert.AlertRules
import com.fallbracelet.alert.CaregiverAlert
import com.fallbracelet.ble.EventType
import com.fallbracelet.data.AppMode
import com.fallbracelet.data.AppSettings
import com.fallbracelet.data.SettingsRepository
import com.fallbracelet.relay.RelayClient
import com.fallbracelet.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One event as the relay tells caregivers about it. */
data class RemoteEvent(
    val id: Long,
    val wearerId: Long,
    val type: EventType,
    val batteryPct: Int,
    val occurredAt: Long,
    val impactG: Float,
    val message: String,
    val ackedBy: String?,
    val ackedAt: Long?,
) {
    companion object {
        fun from(o: JSONObject) = RemoteEvent(
            id = o.getLong("id"),
            wearerId = o.getLong("wearerId"),
            type = runCatching { EventType.valueOf(o.getString("type")) }.getOrDefault(EventType.UNKNOWN),
            batteryPct = o.optInt("batteryPct"),
            occurredAt = o.getLong("occurredAt"),
            impactG = o.optDouble("impactG", 0.0).toFloat(),
            message = o.optString("message"),
            ackedBy = o.optString("ackedBy").takeIf { it.isNotEmpty() && it != "null" },
            ackedAt = o.optLong("ackedAt").takeIf { it > 0 },
        )
    }
}

/** A wearer this caregiver follows, as last heard from the relay. */
data class WatchedWearer(
    val id: Long,
    val name: String,
    val offline: Boolean,
    val lastHeartbeat: Long?,
    val braceletConnected: Boolean?,
    val state: String?,
    val batteryPct: Int?,
    val events: List<RemoteEvent>,
) {
    val lastCheckin get() = events.firstOrNull { it.type == EventType.CHECKIN }
    val lastEvent get() = events.firstOrNull()

    companion object {
        fun from(o: JSONObject): WatchedWearer {
            val st = o.optJSONObject("status")
            val ev = o.optJSONArray("events")
            return WatchedWearer(
                id = o.getLong("id"),
                name = o.getString("name"),
                offline = o.optBoolean("offline"),
                lastHeartbeat = o.optLong("lastHeartbeat").takeIf { it > 0 },
                braceletConnected = st?.takeIf { it.has("braceletConnected") }?.optBoolean("braceletConnected"),
                state = st?.optString("state")?.takeIf { it.isNotEmpty() },
                batteryPct = st?.takeIf { it.has("batteryPct") }?.optInt("batteryPct"),
                events = if (ev == null) emptyList() else List(ev.length()) { RemoteEvent.from(ev.getJSONObject(it)) },
            )
        }
    }
}

enum class StreamState { OFF, CONNECTING, LIVE }

/**
 * Caregiver mode: keeps a live stream open to the relay and rings for FALL and SOS (APP-15, 16, 17).
 * Lives as long as the process; [CaregiverService] keeps the process alive while linked.
 */
class CaregiverManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val alerts: AlertDispatcher,
    private val scope: CoroutineScope,
    private val current: () -> AppSettings,
) {
    private val _wearers = MutableStateFlow<List<WatchedWearer>>(emptyList())
    val wearers: StateFlow<List<WatchedWearer>> = _wearers.asStateFlow()
    private val _stream = MutableStateFlow(StreamState.OFF)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    fun start() {
        scope.launch {
            settings.settings.map { s -> (s.caregiverToken.takeIf { s.mode == AppMode.CAREGIVER }) to s.relayUrl }
                .distinctUntilChanged().collect { (token, url) ->
                    streamJob?.cancel()
                    if (token == null || url.isBlank()) {
                        _stream.value = StreamState.OFF
                        CaregiverService.stop(context)
                    } else {
                        CaregiverService.start(context)
                        streamJob = scope.launch { streamLoop(url, token) }
                    }
                }
        }
    }

    private fun client() = current().let { RelayClient(it.relayUrl, it.caregiverToken) }

    /** Links to a wearer with their invite code. A phone already linked keeps its key and follows one more. */
    suspend fun link(relayUrl: String, code: String, name: String): Result<String> = runCatching {
        if (relayUrl.trim() != current().relayUrl) settings.setRelayUrl(relayUrl)
        val token = current().caregiverToken
        val r = RelayClient(relayUrl.trim(), token).post("/v1/link", JSONObject().put("code", code.trim()).put("name", name.trim()))
        settings.setCaregiver(r.optString("token").takeIf { it.isNotEmpty() && it != "null" }, name.trim())
        refresh()
        r.getJSONObject("wearer").getString("name")
    }.onFailure { Log.w(TAG, "link failed", it) }

    suspend fun refresh() {
        val a = client().get("/v1/dashboard").getJSONArray("wearers")
        _wearers.value = List(a.length()) { WatchedWearer.from(a.getJSONObject(it)) }
    }

    fun acknowledge(eventId: Long) = scope.launch {
        alerts.cancelCaregiverAlarm(eventId)
        runCatching { client().post("/v1/events/$eventId/ack") }
            .onFailure { _error.value = "Couldn't send the acknowledgement: ${it.message}" }
        runCatching { refresh() }
    }

    // --- Live stream ---------------------------------------------------------------------

    private suspend fun streamLoop(url: String, token: String) {
        var backoff = 2_000L
        while (scope.isActive) {
            _stream.value = StreamState.CONNECTING
            try {
                refresh()
                readStream(url, token)
                backoff = 2_000L
            } catch (e: RelayException) {
                _error.value = if (e.status == 401) "This phone's link was removed. Link again." else "Relay unreachable: ${e.message}"
            } catch (e: Exception) {
                _error.value = "Relay unreachable: ${e.message}"
            }
            _stream.value = StreamState.CONNECTING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Blocks until the stream drops. */
    private suspend fun readStream(url: String, token: String) = withContext(Dispatchers.IO) {
        val c = RelayClient(url, token).open("/v1/stream")
        c.connectTimeout = 10_000
        c.readTimeout = 60_000 // the relay sends a keepalive every 25 s
        c.setRequestProperty("Accept", "text/event-stream")
        current().lastStreamEventId.takeIf { it > 0 }?.let { c.setRequestProperty("Last-Event-ID", it.toString()) }
        try {
            if (c.responseCode != 200) throw RelayException(c.responseCode, "stream refused (HTTP ${c.responseCode})")
            _stream.value = StreamState.LIVE
            _error.value = null
            val reader = c.inputStream.bufferedReader()
            var type = "message"
            var id: Long? = null
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) dispatch(type, JSONObject(data.toString()), id)
                        type = "message"; id = null; data.clear()
                    }
                    line.startsWith("event:") -> type = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trim())
                    line.startsWith("id:") -> id = line.substringAfter(':').trim().toLongOrNull()
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun dispatch(type: String, d: JSONObject, id: Long?) {
        when (type) {
            "event" -> onEvent(RemoteEvent.from(d), d.optString("wearerName"), d.optBoolean("escalated"))
            "status" -> _wearers.update { list ->
                list.map {
                    if (it.id != d.getLong("wearerId")) it else {
                        val st = d.getJSONObject("status")
                        it.copy(
                            offline = false, lastHeartbeat = d.getLong("at"),
                            braceletConnected = st.optBoolean("braceletConnected"),
                            state = st.optString("state").takeIf { s -> s.isNotEmpty() } ?: it.state,
                            batteryPct = if (st.has("batteryPct")) st.getInt("batteryPct") else it.batteryPct,
                        )
                    }
                }
            }
            "offline" -> {
                _wearers.update { l -> l.map { if (it.id == d.getLong("wearerId")) it.copy(offline = true) else it } }
                alerts.notifyWearerOffline(d.getLong("wearerId"), d.optString("name"))
            }
            "online" -> {
                _wearers.update { l -> l.map { if (it.id == d.getLong("wearerId")) it.copy(offline = false) else it } }
                alerts.clearWearerOffline(d.getLong("wearerId"))
            }
            "ack" -> {
                alerts.cancelCaregiverAlarm(d.getLong("eventId"))
                _wearers.update { l ->
                    l.map { w ->
                        w.copy(events = w.events.map { e ->
                            if (e.id == d.getLong("eventId")) e.copy(ackedBy = d.getString("by"), ackedAt = d.getLong("at")) else e
                        })
                    }
                }
            }
            "unlinked", "hello" -> runCatching { refresh() }
        }
        if (id != null && id > current().lastStreamEventId) settings.setLastStreamEventId(id)
    }

    private fun onEvent(e: RemoteEvent, wearerName: String, escalated: Boolean) {
        _wearers.update { l ->
            l.map { w -> if (w.id == e.wearerId) w.copy(events = (listOf(e) + w.events.filter { it.id != e.id })) else w }
        }
        if (e.ackedAt != null) return
        when (AlertRules.caregiverAlert(e.type)) {
            CaregiverAlert.ALARM -> alerts.caregiverAlarm(e, wearerName, escalated)
            CaregiverAlert.RING_ONCE -> alerts.caregiverTest(e, wearerName)
            CaregiverAlert.NOTIFY -> alerts.caregiverInfo(e, wearerName)
            CaregiverAlert.HISTORY -> Unit
        }
    }

    private companion object {
        const val TAG = "CaregiverManager"
    }
}
