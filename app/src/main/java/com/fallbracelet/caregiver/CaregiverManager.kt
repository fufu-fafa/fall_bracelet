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
import com.fallbracelet.relay.RelayDiscovery
import com.fallbracelet.relay.RelayFinder
import com.fallbracelet.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging
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

/** PUSH: no live connection because the dashboard is closed; alarms arrive by FCM. */
enum class StreamState { OFF, CONNECTING, LIVE, PUSH }

/**
 * Caregiver mode: keeps a live stream open to the relay and rings for FALL and SOS (APP-15, 16, 17).
 * Lives as long as the process; [CaregiverService] keeps the process alive while linked.
 */
class CaregiverManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val alerts: AlertDispatcher,
    private val scope: CoroutineScope,
    private val finder: RelayFinder,
    private val current: () -> AppSettings,
) {
    private val _wearers = MutableStateFlow<List<WatchedWearer>>(emptyList())
    val wearers: StateFlow<List<WatchedWearer>> = _wearers.asStateFlow()
    private val _stream = MutableStateFlow(StreamState.OFF)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    /** The caregiver screen is on screen. */
    private val visible = MutableStateFlow(false)
    /** The relay has this phone's FCM token, so alarms can wake it without a live connection. */
    private val _pushReady = MutableStateFlow(false)
    val pushReady: StateFlow<Boolean> = _pushReady.asStateFlow()

    /** Called by the activity on start and stop. */
    fun setVisible(on: Boolean) { visible.value = on }

    /**
     * Push (FCM) wakes the phone for alarms, so the live stream only runs while the dashboard is
     * open. If push isn't set up (no Google Play services, no internet when linking), the stream
     * and its foreground service stay on in the background as before: the local-network fallback.
     */
    fun start() {
        val link = settings.settings.map { s -> (s.caregiverToken.takeIf { s.mode == AppMode.CAREGIVER }) to s.relayUrl }
            .distinctUntilChanged()
        scope.launch {
            link.collect { (token, url) -> if (token != null && url.isNotBlank()) registerPush() else _pushReady.value = false }
        }
        scope.launch {
            combine(link, visible, _pushReady) { (token, url), shown, push ->
                val linked = token != null && url.isNotBlank()
                Triple(if (linked && (shown || !push)) url to token!! else null, linked && !push, linked)
            }.distinctUntilChanged().collect { (wantStream, wantService, _) ->
                streamJob?.cancel()
                if (wantService) CaregiverService.start(context) else CaregiverService.stop(context)
                if (wantStream == null) {
                    _stream.value = if (_pushReady.value) StreamState.PUSH else StreamState.OFF
                } else {
                    streamJob = scope.launch { streamLoop(wantStream.first, wantStream.second) }
                }
            }
        }
    }

    // --- Push (FCM) ----------------------------------------------------------------------

    /** Gets this phone's FCM token and gives it to the relay. Retries until it sticks. */
    private suspend fun registerPush() {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { Log.w(TAG, "no FCM token, staying on the live stream", it) }
            .getOrNull() ?: return
        sendPushToken(token)
    }

    /** Firebase rotated the token (FcmService.onNewToken). */
    fun onNewPushToken(token: String) = scope.launch { sendPushToken(token) }

    private suspend fun sendPushToken(token: String) {
        var backoff = 5_000L
        while (scope.isActive) {
            val s = current()
            if (s.caregiverToken == null || s.relayUrl.isBlank() || s.mode != AppMode.CAREGIVER) return
            val ok = runCatching { client().put("/v1/push-token", JSONObject().put("token", token)) }
                .onFailure { Log.w(TAG, "relay didn't take the FCM token: ${it.message}") }.isSuccess
            if (ok) { _pushReady.value = true; return }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
    }

    /** A data message from FcmService. Same handling as the stream, so nothing rings twice. */
    fun handlePush(data: Map<String, String>) {
        // The relay keeps the FCM token after a sign-out or a switch to wearer mode.
        if (current().caregiverToken == null || current().mode != AppMode.CAREGIVER) return
        when (data["kind"]) {
            "event" -> {
                val e = RemoteEvent(
                    id = data["id"]?.toLongOrNull() ?: return,
                    wearerId = data["wearerId"]?.toLongOrNull() ?: 0,
                    type = runCatching { EventType.valueOf(data["type"].orEmpty()) }.getOrDefault(EventType.UNKNOWN),
                    batteryPct = data["batteryPct"]?.toIntOrNull() ?: 0,
                    occurredAt = data["occurredAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                    impactG = data["impactG"]?.toFloatOrNull() ?: 0f,
                    message = data["message"].orEmpty(),
                    ackedBy = null,
                    ackedAt = null,
                )
                onEvent(e, data["wearerName"].orEmpty(), data["escalated"] == "1", data["escalations"]?.toIntOrNull() ?: 0)
            }
            "ack" -> data["eventId"]?.toLongOrNull()?.let { alerts.cancelCaregiverAlarm(it) }
            "offline" -> data["wearerId"]?.toLongOrNull()?.let { alerts.notifyWearerOffline(it, data["name"].orEmpty()) }
            "online" -> data["wearerId"]?.toLongOrNull()?.let { alerts.clearWearerOffline(it) }
        }
        if (visible.value) scope.launch { runCatching { refresh() } }
    }

    private fun client() = current().let { RelayClient(it.relayUrl, it.caregiverToken) }

    /** Links to a wearer with their invite code. A phone already linked keeps its key and follows one more. */
    suspend fun link(relayUrl: String, code: String, name: String): Result<String> = runCatching {
        if (relayUrl.trim() != current().relayUrl) settings.setRelay(relayUrl, RelayDiscovery.check(relayUrl.trim())?.relayId)
        val token = current().caregiverToken
        val r = RelayClient(relayUrl.trim(), token).post("/v1/link", JSONObject().put("code", code.trim()).put("name", name.trim()))
        settings.setCaregiver(r.optString("token").takeIf { it.isNotEmpty() && it != "null" }, name.trim())
        refresh()
        r.getJSONObject("wearer").getString("name")
    }.onFailure { Log.w(TAG, "link failed", it) }

    /** "Find relay" on the link screen: scans the WiFi and returns the address it found. */
    suspend fun findRelay(): String? = finder.findNow()?.url

    suspend fun refresh() {
        val a = client().get("/v1/dashboard?limit=1000").getJSONArray("wearers")
        _wearers.value = List(a.length()) { WatchedWearer.from(a.getJSONObject(it)) }
    }

    /** Stops alarms on this phone, like the web page's sign-out. The name is kept for the next link. */
    suspend fun signOut() {
        _wearers.value.flatMap { it.events }.filter { it.type.isAlarm && it.ackedAt == null }
            .forEach { alerts.cancelCaregiverAlarm(it.id) }
        settings.clearCaregiverLink()
        _wearers.value = emptyList()
        _error.value = null
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
                // Found at a new address: the settings change restarts the stream from start().
                if (e.status == 0 && finder.afterFailure()?.url.let { it != null && it != url }) return
            } catch (e: Exception) {
                _error.value = "Relay unreachable: ${e.message}"
                if (finder.afterFailure()?.url.let { it != null && it != url }) return
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
            "event" -> onEvent(RemoteEvent.from(d), d.optString("wearerName"), d.optBoolean("escalated"), d.optInt("escalations"))
            "status" -> _wearers.update { list ->
                list.map {
                    if (it.id != d.getLong("wearerId")) it else {
                        val st = d.getJSONObject("status")
                        // Replaces the old status: with no bracelet connected, state and battery aren't known.
                        it.copy(
                            offline = false, lastHeartbeat = d.getLong("at"),
                            braceletConnected = st.optBoolean("braceletConnected"),
                            state = st.optString("state").takeIf { s -> s.isNotEmpty() },
                            batteryPct = if (st.has("batteryPct")) st.getInt("batteryPct") else null,
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

    /** Alerts already raised, as "eventId:escalations": push and stream can both deliver one. */
    private val alerted = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 200
    }

    private fun onEvent(e: RemoteEvent, wearerName: String, escalated: Boolean, escalations: Int) {
        _wearers.update { l ->
            l.map { w ->
                if (w.id != e.wearerId) w else w.copy(
                    events = (listOf(e) + w.events.filter { it.id != e.id })
                        .sortedWith(compareByDescending<RemoteEvent> { it.occurredAt }.thenByDescending { it.id }),
                    batteryPct = if (e.batteryPct > 0) e.batteryPct else w.batteryPct,
                )
            }
        }
        if (e.ackedAt != null) return
        synchronized(alerted) { if (alerted.put("${e.id}:$escalations", Unit) != null) return }
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
