package de.kf.tapstop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class OverlayService : Service() {
    internal enum class Phase { IDLE, PREPARING, RECORDING, PROCESSING }

    internal lateinit var settings: Settings
        private set
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private var processingToken = 0
    private var recordingStarted = 0L
    private var recordingTarget: String? = null
    private var recorder: AudioRecording? = null
    private var recordingProvider: Provider? = null
    private var activeProcessor: DictationProcessor? = null
    internal var sttProviderFactory: (Provider, GroqModel, String, DictationTrace?) -> SpeechToTextProvider =
        { provider, model, key, diagnostic -> SpeechToTextProviders.create(this, provider, model, key, diagnostic) }
    private var audioFile: File? = null
    internal var phase = Phase.IDLE
        private set
    private var trace: DictationTrace? = null
    private var audioTrace: AudioSttTrace? = null
    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("blitztext", "TapStop-Blase", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(1, notification())
            runningService.value = this
            TextInsertService.instance?.let { attachBubble(it) }
        } catch (e: Exception) {
            toast("Blase konnte nicht gestartet werden: ${e.localizedMessage}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    internal fun attachBubble(accessibility: TextInsertService) {
        try {
            accessibility.showBubble(this)
        } catch (e: WindowManager.BadTokenException) {
            toast("Blase konnte nicht gestartet werden. Bitte die Bedienungshilfe prüfen.")
            stopSelf()
        } catch (e: SecurityException) {
            toast("Blase konnte nicht gestartet werden. Bitte die Bedienungshilfe prüfen.")
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "blitztext")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TapStop")
            .setContentText("Mikrofon-Dienst bereit")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    internal fun onTap() {
        when (phase) {
            Phase.IDLE -> startRecording()
            Phase.RECORDING -> stopRecording()
            Phase.PREPARING, Phase.PROCESSING -> Unit
        }
    }

    private fun startRecording() {
        val provider = settings.provider
        if (provider.isCloud && settings.apiKey(provider).isBlank()) {
            toast("Bitte zuerst den ${provider.label} API-Key speichern."); return
        }
        if (settings.mode.usesRewrite && settings.apiKey(settings.rewriteProvider).isBlank()) {
            toast("Bitte zuerst den ${settings.rewriteProvider.label} API-Key für die Überarbeitung speichern."); return
        }
        if (provider == Provider.QUALCOMM_LOCAL) {
            val runtime = QualcommRuntime.get(this)
            phase = Phase.PREPARING
            render()
            val preparationNotice = if (runtime.status.value.phase != QualcommRuntimePhase.READY)
                Toast.makeText(this, "Lokales Modell wird vorbereitet …", Toast.LENGTH_LONG).also { it.show() }
            else null
            val token = ++processingToken
            worker.execute {
                try {
                    runtime.prepare()
                    main.post {
                        preparationNotice?.cancel()
                        if (token == processingToken && phase == Phase.PREPARING) beginRecording(provider)
                    }
                } catch (e: Exception) {
                    main.post {
                        preparationNotice?.cancel()
                        if (token == processingToken && phase == Phase.PREPARING) {
                            toast("Lokale Spracherkennung nicht bereit: ${e.localizedMessage}")
                            reset()
                        }
                    }
                }
            }
        } else beginRecording(provider)
    }

    private fun beginRecording(provider: Provider) {
        TextInsertService.instance?.rememberFocusedField()
        recordingTarget = TextInsertService.instance?.statisticsTargetPackage()
        try {
            val newRecorder = AudioRecording.create(this, !provider.isCloud) {
                if (phase == Phase.RECORDING) stopRecording()
            }
            recorder = newRecorder
            audioFile = newRecorder.file
            // STT is pinned at capture start: format, limit and audio privacy cannot change mid-dictation.
            recordingProvider = provider
            audioTrace = AudioSttDiagnostics.begin(this)
            newRecorder.start()
            audioTrace?.event("RECORDER_STARTED")
            recordingStarted = SystemClock.elapsedRealtime()
            phase = Phase.RECORDING
            vibrate()
            render()
        } catch (e: Exception) {
            recorder?.release(); recorder = null
            audioFile?.delete(); audioFile = null
            recordingProvider = null
            phase = Phase.IDLE
            render()
            audioTrace?.event("RECORDING_FAILED"); audioTrace = null
            toast("Aufnahme fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    private data class ProcessingConfiguration(
        val provider: Provider, val model: GroqModel, val mode: Mode,
        val sttKey: String, val rewriteProvider: Provider, val rewriteKey: String
    )

    private fun stopRecording() {
        val provider = recordingProvider ?: settings.provider
        val mode = settings.mode
        val rewriteProvider = settings.rewriteProvider
        val configuration = ProcessingConfiguration(provider, settings.groqModel, mode,
            settings.apiKey(provider), rewriteProvider,
            if (mode.usesRewrite) settings.apiKey(rewriteProvider) else "")
        val file = audioFile
        val ended = SystemClock.elapsedRealtime()
        val capture = recorder
        if (recordingProvider == Provider.QUALCOMM_LOCAL && capture != null) {
            phase = Phase.PROCESSING
            render()
            val token = ++processingToken
            worker.execute {
                val result = runCatching { capture.stop(); capture.durationMs }
                capture.release()
                main.post {
                    if (token != processingToken) { file?.delete(); return@post }
                    recorder = null
                    result.onSuccess { finishRecording(file, ended, it, configuration) }.onFailure {
                        audioFile = null; file?.delete(); recordingProvider = null
                        audioTrace?.event("RECORDING_FAILED"); audioTrace = null
                        toast("Aufnahme fehlgeschlagen: ${it.localizedMessage}"); reset()
                    }
                }
            }
        } else finishRecording(file, ended, null, configuration)
    }

    private fun finishRecording(file: File?, recordingEnded: Long, capturedMs: Long?, configuration: ProcessingConfiguration) {
        val audioDiagnostic = audioTrace
        audioTrace = null
        val diagnostic = DictationTrace.create(audioDiagnostic)
        trace = diagnostic
        diagnostic?.event("recording_stop_requested")
        var recordingMs = capturedMs ?: (recordingEnded - recordingStarted).coerceAtLeast(0)
        try {
            recorder?.stop()
            recorder?.let { recordingMs = it.durationMs }
            diagnostic?.event("recording_stopped")
            recorder?.release(); recorder = null
            audioFile = null
            if (file == null || file.length() == 0L) error("Keine Aufnahme vorhanden.")
            runCatching { audioDiagnostic?.finalized(file, recordingMs, configuration.provider, configuration.model) }
            diagnostic?.event("audio_finalized", "audio_ms=$recordingMs audio_bytes=${file.length()}")
            phase = Phase.PROCESSING
            vibrate()
            render()
            val mode = configuration.mode
            val provider = configuration.provider
            recordingProvider = null
            val key = configuration.sttKey
            val groqModel = configuration.model
            val token = ++processingToken
            diagnostic?.event("configuration", "provider=${provider.name} model=${provider.sttModel(groqModel)} mode=${mode.name}")
            val stt = sttProviderFactory(provider, groqModel, key, diagnostic)
            val rewriteProvider = configuration.rewriteProvider
            val rewriteKey = configuration.rewriteKey
            val dictionary = settings.personalDictionary
            val rewriteClient = if (mode.usesRewrite) ApiClient(rewriteProvider, groqModel).also { it.diagnostic = diagnostic } else null
            val processor = DictationProcessor(stt,
                rewrite = { text, selectedMode ->
                    check(rewriteKey.isNotBlank()) { "${rewriteProvider.label} API-Key für die Überarbeitung fehlt." }
                    requireNotNull(rewriteClient).rewrite(text, rewriteKey, selectedMode, dictionary)
                }, cancelRewrite = { rewriteClient?.cancel() })
            activeProcessor = processor
            val rawTranscript = AtomicReference<String?>(null)
            val metrics = AtomicReference(DictationStat(
                timestamp = System.currentTimeMillis(), recordingMs = recordingMs,
                provider = provider.statisticsId,
                model = provider.sttModel(groqModel),
                mode = mode.name, targetPackage = recordingTarget
            ))
            if (diagnostic != null) DictationTrace.watchdogs.incrementAndGet()
            main.postDelayed({
                if (diagnostic != null) DictationTrace.watchdogs.decrementAndGet()
                diagnostic?.event("watchdog_fired", "current=${token == processingToken} phase=$phase")
                if (token == processingToken && phase == Phase.PROCESSING) {
                    diagnostic?.snapshot("watchdog_timeout")
                    processingToken++
                    UsageStats.get(this).record(metrics.get())
                    audioDiagnostic?.event("PROCESSING_TIMEOUT")
                    processor.cancel()
                    worker.shutdownNow()
                    worker = Executors.newSingleThreadExecutor()
                    file.delete()
                    val raw = rawTranscript.get()
                    if (raw != null) {
                        copy(withTrailingSpace(raw))
                        toast("Überarbeitung dauerte zu lange. Rohtranskript wurde kopiert.")
                    } else toast("Transkription dauerte zu lange. Bitte erneut versuchen.")
                    reset()
                }
            }, if (mode.usesRewrite) 140_000 else 90_000)
            if (diagnostic != null) main.postDelayed({
                if (token == processingToken && phase == Phase.PROCESSING) diagnostic.slowSnapshot()
            }, 10_000)
            diagnostic?.snapshot("worker_submitted")
            if (diagnostic != null) DictationTrace.submitted.incrementAndGet()
            worker.execute {
                if (diagnostic != null) { DictationTrace.submitted.decrementAndGet(); DictationTrace.workers.incrementAndGet() }
                diagnostic?.workerStarted()
                diagnostic?.snapshot("worker_started")
                var raw: String? = null
                try {
                    val processed = processor.process(file, mode) { transcript ->
                        raw = transcript.rawTranscript
                        audioDiagnostic?.event("STT_RESULT", org.json.JSONObject().put("raw_transcript", transcript.rawTranscript)
                            .put("stt_provider", transcript.provider.name).put("stt_model", transcript.model).put("stt_ms", transcript.sttMs))
                        metrics.set(metrics.get().copy(rawWords = countWords(transcript.rawTranscript), sttMs = transcript.sttMs))
                        rawTranscript.set(transcript.rawTranscript)
                    }
                    val result = processed.text
                    audioDiagnostic?.event("PROCESSED_TEXT", org.json.JSONObject().put("final_text", result))
                    val finished = SystemClock.elapsedRealtime()
                    val completed = metrics.get().copy(
                        finalWords = countWords(result),
                        rewriteMs = processed.rewriteMs,
                        totalMs = finished - recordingEnded, success = true
                    )
                    diagnostic?.event("result_ready")
                    main.post {
                        diagnostic?.event("result_main_callback", "current=${token == processingToken} phase=$phase")
                        if (token == processingToken && phase == Phase.PROCESSING) {
                            deliver(result, InsertMetadata(recordingMs, completed.finalWords, mode, provider, groqModel, completed.targetPackage), raw, audioDiagnostic?.id)
                            UsageStats.get(this).record(completed)
                        }
                    }
                } catch (e: Exception) {
                    diagnostic?.event("processing_error", "type=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
                    audioDiagnostic?.event("PROCESSING_FAILED", org.json.JSONObject().put("error_type", e.javaClass.simpleName))
                    val transcript = raw
                    main.post {
                        if (token != processingToken || phase != Phase.PROCESSING) return@post
                        UsageStats.get(this).record(metrics.get())
                        if (transcript != null) {
                            copy(withTrailingSpace(transcript))
                            toast("Überarbeitung fehlgeschlagen; Rohtranskript kopiert: ${e.localizedMessage}")
                        } else toast("Transkription fehlgeschlagen: ${e.localizedMessage}")
                        reset()
                    }
                } finally {
                    val deleted = file.delete()
                    if (diagnostic != null) DictationTrace.workers.decrementAndGet()
                    diagnostic?.snapshot("cleanup_complete")
                    diagnostic?.event("audio_cleanup", "deleted=$deleted exists=${file.exists()}")
                }
            }
        } catch (e: Exception) {
            recorder?.release(); recorder = null
            audioFile = null
            file?.delete()
            audioDiagnostic?.event("RECORDING_OR_SETUP_FAILED")
            audioTrace?.event("RECORDING_FAILED"); audioTrace = null
            toast("Aufnahme fehlgeschlagen: ${e.localizedMessage}")
            reset()
        }
    }

    private fun deliver(text: String) = deliver(text, null)

    private fun deliver(text: String, metadata: InsertMetadata?, raw: String? = null, diagnosticId: String? = null) {
        trace?.event("insertion_begin")
        val ready = withTrailingSpace(text)
        val insertion = InsertDiagnostics.begin(ready.length, metadata)
        runCatching { insertion?.delayed = DelayedInsertDiagnostics.begin(this, ready, raw, text, diagnosticId) }
        if (diagnosticId != null) insertion?.audioInsertId = insertion?.delayed?.id ?: java.util.UUID.randomUUID().toString()
        var clipboard = false
        var clipboardReason: String? = null
        try {
            if (!TextInsertService.instance.orFalseInsert(ready, insertion)) {
                clipboardReason = insertion?.clipboardReason()
                copy(ready)
                clipboard = true
                insertion?.step(InsertStep.CLIPBOARD_WRITTEN)
                toast("Text in Zwischenablage kopiert. Bitte einfügen.")
            }
        } catch (_: Exception) {
            insertion?.step(InsertStep.DELIVERY_EXCEPTION)
            clipboardReason = insertion?.clipboardReason()
            copy(ready)
            clipboard = true
            insertion?.step(InsertStep.CLIPBOARD_WRITTEN)
            toast("Text in Zwischenablage kopiert. Bitte einfügen.")
        } finally {
            InsertDiagnostics.finish(this, insertion, clipboard, clipboardReason)
            runCatching {
                val delayed = insertion?.delayed
                AudioSttDiagnostics.link(this, diagnosticId, delayed?.id ?: insertion?.audioInsertId, org.json.JSONObject(insertion?.json(clipboard, clipboardReason) ?: "{}").optString("outcome"))
                if (clipboard) delayed?.path = "CLIPBOARD"
                DelayedInsertDiagnostics.finish(this, delayed, org.json.JSONObject(insertion?.json(clipboard, clipboardReason) ?: "{}").optString("outcome"))
                if (delayed != null && delayed.path != "INPUT_CONNECTION") {
                    for (delay in listOf(250, 1000, 3000)) DelayedInsertDiagnostics.emit(this, delayed, "DELAYED_READBACK",
                        org.json.JSONObject().put("scheduled_ms", delay).put("status", "DELAYED_NOT_VERIFIABLE").put("reason", "NO_CONFIRMED_INPUT_CONNECTION_PATH"))
                }
            }
            reset()
        }
    }

    private fun TextInsertService?.orFalseInsert(text: String, diagnostic: InsertAttempt?): Boolean {
        if (this == null) diagnostic?.step(InsertStep.SERVICE_UNAVAILABLE)
        return this?.insert(text, diagnostic) == true
    }

    private fun withTrailingSpace(text: String): String =
        if (text.isNotEmpty() && !text.last().isWhitespace()) "$text " else text

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TapStop", text))
    }

    private fun reset() { activeProcessor = null; phase = Phase.IDLE; render(); trace?.event("processing_complete", "phase=$phase"); trace?.snapshot("idle") }

    private fun render() { TextInsertService.instance?.renderBubble(this) }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        trace?.snapshot("service_destroyed")
        TextInsertService.instance?.hideBubble(this)
        if (runningService.value === this) runningService.value = null
        processingToken++
        activeProcessor?.cancel(); activeProcessor = null
        audioTrace?.event("RECORDING_ABORTED_SERVICE_DESTROYED"); audioTrace = null
        recorder?.runCatching { stop() }
        recorder?.release(); recorder = null
        audioFile?.delete(); audioFile = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val runningService = MutableStateFlow<OverlayService?>(null)
        internal val active = runningService.asStateFlow()
    }
}
