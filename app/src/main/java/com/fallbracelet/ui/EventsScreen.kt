package com.fallbracelet.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fallbracelet.ble.EventType
import com.fallbracelet.data.EventEntity
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventsScreen(vm: AppViewModel) {
    val events by vm.events.collectAsState()
    Column {
        ScreenTitle("Events", "Everything the bracelet reported, newest first.")
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (events.isEmpty()) item {
                Card { Text("No events yet. Use “Send test alert” on Home to try the path end to end.", color = AppTheme.extra.muted) }
            }
            items(events, key = { it.id }) { e -> Card { EventLine(e, full = true) } }
        }
    }
}

@Composable
fun EventLine(e: EventEntity, full: Boolean = false) {
    val type = runCatching { EventType.valueOf(e.type) }.getOrDefault(EventType.UNKNOWN)
    val (fg, bg) = when (type) {
        EventType.FALL, EventType.SOS -> Tokens.Wrong to Tokens.WrongBg
        EventType.CANCELLED, EventType.CHECKIN, EventType.ON -> Tokens.Correct to Tokens.CorrectBg
        else -> Tokens.Muted to Tokens.Border
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(type.label, fg, bg)
            Spacer(Modifier.size(10.dp))
            Text(
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(e.message, style = MaterialTheme.typography.bodyMedium)
        if (full) {
            if (e.receivedAt - e.occurredAt > 60_000) {
                Text("Received ${relativeTime(e.receivedAt)} (delivered late)", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun relativeTime(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
