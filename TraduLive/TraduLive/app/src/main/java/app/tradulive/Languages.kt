package app.tradulive

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

object Languages {

    /** Langues gérées par la traduction hors ligne (ML Kit), triées par nom français. */
    val codes: List<String> by lazy {
        TranslateLanguage.getAllLanguages().sortedBy { displayName(it) }
    }

    /** Nom de la langue en français (ex. "es" -> "Espagnol"). */
    fun displayName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH)
        if (name.isNullOrBlank()) return code
        return name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
    }

    fun isSupported(code: String): Boolean = code in codes

    fun badge(code: String): String = if (code.isBlank() || code == "?") "?" else code.uppercase()
}
