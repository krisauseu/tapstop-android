package de.kf.tapstop

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Only existing metadata and fixed enums cross this boundary. Never accept text or exceptions. */
internal data class InsertMetadata(
    val recordingMs: Long, val words: Int?, val mode: Mode,
    val provider: Provider, val model: GroqModel, val targetPackage: String?
)

internal enum class InsertStep {
    SERVICE_UNAVAILABLE, TARGET_MISSING, TARGET_INVALID, TARGET_VALID,
    INPUT_CONNECTION_UNSUPPORTED, INPUT_CONNECTION_UNAVAILABLE, INPUT_CONNECTION_PACKAGE_MISMATCH,
    INPUT_CONNECTION_AVAILABLE, INPUT_CONNECTION_PROBE_EMPTY, INPUT_CONNECTION_COMMIT_ATTEMPTED,
    INPUT_CONNECTION_CONFIRMED, INPUT_CONNECTION_UNCONFIRMED, INPUT_CONNECTION_ACCEPTED_UNVERIFIED,
    INPUT_CONNECTION_EXCEPTION, ACCESSIBILITY_TARGET_INVALID, ACCESSIBILITY_ATTEMPTED,
    ACCESSIBILITY_REJECTED, ACCESSIBILITY_SUCCESS, INSERT_EXCEPTION, DELIVERY_EXCEPTION,
    CLIPBOARD_WRITTEN
}

internal class InsertAttempt(val timestamp: Long, private val chars: Int, private val metadata: InsertMetadata?) {
    var delayed: DelayedInsertTrace? = null
    var audioInsertId: String? = null
    private val steps = mutableListOf<InsertStep>()
    private var targetPackage = metadata?.targetPackage
    fun step(value: InsertStep) { steps.add(value) }
    fun target(value: String?) { if (value != null) { targetPackage = value; delayed?.targetPackage = value } }

    fun clipboardReason(): String = when (steps.lastOrNull()) {
        InsertStep.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        InsertStep.TARGET_MISSING -> "NO_RETAINED_TARGET"
        InsertStep.TARGET_INVALID -> "INVALID_TARGET"
        InsertStep.ACCESSIBILITY_TARGET_INVALID -> "ACCESSIBILITY_TARGET_INVALID"
        InsertStep.ACCESSIBILITY_REJECTED -> "ACCESSIBILITY_REJECTED"
        InsertStep.INSERT_EXCEPTION -> "INSERT_EXCEPTION"
        InsertStep.DELIVERY_EXCEPTION -> "DELIVERY_EXCEPTION"
        else -> "UNCLASSIFIED" // Do not invent a cause where the code supplies none.
    }

    fun json(clipboard: Boolean, reason: String?): String = JSONObject().apply {
        put("schema", 1)
        (delayed?.id ?: audioInsertId)?.let { put("insert_id", it) }
        put("timestamp_ms", timestamp)
        // Package names only, bounded even for malformed platform metadata.
        put("target_package", targetPackage?.take(255)?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: JSONObject.NULL)
        put("text_chars", chars)
        put("recording_ms", metadata?.recordingMs ?: JSONObject.NULL)
        put("final_words", metadata?.words ?: JSONObject.NULL)
        put("mode", metadata?.mode?.name ?: JSONObject.NULL)
        put("stt_provider", metadata?.provider?.name ?: JSONObject.NULL)
        put("stt_location", metadata?.provider?.let { if (it.isCloud) "cloud" else "local" } ?: JSONObject.NULL)
        put("stt_model", metadata?.provider?.sttModel(metadata.model) ?: JSONObject.NULL)
        put("outcome", if (clipboard) "CLIPBOARD" else if (reason != null) "CLIPBOARD_WRITE_FAILED" else when {
            InsertStep.ACCESSIBILITY_SUCCESS in steps -> "DIRECT_ACCESSIBILITY_SUCCESS"
            InsertStep.INPUT_CONNECTION_CONFIRMED in steps -> "DIRECT_INPUT_CONNECTION_SUCCESS"
            InsertStep.INPUT_CONNECTION_ACCEPTED_UNVERIFIED in steps -> "DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED"
            else -> "DIRECT_SUCCESS_UNCLASSIFIED"
        })
        put("clipboard_reason", reason ?: JSONObject.NULL)
        put("steps", JSONArray(steps.map { it.name }))
    }.toString()
}

/** Separate, atomic, bounded JSONL file. All disk work happens after insertion on a worker. */
internal class InsertDiagnosticStore(
    file: File, private val maxEntries: Int = 1024, private val maxBytes: Int = 1024 * 1024,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000
) {
    private val atomic = AtomicFile(file)
    @Synchronized fun append(line: String, now: Long) {
        val existing = try { atomic.openRead().bufferedReader().use { it.readLines() } }
            catch (_: FileNotFoundException) { emptyList() }
        val retained = (existing + line).filter {
            runCatching { JSONObject(it).getLong("timestamp_ms") >= now - maxAgeMs }.getOrDefault(false)
        }.takeLast(maxEntries).toMutableList()
        var bytes = retained.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        while (bytes > maxBytes && retained.isNotEmpty()) {
            bytes -= retained.removeAt(0).toByteArray(Charsets.UTF_8).size + 1
        }
        atomic.baseFile.parentFile?.mkdirs()
        val output = atomic.startWrite()
        try {
            output.write(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}

internal object InsertDiagnostics {
    const val FILE = "insert-diagnostics.jsonl"
    // Saturation discards diagnostic work only; never block or change an insert result.
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "TapStopInsertDiagnostics").apply { isDaemon = true } },
        { _, _ -> Log.w("TapStopInsertDiag", "queue_full_record_dropped") })

    fun begin(chars: Int, metadata: InsertMetadata?): InsertAttempt? =
        if (BuildConfig.LOCAL_DIAGNOSTICS) runCatching { InsertAttempt(System.currentTimeMillis(), chars, metadata) }.getOrNull() else null

    private fun enabled(context: Context) =
        DelayedInsertDiagnostics.enabled(context) || AudioSttDiagnostics.enabled(context)

    fun finish(context: Context, attempt: InsertAttempt?, clipboard: Boolean, reason: String?) {
        if (attempt == null) return
        // Serialization, queueing and storage failures cannot escape into the insert caller.
        runCatching {
            if (!enabled(context)) return
            val file = File(context.noBackupFilesDir, FILE)
            writer.execute {
                runCatching {
                    if (enabled(context)) {
                        InsertDiagnosticStore(file).append(attempt.json(clipboard, reason), System.currentTimeMillis())
                    }
                }
                    .onFailure { Log.w("TapStopInsertDiag", "storage_failed") }
            }
        }
    }
}
