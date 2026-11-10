package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilterTest {
    @Test
    fun filtersKnownSubtitleCredits() {
        assertTrue(TextFilter.isNoise("Sous-titres réalisés par la communauté d'Amara.org"))
        assertTrue(TextFilter.isNoise("Subtitles by the Amara.org community"))
        assertTrue(TextFilter.isNoise("Sous-titrage Société Radio-Canada"))
    }

    @Test
    fun filtersLoopsAndEmptyText() {
        assertTrue(TextFilter.isNoise("Merci. Merci. Merci. Merci. Merci. Merci. Merci."))
        assertTrue(TextFilter.isNoise("..."))
        assertTrue(TextFilter.isNoise("♪ ♪"))
        assertTrue(TextFilter.isNoise(""))
    }

    @Test
    fun keepsRealSentences() {
        assertFalse(TextFilter.isNoise("Thank you."))
        assertFalse(TextFilter.isNoise("Merci."))
        assertFalse(TextFilter.isNoise("Bonjour à tous, aujourd'hui nous allons parler de l'histoire de l'Empire romain."))
        assertFalse(TextFilter.isNoise("Non, non, non, je ne veux pas partir."))
    }

    @Test
    fun keyIgnoresCaseAndPunctuation() {
        assertEquals(TextFilter.key("Hello, World!"), TextFilter.key("hello world"))
    }
}
