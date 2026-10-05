package de.kf.blitztext

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.IOException

/** Synthetic fixture only. Never reads personal recordings or writes production statistics/settings. */
internal fun StatsInstrumentation.runLocalRuntimeChecks(arguments: Bundle) {
    try {
        val compatibility = QualcommTargets.detect(AndroidQualcommHardware.read())
        val target = checkNotNull(compatibility.target)
        check(compatibility.selectable)
        check(target.id == arguments.getString("expectedTarget"))
        val audio = File(targetContext.getExternalFilesDir(null), "qualcomm-test.wav")
        val duration = PcmWav.validate(audio)
        val runtime = QualcommRuntime.get(targetContext)
        val started = SystemClock.elapsedRealtime()
        runtime.prepare()
        sendStatus(1, Bundle().apply { putString("stream", "LOCAL_READY target=${target.id} init_ms=${SystemClock.elapsedRealtime() - started}\n") })
        if (arguments.getString("expectSpectrogramFailure") == "true") {
            var rewriteCalls = 0
            var partialDeliveries = 0
            val processor = DictationProcessor(runtime, rewrite = { text, _ ->
                rewriteCalls++
                text // In-memory test seam only; no network client is created.
            })
            val failure = runCatching {
                processor.process(audio, Mode.PLUS) { partialDeliveries++ }
            }.exceptionOrNull()
            check(failure is IOException && failure.message.orEmpty().contains("SPECTROGRAM FAIL")) {
                "Expected the real SDK spectrogram failure to be rejected as an IOException."
            }
            check(rewriteCalls == 0 && partialDeliveries == 0) {
                "Failed STT must not reach rewrite or partial transcript delivery."
            }
            check(runtime.status.value.phase == QualcommRuntimePhase.READY) {
                "Runtime must remain ready after the recoverable SDK spectrogram error."
            }
            sendStatus(1, Bundle().apply {
                putString("stream", "LOCAL_EXPECTED_FAILURE audio_ms=$duration error=SPECTROGRAM_FAIL rewrite_calls=0 partial_deliveries=0 runtime_ready=true\n")
            })
            // Explicit separate test dictation, never an automatic production retry.
            val recovery = File(targetContext.getExternalFilesDir(null), "qualcomm-recovery.wav")
            val recoveryDuration = PcmWav.validate(recovery)
            check(QualcommRuntime.get(targetContext) === runtime)
            runtime.prepare() // Reuse the same initialized native process/session owner.
            val result = runtime.transcribe(recovery)
            check(result.success && result.rawTranscript.isNotBlank())
            check(result.provider == Provider.QUALCOMM_LOCAL && result.model == target.modelId)
            check(runtime.status.value.phase == QualcommRuntimePhase.READY)
            sendStatus(1, Bundle().apply {
                putString("stream", "LOCAL_RECOVERY_RUN audio_ms=$recoveryDuration stt_ms=${result.sttMs} language=${result.detectedLanguage} success=true\n")
            })
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS real SDK spectrogram error rejected, no rewrite/partial delivery, explicit recovery in same runtime\n")
            })
            return
        }
        repeat(2) { index ->
            runtime.prepare() // Same process, no native reload.
            val result = runtime.transcribe(audio)
            check(result.success && result.rawTranscript.isNotBlank())
            check(result.provider == Provider.QUALCOMM_LOCAL && result.model == target.modelId)
            sendStatus(1, Bundle().apply {
                putString("stream", "LOCAL_RUN n=${index + 1} audio_ms=$duration stt_ms=${result.sttMs} language=${result.detectedLanguage} success=true\n")
            })
        }
        check(runtime.status.value.phase == QualcommRuntimePhase.READY)
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS local model, target, two serial sessions and process reuse\n") })
    } catch (e: Throwable) {
        finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED local runtime: ${e.javaClass.simpleName}: ${e.message}\n") })
    }
}
