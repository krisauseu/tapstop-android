package de.kf.tapstop

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleConfig
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList

/** Checks framework resource matching on device without changing the system language. */
internal fun Instrumentation.runLocalizationChecks() {
    try {
        if (Build.VERSION.SDK_INT >= 33) {
            val config = LocaleConfig(targetContext)
            check(config.status == LocaleConfig.STATUS_SUCCESS)
            val tags = config.supportedLocales?.toLanguageTags()
            check(tags?.split(',')?.toSet() == setOf("en", "de")) { "Unexpected locale config: $tags" }
        }
        val cases = mapOf(
            "de-DE" to "de", "de-AT" to "de", "de-CH" to "de",
            "en-GB" to "en", "en-US" to "en", "fr-FR" to "en", "ja-JP" to "en",
            "fr-FR,de-AT,en-GB" to "de", "fr-FR,en-GB,de-AT" to "en",
            "en-AU,de-DE" to "en", "de-CH,en-US" to "de"
        )
        for ((tags, language) in cases) {
            val context = targetContext.createConfigurationContext(Configuration().apply {
                setLocales(LocaleList.forLanguageTags(tags))
            })
            check(context.getString(R.string.settings) == if (language == "de") "Einstellungen" else "Settings") {
                "Wrong settings language for $tags"
            }
            val locale = context.statisticsLocale()
            check(locale.language == language)
            val plurals = context.statisticsPlurals(locale)
            check(plurals.getQuantityString(R.plurals.word_count, 0, "0") == if (language == "de") "0 Wörter" else "0 words")
            check(plurals.getQuantityString(R.plurals.word_count, 1, "1") == if (language == "de") "1 Wort" else "1 word")
        }
        finish(Activity.RESULT_OK, Bundle().apply {
            putString("stream", "PASS: locale configuration, ${cases.size} locale lists, fallback and plurals\n")
        })
    } catch (e: Throwable) {
        finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${e.stackTraceToString()}") })
    }
}
