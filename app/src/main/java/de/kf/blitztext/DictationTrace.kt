package de.kf.blitztext

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** DEBUG-only metadata. Never pass text, keys, URLs, audio or exception messages here. */
internal class DictationTrace private constructor(val id: String) {
    private val started = SystemClock.elapsedRealtime()
    @Volatile private var workerThread: Thread? = null
    fun workerStarted() { workerThread = Thread.currentThread() }
    fun slowSnapshot() {
        snapshot("slow_processing")
        workerThread?.stackTrace?.forEach { event("slow_worker_frame", "frame=$it") }
    }
    fun event(name: String, metadata: String = "") {
        if (BuildConfig.DEBUG) Log.i(TAG, "$id t=${SystemClock.elapsedRealtime()} dt=${SystemClock.elapsedRealtime() - started} event=$name thread=${Thread.currentThread().name} $metadata")
    }
    fun snapshot(name: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val runtime = Runtime.getRuntime()
            val status = File("/proc/self/status").readLines()
            val threads = status.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()
            val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.replace(' ', '_')
            event(name, "workers=${workers.get()} submitted=${submitted.get()} watchdogs=${watchdogs.get()} connections_owned=${connections.get()} threads=$threads fds=${File("/proc/self/fd").list()?.size} heap_bytes=${runtime.totalMemory() - runtime.freeMemory()} native_bytes=${Debug.getNativeHeapAllocatedSize()} rss=$rss dispatcher_running=NA dispatcher_queued=NA pool=NA jobs=NA")
        }.onFailure { event("snapshot_unavailable", "type=${it.javaClass.simpleName}") }
    }
    fun connectionOpened(implementation: String) { connections.incrementAndGet(); event("connection_created", "implementation=$implementation connect_timeout_ms=10000 read_timeout_ms=60000") }
    fun connectionClosed() { connections.decrementAndGet(); event("connection_disconnected") }
    companion object {
        const val TAG = "TapStopDiag"
        @Volatile internal var connectionFactory: ((java.net.URL) -> java.net.HttpURLConnection)? = null
        private val sequence = AtomicInteger()
        val workers = AtomicInteger()
        val submitted = AtomicInteger()
        val watchdogs = AtomicInteger()
        val connections = AtomicInteger()
        fun create(): DictationTrace? = if (BuildConfig.DEBUG) DictationTrace("TS-%04d".format(sequence.incrementAndGet())) else null
    }
}
