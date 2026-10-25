package com.fallbracelet.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fallbracelet.ble.LinkState
import com.fallbracelet.ui.theme.AppTheme
import com.fallbracelet.ui.theme.Mono
import com.fallbracelet.ui.theme.Tokens
import java.io.File

/** Raw-data view for tuning: live SVM against the default thresholds, plus labelled CSV recordings. */
@Composable
fun LiveScreen(vm: AppViewModel) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val svm by vm.svm.collectAsState()
    val recording by vm.recording.collectAsState()
    val recordings by vm.recordings.collectAsState()
    val streaming = status?.streaming == true
    var label by rememberSaveable { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenTitle("Live data", "Signal vector magnitude, 100 Hz, last 3 s.")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stream accelerometer", style = MaterialTheme.typography.titleMedium)
                        Text("Uses more battery — turn off after testing.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = streaming,
                        onCheckedChange = vm::setStreaming,
                        enabled = link == LinkState.CONNECTED,
                        colors = SwitchDefaults.colors(checkedTrackColor = Tokens.Accent),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SvmChart(svm)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("now ", color = AppTheme.extra.muted)
                    Text(svm.lastOrNull()?.let { "%.2f g".format(it) } ?: "—", fontFamily = Mono)
                    Spacer(Modifier.weight(1f))
                    Text("peak ", color = AppTheme.extra.muted)
                    Text(svm.maxOrNull()?.let { "%.2f g".format(it) } ?: "—", fontFamily = Mono)
                }
                Text(
                    "Lines: free fall 0.5 g · impact 2.5 g · impact without free fall 3.5 g",
                    color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
                )
            }

            Card {
                SectionLabel("Record a trial")
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label, e.g. fall_forward_left") },
                    singleLine = true, enabled = recording == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (recording == null) {
                    Button(
                        onClick = { if (!streaming) vm.setStreaming(true); vm.startRecording(label) },
                        enabled = link == LinkState.CONNECTED,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start recording") }
                } else {
                    Button(
                        onClick = vm::stopRecording,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Wrong),
                    ) { Text("Stop · ${recording!!.name}") }
                }
            }

            if (recordings.isNotEmpty()) Card {
                SectionLabel("Recordings")
                recordings.forEach { f -> RecordingRow(f, onDelete = { vm.deleteRecording(f) }) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SvmChart(values: FloatArray) {
    val maxG = 5f
    Canvas(
        Modifier.fillMaxWidth().height(180.dp)
            .background(AppTheme.extra.codeBg, RoundedCornerShape(8.dp))
    ) {
        fun y(g: Float) = size.height * (1 - (g / maxG).coerceIn(0f, 1f))
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        listOf(0.5f to Tokens.Accent, 2.5f to Tokens.Wrong, 3.5f to Tokens.Wrong).forEach { (g, c) ->
            drawLine(c.copy(alpha = 0.6f), Offset(0f, y(g)), Offset(size.width, y(g)), 2f, pathEffect = dash)
        }
        drawLine(Tokens.Border, Offset(0f, y(1f)), Offset(size.width, y(1f)), 1f)
        if (values.size < 2) return@Canvas
        val step = size.width / (AppViewModel.WINDOW - 1)
        val x0 = size.width - (values.size - 1) * step
        val path = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x0, y(v)) else lineTo(x0 + i * step, y(v)) }
        }
        drawPath(path, Tokens.Text, style = Stroke(width = 3f))
    }
}

@Composable
private fun RecordingRow(f: File, onDelete: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            Text("${f.length() / 1024} KB", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, f.name))
        }) { Icon(Icons.Filled.Share, "Share", tint = Tokens.Accent) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = AppTheme.extra.muted) }
    }
}
