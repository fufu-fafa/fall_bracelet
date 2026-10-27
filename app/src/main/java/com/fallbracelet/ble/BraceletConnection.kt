package com.fallbracelet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * GATT client for one bracelet. Android allows only one outstanding GATT operation at a time,
 * so every read/write goes through [op], which waits for the matching callback.
 *
 * Once [connect] is called the connection is kept alive: after a drop it reconnects with
 * autoConnect, which waits in the background until the bracelet is back in range. Turning
 * Bluetooth off kills that pending connection, so it reconnects again when Bluetooth comes back.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before connecting.
class BraceletConnection(private val context: Context, private val scope: CoroutineScope) {

    private val _link = MutableStateFlow(LinkState.DISCONNECTED)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _status = MutableStateFlow<BraceletStatus?>(null)
    val status: StateFlow<BraceletStatus?> = _status.asStateFlow()

    private val _config = MutableStateFlow<BraceletConfig?>(null)
    val config: StateFlow<BraceletConfig?> = _config.asStateFlow()

    private val _events = MutableSharedFlow<BraceletEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BraceletEvent> = _events.asSharedFlow()

    private val _stream = MutableSharedFlow<List<AccelSample>>(extraBufferCapacity = 128)
    val stream: SharedFlow<List<AccelSample>> = _stream.asSharedFlow()

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var address: String? = null
    private var setupJob: Job? = null

    private val opLock = Mutex()

    /** Bluetooth off drops the link and any pending autoConnect; on again starts a fresh one. */
    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth off")
                    setupJob?.cancel()
                    pending?.complete(null)
                    gatt?.close()
                    gatt = null
                    _status.value = null
                    if (address != null) _link.value = LinkState.CONNECTING
                }
                BluetoothAdapter.STATE_ON -> if (address != null && gatt == null) {
                    Log.i(TAG, "Bluetooth on, reconnecting")
                    open(autoConnect = false)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null

    fun connect(address: String) {
        if (this.address == address && gatt != null) return
        disconnect()
        this.address = address
        open(autoConnect = false)
    }

    fun disconnect() {
        address = null
        setupJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pending?.complete(null)
        _link.value = LinkState.DISCONNECTED
        _status.value = null
    }

    private fun open(autoConnect: Boolean) {
        val addr = address ?: return
        _link.value = LinkState.CONNECTING
        // With Bluetooth off connectGatt fails quietly; the adapter receiver opens again on STATE_ON.
        if (adapter?.isEnabled != true) return
        val device: BluetoothDevice = adapter.getRemoteDevice(addr)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    suspend fun sendCommand(command: Byte): Boolean = write(BleProtocol.COMMAND, byteArrayOf(command))

    suspend fun ack(seq: Long): Boolean = write(BleProtocol.EVENT_ACK, BleProtocol.encodeAck(seq))

    suspend fun writeConfig(config: BraceletConfig): Boolean {
        val ok = write(BleProtocol.CONFIG, BleProtocol.encodeConfig(config))
        if (ok) _config.value = config.copy(protocolVersion = _config.value?.protocolVersion ?: BleProtocol.VERSION)
        return ok
    }

    // --- Setup after connecting -----------------------------------------------------------

    private suspend fun setUp(g: BluetoothGatt) {
        if (op(timeoutMs = 15_000) { g.discoverServices() } == null) return fail(g, "service discovery")
        if (g.getService(BleProtocol.SERVICE) == null) return fail(g, "bracelet service not found")
        op { g.requestMtu(BleProtocol.MTU) }

        // The first secure operation may trigger the pairing dialog, so give the user time.
        val now = System.currentTimeMillis()
        val offsetMin = TimeZone.getDefault().getOffset(now) / 60_000
        if (!write(BleProtocol.TIME, BleProtocol.encodeTime(now / 1000, offsetMin), timeoutMs = 45_000)) {
            return fail(g, "set time")
        }
        read(BleProtocol.CONFIG)?.let { _config.value = BleProtocol.parseConfig(it) }
        read(BleProtocol.STATUS)?.let { _status.value = BleProtocol.parseStatus(it) }

        subscribe(g, BleProtocol.STATUS, indicate = false)
        subscribe(g, BleProtocol.STREAM, indicate = false)
        // Last: the bracelet starts sending queued events as soon as this is on.
        if (!subscribe(g, BleProtocol.EVENT, indicate = true)) return fail(g, "subscribe to events")

        _link.value = LinkState.CONNECTED
    }

    private fun fail(g: BluetoothGatt, what: String) {
        Log.w(TAG, "Setup failed: $what")
        g.disconnect() // onConnectionStateChange schedules the reconnect
    }

    private suspend fun subscribe(g: BluetoothGatt, uuid: java.util.UUID, indicate: Boolean): Boolean {
        val ch = characteristic(uuid) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(BleProtocol.CCCD) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return op { writeDescriptor(it, cccd, value) } != null
    }

    private suspend fun read(uuid: java.util.UUID): ByteArray? {
        val ch = characteristic(uuid) ?: return null
        return op { it.readCharacteristic(ch) }
    }

    private suspend fun write(uuid: java.util.UUID, value: ByteArray, timeoutMs: Long = 10_000): Boolean {
        val ch = characteristic(uuid) ?: return false
        return op(timeoutMs) { writeCharacteristic(it, ch, value) } != null
    }

    private fun characteristic(uuid: java.util.UUID): BluetoothGattCharacteristic? =
        gatt?.getService(BleProtocol.SERVICE)?.getCharacteristic(uuid)

    /** Starts one GATT operation and suspends until its callback; null on failure or timeout. */
    private suspend fun op(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): ByteArray? =
        opLock.withLock {
            val g = gatt ?: return null
            val done = CompletableDeferred<ByteArray?>()
            pending = done
            try {
                if (!start(g)) return null
                withTimeoutOrNull(timeoutMs) { done.await() }
            } finally {
                pending = null
            }
        }

    private fun complete(status: Int, value: ByteArray = OK) {
        pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
    }

    // --- API 33 changed the read/write signatures; support both ------------------------------

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun onValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleProtocol.STATUS -> BleProtocol.parseStatus(value)?.let { _status.value = it }
            BleProtocol.EVENT -> BleProtocol.parseEvent(value)?.let { _events.tryEmit(it) }
            BleProtocol.STREAM -> _stream.tryEmit(BleProtocol.parseStream(value).second)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setupJob = scope.launch { setUp(g) }
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            Log.i(TAG, "Disconnected, status $status")
            setupJob?.cancel()
            pending?.complete(null)
            g.close()
            gatt = null
            _status.value = null
            if (address == null) {
                _link.value = LinkState.DISCONNECTED
                return
            }
            // Keep trying: autoConnect waits for the bracelet without draining the battery.
            _link.value = LinkState.CONNECTING
            scope.launch {
                delay(1_000)
                if (address != null && gatt == null) open(autoConnect = true)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            complete(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(status)

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = complete(status, value)

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) complete(status, ch.value ?: OK)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            onValue(ch.uuid, value)

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onValue(ch.uuid, ch.value ?: return)
        }
    }

    private companion object {
        const val TAG = "BraceletConnection"
        val OK = ByteArray(0)
    }
}
