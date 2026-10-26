package com.fallbracelet.alert

import com.fallbracelet.ble.BraceletState
import com.fallbracelet.ble.EventType

/** What a linked caregiver's phone does with each event type (PRD event table). */
enum class CaregiverAlert {
    /** Full-screen alarm that rings until acknowledged. */
    ALARM,
    /** Rings once, labelled as a test. */
    RING_ONCE,
    /** Normal-priority notification. */
    NOTIFY,
    /** Dashboard and history only. */
    HISTORY,
}

/** Alert decisions, kept free of Android types so they can be unit tested. */
object AlertRules {
    fun caregiverAlert(type: EventType): CaregiverAlert = when (type) {
        EventType.FALL, EventType.SOS -> CaregiverAlert.ALARM
        EventType.TEST -> CaregiverAlert.RING_ONCE
        EventType.LOW_BATTERY, EventType.OFF -> CaregiverAlert.NOTIFY
        EventType.CHECKIN, EventType.CANCELLED, EventType.ON, EventType.UNKNOWN -> CaregiverAlert.HISTORY
    }

    /** Whether the wearer's own phone shows a notification for this event. */
    fun notifiesThisPhone(type: EventType): Boolean = type != EventType.CHECKIN && type != EventType.ON

    /**
     * Whether to warn that the bracelet has been out of contact too long. A bracelet that went
     * Idle or Off on purpose isn't lost, so it never triggers the warning.
     */
    fun shouldWarnOffline(
        now: Long,
        lastSeenAt: Long,
        offlineAlertMin: Int,
        lastKnownState: BraceletState,
        paired: Boolean,
    ): Boolean = paired && offlineAlertMin > 0 &&
        lastKnownState != BraceletState.IDLE && lastKnownState != BraceletState.OFF &&
        now - lastSeenAt > offlineAlertMin * 60_000L
}
