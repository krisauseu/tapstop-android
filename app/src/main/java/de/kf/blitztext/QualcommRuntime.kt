package de.kf.blitztext

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest

enum class QualcommRuntimePhase { NOT_INITIALIZED, PREPARING, READY, TRANSCRIBING, FAILED }
data class QualcommRuntimeStatus(val phase: QualcommRuntimePhase, val message: String)

/** Application-scoped model ownership. Activities, overlays and provider changes never unload it. */
class QualcommRuntime private constructor(private val context: Context) : SpeechToTextProvider {
    private val session = QualcommSession()
    private val changes = MutableStateFlow(QualcommRuntimeStatus(QualcommRuntimePhase.NOT_INITIALIZED, "Lokale Runtime noch nicht geladen."))
    val status = changes.asStateFlow()
    private val modelChanges = MutableStateFlow<QualcommModelStatus?>(null)
    val modelStatus = modelChanges.asStateFlow()
    private var target: QualcommTarget? = null
    private var modelDirectory: File? = null
    private var modelFingerprint: List<Pair<Long, Long>>? = null

    @Synchronized override fun prepare() {
        if (session.ready) {
            verifyResidentModel()
            changes.value = QualcommRuntimeStatus(QualcommRuntimePhase.READY, "Lokales Modell bereit.")
            return
        }
        session.terminalFailure?.let { throw IOException(QualcommSession.RESTART_MESSAGE, it) }
        if (session.initializationAttempted) {
            // A previous UI owner was cancelled while native init was still running.
            // Queue behind it; never replace its model identity or launch a second init.
            session.prepare { throw IOException(QualcommSession.RESTART_MESSAGE) }
            verifyResidentModel()
            changes.value = QualcommRuntimeStatus(QualcommRuntimePhase.READY, "Lokales Modell bereit.")
            return
        }
        changes.value = QualcommRuntimeStatus(QualcommRuntimePhase.PREPARING, "Lokales Modell wird geprüft und geladen …")
        try {
            val compatibility = QualcommTargets.detect(AndroidQualcommHardware.read())
            check(compatibility.selectable) { compatibility.message }
            val selected = requireNotNull(compatibility.target)
            availability(context, selected)?.let { throw IOException(it) }
            // Missing/invalid models are recoverable without spending the one native init attempt.
            val checkedModel = QualcommModelStore(context).inspect(selected, verifyHashes = true)
            modelChanges.value = checkedModel
            check(checkedModel.ready && checkedModel.hashesVerified) { checkedModel.message }
            val models = checkedModel.directory
            verifyLibraries(context, selected)
            val skeletonDirectory = prepareSkeleton(context, selected)
            val fingerprint = fingerprint(models, selected)
            // Native init can outlive an interrupted overlay worker. Retain its exact model
            // identity before dispatch so a later Activity/service can reuse that same engine.
            target = selected
            modelDirectory = models
            modelFingerprint = fingerprint
            session.prepare {
                val dspPath = "$skeletonDirectory;${context.applicationInfo.nativeLibraryDir};/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp"
                Os.setenv("ADSP_LIBRARY_PATH", dspPath, true)
                Os.setenv("DSP_LIBRARY_PATH", dspPath, true)
                // The system library uses the normal FastRPC/HAL path. No copied vendor library.
                System.loadLibrary("cdsprpc")
                listOf("QnnSystem", "QnnHtpV${selected.htpVersion}Stub", "QnnHtp", "QnnCpu").forEach(System::loadLibrary)
                ReflectiveVoiceAi(models, skeletonDirectory).also {
                    if (BuildConfig.DEBUG) Log.i(TAG, "runtime_ready target=${selected.id} htp=${selected.htpVersion} soc_model=${selected.socModel}")
                }
            }
            changes.value = QualcommRuntimeStatus(QualcommRuntimePhase.READY, "Whisper Large V3 Turbo bereit.")
        } catch (e: Throwable) {
            changes.value = QualcommRuntimeStatus(
                if (session.initializationAttempted) QualcommRuntimePhase.FAILED else QualcommRuntimePhase.NOT_INITIALIZED,
                e.message ?: "Lokale Spracherkennung nicht verfügbar.")
            if (e is Exception) throw e
            throw IOException("Qualcomm-Runtime konnte nicht geladen werden.", e)
        }
    }

    override fun transcribe(audio: File): SttResult {
        prepare()
        PcmWav.validate(audio)
        verifyResidentModel()
        changes.value = QualcommRuntimeStatus(QualcommRuntimePhase.TRANSCRIBING, "Spracherkennung lokal auf dem Gerät …")
        try {
            val selected = requireNotNull(target)
            if (BuildConfig.DEBUG) Log.i(TAG, "session_start target=${selected.id} audio_ms=${PcmWav.validate(audio)}")
            val result = session.transcribe(audio)
            if (BuildConfig.DEBUG) Log.i(TAG, "session_success target=${selected.id} stt_ms=${result.sttMs}")
            return SttResult(result.text, result.language, result.sttMs, Provider.QUALCOMM_LOCAL, selected.modelId)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "session_failed type=${e.javaClass.simpleName}")
            throw e
        } finally {
            changes.value = if (session.terminalFailure != null)
                QualcommRuntimeStatus(QualcommRuntimePhase.FAILED, QualcommSession.RESTART_MESSAGE)
            else QualcommRuntimeStatus(QualcommRuntimePhase.READY, "Lokales Modell bereit.")
        }
    }

    override fun cancel() { session.cancel() }

    private fun verifyResidentModel() {
        val selected = target ?: return
        val directory = requireNotNull(modelDirectory)
        try {
            if (fingerprint(directory, selected) != modelFingerprint)
                throw IOException("Geladene Modelldateien wurden verändert. Modell neu prüfen und TapStop vollständig neu starten.")
        } catch (e: Exception) {
            modelChanges.value = QualcommModelStatus(QualcommModelState.INVALID,
                e.message ?: "Geladenes Modell ist ungültig.", directory)
            throw e
        }
    }

    companion object {
        const val TAG = "TapStopQualcomm"
        @Volatile private var instance: QualcommRuntime? = null
        fun get(context: Context): QualcommRuntime = instance ?: synchronized(this) {
            instance ?: QualcommRuntime(context.applicationContext).also { instance = it }
        }

        /** A read-only packaging check, not an NPU execution claim. Does not initialize native code. */
        fun availability(context: Context, target: QualcommTarget): String? {
            if (runCatching { Class.forName(VOICE_AI_CLASS, false, context.classLoader) }.isFailure)
                return "Diese APK enthält keine Qualcomm-Runtime. Ein Build mit lokal bereitgestelltem SDK ist erforderlich."
            val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
            val missing = requiredLibraries(target).keys.firstOrNull { !File(nativeDirectory, it).isFile }
            if (missing != null) return "Qualcomm-Runtime unvollständig: $missing fehlt."
            if (runCatching { context.assets.open("qualcomm/${target.skeletonName}").use { } }.isFailure)
                return "Qualcomm-Runtime für dieses Target fehlt (${target.skeletonName})."
            return null
        }

        private fun fingerprint(directory: File, target: QualcommTarget) = target.modelFiles.map {
            val file = File(directory, it.name)
            if (!file.isFile || file.length() != it.sizeBytes) throw IOException("Modelldatei fehlt oder hat eine falsche Größe: ${it.name}")
            file.length() to file.lastModified()
        }

        private fun verifyLibraries(context: Context, target: QualcommTarget) {
            for ((name, hash) in requiredLibraries(target)) {
                val file = File(context.applicationInfo.nativeLibraryDir, name)
                if (sha256(file) != hash) throw IOException("Inkompatible Qualcomm-Runtime: $name (SHA-256).")
            }
        }

        private fun prepareSkeleton(context: Context, target: QualcommTarget): File {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir,
                "qualcomm-runtime/${target.qairtVersion}/v${target.htpVersion}")
            check(directory.isDirectory || directory.mkdirs()) { "Lokaler Runtime-Ordner nicht verfügbar." }
            val output = File(directory, target.skeletonName)
            val expected = skeletonHashes[target.htpVersion] ?: throw IOException("Kein validierter Skeleton für dieses Target.")
            if (!output.isFile || sha256(output) != expected) {
                val pending = File(directory, "${target.skeletonName}.pending")
                try {
                    context.assets.open("qualcomm/${target.skeletonName}").use { input -> pending.outputStream().use { input.copyTo(it) } }
                    check(sha256(pending) == expected) { "Qualcomm-Skeleton hat einen falschen SHA-256." }
                    check(pending.renameTo(output)) { "Qualcomm-Skeleton konnte nicht installiert werden." }
                } finally { pending.delete() }
            }
            return directory
        }

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        // These are hashes of the unmodified, validated VoiceAI 2.7.1.0 / QAIRT 2.50 runtime.
        private val commonLibraries = mapOf(
            "libwhisper_lib.so" to "cec6397ee7f4abed8cb8532409252c75a6e0753da07fb85fc58781bdc01720b7",
            "libopencc.so" to "b93f50ef18003729ce298b11c6bf4d3d564f9187b4696a389ca3c774815f182a",
            "libfft.so" to "2fb68bdf0c627032e6bb19e997233f89800f55b135d069a0a5a84b2454f37916",
            "libQnnSystem.so" to "b30bd9880dc48f6c5349185a7d45b326f8a064b10072e60094354fa23d9a2027",
            "libopencc_jni.so" to "0bd932c8f5a393c88cca268a2e5649156f87074e2c5edc090c42fe10ca6b457c",
            "libdnnvad.so" to "46b1814e3a7b5be7cba21ec1ec493c30185d585272d26942b8697fed2c2330a7",
            "libQnnHtp.so" to "09e930b6af975bae23bcf6965ac2ce60f6b999aff37cb7eabe9b4d7c1bc3f264",
            "libnnvad_model.so" to "7c1690c6e9444a09d5a4baadd42afc01d703f16348716d8e20ae144f7659daaa",
            "libwhisperfunction_jni.so" to "a7de2eee58db537e83a4b31336fe0e5be0bbd9739ab741b0ea8fa5316d86e40e",
            "libwhisperfunction.so" to "342b86c4a34135d020bba2a318774d873aaaa1f2ecdc920b3612a4795be9286b",
            // VoiceAI's VAD uses QnnCpu; Whisper encoder/decoder remain pinned HTP contexts.
            "libQnnCpu.so" to "b7af9f083f8ccafdef889021d5b3c78546ad9d4f689e7027ab66571d2142904f"
        )
        private val stubHashes = mapOf(75 to "8be2bf0b0c6839d3e3bf9d8435e9b92d3fde0ccdea343d89960d75285ad28dab",
            81 to "5b0ea1ef9929bdfcdda0d8dfc6ddffdc913fa2be02cf01a1574d644b2e0e0d38")
        private val skeletonHashes = mapOf(75 to "2f6cfe5aae553d8ec88b6741bf8598a557375eacfc58056c2667fc43fda64f6a",
            81 to "9b7266e38aea818a1caeba5cbfebd9a8cbdba8f25dd82126d9b44ebf4fec4fa0")
        private fun requiredLibraries(target: QualcommTarget): Map<String, String> =
            commonLibraries + (target.stubName to (stubHashes[target.htpVersion] ?: error("Unbekannte HTP-Runtime")))
    }
}

private const val VOICE_AI_CLASS = "com.qualcomm.qti.voice.assist.whisper.sdk.Whisper"

/** Optional SDK bridge: the open-source APK builds and runs without proprietary JAR/SO files. */
private class ReflectiveVoiceAi(models: File, skeletonDirectory: File) : NativeWhisper {
    private val type = Class.forName(VOICE_AI_CLASS)
    private val listenerType = Class.forName("com.qualcomm.qti.voice.assist.whisper.sdk.WhisperResponseListener")
    private val whisper = invoke("newInstance", null, emptyArray(), emptyArray())!!
    // Keep the proxy strongly reachable for the lifetime of each SDK session.
    private var callbackProxy: Any? = null

    init {
        call("setAILogLevel", Int::class.javaPrimitiveType!!, if (BuildConfig.DEBUG) 0 else 3)
        call("enableDebug", Boolean::class.javaPrimitiveType!!, false)
        call("enablePartialTranscriptions", Boolean::class.javaPrimitiveType!!, false)
        call("enableContinuousTranscription", Boolean::class.javaPrimitiveType!!, false)
        call("setTranslationEnabled", Boolean::class.javaPrimitiveType!!, false)
        invoke("setLanguageCodes", whisper, arrayOf(Array<String>::class.java), arrayOf(emptyArray<String>()))
        val code = invoke("init", whisper, arrayOf(String::class.java, String::class.java, Array<String>::class.java),
            arrayOf(skeletonDirectory.absolutePath, "libnnvad_model.so", arrayOf("vocab.bin", "encoder.bin", "decoder.bin").map { File(models, it).absolutePath }.toTypedArray())) as Int
        if (code != 0) throw IOException("VoiceAI/QNN/HTP-Initialisierung fehlgeschlagen (Code $code).")
    }

    override fun listener(listener: NativeWhisperListener) {
        callbackProxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "onTranscription" -> listener.transcription(args[0] as? String ?: "", args[1] as? String, args[2] as Boolean, args[3] as Int)
                "onError" -> listener.error(args[0] as Int)
                "onFinished" -> listener.finished()
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "toString" -> return@newProxyInstance "TapStopVoiceAiListener"
            }
            null
        }
        call("setListener", listenerType, callbackProxy!!)
    }
    override fun start(audio: FileInputStream) { call("start", FileInputStream::class.java, audio) }
    override fun stop() { invoke("stop", whisper, emptyArray(), emptyArray()) }
    private fun call(name: String, parameter: Class<*>, value: Any) = invoke(name, whisper, arrayOf(parameter), arrayOf(value))
    private fun invoke(name: String, receiver: Any?, parameters: Array<Class<*>>, values: Array<Any>): Any? = try {
        type.getMethod(name, *parameters).invoke(receiver, *values)
    } catch (e: InvocationTargetException) { throw IOException("VoiceAI-Aufruf $name fehlgeschlagen.", e.cause ?: e) }
}
