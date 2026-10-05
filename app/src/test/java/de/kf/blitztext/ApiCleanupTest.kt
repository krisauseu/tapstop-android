package de.kf.blitztext

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiCleanupTest {
    private class Connection(url: URL, val outcome: String) : HttpURLConnection(url) {
        var disconnected = false
        var inputClosed = false
        var outputClosed = false
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun getOutputStream() = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (outcome == "upload") throw IOException("fixture")
                super.write(b, off, len)
            }
            override fun close() { outputClosed = true; super.close() }
        }
        override fun getResponseCode(): Int {
            if (outcome == "timeout") throw SocketTimeoutException("fixture")
            if (outcome == "cancel") {
                entered.countDown()
                check(released.await(3, TimeUnit.SECONDS))
                throw IOException("fixture cancelled")
            }
            return if (outcome == "http") 429 else 200
        }
        override fun getInputStream() = stream()
        override fun getErrorStream() = stream()
        private fun stream() = object : ByteArrayInputStream((when {
            outcome == "http" -> """{"error":{"message":"fixture"}}"""
            outcome == "json" -> "invalid-json"
            url.path.endsWith("transcriptions") -> """{"text":"fixture"}"""
            else -> """{"choices":[{"message":{"content":"fixture"}}]}"""
        }).toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
    }
    private fun active(client: ApiClient) = ApiClient::class.java.getDeclaredField("activeConnection")
        .apply { isAccessible = true }.get(client)

    @Test fun repeatedSttAndRewriteCloseStreamsAndConnectionsForBothProviders() {
        val audio = File.createTempFile("api-fixture", ".m4a").apply { writeBytes(ByteArray(128)) }
        try {
            for (provider in Provider.cloudProviders) {
                val requests = mutableListOf<Connection>()
                val client = ApiClient(provider, GroqModel.LARGE_V3_TURBO) { Connection(it, "ok").also(requests::add) }
                repeat(25) {
                    assertEquals("fixture", client.transcribe(audio, "test-only"))
                    assertEquals("fixture", client.rewrite("fixture", "test-only", Mode.PLUS))
                    assertNull(active(client))
                }
                assertEquals(50, requests.size)
                assertTrue(requests.all { it.disconnected && it.inputClosed && it.outputClosed })
            }
        } finally { audio.delete() }
    }

    @Test fun httpTimeoutUploadAndParsingFailuresPermitImmediateNextRequest() {
        val audio = File.createTempFile("api-fixture", ".m4a").apply { writeBytes(ByteArray(128)) }
        try {
            for (provider in Provider.cloudProviders) for (failure in listOf("http", "timeout", "upload", "json")) {
                val requests = mutableListOf<Connection>()
                val client = ApiClient(provider, GroqModel.LARGE_V3_TURBO) {
                    Connection(it, if (requests.isEmpty()) failure else "ok").also(requests::add)
                }
                assertTrue(runCatching { client.transcribe(audio, "test-only") }.isFailure)
                assertNull(active(client))
                assertTrue(requests.first().disconnected)
                assertTrue(requests.first().outputClosed)
                if (failure in listOf("http", "json")) assertTrue(requests.first().inputClosed)
                assertEquals("fixture", client.transcribe(audio, "test-only"))
                assertNull(active(client))
                assertTrue(requests.last().disconnected && requests.last().inputClosed && requests.last().outputClosed)
            }
        } finally { audio.delete() }
    }

    @Test fun cancellationDisconnectsBlockedRequestAndNextRequestWorks() {
        val audio = File.createTempFile("api-fixture", ".m4a").apply { writeBytes(ByteArray(128)) }
        try {
            val requests = mutableListOf<Connection>()
            val client = ApiClient(Provider.GROQ, GroqModel.LARGE_V3_TURBO) {
                Connection(it, if (requests.isEmpty()) "cancel" else "ok").also(requests::add)
            }
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val running = thread { runCatching { client.transcribe(audio, "test-only") }.onFailure(failure::set) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (active(client) == null && System.nanoTime() < deadline) Thread.sleep(5)
            val request = active(client) as Connection
            assertTrue(request.entered.await(3, TimeUnit.SECONDS))
            client.cancel()
            running.join(3000)
            assertFalse(running.isAlive)
            assertNotNull(failure.get())
            assertTrue(request.disconnected && request.outputClosed)
            assertNull(active(client))
            assertEquals("fixture", client.transcribe(audio, "test-only"))
        } finally { audio.delete() }
    }

    @Test fun rewriteErrorAfterSuccessfulSttReleasesConnectionAndNextDictationWorks() {
        val audio = File.createTempFile("api-fixture", ".m4a").apply { writeBytes(ByteArray(128)) }
        try {
            for (provider in Provider.cloudProviders) {
                val requests = mutableListOf<Connection>()
                val client = ApiClient(provider, GroqModel.LARGE_V3_TURBO) {
                    Connection(it, if (requests.size == 1) "http" else "ok").also(requests::add)
                }
                assertEquals("fixture", client.transcribe(audio, "test-only"))
                assertTrue(runCatching { client.rewrite("fixture", "test-only") }.isFailure)
                assertNull(active(client))
                assertEquals("fixture", client.transcribe(audio, "test-only"))
                assertEquals("fixture", client.rewrite("fixture", "test-only"))
                assertTrue(requests.all { it.disconnected && it.inputClosed && it.outputClosed })
            }
        } finally { audio.delete() }
    }
}
