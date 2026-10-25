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
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fallbracelet.R
import com.fallbracelet.ble.BraceletEvent
import com.fallbracelet.ble.EventType
import com.fallbracelet.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns bracelet events into phone notifications and caregiver texts. */
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

    /** The caregiver message, in the spec's wording. */
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

    fun shouldText(type: EventType, includeCheckins: Boolean): Boolean = when (type) {
        EventType.FALL, EventType.SOS, EventType.LOW_BATTERY, EventType.OFF, EventType.TEST -> true
        EventType.CHECKIN -> includeCheckins
        else -> false
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

    /** Returns true when the text was handed to the SMS app for every number. */
    fun sendSms(numbers: List<String>, text: String): Boolean {
        if (numbers.isEmpty()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            numbers.forEach { sms.sendMultipartTextMessage(it, null, parts, null, null) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "SMS failed", e)
            false
        }
    }

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
        private const val TAG = "AlertDispatcher"
    }
}
