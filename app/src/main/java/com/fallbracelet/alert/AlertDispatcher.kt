package com.fallbracelet.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fallbracelet.R
import com.fallbracelet.ble.BraceletEvent
import com.fallbracelet.ble.EventType
import com.fallbracelet.caregiver.AckReceiver
import com.fallbracelet.caregiver.AlarmActivity
import com.fallbracelet.caregiver.RemoteEvent
import com.fallbracelet.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns bracelet events into notifications on this phone. Caregivers are reached through the relay. */
class AlertDispatcher(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_ALARM, "Fall and SOS alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A fall nobody cancelled, or an SOS press"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setBypassDnd(true)
        }
        val info = NotificationChannel(CH_INFO, "Bracelet updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Battery, switched off, check-ins and connection warnings"
        }
        val service = NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the app keeps the bracelet connected"
        }
        nm.createNotificationChannels(listOf(alarm, info, service))
    }

    /** The alert text, in the spec's wording. */
    fun message(e: BraceletEvent, occurredAt: Long, wearer: String, countdownS: Int): String {
        val who = if (wearer.isBlank()) "Bracelet" else "$wearer's bracelet"
        val whenText = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(occurredAt))
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(occurredAt))
        return when (e.type) {
            EventType.FALL -> "FALL detected — $who — $whenText — no response to the ${countdownS} s check — " +
                "battery ${e.batteryPct}% — impact ${"%.1f".format(e.impactG)} g"
            EventType.SOS -> "SOS pressed — $who — $whenText — battery ${e.batteryPct}%"
            EventType.LOW_BATTERY -> "Battery ${e.batteryPct}% — please charge — $who"
            EventType.OFF -> "$who switched off at $time — not monitoring"
            EventType.CHECKIN -> "OK — monitoring — battery ${e.batteryPct}% — " +
                "${e.alarms24h} alarms, ${e.cancelled24h} cancelled in 24h — $who"
            EventType.CANCELLED -> "Fall alarm cancelled by the wearer at $time — impact ${"%.1f".format(e.impactG)} g"
            EventType.ON -> "$who switched on at $time"
            EventType.TEST -> "TEST alert — $who — $whenText — battery ${e.batteryPct}%"
            EventType.UNKNOWN -> "Unknown event from $who at $whenText"
        }
    }

    fun notify(type: EventType, text: String, id: Int) {
        val alarm = type.isAlarm
        val title = when (type) {
            EventType.FALL -> "Fall detected"
            EventType.SOS -> "SOS"
            else -> type.label
        }
        val n = NotificationCompat.Builder(context, if (alarm) CH_ALARM else CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(id))
            .setAutoCancel(true)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .apply { if (alarm) setFullScreenIntent(openApp(id), true) }
            .build()
        post(id, n)
    }

    fun notifyOffline(minutes: Int) {
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle("Bracelet not connected")
            .setContentText("No contact for $minutes min. It may be off, flat or out of range.")
            .setContentIntent(openApp(OFFLINE_ID))
            .setAutoCancel(true)
            .build()
        post(OFFLINE_ID, n)
    }

    fun clearOffline() = NotificationManagerCompat.from(context).cancel(OFFLINE_ID)

    // --- Caregiver mode ------------------------------------------------------------------

    /** FALL or SOS from a wearer: full-screen, rings until acknowledged (APP-15). */
    fun caregiverAlarm(e: RemoteEvent, wearer: String, escalated: Boolean) {
        val title = (if (e.type == EventType.FALL) "Fall detected" else "SOS") + " — ${wearer.ifBlank { "Wearer" }}"
        val text = (if (escalated) "Still not acknowledged. " else "") + e.message
        val open = alarmScreen(e, wearer)
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Acknowledge", ackIntent(e.id))
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT // keep ringing until acknowledged
        post(alarmId(e.id), n)
        // With the app on screen Android shows a banner instead of the full-screen alarm; open it ourselves.
        if (appInForeground()) runCatching { context.startActivity(AlarmActivity.intent(context, e, wearer)) }
    }

    /** TEST rings once, labelled as a test. */
    fun caregiverTest(e: RemoteEvent, wearer: String) {
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle("TEST alert — ${wearer.ifBlank { "Wearer" }}")
            .setContentText("This is a test. ${e.message}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(alarmId(e.id)))
            .setAutoCancel(true)
            .addAction(0, "Acknowledge", ackIntent(e.id))
            .build()
        post(alarmId(e.id), n)
    }

    /** LOW_BATTERY and OFF: a normal notification. */
    fun caregiverInfo(e: RemoteEvent, wearer: String) {
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle("${e.type.label} — ${wearer.ifBlank { "Wearer" }}")
            .setContentText(e.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message))
            .setContentIntent(openApp(INFO_BASE + (e.id % 100_000).toInt()))
            .setAutoCancel(true)
            .build()
        post(INFO_BASE + (e.id % 100_000).toInt(), n)
    }

    fun cancelCaregiverAlarm(eventId: Long) = NotificationManagerCompat.from(context).cancel(alarmId(eventId))

    fun notifyWearerOffline(wearerId: Long, name: String) {
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_bracelet)
            .setContentTitle("${name.ifBlank { "Wearer" }}'s phone is offline")
            .setContentText("No contact for 15 min. Alerts can't get through until it's back.")
            .setContentIntent(openApp(OFFLINE_BASE + wearerId.toInt()))
            .setAutoCancel(true)
            .build()
        post(OFFLINE_BASE + wearerId.toInt(), n)
    }

    fun clearWearerOffline(wearerId: Long) = NotificationManagerCompat.from(context).cancel(OFFLINE_BASE + wearerId.toInt())

    private fun appInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun alarmId(eventId: Long) = ALARM_BASE + (eventId % 100_000).toInt()

    private fun alarmScreen(e: RemoteEvent, wearer: String): PendingIntent = PendingIntent.getActivity(
        context, alarmId(e.id),
        AlarmActivity.intent(context, e, wearer),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ackIntent(eventId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, alarmId(eventId),
        Intent(context, AckReceiver::class.java).putExtra(AckReceiver.EXTRA_EVENT_ID, eventId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    private fun post(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CH_ALARM = "alarm"
        const val CH_INFO = "info"
        const val CH_SERVICE = "service"
        const val SERVICE_ID = 1
        const val OFFLINE_ID = 2
        const val CAREGIVER_SERVICE_ID = 3
        private const val ALARM_BASE = 100_000
        private const val INFO_BASE = 200_000
        private const val OFFLINE_BASE = 300_000
    }
}
