package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionLogTest {
    private fun tmp(): File = File(Files.createTempDirectory("tl").toFile(), "transcript.txt")

    @Test
    fun savesOriginalAndTranslation() {
        val f = tmp()
        SessionLog.append(f, Subtitle("Hola a todos", "Bonjour à tous", "es", "fr"))
        val t = SessionLog.read(f)
        assertTrue(t.contains("ES → FR"))
        assertTrue(t.contains("Hola a todos"))
        assertTrue(t.contains("Bonjour à tous"))
    }

    @Test
    fun savesOriginalOnlyWhenNoTranslation() {
        val f = tmp()
        SessionLog.append(f, Subtitle("Hello", null, "en", "fr"))
        val t = SessionLog.read(f)
        assertTrue(t.contains("Hello"))
        assertEquals(2, t.trim().lines().size) // en-tête + original, pas de ligne de traduction
    }

    @Test
    fun ignoresEmptySubtitles() {
        val f = tmp()
        SessionLog.append(f, Subtitle("", null, "", "", true))
        SessionLog.append(f, Subtitle("   ", null, "en", "fr"))
        assertFalse(f.exists())
    }

    @Test
    fun clearDeletesTheFile() {
        val f = tmp()
        SessionLog.append(f, Subtitle("Hello", "Bonjour", "en", "fr"))
        SessionLog.clear(f)
        assertEquals("", SessionLog.read(f))
    }

    @Test
    fun readReturnsOnlyTheEndWhenTooLong() {
        val f = tmp()
        f.writeText("x".repeat(500) + "FIN")
        val t = SessionLog.read(f, 100)
        assertEquals(100, t.length)
        assertTrue(t.endsWith("FIN"))
    }

    @Test
    fun trimsAHugeFileAndKeepsWholeEntries() {
        val f = tmp()
        val entry = "[00:00:00] EN → FR\n" + "mot ".repeat(60) + "\nPhrase traduite\n\n"
        f.writeText(entry.repeat((SessionLog.MAX_BYTES / entry.length + 50).toInt()))
        SessionLog.append(f, Subtitle("Dernier", "Dernier traduit", "en", "fr"))
        assertTrue("le fichier doit être réduit", f.length() < SessionLog.MAX_BYTES)
        val t = f.readText()
        assertTrue(t.startsWith("[00:00:00]")) // on repart d'une entrée entière
        assertTrue(t.endsWith("Dernier traduit\n\n"))
    }
}
