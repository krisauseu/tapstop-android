package de.kf.blitztext

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TextInsertService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var target: AccessibilityNodeInfo? = null
    private var lastFocused: AccessibilityNodeInfo? = null

    override fun onServiceConnected() { instance = this }
    // Framework callback introduced in Android 13; never invoked on older releases.
    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod = InputMethod(this)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            event.source?.takeIf { it.isEditable && it.isFocused }?.let { lastFocused = it }
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        target = null
        lastFocused = null
        instance = null
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

    fun insert(text: String): Boolean {
        val node = target ?: return false
        target = null
        return try {
            if (!node.refresh() || !node.isEditable || !node.isFocused) return false
            // Nur explizit gemeldete Hints ignorieren. Mehrdeutiger Web-Text darf
            // den direkten Weg nicht sperren; vorhandenen Inhalt beibehalten.
            val old = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val start = node.textSelectionStart.takeIf { it in 0..old.length } ?: old.length
            val end = node.textSelectionEnd.takeIf { it in 0..old.length } ?: start
            val from = minOf(start, end)
            val to = maxOf(start, end)
            val combined = old.substring(0, from) + text + old.substring(to)
            if (Build.VERSION.SDK_INT >= 33 && tryCommitText(node, text)) return true
            // Derselbe Snapshot macht SET_TEXT auch nach einem nicht bestätigten
            // commitText idempotent: das Transkript nicht ein zweites Mal anhängen.
            if (!node.refresh() || !node.isEditable || !node.isFocused) return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            val cursor = from + text.length
            runCatching { setCursor(node, cursor) }
            // WebView-Editoren können die Auswahl nach ACTION_SET_TEXT asynchron
            // auf 0 zurücksetzen. Nur beim unveränderten Text korrigieren.
            main.postDelayed({ repairCursor(node, combined, cursor) }, 100)
            main.postDelayed({ repairCursor(node, combined, cursor) }, 450)
            true
        } catch (_: Exception) { false }
    }

    @android.annotation.TargetApi(33)
    private fun tryCommitText(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val input = inputMethod
        val connection = input?.currentInputConnection
        if (connection == null || input.currentInputEditorInfo?.packageName != node.packageName?.toString()) {
            false
        } else if (node.packageName?.toString() == packageName) {
            // Eigene Editoren teilen unseren Main-Thread: eine synchrone
            // Rückfrage würde ihre Verarbeitung des Commits blockieren.
            connection.commitText(text, 1, null)
            true
        } else if (connection.getSurroundingText(0, 0, 0) == null) {
            // Ein vorhandenes Connection-Objekt kann bereits ungültig sein.
            false
        } else {
            connection.commitText(text, 1, null)
            // AccessibilityInputConnection.commitText liefert keinen Erfolgswert.
            // Die nachfolgende Abfrage läuft nach dem Commit im Ziel-Editor.
            val after = connection.getSurroundingText(text.length, 0, 0)
            after != null && after.selectionStart == after.selectionEnd &&
                after.selectionStart in text.length..after.text.length &&
                after.text.subSequence(0, after.selectionStart).endsWith(text)
        }
    } catch (_: Exception) {
        // Ein defekter IME-Pfad darf den Accessibility-Versuch nicht überspringen.
        false
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
        @Volatile var instance: TextInsertService? = null
            private set
    }
}
