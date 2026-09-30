package de.kf.blitztext

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Inserts into the externally focused browser field; no provider or clipboard writes. */
fun Instrumentation.runBrowserInputChecks(accessibilityOnly: Boolean) {
    try {
        sendStatus(1, Bundle().apply { putString("stream", "READY_ACCESSIBILITY\n") })
        val deadline = System.currentTimeMillis() + 20000
        while (TextInsertService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val service = checkNotNull(TextInsertService.instance)
        val originalFlags = service.serviceInfo.flags
        try {
            if (accessibilityOnly) {
                runOnMainSync {
                    service.serviceInfo = service.serviceInfo.apply {
                        flags = flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR.inv()
                    }
                }
            }
            Thread.sleep(1200)
            var inserted = false
            var connectionAvailable = false
            runOnMainSync {
                connectionAvailable = android.os.Build.VERSION.SDK_INT >= 33 &&
                    service.inputMethod?.currentInputConnection != null
                service.rememberFocusedField()
                inserted = service.insert("Blitzprobe ")
            }
            check(inserted) { "Direct insertion failed (would trigger clipboard)" }
            if (accessibilityOnly) check(!connectionAvailable) { "IME was not disabled" }
            // Keep the service alive for WebView's delayed cursor repair.
            Thread.sleep(700)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: direct insertion; connectionAvailable=$connectionAvailable; accessibilityOnly=$accessibilityOnly\n")
            })
        } finally {
            runOnMainSync { service.serviceInfo = service.serviceInfo.apply { flags = originalFlags } }
        }
    } catch (e: Throwable) {
        finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${e.stackTraceToString()}") })
    }
}
