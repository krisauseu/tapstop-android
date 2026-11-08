package de.kf.tapstop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TextInsertService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var bubbleOverlay: BubbleOverlay? = null
    private var target: AccessibilityNodeInfo? = null
    private var lastFocused: AccessibilityNodeInfo? = null
    @Volatile internal var diagnosticSession = 0L
    @Volatile internal var diagnosticSessionActive = false
    @Volatile private var diagnosticFocusEpoch = 0L
    @Volatile private var diagnosticInsertEpoch = 0L
    @Volatile private var diagnosticDestroyed = false
    private val diagnosticReader = java.util.concurrent.ThreadPoolExecutor(1, 1, 30,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(3),
        { task -> Thread(task, "TapStopDelayedReadback").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy())

    override fun onServiceConnected() {
        connectedService.value = this
        OverlayService.active.value?.attachBubble(this)
    }

    internal fun showBubble(recording: OverlayService) {
        if (bubbleOverlay?.recording === recording) return
        hideBubble()
        bubbleOverlay = BubbleOverlay(this, recording).also { it.attach() }
    }

    internal fun renderBubble(recording: OverlayService) {
        bubbleOverlay?.takeIf { it.recording === recording }?.render()
    }

    internal fun hideBubble(recording: OverlayService? = null) {
        val overlay = bubbleOverlay ?: return
        if (recording != null && overlay.recording !== recording) return
        bubbleOverlay = null
        overlay.detach()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubbleOverlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnectOverlay()
        return super.onUnbind(intent)
    }

    private fun disconnectOverlay() {
        hideBubble()
        if (connectedService.value === this) connectedService.value = null
    }

    // Framework callback introduced in Android 13; never invoked on older releases.
    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: android.view.inputmethod.EditorInfo, restarting: Boolean) {
            diagnosticSession++; diagnosticSessionActive = true
            super.onStartInput(attribute, restarting)
        }
        override fun onFinishInput() {
            diagnosticSession++; diagnosticSessionActive = false
            super.onFinishInput()
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) diagnosticFocusEpoch++
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            event.source?.takeIf { it.isEditable && it.isFocused }?.let { lastFocused = it }
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        diagnosticDestroyed = true
        diagnosticSession++; diagnosticSessionActive = false
        diagnosticReader.shutdownNow()
        target = null
        lastFocused = null
        disconnectOverlay()
        super.onDestroy()
    }

    fun rememberFocusedField() {
        target = findFocusedEditableField()
    }

    // Metadata only: read the package of the exact target already selected above.
    fun statisticsTargetPackage(): String? = target?.packageName?.toString()

    private fun findFocusedEditableField(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (active?.isEditable == true && active.isFocused) return active
        // Beim Tap auf die Overlay-Blase kann das gerade berührte Fenster aktiv
        // sein, während das fremde Eingabefeld weiterhin den Eingabefokus hat.
        windows.asSequence().filter { it.isFocused || it.isActive }.forEach { window ->
            val node = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node?.isEditable == true && node.isFocused) return node
        }
        return lastFocused?.takeIf { it.refresh() && it.isEditable && it.isFocused }
    }

    fun insert(text: String): Boolean = insert(text, null)

    internal fun insert(text: String, diagnostic: InsertAttempt?): Boolean {
        diagnosticInsertEpoch++
        val node = target ?: run { diagnostic?.step(InsertStep.TARGET_MISSING); return false }
        target = null
        return try {
            if (diagnostic != null) runCatching { diagnostic.target(node.packageName?.toString()) }
            if (!node.refresh() || !node.isEditable || !node.isFocused) {
                diagnostic?.step(InsertStep.TARGET_INVALID)
                return false
            }
            diagnostic?.step(InsertStep.TARGET_VALID)
            // Nur explizit gemeldete Hints ignorieren. Mehrdeutiger Web-Text darf
            // den direkten Weg nicht sperren; vorhandenen Inhalt beibehalten.
            val old = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val start = node.textSelectionStart.takeIf { it in 0..old.length } ?: old.length
            val end = node.textSelectionEnd.takeIf { it in 0..old.length } ?: start
            val from = minOf(start, end)
            val to = maxOf(start, end)
            val combined = old.substring(0, from) + text + old.substring(to)
            runCatching {
                diagnostic?.delayed?.let { delayed ->
                    delayed.session = diagnosticSession; delayed.focusEpoch = diagnosticFocusEpoch
                    delayed.insertEpoch = diagnosticInsertEpoch
                    DelayedInsertDiagnostics.emit(this, delayed, "PRE_INSERT_EDITOR", org.json.JSONObject()
                        .put("pre_editor_text", if (node.isPassword) org.json.JSONObject.NULL else old)
                        .put("selection_start", start).put("selection_end", end)
                        .put("scope", "ACCESSIBILITY_SNAPSHOT_NOT_GUARANTEED_COMPLETE"))
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && tryCommitText(node, text, diagnostic)) return true
            if (Build.VERSION.SDK_INT < 33) diagnostic?.step(InsertStep.INPUT_CONNECTION_UNSUPPORTED)
            // Derselbe Snapshot macht SET_TEXT auch nach einem nicht bestätigten
            // commitText idempotent: das Transkript nicht ein zweites Mal anhängen.
            if (!node.refresh() || !node.isEditable || !node.isFocused) {
                diagnostic?.step(InsertStep.ACCESSIBILITY_TARGET_INVALID)
                return false
            }
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined) }
            diagnostic?.step(InsertStep.ACCESSIBILITY_ATTEMPTED)
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                diagnostic?.step(InsertStep.ACCESSIBILITY_REJECTED)
                return false
            }
            val cursor = from + text.length
            runCatching { setCursor(node, cursor) }
            // WebView-Editoren können die Auswahl nach ACTION_SET_TEXT asynchron
            // auf 0 zurücksetzen. Nur beim unveränderten Text korrigieren.
            main.postDelayed({ repairCursor(node, combined, cursor) }, 100)
            main.postDelayed({ repairCursor(node, combined, cursor) }, 450)
            diagnostic?.step(InsertStep.ACCESSIBILITY_SUCCESS)
            diagnostic?.delayed?.path = "ACCESSIBILITY"
            true
        } catch (_: Exception) { diagnostic?.step(InsertStep.INSERT_EXCEPTION); false }
    }

    @android.annotation.TargetApi(33)
    private fun tryCommitText(node: AccessibilityNodeInfo, text: String, diagnostic: InsertAttempt?): Boolean = try {
        val input = inputMethod
        val connection = input?.currentInputConnection
        if (connection != null) diagnostic?.step(InsertStep.INPUT_CONNECTION_AVAILABLE)
        if (connection == null || input.currentInputEditorInfo?.packageName != node.packageName?.toString()) {
            diagnostic?.step(if (connection == null) InsertStep.INPUT_CONNECTION_UNAVAILABLE else InsertStep.INPUT_CONNECTION_PACKAGE_MISMATCH)
            false
        } else if (node.packageName?.toString() == packageName) {
            // Eigene Editoren teilen unseren Main-Thread: eine synchrone
            // Rückfrage würde ihre Verarbeitung des Commits blockieren.
            diagnostic?.step(InsertStep.INPUT_CONNECTION_COMMIT_ATTEMPTED)
            connection.commitText(text, 1, null)
            diagnostic?.step(InsertStep.INPUT_CONNECTION_ACCEPTED_UNVERIFIED)
            diagnostic?.delayed?.path = "INPUT_CONNECTION_OWN_UNVERIFIED"
            true
        } else if (connection.getSurroundingText(0, 0, 0) == null) {
            // Ein vorhandenes Connection-Objekt kann bereits ungültig sein.
            diagnostic?.step(InsertStep.INPUT_CONNECTION_PROBE_EMPTY)
            false
        } else {
            diagnostic?.step(InsertStep.INPUT_CONNECTION_COMMIT_ATTEMPTED)
            connection.commitText(text, 1, null)
            // AccessibilityInputConnection.commitText liefert keinen Erfolgswert.
            // Die nachfolgende Abfrage läuft nach dem Commit im Ziel-Editor.
            val committedAt = android.os.SystemClock.elapsedRealtime()
            val after = connection.getSurroundingText(text.length, 0, 0)
            val confirmed = after != null && after.selectionStart == after.selectionEnd &&
                after.selectionStart in text.length..after.text.length &&
                after.text.subSequence(0, after.selectionStart).endsWith(text)
            diagnostic?.step(if (confirmed) InsertStep.INPUT_CONNECTION_CONFIRMED else InsertStep.INPUT_CONNECTION_UNCONFIRMED)
            runCatching {
                diagnostic?.delayed?.let { delayed ->
                    delayed.path = "INPUT_CONNECTION"
                    delayed.immediateConfirmed = confirmed
                    delayed.baseline = EditorReadback.from(after)
                    if (confirmed && after!!.offset >= 0) delayed.insertionStart = after.offset + after.selectionStart - text.length
                    DelayedInsertDiagnostics.emit(this, delayed, "IMMEDIATE_READBACK", org.json.JSONObject()
                        .put("status", if (confirmed) "IMMEDIATE_CONFIRMED" else "IMMEDIATE_UNCONFIRMED")
                        .put("editor", if (node.isPassword) org.json.JSONObject.NULL else delayed.baseline?.json() ?: org.json.JSONObject.NULL))
                    if (confirmed) scheduleDiagnosticReadbacks(node, delayed, committedAt)
                }
            }
            confirmed
        }
    } catch (_: Exception) {
        // Ein defekter IME-Pfad darf den Accessibility-Versuch nicht überspringen.
        diagnostic?.step(InsertStep.INPUT_CONNECTION_EXCEPTION)
        false
    }

    // No retained connection across timers: acquire only after validating the original session.
    // The worker's wrapper is used for a single read and released; no retry or lifetime extension.
    @android.annotation.TargetApi(33)
    private fun scheduleDiagnosticReadbacks(sourceNode: AccessibilityNodeInfo, trace: DelayedInsertTrace, committedAt: Long) {
        // refresh() mutates a node snapshot. Give the serial reader its own copy so it
        // never refreshes a node also used by lastFocused/the production main thread.
        val node = AccessibilityNodeInfo.obtain(sourceNode)
        val password = sourceNode.isPassword
        for (delay in listOf(250L, 1000L, 3000L)) {
            main.postDelayed({
                if (!DelayedInsertDiagnostics.enabled(this)) return@postDelayed
                val reason = diagnosticGate(trace)
                fun unavailable(reason: String, status: String = "DELAYED_NOT_VERIFIABLE") {
                    DelayedInsertDiagnostics.emit(this, trace, "DELAYED_READBACK", org.json.JSONObject()
                        .put("scheduled_ms", delay).put("since_commit_ms", android.os.SystemClock.elapsedRealtime() - committedAt)
                        .put("status", status).put("reason", reason))
                }
                if (reason != null) {
                    unavailable(reason, if (reason.contains("CHANGED")) "DELAYED_TARGET_CHANGED" else "DELAYED_NOT_VERIFIABLE")
                    return@postDelayed
                }
                val input = inputMethod
                val connection = input?.currentInputConnection
                if (connection == null) { unavailable("NO_CURRENT_CONNECTION", "DELAYED_CONNECTION_UNAVAILABLE"); return@postDelayed }
                if (input.currentInputEditorInfo?.packageName != trace.targetPackage) {
                    unavailable("EDITOR_PACKAGE_CHANGED", "DELAYED_TARGET_CHANGED"); return@postDelayed
                }
                if (password || trace.text.length > DelayedInsertDiagnostics.MAX_TEXT) {
                    unavailable("PASSWORD_OR_TEXT_LIMIT"); return@postDelayed
                }
                try { diagnosticReader.execute {
                    val readStarted = android.os.SystemClock.elapsedRealtime()
                    var editor: EditorReadback? = null
                    var failure: String? = diagnosticGate(trace)
                    if (failure == null) {
                        try {
                            // Both node identity and focused node are checked off the main thread.
                            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            failure = when {
                                !node.refresh() -> "TARGET_REFRESH_FAILED"
                                !node.isFocused -> "FOCUS_LOST"
                                focused == null -> "CURRENT_FOCUS_UNAVAILABLE"
                                focused != node -> "FOCUSED_NODE_CHANGED"
                                else -> null
                            }
                            if (failure == null) editor = EditorReadback.from(connection.getSurroundingText(
                                DelayedInsertDiagnostics.MAX_TEXT, DelayedInsertDiagnostics.MAX_TEXT, 0))
                            val focusedAfter = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            failure = diagnosticGate(trace) ?: failure ?: when {
                                !node.refresh() || !node.isFocused -> "FOCUS_LOST_DURING_READ"
                                focusedAfter != node -> "FOCUSED_NODE_CHANGED_DURING_READ"
                                else -> null
                            }
                        } catch (_: Exception) { failure = "READ_EXCEPTION" }
                    }
                    // Revalidate on main after callbacks queued during the IPC have been delivered.
                    val readFinished = android.os.SystemClock.elapsedRealtime()
                    val comparison = if (failure == null) DelayedComparison.compare(trace, editor) else null
                    val editorJson = if (failure == null) editor?.json() else null
                    main.post {
                        if (!DelayedInsertDiagnostics.enabled(this)) return@post
                        val invalid = diagnosticGate(trace) ?: failure
                        val data = if (invalid == null) comparison!! else org.json.JSONObject()
                            .put("status", when {
                                invalid.startsWith("FOCUS_LOST") -> "DELAYED_FOCUS_LOST"
                                invalid.contains("CHANGED") -> "DELAYED_TARGET_CHANGED"
                                else -> "DELAYED_NOT_VERIFIABLE"
                            }).put("reason", invalid)
                        if (data.optString("status") == "DELAYED_CONFIRMED" && editor != null) trace.baseline = editor
                        data.put("scheduled_ms", delay).put("read_started_since_commit_ms", readStarted - committedAt)
                            .put("read_finished_since_commit_ms", readFinished - committedAt)
                            .put("read_duration_ms", readFinished - readStarted)
                            .put("editor", if (invalid == null) editorJson ?: org.json.JSONObject.NULL else org.json.JSONObject.NULL)
                            .put("discarded_unattributable_readback", invalid != null && editor != null)
                        DelayedInsertDiagnostics.emit(this, trace, "DELAYED_READBACK", data)
                    }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { unavailable("READ_QUEUE_SATURATED_OR_STOPPED") }
            }, (committedAt + delay - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    internal fun diagnosticGate(trace: DelayedInsertTrace): String? = when {
        diagnosticDestroyed -> "SERVICE_DESTROYED"
        trace.insertEpoch != diagnosticInsertEpoch -> "NEW_INSERT_CHANGED"
        !diagnosticSessionActive -> "INPUT_SESSION_UNAVAILABLE"
        trace.session != diagnosticSession -> "INPUT_SESSION_CHANGED"
        trace.focusEpoch != diagnosticFocusEpoch -> "FOCUS_EVENT_CHANGED"
        else -> null
    }

    private fun repairCursor(original: AccessibilityNodeInfo, expectedText: String, cursor: Int) {
        val current = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: original.takeIf { it.refresh() && it.isFocused }
        if (current?.isEditable == true && current.text?.toString() == expectedText &&
            current.textSelectionStart == 0 && current.textSelectionEnd == 0) {
            runCatching { setCursor(current, cursor) }
        }
    }

    private fun setCursor(node: AccessibilityNodeInfo, position: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
        })
    }

    companion object {
        private val connectedService = MutableStateFlow<TextInsertService?>(null)
        internal val connection = connectedService.asStateFlow()
        val instance: TextInsertService? get() = connection.value
    }
}
