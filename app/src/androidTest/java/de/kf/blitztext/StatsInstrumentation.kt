package de.kf.blitztext

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/** Runs without another testing dependency; fixtures use a separate disposable database. */
class StatsInstrumentation : Instrumentation() {
    private val report = StringBuilder()
    private var localArguments: Bundle? = null
    private var browserInput = false
    private var accessibilityOnly = false
    private var statisticsOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("localRuntime") == "true") localArguments = arguments
        browserInput = arguments?.getString("browserInput") == "true"
        accessibilityOnly = arguments?.getString("accessibilityOnly") == "true"
        statisticsOnly = arguments?.getString("statisticsOnly") == "true"
        start()
    }
    override fun onStart() {
        localArguments?.let { runLocalRuntimeChecks(it); return }
        if (browserInput) {
            runBrowserInputChecks(accessibilityOnly)
            return
        }
        try {
            databaseChecks()
            if (!statisticsOnly) inputChecks()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "$report\nFAILED: ${e.stackTraceToString()}") })
        }
    }
    private fun pass(name: String) { report.append("PASS: $name\n") }
    private fun awaitWrite(db: UsageStats, action: () -> Unit) {
        val revision = db.revision.value
        action()
        val until = System.currentTimeMillis() + 5000
        while (db.revision.value == revision && System.currentTimeMillis() < until) Thread.sleep(20)
        check(db.revision.value > revision && !db.storageError.value) { "SQLite write failed" }
    }
    private fun databaseChecks() = runBlocking {
        check(countWords("  Hallo, Welt!  \nÜbermäßig schön: 123 – E-Mail don't 😀") == 7)
        check(countWords(" ... 😀 \n") == 0)
        check(countWords("") == 0)
        check(median(listOf(900, 100, 500)) == 500.0)
        check(median(listOf(100, 200)) == 150.0)
        check(median(emptyList()) == null)
        pass("Unicode word counting and odd/even/empty medians")
        val name = "instrumentation-usage.db"
        targetContext.deleteDatabase(name)
        var db = UsageStats(targetContext, name)
        try {
            val today = LocalDate.of(2026, 9, 27)
            fun timestamp(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            check(db.read(StatsPeriod.ALL, today).attempts.isEmpty())
            val first = DictationStat(timestamp(today), 30_000, 60, 60, "Groq", "whisper-large-v3-turbo", Mode.BLITZTEXT.name, "com.android.chrome", 1000, null, 1100, true)
            val plus = first.copy(recordingMs = 60_000, rawWords = 120, finalWords = 110, provider = "OpenAI", model = "whisper-1", mode = Mode.PLUS.name, sttMs = 2000, rewriteMs = 500, totalMs = 2600)
            check(sttRealtimeFactor(listOf(first)) == 30.0)
            val slower = first.copy(recordingMs = 10_000, sttMs = 3000)
            check(sttRealtimeFactor(listOf(first, slower)) == 10.0) // 40,000 / 4,000, not mean of factors
            val invalid = listOf(first.copy(sttMs = null), first.copy(sttMs = 0), first.copy(recordingMs = 0), first.copy(success = false))
            check(sttRealtimeFactor(invalid) == null)
            check(sttRealtimeFactor(emptyList()) == null)
            check(sttRealtimeFactor(listOf(first, slower) + invalid) == 10.0)
            pass("STT realtime factor: ratio of sums, 30x example, empty/missing/zero times and failures")
            awaitWrite(db) { db.record(first) }
            awaitWrite(db) { db.record(plus) }
            awaitWrite(db) { db.record(first.copy(rawWords = null, finalWords = null, sttMs = null, totalMs = null, success = false, targetPackage = null)) }
            awaitWrite(db) { db.record(first.copy(rawWords = 50, finalWords = null, rewriteMs = null, totalMs = null, success = false)) }
            val summary = db.read(StatsPeriod.TODAY, today)
            check(summary.successful.size == 2 && summary.attempts.size == 4)
            check(summary.words == 180L && summary.finalWords == 170L)
            check(summary.recordingMs == 90_000L && summary.wpm == 120.0)
            check(summary.days[today] == 180L)
            check(median(summary.successful.mapNotNull { it.sttMs }) == 1500.0)
            check(median(summary.successful.mapNotNull { it.rewriteMs }) == 500.0)
            check(summary.attempts.count { it.rawWords == null && it.sttMs == null } == 1)
            pass("Aggregation, weighted WPM, partial failures, PLUS latency, target package")
            awaitWrite(db) { db.record(first.copy(timestamp = timestamp(today.minusDays(1)))) }
            awaitWrite(db) { db.record(first.copy(timestamp = timestamp(today.withDayOfMonth(1)))) }
            awaitWrite(db) { db.record(first.copy(timestamp = timestamp(today.minusMonths(1)))) }
            awaitWrite(db) { db.record(first.copy(timestamp = timestamp(today.plusDays(1)))) }
            check(db.read(StatsPeriod.TODAY, today).successful.size == 2)
            check(db.read(StatsPeriod.WEEK, today).successful.size == 3)
            check(db.read(StatsPeriod.MONTH, today).successful.size == 4)
            check(db.read(StatsPeriod.ALL, today).successful.size == 5)
            for (period in StatsPeriod.entries) {
                val groups = db.read(period, today).successful.groupBy { it.provider to it.model }
                check(groups.size == 2)
                check(groups.values.all { sttRealtimeFactor(it) == 30.0 })
            }
            pass("STT factors per provider/model in each selected period")
            check(StatsPeriod.WEEK.start(LocalDate.of(2026, 9, 28)) == LocalDate.of(2026, 9, 28))
            pass("Today/week/month/all boundaries and future exclusion")
            db.close()
            db = UsageStats(targetContext, name)
            check(db.read(StatsPeriod.ALL, today).successful.size == 5)
            pass("SQLite persists after reopening")
            val columns = db.readableDatabase.rawQuery("PRAGMA table_info(dictations)", null).use { c ->
                buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            check(columns == listOf("id", "timestamp", "recording_ms", "raw_words", "final_words", "provider", "model", "mode", "target_package", "stt_ms", "rewrite_ms", "total_ms", "success"))
            awaitWrite(db) { db.reset() }
            check(db.read(StatsPeriod.ALL, today.plusDays(2)).attempts.isEmpty())
            pass("Metadata-only schema and full reset")
        } finally { db.close(); targetContext.deleteDatabase(name) }
        val settings = Settings(targetContext)
        if (settings.provider.isCloud) {
            check(settings.apiKey().isNotBlank()) { "Existing selected API key cannot be decrypted" }
            pass("Existing selected provider API key is decryptable (not logged)")
        } else {
            check(settings.apiKey().isEmpty())
            pass("Local provider requires no API key")
        }
    }
    private fun inputChecks() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        try {
            sendStatus(1, Bundle().apply { putString("stream", "READY_ACCESSIBILITY\n") })
            val deadline = System.currentTimeMillis() + 20000
            while (TextInsertService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            check(TextInsertService.instance != null) { "Accessibility service not connected" }
            val cases = listOf(
                Triple("", "Platzhalter", 0), Triple("", "", 0),
                Triple("Anfang Ende", "", 11), Triple("Anfang Ende", "", 7)
            )
            for ((old, hint, cursor) in cases) {
                lateinit var field: EditText
                runOnMainSync {
                    field = EditText(activity).apply { setText(old); this.hint = hint; textSize = 24f }
                    activity.setContentView(field)
                    field.requestFocus()
                    field.setSelection(cursor)
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, 0)
                }
                Thread.sleep(900)
                runOnMainSync {
                    val service = TextInsertService.instance!!
                    service.rememberFocusedField()
                    check(service.statisticsTargetPackage() == targetContext.packageName)
                    check(service.insert("Test ")) { "Insertion refused" }
                }
                Thread.sleep(600)
                runOnMainSync { check(field.text.toString() == old.substring(0, cursor) + "Test " + old.substring(cursor)) { "Unexpected inserted text" } }
            }
            pass("InputConnection: empty with/without placeholder, append, cursor in middle; target package")
            runOnMainSync {
                val overlay = OverlayService()
                android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply { isAccessible = true }.invoke(overlay, targetContext)
                OverlayService::class.java.getDeclaredMethod("deliver", String::class.java).apply { isAccessible = true }.invoke(overlay, "Fallback test")
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Fallback test ")
                clipboard.clearPrimaryClip()
            }
            pass("Original deliver path falls back to clipboard when no target is retained")
        } finally { runOnMainSync { activity.finish() } }
    }
}
