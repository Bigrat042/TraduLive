package app.tradulive

/** Whisper invente parfois des phrases sur du silence ou de la musique. On filtre les cas évidents. */
object TextFilter {

    fun isNoise(text: String): Boolean {
        val t = text.lowercase()
        if (t.contains("amara.org")) return true
        if (t.contains("sous-titres réalisés par")) return true
        if (t.contains("sous-titrage société radio-canada")) return true
        val words = t.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        // Répétition en boucle ("Merci. Merci. Merci. ...")
        if (words.size >= 6 && words.toSet().size.toFloat() / words.size < 0.3f) return true
        return false
    }

    /** Clé de comparaison pour repérer deux fois la même phrase. */
    fun key(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
