package de.kf.blitztext

import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalRecordingSnapshotTest {
    private class FinishingCapture(override val file: File) : AudioRecording {
        val stopping = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        var releases = 0
        override val durationMs = 1000L
        override fun start() = Unit
        override fun stop() {
            stopping.countDown()
            check(allowFinish.await(5, TimeUnit.SECONDS)) { "Fixture finalization was not released" }
        }
        override fun release() { releases++ }
    }

    /** Exercise the real service boundary: the SDK is replaced, not the processing pipeline. */
    @Test fun settingsChangedWhileLocalCaptureFinishesCannotTurnStoppedRawIntoCloudRewrite() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(context).apply {
            provider = Provider.QUALCOMM_LOCAL
            mode = Mode.BLITZTEXT
            groqModel = GroqModel.LARGE_V3
            rewriteProvider = Provider.OPENAI
        }
        val dbName = "local-snapshot-test.db"
        val db = UsageStats(context, dbName)
        val singleton = UsageStats::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val original = singleton.get(null)
        singleton.set(null, db)
        val service = Robolectric.buildService(OverlayService::class.java).create().get()
        val file = File.createTempFile("snapshot-fixture-", ".wav", context.cacheDir)
            .apply { writeBytes(PcmWav.header(32_000) + ByteArray(32_000)) }
        val capture = FinishingCapture(file)
        val providerCalls = AtomicInteger()
        val sttCalls = AtomicInteger()
        val cloudCalls = AtomicInteger()
        val raw = "Ein rein lokales Rohdiktat."
        try {
            service.sttProviderFactory = { provider, model, key, _ ->
                providerCalls.incrementAndGet()
                assertEquals(Provider.QUALCOMM_LOCAL, provider)
                assertEquals(GroqModel.LARGE_V3, model)
                assertEquals("", key)
                object : SpeechToTextProvider {
                    override fun transcribe(audio: File): SttResult {
                        sttCalls.incrementAndGet()
                        assertEquals(1000L, PcmWav.validate(audio))
                        return SttResult(raw, "de", 123, Provider.QUALCOMM_LOCAL, "whisper-large-v3-turbo")
                    }
                }
            }
            DictationTrace.connectionFactory = {
                cloudCalls.incrementAndGet()
                error("A stopped local Raw dictation must never reach the cloud")
            }
            field("recorder").set(service, capture)
            field("audioFile").set(service, file)
            field("recordingProvider").set(service, Provider.QUALCOMM_LOCAL)
            field("recordingStarted").setLong(service, SystemClock.elapsedRealtime() - 1000)
            field("phase").set(service, field("phase").type.enumConstants.first { it.toString() == "RECORDING" })

            OverlayService::class.java.getDeclaredMethod("stopRecording").apply { isAccessible = true }.invoke(service)
            assertTrue(capture.stopping.await(2, TimeUnit.SECONDS))
            assertEquals("PROCESSING", field("phase").get(service).toString())
            // The Stop tap already chose Raw. These preferences apply only to a later dictation.
            settings.mode = Mode.PLUS
            settings.provider = Provider.GROQ
            settings.groqModel = GroqModel.LARGE_V3_TURBO
            settings.rewriteProvider = Provider.GROQ
            capture.allowFinish.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (field("phase").get(service).toString() == "IDLE" &&
                    DictationTrace.workers.get() == 0 && DictationTrace.submitted.get() == 0) break
                Thread.sleep(5)
            }
            assertEquals("IDLE", field("phase").get(service).toString())
            databaseExecutor(db).submit {}.get(2, TimeUnit.SECONDS)
            val row = runBlocking { db.read(StatsPeriod.ALL, LocalDate.now()).attempts.single() }
            assertTrue("Raw processing must complete successfully after the settings change", row.success)
            assertEquals(Mode.BLITZTEXT.name, row.mode)
            assertEquals("qualcomm", row.provider)
            assertEquals("whisper-large-v3-turbo", row.model)
            assertEquals(1000L, row.recordingMs)
            assertEquals(123L, row.sttMs)
            assertNull(row.rewriteMs)
            assertEquals(1, providerCalls.get())
            assertEquals(1, sttCalls.get())
            assertEquals(0, cloudCalls.get())
            assertFalse(file.exists())
            assertEquals(1, capture.releases)
            assertEquals(Provider.GROQ, settings.provider)
            assertEquals(Mode.PLUS, settings.mode)
        } finally {
            capture.allowFinish.countDown()
            DictationTrace.connectionFactory = null
            service.onDestroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(150))
            singleton.set(null, original)
            databaseExecutor(db).apply { shutdown(); awaitTermination(3, TimeUnit.SECONDS) }
            db.close()
            context.deleteDatabase(dbName)
            file.delete()
        }
    }

    private fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun databaseExecutor(db: UsageStats) = UsageStats::class.java.getDeclaredField("executor")
        .apply { isAccessible = true }.get(db) as ExecutorService
}
