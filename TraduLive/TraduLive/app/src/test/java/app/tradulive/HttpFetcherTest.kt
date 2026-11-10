package app.tradulive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files

/** Vrai serveur HTTP local : on y simule coupures, absence de « Range », erreurs 404 et 416. */
class HttpFetcherTest {

    private val data = ByteArray(3_000_000) { (it * 31 + it / 7).toByte() }

    private class Fixture(val server: HttpServer, val dir: File, val rangeHeaders: MutableList<String>, val requests: IntArray)

    private fun withServer(block: (Fixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dir = Files.createTempDirectory("tradulive-test").toFile()
        val ranges = ArrayList<String>()
        val count = intArrayOf(0)
        val fx = Fixture(server, dir, ranges, count)

        fun serve(ex: HttpExchange, honorRange: Boolean, cutAfter: Int?) {
            count[0]++
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) ranges.add(range)
            var from = 0
            var code = 200
            if (range != null && honorRange) {
                from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                code = 206
            }
            val body = data.copyOfRange(from, data.size)
            ex.sendResponseHeaders(code, body.size.toLong())
            val out = ex.responseBody
            try {
                if (cutAfter != null && range == null) {
                    out.write(body, 0, cutAfter) // on coupe en plein milieu, sans finir
                    out.flush()
                } else {
                    out.write(body)
                }
            } catch (_: IOException) {
            } finally {
                try {
                    ex.close()
                } catch (_: Exception) {
                }
            }
        }

        server.createContext("/full") { serve(it, true, null) }
        server.createContext("/cut") { serve(it, true, 1_200_000) }
        server.createContext("/norange") { serve(it, false, null) }
        server.createContext("/missing") { it.sendResponseHeaders(404, -1); it.close() }
        server.createContext("/r416") {
            count[0]++
            if (it.requestHeaders.getFirst("Range") != null) {
                it.sendResponseHeaders(416, -1); it.close()
            } else {
                serve(it, true, null)
                count[0]--
            }
        }
        server.start()
        try {
            block(fx)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    private fun url(fx: Fixture, path: String) = URL("http://127.0.0.1:${fx.server.address.port}$path")

    @Test
    fun downloadsAWholeFileAndChecksItsSize() = withServer { fx ->
        val target = File(fx.dir, "a.bin")
        var last = 0L
        runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/full"), target, data.size.toLong(), backoffMs = 10) { last = it } }
        assertTrue(target.readBytes().contentEquals(data))
        assertEquals(data.size.toLong(), last)
        assertFalse(File(fx.dir, "a.bin.part").exists())
    }

    @Test
    fun resumesAfterAConnectionCut() = withServer { fx ->
        val target = File(fx.dir, "b.bin")
        runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/cut"), target, data.size.toLong(), backoffMs = 10) { } }
        assertTrue("le contenu doit être identique octet pour octet", target.readBytes().contentEquals(data))
        assertEquals("une seule reprise attendue", 1, fx.rangeHeaders.size)
        val resumedFrom = fx.rangeHeaders[0].removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue("la reprise doit partir de ce qui est déjà reçu (>0), pas de zéro : $resumedFrom", resumedFrom > 0)
    }

    @Test
    fun restartsWhenServerIgnoresRange() = withServer { fx ->
        val target = File(fx.dir, "c.bin")
        File(fx.dir, "c.bin.part").writeBytes(ByteArray(100) { 7 }) // reste d'un téléchargement abandonné
        runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/norange"), target, data.size.toLong(), backoffMs = 10) { } }
        assertTrue(target.readBytes().contentEquals(data))
    }

    @Test
    fun recoversFrom416() = withServer { fx ->
        val target = File(fx.dir, "d.bin")
        File(fx.dir, "d.bin.part").writeBytes(ByteArray(50) { 1 })
        runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/r416"), target, data.size.toLong(), backoffMs = 10) { } }
        assertTrue(target.readBytes().contentEquals(data))
    }

    @Test
    fun failsClearlyOn404() = withServer { fx ->
        val target = File(fx.dir, "e.bin")
        var error: IOException? = null
        try {
            runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/missing"), target, null, attempts = 2, backoffMs = 10) { } }
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
            runBlocking { HttpFetcher.downloadWithRetry(url(fx, "/full"), target, 12345L, backoffMs = 10) { } }
        } catch (e: IOException) {
            error = e
        }
        assertTrue("IOException attendue", error != null)
        assertTrue(error!!.message!!.contains("corrompu"))
        assertFalse("le fichier faux doit être supprimé", target.exists())
    }
}
