package de.kf.tapstop

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/** Replays the original bytes through production STT on-device, without changing settings or stats. */
internal fun Instrumentation.runGeminiDeviceReplay(arguments: Bundle) {
    val report = StringBuilder()
    try {
        val audio = File(targetContext.cacheDir, "gemini-acceptance.m4a")
        val expectedHash = requireNotNull(arguments.getString("audioSha256"))
        val sentence = requireNotNull(arguments.getString("expectedSentence"))
        require(Regex("[0-9a-f]{64}").matches(expectedHash) && sentence.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(audio.inputStream(), digest).use { input ->
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) Unit
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Unexpected audio SHA-256" }
        val settings = Settings(targetContext)
        val providers = listOf(Provider.GEMINI, Provider.OPENAI)
        val keys = providers.associateWith {
            settings.apiKey(it).also { key -> check(key.isNotBlank()) { "${it.label} key unavailable" } }
        }
        fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        for (run in 1..3) {
            for (provider in if (run % 2 == 1) providers else providers.reversed()) {
                val result = SpeechToTextProviders.create(targetContext, provider, settings.groqModel,
                    keys.getValue(provider), DictationTrace.create(), settings.personalDictionary).transcribe(audio)
                val match = " ${normalize(sentence)} " in " ${normalize(result.rawTranscript)} "
                val line = "${result.model} run=$run stt_ms=${result.sttMs} target_correct=$match\n"
                report.append(line)
                sendStatus(1, Bundle().apply { putString("stream", line) })
            }
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
    } catch (e: Exception) {
        finish(Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "${report}FAILED: ${e.javaClass.simpleName}\n")
        })
    }
}
