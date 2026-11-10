package app.tradulive

import com.k2fsa.sherpa.onnx.Vad

/** Adaptateur : le détecteur de voix Silero de sherpa-onnx vu comme un SpeechDetector. */
class SherpaSpeechDetector(private val vad: Vad) : SpeechDetector {
    override fun accept(samples: FloatArray) {
        vad.acceptWaveform(samples)
        // On n'utilise que l'indicateur « ça parle » : on vide la file des segments pour ne pas l'encombrer.
        while (!vad.empty()) vad.pop()
    }

    override fun isSpeaking(): Boolean = vad.isSpeechDetected()
}
