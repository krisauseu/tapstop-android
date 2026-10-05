package de.kf.blitztext

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class QualcommSessionTest {
    private class FakeWhisper : NativeWhisper {
        lateinit var callback: NativeWhisperListener
        var starts = 0
        var stops = 0
        var code = 0
        var errorCode: Int? = null
        var delayed = false
        var finalText = "Zweiter Satz."
        val entered = CountDownLatch(1)
        val stopThreads = mutableListOf<String>()
        override fun listener(listener: NativeWhisperListener) { callback = listener }
        override fun start(audio: FileInputStream) {
            starts++
            assertEquals(0x52, audio.read()) // Complete canonical WAV, including the header.
            entered.countDown()
            if (!delayed) {
                val completion = thread(name = "FakeNativeCallback") {
                    errorCode?.let { callback.error(it); return@thread }
                    callback.transcription("Erster Satz.", "de", true, code)
                    callback.transcription(finalText, "de", true, 0)
                    callback.finished()
                }
                completion.join()
            }
        }
        override fun stop() { stops++; stopThreads += Thread.currentThread().name }
    }

    private fun withAudio(test: (File) -> Unit) {
        val file = File.createTempFile("qualcomm-fixture-", ".wav")
        try { file.writeBytes(PcmWav.header(32_000) + ByteArray(32_000)); test(file) }
        finally { file.delete() }
    }

    @Test fun oneNativeInitializationSurvivesRepeatedSessionsAndProviderReentry() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper()
        var inits = 0
        repeat(4) {
            session.prepare { inits++; native }
            val result = session.transcribe(file)
            assertEquals("Erster Satz. Zweiter Satz.", result.text)
            assertEquals("de", result.language)
            assertTrue(result.sttMs >= 0)
        }
        assertEquals(1, inits)
        assertEquals(4, native.starts) // No outer chunks or automatic retries.
        assertEquals(8, native.stops)
        assertTrue(native.stopThreads.all { it == "TapStopQualcomm" })
        assertTrue(session.ready)
    }

    @Test fun failedNativeInitializationIsNeverRetriedInTheProcess() {
        val session = QualcommSession()
        var attempts = 0
        repeat(3) {
            val result = runCatching { session.prepare { attempts++; throw IOException("fixture init") } }
            assertTrue(result.isFailure)
        }
        assertEquals(1, attempts)
        assertFalse(session.ready)
        assertNotNull(session.terminalFailure)
    }

    @Test fun interruptedOwnerDuringInitDoesNotDestroyRuntimeOrInitializeAgain() {
        val session = QualcommSession()
        val native = FakeWhisper()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initializations = AtomicInteger()
        val owner = thread {
            runCatching { session.prepare {
                initializations.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                native
            } }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        owner.interrupt()
        owner.join(2000)
        release.countDown()
        session.prepare { initializations.incrementAndGet(); native }
        assertTrue(session.ready)
        assertEquals(1, initializations.get())
    }

    @Test fun invalidSpeechTokenRejectsEntireTranscriptWithoutFallbackAndNextSessionIsPossible() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper().apply { code = 1 }
        session.prepare { native }
        val error = runCatching { session.transcribe(file) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("INVALID_SPEECH_TOKEN"))
        assertEquals(1, native.starts)
        assertEquals(2, native.stops)
        native.code = 0
        assertEquals("Erster Satz. Zweiter Satz.", session.transcribe(file).text)
        assertEquals(2, native.starts)
    }

    @Test fun sdkErrorsAreVisibleAndStopOnWorkerWithoutRetryOrFallback() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper().apply { errorCode = -4 }
        session.prepare { native }
        assertTrue(runCatching { session.transcribe(file) }.exceptionOrNull()?.message.orEmpty().contains("Code -4"))
        assertEquals(1, native.starts)
        assertEquals(2, native.stops)
        assertTrue(native.stopThreads.all { it == "TapStopQualcomm" })
    }

    @Test fun spectrogramFailureSentinelWithSuccessCodeRejectsEarlierValidSegment() = withAudio { file ->
        val session = QualcommSession()
        var starts = 0
        var stops = 0
        val native = object : NativeWhisper {
            lateinit var callback: NativeWhisperListener
            override fun listener(listener: NativeWhisperListener) { callback = listener }
            override fun start(audio: FileInputStream) {
                starts++
                callback.transcription("Gültiger Testabschnitt.", "de", true, 0)
                // VoiceAI 2.7.1.0 emits this exact diagnostic as a successful final callback.
                callback.transcription("SPECTROGRAM FAIL", "de", true, 0)
                callback.finished()
            }
            override fun stop() { stops++ }
        }
        session.prepare { native }
        val result = runCatching { session.transcribe(file) }
        assertTrue("A native diagnostic must fail STT, not become text appended to an earlier valid segment.", result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, starts)
        assertEquals(2, stops)
    }

    @Test fun spectrogramFailureCleansUpAndNextDictationReusesTheSameInitializedRuntime() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper().apply { finalText = "  SPECTROGRAM FAIL\n" }
        var initializations = 0
        session.prepare { initializations++; native }
        val failure = runCatching { session.transcribe(file) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("SPECTROGRAM FAIL"))
        assertTrue(session.ready)
        assertNull(session.terminalFailure)
        native.finalText = "Zweiter Satz."
        session.prepare { initializations++; native }
        assertEquals("Erster Satz. Zweiter Satz.", session.transcribe(file).text)
        assertEquals(1, initializations)
        assertEquals(2, native.starts)
        assertEquals(4, native.stops)
        assertTrue(native.stopThreads.all { it == "TapStopQualcomm" })
    }

    @Test fun ordinaryDictationContainingSpectrogramFailureWordsRemainsUnchanged() = withAudio { file ->
        val session = QualcommSession()
        val sentence = "Im Protokoll steht SPECTROGRAM FAIL als Diagnose."
        val native = FakeWhisper().apply { finalText = sentence }
        session.prepare { native }
        assertEquals("Erster Satz. $sentence", session.transcribe(file).text)
        native.finalText = "spectrogram fail"
        assertEquals("Erster Satz. spectrogram fail", session.transcribe(file).text)
    }

    @Test fun codeZeroSpectrogramFailureNeverReachesRewriteOrPartialTranscriptDelivery() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper().apply { finalText = "SPECTROGRAM FAIL" }
        session.prepare { native }
        val provider = object : SpeechToTextProvider {
            override fun transcribe(audio: File): SttResult {
                val result = session.transcribe(audio)
                return SttResult(result.text, result.language, result.sttMs, Provider.QUALCOMM_LOCAL, "whisper-large-v3-turbo")
            }
        }
        var rewrites = 0
        var partialDeliveries = 0
        val processor = DictationProcessor(provider,
            rewrite = { text, _ -> rewrites++; text }, clock = { 0L })
        assertTrue(runCatching {
            processor.process(file, Mode.PLUS) { partialDeliveries++ }
        }.exceptionOrNull() is IOException)
        assertEquals(0, rewrites)
        assertEquals(0, partialDeliveries)
        assertEquals(1, native.starts)
        assertEquals(2, native.stops)
    }

    @Test fun cancelledSessionStopsOnOwnerWorkerAndAllowsReuse() = withAudio { file ->
        val session = QualcommSession()
        val native = FakeWhisper().apply { delayed = true }
        session.prepare { native }
        val errors = AtomicInteger()
        val caller = thread { runCatching { session.transcribe(file) }.onFailure { errors.incrementAndGet() } }
        assertTrue(native.entered.await(2, TimeUnit.SECONDS))
        session.cancel()
        caller.join(2000)
        assertFalse(caller.isAlive)
        assertEquals(1, errors.get())
        assertEquals(2, native.stops)
        native.delayed = false
        assertEquals("de", session.transcribe(file).language)
        assertTrue(native.stopThreads.all { it == "TapStopQualcomm" })
    }

    @Test fun callsFromDifferentOwnersAreSerialized() = withAudio { file ->
        val session = QualcommSession()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val native = object : NativeWhisper {
            lateinit var callback: NativeWhisperListener
            override fun listener(listener: NativeWhisperListener) { callback = listener }
            override fun start(audio: FileInputStream) {
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                val current = callback
                thread {
                    Thread.sleep(20)
                    current.transcription("Test", null, true, 0)
                    active.decrementAndGet()
                    current.finished()
                }
            }
            override fun stop() = Unit
        }
        session.prepare { native }
        val errors = AtomicInteger()
        val callers = List(3) { thread { runCatching { session.transcribe(file) }.onFailure { errors.incrementAndGet() } } }
        callers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(0, errors.get())
        assertEquals(1, peak.get())
    }

    @Test fun nativeTimeoutMakesProcessTerminalWithoutAnotherStartOrInitialization() = withAudio { file ->
        val session = QualcommSession(timeoutMs = 20)
        val native = FakeWhisper().apply { delayed = true }
        var initializations = 0
        session.prepare { initializations++; native }
        assertTrue(runCatching { session.transcribe(file) }.exceptionOrNull()?.message.orEmpty().contains("Zeitlimit"))
        assertTrue(runCatching { session.prepare { initializations++; native } }.isFailure)
        assertTrue(runCatching { session.transcribe(file) }.isFailure)
        assertEquals(1, initializations)
        assertEquals(1, native.starts)
        assertFalse(session.ready)
    }

    @Test fun canonicalPcmWavHasAnExactThirtySecondMaximum() {
        val file = File.createTempFile("wav-fixture-", ".wav")
        try {
            file.writeBytes(PcmWav.header(PcmWav.MAX_PCM_BYTES) + ByteArray(PcmWav.MAX_PCM_BYTES))
            assertEquals(30_000L, PcmWav.validate(file))
            file.appendBytes(byteArrayOf(0, 0))
            assertTrue(runCatching { PcmWav.validate(file) }.isFailure)
            file.writeBytes(PcmWav.header(320) + ByteArray(320))
            assertEquals(10L, PcmWav.validate(file))
            val malformed = file.readBytes().apply { this[24] = 0 } // Wrong sample rate.
            file.writeBytes(malformed)
            assertTrue(runCatching { PcmWav.validate(file) }.isFailure)
        } finally { file.delete() }
    }
}
