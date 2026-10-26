package com.fallbracelet.caregiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fallbracelet.R
import com.fallbracelet.alert.AlertDispatcher
import com.fallbracelet.caregiver
import com.fallbracelet.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the caregiver's live stream to the relay open in the background, like a messaging app. */
class CaregiverService : LifecycleService() {
    private var started = false

    override fun onDestroy() {
        super.onDestroy()
        NotificationManagerCompat.from(this).cancel(AlertDispatcher.CAREGIVER_SERVICE_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            ServiceCompat.startForeground(
                this, AlertDispatcher.CAREGIVER_SERVICE_ID, notification("Connecting to the relay…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val c = caregiver
            lifecycleScope.launch {
                combine(c.stream, c.wearers) { s, w ->
                    val names = w.joinToString { it.name }.ifEmpty { "nobody yet" }
                    when (s) {
                        StreamState.LIVE -> "Watching $names"
                        else -> "Reconnecting to the relay…"
                    }
                }.collect { text ->
                    @Suppress("MissingPermission")
                    runCatching { NotificationManagerCompat.from(this@CaregiverService).notify(AlertDispatcher.CAREGIVER_SERVICE_ID, notification(text)) }
                }
            }
        }
        return START_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, AlertDispatcher.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_bracelet)
        .setContentTitle("Fall bracelet caregiver")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val TAG = "CaregiverService"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CaregiverService::class.java)) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaregiverService::class.java))
        }
    }
}

/** The Acknowledge button on an alarm notification. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        if (id >= 0) context.caregiver.acknowledge(id)
    }

    companion object {
        const val EXTRA_EVENT_ID = "eventId"
    }
}
