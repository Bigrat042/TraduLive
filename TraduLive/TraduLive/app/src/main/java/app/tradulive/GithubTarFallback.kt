package app.tradulive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Source de secours : l'archive officielle .tar.bz2 sur GitHub, dont on n'extrait que les fichiers utiles. */
object GithubTarFallback {

    suspend fun extract(
        archiveUrl: URL,
        folder: File,
        wanted: Set<String>,
        approxTotalBytes: Long,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val conn = archiveUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TraduLive/1.2")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Erreur HTTP ${conn.responseCode}")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else approxTotalBytes
            var bytesRead = 0L
            var lastPercent = -1

            val counting = object : FilterInputStream(conn.inputStream) {
                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) bytesRead++
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) bytesRead += n
                    return n
                }
            }
            val source: InputStream = BufferedInputStream(counting, 64 * 1024)

            TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
                val buf = ByteArray(64 * 1024)
                var entry: ArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val base = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && base in wanted) {
                        val part = File(folder, "$base.part")
                        FileOutputStream(part).buffered().use { out ->
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                currentCoroutineContext().ensureActive()
                                val p = ((bytesRead * 100) / total).toInt().coerceIn(0, 99)
                                if (p != lastPercent) {
                                    lastPercent = p
                                    onProgress(p)
                                }
                            }
                        }
                        val target = File(folder, base)
                        target.delete()
                        if (!part.renameTo(target)) throw IOException("Impossible d'enregistrer $base")
                    }
                    entry = tar.nextEntry
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
