package com.fallbracelet.service

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
import com.fallbracelet.ble.LinkState
import com.fallbracelet.manager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process (and so the BLE link) alive in the background, with a status notification. */
class BraceletService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            ServiceCompat.startForeground(
                this, AlertDispatcher.SERVICE_ID, notification("Connecting…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Missing Bluetooth permission or a background-start restriction.
            Log.w("BraceletService", "Could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val c = manager.connection
            lifecycleScope.launch {
                combine(c.link, c.status) { link, status ->
                    when {
                        link == LinkState.CONNECTED && status != null -> "${status.state.label} · battery ${status.batteryPct}%"
                        link == LinkState.CONNECTED -> "Connected"
                        else -> "Looking for the bracelet…"
                    }
                }.collect { text ->
                    @Suppress("MissingPermission")
                    runCatching { NotificationManagerCompat.from(this@BraceletService).notify(AlertDispatcher.SERVICE_ID, notification(text)) }
                }
            }
        }
        return START_STICKY
    }

    private var started = false

    private fun notification(text: String) = NotificationCompat.Builder(this, AlertDispatcher.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_bracelet)
        .setContentTitle("Fall bracelet")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                this, 0, Intent(this, com.fallbracelet.ui.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BraceletService::class.java)) }
                .onFailure { Log.w("BraceletService", "start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BraceletService::class.java))
        }
    }
}

/** Creating the Application on boot starts the manager, which reconnects and starts the service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.manager.retryConnect()
    }
}
