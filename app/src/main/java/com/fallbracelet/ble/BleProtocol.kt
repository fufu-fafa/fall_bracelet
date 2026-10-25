package com.fallbracelet.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sqrt

/** Mirror of firmware/fall_bracelet/ble_protocol.h. See PROTOCOL.md. */
object BleProtocol {
    const val VERSION = 1
    const val DEVICE_NAME_PREFIX = "FallBracelet"
    const val MTU = 185

    val SERVICE: UUID = uuid(1)
    val STATUS: UUID = uuid(2)
    val EVENT: UUID = uuid(3)
    val EVENT_ACK: UUID = uuid(4)
    val TIME: UUID = uuid(5)
    val CONFIG: UUID = uuid(6)
    val COMMAND: UUID = uuid(7)
    val STREAM: UUID = uuid(8)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid(n: Int): UUID = UUID.fromString("7a1f%04x-6b2c-4e3d-9f10-a1b2c3d4e5f6".format(n))

    const val STATUS_SIZE = 12
    const val EVENT_SIZE = 16
    const val CONFIG_SIZE = 24
    const val NAME_BYTES = 20

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun parseStatus(b: ByteArray): BraceletStatus? {
        if (b.size < STATUS_SIZE) return null
        val buf = le(b)
        val state = BraceletState.from(buf.get(0).toInt() and 0xFF)
        val flags = buf.get(5).toInt() and 0xFF
        return BraceletStatus(
            state = state,
            batteryPct = buf.get(1).toInt() and 0xFF,
            batteryMv = buf.getShort(2).toInt() and 0xFFFF,
            countdownRemaining = buf.get(4).toInt() and 0xFF,
            clockSet = flags and 0x01 != 0,
            streaming = flags and 0x02 != 0,
            lowBattery = flags and 0x04 != 0,
            pendingEvents = buf.getShort(6).toInt() and 0xFFFF,
            uptimeS = buf.getInt(8).toLong() and 0xFFFFFFFFL,
        )
    }

    fun parseEvent(b: ByteArray): BraceletEvent? {
        if (b.size < EVENT_SIZE) return null
        val buf = le(b)
        return BraceletEvent(
            seq = buf.getInt(0).toLong() and 0xFFFFFFFFL,
            type = EventType.from(buf.get(4).toInt() and 0xFF),
            batteryPct = buf.get(5).toInt() and 0xFF,
            epochS = buf.getInt(6).toLong() and 0xFFFFFFFFL,
            impactG = (buf.getShort(10).toInt() and 0xFFFF) / 100f,
            alarms24h = buf.get(12).toInt() and 0xFF,
            cancelled24h = buf.get(13).toInt() and 0xFF,
        )
    }

    fun encodeAck(seq: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq.toInt()).array()

    fun encodeTime(epochS: Long, utcOffsetMin: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(epochS.toInt())
            .putShort(utcOffsetMin.toShort())
            .array()

    fun parseConfig(b: ByteArray): BraceletConfig? {
        if (b.size < CONFIG_SIZE) return null
        val nameBytes = b.copyOfRange(4, 4 + NAME_BYTES)
        val end = nameBytes.indexOf(0).let { if (it < 0) NAME_BYTES else it }
        return BraceletConfig(
            countdownS = b[0].toInt() and 0xFF,
            checkinHour = b[1].toInt() and 0xFF,
            checkinMinute = b[2].toInt() and 0xFF,
            protocolVersion = b[3].toInt() and 0xFF,
            wearerName = String(nameBytes, 0, end, Charsets.UTF_8),
        )
    }

    fun encodeConfig(c: BraceletConfig): ByteArray {
        val out = ByteArray(CONFIG_SIZE)
        out[0] = c.countdownS.coerceIn(5, 60).toByte()
        out[1] = c.checkinHour.coerceIn(0, 23).toByte()
        out[2] = c.checkinMinute.coerceIn(0, 59).toByte()
        out[3] = VERSION.toByte()
        val name = truncateUtf8(c.wearerName.trim(), NAME_BYTES)
        name.copyInto(out, 4)
        return out
    }

    /** Cuts at a character boundary so the firmware never sees half a multi-byte character. */
    private fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        var bytes = s.toByteArray(Charsets.UTF_8)
        var end = s.length
        while (bytes.size > maxBytes && end > 0) {
            end--
            bytes = s.substring(0, end).toByteArray(Charsets.UTF_8)
        }
        return bytes
    }

    /** Returns the packet counter and the samples, in g. */
    fun parseStream(b: ByteArray): Pair<Int, List<AccelSample>> {
        if (b.size < 2) return 0 to emptyList()
        val buf = le(b)
        val counter = buf.getShort(0).toInt() and 0xFFFF
        val n = (b.size - 2) / 6
        val samples = List(n) { i ->
            val o = 2 + i * 6
            AccelSample(buf.getShort(o) / 1000f, buf.getShort(o + 2) / 1000f, buf.getShort(o + 4) / 1000f)
        }
        return counter to samples
    }
}

enum class BraceletState(val code: Int, val label: String) {
    OFF(0, "Off"),
    MONITORING(1, "Monitoring"),
    IDLE(2, "Idle"),
    COUNTDOWN(3, "Fall countdown"),
    SENDING_ALERT(4, "Sending alert"),
    UNKNOWN(-1, "Unknown");

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class EventType(val code: Int, val label: String, val isAlarm: Boolean) {
    FALL(1, "Fall", true),
    SOS(2, "SOS", true),
    LOW_BATTERY(3, "Low battery", false),
    OFF(4, "Switched off", false),
    CHECKIN(5, "Daily check-in", false),
    CANCELLED(6, "Alarm cancelled", false),
    ON(7, "Switched on", false),
    TEST(8, "Test", false),
    UNKNOWN(-1, "Unknown", false);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

object Command {
    const val FIND: Byte = 0x01
    const val CANCEL: Byte = 0x02
    const val STREAM_ON: Byte = 0x03
    const val STREAM_OFF: Byte = 0x04
    const val TEST: Byte = 0x05
    const val POWER_OFF: Byte = 0x06
}

data class BraceletStatus(
    val state: BraceletState,
    val batteryPct: Int,
    val batteryMv: Int,
    val countdownRemaining: Int,
    val clockSet: Boolean,
    val streaming: Boolean,
    val lowBattery: Boolean,
    val pendingEvents: Int,
    val uptimeS: Long,
)

data class BraceletEvent(
    val seq: Long,
    val type: EventType,
    val batteryPct: Int,
    /** 0 when the bracelet's clock wasn't set yet. */
    val epochS: Long,
    val impactG: Float,
    val alarms24h: Int,
    val cancelled24h: Int,
)

data class BraceletConfig(
    val countdownS: Int = 20,
    val checkinHour: Int = 8,
    val checkinMinute: Int = 0,
    val wearerName: String = "",
    val protocolVersion: Int = BleProtocol.VERSION,
)

data class AccelSample(val x: Float, val y: Float, val z: Float) {
    /** Signal vector magnitude, 1 g at rest. */
    val svm: Float get() = sqrt(x * x + y * y + z * z)
}
