package de.kf.tapstop

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ExecutorService

/** Replays stopRecording -> original worker -> ApiClient -> deliver/reset/cleanup.
 * Only microphone acquisition and foreign-editor insertion are outside this replay.
 * Settings reads are overlaid in memory; statistics go to a disposable DB. */
internal fun StatsInstrumentation.runLatencyChecks(arguments: Bundle) {
    val report = StringBuilder("LATENCY_REPLAY\n")
    val rawCount = arguments.getString("rawCount", "5").toInt().coerceIn(0, 50)
    val plusCount = arguments.getString("plusCount", "3").toInt().coerceIn(0, 50)
    val audioChecks = arguments.getString("audioDiagnostics") == "true"
    val audioCases = mutableListOf<Pair<String, String>>()
    if (audioChecks) {
        AudioSttDiagnostics.setEnabled(targetContext, true)
        DelayedInsertDiagnostics.setEnabled(targetContext, true)
    }
    val faults = arguments.getString("faultReplay") == "true"
    val selected = arguments.getString("provider", "BOTH")
    val settings = Settings(targetContext)
    var providerOverride = Provider.GROQ
    var modeOverride = Mode.BLITZTEXT
    val replayContext = object : ContextWrapper(targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val original = super.getSharedPreferences(name, mode)
            if (name != "settings") return original
            return object : SharedPreferences by original {
                // Tablet manual replay must not persist bubble position or mode changes.
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                    override fun putString(k: String?, v: String?) = this
                    override fun putStringSet(k: String?, v: MutableSet<String>?) = this
                    override fun putInt(k: String?, v: Int) = this
                    override fun putLong(k: String?, v: Long) = this
                    override fun putFloat(k: String?, v: Float) = this
                    override fun putBoolean(k: String?, v: Boolean) = this
                    override fun remove(k: String?) = this
                    override fun clear() = this
                    override fun commit() = true
                    override fun apply() = Unit
                }
                override fun getString(key: String?, defValue: String?): String? = when (key) {
                    "provider" -> providerOverride.name
                    "mode" -> modeOverride.name
                    else -> original.getString(key, defValue)
                }
            }
        }
    }
    val singleton = UsageStats::class.java.getDeclaredField("instance").apply { isAccessible = true }
    val originalDb = singleton.get(null)
    val db = UsageStats(targetContext, "latency-replay.db")
    val overlay = OverlayService()
    fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
    val stop = OverlayService::class.java.getDeclaredMethod("stopRecording").apply { isAccessible = true }
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var oldClip: android.content.ClipData? = null
    var created = false
    var resultCode = Activity.RESULT_CANCELED
    var resultBundle = Bundle()
    try {
        runOnMainSync { oldClip = clipboard.primaryClip }
        singleton.set(null, db)
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(overlay, replayContext)
        runOnMainSync { overlay.onCreate(); created = true }
        val phaseField = field("phase")
        val recording = phaseField.type.enumConstants.first { it.toString() == "RECORDING" }
        DictationTrace.create()?.snapshot("replay_baseline")
        val providerOrder = if (arguments.getString("groqFirst") == "true") listOf(Provider.GROQ, Provider.OPENAI) else Provider.rewriteProviders
        for (provider in providerOrder.filter { selected == "BOTH" || selected == it.name }) {
            if (!faults && settings.apiKey(provider).isBlank()) {
                report.append("SKIP provider=${provider.name} reason=key_unavailable\n")
                continue
            }
            providerOverride = provider
            for ((mode, count) in listOf(Mode.BLITZTEXT to rawCount, Mode.PLUS to plusCount)) {
                modeOverride = mode
                for (index in 1..count) {
                    if (faults) {
                        val scenario = if (mode == Mode.BLITZTEXT) when (index) {
                            2 -> "http"; 4 -> "timeout"; else -> "ok"
                        } else if (index == 1) "rewrite" else "ok"
                        DictationTrace.connectionFactory = { FaultConnection(it, scenario) }
                    }
                    val audio = File(targetContext.cacheDir, "latency-replay-${provider.name}-${mode.name}-$index.m4a")
                    context.assets.open("latency-speech.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
                    val revision = db.revision.value
                    val start = SystemClock.elapsedRealtime()
                    runOnMainSync {
                        check(phaseField.get(overlay)?.toString() == "IDLE")
                        if (audioChecks) {
                            val audioTrace = AudioSttDiagnostics.begin(targetContext)!!
                            audioTrace.event("SYNTHETIC_DEVICE_REPLAY", org.json.JSONObject().put("microphone_used", false).put("real_network_used", false))
                            field("audioTrace").set(overlay, audioTrace)
                            audioCases.add(audioTrace.id to java.security.MessageDigest.getInstance("SHA-256").digest(audio.readBytes()).joinToString("") { "%02x".format(it) })
                        }
                        field("audioFile").set(overlay, audio)
                        field("recordingStarted").setLong(overlay, SystemClock.elapsedRealtime() - 6151)
                        phaseField.set(overlay, recording)
                        stop.invoke(overlay)
                    }
                    var idle = false
                    val deadline = start + 155_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        runOnMainSync { idle = phaseField.get(overlay)?.toString() == "IDLE" }
                        if (idle && DictationTrace.workers.get() == 0 && DictationTrace.submitted.get() == 0 && db.revision.value > revision) break
                        Thread.sleep(20)
                    }
                    check(idle && DictationTrace.workers.get() == 0 && DictationTrace.submitted.get() == 0) { "Replay did not return to idle" }
                    check(!audio.exists() && DictationTrace.connections.get() == 0) { "Replay resources retained" }
                    // Synthetic responses finish in milliseconds; avoid intentionally saturating
                    // the bounded best-effort diagnostic queue between unrelated fixture cases.
                    if (audioChecks) check(AudioSttDiagnostics.awaitWriter())
                    val row = runBlocking { db.read(StatsPeriod.ALL, java.time.LocalDate.now()) }.attempts.last()
                    val summary = "RUN provider=${provider.name} mode=${mode.name} n=$index stt_ms=${row.sttMs} rewrite_ms=${row.rewriteMs} total_ms=${row.totalMs} success=${row.success} wall_ms=${SystemClock.elapsedRealtime() - start}"
                    report.append(summary).append('\n')
                    sendStatus(1, Bundle().apply { putString("stream", "$summary\n") })
                    // Stop on provider errors (including rate limits); never retry automatically.
                    if (!row.success && !faults) break
                }
            }
        }
        if (audioChecks) {
            check(AudioSttDiagnostics.awaitWriter())
            val store = AudioDiagnosticStore(File(targetContext.noBackupFilesDir, AudioSttDiagnostics.DIRECTORY))
            for ((id, hash) in audioCases) {
                val record = checkNotNull(store.read(id))
                check(record.getString("audio_sha256") == hash)
                val copy = File(store.root, "$id/${record.getString("audio_file")}")
                check(java.security.MessageDigest.getInstance("SHA-256").digest(copy.readBytes()).joinToString("") { "%02x".format(it) } == hash)
                val events = record.getJSONArray("events")
                val uploaded = (0 until events.length()).map { events.getJSONObject(it) }.single { it.getString("event") == "STT_UPLOAD_FILE" }
                check(uploaded.getString("sha256") == hash)
                check(record.getJSONObject("format").getInt("sample_rate_hz") == 44100)
            }
            runOnMainSync { DelayedInsertDiagnostics.mark(targetContext) }
            check(AudioSttDiagnostics.awaitWriter())
            val insert = targetContext.getSharedPreferences(DelayedInsertDiagnostics.PREFS, 0).getString("last_id", null)
            val linked = audioCases.mapNotNull { store.read(it.first) }.single { it.optString("insert_id") == insert }
            check(linked.getBoolean("marked"))
            sendStatus(1, Bundle().apply { putString("stream", "PASS AUDIO: ${audioCases.size} original files; copy/upload SHA-256 equal; cache cleanup; persistent insert+marker; no microphone or network\n") })
        }
        val manualRaw = arguments.getString("manualRawCount", "0").toInt().coerceIn(0, 5)
        val manualPlus = arguments.getString("manualPlusCount", "0").toInt().coerceIn(0, 3)
        if (manualRaw + manualPlus > 0) {
            runOnMainSync {
                checkNotNull(TextInsertService.instance) { "Accessibility service not connected" }.showBubble(overlay)
            }
            for ((mode, count) in listOf(Mode.BLITZTEXT to manualRaw, Mode.PLUS to manualPlus)) {
                modeOverride = mode
                if (count == 0) continue
                sendStatus(1, Bundle().apply { putString("stream", "READY_MANUAL provider=${providerOverride.name} mode=${mode.name} count=$count\n") })
                for (index in 1..count) {
                    val revision = db.revision.value
                    val deadline = SystemClock.elapsedRealtime() + 300_000
                    while (SystemClock.elapsedRealtime() < deadline && (db.revision.value == revision || DictationTrace.workers.get() != 0 || DictationTrace.submitted.get() != 0)) Thread.sleep(100)
                    check(db.revision.value > revision && DictationTrace.workers.get() == 0) { "Manual replay timed out" }
                    val row = runBlocking { db.read(StatsPeriod.ALL, java.time.LocalDate.now()) }.attempts.last()
                    val summary = "MANUAL provider=${providerOverride.name} mode=${mode.name} n=$index stt_ms=${row.sttMs} rewrite_ms=${row.rewriteMs} total_ms=${row.totalMs} success=${row.success}"
                    report.append(summary).append('\n')
                    sendStatus(1, Bundle().apply { putString("stream", "$summary\n") })
                    if (!row.success) break
                }
            }
        }
        val settleMs = arguments.getString("settleMs", "0").toLong().coerceIn(0, 150_000)
        val settleUntil = SystemClock.elapsedRealtime() + settleMs
        while (SystemClock.elapsedRealtime() < settleUntil) Thread.sleep(100)
        DictationTrace.create()?.snapshot("settled")
        report.append("SETTLED watchdogs=${DictationTrace.watchdogs.get()}\n")
        report.append("PASS replay: idle, worker drained, audio deleted, owned connections=0\n")
        resultCode = Activity.RESULT_OK
        resultBundle = Bundle().apply { putString("stream", report.toString()) }
    } catch (e: Throwable) {
        resultBundle = Bundle().apply { putString("stream", "$report\nFAILED type=${e.javaClass.name}\n") }
    } finally {
        runOnMainSync {
            if (created) overlay.onDestroy()
            if (oldClip != null) clipboard.setPrimaryClip(oldClip!!) else clipboard.clearPrimaryClip()
            activity.finish()
        }
        DictationTrace.connectionFactory = null
        singleton.set(null, originalDb)
        (UsageStats::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(db) as ExecutorService).shutdown()
        db.close()
        targetContext.deleteDatabase("latency-replay.db")
    }
    // finish() kills the instrumented app; complete cleanup before reporting.
    finish(resultCode, resultBundle)
}


/** No live HTTP request: deterministic transport errors in the original Service/ApiClient path. */
private class FaultConnection(url: java.net.URL, private val scenario: String) : java.net.HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
    override fun getOutputStream() = java.io.ByteArrayOutputStream()
    override fun getResponseCode(): Int {
        if (scenario == "timeout") throw java.net.SocketTimeoutException("fixture")
        return if (scenario == "http" || (scenario == "rewrite" && url.path.endsWith("completions"))) 500 else 200
    }
    override fun getInputStream() = if (url.path.endsWith("transcriptions")) """{"text":"fixture"}""".byteInputStream()
        else """{"choices":[{"message":{"content":"fixture"}}]}""".byteInputStream()
    override fun getErrorStream() = """{"error":{"message":"fixture"}}""".byteInputStream()
}
