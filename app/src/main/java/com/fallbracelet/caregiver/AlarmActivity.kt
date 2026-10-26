package com.fallbracelet.caregiver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fallbracelet.caregiver
import com.fallbracelet.ui.theme.FallBraceletTheme
import com.fallbracelet.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen FALL / SOS alarm shown over the lock screen until the caregiver acknowledges it. */
class AlarmActivity : ComponentActivity() {
    private data class Shown(val id: Long, val title: String, val message: String, val at: Long)

    private var shown by mutableStateOf(Shown(-1, "", "", 0))

    private fun show(i: Intent) {
        shown = Shown(i.getLongExtra(EXTRA_ID, -1), i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getStringExtra(EXTRA_MESSAGE).orEmpty(), i.getLongExtra(EXTRA_AT, 0))
    }

    /** A newer alarm while this one is open replaces it; the older one keeps ringing in the shade. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(intent)
        setContent {
            val (id, title, message, at) = shown
            FallBraceletTheme {
                Column(
                    Modifier.fillMaxSize().background(Tokens.WrongBg).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(title, color = Tokens.Wrong, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at)), color = Tokens.Text)
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = Tokens.Text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { caregiver.acknowledge(id); finish() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Wrong, contentColor = Tokens.AccentText),
                    ) { Text("Acknowledge — I'm on it", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_AT = "at"

        fun intent(context: Context, e: RemoteEvent, wearer: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, e.id)
                .putExtra(EXTRA_TITLE, "${if (e.type.name == "FALL") "Fall detected" else "SOS"} — ${wearer.ifBlank { "Wearer" }}")
                .putExtra(EXTRA_MESSAGE, e.message)
                .putExtra(EXTRA_AT, e.occurredAt)
    }
}
