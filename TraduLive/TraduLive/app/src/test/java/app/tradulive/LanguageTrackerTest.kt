package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTrackerTest {
    private val ok = setOf("fr", "en", "es", "de", "no", "zh")
    private val supported: (String) -> Boolean = { it in ok }
    private val longText = 45
    private val shortText = 12

    @Test
    fun shortFirstPartialDoesNotChooseTheLanguage() {
        val t = LanguageTracker()
        assertEquals("", t.resolve("ru", shortText, 1.0f, false, supported))
        assertEquals("", t.stable)
    }

    @Test
    fun firstFinalIsAccepted() {
        val t = LanguageTracker()
        assertEquals("fr", t.resolve("fr", shortText, 1.0f, true, supported))
    }

    @Test
    fun firstLongEnoughPartialIsAccepted() {
        val t = LanguageTracker()
        assertEquals("es", t.resolve("es", shortText, 2.5f, false, supported))
    }

    @Test
    fun unsupportedLanguageIsIgnored() {
        val t = LanguageTracker()
        t.resolve("fr", shortText, 3f, true, supported)
        assertEquals("fr", t.resolve("bo", shortText, 3f, true, supported)) // « bo » = tibétain, mesuré sur « Thank you »
        assertEquals("fr", t.stable)
    }

    @Test
    fun unsupportedLanguageNeverBecomesTheFirstLanguage() {
        val t = LanguageTracker()
        assertEquals("", t.resolve("bo", shortText, 3f, true, supported))
        assertEquals("", t.stable)
    }

    @Test
    fun switchNeedsTwoConfirmations() {
        val t = LanguageTracker()
        t.resolve("fr", longText, 3f, true, supported)
        assertEquals("fr", t.resolve("en", shortText, 3f, false, supported))
        assertEquals("en", t.resolve("en", shortText, 3f, false, supported))
    }

    @Test
    fun anIsolatedOtherDetectionDoesNotSwitch() {
        val t = LanguageTracker()
        t.resolve("fr", longText, 3f, true, supported)
        assertEquals("fr", t.resolve("en", shortText, 3f, true, supported))
        assertEquals("fr", t.resolve("fr", shortText, 3f, true, supported))
        assertEquals("fr", t.resolve("en", shortText, 3f, true, supported)) // le compteur est reparti à 1
        assertEquals("fr", t.stable)
    }

    @Test
    fun longFinalSwitchesImmediately() {
        val t = LanguageTracker()
        t.resolve("fr", longText, 3f, true, supported)
        assertEquals("de", t.resolve("de", longText, 6f, true, supported))
    }

    @Test
    fun longPartialDoesNotSwitchImmediately() {
        val t = LanguageTracker()
        t.resolve("fr", longText, 3f, true, supported)
        assertEquals("fr", t.resolve("de", longText, 6f, false, supported))
    }

    @Test
    fun normalizesWhisperCodes() {
        assertEquals("no", normalizeWhisperLang("nn"))
        assertEquals("zh", normalizeWhisperLang("yue"))
        assertEquals("fr", normalizeWhisperLang(" FR "))
    }
}
