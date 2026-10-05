package de.kf.blitztext

import android.content.Context
import android.os.SystemClock
import java.io.File

/** STT errors throw; callers record a failed attempt and never select a fallback provider. */
data class SttResult(
    val rawTranscript: String,
    val detectedLanguage: String? = null,
    val sttMs: Long,
    val provider: Provider,
    val model: String,
    val success: Boolean = true
)

interface SpeechToTextProvider {
    /** Blocking preparation; always call on a worker. */
    fun prepare() {}
    fun transcribe(audio: File): SttResult
    fun cancel() {}
}

internal class CloudSpeechToTextProvider(
    private val provider: Provider,
    private val model: GroqModel,
    private val key: String,
    private val client: ApiClient
) : SpeechToTextProvider {
    init { require(provider.isCloud) }
    override fun transcribe(audio: File): SttResult {
        val started = SystemClock.elapsedRealtime()
        val raw = client.transcribe(audio, key)
        return SttResult(raw, sttMs = SystemClock.elapsedRealtime() - started,
            provider = provider, model = provider.sttModel(model))
    }
    override fun cancel() = client.cancel()
}

internal object SpeechToTextProviders {
    fun create(context: Context, provider: Provider, model: GroqModel, key: String,
               diagnostic: DictationTrace? = null): SpeechToTextProvider = when (provider) {
        Provider.OPENAI, Provider.GROQ -> CloudSpeechToTextProvider(provider, model, key,
            ApiClient(provider, model).also { it.diagnostic = diagnostic })
        Provider.QUALCOMM_LOCAL -> QualcommRuntime.get(context)
    }
}

internal data class ProcessedDictation(val stt: SttResult, val text: String, val rewriteMs: Long?)

/** Shared by cloud and local STT. Raw never evaluates the rewrite callback. */
internal class DictationProcessor(
    private val stt: SpeechToTextProvider,
    private val rewrite: (String, Mode) -> String,
    private val cancelRewrite: () -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    fun process(audio: File, mode: Mode, onTranscribed: (SttResult) -> Unit = {}): ProcessedDictation {
        val transcript = stt.transcribe(audio)
        check(transcript.success) { "Spracherkennung fehlgeschlagen." }
        onTranscribed(transcript)
        if (!mode.usesRewrite) return ProcessedDictation(transcript, transcript.rawTranscript, null)
        val started = clock()
        val result = rewrite(transcript.rawTranscript, mode)
        return ProcessedDictation(transcript, result, clock() - started)
    }
    fun cancel() { stt.cancel(); cancelRewrite() }
}
