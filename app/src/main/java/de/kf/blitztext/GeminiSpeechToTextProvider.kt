package de.kf.blitztext

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One request lifecycle per dictation, including any temporary Files API resource. */
internal class GeminiSpeechToTextProvider(
    private val key: String,
    private val diagnostic: DictationTrace? = null,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        val factory = if (BuildConfig.DEBUG) DictationTrace.connectionFactory else null
        factory?.invoke(url) ?: url.openConnection() as HttpURLConnection
    },
    private val durationMs: (File) -> Long = { audio ->
        MediaMetadataRetriever().use {
            it.setDataSource(audio.absolutePath)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("Aufnahmedauer konnte nicht gelesen werden.")
        }
    },
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : SpeechToTextProvider {
    private val lock = Any()
    private var activeConnection: HttpURLConnection? = null
    @Volatile private var stopped: String? = null
    private val model = Provider.GEMINI.sttModel(GroqModel.LARGE_V3_TURBO)

    override fun cancel() {
        diagnostic?.event("cancel_requested")
        stop("Gemini-Spracherkennung abgebrochen.")
    }

    private fun stop(message: String) {
        val connection = synchronized(lock) {
            if (stopped == null) stopped = message
            activeConnection
        }
        connection?.disconnect()
    }

    private fun checkRunning() {
        stopped?.let { throw IOException(it) }
    }

    override fun transcribe(audio: File): SttResult {
        checkRunning()
        if (key.isBlank()) throw IOException("Google Gemini API-Key fehlt.")
        val size = audio.length()
        if (size == 0L) throw IOException("Die Aufnahme ist leer.")
        if (size > MAX_FILE_BYTES) throw IOException("Gemini akzeptiert Dateien bis 2 GB.")
        val started = clock()
        diagnostic?.event("stt_begin")
        val duration = try {
            durationMs(audio)
        } catch (_: Exception) {
            throw IOException("Gemini: Aufnahmedauer konnte nicht gelesen werden.")
        }
        if (duration !in 1..3_600_000L) throw IOException("Gemini unterstützt Aufnahmen bis zu einer Stunde.")

        val deadline = timeouts.schedule({ stop(TIMEOUT_MESSAGE) }, 180, TimeUnit.SECONDS)
        var fileName: String? = null
        var outcome = "failed"
        try {
            val inlineSize = inlinePrefix.size + 4 * ((size + 2) / 3) + INLINE_SUFFIX.size
            val result = if (inlineSize <= MAX_INLINE_BYTES) {
                request("/v1beta/interactions", "stt", length = inlineSize, contentType = JSON) { output ->
                    output.write(inlinePrefix)
                    Base64OutputStream(output, Base64.NO_WRAP or Base64.NO_CLOSE).use { copyAudio(audio, it) }
                    output.write(INLINE_SUFFIX)
                }
            } else {
                // Assign the name before upload so cleanup can also cover a lost finalize response.
                val name = "files/tapstop-${UUID.randomUUID().toString().replace("-", "")}"
                fileName = name
                val uri = upload(audio, size, name)
                val body = interaction().put("input", JSONArray().put(
                    JSONObject().put("type", "audio").put("mime_type", MIME).put("uri", uri)))
                    .toString().toByteArray()
                request("/v1beta/interactions", "stt", length = body.size.toLong(), contentType = JSON) { it.write(body) }
            }
            val transcript = parseTranscript(JSONObject(result))
            checkRunning()
            diagnostic?.event("stt_parsing_complete")
            outcome = "completed"
            return SttResult(transcript, sttMs = clock() - started, provider = Provider.GEMINI, model = model)
        } catch (e: Exception) {
            checkRunning()
            throw when (e) {
                is SocketTimeoutException -> IOException(TIMEOUT_MESSAGE)
                is JSONException -> IOException("Gemini hat eine ungültige Antwort geliefert.")
                is GeminiResponseException -> e
                else -> IOException("Netzwerkfehler bei der Gemini-Spracherkennung.")
            }
        } finally {
            deadline.cancel(false)
            fileName?.let { name ->
                // Cleanup uses a separate, short-lived connection even after cancellation.
                val deleted = runCatching { request("/v1beta/$name", "stt_file_delete", method = "DELETE") }.isSuccess
                diagnostic?.event("stt_file_cleanup", if (deleted) "done" else "failed")
            }
            diagnostic?.event("stt_finished", "status=$outcome elapsed_ms=${clock() - started}")
        }
    }

    private val inlinePrefix = interaction().toString().dropLast(1)
        .plus(""","input":[{"type":"audio","mime_type":"$MIME","data":""" + '"').toByteArray()

    private fun interaction(): JSONObject = JSONObject()
        .put("model", model)
        .put("store", false)
        .put("generation_config", JSONObject().put("transcription_config", JSONObject()
            .put("mode", JSONObject().put("type", "verbatim"))
            .put("language_codes", JSONArray().put("de-DE"))))

    private fun upload(audio: File, size: Long, name: String): String {
        val metadata = JSONObject().put("file", JSONObject().put("name", name)).toString().toByteArray()
        val location = request("/upload/v1beta/files", "stt_file_start", length = metadata.size.toLong(),
            contentType = JSON, headers = mapOf(
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to size.toString(),
                "X-Goog-Upload-Header-Content-Type" to MIME
            ), responseHeader = "X-Goog-Upload-URL") { it.write(metadata) }
        if (location.isBlank()) throw GeminiResponseException("Gemini hat keine Upload-Adresse geliefert.")
        val uploadUrl = URL(location)
        if (uploadUrl.protocol != "https" || uploadUrl.host != URL(BASE_URL).host ||
            uploadUrl.userInfo != null || uploadUrl.port !in listOf(-1, 443)) {
            throw GeminiResponseException("Gemini hat eine ungültige Upload-Adresse geliefert.")
        }

        val uploaded = request(uploadUrl.file, "stt_file_upload", length = size, contentType = MIME,
            headers = mapOf("X-Goog-Upload-Offset" to "0", "X-Goog-Upload-Command" to "upload, finalize"),
            authenticate = false) { copyAudio(audio, it) }
        var file = JSONObject(uploaded).getJSONObject("file")
        while (file.optString("state") == "PROCESSING") {
            checkRunning()
            Thread.sleep(500)
            file = JSONObject(request("/v1beta/$name", "stt_file_status", method = "GET"))
        }
        if (file.optString("state") != "ACTIVE") throw GeminiResponseException("Gemini konnte die Audiodatei nicht vorbereiten.")
        return (file.opt("uri") as? String)?.takeIf { it.isNotBlank() }
            ?: throw GeminiResponseException("Gemini hat keine Datei-URI geliefert.")
    }

    private fun copyAudio(audio: File, output: OutputStream) {
        val audioTrace = diagnostic?.audioDiagnostic?.takeIf { AudioSttDiagnostics.enabled(it.context) }
        val hash = audioTrace?.let { MessageDigest.getInstance("SHA-256") }
        audio.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkRunning()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                hash?.update(buffer, 0, read)
            }
        }
        if (audioTrace != null && hash != null) {
            audioTrace.event("STT_UPLOAD_FILE", JSONObject().put("bytes", audio.length())
                .put("sha256", hash.digest().joinToString("") { "%02x".format(it) }))
        }
    }

    private fun request(
        path: String,
        stage: String,
        method: String = "POST",
        length: Long = 0,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        responseHeader: String? = null,
        authenticate: Boolean = true,
        write: ((OutputStream) -> Unit)? = null
    ): String {
        val cleanup = method == "DELETE"
        if (!cleanup) checkRunning()
        val connection = openConnection(URL(BASE_URL + path))
        val connectMs = if (cleanup) 3_000 else 10_000
        val readMs = if (cleanup) 3_000 else 120_000
        diagnostic?.connectionOpened(connection.javaClass.name, connectMs, readMs)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs
            if (authenticate) connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", JSON)
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (!cleanup) synchronized(lock) {
                checkRunning()
                activeConnection = connection
            }
            diagnostic?.event("${stage}_http_execute")
            if (write != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(length)
                connection.outputStream.use(write)
                diagnostic?.event("${stage}_upload_complete")
            }
            val status = connection.responseCode
            diagnostic?.event("${stage}_headers_received", "http=$status")
            if (cleanup && status == 404) return ""
            if (status !in 200..299) throw GeminiResponseException(when (status) {
                400 -> "Gemini hat die Anfrage abgelehnt (HTTP 400). Bitte API-Key und Aufnahme prüfen."
                401, 403 -> "Google Gemini API-Key ungültig oder für dieses Modell nicht berechtigt."
                408, 504 -> TIMEOUT_MESSAGE
                413 -> "Die Audiodatei überschreitet das Gemini-Größenlimit."
                429 -> "Gemini-Anfragelimit erreicht. Bitte später erneut versuchen."
                else -> "Gemini-Anfrage fehlgeschlagen (HTTP $status)."
            })
            if (cleanup) return ""
            val response = if (responseHeader == null) connection.inputStream.bufferedReader().use { it.readText() }
                else connection.getHeaderField(responseHeader).orEmpty()
            diagnostic?.event("${stage}_body_complete")
            return response
        } finally {
            synchronized(lock) { if (activeConnection === connection) activeConnection = null }
            connection.disconnect()
            diagnostic?.connectionClosed()
        }
    }

    private class GeminiResponseException(message: String) : IOException(message)

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private const val MIME = "audio/m4a"
        private const val JSON = "application/json"
        private const val MAX_FILE_BYTES = 2_000_000_000L
        private const val MAX_INLINE_BYTES = 20_000_000L
        private const val TIMEOUT_MESSAGE = "Zeitlimit für Gemini-Spracherkennung überschritten."
        private val INLINE_SUFFIX = "\"}]}".toByteArray()
        private val timeouts = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "TapStopGeminiTimeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        private fun parseTranscript(result: JSONObject): String {
            if (result.optString("status") != "completed") {
                throw GeminiResponseException("Gemini hat die Transkription nicht abgeschlossen.")
            }
            val text = buildString {
                val steps = result.optJSONArray("steps") ?: return@buildString
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i) ?: continue
                    if (step.optString("type") != "model_output") continue
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") (part.opt("text") as? String)?.let { append(it) }
                    }
                }
            }
            return text.takeIf { it.isNotBlank() }
                ?: throw GeminiResponseException("Gemini hat kein Transkript geliefert.")
        }
    }
}
