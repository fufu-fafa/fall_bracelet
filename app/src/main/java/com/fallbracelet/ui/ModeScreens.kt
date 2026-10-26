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
import com.fallbracelet.data.AppMode
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

/** Caregiver mode needs the relay, which this build doesn't have yet. */
@Composable
fun CaregiverScreen(onUseAsWearer: () -> Unit) {
    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScreenTitle("Caregiver")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
                    Text("Not connected to the relay yet", color = Tokens.Wrong, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Caregiver alarms, linking and the wearer's dashboard arrive through the relay, " +
                            "which isn't set up in this build. This phone won't ring for anyone yet.",
                    )
                }
                OutlinedButton(onClick = onUseAsWearer, modifier = Modifier.fillMaxWidth()) {
                    Text("Use this phone as the wearer's phone")
                }
            }
        }
    }
}
