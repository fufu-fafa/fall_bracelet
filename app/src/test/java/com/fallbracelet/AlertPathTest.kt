package com.fallbracelet

import com.fallbracelet.alert.AlertRules
import com.fallbracelet.alert.CaregiverAlert
import com.fallbracelet.ble.BraceletState
import com.fallbracelet.ble.EventType
import com.fallbracelet.data.EventEntity
import com.fallbracelet.data.EventRelay
import com.fallbracelet.data.UploadQueue
import com.fallbracelet.data.Uploader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which events alarm whom, the offline warning, and the upload queue (PRD event table, APP-4, APP-11). */
class AlertPathTest {

    @Test fun onlyFallAndSosRingTheCaregiverAlarm() {
        val alarms = EventType.entries.filter { AlertRules.caregiverAlert(it) == CaregiverAlert.ALARM }
        assertEquals(listOf(EventType.FALL, EventType.SOS), alarms)
    }

    @Test fun caregiverTableMatchesPrd() {
        assertEquals(CaregiverAlert.RING_ONCE, AlertRules.caregiverAlert(EventType.TEST))
        assertEquals(CaregiverAlert.NOTIFY, AlertRules.caregiverAlert(EventType.LOW_BATTERY))
        assertEquals(CaregiverAlert.NOTIFY, AlertRules.caregiverAlert(EventType.OFF))
        for (t in listOf(EventType.CHECKIN, EventType.CANCELLED, EventType.ON)) {
            assertEquals(t.name, CaregiverAlert.HISTORY, AlertRules.caregiverAlert(t))
        }
    }

    @Test fun checkinsAndSwitchOnDontNotifyThisPhone() {
        assertFalse(AlertRules.notifiesThisPhone(EventType.CHECKIN))
        assertFalse(AlertRules.notifiesThisPhone(EventType.ON))
        assertTrue(AlertRules.notifiesThisPhone(EventType.FALL))
        assertTrue(AlertRules.notifiesThisPhone(EventType.CANCELLED))
    }

    @Test fun offlineWarning() {
        val min = 60_000L
        fun warn(gapMin: Long, setting: Int = 30, state: BraceletState = BraceletState.MONITORING, paired: Boolean = true) =
            AlertRules.shouldWarnOffline(now = 1_000 * min, lastSeenAt = 1_000 * min - gapMin * min, setting, state, paired)

        assertFalse(warn(29))
        assertTrue(warn(31))
        assertFalse("never", warn(500, setting = 0))
        assertFalse("switched off on purpose", warn(500, state = BraceletState.OFF))
        assertFalse("asleep on purpose", warn(500, state = BraceletState.IDLE))
        assertFalse("nothing paired", warn(500, paired = false))
    }

    // --- Upload queue --------------------------------------------------------------------

    private fun event(id: Long, seq: Long, at: Long) = EventEntity(
        id = id, deviceAddress = "AA", seq = seq, type = "FALL", batteryPct = 80, occurredAt = at,
        receivedAt = at, impactG = 3f, alarms24h = 0, cancelled24h = 0, message = "",
    )

    /** In-memory stand-in for the Room queue. */
    private class FakeQueue(vararg events: EventEntity) : UploadQueue {
        val rows = events.toMutableList()
        override suspend fun pendingUploads() = rows.filter { it.uploadedAt == null }.sortedWith(compareBy({ it.occurredAt }, { it.id }))
        override suspend fun markUploaded(id: Long, at: Long) {
            val i = rows.indexOfFirst { it.id == id }
            rows[i] = rows[i].copy(uploadedAt = at)
        }
    }

    private class FakeRelay(override val configured: Boolean = true, val failOnSeq: Long? = null) : EventRelay {
        val got = mutableListOf<Long>()
        override suspend fun upload(event: EventEntity): Boolean {
            if (event.seq == failOnSeq) return false
            got += event.seq
            return true
        }
    }

    @Test fun uploadsOldestFirst() = runBlocking {
        val q = FakeQueue(event(1, seq = 12, at = 300), event(2, seq = 10, at = 100), event(3, seq = 11, at = 200))
        val relay = FakeRelay()
        assertEquals(3, Uploader(q, relay).flush { 999 })
        assertEquals(listOf(10L, 11L, 12L), relay.got)
        assertTrue(q.pendingUploads().isEmpty())
    }

    @Test fun stopsAtFirstFailureSoOrderIsKept() = runBlocking {
        val q = FakeQueue(event(1, 10, 100), event(2, 11, 200), event(3, 12, 300))
        val relay = FakeRelay(failOnSeq = 11)
        assertEquals(1, Uploader(q, relay).flush { 999 })
        assertEquals(listOf(10L), relay.got)
        assertEquals(listOf(11L, 12L), q.pendingUploads().map { it.seq })
    }

    @Test fun uploadedEventsAreNotSentAgain() = runBlocking {
        val q = FakeQueue(event(1, 10, 100))
        val relay = FakeRelay()
        val up = Uploader(q, relay)
        up.flush { 999 }
        up.flush { 1_000 }
        assertEquals(listOf(10L), relay.got)
    }

    @Test fun nothingLeavesWithoutARelay() = runBlocking {
        val q = FakeQueue(event(1, 10, 100))
        val relay = FakeRelay(configured = false)
        assertEquals(0, Uploader(q, relay).flush { 999 })
        assertEquals(1, q.pendingUploads().size)
    }
}
