package com.fallbracelet

import com.fallbracelet.ble.BleProtocol
import com.fallbracelet.ble.BraceletConfig
import com.fallbracelet.ble.BraceletState
import com.fallbracelet.ble.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte layouts must match firmware/fall_bracelet/ble_protocol.h. */
class BleProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun status() {
        val s = BleProtocol.parseStatus(bytes(3, 68, 0x74, 0x0F, 14, 0b101, 2, 0, 0x10, 0x0E, 0, 0))!!
        assertEquals(BraceletState.COUNTDOWN, s.state)
        assertEquals(68, s.batteryPct)
        assertEquals(3956, s.batteryMv)
        assertEquals(14, s.countdownRemaining)
        assertTrue(s.clockSet && s.lowBattery && !s.streaming)
        assertEquals(2, s.pendingEvents)
        assertEquals(3600L, s.uptimeS)
    }

    @Test fun event() {
        // seq 0xFFFFFFFE checks unsigned handling; epoch 1_761_402_720; impact 4.10 g
        val e = BleProtocol.parseEvent(
            bytes(0xFE, 0xFF, 0xFF, 0xFF, 1, 68, 0x60, 0x83, 0xFC, 0x68, 0x9A, 0x01, 0, 2, 0, 0)
        )!!
        assertEquals(0xFFFFFFFEL, e.seq)
        assertEquals(EventType.FALL, e.type)
        assertEquals(68, e.batteryPct)
        assertEquals(0x68FC8360L, e.epochS)
        assertEquals(4.10f, e.impactG, 0.001f)
        assertEquals(2, e.cancelled24h)
    }

    @Test fun configRoundTrip() {
        val c = BraceletConfig(countdownS = 30, checkinHour = 9, checkinMinute = 15, wearerName = "Grandma")
        assertEquals(c, BleProtocol.parseConfig(BleProtocol.encodeConfig(c)))
    }

    @Test fun nameTruncatesOnCharacterBoundary() {
        val c = BraceletConfig(wearerName = "ÉÉÉÉÉÉÉÉÉÉÉ") // 11 × 2 bytes
        assertEquals("ÉÉÉÉÉÉÉÉÉÉ", BleProtocol.parseConfig(BleProtocol.encodeConfig(c))!!.wearerName)
    }

    @Test fun stream() {
        // counter 7, one sample: x = 1000 mg, y = -500 mg, z = 0
        val (n, samples) = BleProtocol.parseStream(bytes(7, 0, 0xE8, 0x03, 0x0C, 0xFE, 0, 0))
        assertEquals(7, n)
        assertEquals(1f, samples.single().x, 0.001f)
        assertEquals(-0.5f, samples.single().y, 0.001f)
    }

    @Test fun ackAndTime() {
        assertEquals(bytes(0x2A, 0, 0, 0).toList(), BleProtocol.encodeAck(42).toList())
        assertEquals(bytes(1, 0, 0, 0, 0x3C, 0).toList(), BleProtocol.encodeTime(1, 60).toList())
    }
}
