package de.kf.blitztext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiSpeechToTextProviderTest {
    @get:Rule val temp = TemporaryFolder()

    private class Connection(
        url: URL,
        private val body: String = COMPLETED,
        private val code: () -> Int = { 200 },
        private val uploadLocation: String? = null,
        val output: OutputStream = ByteArrayOutputStream()
    ) : HttpURLConnection(url) {
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        override fun getResponseCode() = code()
        override fun getOutputStream() = output
        override fun getInputStream() = body.byteInputStream()
        override fun getHeaderField(name: String) = if (name == "X-Goog-Upload-URL") uploadLocation else null
    }

    @Test fun inlineContractPreservesAudioAndReturnsOnlyCompletedModelText() {
        val bytes = ByteArray(70_001) { it.toByte() }
        val audio = temp.newFile().apply { writeBytes(bytes) }
        lateinit var request: Connection
        val stt = GeminiSpeechToTextProvider("test-key", openConnection = {
            Connection(it).also { connection -> request = connection }
        }, durationMs = { 100 }, clock = listOf(20L, 34L).let { values -> values.iterator()::next })
        val result = stt.transcribe(audio)
        assertEquals(SttResult("Äh, hallo!\nWeiter.", sttMs = 14, provider = Provider.GEMINI, model = "gemini-3.5-transcribe"), result)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", request.url.toString())
        assertEquals("test-key", request.getRequestProperty("x-goog-api-key"))
        assertNull(request.getRequestProperty("Authorization"))
        assertFalse(request.instanceFollowRedirects)
        assertEquals(0L, request.disconnected.count)
        val body = JSONObject(request.output.toString())
        assertEquals(result.model, body.getString("model"))
        assertFalse(body.getBoolean("store"))
        val config = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("verbatim", config.getJSONObject("mode").getString("type"))
        assertEquals("de-DE", config.getJSONArray("language_codes").getString(0))
        assertFalse(config.has("custom_vocabulary"))
        val input = body.getJSONArray("input").getJSONObject(0)
        assertEquals("audio/m4a", input.getString("mime_type"))
        assertArrayEquals(bytes, Base64.getDecoder().decode(input.getString("data")))
    }

    @Test fun rejectsMissingKeyEmptyOversizeAndOverlongRecordingsBeforeOpeningConnection() {
        val audio = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val noNetwork: (URL) -> HttpURLConnection = { fail("Unexpected network request"); error("fixture") }
        fun error(key: String, duration: Long) = assertThrows(IOException::class.java) {
            GeminiSpeechToTextProvider(key, openConnection = noNetwork, durationMs = { duration }).transcribe(audio)
        }.message.orEmpty()
        assertTrue(error("", 1).contains("API-Key fehlt"))
        assertTrue(error("test-key", 3_600_001).contains("Stunde"))
        RandomAccessFile(audio, "rw").use { it.setLength(2_000_000_001L) }
        assertTrue(error("test-key", 1).contains("2 GB"))
        RandomAccessFile(audio, "rw").use { it.setLength(0) }
        assertTrue(error("test-key", 1).contains("leer"))
    }

    @Test fun refusesIncompleteMalformedAndEmptyResponsesAndSanitizesHttpErrors() {
        val audio = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val fixtures = listOf(
            Triple(200, """{"status":"in_progress","steps":[]}""", "nicht abgeschlossen"),
            Triple(200, """{"status":"completed","steps":[]}""", "kein Transkript"),
            Triple(200, "broken", "ungültige Antwort"),
            Triple(429, "test-key private request", "Anfragelimit"),
            Triple(403, "test-key private request", "nicht berechtigt"),
            Triple(413, "test-key private request", "Größenlimit")
        )
        for ((status, payload, expected) in fixtures) {
            lateinit var request: Connection
            val stt = GeminiSpeechToTextProvider("test-key", openConnection = {
                Connection(it, payload, code = { status }).also { connection -> request = connection }
            }, durationMs = { 1 })
            val message = assertThrows(IOException::class.java) { stt.transcribe(audio) }.message.orEmpty()
            assertTrue(message, message.contains(expected))
            assertFalse(message.contains("test-key"))
            assertEquals(0L, request.disconnected.count)
        }
    }

    @Test fun timeoutAndCancellationCloseConnection() {
        val audio = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        lateinit var request: Connection
        val timedOut = GeminiSpeechToTextProvider("test-key", openConnection = {
            Connection(it, code = { throw SocketTimeoutException() }).also { connection -> request = connection }
        }, durationMs = { 1 })
        assertTrue(assertThrows(IOException::class.java) { timedOut.transcribe(audio) }.message.orEmpty().contains("Zeitlimit"))
        assertEquals(0L, request.disconnected.count)

        val waiting = CountDownLatch(1)
        val cancelled = GeminiSpeechToTextProvider("test-key", openConnection = {
            Connection(it, code = {
                waiting.countDown()
                check(request.disconnected.await(3, TimeUnit.SECONDS))
                200
            }).also { connection -> request = connection }
        }, durationMs = { 1 })
        var error: Throwable? = null
        val worker = thread { error = runCatching { cancelled.transcribe(audio) }.exceptionOrNull() }
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
        } finally {
            cancelled.cancel()
            worker.join(3000)
        }
        assertFalse(worker.isAlive)
        assertTrue(error?.message.orEmpty().contains("abgebrochen"))
    }

    @Test fun filesApiUsesOriginalBytesAndCleansUpAfterSuccessFailureAndCancellation() {
        // Base64 alone would be 20 MB. The JSON envelope sends it over the inline limit.
        val audio = temp.newFile()
        RandomAccessFile(audio, "rw").use { it.setLength(15_000_000) }
        for (outcome in listOf("success", "failure", "cancel")) {
            val requests = mutableListOf<Connection>()
            var uploadedBytes = 0L
            lateinit var stt: GeminiSpeechToTextProvider
            stt = GeminiSpeechToTextProvider("test-key", openConnection = { url ->
                when (requests.size) {
                    0 -> Connection(url, uploadLocation = "https://generativelanguage.googleapis.com/upload/session?upload_id=fixture")
                    1 -> Connection(url, """{"file":{"state":"ACTIVE","uri":"https://generativelanguage.googleapis.com/v1beta/files/audio"}}""",
                        output = object : OutputStream() {
                            override fun write(value: Int) { uploadedBytes++ }
                            override fun write(bytes: ByteArray, start: Int, length: Int) { uploadedBytes += length }
                        }, code = {
                            if (outcome == "cancel") stt.cancel()
                            if (outcome == "failure") 429 else 200
                        })
                    else -> Connection(url)
                }.also(requests::add)
            }, durationMs = { 3_600_000 })
            if (outcome == "success") assertTrue(stt.transcribe(audio).success)
            else assertThrows(IOException::class.java) { stt.transcribe(audio) }

            val name = JSONObject(requests.first().output.toString()).getJSONObject("file").getString("name")
            assertEquals("/upload/v1beta/files", requests[0].url.path)
            assertEquals("resumable", requests[0].getRequestProperty("X-Goog-Upload-Protocol"))
            assertEquals(audio.length(), uploadedBytes)
            assertEquals("upload, finalize", requests[1].getRequestProperty("X-Goog-Upload-Command"))
            assertNull(requests[1].getRequestProperty("x-goog-api-key"))
            if (outcome == "success") {
                val input = JSONObject(requests[2].output.toString()).getJSONArray("input").getJSONObject(0)
                assertEquals("https://generativelanguage.googleapis.com/v1beta/files/audio", input.getString("uri"))
                assertFalse(input.has("data"))
            }
            assertEquals(if (outcome == "success") 4 else 3, requests.size)
            assertEquals("DELETE", requests.last().requestMethod)
            assertEquals("/v1beta/$name", requests.last().url.path)
            assertTrue(requests.all { it.disconnected.count == 0L })
        }
    }

    @Test fun geminiIsOptInAndNeverBecomesTheRewriteProvider() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", 0)
        prefs.edit().clear().apply()
        val settings = Settings(context)
        assertEquals(Provider.OPENAI, settings.provider)
        prefs.edit().putString("provider", "GROQ").putString("mode", "CHAT").putString("groq_model", "LARGE_V3").apply()
        settings.provider = Provider.GEMINI
        assertEquals(Provider.GROQ, settings.rewriteProvider)
        settings.provider = Provider.QUALCOMM_LOCAL
        assertEquals(Provider.GROQ, settings.rewriteProvider)
        settings.rewriteProvider = Provider.OPENAI
        settings.provider = Provider.GEMINI
        assertEquals(Provider.OPENAI, settings.rewriteProvider)
        assertThrows(IllegalArgumentException::class.java) { settings.rewriteProvider = Provider.GEMINI }
        settings.followSttForRewrite()
        assertEquals(Provider.GROQ, Settings(context).rewriteProvider)
        assertEquals(Mode.CHAT, settings.mode)
        assertEquals(GroqModel.LARGE_V3, settings.groqModel)
    }

    @Test fun missingRewriteKeyLeavesRawAvailableAndPreventsProcessedOutput() {
        val audio = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val stt = GeminiSpeechToTextProvider("test-key", openConnection = { Connection(it) }, durationMs = { 1 })
        val processor = DictationProcessor(stt, rewrite = { _, _ -> error("OpenAI API-Key für die Überarbeitung fehlt.") })
        assertNull(processor.process(audio, Mode.BLITZTEXT).rewriteMs)
        for (mode in listOf(Mode.PLUS, Mode.CHAT, Mode.FORMAL)) {
            assertTrue(assertThrows(IllegalStateException::class.java) { processor.process(audio, mode) }
                .message.orEmpty().contains("API-Key"))
        }
    }

    companion object {
        private const val COMPLETED = """{"status":"completed","steps":[
            {"type":"thought","content":[{"type":"text","text":"ignored"}]},
            {"type":"model_output","content":[{"type":"text","text":"Äh, hallo!"},{"type":"audio","text":"ignored"}]},
            {"type":"model_output","content":[{"type":"text","text":"\nWeiter."}]}]}"""
    }
}
