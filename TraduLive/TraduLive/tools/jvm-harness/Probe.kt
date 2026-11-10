import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun readWav(path: String): FloatArray {
    val b = File(path).readBytes()
    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12; var channels = 1; var rate = 16000; var bits = 16
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4); val sz = bb.getInt(pos + 4)
        if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
        if (id == "data") {
            val n = minOf(sz, b.size - pos - 8) / 2 / channels
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = bb.getShort(pos + 8 + i * 2 * channels) / 32768f
            require(bits == 16) { "wav $path bits=$bits" }
            if (rate == 16000) return out
            val m = (out.size.toLong() * 16000 / rate).toInt()
            return FloatArray(m) { i -> val x = i.toDouble() * rate / 16000; val j = x.toInt().coerceAtMost(out.size - 2); val f = (x - j).toFloat(); out[j] * (1 - f) + out[j + 1] * f }
        }
        pos += 8 + sz + (sz and 1)
    }
    error("no data chunk in $path")
}

fun recognizer(model: String, lang: String, threads: Int): OfflineRecognizer {
    val d = "/tmp/wt/models/sherpa-onnx-whisper-$model"
    val whisper = OfflineWhisperModelConfig(encoder = "$d/$model-encoder.int8.onnx", decoder = "$d/$model-decoder.int8.onnx", language = lang, task = "transcribe")
    val mc = OfflineModelConfig(whisper = whisper, tokens = "$d/$model-tokens.txt", numThreads = threads, debug = false, provider = "cpu")
    val cfg = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = mc, decodingMethod = "greedy_search")
    return OfflineRecognizer(assetManager = null, config = cfg)
}

fun decode(r: OfflineRecognizer, s: FloatArray): Triple<String, String, Long> {
    val t0 = System.nanoTime()
    val st = r.createStream()
    st.acceptWaveform(s, 16000); r.decode(st)
    val res = r.getResult(st); st.release()
    return Triple(res.text.trim(), res.lang, (System.nanoTime() - t0) / 1_000_000)
}

fun main(args: Array<String>) {
    val mode = args[0]
    if (mode == "lang") {
        val model = args[1]
        val clips = listOf("fr0","fr1","fr2","es0","es1","es2","en0","en1","en2")
            .map { it to "/tmp/wt/$it.wav" } +
            listOf("de" to "/tmp/wt/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/test_wavs/de.wav",
                   "en_canary" to "/tmp/wt/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/test_wavs/en.wav",
                   "zh" to "/tmp/wt/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/test_wavs/zh.wav",
                   "ja" to "/tmp/wt/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/test_wavs/ja.wav",
                   "ko" to "/tmp/wt/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/test_wavs/ko.wav")
        val t0 = System.nanoTime()
        val r = recognizer(model, "", args[2].toInt())
        println("[$model] chargement du modèle: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val warm = decode(r, FloatArray(16000)); println("[$model] premier décodage à vide: ${warm.third} ms")
        for ((name, path) in clips) {
            val s = readWav(path); val (text, lang, ms) = decode(r, s)
            println("[$model] %-9s %5.1fs  lang='%s'  %5d ms  « %s »".format(name, s.size / 16000.0, lang, ms, text.take(90)))
        }
        r.release()
    }
    if (mode == "scale") {
        val model = args[1]
        val r = recognizer(model, "", args[2].toInt())
        decode(r, FloatArray(16000))
        val base = listOf("fr0","fr1","es0","es1","en0","en1").map { readWav("/tmp/wt/$it.wav") }
        val all = base.fold(FloatArray(0)) { a, b -> a + FloatArray(4000) + b }   // phrases séparées par 0,25 s de silence
        for (sec in listOf(1, 2, 4, 8, 12, 18, 24)) {
            val n = minOf(all.size, sec * 16000)
            val s = all.copyOf(n)
            val (text, lang, ms) = decode(r, s)
            println("[$model] audio=%2ds -> %5d ms  (x%.2f temps réel)  lang=%s  %d car.".format(sec, ms, ms / (n / 16000.0 * 1000), lang, text.length))
        }
        r.release()
    }
}
