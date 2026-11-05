package de.kf.blitztext

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serialized private store. Only UUID directories owned by this diagnostic are ever removed. */
internal class AudioDiagnosticStore(val root: File, private val maxBytes: Long = 500L * 1024 * 1024,
    private val maxAgeMs: Long = 14L * 24 * 60 * 60 * 1000, private val maxCases: Int = 4096) {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun cases() = root.listFiles()?.filter { it.isDirectory && uuid.matches(it.name) }.orEmpty()
    private fun size() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun read(id: String): JSONObject? {
        require(uuid.matches(id))
        return runCatching { AtomicFile(File(root, "$id/case.json")).openRead().use { JSONObject(it.bufferedReader().readText()) } }.getOrNull()
    }
    fun recover(now: Long) {
        root.mkdirs()
        for (dir in cases()) {
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            val record = read(dir.name)
            if (record == null) { dir.deleteRecursively(); continue }
            val audio = record.optString("audio_file")
            dir.listFiles()?.filter { it.extension in setOf("m4a", "wav") && it.name != audio }?.forEach { it.delete() }
            if (!record.optBoolean("marked") && now - record.optLong("timestamp_ms") > maxAgeMs) dir.deleteRecursively()
        }
    }
    private fun room(extra: Long, keep: String): Boolean {
        if (extra > maxBytes) return false
        val candidates = cases().filter { it.name != keep && read(it.name)?.optBoolean("marked") != true }
            .sortedBy { read(it.name)?.optLong("timestamp_ms") ?: 0 }
        for (dir in candidates) {
            if (size() + extra <= maxBytes && cases().size + (if (File(root, keep).exists()) 0 else 1) <= maxCases) break
            check(dir.deleteRecursively())
        }
        return size() + extra <= maxBytes && cases().size + (if (File(root, keep).exists()) 0 else 1) <= maxCases
    }
    private fun save(id: String, record: JSONObject) {
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        check(room(bytes.size.toLong(), id)) { "diagnostic_capacity" }
        val dir = File(root, id)
        check(dir.isDirectory || dir.mkdirs())
        val atomic = AtomicFile(File(dir, "case.json"))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun event(id: String, event: JSONObject, now: Long) {
        val record = read(id) ?: JSONObject().put("schema", 1).put("diagnostic_id", id)
            .put("timestamp_ms", now).put("marked", false).put("events", JSONArray())
        if (event.optString("event") == "MANUAL_ERROR_MARKER") record.put("marked", true)
        if (event.has("insert_id")) record.put("insert_id", event.get("insert_id"))
        record.getJSONArray("events").put(event)
        save(id, record)
    }
    fun copy(id: String, input: FileInputStream, bytes: Long, extension: String, now: Long) {
        require(extension in setOf("m4a", "wav"))
        recover(now)
        check(room(bytes + 65536, id)) { "diagnostic_capacity" }
        // A durable manifest precedes audio; a crash can leave only a removable .part/orphan.
        event(id, JSONObject().put("event", "AUDIO_COPY_PENDING").put("timestamp_ms", now), now)
        val dir = File(root, id)
        val part = File(dir, "input.$extension.part")
        val audio = File(dir, "input.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var count = 0L
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= bytes) { "source_size_changed" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                check(count == bytes) { "source_size_changed" }
                output.fd.sync()
            }
            check(part.renameTo(audio))
            val record = checkNotNull(read(id))
            record.put("audio_file", audio.name).put("audio_bytes", bytes)
                .put("audio_sha256", digest.digest().joinToString("") { "%02x".format(it) })
            val format = JSONObject().put("container", if (extension == "wav") "WAV" else "MPEG_4")
            if (extension == "wav") {
                format.put("codec", "PCM16").put("sample_rate_hz", 16000).put("channels", 1)
                    .put("media_duration_us", (bytes - 44).coerceAtLeast(0) * 1000000 / 32000)
            } else {
                // Inspect the COPY, never touch MediaRecorder or the input file used by STT.
                runCatching {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(audio.path)
                        val track = extractor.getTrackFormat(0)
                        for ((key, field) in listOf(MediaFormat.KEY_SAMPLE_RATE to "sample_rate_hz", MediaFormat.KEY_CHANNEL_COUNT to "channels"))
                            if (track.containsKey(key)) format.put(field, track.getInteger(key))
                        if (track.containsKey(MediaFormat.KEY_DURATION)) format.put("media_duration_us", track.getLong(MediaFormat.KEY_DURATION))
                        format.put("codec", track.getString(MediaFormat.KEY_MIME))
                    } finally { extractor.release() }
                }.onFailure { format.put("inspection_status", "UNAVAILABLE") }
            }
            record.put("format", format)
            save(id, record)
        } finally { part.delete() }
    }
}

internal class AudioSttTrace(val context: Context, val id: String = UUID.randomUUID().toString()) {
    private val started = SystemClock.elapsedRealtime()
    fun event(name: String, data: JSONObject = JSONObject()) = AudioSttDiagnostics.event(this, name, data)
    fun finalized(file: File, durationMs: Long, provider: Provider, model: GroqModel) {
        event("RECORDING_END", JSONObject().put("recording_duration_ms", durationMs)
            .put("elapsed_recording_ms", SystemClock.elapsedRealtime() - started)
            .put("stt_provider", provider.name).put("stt_model", provider.sttModel(model)))
        AudioSttDiagnostics.capture(this, file)
        AudioSttDiagnostics.lastCompleted(this)
    }
}

/** All expensive IO is isolated; a failed/saturated diagnostic must never fail dictation. */
internal object AudioSttDiagnostics {
    const val DIRECTORY = "audio-stt-diagnostics"
    const val PREFS = "audio-stt-diagnostics"
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16),
        { task -> Thread(task, "TapStopAudioWriter").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private fun submit(action: () -> Unit): Boolean = runCatching {
        writer.execute { runCatching(action).onFailure { android.util.Log.w("TapStopAudioDiag", "diagnostic_storage_failed") } }; true
    }.getOrDefault(false)
    fun awaitWriter(): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        return submit { latch.countDown() } && latch.await(30, TimeUnit.SECONDS)
    }
    fun enabled(context: Context) = BuildConfig.DEBUG && context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", value && BuildConfig.DEBUG).apply() }
    private fun store(context: Context) = AudioDiagnosticStore(File(context.noBackupFilesDir, DIRECTORY))
    fun begin(context: Context): AudioSttTrace? = runCatching {
        if (!enabled(context)) null else AudioSttTrace(context.applicationContext).also { it.event("RECORDING_START") }
    }.getOrNull()
    fun event(trace: AudioSttTrace, name: String, data: JSONObject) {
        runCatching {
            if (!enabled(trace.context)) return
            val now = System.currentTimeMillis()
            val immutable = data.toString()
            submit {
                if (!enabled(trace.context)) return@submit
                val store = store(trace.context)
                store.recover(now)
                store.event(trace.id, JSONObject(immutable).put("event", name).put("timestamp_ms", now), now)
            }
        }
    }
    fun capture(trace: AudioSttTrace, file: File) {
        runCatching {
            if (!enabled(trace.context)) return
            // Open before returning: Linux keeps this inode alive when normal cleanup unlinks it.
            // No file data is read/written here; no wait for diagnostic disk IO on the STT path.
            val input = FileInputStream(file)
            val bytes = file.length()
            val extension = file.extension
            val accepted = submit {
                input.use {
                    if (enabled(trace.context)) {
                        try { store(trace.context).copy(trace.id, it, bytes, extension, System.currentTimeMillis()) }
                        catch (_: Exception) { trace.event("AUDIO_COPY_FAILED") }
                    }
                }
            }
            if (!accepted) { input.close(); trace.event("AUDIO_COPY_QUEUE_FULL") }
        }.onFailure { trace.event("AUDIO_COPY_OPEN_FAILED") }
    }
    fun lastCompleted(trace: AudioSttTrace) {
        runCatching { trace.context.getSharedPreferences(PREFS, 0).edit().putString("last_id", trace.id)
            .putLong("last_time", System.currentTimeMillis()).apply() }
    }
    fun link(context: Context, id: String?, insertId: String?, outcome: String) {
        if (id == null) return
        AudioSttTrace(context.applicationContext, id).event("INSERT_LINK", JSONObject()
            .put("insert_id", insertId ?: JSONObject.NULL).put("outcome", outcome))
    }
    /** Existing last-insert semantics win. Never silently assign its marker to a newer recording. */
    fun mark(context: Context, insertId: String?, association: String, markerTime: Long): String {
        if (!enabled(context)) return "Audiodiagnose deaktiviert."
        val prefs = context.getSharedPreferences(PREFS, 0)
        val fallback = if (insertId == null) prefs.getString("last_id", null) else null
        submit {
            val store = store(context)
            val id = if (insertId != null) store.root.listFiles()?.firstOrNull {
                it.isDirectory && runCatching { store.read(it.name)?.optString("insert_id") == insertId }.getOrDefault(false)
            }?.name else fallback
            if (id != null) {
                val age = markerTime - (store.read(id)?.optLong("timestamp_ms") ?: 0)
                store.event(id, JSONObject().put("event", "MANUAL_ERROR_MARKER").put("timestamp_ms", markerTime)
                    .put("associated_insert_id", insertId ?: JSONObject.NULL)
                    .put("association", if (insertId != null) association else if (age in 0..120000) "LAST_COMPLETED_RECORDING_CANDIDATE_UNCONFIRMED" else "STALE_OR_CLOCK_CHANGED")
                    .put("screenshot_timestamp_ms", JSONObject.NULL), markerTime)
            }
        }
        return "Audio-Markierung angefordert; vorhandene Insert-Zuordnung hat Vorrang."
    }
}
