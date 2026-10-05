package de.kf.blitztext

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Deliberately no deInit: this SDK cannot reliably initialize twice in one process. */
internal interface NativeWhisper {
    fun listener(listener: NativeWhisperListener)
    fun start(audio: FileInputStream)
    fun stop()
}

internal interface NativeWhisperListener {
    fun transcription(text: String, language: String?, final: Boolean, code: Int)
    fun error(code: Int)
    fun finished()
}

internal data class LocalTranscript(val text: String, val language: String?, val sttMs: Long)

/** One process owner, one worker, one native initialization, arbitrarily many serial sessions. */
internal class QualcommSession(private val timeoutMs: Long = 75_000) {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "TapStopQualcomm").apply { isDaemon = true } }
    @Volatile private var engine: NativeWhisper? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var active: Utterance? = null
    @Volatile var initializationAttempted = false
        private set
    val ready: Boolean get() = engine != null && failure == null
    val terminalFailure: Throwable? get() = failure

    fun prepare(initialize: () -> NativeWhisper) = await {
        checkHealthy()
        if (engine == null) {
            check(!initializationAttempted) { RESTART_MESSAGE }
            initializationAttempted = true
            try { engine = initialize() }
            catch (e: Throwable) { failure = e; throw IOException("Lokale Runtime konnte nicht initialisiert werden. $RESTART_MESSAGE", e) }
        }
    }

    fun transcribe(file: File): LocalTranscript = await {
        checkHealthy()
        val native = engine ?: throw IOException("Lokale Runtime ist noch nicht initialisiert.")
        val utterance = Utterance()
        // stop() waits for the preceding SDK session to drain, always on this worker.
        try { native.stop() } catch (e: Throwable) { failure = e; throw IOException(RESTART_MESSAGE, e) }
        FileInputStream(file).use { stream ->
            active = utterance
            try {
                native.listener(utterance)
                utterance.startedNanos = System.nanoTime()
                native.start(stream) // One complete WAV. Internal SDK VAD is untouched.
                if (!utterance.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    val error = IOException("Lokale Transkription hat das Zeitlimit überschritten. $RESTART_MESSAGE")
                    failure = error
                    utterance.fail(error)
                }
                utterance.result()
            } finally {
                active = null
                // Never call stop from a VoiceAI callback: it can deadlock the SDK thread.
                try { native.stop() }
                catch (e: Throwable) { failure = e; throw IOException("Lokale Sitzung konnte nicht beendet werden. $RESTART_MESSAGE", e) }
            }
        }
    }

    fun cancel() { active?.fail(CancellationException("Lokale Transkription abgebrochen.")) }

    private fun checkHealthy() { failure?.let { throw IOException(RESTART_MESSAGE, it) } }

    private fun <T> await(action: () -> T): T {
        checkHealthy()
        val future = worker.submit(Callable(action))
        return try { future.get(timeoutMs + 10_000, TimeUnit.MILLISECONDS) }
        catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is Exception) throw cause
            throw IOException("Qualcomm-Laufzeitfehler. $RESTART_MESSAGE", cause)
        } catch (e: InterruptedException) {
            future.cancel(false)
            cancel()
            Thread.currentThread().interrupt()
            throw CancellationException("Lokale Transkription abgebrochen.")
        } catch (e: TimeoutException) {
            failure = e
            cancel()
            // Do not create another native worker or retry initialization after a hung call.
            throw IOException("Qualcomm-Runtime antwortet nicht. $RESTART_MESSAGE", e)
        }
    }

    private class Utterance : NativeWhisperListener {
        val done = CountDownLatch(1)
        var startedNanos = 0L
        private var endedNanos = 0L
        private val segments = mutableListOf<String>()
        private val languages = linkedSetOf<String>()
        private var error: Throwable? = null
        private var complete = false

        @Synchronized override fun transcription(text: String, language: String?, final: Boolean, code: Int) {
            if (complete || !final) return
            if (code != 0) {
                val label = when (code) { 1 -> "INVALID_SPEECH_TOKEN"; 2 -> "INVALID_LANGUAGE_TOKEN"; else -> "Code $code" }
                error = IOException("VoiceAI hat ungültige Sprachdaten geliefert ($label).")
            } else if (text.trim() == "SPECTROGRAM FAIL") {
                // VoiceAI 2.7.1.0 can report an empty-tail spectrogram failure as a final
                // code-0 payload. It invalidates the whole utterance, including earlier
                // valid VAD segments. Ordinary sentences containing these words are text.
                error = IOException("VoiceAI konnte das Spektrogramm nicht berechnen (SPECTROGRAM FAIL).")
            } else {
                // Preserve the SDK's text, adding only a separator between final VAD segments.
                if (text.isNotBlank()) segments.add(text.trim())
                language?.takeIf { it.isNotBlank() }?.let(languages::add)
            }
        }
        override fun error(code: Int) = fail(IOException("VoiceAI-Spracherkennung fehlgeschlagen (Code $code)."))
        @Synchronized override fun finished() {
            if (complete) return
            complete = true
            endedNanos = System.nanoTime()
            done.countDown()
        }
        @Synchronized fun fail(cause: Throwable) {
            if (complete) return
            error = cause
            finished()
        }
        @Synchronized fun result(): LocalTranscript {
            error?.let { if (it is Exception) throw it else throw IOException(it) }
            val text = segments.joinToString(" ")
            if (text.isBlank()) throw IOException("Lokale Spracherkennung hat kein Transkript geliefert.")
            return LocalTranscript(text, languages.singleOrNull() ?: languages.takeIf { it.isNotEmpty() }?.joinToString(", "),
                ((endedNanos - startedNanos) / 1_000_000).coerceAtLeast(0))
        }
    }

    companion object {
        const val RESTART_MESSAGE = "TapStop vollständig beenden und erneut öffnen; kein CPU- oder Cloud-Fallback."
    }
}
