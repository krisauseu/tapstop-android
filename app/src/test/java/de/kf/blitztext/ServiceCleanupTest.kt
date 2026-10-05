package de.kf.blitztext

import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceCleanupTest {
    private class Connection(url: URL, private val failure: String) : HttpURLConnection(url) {
        var disconnected = false
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; cancelled.countDown() }
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getResponseCode(): Int {
            entered.countDown()
            if (failure == "blocked") { check(cancelled.await(5, TimeUnit.SECONDS)); throw SocketTimeoutException("fixture") }
            if (failure == "timeout") throw SocketTimeoutException("fixture")
            return if (failure == "http" || (failure == "rewrite" && url.path.endsWith("completions"))) 500 else 200
        }
        override fun getInputStream() = if (url.path.endsWith("transcriptions")) """{"text":"fixture"}""".byteInputStream()
            else """{"choices":[{"message":{"content":"fixture"}}]}""".byteInputStream()
        override fun getErrorStream() = """{"error":{"message":"fixture"}}""".byteInputStream()
    }
    private fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun phase(service: OverlayService) = field("phase").get(service)?.toString()
    private fun start(service: OverlayService): File {
        assertEquals("IDLE", phase(service))
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "service-fixture.m4a").apply { writeBytes(ByteArray(128)) }
        field("audioFile").set(service, file)
        field("recordingStarted").setLong(service, SystemClock.elapsedRealtime() - 1000)
        field("phase").set(service, requireNotNull(field("phase").type.enumConstants).first { it.toString() == "RECORDING" })
        OverlayService::class.java.getDeclaredMethod("stopRecording").apply { isAccessible = true }.invoke(service)
        return file
    }
    private fun drain(service: OverlayService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (phase(service) == "IDLE" && DictationTrace.workers.get() == 0 && DictationTrace.submitted.get() == 0) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Processing did not drain")
    }
    private fun withService(test: (OverlayService, MutableList<Connection>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        Settings(context).mode = Mode.PLUS
        val singleton = UsageStats::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val db = UsageStats(context, "service-fixture.db")
        singleton.set(null, db)
        val service = Robolectric.buildService(OverlayService::class.java).create().get()
        val requests = mutableListOf<Connection>()
        try { test(service, requests) } finally {
            DictationTrace.connectionFactory = null
            service.onDestroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(150))
            singleton.set(null, previous)
            val executor = UsageStats::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(db) as ExecutorService
            executor.shutdown(); executor.awaitTermination(3, TimeUnit.SECONDS)
            db.close(); context.deleteDatabase("service-fixture.db")
        }
    }
    @Test fun successfulHttpErrorTimeoutAndRewriteErrorReturnToIdleAndAllowNextDictation() = withService { service, requests ->
        for (failure in listOf("ok", "http", "timeout", "rewrite")) {
            DictationTrace.connectionFactory = { Connection(it, failure).also(requests::add) }
            val audio = start(service)
            drain(service)
            assertFalse(audio.exists())
            assertEquals(0, DictationTrace.connections.get())
            assertTrue(requests.all { it.disconnected })
            DictationTrace.connectionFactory = { Connection(it, "ok").also(requests::add) }
            val next = start(service)
            drain(service)
            assertFalse(next.exists())
            assertEquals("IDLE", phase(service))
        }
    }
    @Test fun watchdogCancelsOldConnectionAndNextWorkerCompletesNormally() = withService { service, requests ->
        Settings(RuntimeEnvironment.getApplication()).mode = Mode.BLITZTEXT
        val ready = CountDownLatch(1)
        var stalled: Connection? = null
        DictationTrace.connectionFactory = { Connection(it, "blocked").also { c -> requests.add(c); stalled = c; ready.countDown() } }
        val audio = start(service)
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        assertTrue(stalled!!.entered.await(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
        drain(service)
        assertTrue(stalled!!.disconnected)
        assertFalse(audio.exists())
        assertEquals(0, DictationTrace.connections.get())
        DictationTrace.connectionFactory = { Connection(it, "ok").also(requests::add) }
        val next = start(service)
        drain(service)
        assertFalse(next.exists())
        assertEquals("IDLE", phase(service))
    }
}
