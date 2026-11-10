package app.tradulive

/** Dit si ça parle en ce moment. Implémenté par le détecteur de voix (Silero) ou, dans les tests, par un faux. */
interface SpeechDetector {
    fun accept(samples: FloatArray)
    fun isSpeaking(): Boolean
}

/** Tableau de nombres qui grandit tout seul (pour accumuler le son sans recréer de tableaux). */
class FloatBuf(initial: Int) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    fun append(src: FloatArray, count: Int = src.size) {
        if (count <= 0) return
        ensure(size + count)
        System.arraycopy(src, 0, data, size, count)
        size += count
    }

    fun clear() {
        size = 0
    }

    fun copy(): FloatArray = data.copyOf(size)

    fun copyTail(n: Int): FloatArray {
        val k = minOf(n, size)
        return data.copyOfRange(size - k, size)
    }

    fun keepTail(n: Int) {
        if (size <= n) return
        System.arraycopy(data, size - n, data, 0, n)
        size = n
    }

    private fun ensure(cap: Int) {
        if (cap > data.size) data = data.copyOf(maxOf(cap, data.size * 2))
    }
}

/**
 * Découpe le flux audio en phrases :
 *  - garde un peu de son AVANT le début détecté de la voix (pre-roll) ;
 *  - demande régulièrement un texte provisoire pendant que la phrase se poursuit ;
 *  - termine la phrase après un silence, ou la coupe si elle devient trop longue (avec recouvrement).
 * Tout est exprimé en nombre d'échantillons, donc indépendant de l'horloge : testable sans téléphone.
 */
class Segmenter(
    private val detector: SpeechDetector,
    private val listener: Listener,
    private val partialsEnabled: Boolean,
    private val sampleRate: Int = 16000,
) {
    interface Listener {
        fun onPartial(samples: FloatArray, utterId: Long)
        fun onFinal(samples: FloatArray, utterId: Long)
    }

    companion object {
        const val PRE_ROLL_MS = 400       // son gardé avant le début de la voix
        const val END_SILENCE_MS = 350    // silence supplémentaire (après celui du détecteur) avant de terminer
        const val MAX_UTTER_MS = 10_000   // une phrase plus longue est coupée (le décodage coûte plus cher quand c'est long)
        const val MIN_PARTIAL_MS = 1000   // pas de texte provisoire avant 1 s de parole
        const val PARTIAL_STEP_MS = 500   // nouveau texte provisoire tous les 0,5 s de parole
    }

    private val preRollN = sampleRate * PRE_ROLL_MS / 1000
    private val endSilenceN = sampleRate * END_SILENCE_MS / 1000
    private val maxUtterN = sampleRate * MAX_UTTER_MS / 1000
    private val minPartialN = sampleRate * MIN_PARTIAL_MS / 1000
    private val partialStepN = sampleRate * PARTIAL_STEP_MS / 1000

    private val preRoll = FloatBuf(preRollN * 2)
    private val utter = FloatBuf(maxUtterN + sampleRate)
    private var inUtterance = false
    private var silenceSamples = 0
    private var lastPartialAt = 0
    private var utterId = 0L

    @Volatile
    var currentUtterId = 0L
        private set

    fun push(chunk: FloatArray) {
        detector.accept(chunk)
        val speaking = detector.isSpeaking()

        if (!inUtterance) {
            if (speaking) {
                inUtterance = true
                silenceSamples = 0
                utterId++
                currentUtterId = utterId
                lastPartialAt = 0
                utter.clear()
                utter.append(preRoll.data, preRoll.size)
                utter.append(chunk)
                preRoll.clear()
            } else {
                preRoll.append(chunk)
                preRoll.keepTail(preRollN)
            }
            return
        }

        utter.append(chunk)
        if (speaking) silenceSamples = 0 else silenceSamples += chunk.size

        if (silenceSamples >= endSilenceN || utter.size >= maxUtterN) {
            finish()
        } else if (partialsEnabled && utter.size >= minPartialN && utter.size - lastPartialAt >= partialStepN) {
            lastPartialAt = utter.size
            listener.onPartial(utter.copy(), utterId)
        }
    }

    private fun finish() {
        val id = utterId
        listener.onFinal(utter.copy(), id)
        // Si la phrase a été coupée parce qu'elle était trop longue, on garde un petit recouvrement.
        val tail = utter.copyTail(preRollN)
        preRoll.clear()
        preRoll.append(tail)
        utter.clear()
        inUtterance = false
        silenceSamples = 0
    }
}
