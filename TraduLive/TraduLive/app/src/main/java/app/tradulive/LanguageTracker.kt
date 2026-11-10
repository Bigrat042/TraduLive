package app.tradulive

/** Whisper et ML Kit n'écrivent pas toujours le code de la même façon. */
fun normalizeWhisperLang(code: String): String = when (code.trim().lowercase()) {
    "nn" -> "no"
    "yue" -> "zh"
    else -> code.trim().lowercase()
}

/**
 * La détection de langue de Whisper se trompe parfois sur un extrait très court
 * (mesuré : « Thank you » détecté « bo », 1 s de français détecté « ru » avec le modèle tiny).
 * Règles :
 *  - une langue que la traduction ne gère pas est ignorée ;
 *  - au tout début, un extrait provisoire de moins de 2 s ne suffit pas pour choisir la langue ;
 *  - on ne change de langue que si la nouvelle langue est confirmée deux fois de suite,
 *    ou si une phrase finale est longue (alors la détection est fiable).
 */
class LanguageTracker {
    var stable = ""
        private set
    private var candidate = ""
    private var candidateCount = 0

    fun reset() {
        stable = ""
        candidate = ""
        candidateCount = 0
    }

    fun resolve(
        detectedRaw: String,
        textLen: Int,
        audioSeconds: Float,
        isFinal: Boolean,
        isSupported: (String) -> Boolean,
    ): String {
        val d = normalizeWhisperLang(detectedRaw)
        if (d.isEmpty() || !isSupported(d)) return stable

        if (stable.isEmpty()) {
            if (!isFinal && audioSeconds < 2f) return ""
            stable = d
            return d
        }
        if (d == stable) {
            candidate = ""
            candidateCount = 0
            return d
        }
        if (isFinal && textLen >= 40) {
            stable = d
            candidate = ""
            candidateCount = 0
            return d
        }
        if (d == candidate) {
            candidateCount++
        } else {
            candidate = d
            candidateCount = 1
        }
        if (candidateCount >= 2) {
            stable = d
            candidate = ""
            candidateCount = 0
        }
        return stable
    }
}
