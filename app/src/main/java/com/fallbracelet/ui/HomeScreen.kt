package com.fallbracelet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fallbracelet.ble.BraceletState
import com.fallbracelet.ble.BraceletStatus
import com.fallbracelet.ble.Command
import com.fallbracelet.ble.LinkState
import com.fallbracelet.data.EventEntity
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Mono
import com.fallbracelet.ui.theme.Tokens

@Composable
fun HomeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val events by vm.events.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        val who = settings.wearerName.ifBlank { null }?.let { "$it's bracelet" } ?: (settings.deviceName ?: "Bracelet")
        ScreenTitle(who)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkCard(link, settings.deviceName, settings.lastSeenAt)
            status?.let { s ->
                if (s.state == BraceletState.COUNTDOWN) CountdownCard(s) { vm.command(Command.CANCEL) }
                StateCard(s)
                BatteryCard(s)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.command(Command.FIND) },
                    enabled = link == LinkState.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) { Text("Find (buzz)") }
                OutlinedButton(
                    onClick = { vm.command(Command.TEST) },
                    enabled = link == LinkState.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) { Text("Send test alert") }
            }
            RecentCard(events.take(4))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LinkCard(link: LinkState, name: String?, lastSeenAt: Long) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Bluetooth")
                Text(name ?: "Bracelet", style = MaterialTheme.typography.titleMedium)
                if (link != LinkState.CONNECTED && lastSeenAt > 0) {
                    Text("Last seen ${relativeTime(lastSeenAt)}", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (link) {
                LinkState.CONNECTED -> Pill("Connected", Tokens.Correct, Tokens.CorrectBg)
                LinkState.CONNECTING -> Pill("Searching…", Tokens.Muted, Tokens.Border)
                LinkState.DISCONNECTED -> Pill("Disconnected", Tokens.Wrong, Tokens.WrongBg)
            }
        }
    }
}

@Composable
private fun CountdownCard(s: BraceletStatus, onCancel: () -> Unit) {
    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
        Text("Fall detected", color = Tokens.Wrong, style = MaterialTheme.typography.titleLarge)
        Text(
            "The alert goes out in ${s.countdownRemaining} s unless it's cancelled.",
            color = Tokens.Text,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.Wrong, contentColor = Tokens.AccentText),
        ) { Text("I'm OK — cancel the alarm") }
    }
}

@Composable
private fun StateCard(s: BraceletStatus) {
    val (fg, bg) = when (s.state) {
        BraceletState.MONITORING -> Tokens.Correct to Tokens.CorrectBg
        BraceletState.COUNTDOWN, BraceletState.SENDING_ALERT -> Tokens.Wrong to Tokens.WrongBg
        else -> Tokens.Muted to Tokens.Border
    }
    Card {
        SectionLabel("State")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.state.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Pill(if (s.state == BraceletState.MONITORING) "Protected" else s.state.label, fg, bg)
        }
        Spacer(Modifier.height(6.dp))
        Text(stateHint(s.state), color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium)
        if (s.pendingEvents > 0 || !s.clockSet) {
            Spacer(Modifier.height(8.dp))
            if (s.pendingEvents > 0) Text("${s.pendingEvents} event(s) waiting to sync", color = AppTheme.extra.muted)
            if (!s.clockSet) Text("Bracelet clock not set yet", color = AppTheme.extra.muted)
        }
    }
}

private fun stateHint(s: BraceletState) = when (s) {
    BraceletState.MONITORING -> "Worn and watching for falls."
    BraceletState.IDLE -> "No movement for a while — sleeping until it moves."
    BraceletState.COUNTDOWN -> "Buzzing for the cancel window."
    BraceletState.SENDING_ALERT -> "Delivering an alert to this phone."
    BraceletState.OFF -> "Switched off. Hold the button 3 s to turn it on."
    BraceletState.UNKNOWN -> "Unrecognised state — update the app or firmware."
}

@Composable
private fun BatteryCard(s: BraceletStatus) {
    val color = when {
        s.lowBattery || s.batteryPct <= 20 -> Tokens.Wrong
        else -> Tokens.Correct
    }
    Card {
        SectionLabel("Battery")
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.batteryPct}%", style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
            Spacer(Modifier.weight(1f))
            Text("%.2f V".format(s.batteryMv / 1000f), color = AppTheme.extra.muted, fontFamily = Mono)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(Tokens.Border, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(s.batteryPct.coerceIn(0, 100) / 100f).fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Voltage estimate; reads high while charging.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecentCard(events: List<EventEntity>) {
    Card {
        SectionLabel("Recent events")
        if (events.isEmpty()) {
            Text("Nothing yet.", color = AppTheme.extra.muted)
        }
        events.forEachIndexed { i, e ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            EventLine(e)
        }
    }
}
