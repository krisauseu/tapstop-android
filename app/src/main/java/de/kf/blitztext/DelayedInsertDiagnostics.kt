package de.kf.blitztext

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.SurroundingText
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Temporary opt-in text recorder. Nothing here receives audio, credentials or exception messages. */
internal class DelayedInsertTrace(val id: String = UUID.randomUUID().toString(), val text: String,
    val started: Long = SystemClock.elapsedRealtime(), val timestamp: Long = System.currentTimeMillis()) {
    @Volatile var targetPackage: String? = null
    @Volatile var path: String = "UNKNOWN"
    @Volatile var immediateConfirmed = false
    @Volatile var insertionStart: Int? = null
    @Volatile var baseline: EditorReadback? = null
    var session = 0L
    var focusEpoch = 0L
    var insertEpoch = 0L
}

internal data class EditorReadback(val text: String, val start: Int, val end: Int, val offset: Int) {
    companion object {
        @android.annotation.TargetApi(31)
        fun from(value: SurroundingText?) = value?.let { EditorReadback(it.text.toString(), it.selectionStart, it.selectionEnd, it.offset) }
    }
    fun json() = JSONObject().apply {
        put("text", text); put("selection_start", start); put("selection_end", end); put("offset", offset)
        put("returned_chars", text.length)
        // Android does not promise the whole editor, even when the requested range is large.
        put("scope", "SURROUNDING_TEXT_WINDOW"); put("whole_editor_verified", false)
    }
}

internal object DelayedComparison {
    fun compare(trace: DelayedInsertTrace, read: EditorReadback?): JSONObject {
        val result = JSONObject().put("status", "DELAYED_NOT_VERIFIABLE")
        if (read == null) return result.put("status", "DELAYED_CONNECTION_UNAVAILABLE").put("reason", "NULL_READBACK_UNSUPPORTED_INVALID_OR_TIMEOUT")
        val absoluteStart = trace.insertionStart ?: return result.put("reason", "NO_IMMEDIATE_ABSOLUTE_ANCHOR")
        if (!trace.immediateConfirmed || read.offset < 0) return result.put("reason", "UNCONFIRMED_OR_UNKNOWN_OFFSET")
        val from = absoluteStart - read.offset
        if (from < 0 || from > read.text.length) return result.put("reason", "INSERT_START_OUTSIDE_WINDOW")
        // Existing prefix must agree where both windows overlap. Changed earlier text invalidates offsets.
        val baseline = trace.baseline ?: return result.put("reason", "NO_BASELINE")
        val overlap = maxOf(read.offset, baseline.offset)
        if (overlap < absoluteStart) {
            val a = baseline.text.substring(overlap - baseline.offset, absoluteStart - baseline.offset)
            val b = read.text.substring(overlap - read.offset, from)
            if (a != b) return result.put("reason", "PREFIX_CHANGED_OFFSETS_UNCERTAIN")
        }
        val available = minOf(trace.text.length, read.text.length - from)
        val observed = read.text.substring(from, from + available)
        val first = (0 until available).firstOrNull { observed[it] != trace.text[it] }
        result.put("checked_chars", available).put("insert_start_utf16", absoluteStart)
        if (first != null) {
            if (absoluteStart > 0 && overlap >= absoluteStart) return result.put("reason", "NO_PREFIX_ANCHOR_RANGE_ALIGNMENT_UNCERTAIN")
            // This proves a difference in returned characters, never its author or cause.
            result.put("status", "DELAYED_TEXT_MISMATCH").put("first_difference_utf16", first)
                .put("reason", "OBSERVED_DIFFERENCE_MANUAL_IME_OR_APP_CHANGE_POSSIBLE")
                .put("cause", "UNKNOWN").put("proven_tapstop_text_loss", false)
        } else if (available == trace.text.length) {
            result.put("status", "DELAYED_CONFIRMED").put("reason", "INSERT_RANGE_EXACT")
        }
        else result.put("reason", "RETURNED_WINDOW_TOO_SHORT_NO_DELETION_PROOF")
        return result
    }
}

/** Small atomic segments bound write amplification, heap use and private disk consumption. */
internal class DelayedDiagnosticStore(private val directory: File, private val segmentBytes: Int = 2 * 1024 * 1024,
    private val segments: Int = 16, private val ageMs: Long = 14L * 24 * 60 * 60 * 1000) {
    @Synchronized fun append(line: String, now: Long) {
        directory.mkdirs()
        // Recover AtomicFile backups and remove abandoned .new files after process death.
        // Otherwise repeated interrupted rotations could escape the total disk bound.
        val candidates = directory.listFiles { file -> file.name.matches(Regex("events-[0-9]+\\.jsonl(\\.new|\\.bak)?")) }!!
            .map { File(directory, it.name.removeSuffix(".new").removeSuffix(".bak")) }.distinct()
        candidates.forEach { file ->
            val atomic = android.util.AtomicFile(file)
            runCatching { atomic.openRead().close() }
            if (!file.exists()) atomic.delete()
        }
        val files = candidates.filter { it.exists() }.sortedBy { it.name }
        files.filter { now - it.lastModified() > ageMs }.forEach { android.util.AtomicFile(it).delete() }
        val retained = files.filter { it.exists() }.toMutableList()
        var file = retained.lastOrNull()
        if (file == null || file.length() + line.toByteArray(Charsets.UTF_8).size + 1 > segmentBytes) {
            file = File(directory, "events-${now.toString().padStart(16, '0')}.jsonl")
            // Same millisecond rotation must not overwrite an existing segment.
            var suffix = now
            while (file!!.exists()) file = File(directory, "events-${(++suffix).toString().padStart(16, '0')}.jsonl")
            retained.add(file)
        }
        while (retained.size > segments) android.util.AtomicFile(retained.removeAt(0)).delete()
        InsertDiagnosticStore(file!!, maxEntries = 4096, maxBytes = segmentBytes, maxAgeMs = ageMs).append(line, now)
    }
}

internal object DelayedInsertDiagnostics {
    const val DIRECTORY = "insert-delayed-diagnostics"
    const val PREFS = "insert-delayed-diagnostics"
    const val MAX_TEXT = 64 * 1024
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16),
        { task -> Thread(task, "TapStopDelayedWriter").apply { isDaemon = true } },
        { _, _ -> android.util.Log.w("TapStopDelayedDiag", "queue_full_event_dropped") })
    internal fun awaitWriter(timeoutMs: Long = 5000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        writer.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
    fun enabled(context: Context) = BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value && BuildConfig.DEBUG).apply()
    }
    fun begin(context: Context, text: String, raw: String?, final: String, diagnosticId: String? = null): DelayedInsertTrace? {
        if (!enabled(context)) return null
        val trace = DelayedInsertTrace(text = text)
        emit(context, trace, "INSERT_BEGIN", JSONObject().apply {
            put("raw_transcript", raw ?: JSONObject.NULL); put("final_text", final); put("insert_argument", text)
            put("raw_available", raw != null)
            if (diagnosticId != null) put("diagnostic_id", diagnosticId)
        })
        return trace
    }
    fun emit(context: Context, trace: DelayedInsertTrace?, event: String, data: JSONObject = JSONObject()) {
        if (trace == null || !enabled(context)) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - trace.started
        val target = trace.targetPackage
        val path = trace.path
        val confirmed = trace.immediateConfirmed
        runCatching { writer.execute {
            if (!enabled(context)) return@execute
            runCatching {
                data.put("schema", 2).put("event", event).put("timestamp_ms", now).put("insert_id", trace.id)
                    .put("elapsed_ms", elapsed).put("insert_timestamp_ms", trace.timestamp)
                    .put("target_package", target ?: JSONObject.NULL).put("path", path)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("text_chars", trace.text.length).put("contains_umlaut_or_sz", trace.text.any { it in "äöüÄÖÜßẞ" })
                    .put("immediate_confirmed", confirmed)
                if (event == "MANUAL_ERROR_MARKER") {
                    // A marker has no text snapshot and must not invent original insert properties.
                    data.remove("text_chars"); data.remove("contains_umlaut_or_sz"); data.remove("immediate_confirmed")
                }
                // Oversized text fields are omitted whole, never silently truncated as complete.
                for (key in listOf("raw_transcript", "final_text", "insert_argument", "pre_editor_text")) {
                    if (data.optString(key).length > MAX_TEXT) { data.remove(key); data.put("${key}_omitted", "TEXT_LIMIT_65536_UTF16") }
                }
                val editor = data.optJSONObject("editor")
                if (editor != null && editor.optString("text").length > MAX_TEXT * 2) {
                    editor.remove("text"); editor.put("text_omitted", "EDITOR_LIMIT_131072_UTF16")
                }
                DelayedDiagnosticStore(File(context.noBackupFilesDir, DIRECTORY)).append(data.toString(), now)
            }.onFailure { android.util.Log.w("TapStopDelayedDiag", "storage_failed") }
        } }
    }
    fun finish(context: Context, trace: DelayedInsertTrace?, outcome: String) {
        if (trace == null || !enabled(context)) return
        emit(context, trace, "INSERT_RESULT", JSONObject().put("outcome", outcome))
        // Marker association persists across process death, with no text in preferences.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_id", trace.id)
            .putLong("last_time", trace.timestamp).putString("last_package", trace.targetPackage)
            .putString("last_path", trace.path).apply()
    }
    fun mark(context: Context): String {
        if (!enabled(context)) return AudioSttDiagnostics.mark(context, null, "NO_INSERT", System.currentTimeMillis())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("last_id", null)
        val age = System.currentTimeMillis() - prefs.getLong("last_time", 0)
        val association = if (id == null) "NO_INSERT" else if (age !in 0..120_000) "STALE_OR_CLOCK_CHANGED" else "LAST_INSERT_CANDIDATE_UNCONFIRMED"
        val trace = DelayedInsertTrace(id = id ?: UUID.randomUUID().toString(), text = "",
            timestamp = if (id == null) System.currentTimeMillis() else prefs.getLong("last_time", 0))
        trace.targetPackage = prefs.getString("last_package", null)
        trace.path = prefs.getString("last_path", "UNKNOWN") ?: "UNKNOWN"
        emit(context, trace, "MANUAL_ERROR_MARKER", JSONObject().put("association", association)
            .put("associated_insert_id", id ?: JSONObject.NULL).put("age_since_insert_ms", if (id == null) JSONObject.NULL else age))
        val audioNotice = AudioSttDiagnostics.mark(context, id, association, System.currentTimeMillis())
        return "$audioNotice Marker angefordert: ${id?.take(8) ?: "keine Einfügung"}; Zuordnung: $association. Kein Screenshot erfasst."
    }
}
