package de.kf.blitztext

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.concurrent.Executors

/** Metadata only. Null means that a stage did not successfully complete. */
data class DictationStat(
    val timestamp: Long,
    val recordingMs: Long,
    val rawWords: Int? = null,
    val finalWords: Int? = null,
    val provider: String,
    val model: String,
    val mode: String,
    val targetPackage: String? = null,
    val sttMs: Long? = null,
    val rewriteMs: Long? = null,
    val totalMs: Long? = null,
    val success: Boolean = false
)

fun countWords(text: String): Int = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*").findAll(text).count()
fun median(values: List<Long>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
    else sorted[sorted.size / 2 - 1] / 2.0 + sorted[sorted.size / 2] / 2.0
}

/** Ratio of sums for completed dictations with measurable STT and recording durations. */
fun sttRealtimeFactor(rows: List<DictationStat>): Double? {
    val measured = rows.filter { it.success && it.recordingMs > 0 && (it.sttMs ?: 0) > 0 }
    if (measured.isEmpty()) return null
    return measured.sumOf { it.recordingMs.toDouble() } / measured.sumOf { it.sttMs!!.toDouble() }
}

enum class StatsPeriod(val label: String) {
    TODAY("Heute"), WEEK("Woche"), MONTH("Monat"), ALL("Gesamt");
    fun start(today: LocalDate): LocalDate? = when (this) {
        TODAY -> today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
        ALL -> null
    }
}

data class UsageSummary(val attempts: List<DictationStat>) {
    val successful = attempts.filter { it.success }
    val words = successful.sumOf { (it.rawWords ?: 0).toLong() }
    val finalWords = successful.sumOf { (it.finalWords ?: 0).toLong() }
    val recordingMs = successful.sumOf { it.recordingMs }
    val wpm = if (recordingMs > 0) words * 60_000.0 / recordingMs else 0.0
    val days = successful.groupBy { java.time.Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
        .mapValues { (_, rows) -> rows.sumOf { (it.rawWords ?: 0).toLong() } }
}

/** Separate DB; existing encrypted preferences and Keystore are never touched. */
class UsageStats internal constructor(context: Context, databaseName: String = "usage.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val executor = Executors.newSingleThreadExecutor()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    // A write failure must not interrupt dictation, but must be visible in the app.
    val storageError = MutableStateFlow(false)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE dictations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            recording_ms INTEGER NOT NULL CHECK(recording_ms >= 0),
            raw_words INTEGER, final_words INTEGER,
            provider TEXT NOT NULL, model TEXT NOT NULL, mode TEXT NOT NULL,
            target_package TEXT, stt_ms INTEGER, rewrite_ms INTEGER, total_ms INTEGER,
            success INTEGER NOT NULL CHECK(success IN (0,1)))""")
        db.execSQL("CREATE INDEX dictations_timestamp ON dictations(timestamp)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing statistics migration: $oldVersion → $newVersion")
    }
    fun record(row: DictationStat) = mutate {
        val values = ContentValues().apply {
            put("timestamp", row.timestamp); put("recording_ms", row.recordingMs)
            put("raw_words", row.rawWords); put("final_words", row.finalWords)
            put("provider", row.provider); put("model", row.model); put("mode", row.mode)
            put("target_package", row.targetPackage); put("stt_ms", row.sttMs)
            put("rewrite_ms", row.rewriteMs); put("total_ms", row.totalMs)
            put("success", if (row.success) 1 else 0)
        }
        writableDatabase.insertOrThrow("dictations", null, values)
    }
    fun reset() = mutate { writableDatabase.delete("dictations", null, null) }
    private fun mutate(action: () -> Unit) {
        executor.execute {
            runCatching(action).onSuccess { storageError.value = false; changes.value++ }
                .onFailure { storageError.value = true }
        }
    }
    suspend fun read(period: StatsPeriod, today: LocalDate): UsageSummary = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val from = period.start(today)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
        val until = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = mutableListOf<DictationStat>()
        readableDatabase.rawQuery("SELECT * FROM dictations WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp", arrayOf(from.toString(), until.toString())).use { c ->
            fun number(name: String): Long? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getLong(it) }
            fun text(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
            while (c.moveToNext()) rows += DictationStat(
                number("timestamp")!!, number("recording_ms")!!, number("raw_words")?.toInt(), number("final_words")?.toInt(),
                text("provider")!!, text("model")!!, text("mode")!!, text("target_package"),
                number("stt_ms"), number("rewrite_ms"), number("total_ms"), number("success") == 1L
            )
        }
        UsageSummary(rows)
    }
    companion object {
        @Volatile private var instance: UsageStats? = null
        fun get(context: Context): UsageStats = instance ?: synchronized(this) {
            instance ?: UsageStats(context.applicationContext).also { instance = it }
        }
    }
}
