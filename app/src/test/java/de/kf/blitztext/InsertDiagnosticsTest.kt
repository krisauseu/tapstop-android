package de.kf.blitztext

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [InsertDiagnosticsTest.ServiceShadow::class, InsertDiagnosticsTest.InputShadow::class, InsertDiagnosticsTest.ConnectionShadow::class])
class InsertDiagnosticsTest {
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation fun getInputMethod(): InputMethod? = input
    }
    @Implements(InputMethod::class)
    class InputShadow {
        @Implementation fun getCurrentInputConnection(): InputMethod.AccessibilityInputConnection? = connection
        @Implementation fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().apply { packageName = editorPackage }
    }
    @Implements(InputMethod.AccessibilityInputConnection::class)
    class ConnectionShadow {
        @Implementation fun commitText(text: CharSequence, cursor: Int, attribute: TextAttribute?) {
            commits++
            if (commitThrows) throw IllegalStateException("PRIVATE_EXCEPTION_CONTENT")
            if (loseFocusOnCommit) focusedNode?.isFocused = false
        }
        @Implementation fun getSurroundingText(before: Int, after: Int, flags: Int): SurroundingText? {
            probes++
            if (probeEmpty || (probes > 1 && !confirmed)) return null
            return if (probes == 1) SurroundingText("", 0, 0, 0)
                else SurroundingText("PRIVATE_DICTATION ", 18, 18, 0)
        }
    }
    companion object {
        var input: InputMethod? = null
        var connection: InputMethod.AccessibilityInputConnection? = null
        var editorPackage = "com.example.editor"
        var commits = 0
        var probes = 0
        var commitThrows = false
        var probeEmpty = false
        var confirmed = true
        var loseFocusOnCommit = false
        var focusedNode: AccessibilityNodeInfo? = null
    }
    private lateinit var service: TextInsertService
    private lateinit var node: AccessibilityNodeInfo
    private var actions = 0
    private var acceptAction = true
    private val context get() = RuntimeEnvironment.getApplication()
    private fun attempt() = InsertAttempt(1_800_000_000_000, 18,
        InsertMetadata(1234, 1, Mode.PLUS, Provider.GROQ, GroqModel.LARGE_V3_TURBO, "com.example.editor"))
    private fun retain() = TextInsertService::class.java.getDeclaredField("target").apply { isAccessible = true }.set(service, node)
    private fun connect() {
        input = InputMethod(service)
        connection = InputMethod.AccessibilityInputConnection::class.java.getDeclaredConstructor(InputMethod::class.java, Class.forName("com.android.internal.inputmethod.RemoteAccessibilityInputConnection"))
            .apply { isAccessible = true }.newInstance(input, null)
    }
    @Before fun setUp() {
        DelayedInsertDiagnostics.setEnabled(context, false)
        input = null; connection = null; commits = 0; probes = 0
        commitThrows = false; probeEmpty = false; confirmed = true; editorPackage = "com.example.editor"
        loseFocusOnCommit = false
        service = Robolectric.buildService(TextInsertService::class.java).create().get()
        TextInsertService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.example.editor"; isEditable = true; isFocused = true
            text = ""; setTextSelection(0, 0)
        }
        shadowOf(node).setRefreshReturnValue(true)
        focusedNode = node
        shadowOf(node).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                actions++
                if (acceptAction) node.text = args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                acceptAction
            } else true
        }
        File(context.noBackupFilesDir, InsertDiagnostics.FILE).delete()
    }
    @After fun tearDown() { service.onDestroy(); input = null; connection = null; focusedNode = null }
    private fun insert(trace: InsertAttempt?): Boolean { retain(); return service.insert("PRIVATE_DICTATION ", trace) }
    private fun steps(trace: InsertAttempt) = JSONObject(trace.json(false, null)).getJSONArray("steps").toString()

    @Test fun confirmedInputConnectionSucceedsWithoutAccessibilityOrExtraCommit() {
        connect()
        val trace = attempt()
        assertTrue(insert(trace))
        assertEquals("DIRECT_INPUT_CONNECTION_SUCCESS", JSONObject(trace.json(false, null)).getString("outcome"))
        assertEquals(1, commits); assertEquals(2, probes); assertEquals(0, actions)
        assertTrue(steps(trace).contains("INPUT_CONNECTION_COMMIT_ATTEMPTED"))
        assertTrue(steps(trace).contains("INPUT_CONNECTION_CONFIRMED"))
    }
    @Test fun ownEditorCommitIsExplicitlyUnverified() {
        connect(); node.packageName = context.packageName; editorPackage = context.packageName
        val trace = attempt()
        assertTrue(insert(trace)); assertEquals(0, probes)
        assertEquals("DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED", JSONObject(trace.json(false, null)).getString("outcome"))
    }
    @Test fun unavailableInputConnectionUsesAccessibility() {
        val trace = attempt()
        assertTrue(insert(trace)); assertEquals(1, actions)
        assertEquals("PRIVATE_DICTATION ", node.text.toString())
        assertEquals("DIRECT_ACCESSIBILITY_SUCCESS", JSONObject(trace.json(false, null)).getString("outcome"))
        assertTrue(steps(trace).contains("INPUT_CONNECTION_UNAVAILABLE"))
    }
    @Test fun unconfirmedCommitStillUsesExactlyOneAccessibilityAttempt() {
        connect(); confirmed = false
        val trace = attempt()
        assertTrue(insert(trace)); assertEquals(1, commits); assertEquals(1, actions)
        assertTrue(steps(trace).contains("INPUT_CONNECTION_UNCONFIRMED"))
    }
    @Test fun differentFailuresRemainVisibleBeforeClipboard() {
        for (cause in listOf("missing", "mismatch", "probe", "commit")) {
            connect(); probes = 0; commits = 0; actions = 0; acceptAction = false
            editorPackage = if (cause == "mismatch") "com.other" else "com.example.editor"
            connection = if (cause == "missing") null else connection
            probeEmpty = cause == "probe"; commitThrows = cause == "commit"
            val trace = attempt()
            assertFalse(insert(trace))
            assertEquals("ACCESSIBILITY_REJECTED", trace.clipboardReason())
            val expected = mapOf("missing" to "INPUT_CONNECTION_UNAVAILABLE", "mismatch" to "INPUT_CONNECTION_PACKAGE_MISMATCH",
                "probe" to "INPUT_CONNECTION_PROBE_EMPTY", "commit" to "INPUT_CONNECTION_EXCEPTION").getValue(cause)
            assertTrue(steps(trace), steps(trace).contains(expected))
            trace.step(InsertStep.CLIPBOARD_WRITTEN)
            assertEquals("CLIPBOARD", JSONObject(trace.json(true, "ACCESSIBILITY_REJECTED")).getString("outcome"))
        }
    }
    @Test fun missingAndInvalidTargetsAreDistinctAndNeverCommit() {
        val missing = attempt()
        assertFalse(service.insert("PRIVATE_DICTATION ", missing)); assertEquals("NO_RETAINED_TARGET", missing.clipboardReason())
        node.isFocused = false
        val invalid = attempt()
        assertFalse(insert(invalid)); assertEquals("INVALID_TARGET", invalid.clipboardReason())
        assertEquals(0, commits); assertEquals(0, actions)
    }
    @Test fun targetLostAfterUnconfirmedCommitIsRecordedAtAccessibilityStage() {
        connect(); confirmed = false; loseFocusOnCommit = true
        val trace = attempt()
        assertFalse(insert(trace)); assertEquals(1, commits); assertEquals(0, actions)
        assertEquals("ACCESSIBILITY_TARGET_INVALID", trace.clipboardReason())
        assertTrue(steps(trace).contains("INPUT_CONNECTION_UNCONFIRMED"))
    }
    @Test fun accessibilityExceptionIsRecordedWithoutExceptionMessage() {
        shadowOf(node).setOnPerformActionListener { _, _ -> throw IllegalStateException("PRIVATE_EXCEPTION_CONTENT") }
        val trace = attempt()
        assertFalse(insert(trace)); assertEquals("INSERT_EXCEPTION", trace.clipboardReason())
        assertFalse(trace.json(true, trace.clipboardReason()).contains("PRIVATE_EXCEPTION_CONTENT"))
    }
    @Test fun instrumentationDoesNotChangeReturnValueOrCallCounts() {
        for (hasConnection in listOf(false, true)) for (accept in listOf(false, true)) {
            if (hasConnection) connect() else { input = null; connection = null }
            confirmed = false; acceptAction = accept; actions = 0; commits = 0; probes = 0
            val plain = insert(null)
            val originalCalls = listOf(actions, commits, probes)
            actions = 0; commits = 0; probes = 0
            val diagnosed = insert(attempt())
            assertEquals(plain, diagnosed); assertEquals(originalCalls, listOf(actions, commits, probes))
        }
    }
    @Test fun insertMetadataSkipsStorageWhenBothDiagnosticsAreDisabled() {
        AudioSttDiagnostics.setEnabled(context, false)
        var accessedStorage = false
        val tracked = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File {
                accessedStorage = true
                return super.getNoBackupFilesDir()
            }
        }
        InsertDiagnostics.finish(tracked, attempt(), false, null)
        assertFalse(accessedStorage)
        assertFalse(File(context.noBackupFilesDir, InsertDiagnostics.FILE).exists())
    }
    @Test fun realDeliveryWritesActualClipboardOutcomeAndNoPrivateContents() {
        DelayedInsertDiagnostics.setEnabled(context, true)
        // Fake secrets live in preferences, but the diagnosis never reads them.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("api_key", "PRIVATE_API_KEY").putString("personal_dictionary", "PRIVATE_DICTIONARY").commit()
        val overlay = Robolectric.buildService(OverlayService::class.java).create().get()
        try {
            OverlayService::class.java.getDeclaredMethod("deliver", String::class.java).apply { isAccessible = true }
                .invoke(overlay, "PRIVATE_DICTATION")
            assertEquals("PRIVATE_DICTATION ", (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text)
            val file = File(context.noBackupFilesDir, InsertDiagnostics.FILE)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            val content = file.readText()
            for (secret in listOf("PRIVATE_DICTATION", "PRIVATE_API_KEY", "PRIVATE_DICTIONARY")) assertFalse(content.contains(secret))
            val row = JSONObject(content.trim())
            assertEquals("CLIPBOARD", row.getString("outcome")); assertEquals("NO_RETAINED_TARGET", row.getString("clipboard_reason"))
            assertTrue(row.getJSONArray("steps").toString().contains("CLIPBOARD_WRITTEN"))
        } finally {
            overlay.onDestroy()
            DelayedInsertDiagnostics.setEnabled(context, false)
        }
    }
    @Test fun ringSurvivesReopenAndLimitsCountBytesAndAge() {
        val file = File(context.cacheDir, "insert-ring.jsonl").apply { delete() }
        fun line(time: Long) = InsertAttempt(time, 123, null).json(false, null)
        val store = InsertDiagnosticStore(file, maxEntries = 3, maxBytes = 4096, maxAgeMs = 100)
        (1L..5L).forEach { store.append(line(it), 5) }
        assertEquals(listOf(3L, 4L, 5L), file.readLines().map { JSONObject(it).getLong("timestamp_ms") })
        InsertDiagnosticStore(file, maxEntries = 3, maxBytes = 4096, maxAgeMs = 100).append(line(200), 200)
        assertEquals(1, file.readLines().size)
        val singleBytes = line(201).toByteArray().size + 1
        InsertDiagnosticStore(file, maxEntries = 10, maxBytes = singleBytes + 1).append(line(201), 201)
        assertTrue(file.length() <= singleBytes + 1); assertEquals(1, file.readLines().size)
    }
    @Test fun storageFailureCannotTurnSuccessfulInsertIntoFallback() {
        connect(); val trace = attempt(); assertTrue(insert(trace))
        val broken = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = throw IllegalStateException("PRIVATE_EXCEPTION_CONTENT") }
        DelayedInsertDiagnostics.setEnabled(context, true)
        try {
            InsertDiagnostics.finish(broken, trace, false, null)
        } finally {
            DelayedInsertDiagnostics.setEnabled(context, false)
        }
        assertEquals(1, commits); assertEquals(0, actions)
        assertEquals("DIRECT_INPUT_CONNECTION_SUCCESS", JSONObject(trace.json(false, null)).getString("outcome"))
    }
    @Test fun delayedTimersUseOriginalCommitTimesAndNeverCommitOrMoveSelection() {
        connect()
        DelayedInsertDiagnostics.setEnabled(context, true)
        val trace = attempt().apply { delayed = DelayedInsertDiagnostics.begin(context, "PRIVATE_DICTATION ", "raw Üß", "PRIVATE_DICTATION") }
        assertTrue(insert(trace))
        assertEquals(2, probes); assertEquals(1, commits); assertEquals(0, actions)
        assertTrue(DelayedInsertDiagnostics.awaitWriter())
        fun rows() = File(context.noBackupFilesDir, DelayedInsertDiagnostics.DIRECTORY).listFiles()!!.filter { it.name.endsWith(".jsonl") }
            .flatMap { it.readLines() }.map { JSONObject(it) }.filter { it.optString("insert_id") == trace.delayed!!.id && it.optString("event") == "DELAYED_READBACK" }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(249))
        assertTrue(DelayedInsertDiagnostics.awaitWriter()); assertEquals(0, rows().size)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        assertTrue(DelayedInsertDiagnostics.awaitWriter()); assertEquals(listOf(250), rows().map { it.getInt("scheduled_ms") })
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(750))
        assertTrue(DelayedInsertDiagnostics.awaitWriter()); assertEquals(listOf(250, 1000), rows().map { it.getInt("scheduled_ms") })
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        assertTrue(DelayedInsertDiagnostics.awaitWriter()); assertEquals(listOf(250, 1000, 3000), rows().map { it.getInt("scheduled_ms") })
        assertTrue(rows().all { it.getString("reason") == "INPUT_SESSION_UNAVAILABLE" })
        assertEquals(2, probes); assertEquals(1, commits); assertEquals(0, actions)
        DelayedInsertDiagnostics.setEnabled(context, false)
    }

}
