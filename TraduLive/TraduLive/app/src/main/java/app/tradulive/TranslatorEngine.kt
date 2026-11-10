package app.tradulive

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Traduction hors ligne avec Google ML Kit.
 * Les langues sont téléchargées une seule fois (environ 30 Mo chacune), puis tout marche sans internet.
 */
class TranslatorEngine {
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val conditions = DownloadConditions.Builder().build()

    private fun client(src: String, tgt: String): Translator =
        translators.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

    /** Télécharge si besoin les modèles de langue. Renvoie false en cas d'échec (pas d'internet...). */
    suspend fun prepare(src: String, tgt: String): Boolean {
        val key = "$src>$tgt"
        if (key in ready) return true
        return try {
            client(src, tgt).downloadModelIfNeeded(conditions).await()
            ready.add(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Traduit un texte. Renvoie null si la traduction est impossible. */
    suspend fun translate(text: String, src: String, tgt: String): String? {
        if (src == tgt) return text
        if (!prepare(src, tgt)) return null
        return try {
            client(src, tgt).translate(text).await()
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        ready.clear()
    }
}
