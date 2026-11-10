package app.tradulive

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection

/**
 * Capte le son.
 *  - projection != null : son INTERNE du téléphone (ce que joue YouTube...), avant la sortie audio.
 *    C'est pour ça que ça marche avec un casque sans fil, filaire, ou sans rien de branché.
 *  - projection == null : micro.
 * Les échantillons sont envoyés en 16 kHz mono, valeurs entre -1 et 1.
 */
class AudioCapture(private val projection: MediaProjection?) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(onSamples: (FloatArray) -> Unit): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val rec: AudioRecord = try {
            if (projection != null) {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            }
        } catch (e: Exception) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        record = rec
        running = true
        rec.startRecording()

        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val shorts = ShortArray(1600) // 0,1 seconde
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n > 0) {
                    val floats = FloatArray(n) { i -> shorts[i] / 32768f }
                    onSamples(floats)
                } else if (n < 0) {
                    break
                }
            }
        }, "tradulive-audio").also { it.start() }

        return true
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            thread?.join(800)
        } catch (_: InterruptedException) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        thread = null
    }
}
