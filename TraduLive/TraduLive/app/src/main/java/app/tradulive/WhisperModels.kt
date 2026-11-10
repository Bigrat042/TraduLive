package app.tradulive

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URL

/** Les modèles Whisper (multilingues, version int8) qu'on peut télécharger sur le téléphone. */
object WhisperModels {

    /**
     * expectedSizes : taille exacte (en octets) des deux fichiers .onnx, relevée dans les archives officielles
     * du projet sherpa-onnx. Sert à détecter un fichier tronqué ou corrompu.
     */
    data class Model(
        val id: String,
        val label: String,
        val repo: String,
        val prefix: String,
        val approxMb: Int,
        val encoderBytes: Long,
        val decoderBytes: Long,
    )

    val all: List<Model> = listOf(
        Model("tiny", "Ultra-rapide (tiny, moins précis)", "csukuangfj/sherpa-onnx-whisper-tiny", "tiny", 104, 12_937_772L, 89_855_401L),
        Model("base", "Rapide (base)", "csukuangfj/sherpa-onnx-whisper-base", "base", 161, 29_120_534L, 130_672_026L),
        Model("small", "Équilibré (small) – recommandé", "csukuangfj/sherpa-onnx-whisper-small", "small", 375, 112_442_483L, 262_226_114L),
        Model("turbo", "Très précis (turbo, lourd)", "csukuangfj/sherpa-onnx-whisper-turbo", "turbo", 1037, 674_716_297L, 361_080_764L),
    )

    private const val MIN_TOKENS_BYTES = 100_000L

    fun byId(id: String): Model = all.firstOrNull { it.id == id } ?: all[2]

    fun fileNames(m: Model): List<String> = listOf(
        "${m.prefix}-encoder.int8.onnx",
        "${m.prefix}-decoder.int8.onnx",
        "${m.prefix}-tokens.txt",
    )

    /** Taille exacte attendue, ou null si inconnue (fichier de vocabulaire). */
    fun expectedSize(m: Model, name: String): Long? = when (name) {
        "${m.prefix}-encoder.int8.onnx" -> m.encoderBytes
        "${m.prefix}-decoder.int8.onnx" -> m.decoderBytes
        else -> null
    }

    fun dir(context: Context, m: Model): File = File(context.filesDir, "models/${m.id}")

    fun file(context: Context, m: Model, name: String): File = File(dir(context, m), name)

    private fun isFileOk(f: File, expected: Long?): Boolean =
        f.exists() && (if (expected != null) f.length() == expected else f.length() >= MIN_TOKENS_BYTES)

    /** Installé = les 3 fichiers sont là, avec la taille exacte attendue pour les deux modèles. */
    fun isInstalled(context: Context, m: Model): Boolean =
        fileNames(m).all { n -> isFileOk(file(context, m, n), expectedSize(m, n)) }

    // ------------------------------------------------------------------ téléchargement

    /**
     * Télécharge les 3 fichiers (Hugging Face d'abord, puis l'archive officielle sur GitHub en secours).
     * Reprend après une coupure et vérifie la taille de chaque fichier.
     * onProgress reçoit un pourcentage de 0 à 100.
     */
    suspend fun download(context: Context, m: Model, onProgress: (Int) -> Unit) {
        try {
            downloadFromHub(context, m, onProgress)
        } catch (e: IOException) {
            downloadFromGithub(context, m, onProgress)
        }
        if (!isInstalled(context, m)) throw IOException("Modèle incomplet après téléchargement")
        onProgress(100)
    }

    private suspend fun downloadFromHub(context: Context, m: Model, onProgress: (Int) -> Unit) {
        val folder = dir(context, m)
        folder.mkdirs()
        val totalApprox = m.approxMb.toLong() * 1024L * 1024L
        var doneBefore = 0L
        var lastPercent = -1

        for (name in fileNames(m)) {
            val target = File(folder, name)
            val expected = expectedSize(m, name)
            if (isFileOk(target, expected)) {
                doneBefore += target.length()
                continue
            }
            target.delete() // présent mais de mauvaise taille : on le refait
            val url = URL("https://huggingface.co/${m.repo}/resolve/main/$name")
            val base = doneBefore
            HttpFetcher.downloadWithRetry(url, target, expected) { fileBytes ->
                val p = (((base + fileBytes) * 100) / totalApprox).toInt().coerceIn(0, 99)
                if (p != lastPercent) {
                    lastPercent = p
                    onProgress(p)
                }
            }
            doneBefore += target.length()
        }
    }

    private suspend fun downloadFromGithub(context: Context, m: Model, onProgress: (Int) -> Unit) {
        val url = URL(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-${m.id}.tar.bz2"
        )
        GithubTarFallback.extract(
            archiveUrl = url,
            folder = dir(context, m),
            wanted = fileNames(m).toSet(),
            approxTotalBytes = m.approxMb.toLong() * 1024L * 1024L,
            onProgress = onProgress,
        )
    }

    // ------------------------------------------------------------------ état partagé du téléchargement
    // Le téléchargement tourne dans une portée propre à l'application : il continue si l'écran tourne
    // ou si on change d'appli (tant que le processus vit). L'écran lit simplement ces valeurs.

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var downloadingId: String? = null
        private set

    @Volatile
    var downloadPercent: Int = 0
        private set

    @Volatile
    var downloadError: String? = null
        private set

    fun startDownload(context: Context, m: Model) {
        if (downloadingId != null) return
        val app = context.applicationContext
        downloadingId = m.id
        downloadPercent = 0
        downloadError = null
        ioScope.launch {
            try {
                download(app, m) { downloadPercent = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = e.message ?: "erreur inconnue"
            } finally {
                downloadingId = null
            }
        }
    }

    fun clearError() {
        downloadError = null
    }
}
