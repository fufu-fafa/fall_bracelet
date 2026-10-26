package com.fallbracelet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fallbracelet.manager
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Something that stops alerts getting out, with the fix if the app can offer one. */
data class SetupProblem(val text: String, val fixLabel: String? = null, val fix: ((Context) -> Unit)? = null)

/** What the wearer phone last learned from the relay. */
data class RelayHealth(val url: String, val registered: Boolean, val error: String?, val caregivers: Int?)

fun setupProblems(context: Context, relay: RelayHealth?): List<SetupProblem> = buildList {
    if (relay != null && !context.manager.canConnect()) {
        add(SetupProblem("Nearby devices permission is off, so the app can't reach the bracelet.", "Open app settings", ::openAppSettings))
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        add(SetupProblem("Notifications are off, so alarms won't show on this phone.", "Turn on notifications", ::openNotificationSettings))
    }
    val power = context.getSystemService(PowerManager::class.java)
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
        add(SetupProblem("Battery optimisation can stop the app in the background, so alerts can be missed.", "Allow background use", ::requestBatteryExemption))
    }
    if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
        add(SetupProblem("Full-screen alarms are blocked, so a fall only shows as a notification.", "Allow full-screen alarms", ::openFullScreenSettings))
    }
    if (!hasInternet(context)) {
        add(SetupProblem("No internet connection, so alerts can't reach caregivers."))
    }
    when {
        relay == null -> Unit // caregiver phone: its own status card covers the relay
        relay.url.isBlank() -> add(SetupProblem("No relay address set in Settings, so alerts stay on this phone."))
        relay.error != null -> add(SetupProblem("Can't reach the relay (${relay.error}). Events wait on this phone and go out when it's back."))
        !relay.registered -> add(SetupProblem("Connecting to the relay…"))
        relay.caregivers == 0 -> add(SetupProblem("No caregiver is linked yet, so nobody else gets alerts. Invite one in Settings."))
    }
}

private fun openFullScreenSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
    }.onFailure { openAppSettings(context) }
}

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

// The whole point of the app is to run in the background, which is the permitted use case.
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

/** Lists what stops alerts getting out (APP-10). Re-checked on resume and every 15 s. Null relay = caregiver phone. */
@Composable
fun SetupWarnings(relay: RelayHealth?) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); tick++ }
    }
    val problems = remember(tick, relay) { setupProblems(context, relay) }
    if (problems.isEmpty()) return

    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
        SectionLabel("Alerts may not get through")
        problems.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Text(p.text, style = MaterialTheme.typography.bodyMedium)
            if (p.fix != null) {
                OutlinedButton(onClick = { p.fix.invoke(context) }) { Text(p.fixLabel ?: "Fix") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("These warnings clear by themselves once fixed.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
    }
}
