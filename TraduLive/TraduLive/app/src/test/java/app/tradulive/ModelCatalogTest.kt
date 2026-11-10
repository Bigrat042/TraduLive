package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun everyModelHasThreeFilesAndKnownSizes() {
        for (m in WhisperModels.all) {
            val names = WhisperModels.fileNames(m)
            assertEquals(3, names.size)
            assertEquals(m.encoderBytes, WhisperModels.expectedSize(m, names[0]))
            assertEquals(m.decoderBytes, WhisperModels.expectedSize(m, names[1]))
            assertNull(WhisperModels.expectedSize(m, names[2]))
            assertTrue(m.encoderBytes > 0 && m.decoderBytes > 0)
        }
    }

    @Test
    fun approximateSizeMatchesRealSize() {
        // « Mo » = mégaoctets (10^6 octets), comme affiché à l'utilisateur ; +1 Mo environ pour le fichier de vocabulaire.
        for (m in WhisperModels.all) {
            val realMb = (m.encoderBytes + m.decoderBytes) / 1_000_000.0
            assertTrue("${m.id}: ${m.approxMb} Mo annoncés pour $realMb Mo réels", kotlin.math.abs(realMb - m.approxMb) <= 2.0)
        }
    }

    @Test
    fun unknownIdFallsBackToSmall() {
        assertEquals("small", WhisperModels.byId("nimporte-quoi").id)
    }

    @Test
    fun defaultFilesFollowTheOfficialNaming() {
        val m = WhisperModels.byId("base")
        assertEquals(listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"), WhisperModels.fileNames(m))
    }
}
