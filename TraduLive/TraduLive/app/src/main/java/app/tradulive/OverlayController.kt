package app.tradulive

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bulle de sous-titres flottante, déplaçable avec le doigt, affichée par-dessus toutes les applis.
 * Affiche la phrase précédente (en gris) et la phrase en cours (texte provisoire puis définitif).
 * Toutes les méthodes publiques peuvent être appelées depuis n'importe quel thread.
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var background: GradientDrawable? = null
    private var badge: TextView? = null
    private var translatedView: TextView? = null
    private var originalView: TextView? = null
    private var statusView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var locked = false

    // Contenu affiché
    private var prevLine = ""
    private var curLine = ""
    private var curOriginal = ""
    private var curNote = ""
    private var curIsFinal = true

    private val clearRunnable = Runnable {
        prevLine = ""
        curLine = ""
        curOriginal = ""
        curNote = ""
        curIsFinal = true
        badge?.text = ""
        render()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun show() = handler.post { build() }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            root?.let { runCatching { wm.removeView(it) } }
            root = null
        }
    }

    fun setStatus(text: String) = handler.post {
        statusView?.text = text
        statusView?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun toggleLocked() = handler.post { applyLocked(!locked) }

    /** À appeler quand l'écran tourne : on recalcule la largeur et on garde la bulle visible. */
    fun onScreenChanged() = handler.post {
        val p = params ?: return@post
        val r = root ?: return@post
        val dm = context.resources.displayMetrics
        p.width = (dm.widthPixels * 0.94f).toInt()
        p.x = p.x.coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (dm.heightPixels - dp(100)).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(r, p) }
    }

    fun showSubtitle(sub: Subtitle) = handler.post {
        if (sub.original.isEmpty()) {
            // Le texte provisoire était du bruit : on l'enlève.
            if (!curIsFinal) {
                curLine = prevLine
                prevLine = ""
                curOriginal = ""
                curNote = ""
                curIsFinal = true
                render()
            }
            return@post
        }

        val text = sub.translated ?: sub.original
        if (curIsFinal && curLine.isNotEmpty()) prevLine = curLine
        curLine = text
        curIsFinal = sub.isFinal
        if (sub.translated != null) {
            curOriginal = sub.original
            curNote = ""
        } else {
            curOriginal = ""
            curNote = "(traduction indisponible)"
        }
        badge?.text = "${Languages.badge(sub.src)} → ${Languages.badge(sub.tgt)}"
        statusView?.visibility = View.GONE
        render()
        handler.removeCallbacks(clearRunnable)
        handler.postDelayed(clearRunnable, 8000)
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        if (prevLine.isNotEmpty()) {
            sb.append(prevLine)
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#90A4AE")),
                0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("\n")
        }
        val start = sb.length
        sb.append(curLine)
        if (!curIsFinal && curLine.isNotEmpty()) {
            sb.append(" …")
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#CFD8DC")),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        translatedView?.text = sb

        val orig = when {
            curNote.isNotEmpty() -> curNote
            prefs.showOriginal -> curOriginal
            else -> ""
        }
        originalView?.text = orig
        originalView?.visibility = if (orig.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyStyle() {
        val size = prefs.textSizeSp.toFloat()
        translatedView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        originalView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.75f)
        val alpha = (prefs.bgOpacity.coerceIn(30, 100) * 255) / 100
        background?.setColor(Color.argb(alpha, 16, 20, 24))
    }

    private fun applyLocked(value: Boolean) {
        locked = value
        val p = params ?: return
        val r = root ?: return
        p.flags = if (locked) {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(r, p) }
        statusView?.text = if (locked) {
            "Affichage verrouillé : les touches passent à travers. Déverrouille depuis la notification."
        } else {
            ""
        }
        statusView?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun smallButton(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setOnClickListener { action() }
        }

    private fun build() {
        if (root != null) return

        val bg = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
        background = bg

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            this.background = bg
        }

        // Ligne du haut : langues + boutons
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val b = TextView(context).apply {
            setTextColor(Color.parseColor("#80CBC4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
        badge = b
        topRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(smallButton("A−") {
            prefs.textSizeSp = (prefs.textSizeSp - 2).coerceAtLeast(12)
            applyStyle()
        })
        topRow.addView(smallButton("A+") {
            prefs.textSizeSp = (prefs.textSizeSp + 2).coerceAtMost(34)
            applyStyle()
        })
        topRow.addView(smallButton("Orig.") {
            prefs.showOriginal = !prefs.showOriginal
            render()
        })
        topRow.addView(smallButton("Fixer") { applyLocked(true) })
        topRow.addView(smallButton("✕") { onStop() })
        container.addView(topRow)

        val t = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 6
        }
        translatedView = t
        container.addView(t)

        val o = TextView(context).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            setTypeface(typeface, Typeface.ITALIC)
            maxLines = 3
            visibility = View.GONE
        }
        originalView = o
        container.addView(o)

        val s = TextView(context).apply {
            setTextColor(Color.parseColor("#90A4AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        statusView = s
        container.addView(s)

        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.94f).toInt()
        val startX = if (prefs.overlayX >= 0) prefs.overlayX else (dm.widthPixels * 0.03f).toInt()
        val startY = if (prefs.overlayY >= 0) prefs.overlayY else (dm.heightPixels * 0.62f).toInt()
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX.coerceIn(0, (dm.widthPixels - width).coerceAtLeast(0))
            y = startY.coerceIn(0, (dm.heightPixels - dp(100)).coerceAtLeast(0))
        }
        params = lp

        // Glisser la bulle avec le doigt (la position est mémorisée)
        container.setOnTouchListener(object : View.OnTouchListener {
            private var startPx = 0
            private var startPy = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = params ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startPx = p.x
                        startPy = p.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = startPx + (e.rawX - touchX).toInt()
                        p.y = startPy + (e.rawY - touchY).toInt()
                        runCatching { wm.updateViewLayout(container, p) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.overlayX = p.x
                        prefs.overlayY = p.y
                        return true
                    }
                }
                return false
            }
        })

        root = container
        applyStyle()
        try {
            wm.addView(container, lp)
        } catch (e: Exception) {
            // Autorisation « afficher par-dessus les autres applis » retirée entre-temps : on arrête proprement.
            root = null
            onStop()
        }
    }
}
