
package app.tradulive

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests HTTP de HttpFetcher.
 * MockWebServer simule les réponses, coupures et erreurs réseau.
 */
class HttpFetcherTest {

    private val data = ByteArray(3_000_000) {
        (it * 31 + it / 7).toByte()
    }

    private class Fixture(
        val server: MockWebServer,
        val dir: File,
        val rangeHeaders: MutableList<String>,
        val requests: AtomicInteger
    )

    private fun withServer(block: (Fixture) -> Unit) {
        val server = MockWebServer()
        val dir = Files.createTempDirectory("tradulive-test").toFile()
        val ranges = CopyOnWriteArrayList<String>()
        val count = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {

            private fun response(
                code: Int,
                body: ByteArray = data
            ): MockResponse {
                return MockResponse()
                    .setResponseCode(code)
                    .setBody(Buffer().write(body))
            }

            private fun rangeResponse(range: String): MockResponse {
                val from = range
                    .removePrefix("bytes=")
                    .removeSuffix("-")
                    .toInt()

                return response(
                    206,
                    data.copyOfRange(from, data.size)
                )
            }

            override fun dispatch(request: RecordedRequest): MockResponse {
                count.incrementAndGet()

                val path = request.path ?: "/"
                val range = request.getHeader("Range")

                if (range != null) {
                    ranges.add(range)
                }

                return when {

                    path.startsWith("/full") -> {
                        response(200)
                    }

                    path.startsWith("/cut") -> {
                        if (range == null) {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Length", data.size)
                                .setBody(Buffer().write(data))
                                .setSocketPolicy(
                                    SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
                                )
                        } else {
                            rangeResponse(range)
                        }
                    }

                    path.startsWith("/norange") -> {
                        // Le serveur ignore volontairement Range.
                        response(200)
                    }

                    path.startsWith("/missing") -> {
                        MockResponse().setResponseCode(404)
                    }

                    path.startsWith("/r416") -> {
                        if (range != null) {
                            MockResponse().setResponseCode(416)
                        } else {
                            response(200)
                        }
                    }

                    else -> {
                        MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        try {
            server.start()

            block(
                Fixture(
                    server,
                    dir,
                    ranges,
                    count
                )
            )
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    private fun url(fx: Fixture, path: String): URL {
        return fx.server.url(path).toUrl()
    }

    @Test
    fun downloadsAWholeFileAndChecksItsSize() = withServer { fx ->
        val target = File(fx.dir, "a.bin")
        var last = 0L

        runBlocking {
            HttpFetcher.downloadWithRetry(
                url(fx, "/full"),
                target,
                data.size.toLong(),
                backoffMs = 10
            ) {
                last = it
            }
        }

        assertTrue(target.readBytes().contentEquals(data))
        assertEquals(data.size.toLong(), last)
        assertFalse(File(fx.dir, "a.bin.part").exists())
    }

    @Test
    fun resumesAfterAConnectionCut() = withServer { fx ->
        val target = File(fx.dir, "b.bin")

        runBlocking {
            HttpFetcher.downloadWithRetry(
                url(fx, "/cut"),
                target,
                data.size.toLong(),
                backoffMs = 10
            ) { }
        }

        assertTrue(
            "Le contenu doit être identique octet pour octet",
            target.readBytes().contentEquals(data)
        )

        assertEquals(
            "Une seule reprise attendue",
            1,
            fx.rangeHeaders.size
        )

        val resumedFrom = fx.rangeHeaders[0]
            .removePrefix("bytes=")
            .removeSuffix("-")
            .toLong()

        assertTrue(
            "La reprise doit partir de ce qui est déjà reçu : $resumedFrom",
            resumedFrom > 0
        )
    }

    @Test
    fun restartsWhenServerIgnoresRange() = withServer { fx ->
        val target = File(fx.dir, "c.bin")

        File(fx.dir, "c.bin.part")
            .writeBytes(ByteArray(100) { 7 })

        runBlocking {
            HttpFetcher.downloadWithRetry(
                url(fx, "/norange"),
                target,
                data.size.toLong(),
                backoffMs = 10
            ) { }
        }

        assertTrue(target.readBytes().contentEquals(data))
    }

    @Test
    fun recoversFrom416() = withServer { fx ->
        val target = File(fx.dir, "d.bin")

        File(fx.dir, "d.bin.part")
            .writeBytes(ByteArray(50) { 1 })

        runBlocking {
            HttpFetcher.downloadWithRetry(
                url(fx, "/r416"),
                target,
                data.size.toLong(),
                backoffMs = 10
            ) { }
        }

        assertTrue(target.readBytes().contentEquals(data))
    }

    @Test
    fun failsClearlyOn404() = withServer { fx ->
        val target = File(fx.dir, "e.bin")
        var error: IOException? = null

        try {
            runBlocking {
                HttpFetcher.downloadWithRetry(
                    url(fx, "/missing"),
                    target,
                    null,
                    attempts = 2,
                    backoffMs = 10
                ) { }
            }
        } catch (e: IOException) {
            error = e
        }

        assertTrue("IOException attendue", error != null)
        assertTrue(error!!.message!!.contains("404"))
        assertFalse(target.exists())
    }

    @Test
    fun rejectsAFileOfTheWrongSize() = withServer { fx ->
        val target = File(fx.dir, "f.bin")
        var error: IOException? = null

        try {
            runBlocking {
                HttpFetcher.downloadWithRetry(
                    url(fx, "/full"),
                    target,
                    12345L,
                    backoffMs = 10
                ) { }
            }
        } catch (e: IOException) {
            error = e
        }

        assertTrue("IOException attendue", error != null)
        assertTrue(error!!.message!!.contains("corrompu"))
        assertFalse(
            "Le fichier incorrect doit être supprimé",
            target.exists()
        )
    }
}
