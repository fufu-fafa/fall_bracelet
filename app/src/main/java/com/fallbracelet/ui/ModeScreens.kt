package com.fallbracelet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.fallbracelet.caregiver.HistoryMode
import com.fallbracelet.caregiver.ago
import com.fallbracelet.caregiver.dayName
import com.fallbracelet.caregiver.historyPeriod
import com.fallbracelet.caregiver.localDay
import java.time.LocalDate
import kotlinx.coroutines.delay
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

/** Caregiver mode: link with an invite code, then a live dashboard per wearer. Follows relay/web/index.html. */
@Composable
fun CaregiverScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val wearers by vm.watched.collectAsState()
    val stream by vm.streamState.collectAsState()
    val error by vm.caregiverError.collectAsState()
    var linking by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // Keeps "5 min ago" and the Today / This week periods current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val linked = settings.caregiverToken != null

    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenTitle(
                "Caregiver",
                if (linked && settings.caregiverName.isNotBlank()) "Signed in as ${settings.caregiverName}"
                else "Gets the bracelet's alarms on this phone.",
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupWarnings(null)
                if (linked) {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                SectionLabel("Relay")
                                Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                            }
                            when (stream) {
                                StreamState.LIVE -> Pill("Live", Tokens.Correct, Tokens.CorrectBg)
                                StreamState.CONNECTING -> Pill("Reconnecting…", Tokens.Muted, Tokens.Border)
                                StreamState.PUSH -> Pill("Push", Tokens.Correct, Tokens.CorrectBg)
                                StreamState.OFF -> Pill("Off", Tokens.Wrong, Tokens.WrongBg)
                            }
                        }
                        error?.let { Text(it, color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (wearers.isEmpty()) Card { Text("Not following anyone yet.", color = AppTheme.extra.muted) }
                    wearers.forEach { w -> key(w.id) { WearerCard(w, now, vm::acknowledge) } }
                }
                if (!linked || linking) {
                    LinkCard(vm, settings.relayUrl, settings.caregiverName, onCancel = if (linked) ({ linking = false }) else null) {
                        linking = false
                    }
                }
                if (linked) {
                    OutlinedButton(
                        onClick = { vm.clearLinkResult(); linking = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Link another wearer") }
                    OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out of this phone")
                    }
                }
                OutlinedButton(onClick = { vm.setMode(AppMode.WEARER) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use this phone as the wearer's phone")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = Tokens.Card,
            title = { Text("Sign out?") },
            text = { Text("Stop getting alarms on this phone?", color = AppTheme.extra.muted) },
            confirmButton = {
                TextButton(onClick = { vm.signOutCaregiver(); confirmSignOut = false }) { Text("Sign out", color = Tokens.Wrong) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LinkCard(vm: AppViewModel, relayUrl: String, savedName: String, onCancel: (() -> Unit)?, onDone: () -> Unit) {
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
            label = { Text("Invite code") }, placeholder = { Text("000000", style = CodeStyle, modifier = Modifier.fillMaxWidth()) },
            textStyle = CodeStyle, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Your name (shown as \"Seen by …\")") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        // The web page is served by the relay, so only the app has to be told where it is.
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
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
        result?.let { Text(it, color = if (it.startsWith("Linked")) Tokens.Correct else Tokens.Wrong) }
    }
}

private val CodeStyle = TextStyle(fontSize = 24.sp, fontFamily = Mono, letterSpacing = 7.sp, textAlign = TextAlign.Center)

@Composable
private fun WearerCard(w: WatchedWearer, now: Long, onAck: (Long) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(HistoryMode.DAY) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val period = historyPeriod(mode, offset, now)
    val shown = remember(w.events, period) { w.events.filter { it.occurredAt >= period.from && it.occurredAt < period.until } }
    val battery = w.batteryPct ?: w.events.firstOrNull()?.batteryPct

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.name, color = Tokens.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            when {
                w.offline -> Pill("Phone offline", Tokens.Wrong, Tokens.WrongBg)
                w.lastHeartbeat != null -> Pill("Online", Tokens.Correct, Tokens.CorrectBg)
                else -> Pill("No heartbeat yet", Tokens.Muted, Tokens.Border)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val bracelet = when (w.braceletConnected) {
                true -> "Connected"
                false -> "Not connected"
                null -> "Unknown"
            }
            StatTile("Bracelet", bracelet, Modifier.weight(1f))
            StatTile("State", w.state?.lowercase()?.replace('_', ' ') ?: "—", Modifier.weight(1f))
            StatTile("Battery", battery?.let { "$it%" } ?: "—", Modifier.weight(1f)) {
                if (battery != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Tokens.Border, RoundedCornerShape(3.dp))) {
                        Box(
                            Modifier.fillMaxWidth(battery.coerceIn(0, 100) / 100f).fillMaxHeight()
                                .background(if (battery <= 20) Tokens.Wrong else Tokens.Correct, RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            listOf(
                w.lastHeartbeat?.let { "Last heard ${ago(it, now)}" } ?: "Not heard from yet",
                w.lastCheckin?.let { "last check-in ${ago(it.occurredAt, now)}" } ?: "no check-in yet",
            ).joinToString(" · "),
            color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel("History")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMode.entries.forEach { m ->
                SmallButton(m.label, Modifier.weight(1f), selected = mode == m) {
                    mode = m
                    offset = 0
                }
            }
        }
        if (mode != HistoryMode.ALL) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("‹", Modifier.width(44.dp)) { offset-- }
                Text(
                    period.title, color = Tokens.Text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                )
                SmallButton("›", Modifier.width(44.dp), enabled = offset < 0) { offset++ }
            }
        }
        val alarms = shown.count { it.type.isAlarm }
        if (shown.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${shown.size} event${if (shown.size > 1) "s" else ""}" + if (alarms > 0) ", $alarms alarm(s)" else "",
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(if (w.events.isEmpty()) "Nothing yet." else "Nothing in this period.", color = AppTheme.extra.muted)
        }
        val today = localDay(now)
        var lastDay: LocalDate? = null
        shown.forEach { e ->
            val day = localDay(e.occurredAt)
            if (mode != HistoryMode.DAY && day != lastDay) {
                Text(
                    dayName(day, today), color = AppTheme.extra.muted, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
                )
            } else if (lastDay != null) {
                HorizontalDivider(Modifier.padding(top = 10.dp), color = Tokens.Border)
            }
            lastDay = day
            RemoteEventLine(e, showDate = mode != HistoryMode.DAY, now, onAck)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxHeight()
            .background(Tokens.Bg, RoundedCornerShape(10.dp))
            .border(1.dp, Tokens.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(label, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        // One line per word, so a long single word shrinks instead of breaking mid-word.
        BasicText(
            value,
            style = TextStyle(color = Tokens.Text, fontWeight = FontWeight.Bold),
            maxLines = value.count { it == ' ' } + 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp),
        )
        extra()
    }
}

@Composable
private fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) Tokens.Accent else Tokens.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Tokens.Accent else Color.Transparent,
            contentColor = if (selected) Tokens.AccentText else Tokens.Text,
        ),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) { Text(text) }
}

@Composable
private fun RemoteEventLine(e: RemoteEvent, showDate: Boolean, now: Long, onAck: (Long) -> Unit) {
    val (fg, bg) = when {
        e.type.isAlarm -> Tokens.Wrong to Tokens.WrongBg
        e.type == EventType.CHECKIN || e.type == EventType.CANCELLED -> Tokens.Correct to Tokens.CorrectBg
        else -> Tokens.Muted to Tokens.Border
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.type.label, fg, bg)
            Spacer(Modifier.width(10.dp))
            Text(
                SimpleDateFormat(if (showDate) "d MMM, HH:mm" else "HH:mm", Locale.getDefault()).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (e.message.isNotEmpty()) Text(e.message, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
        if (e.ackedBy != null) {
            Text("Seen by ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
        } else if (e.type.isAlarm || e.type == EventType.TEST) {
            OutlinedButton(
                onClick = { onAck(e.id) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.Text),
                border = BorderStroke(1.dp, Tokens.Border),
            ) { Text("Acknowledge") }
        }
    }
}
