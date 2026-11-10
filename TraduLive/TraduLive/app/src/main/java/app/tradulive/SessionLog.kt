package app.tradulive

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Garde la transcription (original + traduction) pour pouvoir la relire ou la partager. */
object SessionLog {
    private const val NAME = "transcript.txt"
    const val MAX_BYTES = 1_500_000L
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun file(context: Context) = File(context.filesDir, NAME)

    fun append(context: Context, sub: Subtitle) = append(file(context), sub)
    fun read(context: Context, maxChars: Int = 100_000): String = read(file(context), maxChars)
    fun clear(context: Context) = clear(file(context))

    // Versions sur fichier : sans Android, donc testables.

    @Synchronized
    fun append(f: File, sub: Subtitle) {
        if (sub.original.isBlank()) return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                // Fichier trop gros : on ne garde que la fin, en repartant d'une entrée entière.
                val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                val cut = tail.indexOf("\n\n")
                f.writeText(if (cut >= 0) tail.substring(cut + 2) else tail)
            }
            val sb = StringBuilder()
            sb.append('[').append(fmt.format(Date())).append("] ")
                .append(Languages.badge(sub.src)).append(" → ").append(Languages.badge(sub.tgt)).append('\n')
            sb.append(sub.original).append('\n')
            if (sub.translated != null) sb.append(sub.translated).append('\n')
            sb.append('\n')
            f.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(f: File, maxChars: Int = 100_000): String = try {
        if (f.exists()) f.readText().takeLast(maxChars) else ""
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear(f: File) {
        try {
            f.delete()
        } catch (_: Exception) {
        }
    }
}
