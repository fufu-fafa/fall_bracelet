package com.fallbracelet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fallbracelet.alert.AlertDispatcher
import com.fallbracelet.alert.AlertRules
import com.fallbracelet.ble.BraceletConnection
import com.fallbracelet.ble.BraceletEvent
import com.fallbracelet.ble.BraceletState
import com.fallbracelet.ble.EventType
import com.fallbracelet.ble.LinkState
import com.fallbracelet.data.AppDatabase
import com.fallbracelet.data.AppMode
import com.fallbracelet.data.AppSettings
import com.fallbracelet.data.EventEntity
import com.fallbracelet.data.Recorder
import com.fallbracelet.data.SettingsRepository
import com.fallbracelet.data.Uploader
import com.fallbracelet.relay.WearerRelay
import com.fallbracelet.service.BraceletService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application-wide owner of the bracelet link. Lives as long as the process; the foreground
 * [BraceletService] only keeps the process alive while a bracelet is paired.
 */
class BraceletManager(
    private val context: Context,
    val settings: SettingsRepository,
    private val db: AppDatabase,
    val alerts: AlertDispatcher,
    private val scope: CoroutineScope,
) {
    val connection = BraceletConnection(context, scope)
    val recorder = Recorder(context)
    val events = db.events().recent()
    val lastCheckin = db.events().lastCheckin()
    val appSettings: StateFlow<AppSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The Pi relay; events wait in the local upload queue whenever it can't be reached. */
    val relay = WearerRelay(settings) { appSettings.value }
    private val uploader = Uploader(db.events(), relay)

    private val _relayError = MutableStateFlow<String?>(null)
    /** Null when the last relay call worked. */
    val relayError: StateFlow<String?> = _relayError.asStateFlow()
    private val _caregiverCount = MutableStateFlow<Int?>(null)
    /** Linked caregivers, as of the last heartbeat; null before the first one. */
    val caregiverCount: StateFlow<Int?> = _caregiverCount.asStateFlow()

    private val _recording = MutableStateFlow<File?>(null)
    val recording: StateFlow<File?> = _recording.asStateFlow()

    fun start() {
        scope.launch {
            // Only the wearer's phone talks to the bracelet; a caregiver phone never connects.
            settings.settings.map { s -> s.deviceAddress.takeIf { s.mode == AppMode.WEARER } }
                .distinctUntilChanged().collect { address ->
                if (address == null) {
                    connection.disconnect()
                    BraceletService.stop(context)
                } else if (canConnect()) {
                    connection.connect(address)
                    BraceletService.start(context)
                }
            }
        }
        scope.launch { connection.events.collect { handle(it) } }
        scope.launch { connection.stream.collect { recorder.append(it) } }
        scope.launch {
            connection.config.filterNotNull().collect {
                if (it.wearerName.isNotBlank()) settings.setWearerName(it.wearerName)
            }
        }
        scope.launch { connection.status.filterNotNull().collect { lastKnownState = it.state } }
        scope.launch { watchOffline() }
        scope.launch { relayLoop() }
        scope.launch {
            // A new wearer name on the bracelet is the name caregivers see.
            appSettings.map { it.wearerName }.distinctUntilChanged().collect { runCatching { relay.rename(it) } }
        }
    }

    /** Register once, then upload queued events every minute and heartbeat every 5 min (APP-4, APP-11). */
    private suspend fun relayLoop() {
        var lastHeartbeat = 0L
        while (true) {
            // Read the stored settings, not the StateFlow's initial placeholder.
            val s = settings.settings.first()
            if (s.mode == AppMode.WEARER && s.relayUrl.isNotBlank()) {
                val result = runCatching {
                    relay.ensureRegistered()
                    uploader.flush()
                    // While an alarm waits for a caregiver, check every minute so "Seen by" shows quickly.
                    val waiting = db.events().unackedAlarms(System.currentTimeMillis() - 30 * 60_000) > 0
                    if (waiting || System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_MS) {
                        heartbeat()
                        lastHeartbeat = System.currentTimeMillis()
                    }
                }
                _relayError.value = result.exceptionOrNull()?.message
            }
            delay(60_000)
        }
    }

    private suspend fun heartbeat() {
        val s = appSettings.value
        val reply = relay.heartbeat(connection.status.value, connection.link.value == LinkState.CONNECTED, s.acksSince)
        for (a in reply.acks) db.events().setAck(a.deviceAddress, a.seq, a.by, a.at)
        reply.acks.maxOfOrNull { it.at }?.let { settings.setAcksSince(it) }
        _caregiverCount.value = reply.caregivers
    }

    /** After pairing, or when a heartbeat is wanted right now (e.g. a caregiver just linked). */
    fun syncNow() = scope.launch {
        _relayError.value = runCatching { relay.ensureRegistered(); uploader.flush(); heartbeat() }.exceptionOrNull()?.message
    }

    /** Survives disconnects: a bracelet that went Idle or Off on purpose isn't "lost". */
    @Volatile private var lastKnownState = BraceletState.UNKNOWN

    /** Called after the user grants Bluetooth permission. */
    fun retryConnect() {
        if (appSettings.value.mode != AppMode.WEARER) return
        val address = appSettings.value.deviceAddress ?: return
        if (!canConnect()) return
        connection.connect(address)
        BraceletService.start(context)
    }

    fun canConnect(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    suspend fun pair(address: String, name: String) = settings.setDevice(address, name)

    suspend fun setMode(mode: AppMode) {
        if (mode == AppMode.CAREGIVER) forget()
        settings.setMode(mode)
    }

    suspend fun forget() {
        stopRecording()
        settings.setDevice(null, null)
    }

    fun startRecording(label: String) {
        _recording.value = recorder.start(label)
    }

    fun stopRecording() {
        recorder.stop()
        _recording.value = null
    }

    suspend fun clearHistory() = db.events().clear()

    /**
     * Store, alert, then acknowledge, then upload. The bracelet keeps the event in flash until
     * the ACK; after that the local upload queue keeps it until the relay has it.
     */
    private suspend fun handle(e: BraceletEvent) {
        val s = settings.settings.first()
        val address = s.deviceAddress ?: return
        val now = System.currentTimeMillis()
        val occurredAt = if (e.epochS > 0) e.epochS * 1000 else now
        val countdown = connection.config.value?.countdownS ?: 20
        val text = alerts.message(e, occurredAt, s.wearerName, countdown)
        val id = db.events().insert(
            EventEntity(
                deviceAddress = address, seq = e.seq, type = e.type.name, batteryPct = e.batteryPct,
                occurredAt = occurredAt, receivedAt = now, impactG = e.impactG,
                alarms24h = e.alarms24h, cancelled24h = e.cancelled24h, message = text,
            )
        )
        if (id != -1L && AlertRules.notifiesThisPhone(e.type)) { // -1: a resend we already handled
            alerts.notify(e.type, text, NOTIFY_BASE + (e.seq % 10_000).toInt())
        }
        connection.ack(e.seq)
        _relayError.value = runCatching { uploader.flush() }.exceptionOrNull()?.message
    }

    private suspend fun watchOffline() {
        var lastSeen = appSettings.value.lastSeenAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        var warned = false
        var lastPersist = 0L
        while (true) {
            val s = appSettings.value
            val now = System.currentTimeMillis()
            if (connection.link.value == LinkState.CONNECTED) {
                lastSeen = now
                if (warned) alerts.clearOffline()
                warned = false
                if (now - lastPersist > 5 * 60_000) {
                    settings.setLastSeen(now)
                    lastPersist = now
                }
            } else if (!warned &&
                AlertRules.shouldWarnOffline(now, lastSeen, s.offlineAlertMin, lastKnownState, s.deviceAddress != null)
            ) {
                alerts.notifyOffline(((now - lastSeen) / 60_000).toInt())
                warned = true
            }
            delay(60_000)
        }
    }

    private companion object {
        const val NOTIFY_BASE = 1000
        const val HEARTBEAT_MS = 5 * 60_000L
    }
}
