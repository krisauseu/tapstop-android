package de.kf.blitztext

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioSttDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val root get() = File(context.noBackupFilesDir, AudioSttDiagnostics.DIRECTORY)
    private fun flush() { assertTrue(AudioSttDiagnostics.awaitWriter()) }
    private fun record(id: String) = checkNotNull(AudioDiagnosticStore(root).read(id))
    private fun events(id: String): List<JSONObject> = record(id).getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun fixture() = File.createTempFile("diagnostic-", ".wav", context.cacheDir).apply {
        writeBytes(PcmWav.header(3200) + ByteArray(3200) { (it % 251).toByte() })
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Before fun setup() {
        flush(); root.deleteRecursively()
        context.getSharedPreferences(AudioSttDiagnostics.PREFS, 0).edit().clear().commit()
        AudioSttDiagnostics.setEnabled(context, true)
    }
    @After fun after() { flush(); AudioSttDiagnostics.setEnabled(context, false) }

    @Test fun openDescriptorSurvivesNormalProductiveCleanupAndCopyIsExact() {
        val source = fixture(); val bytes = source.readBytes()
        val trace = AudioSttDiagnostics.begin(context)!!
        trace.finalized(source, 100, Provider.QUALCOMM_LOCAL, GroqModel.LARGE_V3_TURBO)
        assertTrue(source.delete())
        flush()
        val record = record(trace.id)
        assertArrayEquals(bytes, File(root, "${trace.id}/input.wav").readBytes())
        assertEquals(sha(bytes), record.getString("audio_sha256"))
        assertEquals(bytes.size.toLong(), record.getLong("audio_bytes"))
        assertEquals(16000, record.getJSONObject("format").getInt("sample_rate_hz"))
        assertEquals(1, record.getJSONObject("format").getInt("channels"))
        assertEquals(100000L, record.getJSONObject("format").getLong("media_duration_us"))
        assertEquals(100, events(trace.id).first { it.getString("event") == "RECORDING_END" }.getInt("recording_duration_ms"))
        assertFalse(source.exists())
    }
    @Test fun disabledRecordsNothingAndDoesNotChangeProductiveSettings() {
        val prefs = context.getSharedPreferences("settings", 0)
        val before = prefs.all.toMap()
        AudioSttDiagnostics.setEnabled(context, false)
        assertNull(AudioSttDiagnostics.begin(context))
        val source = fixture(); val original = source.readBytes()
        AudioSttDiagnostics.capture(AudioSttTrace(context), source)
        AudioSttTrace(context).event("STT_RESULT", JSONObject().put("raw_transcript", "PRIVATE")); flush()
        assertFalse(root.exists())
        assertArrayEquals(original, source.readBytes())
        assertEquals(before, prefs.all)
        source.delete()
    }
    @Test fun abortedRecordingHasIdButNoPretendedSttAudio() {
        val trace = AudioSttDiagnostics.begin(context)!!
        val service = org.robolectric.Robolectric.buildService(OverlayService::class.java).create().get()
        val file = fixture()
        var stops = 0; var releases = 0
        val recording = object : AudioRecording {
            override val file = file
            override val durationMs = 100L
            override fun start() = Unit
            override fun stop() { stops++ }
            override fun release() { releases++ }
        }
        fun set(name: String, value: Any) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        set("audioTrace", trace); set("audioFile", file); set("recorder", recording)
        service.onDestroy(); flush()
        assertEquals(1, stops); assertEquals(1, releases); assertFalse(file.exists())
        assertFalse(record(trace.id).has("audio_file"))
        assertTrue(events(trace.id).any { it.getString("event") == "RECORDING_ABORTED_SERVICE_DESTROYED" })
    }
    @Test fun missingSourceIsIsolatedAndExplicit() {
        val trace = AudioSttDiagnostics.begin(context)!!
        AudioSttDiagnostics.capture(trace, File(context.cacheDir, "absent.wav")); flush()
        assertTrue(events(trace.id).any { it.getString("event") == "AUDIO_COPY_OPEN_FAILED" })
    }
    @Test fun networkFailureRetainsFinalizedInputWithoutInsert() {
        val source = fixture()
        val trace = AudioSttDiagnostics.begin(context)!!
        trace.finalized(source, 100, Provider.GROQ, GroqModel.LARGE_V3)
        val stt = object : SpeechToTextProvider {
            override fun transcribe(audio: File): SttResult { assertSame(source, audio); throw java.net.SocketTimeoutException() }
        }
        assertTrue(runCatching { DictationProcessor(stt, { _, _ -> error("rewrite") }).process(source, Mode.BLITZTEXT) }.isFailure)
        trace.event("PROCESSING_FAILED"); source.delete(); flush()
        assertTrue(record(trace.id).has("audio_file")); assertFalse(record(trace.id).has("insert_id"))
        assertFalse(events(trace.id).any { it.has("raw_transcript") })
    }
    @Test fun successCloudWireBytesRequestsAndFullTranscriptStayUnchanged() {
        val file = fixture()
        val sent = mutableListOf<ByteArray>()
        var requestCount = 0
        fun client() = ApiClient(Provider.GROQ, GroqModel.LARGE_V3) { url ->
            requestCount++
            object : HttpURLConnection(url) {
                val output = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() { sent.add(output.toByteArray()) }
                override fun usingProxy() = false
                override fun getOutputStream() = output
                override fun getResponseCode() = 200
                override fun getInputStream() = "{\"text\":\"  Äß😀 Rohfragment g er\\n\"}".byteInputStream()
            }
        }
        val expected = client().transcribe(file, "fixture")
        val trace = AudioSttDiagnostics.begin(context)!!
        trace.finalized(file, 100, Provider.GROQ, GroqModel.LARGE_V3)
        val client = client().also { it.diagnostic = DictationTrace.create()!!.also { d -> d.audioDiagnostic = trace } }
        val result = DictationProcessor(CloudSpeechToTextProvider(Provider.GROQ, GroqModel.LARGE_V3, "fixture", client),
            { _, _ -> error("raw must not rewrite") }).process(file, Mode.BLITZTEXT) {
                trace.event("STT_RESULT", JSONObject().put("raw_transcript", it.rawTranscript))
            }
        assertEquals(expected, result.text)
        assertEquals(2, requestCount)
        fun normalized(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).replace(Regex("TapStop-[a-f0-9-]+"), "BOUNDARY")
        assertEquals(normalized(sent[0]), normalized(sent[1]))
        flush()
        assertEquals(expected, events(trace.id).single { it.getString("event") == "STT_RESULT" }.getString("raw_transcript"))
        assertEquals(record(trace.id).getString("audio_sha256"), events(trace.id).single { it.getString("event") == "STT_UPLOAD_FILE" }.getString("sha256"))
        file.delete()
    }
    @Test fun persistentInsertLinkAndMarkerPreferOriginalInsertOverNewerRecording() {
        val first = AudioSttDiagnostics.begin(context)!!
        val file = fixture(); first.finalized(file, 100, Provider.GROQ, GroqModel.LARGE_V3)
        AudioSttDiagnostics.link(context, first.id, "existing-insert-id", "CLIPBOARD")
        val second = AudioSttDiagnostics.begin(context)!!
        second.finalized(file, 100, Provider.GROQ, GroqModel.LARGE_V3)
        flush()
        AudioSttDiagnostics.mark(context, "existing-insert-id", "STALE_OR_CLOCK_CHANGED", System.currentTimeMillis()); flush()
        val reopened = AudioDiagnosticStore(root)
        assertTrue(reopened.read(first.id)!!.getBoolean("marked"))
        assertFalse(reopened.read(second.id)!!.getBoolean("marked"))
        assertEquals("existing-insert-id", reopened.read(first.id)!!.getString("insert_id"))
        AudioSttDiagnostics.mark(context, null, "NO_INSERT", System.currentTimeMillis()); flush()
        assertTrue(reopened.read(second.id)!!.getBoolean("marked")); file.delete()
    }
    @Test fun agePrunesOnlyUnmarkedCasesAndNeverProductiveData() {
        val store = AudioDiagnosticStore(root, maxAgeMs = 100)
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        store.event(a, JSONObject().put("event", "START"), 10)
        store.event(b, JSONObject().put("event", "MANUAL_ERROR_MARKER"), 10)
        val sibling = File(context.noBackupFilesDir, "productive-fixture").apply { writeText("keep") }
        store.recover(111)
        assertNull(store.read(a)); assertTrue(store.read(b)!!.getBoolean("marked")); assertEquals("keep", sibling.readText()); sibling.delete()
    }
    @Test fun capacityEvictsOldUnmarkedBeforeMarkedAndRefusesOversizeInput() {
        val store = AudioDiagnosticStore(root, maxBytes = 100000)
        val marked = UUID.randomUUID().toString(); val old = UUID.randomUUID().toString(); val next = UUID.randomUUID().toString()
        store.event(marked, JSONObject().put("event", "MANUAL_ERROR_MARKER"), 1)
        store.event(old, JSONObject().put("event", "START"), 2)
        File(root, "$old/filler").writeBytes(ByteArray(90000))
        store.event(next, JSONObject().put("event", "START").put("test", "x".repeat(15000)), 3)
        assertNull(store.read(old)); assertNotNull(store.read(marked))
        val file = fixture()
        assertTrue(runCatching { FileInputStream(file).use { store.copy(next, it, 100001, "wav", 3) } }.isFailure)
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 100000); file.delete()
    }
    @Test fun fullMarkedStoreRejectsNewDataAndWriterFailureDoesNotEscape() {
        val store = AudioDiagnosticStore(root, maxBytes = 1000)
        val id = UUID.randomUUID().toString()
        store.event(id, JSONObject().put("event", "MANUAL_ERROR_MARKER"), 1)
        File(root, "$id/filler").writeBytes(ByteArray(700))
        assertTrue(runCatching { store.event(UUID.randomUUID().toString(), JSONObject().put("test", "x".repeat(800)), 2) }.isFailure)
        assertNotNull(store.read(id))
        root.deleteRecursively(); root.writeText("write-blocker")
        val trace = AudioSttDiagnostics.begin(context)!!
        val file = fixture(); trace.finalized(file, 100, Provider.GROQ, GroqModel.LARGE_V3); flush()
        val stt = object : SpeechToTextProvider {
            override fun transcribe(audio: File): SttResult {
                assertSame(file, audio)
                return SttResult("unchanged", sttMs = 1, provider = Provider.QUALCOMM_LOCAL, model = "fixture")
            }
        }
        assertEquals("unchanged", DictationProcessor(stt, { _, _ -> error("unexpected rewrite") }).process(file, Mode.BLITZTEXT).text)
        assertTrue(file.isFile); file.delete(); root.delete()
    }
    @Test fun restartCleansIncompleteCopiesAndRestoresAtomicManifest() {
        val store = AudioDiagnosticStore(root)
        val id = UUID.randomUUID().toString()
        store.event(id, JSONObject().put("event", "AUDIO_COPY_PENDING"), 100)
        File(root, "$id/input.wav.part").writeText("incomplete")
        File(root, "$id/input.m4a").writeText("orphan")
        val base = File(root, "$id/case.json")
        base.copyTo(File(root, "$id/case.json.bak")); base.writeText("broken")
        AudioDiagnosticStore(root).recover(101)
        assertNotNull(store.read(id)); assertFalse(File(root, "$id/input.wav.part").exists())
        assertFalse(File(root, "$id/input.m4a").exists())
    }
}
