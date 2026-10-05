package de.kf.blitztext

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The provider fixes the capture format at recording start. Cloud capture remains AAC. */
interface AudioRecording {
    val file: File
    val durationMs: Long
    fun start()
    fun stop()
    fun release()

    companion object {
        fun create(context: Context, local: Boolean, onLimitReached: () -> Unit): AudioRecording =
            if (local) PcmAudioRecording(context, onLimitReached) else AacAudioRecording(context)
    }
}

private class AacAudioRecording(context: Context) : AudioRecording {
    override val file = File.createTempFile("recording-", ".m4a", context.cacheDir)
    private val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
    private var startedAt = 0L
    private var stoppedAt = 0L
    override val durationMs: Long get() =
        if (startedAt == 0L) 0L else ((stoppedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()) - startedAt).coerceAtLeast(0)

    override fun start() {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setAudioSamplingRate(44_100)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun stop() { recorder.stop(); stoppedAt = SystemClock.elapsedRealtime() }
    override fun release() { recorder.release() }
}

/** Exactly one PCM stream, capped by sample count as well as the UI timer. No chunking. */
private class PcmAudioRecording(context: Context, private val onLimitReached: () -> Unit) : AudioRecording {
    override val file = File.createTempFile("recording-", ".wav", context.cacheDir)
    private val main = Handler(Looper.getMainLooper())
    private val finished = CountDownLatch(1)
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var stopping = false
    @Volatile private var released = false
    @Volatile private var pcmBytes = 0
    @Volatile private var failure: Throwable? = null
    private var started = false
    private val limitCallback = Runnable { if (!released && !stopping) onLimitReached() }
    override val durationMs: Long get() = pcmBytes * 1000L / PcmWav.BYTES_PER_SECOND

    @SuppressLint("MissingPermission") // The foreground recording service checks RECORD_AUDIO first.
    override fun start() {
        check(!started) { "Aufnahme wurde bereits gestartet." }
        val min = AudioRecord.getMinBufferSize(PcmWav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "PCM-Mikrofonaufnahme nicht verfügbar ($min)." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, PcmWav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
        audio = recorder
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "PCM-Mikrofon konnte nicht initialisiert werden." }
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofonaufnahme konnte nicht starten." }
        started = true
        Thread({
            try {
                RandomAccessFile(file, "rw").use { output ->
                    output.setLength(0)
                    output.write(PcmWav.header(0))
                    val buffer = ByteArray(3200)
                    while (!stopping && pcmBytes < PcmWav.MAX_PCM_BYTES) {
                        val count = recorder.read(buffer, 0, minOf(buffer.size, PcmWav.MAX_PCM_BYTES - pcmBytes))
                        if (count <= 0) {
                            if (stopping) break
                            throw IOException("PCM-Mikrofonfehler ($count).")
                        }
                        if (count % 2 != 0) throw IOException("Unvollständiges PCM-Sample.")
                        output.write(buffer, 0, count)
                        pcmBytes += count
                    }
                    output.seek(0)
                    output.write(PcmWav.header(pcmBytes))
                }
            } catch (e: Throwable) { failure = e }
            finally {
                runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                recorder.release()
                audio = null
                finished.countDown()
                if (!stopping && !released) main.post(limitCallback)
            }
        }, "TapStopPcmCapture").start()
        main.postDelayed(limitCallback, PcmWav.MAX_DURATION_MS)
    }

    override fun stop() {
        main.removeCallbacks(limitCallback)
        stopping = true
        audio?.let { runCatching { it.stop() } }
        if (started && !finished.await(2, TimeUnit.SECONDS)) throw IOException("PCM-Aufnahme konnte nicht beendet werden.")
        failure?.let { throw IOException("PCM-Aufnahme fehlgeschlagen.", it) }
        if (pcmBytes == 0) throw IOException("Keine Audiodaten aufgenommen.")
    }

    override fun release() {
        released = true
        main.removeCallbacks(limitCallback)
        stopping = true
        audio?.let { recorder ->
            runCatching { recorder.stop() }
            // After a successful start the capture thread exclusively owns release().
            if (!started) { recorder.release(); audio = null }
        }
    }
}

/** VoiceAI's FileInputStream overload skips exactly 44 bytes: reject any other layout. */
internal object PcmWav {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val MAX_DURATION_MS = 30_000L
    const val MAX_PCM_BYTES = 30 * BYTES_PER_SECOND

    fun header(pcmBytes: Int): ByteArray {
        require(pcmBytes in 0..MAX_PCM_BYTES && pcmBytes % 2 == 0)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(36 + pcmBytes); putInt(0x45564157)
            putInt(0x20746d66); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND); putShort(2); putShort(16)
            putInt(0x61746164); putInt(pcmBytes)
        }.array()
    }

    fun validate(file: File): Long {
        if (file.length() !in 46L..(44L + MAX_PCM_BYTES)) throw IOException("Lokale Aufnahme muss zwischen 0 und 30 Sekunden lang sein.")
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val pcmBytes = (file.length() - 44L).toInt()
        if (!header.contentEquals(this.header(pcmBytes))) throw IOException("Lokale Erkennung benötigt PCM16, Mono, 16 kHz mit gültigem WAV-Header.")
        return pcmBytes * 1000L / BYTES_PER_SECOND
    }
}
