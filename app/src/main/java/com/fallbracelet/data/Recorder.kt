package com.fallbracelet.data

import android.content.Context
import com.fallbracelet.ble.AccelSample
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the raw 100 Hz stream as CSV for the spec's test plan (step 1: logging firmware).
 * One file per trial, labelled, so recordings can be replayed through the detector on a laptop.
 */
class Recorder(context: Context) {
    val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    private var out: BufferedWriter? = null
    private var sampleIndex = 0L
    var current: File? = null
        private set

    @Synchronized
    fun start(label: String): File {
        stop()
        val safe = label.trim().ifEmpty { "trial" }.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "${stamp}_$safe.csv")
        out = file.bufferedWriter().apply { write("t_ms,ax_g,ay_g,az_g,svm_g,label\n") }
        current = file
        sampleIndex = 0
        labelText = safe
        return file
    }

    private var labelText = ""

    @Synchronized
    fun append(samples: List<AccelSample>) {
        val w = out ?: return
        for (s in samples) {
            // 100 Hz, so each sample is 10 ms after the previous one.
            w.write("${sampleIndex * 10},${s.x},${s.y},${s.z},${"%.3f".format(Locale.US, s.svm)},$labelText\n")
            sampleIndex++
        }
    }

    @Synchronized
    fun stop() {
        out?.close()
        out = null
        current = null
    }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "csv" }.orEmpty().sortedByDescending { it.name }
}
