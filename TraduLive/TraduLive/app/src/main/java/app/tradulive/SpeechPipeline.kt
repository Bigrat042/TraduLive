package app.tradulive

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Un sous-titre prêt à afficher.
 * isFinal = false : texte provisoire, pendant que la personne parle encore.
 * original vide + isFinal = true : le provisoire était du bruit, on l'efface.
 */
data class Subtitle(
    val original: String,
    val translated: String?,
    val src: String,
    val tgt: String,
    val isFinal: Boolean = true,
)

private class AudioJob(val samples: FloatArray, val id: Long)

/**
 * Son -> découpage en phrases (Segmenter + détecteur de voix) -> Whisper (texte + langue) -> traduction -> sous-titre.
 *
 *  - pendant qu'une phrase est dite, on relance Whisper sur ce qui a été dit jusque-là (texte provisoire) ;
 *  - quand la phrase se termine, on décode la phrase complète (texte définitif).
 */
class SpeechPipeline(
    private val context: Context,
    private val prefs: Prefs,
    private val onSubtitle: (Subtitle) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private companion object {
        const val SR = AudioCapture.SAMPLE_RATE
        const val MIN_RMS = 0.0015f // en dessous : silence numérique
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var segmenter: Segmenter? = null
    private var capture: AudioCapture? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val translator = TranslatorEngine()
    private val tracker = LanguageTracker()

    // Verrous : on ne libère jamais le code natif pendant qu'il sert encore.
    private val vadLock = Any()
    private val decodeLock = Any()

    @Volatile
    private var stopped = false

    // Phrases terminées (priorité) et dernier texte provisoire demandé.
    private val finals = Channel<AudioJob>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val partialSlot = AtomicReference<AudioJob?>(null)
    private var partialsEnabled = true

    private val segListener = object : Segmenter.Listener {
        override fun onPartial(samples: FloatArray, utterId: Long) {
            partialSlot.set(AudioJob(samples, utterId))
        }

        override fun onFinal(samples: FloatArray, utterId: Long) {
            partialSlot.set(null)
            finals.trySend(AudioJob(samples, utterId))
        }
    }

    // --- État de décodage (thread de travail uniquement) ---
    private var lastFinalKey = ""
    private var lastFinalAt = 0L
    private var lastPartialText = ""
    private var partialShownForId = -1L
    private var lastPartialEnd = 0L
    private var partialGap = 300L

    // --- Surveillance du son ---
    private var projectionMode = true
    private var startedAt = 0L

    @Volatile
    private var heardSound = false
    private var warnedSilence = false

    fun start(projection: MediaProjection?) {
        projectionMode = projection != null
        partialsEnabled = prefs.partials
        val handler = CoroutineExceptionHandler { _, e -> onStatus("Erreur : ${e.message}") }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        scope = sc
        job = sc.launch {
            onStatus("Chargement du modèle…")
            val v: Vad = try {
                recognizer = createRecognizer()
                createVad()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onStatus("Erreur au chargement du modèle : ${e.message}. Réinstalle-le depuis l'écran principal.")
                return@launch
            }
            vad = v
            segmenter = Segmenter(SherpaSpeechDetector(v), segListener, partialsEnabled, SR)

            warmUp()

            val cap = AudioCapture(projection)
            startedAt = SystemClock.elapsedRealtime()
            val ok = try {
                cap.start { samples -> onAudio(samples) }
            } catch (e: Throwable) {
                false
            }
            if (!ok) {
                onStatus(
                    if (projectionMode) "Impossible de capter le son du téléphone."
                    else "Impossible d'utiliser le micro (déjà utilisé par une autre appli ?)."
                )
                return@launch
            }
            capture = cap
            onStatus(if (projectionMode) "À l'écoute du son du téléphone…" else "À l'écoute du micro…")

            workerLoop()
        }
    }

    fun stop() {
        stopped = true
        capture?.stop()
        capture = null
        finals.close()
        partialSlot.set(null)
        job?.cancel()
        runBlocking { withTimeoutOrNull(8000) { job?.join() } }
        scope = null
        synchronized(decodeLock) {
            recognizer?.release()
            recognizer = null
        }
        synchronized(vadLock) {
            vad?.release()
            vad = null
            segmenter = null
        }
        translator.close()
    }

    // ------------------------------------------------------------------ création

    private fun createRecognizer(): OfflineRecognizer {
        val m = WhisperModels.byId(prefs.modelId)
        val names = WhisperModels.fileNames(m)
        val whisper = OfflineWhisperModelConfig(
            encoder = WhisperModels.file(context, m, names[0]).absolutePath,
            decoder = WhisperModels.file(context, m, names[1]).absolutePath,
            // Langue vide = Whisper détecte la langue tout seul à chaque morceau de parole.
            language = if (prefs.autoDetect) "" else prefs.fixedSource,
            task = "transcribe",
        )
        val cores = Runtime.getRuntime().availableProcessors()
        val wanted = when (prefs.power) {
            0 -> 3
            2 -> 6
            else -> 4
        }
        val modelConfig = OfflineModelConfig(
            whisper = whisper,
            tokens = WhisperModels.file(context, m, names[2]).absolutePath,
            numThreads = wanted.coerceAtMost(cores).coerceAtLeast(2),
            debug = false,
            provider = "cpu",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun createVad(): Vad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.2f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = 20f,
            ),
            sampleRate = SR,
            numThreads = 1,
            provider = "cpu",
        )
        return Vad(assetManager = context.assets, config = config)
    }

    /** Premier décodage = lent (chargement). On le fait à vide pour que le vrai premier soit rapide. */
    private suspend fun warmUp() {
        onStatus("Préparation…")
        try {
            synchronized(decodeLock) {
                val rec = recognizer
                if (rec != null) {
                    val s = rec.createStream()
                    try {
                        s.acceptWaveform(FloatArray(SR), SR)
                        rec.decode(s)
                        rec.getResult(s)
                    } finally {
                        s.release()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val native = prefs.nativeLang
        val other = prefs.otherLang
        if (translator.prepare(native, other)) translator.translate("ok", native, other)
        if (translator.prepare(other, native)) translator.translate("ok", other, native)
    }

    // ------------------------------------------------------------------ thread audio

    private fun onAudio(chunk: FloatArray) {
        if (stopped) return
        try {
            watchLevel(chunk)
            synchronized(vadLock) {
                if (!stopped) segmenter?.push(chunk)
            }
        } catch (e: Throwable) {
            onStatus("Erreur audio : ${e.message}")
        }
    }

    private fun watchLevel(chunk: FloatArray) {
        var peak = 0f
        for (x in chunk) {
            val a = abs(x)
            if (a > peak) peak = a
        }
        if (peak > 0.003f) heardSound = true
        if (!heardSound && !warnedSilence && SystemClock.elapsedRealtime() - startedAt > 12_000) {
            warnedSilence = true
            onStatus(
                if (projectionMode) {
                    "Aucun son capté. Si la vidéo joue, cette appli bloque la capture : relance en mode Micro."
                } else {
                    "Aucun son capté par le micro."
                }
            )
        }
    }

    // ------------------------------------------------------------------ thread de travail

    private suspend fun workerLoop() {
        val ctx = currentCoroutineContext()
        while (ctx.isActive) {
            val f = finals.tryReceive().getOrNull()
            if (f != null) {
                try {
                    handle(f, true)
                } catch (e: Throwable) {
                    onStatus("Erreur : ${e.message}")
                }
                continue
            }
            val p = partialSlot.get()
            if (p != null && partialsEnabled) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPartialEnd >= partialGap) {
                    partialSlot.compareAndSet(p, null)
                    if (p.id == (segmenter?.currentUtterId ?: -1L)) {
                        try {
                            handle(p, false)
                        } catch (e: Throwable) {
                            onStatus("Erreur : ${e.message}")
                        }
                    }
                    lastPartialEnd = SystemClock.elapsedRealtime()
                    continue
                }
            }
            delay(10)
        }
    }

    private suspend fun handle(item: AudioJob, isFinal: Boolean) {
        if (stopped) return
        if (item.samples.size < SR / 2) return
        val level = rms(item.samples)
        if (level < MIN_RMS) {
            if (isFinal) dropPartialIfShown(item.id)
            return
        }
        val samples = boost(item.samples, level)

        val t0 = SystemClock.elapsedRealtime()
        val result = synchronized(decodeLock) {
            val rec = recognizer ?: return
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SR)
                rec.decode(stream)
                rec.getResult(stream)
            } finally {
                stream.release()
            }
        }
        val took = SystemClock.elapsedRealtime() - t0
        val text = result.text.trim()
        val seconds = item.samples.size.toFloat() / SR

        if (!isFinal) {
            // On espace les provisoires selon la vitesse du téléphone (évite de le faire chauffer pour rien).
            partialGap = (took / 2).coerceIn(250L, 1500L)
            if (text.length < 2 || TextFilter.isNoise(text) || text == lastPartialText) return
            if (item.id != (segmenter?.currentUtterId ?: -1L)) return
            lastPartialText = text
            partialShownForId = item.id
            emit(text, result.lang, seconds, false)
            return
        }

        lastPartialText = ""
        if (text.length < 2 || TextFilter.isNoise(text)) {
            dropPartialIfShown(item.id)
            return
        }
        val key = TextFilter.key(text)
        val now = SystemClock.elapsedRealtime()
        if (key == lastFinalKey && now - lastFinalAt < 4000) {
            dropPartialIfShown(item.id)
            return
        }
        lastFinalKey = key
        lastFinalAt = now
        emit(text, result.lang, seconds, true)
    }

    private fun dropPartialIfShown(id: Long) {
        if (partialShownForId == id) {
            partialShownForId = -1L
            onSubtitle(Subtitle("", null, "", "", true))
        }
    }

    private suspend fun emit(text: String, whisperLang: String, seconds: Float, isFinal: Boolean) {
        val native = prefs.nativeLang
        val other = prefs.otherLang
        val src = if (prefs.autoDetect) {
            tracker.resolve(whisperLang, text.length, seconds, isFinal) { Languages.isSupported(it) }
        } else {
            prefs.fixedSource
        }

        if (src.isEmpty()) {
            onSubtitle(Subtitle(text, null, "?", native, isFinal))
            return
        }
        // Dans les deux sens : ma langue -> autre langue, et n'importe quelle langue -> ma langue.
        val target = if (src == native) other else native

        if (src == target || !Languages.isSupported(src) || !Languages.isSupported(target)) {
            onSubtitle(Subtitle(text, null, src, target, isFinal))
            return
        }
        val translated = translator.translate(text, src, target)
        onSubtitle(Subtitle(text, translated, src, target, isFinal))
    }

    // ------------------------------------------------------------------ outils

    private fun rms(s: FloatArray): Float {
        var acc = 0.0
        for (x in s) acc += (x * x).toDouble()
        return sqrt(acc / s.size).toFloat()
    }

    /** Remonte le volume d'un son trop faible (micro, vidéo à faible volume) : Whisper comprend mieux. */
    private fun boost(src: FloatArray, level: Float): FloatArray {
        val gain = (0.06f / level).coerceIn(1f, 8f)
        if (gain < 1.15f) return src
        val out = FloatArray(src.size)
        for (i in src.indices) {
            val v = src[i] * gain
            out[i] = if (v > 1f) 1f else if (v < -1f) -1f else v
        }
        return out
    }
}
