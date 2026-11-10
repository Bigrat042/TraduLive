package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux détecteur : « ça parle » si le morceau est fort, et le reste 200 ms après (comme le vrai détecteur). */
private class FakeDetector(private val hangoverSamples: Int = 3200) : SpeechDetector {
    private var quietSince = Int.MAX_VALUE
    private var speaking = false
    override fun accept(samples: FloatArray) {
        val loud = samples.any { it > 0.1f }
        if (loud) {
            speaking = true
            quietSince = 0
        } else {
            quietSince += samples.size
            if (quietSince >= hangoverSamples) speaking = false
        }
    }

    override fun isSpeaking() = speaking
}

private class Recorder : Segmenter.Listener {
    val partials = ArrayList<Pair<Int, Long>>() // (taille, id)
    val finals = ArrayList<Pair<FloatArray, Long>>()
    override fun onPartial(samples: FloatArray, utterId: Long) {
        partials.add(samples.size to utterId)
    }

    override fun onFinal(samples: FloatArray, utterId: Long) {
        finals.add(samples to utterId)
    }
}

class SegmenterTest {
    private val chunk = 1600 // 0,1 s à 16 kHz

    private fun feed(seg: Segmenter, data: FloatArray) {
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            seg.push(data.copyOfRange(i, i + n))
            i += n
        }
    }

    private fun silence(sec: Double) = FloatArray((sec * 16000).toInt())
    private fun speech(sec: Double) = FloatArray((sec * 16000).toInt()) { 0.5f }

    private fun newSeg(rec: Recorder, partials: Boolean = true) = Segmenter(FakeDetector(), rec, partials, 16000)

    @Test
    fun silenceOnlyProducesNothing() {
        val rec = Recorder()
        feed(newSeg(rec), silence(10.0))
        assertEquals(0, rec.finals.size)
        assertEquals(0, rec.partials.size)
    }

    @Test
    fun oneUtteranceGivesOneFinalWithPreRoll() {
        val rec = Recorder()
        val seg = newSeg(rec)
        feed(seg, silence(1.0) + speech(3.0) + silence(2.0))
        assertEquals(1, rec.finals.size)
        val (final, id) = rec.finals[0]
        assertEquals(1L, id)
        // 0,4 s avant la voix + 3 s de voix : rien ne doit manquer au début
        assertTrue("final trop court : ${final.size}", final.size >= 6400 + 48000)
        assertTrue("final trop long : ${final.size}", final.size <= 6400 + 48000 + 3200 + 8000)
        assertEquals(0f, final[0], 0f)
        assertEquals(0f, final[6399], 0f)
        assertEquals(0.5f, final[6400], 0f)
    }

    @Test
    fun partialsGrowAndBelongToTheCurrentUtterance() {
        val rec = Recorder()
        feed(newSeg(rec), silence(1.0) + speech(3.0) + silence(2.0))
        assertTrue("pas assez de provisoires : ${rec.partials.size}", rec.partials.size >= 5)
        assertTrue(rec.partials.all { it.second == 1L })
        assertTrue(rec.partials.first().first >= 16000) // pas avant 1 s de parole
        val sizes = rec.partials.map { it.first }
        assertEquals(sizes.sorted(), sizes) // la taille ne fait que grandir
        assertTrue(sizes.last() <= rec.finals[0].first.size)
    }

    @Test
    fun partialsCanBeDisabled() {
        val rec = Recorder()
        feed(newSeg(rec, partials = false), silence(1.0) + speech(3.0) + silence(2.0))
        assertEquals(0, rec.partials.size)
        assertEquals(1, rec.finals.size)
    }

    @Test
    fun shortPauseDoesNotSplitTheSentence() {
        val rec = Recorder()
        feed(newSeg(rec), silence(0.5) + speech(1.0) + silence(0.3) + speech(1.0) + silence(2.0))
        assertEquals(1, rec.finals.size)
    }

    @Test
    fun longPauseSplitsIntoTwoUtterances() {
        val rec = Recorder()
        feed(newSeg(rec), silence(0.5) + speech(1.0) + silence(1.0) + speech(1.0) + silence(2.0))
        assertEquals(2, rec.finals.size)
        assertEquals(listOf(1L, 2L), rec.finals.map { it.second })
        assertTrue(rec.partials.any { it.second == 2L } || rec.finals[1].first.size < 16000 + 6400 + 20000)
    }

    @Test
    fun veryLongSpeechIsCutAndOverlapped() {
        val rec = Recorder()
        val ramp = FloatArray(25 * 16000) { 0.2f + (it % 1000) / 4000f } // toujours « fort », reconnaissable
        feed(newSeg(rec), ramp + silence(2.0))
        assertTrue("attendu au moins 2 phrases, obtenu ${rec.finals.size}", rec.finals.size >= 2)
        val max = Segmenter.MAX_UTTER_MS * 16 + 1600
        assertTrue(rec.finals.all { it.first.size <= max })
        // recouvrement : les 0,4 s finales de la 1re phrase ouvrent la 2e (rien n'est perdu à la coupure)
        val a = rec.finals[0].first
        val b = rec.finals[1].first
        val n = 6400
        assertTrue(a.copyOfRange(a.size - n, a.size).contentEquals(b.copyOfRange(0, n)))
    }

    @Test
    fun currentUtteranceIdFollowsTheStream() {
        val rec = Recorder()
        val seg = newSeg(rec)
        assertEquals(0L, seg.currentUtterId)
        feed(seg, silence(0.5) + speech(1.0))
        assertEquals(1L, seg.currentUtterId)
        feed(seg, silence(1.5) + speech(1.0))
        assertEquals(2L, seg.currentUtterId)
    }
}
