package com.fallbracelet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fallbracelet.ble.FoundDevice
import com.fallbracelet.data.AppMode
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Mono
import com.fallbracelet.ui.theme.Tokens

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Events("Events", Icons.AutoMirrored.Filled.List),
    Live("Live", Icons.Filled.PlayArrow),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun App(vm: AppViewModel, enableBluetooth: () -> Unit) {
    val settings by vm.settings.collectAsState()
    when (settings.mode) {
        null -> { ModeScreen(vm::setMode); return }
        AppMode.CAREGIVER -> { CaregiverScreen { vm.setMode(AppMode.WEARER) }; return }
        AppMode.WEARER -> Unit
    }
    if (settings.deviceAddress == null) {
        PairScreen(vm, enableBluetooth)
        return
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    Scaffold(
        containerColor = Tokens.Bg,
        bottomBar = {
            NavigationBar(containerColor = Tokens.Card) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Tokens.Accent,
                            selectedTextColor = Tokens.Accent,
                            indicatorColor = Tokens.Border,
                            unselectedIconColor = Tokens.Muted,
                            unselectedTextColor = Tokens.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(vm)
                Tab.Events -> EventsScreen(vm)
                Tab.Live -> LiveScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairScreen(vm: AppViewModel, enableBluetooth: () -> Unit) {
    val found by vm.found.collectAsState()
    val error by vm.scanError.collectAsState()
    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }
    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScreenTitle("Pair a bracelet", "Switch the bracelet on (hold the button 3 s) and keep it near the phone.")
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) item {
                    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
                        Text(error!!, color = Tokens.Wrong)
                        Text("Turn Bluetooth on and allow the Nearby devices permission.", color = AppTheme.extra.muted)
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = enableBluetooth) { Text("Turn on Bluetooth") }
                    }
                }
                if (found.isEmpty() && error == null) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tokens.Accent)
                        Spacer(Modifier.size(12.dp))
                        Text("Searching…", color = AppTheme.extra.muted)
                    }
                }
                items(found, key = { it.address }) { d -> DeviceRow(d) { vm.pair(d) } }
            }
            OutlinedButton(onClick = vm::startScan, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Search again")
            }
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(d.address, color = AppTheme.extra.muted, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            }
            Text("${d.rssi} dBm", color = AppTheme.extra.muted, fontFamily = Mono)
            Spacer(Modifier.size(12.dp))
            Button(onClick = onClick) { Text("Pair") }
        }
    }
}
