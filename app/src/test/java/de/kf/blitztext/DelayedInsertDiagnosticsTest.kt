package de.kf.blitztext

import android.content.Context
import android.view.inputmethod.EditorInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DelayedInsertDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        DelayedInsertDiagnostics.setEnabled(context, false)
        assertTrue(DelayedInsertDiagnostics.awaitWriter())
        context.getSharedPreferences(DelayedInsertDiagnostics.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).deleteRecursively()
    }
    private fun trace(text: String = "Übermäßig schön ß 😀 ", prefix: String = "vor ") = DelayedInsertTrace(text = text).apply {
        immediateConfirmed = true
        insertionStart = prefix.length
        baseline = EditorReadback(prefix + text, prefix.length + text.length, prefix.length + text.length, 0)
    }
    @Test fun unicodeInsertedRangeWithExistingPrefixSuffixAndMovedCursor() {
        val t = trace()
        val read = EditorReadback("vor " + t.text + "nach", 1, 1, 0)
        assertEquals("DELAYED_CONFIRMED", DelayedComparison.compare(t, read).getString("status"))
        assertEquals(t.text.length, DelayedComparison.compare(t, read).getInt("checked_chars"))
        val cropped = EditorReadback(t.text + "nach", t.text.length, t.text.length, 4)
        assertEquals("DELAYED_CONFIRMED", DelayedComparison.compare(t, cropped).getString("status"))
    }
    @Test fun realDelayedCharacterDifferenceHasUnknownAuthorNotProvenLoss() {
        val t = trace()
        val result = DelayedComparison.compare(t, EditorReadback("vor " + t.text.replace("ü", "u").replace("Ü", "U") + "nach", 0, 0, 0))
        assertEquals("DELAYED_TEXT_MISMATCH", result.getString("status"))
        assertEquals(0, result.getInt("first_difference_utf16"))
        assertEquals("UNKNOWN", result.getString("cause"))
        assertFalse(result.getBoolean("proven_tapstop_text_loss"))
    }
    @Test fun manualChangeBeforeInsertionInvalidatesOffsets() {
        val result = DelayedComparison.compare(trace(), EditorReadback("edit" + trace().text, 0, 0, 0))
        assertEquals("PREFIX_CHANGED_OFFSETS_UNCERTAIN", result.getString("reason"))
        assertEquals("DELAYED_NOT_VERIFIABLE", result.getString("status"))
    }
    @Test fun manualEditInsideRangeIsObservationNotTapStopFailure() {
        val t = trace("abc ")
        val result = DelayedComparison.compare(t, EditorReadback("vor axc ", 8, 8, 0))
        assertEquals("DELAYED_TEXT_MISMATCH", result.getString("status"))
        assertFalse(result.getBoolean("proven_tapstop_text_loss"))
    }
    @Test fun unknownOffsetTruncationAndUnavailableConnectionRemainUnverifiable() {
        val t = trace()
        assertEquals("DELAYED_CONNECTION_UNAVAILABLE", DelayedComparison.compare(t, null).getString("status"))
        for (read in listOf(EditorReadback(t.text, 0, 0, -1), EditorReadback("vor Über", 8, 8, 0), EditorReadback("later", 0, 0, 50))) {
            assertEquals("DELAYED_NOT_VERIFIABLE", DelayedComparison.compare(t, read).getString("status"))
        }
        t.immediateConfirmed = false
        assertEquals("DELAYED_NOT_VERIFIABLE", DelayedComparison.compare(t, t.baseline).getString("status"))
    }
    @Test fun sessionRestartNewFieldFocusEventAndNewInsertionInvalidateOldWork() {
        val service = Robolectric.buildService(TextInsertService::class.java).create().get()
        val input = service.onCreateInputMethod()
        input.onStartInput(EditorInfo(), false)
        val t = trace().apply { session = service.diagnosticSession }
        assertNull(service.diagnosticGate(t))
        input.onStartInput(EditorInfo(), true)
        assertEquals("INPUT_SESSION_CHANGED", service.diagnosticGate(t))
        t.session = service.diagnosticSession
        service.onAccessibilityEvent(android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED))
        assertEquals("FOCUS_EVENT_CHANGED", service.diagnosticGate(t))
        t.focusEpoch = 1
        assertNull(service.diagnosticGate(t))
        assertFalse(service.insert("new"))
        assertEquals("NEW_INSERT_CHANGED", service.diagnosticGate(t))
        t.insertEpoch = 1
        input.onFinishInput()
        assertEquals("INPUT_SESSION_UNAVAILABLE", service.diagnosticGate(t))
        service.onDestroy()
        assertEquals("SERVICE_DESTROYED", service.diagnosticGate(t))
    }
    @Test fun markerPersistsCandidateAfterInsertFinishesAndSignalsStaleAssociation() {
        DelayedInsertDiagnostics.setEnabled(context, true)
        val t = trace().apply { targetPackage = "com.example" }
        DelayedInsertDiagnostics.finish(context, t, "DIRECT_INPUT_CONNECTION_SUCCESS")
        val notice = DelayedInsertDiagnostics.mark(context)
        assertTrue(notice.contains(t.id.take(8)))
        assertTrue(DelayedInsertDiagnostics.awaitWriter())
        val marker = File(context.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).listFiles()!!.filter { it.name.endsWith(".jsonl") }
            .flatMap { it.readLines() }.map { JSONObject(it) }.last { it.getString("event") == "MANUAL_ERROR_MARKER" }
        assertEquals(t.timestamp, marker.getLong("insert_timestamp_ms"))
        assertFalse(marker.has("text_chars")); assertFalse(marker.has("immediate_confirmed"))
        context.getSharedPreferences(DelayedInsertDiagnostics.PREFS, Context.MODE_PRIVATE).edit().putLong("last_time", 1).commit()
        assertTrue(DelayedInsertDiagnostics.mark(context).contains("STALE_OR_CLOCK_CHANGED"))
        assertFalse(context.getSharedPreferences(DelayedInsertDiagnostics.PREFS, Context.MODE_PRIVATE).all.toString().contains(t.text))
    }
    @Test fun disabledDiagnosisNeverStoresCleartextAndMarkerCapturesNoEditor() {
        assertNull(DelayedInsertDiagnostics.begin(context, "PRIVATE", "RAW_SECRET", "PRIVATE"))
        assertTrue(DelayedInsertDiagnostics.mark(context).contains("deaktiviert"))
        assertFalse(File(context.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).exists())
        val read = EditorReadback("abc", 3, 3, 0).json()
        assertFalse(read.getBoolean("whole_editor_verified"))
    }
    @Test fun segmentsBoundDiskAndPreserveUnicodeJson() {
        val directory = File(context.cacheDir, "delayed-ring").apply { deleteRecursively() }
        val store = DelayedDiagnosticStore(directory, segmentBytes = 180, segments = 2)
        for (i in 1..10) store.append(JSONObject().put("timestamp_ms", System.currentTimeMillis()).put("text", "Üß😀".repeat(10)).toString(), System.currentTimeMillis())
        val files = directory.listFiles()!!.filter { it.name.endsWith(".jsonl") }
        assertTrue(files.size <= 2)
        assertTrue(files.sumOf { it.length() } <= 360)
        files.flatMap { it.readLines() }.forEach { assertEquals("Üß😀".repeat(10), JSONObject(it).getString("text")) }
    }
    @Test fun fullAuthorizedTextIsExactButNoCredentialsAudioOrExceptionAreRead() {
        DelayedInsertDiagnostics.setEnabled(context, true)
        val raw = "ÄÖÜ ß 😀\nzweiter Satz"
        val t = DelayedInsertDiagnostics.begin(context, raw + " ", raw, raw)!!
        DelayedInsertDiagnostics.emit(context, t, "IMMEDIATE_READBACK", JSONObject().put("editor", EditorReadback(raw + " ", raw.length + 1, raw.length + 1, 0).json()))
        assertTrue(DelayedInsertDiagnostics.awaitWriter())
        val rows = File(context.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).listFiles()!!.filter { it.name.endsWith(".jsonl") }.flatMap { it.readLines() }.map { JSONObject(it) }
        val begin = rows.single { it.getString("event") == "INSERT_BEGIN" }
        assertEquals(raw, begin.getString("raw_transcript"))
        assertEquals(raw + " ", begin.getString("insert_argument"))
        assertEquals(raw + " ", rows.single { it.getString("event") == "IMMEDIATE_READBACK" }.getJSONObject("editor").getString("text"))
        assertTrue(rows.all { it.getString("insert_id") == t.id })
        assertFalse(rows.toString().contains("api_key")); assertFalse(rows.toString().contains("audio"))
    }

    @Test fun missingPrefixAnchorAndTruncatedDeletionCannotProveMismatch() {
        val t = trace("abc", "vor ")
        t.baseline = EditorReadback("abc", 3, 3, 4)
        val result = DelayedComparison.compare(t, EditorReadback("vor axc", 7, 7, 0))
        assertEquals("DELAYED_NOT_VERIFIABLE", result.getString("status"))
        assertEquals("NO_PREFIX_ANCHOR_RANGE_ALIGNMENT_UNCERTAIN", result.getString("reason"))
        assertEquals("DELAYED_NOT_VERIFIABLE", DelayedComparison.compare(trace("abc", ""), EditorReadback("ab", 2, 2, 0)).getString("status"))
    }

    @Test fun abandonedAtomicWritesCannotEscapeRingLimit() {
        val directory = File(context.cacheDir, "delayed-crash-ring").apply { deleteRecursively(); mkdirs() }
        File(directory, "events-0000000000000001.jsonl.new").writeText("abandoned")
        val store = DelayedDiagnosticStore(directory, segmentBytes = 120, segments = 1)
        store.append(JSONObject().put("timestamp_ms", System.currentTimeMillis()).put("text", "Ü".repeat(30)).toString(), System.currentTimeMillis())
        assertFalse(File(directory, "events-0000000000000001.jsonl.new").exists())
        assertEquals(1, directory.listFiles()!!.size)
    }

}
