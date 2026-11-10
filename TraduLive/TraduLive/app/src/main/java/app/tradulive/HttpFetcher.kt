package app.tradulive

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Téléchargement de fichiers : reprise après coupure, nouveaux essais, vérification de la taille. Sans dépendance Android. */
object HttpFetcher {

    /**
     * Télécharge url vers target. Si la connexion coupe, reprend là où elle s'est arrêtée (en-tête Range).
     * Si expectedSize est connu, vérifie la taille exacte du fichier final.
     */
    suspend fun downloadWithRetry(
        url: URL,
        target: File,
        expectedSize: Long?,
        attempts: Int = 4,
        backoffMs: Long = 1500L,
        onBytes: (Long) -> Unit,
    ) {
        val part = File(target.parentFile, target.name + ".part")
        var attempt = 0
        while (true) {
            try {
                fetchResumable(url, part, onBytes)
                break
            } catch (e: IOException) {
                attempt++
                if (attempt >= attempts) throw e
                delay(backoffMs * attempt)
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Impossible d'enregistrer ${target.name}")
        if (expectedSize != null && target.length() != expectedSize) {
            val got = target.length()
            target.delete()
            throw IOException("Fichier incomplet ou corrompu : ${target.name} fait $got octets au lieu de $expectedSize")
        }
    }

    /** Un seul passage : ajoute à part.part ce qui manque. Lève IOException en cas de coupure. */
    suspend fun fetchResumable(url: URL, part: File, onBytes: (Long) -> Unit) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TraduLive/1.2")
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = conn.responseCode
            val append: Boolean = when (code) {
                206 -> true   // le serveur reprend où on s'est arrêté
                200 -> false  // le serveur ignore Range : on recommence depuis le début
                416 -> {
                    part.delete()
                    throw IOException("Reprise impossible, nouvel essai")
                }
                else -> throw IOException("Erreur HTTP $code")
            }
            val offset = if (append) existing else 0L
            val expected = conn.contentLengthLong
            var got = 0L
            FileOutputStream(part, append).buffered().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        currentCoroutineContext().ensureActive()
                        onBytes(offset + got)
                    }
                }
            }
            if (expected >= 0 && got != expected) throw IOException("Téléchargement incomplet")
        } finally {
            conn.disconnect()
        }
    }
}
