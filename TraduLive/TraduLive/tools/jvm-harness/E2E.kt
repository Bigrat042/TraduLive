import app.tradulive.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun readWav(path: String): FloatArray {
    val b = File(path).readBytes(); val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12; var channels = 1; var rate = 16000
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4); val sz = bb.getInt(pos + 4)
        if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12) }
        if (id == "data") {
            val n = minOf(sz, b.size - pos - 8) / 2 / channels
            val out = FloatArray(n) { bb.getShort(pos + 8 + it * 2 * channels) / 32768f }
            if (rate == 16000) return out
            val m = (out.size.toLong() * 16000 / rate).toInt()
            return FloatArray(m) { i -> val x = i.toDouble() * rate / 16000; val j = x.toInt().coerceAtMost(out.size - 2); val f = (x - j).toFloat(); out[j] * (1 - f) + out[j + 1] * f }
        }
        pos += 8 + sz + (sz and 1)
    }
    error("no data")
}

data class Clip(val name: String, val lang: String, val audio: FloatArray) { var start = 0.0; var end = 0.0 }
data class Ev(val t: Double, val final: Boolean, val id: Long, val text: String, val rawLang: String, val shown: String, val ms: Long, val secs: Double)

fun main(args: Array<String>) {
    val model = args[0]; val partials = args[1] == "1"; val threads = args[2].toInt()
    val d = "/tmp/wt/models/sherpa-onnx-whisper-$model"
    val rec = OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = "$d/$model-encoder.int8.onnx", decoder = "$d/$model-decoder.int8.onnx", language = "", task = "transcribe"),
            tokens = "$d/$model-tokens.txt", numThreads = threads, debug = false, provider = "cpu"),
        decodingMethod = "greedy_search"))
    val vad = Vad(assetManager = null, config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "/tmp/wt/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.2f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 20f), sampleRate = 16000, numThreads = 1, provider = "cpu"))
    // chauffe
    run { val s = rec.createStream(); s.acceptWaveform(FloatArray(16000), 16000); rec.decode(s); rec.getResult(s); s.release() }

    val canary = "/tmp/wt/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/test_wavs"
    val clips = listOf(
        Clip("fr0", "fr", readWav("/tmp/wt/fr0.wav")), Clip("fr1", "fr", readWav("/tmp/wt/fr1.wav")),
        Clip("es0", "es", readWav("/tmp/wt/es0.wav")), Clip("es1", "es", readWav("/tmp/wt/es1.wav")),
        Clip("en0", "en", readWav("/tmp/wt/en0.wav")), Clip("en2", "en", readWav("/tmp/wt/en2.wav")),
        Clip("de", "de", readWav("$canary/de.wav")), Clip("fr2", "fr", readWav("/tmp/wt/fr2.wav")))
    val gap = 1.2
    var stream = FloatArray((1.0 * 16000).toInt())
    for (c in clips) {
        c.start = stream.size / 16000.0; stream += c.audio; c.end = stream.size / 16000.0
        stream += FloatArray((gap * 16000).toInt())
    }
    stream += FloatArray(16000)
    println("[e2e:$model partiels=$partials] flux de test : %.1f s, %d phrases".format(stream.size / 16000.0, clips.size))

    val tracker = LanguageTracker(); val native = "fr"; val other = "en"
    val supported = setOf("fr","en","es","de","zh","ja","ko","ru","it","pt","nl")
    val events = ArrayList<Ev>()
    var pos = 0
    fun decode(s: FloatArray): Triple<String, String, Long> {
        val t0 = System.nanoTime(); val st = rec.createStream(); st.acceptWaveform(s, 16000); rec.decode(st)
        val r = rec.getResult(st); st.release(); return Triple(r.text.trim(), r.lang, (System.nanoTime() - t0) / 1_000_000)
    }
    fun handle(samples: FloatArray, id: Long, final: Boolean) {
        val (text, lang, ms) = decode(samples)
        if (text.length < 2 || TextFilter.isNoise(text)) { events.add(Ev(pos / 16000.0, final, id, "", lang, "(filtré)", ms, samples.size / 16000.0)); return }
        val secs = samples.size / 16000f
        val src = tracker.resolve(lang, text.length, secs, final) { it in supported }
        val shown = if (src.isEmpty()) "?" else "${src}→${if (src == native) other else native}"
        events.add(Ev(pos / 16000.0, final, id, text, lang, shown, ms, secs.toDouble()))
    }
    val listener = object : Segmenter.Listener {
        override fun onPartial(samples: FloatArray, utterId: Long) = handle(samples, utterId, false)
        override fun onFinal(samples: FloatArray, utterId: Long) = handle(samples, utterId, true)
    }
    val seg = Segmenter(SherpaSpeechDetector(vad), listener, partials, 16000)
    var i = 0
    while (i < stream.size) { val n = minOf(1600, stream.size - i); pos = i + n; seg.push(stream.copyOfRange(i, i + n)); i += n }

    println("---- chronologie (temps audio) ----")
    for (e in events) println("t=%5.1fs  %s  phrase#%d  %5.1fs d'audio  détecté='%s'  affiché=%-6s  décodage %5d ms  « %s »".format(e.t, if (e.final) "FINAL " else "partiel", e.id, e.secs, e.rawLang, e.shown, e.ms, e.text.take(70)))
    val finals = events.filter { it.final }
    println("---- bilan ----")
    println("phrases attendues : ${clips.size} ; phrases détectées par le découpage (finals) : ${finals.size}")
    val ids = finals.map { it.id }.distinct()
    for ((k, c) in clips.withIndex()) {
        val fin = finals.firstOrNull { it.t >= c.end && (k + 1 >= clips.size || it.t <= clips[k + 1].start + 0.5) }
        val firstPartial = events.firstOrNull { !it.final && it.t >= c.start && it.t <= c.end + 0.2 }
        val okLang = fin != null && fin.shown.startsWith(c.lang)
        println("%-4s attendu=%s  final: %-30s  1er partiel: %s  fin de parole -> final: %s".format(c.name, c.lang,
            fin?.let { "affiché=${it.shown} détecté=${it.rawLang} ${if (okLang) "OK" else "ÉCART"}" } ?: "AUCUN",
            firstPartial?.let { "+%.1f s après le début".format(it.t - c.start) } ?: "aucun",
            fin?.let { "+%.2f s (temps audio, hors décodage) + décodage %d ms".format(it.t - c.end, it.ms) } ?: "-"))
    }
    rec.release(); vad.release()
}
