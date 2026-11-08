package de.kf.tapstop

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Only use with the explicitly prepared disposable browser textarea. No STT/network/audio. */
internal fun Instrumentation.runDelayedDiagnosticChecks() {
    try {
        sendStatus(1, Bundle().apply { putString("stream", "READY_ACCESSIBILITY\n") })
        Thread.sleep(1500)
        val deadline = System.currentTimeMillis() + 20000
        while (TextInsertService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val service = checkNotNull(TextInsertService.instance)
        val text = "Diagnoseprobe: Äpfel, Öl, süß und Straße. 😀 "
        var trace: DelayedInsertTrace? = null
        runOnMainSync {
            DelayedInsertDiagnostics.setEnabled(targetContext, true)
            service.rememberFocusedField()
            check(service.statisticsTargetPackage() == "com.android.chrome") { "Disposable Chrome field required" }
            val attempt = InsertDiagnostics.begin(text.length, null)!!
            trace = DelayedInsertDiagnostics.begin(targetContext, text, text, text)
            attempt.delayed = trace
            check(service.insert(text, attempt))
            InsertDiagnostics.finish(targetContext, attempt, false, null)
            DelayedInsertDiagnostics.finish(targetContext, trace, JSONObject(attempt.json(false, null)).getString("outcome"))
        }
        Thread.sleep(4500)
        check(DelayedInsertDiagnostics.awaitWriter())
        val rows = File(targetContext.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).listFiles()!!
            .filter { it.name.endsWith(".jsonl") }.flatMap { it.readLines() }.map { JSONObject(it) }
            .filter { it.optString("insert_id") == trace!!.id }
        check(rows.any { it.optString("status") == "IMMEDIATE_CONFIRMED" }) { "No immediate confirmation" }
        val delayed = rows.filter { it.optString("event") == "DELAYED_READBACK" }
        check(delayed.size == 3) { "Missing delayed events" }
        check(delayed.all { it.optString("status") == "DELAYED_CONFIRMED" }) {
            "Delayed statuses: " + delayed.joinToString { it.optString("status") + ":" + it.optString("reason") }
        }
        runOnMainSync { DelayedInsertDiagnostics.mark(targetContext) }
        check(DelayedInsertDiagnostics.awaitWriter())
        val marker = File(targetContext.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).listFiles()!!
            .filter { it.name.endsWith(".jsonl") }.flatMap { it.readLines() }.map { JSONObject(it) }
            .last { it.optString("event") == "MANUAL_ERROR_MARKER" }
        check(marker.getString("associated_insert_id") == trace!!.id)
        check(!marker.has("editor") && !marker.has("raw_transcript"))
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: immediate + 250/1000/3000 ms confirmed; Unicode; manual marker associated; insert_id=${trace!!.id}\n") })
    } catch (_: Exception) {
        // Never print editor text, transcript or exception messages from a device.
        finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: delayed diagnostic checks; inspect metadata-only export\n") })
    }
}
