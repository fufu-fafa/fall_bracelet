package com.fallbracelet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fallbracelet.ble.BraceletConfig
import com.fallbracelet.ble.Command
import com.fallbracelet.ble.LinkState
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Mono
import com.fallbracelet.ui.theme.Tokens

@Composable
fun SettingsScreen(vm: AppViewModel, requestSms: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val config by vm.config.collectAsState()
    val saveResult by vm.saveResult.collectAsState()
    val connected = link == LinkState.CONNECTED

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BraceletSettings(config, connected, saveResult, onSave = vm::saveConfig, onShown = vm::clearSaveResult)

            Card {
                SectionLabel("Caregivers")
                var numbers by rememberSaveable(settings.caregivers) { mutableStateOf(settings.caregivers.joinToString("\n")) }
                OutlinedTextField(
                    value = numbers,
                    onValueChange = { numbers = it },
                    label = { Text("Phone numbers, one per line") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.setCaregivers(numbers) }) { Text("Save numbers") }
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Text caregivers",
                    "FALL, SOS, low battery, switched off and test alerts go out by SMS from this phone.",
                    settings.smsEnabled,
                ) { on -> if (on) requestSms(); vm.setSms(on) }
                ToggleRow(
                    "Also text daily check-ins",
                    "If a check-in stops arriving, the bracelet is off, flat or out of range.",
                    settings.smsCheckins,
                    enabled = settings.smsEnabled,
                    onChange = vm::setSmsCheckins,
                )
            }

            Card {
                SectionLabel("Out-of-range warning")
                Text("Notify this phone when the bracelet hasn't connected for:", color = AppTheme.extra.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Never", 15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (min, text) ->
                        FilterChip(
                            selected = settings.offlineAlertMin == min,
                            onClick = { vm.setOfflineAlert(min) },
                            label = { Text(text) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Tokens.Accent,
                                selectedLabelColor = Tokens.AccentText,
                            ),
                        )
                    }
                }
            }

            DeviceActions(vm, connected, settings.deviceName, settings.deviceAddress)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BraceletSettings(
    config: BraceletConfig?,
    connected: Boolean,
    saveResult: String?,
    onSave: (BraceletConfig) -> Unit,
    onShown: () -> Unit,
) {
    val c = config ?: BraceletConfig()
    var name by remember(config) { mutableStateOf(c.wearerName) }
    var countdown by remember(config) { mutableIntStateOf(c.countdownS) }
    var hour by remember(config) { mutableStateOf(c.checkinHour.toString()) }
    var minute by remember(config) { mutableStateOf("%02d".format(c.checkinMinute)) }

    Card {
        SectionLabel("On the bracelet")
        if (!connected) {
            Text("Connect to the bracelet to change these.", color = AppTheme.extra.muted)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = name, onValueChange = { if (it.toByteArray().size <= 20) name = it },
            label = { Text("Wearer's name (used in messages)") },
            singleLine = true, enabled = connected, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel window", modifier = Modifier.weight(1f))
            Text("$countdown s", fontFamily = Mono)
        }
        Slider(
            value = countdown.toFloat(), onValueChange = { countdown = it.toInt() },
            valueRange = 5f..60f, steps = 10, enabled = connected,
            colors = SliderDefaults.colors(thumbColor = Tokens.Accent, activeTrackColor = Tokens.Accent),
        )
        Text("Longer means fewer false alerts but slower help.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text("Daily check-in time")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hour, onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                singleLine = true, enabled = connected, modifier = Modifier.width(72.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(" : ", fontFamily = Mono)
            OutlinedTextField(
                value = minute, onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                singleLine = true, enabled = connected, modifier = Modifier.width(72.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                onSave(
                    c.copy(
                        wearerName = name.trim(),
                        countdownS = countdown,
                        checkinHour = (hour.toIntOrNull() ?: 8).coerceIn(0, 23),
                        checkinMinute = (minute.toIntOrNull() ?: 0).coerceIn(0, 59),
                    )
                )
            },
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save to bracelet") }
        if (saveResult != null) {
            Text(saveResult, color = if (saveResult.startsWith("Saved")) Tokens.Correct else Tokens.Wrong)
            LaunchedEffect(saveResult) {
                kotlinx.coroutines.delay(3_000)
                onShown()
            }
        }
    }
}

@Composable
private fun DeviceActions(vm: AppViewModel, connected: Boolean, name: String?, address: String?) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Card {
        SectionLabel("Device")
        Text(name ?: "Bracelet", style = MaterialTheme.typography.titleMedium)
        Text(address ?: "", color = AppTheme.extra.muted, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { confirm = "off" }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            Text("Switch bracelet off")
        }
        OutlinedButton(onClick = { confirm = "history" }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear event history")
        }
        OutlinedButton(onClick = { confirm = "forget" }, modifier = Modifier.fillMaxWidth()) {
            Text("Forget this bracelet", color = Tokens.Wrong)
        }
    }

    val (title, body, action) = when (confirm) {
        "off" -> Triple("Switch off?", "The bracelet stops monitoring and tells the caregivers it's off.", { vm.command(Command.POWER_OFF); Unit })
        "history" -> Triple("Clear history?", "Deletes the event list on this phone only.", { vm.clearHistory(); Unit })
        "forget" -> Triple("Forget bracelet?", "This phone stops receiving its alerts until you pair again.", { vm.forget(); Unit })
        else -> return
    }
    AlertDialog(
        onDismissRequest = { confirm = null },
        containerColor = Tokens.Card,
        title = { Text(title) },
        text = { Text(body, color = AppTheme.extra.muted) },
        confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Confirm", color = Tokens.Wrong) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Tokens.Text else Tokens.Muted)
            Text(subtitle, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Tokens.Accent),
        )
    }
}
