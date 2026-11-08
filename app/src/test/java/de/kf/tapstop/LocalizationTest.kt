package de.kf.tapstop

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.LocaleList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class LocalizationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun localized(tags: String): Context =
        context.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags(tags))
        })

    @Test fun germanAndEnglishVariantsAndUnsupportedFallback() {
        for (tag in listOf("de", "de-DE", "de-AT", "de-CH")) {
            val resources = localized(tag).resources
            assertEquals(tag, "Einstellungen", resources.getString(R.string.settings))
            assertEquals(tag, "Roh", resources.getString(Mode.BLITZTEXT.labelRes))
        }
        for (tag in listOf("en", "en-US", "en-GB", "fr-FR", "ja-JP")) {
            val resources = localized(tag).resources
            assertEquals(tag, "Settings", resources.getString(R.string.settings))
            assertEquals(tag, "Raw", resources.getString(Mode.BLITZTEXT.labelRes))
        }
    }

    @Test fun dynamicMessagesAndPluralsResolveInTheDisplayContext() {
        val model = UiMessage(R.string.model_import_file_missing, "encoder.bin")
        val de = localized("de-AT")
        val en = localized("en-GB")
        assertEquals("encoder.bin fehlt im gewählten Modellordner.", model.resolve(de))
        assertEquals("encoder.bin is missing from the selected model folder.", model.resolve(en))
        for ((locale, forms) in listOf(de to listOf("0 Wörter", "1 Wort", "2 Wörter"),
            en to listOf("0 words", "1 word", "2 words"),
            localized("fr-FR") to listOf("0 words", "1 word", "2 words"),
            localized("ja-JP") to listOf("0 words", "1 word", "2 words"))) {
            val plurals = locale.statisticsPlurals(locale.statisticsLocale())
            forms.forEachIndexed { count, expected ->
                assertEquals(expected, plurals.getQuantityString(R.plurals.word_count, count, count.toString()))
            }
        }
    }

    @Test fun recreationWithNewLocalePreservesSettingsAndVersionOneStatistics() = runBlocking {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("provider", "GEMINI").putString("rewrite_provider", "GROQ")
            .putString("groq_model", "LARGE_V3").putString("personal_dictionary", "TapStop")
            .putInt("bubble_y", 128).commit()
        val name = "locale-fixture.db"
        val today = LocalDate.now()
        UsageStats(context, name).use { db ->
            val values = arrayOf<Any>(System.currentTimeMillis(), 10_000, 1, 1, "Gemini", "gemini-3.5-transcribe", "BLITZTEXT", 1)
            db.writableDatabase.execSQL(
                "INSERT INTO dictations (timestamp, recording_ms, raw_words, final_words, provider, model, mode, success) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                values
            )
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { activity ->
            activity.configurationChange(Configuration(activity.get().resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("de-CH"))
            })
            assertEquals("Einstellungen", activity.get().getString(R.string.settings))
            for ((id, mode) in listOf("BLITZTEXT" to Mode.BLITZTEXT, "PLUS" to Mode.PLUS,
                "CHAT" to Mode.CHAT, "FORMAL" to Mode.FORMAL)) {
                prefs.edit().putString("mode", id).commit()
                val settings = Settings(activity.get())
                assertEquals(mode, settings.mode)
                settings.mode = mode
                assertEquals(id, prefs.getString("mode", null))
                assertEquals(Provider.GEMINI, settings.provider)
                assertEquals(Provider.GROQ, settings.rewriteProvider)
                assertEquals(GroqModel.LARGE_V3, settings.groqModel)
                assertEquals("gemini-3.5-transcribe", settings.provider.sttModel(settings.groqModel))
                assertEquals("TapStop", settings.personalDictionary)
                assertEquals(128, settings.bubbleY)
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(1, it.version)
            }
            UsageStats(activity.get(), name).use { db ->
                val row = db.read(StatsPeriod.ALL, today).successful.single()
                assertEquals("BLITZTEXT", row.mode)
                assertEquals("Gemini", row.provider)
                assertEquals(1, row.rawWords)
            }
        }
        context.deleteDatabase(name)
        Unit
    }
}
