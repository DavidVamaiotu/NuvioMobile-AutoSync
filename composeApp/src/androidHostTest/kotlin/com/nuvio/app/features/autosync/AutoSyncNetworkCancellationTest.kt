package com.nuvio.app.features.autosync

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncNetworkCancellationTest {
    @Test
    fun subtitleRequestCancellationClosesStalledCallPromptly(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )
            val request = async {
                AutoSyncSubtitleHttp.get(
                    url = server.url("/subtitle.srt").toString(),
                    headers = mapOf("Accept" to "*/*"),
                    maxResponseBodyBytes = 4 * 1024 * 1024,
                )
            }

            val received = withContext(Dispatchers.IO) {
                server.takeRequest(5, TimeUnit.SECONDS)
            }
            assertNotNull(received)

            withTimeout(1_000L) {
                request.cancelAndJoin()
            }
            assertTrue(request.isCancelled)
        }
    }

    @Test
    fun embeddedIndexCancellationDoesNotPublishNegativeCache(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )
            val url = server.url("/movie.mkv").toString()
            val first = async {
                EmbeddedSubtitleTimelineLoader.load(
                    sourceUrl = url,
                    sourceHeaders = emptyMap(),
                )
            }

            val firstRequest = withContext(Dispatchers.IO) {
                server.takeRequest(5, TimeUnit.SECONDS)
            }
            assertNotNull(firstRequest)

            withTimeout(1_000L) {
                first.cancelAndJoin()
            }
            assertTrue(first.isCancelled)

            server.enqueue(MockResponse().setResponseCode(404))
            val second = withTimeout(2_000L) {
                EmbeddedSubtitleTimelineLoader.load(
                    sourceUrl = url,
                    sourceHeaders = emptyMap(),
                )
            }
            assertNull(second)

            val secondRequest = withContext(Dispatchers.IO) {
                server.takeRequest(2, TimeUnit.SECONDS)
            }
            assertNotNull(
                secondRequest,
                "A cancelled index attempt must not publish a negative cache entry",
            )
        }
    }

    @Test
    fun concurrentIndexLoadsShareOneDownload(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeadersDelay(300L, TimeUnit.MILLISECONDS),
            )
            val url = server.url("/shared.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }

            withTimeout(3_000L) {
                assertNull(prefetch.await())
                assertNull(autoSync.await())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun joinedIndexLoadRestartsWhenOwningLoadIsCancelled(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val url = server.url("/handoff.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            assertNotNull(
                withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) },
            )
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            yield()

            server.enqueue(MockResponse().setResponseCode(404))
            withTimeout(1_000L) { prefetch.cancelAndJoin() }

            assertNull(withTimeout(3_000L) { autoSync.await() })
            assertEquals(2, server.requestCount)
        }
    }
}
