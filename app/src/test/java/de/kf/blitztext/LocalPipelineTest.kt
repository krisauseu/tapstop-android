package de.kf.blitztext

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPipelineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val raw = "  Mein rohes Diktat äh\nbleibt genau so. "
    private fun local(failure: Boolean = false) = object : SpeechToTextProvider {
        override fun transcribe(audio: File): SttResult {
            if (failure) error("INVALID_SPEECH_TOKEN")
            return SttResult(raw, "de", 900, Provider.QUALCOMM_LOCAL, "whisper-large-v3-turbo")
        }
    }

    @Test fun rawLocalReturnsExactTranscriptAndNeverCallsRewrite() {
        val result = DictationProcessor(local(), { _, _ -> error("Unexpected cloud request") })
            .process(File("unused.wav"), Mode.BLITZTEXT)
        assertEquals(raw, result.text)
        assertNull(result.rewriteMs)
        assertEquals("de", result.stt.detectedLanguage)
        assertEquals(Provider.QUALCOMM_LOCAL, result.stt.provider)
    }

    @Test fun everyRewriteModeReceivesExactLocalTranscriptOnce() {
        for (mode in listOf(Mode.PLUS, Mode.CHAT, Mode.FORMAL)) {
            var calls = 0
            var ticks = 0L
            val result = DictationProcessor(local(), { text, actualMode ->
                calls++; assertEquals(raw, text); assertEquals(mode, actualMode); "Überarbeitet"
            }, clock = { ticks++.let { it * 75 } }).process(File("unused.wav"), mode)
            assertEquals(1, calls)
            assertEquals("Überarbeitet", result.text)
            assertEquals(75L, result.rewriteMs)
        }
    }

    @Test fun localFailurePropagatesWithoutCloudOrCpuFallback() {
        var rewriteCalls = 0
        val failure = runCatching {
            DictationProcessor(local(true), { _, _ -> rewriteCalls++; "wrong" })
                .process(File("unused.wav"), Mode.PLUS)
        }.exceptionOrNull()
        assertEquals("INVALID_SPEECH_TOKEN", failure?.message)
        assertEquals(0, rewriteCalls)
        assertTrue(runCatching { ApiClient(Provider.QUALCOMM_LOCAL, GroqModel.LARGE_V3_TURBO) }.isFailure)
    }

    @Test fun localOnlyRecordingLimitAndOriginalCloudModels() {
        assertEquals(30_000L, Provider.QUALCOMM_LOCAL.maxRecordingMs)
        Provider.cloudProviders.forEach { assertNull(it.maxRecordingMs) }
        assertEquals("whisper-1", Provider.OPENAI.sttModel(GroqModel.LARGE_V3_TURBO))
        assertEquals("whisper-large-v3", Provider.GROQ.sttModel(GroqModel.LARGE_V3))
    }

    @Test fun upgradeDefaultsKeepCloudProviderAndHistoricalRewritePairing() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("provider", "GROQ").putString("mode", "CHAT").apply()
        val before = prefs.all.toMap()
        val settings = Settings(context)
        assertEquals(Provider.GROQ, settings.provider)
        assertEquals(Provider.GROQ, settings.rewriteProvider)
        assertEquals(Mode.CHAT, settings.mode)
        assertTrue(settings.rewriteFollowsStt)
        assertEquals(before, prefs.all)
    }

    @Test fun switchingProvidersPreservesKeysModePositionAndIndependentRewrite() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("api_key", "encrypted-openai-fixture")
            .putString("groq_api_key", "encrypted-groq-fixture")
            .putString("provider", "GROQ").putString("mode", "FORMAL")
            .putInt("bubble_y", 437).putBoolean("bubble_right", false).apply()
        val settings = Settings(context)
        settings.provider = Provider.QUALCOMM_LOCAL
        assertEquals(Provider.GROQ, settings.rewriteProvider)
        assertEquals("", settings.apiKey())
        assertTrue(runCatching { settings.setApiKey("") }.isFailure)
        settings.rewriteProvider = Provider.OPENAI
        Provider.entries.forEach { settings.provider = it; assertEquals(it, Settings(context).provider) }
        assertEquals(Provider.OPENAI, Settings(context).rewriteProvider)
        assertEquals("encrypted-openai-fixture", prefs.getString("api_key", null))
        assertEquals("encrypted-groq-fixture", prefs.getString("groq_api_key", null))
        assertEquals(Mode.FORMAL, settings.mode)
        assertEquals(437, settings.bubbleY)
        assertFalse(settings.bubbleRight)
    }

    @Test fun localStatisticsUseExistingSchemaAndIndependentProviderGroup() = runBlocking {
        val dbName = "local-stats-test.db"
        val db = UsageStats(context, dbName)
        try {
            val today = LocalDate.now()
            val row = DictationStat(today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                22_320, 12, 12, Provider.QUALCOMM_LOCAL.statisticsId, "whisper-large-v3-turbo",
                Mode.BLITZTEXT.name, sttMs = 900, totalMs = 1000, success = true)
            db.record(row)
            val executor = UsageStats::class.java.getDeclaredField("executor").apply { isAccessible = true }
                .get(db) as java.util.concurrent.ExecutorService
            executor.submit {}.get()
            val actual = db.read(StatsPeriod.ALL, today).attempts.single()
            assertEquals(row, actual)
            assertEquals(24.8, sttRealtimeFactor(listOf(actual))!!, 0.00001)
            assertEquals(1, db.readableDatabase.version)
            assertNull(actual.rewriteMs)
            val cloud = actual.copy(provider = "Groq")
            assertEquals(2, listOf(actual, cloud).groupBy { it.provider to it.model }.size)
            executor.shutdown()
        } finally { db.close(); context.deleteDatabase(dbName) }
    }
}
