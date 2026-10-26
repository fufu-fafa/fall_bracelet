package com.fallbracelet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import com.fallbracelet.ble.EventType
import com.fallbracelet.caregiver.RemoteEvent
import com.fallbracelet.caregiver.StreamState
import com.fallbracelet.caregiver.WatchedWearer
import com.fallbracelet.data.AppMode
import com.fallbracelet.ui.theme.Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Tokens

/** First launch: is this the wearer's phone or a caregiver's? (APP-13) */
@Composable
fun ModeScreen(onChoose: (AppMode) -> Unit) {
    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScreenTitle("Welcome", "Who is this phone for? You can change it later in Settings.")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    "I wear the bracelet",
                    "This phone stays near the wearer, connects to the bracelet and passes its alerts on.",
                ) { onChoose(AppMode.WEARER) }
                ModeCard(
                    "I'm a caregiver",
                    "This phone rings when the wearer falls or presses SOS, and shows their status.",
                ) { onChoose(AppMode.CAREGIVER) }
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, color = AppTheme.extra.muted)
    }
}

/** Caregiver mode: link with an invite code, then a live dashboard per wearer (APP-14, 17). */
@Composable
fun CaregiverScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val wearers by vm.watched.collectAsState()
    val stream by vm.streamState.collectAsState()
    val error by vm.caregiverError.collectAsState()
    var linking by remember { mutableStateOf(false) }

    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenTitle("Caregiver", if (settings.caregiverName.isNotBlank()) "Signed in as ${settings.caregiverName}" else null)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupWarnings(null)
                if (settings.caregiverToken == null || linking) {
                    LinkCard(vm, settings.relayUrl, settings.caregiverName) { linking = false }
                } else {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                SectionLabel("Relay")
                                Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                            }
                            when (stream) {
                                StreamState.LIVE -> Pill("Live", Tokens.Correct, Tokens.CorrectBg)
                                StreamState.CONNECTING -> Pill("Reconnecting…", Tokens.Muted, Tokens.Border)
                                StreamState.OFF -> Pill("Off", Tokens.Wrong, Tokens.WrongBg)
                            }
                        }
                        error?.let { Text(it, color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (wearers.isEmpty()) Card { Text("Not following anyone yet.", color = AppTheme.extra.muted) }
                    wearers.forEach { WearerCard(it, vm::acknowledge) }
                    OutlinedButton(onClick = { linking = true }, modifier = Modifier.fillMaxWidth()) { Text("Link another wearer") }
                }
                OutlinedButton(onClick = { vm.setMode(AppMode.WEARER) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use this phone as the wearer's phone")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LinkCard(vm: AppViewModel, relayUrl: String, savedName: String, onDone: () -> Unit) {
    val result by vm.linkResult.collectAsState()
    var url by remember { mutableStateOf(relayUrl) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(savedName) }
    LaunchedEffect(result) { if (result?.startsWith("Linked") == true) onDone() }
    Card {
        SectionLabel("Link to a wearer")
        Text("On the wearer's phone: Settings → Invite a caregiver. Type the 6-digit code here.", color = AppTheme.extra.muted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Invite code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Your name (shown as \"Seen by …\")") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Relay address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        val finding by vm.finding.collectAsState()
        LaunchedEffect(relayUrl) { url = relayUrl } // "Find relay" updates the stored address
        OutlinedButton(onClick = vm::findRelay, enabled = !finding) { Text(if (finding) "Searching…" else "Find relay on this WiFi") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.link(url, code, name) },
            enabled = code.length == 6 && name.isNotBlank() && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Link") }
        result?.let { Text(it, color = if (it.startsWith("Linked")) Tokens.Correct else Tokens.Wrong) }
    }
}

@Composable
private fun WearerCard(w: WatchedWearer, onAck: (Long) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (w.offline) Pill("Phone offline", Tokens.Wrong, Tokens.WrongBg) else Pill("Online", Tokens.Correct, Tokens.CorrectBg)
        }
        Spacer(Modifier.height(6.dp))
        val bracelet = when (w.braceletConnected) {
            true -> "Bracelet connected"
            false -> "Bracelet not connected"
            null -> "Bracelet status unknown"
        }
        Text(
            listOfNotNull(bracelet, w.state?.lowercase()?.replace('_', ' '), w.batteryPct?.let { "battery $it%" }).joinToString(" · "),
            color = AppTheme.extra.muted,
        )
        w.lastHeartbeat?.let { Text("Last heard ${relativeTime(it)}", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall) }
        Text(
            w.lastCheckin?.let { "Last check-in ${relativeTime(it.occurredAt)}" } ?: "No check-in yet",
            color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        SectionLabel("History")
        if (w.events.isEmpty()) Text("Nothing yet.", color = AppTheme.extra.muted)
        w.events.take(20).forEachIndexed { i, e ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            RemoteEventLine(e, onAck)
        }
    }
}

@Composable
private fun RemoteEventLine(e: RemoteEvent, onAck: (Long) -> Unit) {
    val (fg, bg) = when {
        e.type.isAlarm -> Tokens.Wrong to Tokens.WrongBg
        e.type == EventType.CHECKIN || e.type == EventType.CANCELLED -> Tokens.Correct to Tokens.CorrectBg
        else -> Tokens.Muted to Tokens.Border
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.type.label, fg, bg)
            Spacer(Modifier.width(10.dp))
            Text(
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(e.message, style = MaterialTheme.typography.bodyMedium)
        if (e.ackedBy != null) {
            Text("Seen by ${e.ackedBy} ${e.ackedAt?.let { relativeTime(it) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
        } else if (e.type.isAlarm || e.type == EventType.TEST) {
            OutlinedButton(onClick = { onAck(e.id) }) { Text("Acknowledge") }
        }
    }
}
