package com.fallbracelet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fallbracelet.ble.BleScanner
import com.fallbracelet.ble.BraceletConfig
import com.fallbracelet.ble.Command
import com.fallbracelet.ble.FoundDevice
import com.fallbracelet.data.AppMode
import com.fallbracelet.caregiver
import com.fallbracelet.relay.Invite
import com.fallbracelet.relay.LinkedCaregiver
import kotlinx.coroutines.flow.combine
import com.fallbracelet.manager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val m = app.manager
    private val scanner = BleScanner(app)

    val settings = m.appSettings
    val link = m.connection.link
    val status = m.connection.status
    val config = m.connection.config
    val events = m.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recording = m.recording
    val lastCheckin = m.lastCheckin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val relayHealth = combine(m.appSettings, m.relayError, m.caregiverCount) { s, err, n ->
        RelayHealth(s.relayUrl, s.wearerToken != null, err, n)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- Scanning ------------------------------------------------------------------------

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()
    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _found.value = emptyList()
        _scanError.value = null
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { _scanError.value = it.message }
                .collect { d ->
                    _found.value = (_found.value.filterNot { it.address == d.address } + d).sortedByDescending { it.rssi }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun pair(d: FoundDevice) = viewModelScope.launch {
        stopScan()
        m.pair(d.address, d.name)
    }

    fun forget() = viewModelScope.launch { m.forget() }

    // --- Bracelet ------------------------------------------------------------------------

    fun command(c: Byte) = viewModelScope.launch { m.connection.sendCommand(c) }

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()

    fun saveConfig(c: BraceletConfig) = viewModelScope.launch {
        val ok = m.connection.writeConfig(c)
        if (ok) m.settings.setWearerName(c.wearerName)
        _saveResult.value = if (ok) "Saved to the bracelet" else "Couldn't reach the bracelet"
    }

    fun clearSaveResult() { _saveResult.value = null }

    /** After a permission or Bluetooth prompt: try again with whatever was granted. */
    fun retryConnect() {
        m.retryConnect()
        if (settings.value.deviceAddress == null) startScan()
    }

    // --- Phone settings ------------------------------------------------------------------

    fun setMode(mode: AppMode) = viewModelScope.launch { m.setMode(mode) }

    // --- Relay, wearer side --------------------------------------------------------------

    fun setRelayUrl(url: String) = viewModelScope.launch { m.settings.setRelayUrl(url); m.syncNow() }

    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite.asStateFlow()
    private val _caregivers = MutableStateFlow<List<LinkedCaregiver>>(emptyList())
    val caregivers: StateFlow<List<LinkedCaregiver>> = _caregivers.asStateFlow()
    private val _relayMessage = MutableStateFlow<String?>(null)
    val relayMessage: StateFlow<String?> = _relayMessage.asStateFlow()

    fun newInvite() = viewModelScope.launch {
        runCatching { m.relay.ensureRegistered(); m.relay.invite() }
            .onSuccess { _invite.value = it; _relayMessage.value = null }
            .onFailure { _relayMessage.value = "Couldn't make an invite: ${it.message}" }
    }

    fun refreshCaregivers() = viewModelScope.launch {
        if (!m.relay.configured) return@launch
        runCatching { m.relay.caregivers() }.onSuccess { _caregivers.value = it }
    }

    fun removeCaregiver(id: Long) = viewModelScope.launch {
        runCatching { m.relay.removeCaregiver(id) }.onFailure { _relayMessage.value = "Couldn't remove: ${it.message}" }
        refreshCaregivers()
        m.syncNow()
    }

    // --- Relay, caregiver side -----------------------------------------------------------

    private val cg = app.caregiver
    val watched = cg.wearers
    val streamState = cg.stream
    val caregiverError = cg.error
    private val _linkResult = MutableStateFlow<String?>(null)
    val linkResult: StateFlow<String?> = _linkResult.asStateFlow()

    fun link(url: String, code: String, name: String) = viewModelScope.launch {
        _linkResult.value = cg.link(url, code, name).fold({ "Linked to $it" }, { "Couldn't link: ${it.message}" })
    }

    fun acknowledge(eventId: Long) = cg.acknowledge(eventId)
    fun setOfflineAlert(min: Int) = viewModelScope.launch { m.settings.setOfflineAlertMin(min) }
    fun clearHistory() = viewModelScope.launch { m.clearHistory() }

    // --- Live stream ---------------------------------------------------------------------

    /** The last 3 s of SVM at 100 Hz, oldest first. */
    private val _svm = MutableStateFlow(FloatArray(0))
    val svm: StateFlow<FloatArray> = _svm.asStateFlow()

    private val _recordings = MutableStateFlow<List<File>>(emptyList())
    val recordings: StateFlow<List<File>> = _recordings.asStateFlow()

    init {
        viewModelScope.launch {
            val ring = ArrayDeque<Float>(WINDOW)
            m.connection.stream.collect { samples ->
                samples.forEach {
                    if (ring.size == WINDOW) ring.removeFirst()
                    ring.addLast(it.svm)
                }
                _svm.value = ring.toFloatArray()
            }
        }
        refreshRecordings()
    }

    fun setStreaming(on: Boolean) = command(if (on) Command.STREAM_ON else Command.STREAM_OFF)

    fun startRecording(label: String) {
        m.startRecording(label)
        refreshRecordings()
    }

    fun stopRecording() {
        m.stopRecording()
        refreshRecordings()
    }

    fun deleteRecording(f: File) {
        f.delete()
        refreshRecordings()
    }

    fun refreshRecordings() {
        _recordings.value = m.recorder.list()
    }

    companion object {
        const val WINDOW = 300
    }
}
