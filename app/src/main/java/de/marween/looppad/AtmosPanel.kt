package de.marween.looppad

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Bedienfeld für [Atmos]: großes XY (Filter / Raum), Ein-Aus mit Schwell, Schnipsel einfangen, wenige Regler. */
class AtmosPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    /** Datei als Körner-Quelle wählen (MainActivity). */
    var onPickFile: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bGrab = RectF(); private val bFile = RectF()
    private val bKey = RectF(); private val bWander = RectF()
    private val bPreset = RectF(); private val bTimbre = RectF(); private val bChord = RectF()
    private val xy = RectF()
    private val sliders = Array(6) { RectF() }
    private val SL_NAMES = arrayOf("LAUTSTÄRKE", "FM ↔ KÖRNER", "BEWEGUNG", "DICHTE", "SHIMMER", "EIN/AUS-BLENDE")
    private var drag = -1          // 0..5 Regler, 10 = XY
    private var lastX = 0f
    private var t0 = SystemClock.uptimeMillis()

    private val KEYS = intArrayOf(-1, 21, 12, 14, 16, 17, 19, 0, 2, 4, 5, 7, 9)

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    private fun get(i: Int): Float = when (i) {
        0 -> Atmos.level; 1 -> Atmos.blend; 2 -> Atmos.evolve; 3 -> Atmos.density; 4 -> Atmos.shimmer
        else -> ((Atmos.fadeSec - 0.5f) / 11.5f).coerceIn(0f, 1f)
    }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) {
            0 -> Atmos.level = c; 1 -> Atmos.blend = c; 2 -> Atmos.evolve = c; 3 -> Atmos.density = c; 4 -> Atmos.shimmer = c
            else -> Atmos.fadeSec = 0.5f + c * 11.5f
        }
    }
    private fun label(i: Int): String = when (i) {
        1 -> if (!Atmos.hasSnip()) "nur FM (kein Schnipsel)" else "${(100 - get(1) * 100).toInt()} : ${(get(1) * 100).toInt()}"
        5 -> String.format(java.util.Locale.GERMANY, "%.1f s", Atmos.fadeSec)
        else -> "${(get(i) * 100).toInt()} %"
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f)
        val bh = dp(34f)
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val row1 = box.top + pad + bh + dp(22f)
        val n = 5
        val gw = (box.width() - 2 * pad - (n - 1) * dp(6f)) / n
        val rs = arrayOf(bOn, bGrab, bFile, bKey, bWander)
        for (i in 0 until n) rs[i].set(box.left + pad + i * (gw + dp(6f)), row1, box.left + pad + i * (gw + dp(6f)) + gw, row1 + bh)
        // Vorlage · Klang · Akkord-Art
        val row2 = row1 + bh + dp(6f)
        val w2 = box.width() - 2 * pad
        bPreset.set(box.left + pad, row2, box.left + pad + w2 * 0.46f, row2 + bh)
        bTimbre.set(bPreset.right + dp(6f), row2, bPreset.right + dp(6f) + w2 * 0.26f, row2 + bh)
        bChord.set(bTimbre.right + dp(6f), row2, box.right - pad, row2 + bh)
        // Regler: zwei Reihen à 3
        val sTop = row2 + bh + dp(8f)
        val sh = dp(38f)
        val sw = (box.width() - 2 * pad - 2 * dp(6f)) / 3
        for (i in 0 until 6) {
            val r = i / 3; val c = i % 3
            sliders[i].set(box.left + pad + c * (sw + dp(6f)), sTop + r * (sh + dp(6f)), box.left + pad + c * (sw + dp(6f)) + sw, sTop + r * (sh + dp(6f)) + sh)
        }
        val xyTop = sTop + 2 * (sh + dp(6f)) + dp(6f)
        xy.set(box.left + pad, xyTop, box.right - pad, box.bottom - pad - dp(14f))
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(30, 32, 42)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11.5f), r.height() * 0.42f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6.5f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val tm = (SystemClock.uptimeMillis() - t0) / 1000f
        val col = Color.rgb(140, 170, 255)
        val col2 = Color.rgb(230, 140, 255)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(12, 13, 22)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🌌 ATMOS · Klangteppich", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val src = when {
            !Atmos.hasSnip() -> "FM-Fläche"
            Atmos.blend < 0.05f -> "FM-Fläche (Schnipsel bereit)"
            else -> String.format(java.util.Locale.GERMANY, "FM + Körner aus %.1f s Schnipsel", Atmos.snipSeconds())
        }
        val chord = if (Atmos.wander) " · Akkord ${Atmos.chordStep + 1}/4" else ""
        val st = (if (Atmos.on) "läuft" else "aus") + " · Tonart ${Keys.name(Atmos.curKey)}" + (if (Atmos.keyAuto) " (vom Deck)" else "") + chord + " · " + src
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(14f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Atmos.on) "■ AUSBLENDEN" else "▶ EINBLENDEN", Atmos.on, Color.rgb(140, 230, 170))
        btn(c, bGrab, "📼 SCHNIPSEL 4 s", false, col2)
        btn(c, bFile, "📂 DATEI", false, col2)
        btn(c, bKey, "TONART: " + if (Atmos.keyAuto) "AUTO" else Keys.name(Atmos.keyManual), !Atmos.keyAuto, col)
        btn(c, bWander, if (Atmos.wander) "AKKORD WANDERT" else "AKKORD STEHT", Atmos.wander, col)
        btn(c, bPreset, "◀  " + (if (Atmos.presetIdx >= 0) Atmos.PRESETS[Atmos.presetIdx].name else "VORLAGE WÄHLEN") + "  ▶", Atmos.presetIdx >= 0, col2)
        btn(c, bTimbre, "KLANG: " + Atmos.TIMBRES[Atmos.timbre], false, col)
        btn(c, bChord, Atmos.CHORDSETS[Atmos.chordSet], Atmos.chordSet != 0, col)
        // Regler
        for (i in 0 until 6) {
            val r = sliders[i]
            fill.color = Color.rgb(26, 28, 40)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            val v = get(i)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * v, r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE
            text.textSize = dp(9f)
            text.textAlign = Paint.Align.LEFT
            c.drawText(fitLabel(text, SL_NAMES[i], r.width() * 0.6f, dp(9f), dp(6.5f)), r.left + dp(6f), r.centerY() + dp(3f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(fitLabel(text, label(i), r.width() * 0.4f, dp(9f), dp(6.5f)), r.right - dp(6f), r.centerY() + dp(3f), text)
        }
        text.textAlign = Paint.Align.CENTER
        drawXy(c, tm, col, col2)
        host.postInvalidateOnAnimation()
    }

    private fun drawXy(c: Canvas, tm: Float, col: Int, col2: Int) {
        val r = xy
        fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(16, 18, 40), Color.rgb(34, 14, 44), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        fill.shader = null
        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(60, 255, 255, 255)
        for (k in 1..3) {
            c.drawLine(r.left + r.width() * k / 4, r.top, r.left + r.width() * k / 4, r.bottom, stroke)
            c.drawLine(r.left, r.top + r.height() * k / 4, r.right, r.top + r.height() * k / 4, stroke)
        }
        val m = Atmos.meter.coerceIn(0f, 1f)
        // Wolke: Körner als leuchtende Punkte, FM-Stimmen als weiche Kreise
        c.save()
        c.clipRect(r)
        val px = r.left + Atmos.x * r.width()
        val py = r.bottom - Atmos.y * r.height()
        glow.maskFilter = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
        for (k in 0 until 6) {
            val a = tm * (0.12f + k * 0.03f) + k
            val rad = r.width() * (0.12f + 0.05f * k) * (0.6f + 0.6f * Atmos.y)
            val cx = px + cos(a.toDouble()).toFloat() * rad * 0.6f
            val cy = py + sin((a * 1.3f).toDouble()).toFloat() * rad * 0.4f
            glow.color = Color.argb((30 + 90 * m).toInt().coerceIn(0, 200), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(cx, cy, dp(16f) + rad * 0.25f, glow)
        }
        glow.maskFilter = null
        val gc = Atmos.grainCount
        for (k in 0 until gc) {
            val gx = r.left + Atmos.grainView[k].coerceIn(0f, 1f) * r.width()
            val gy = r.centerY() - Atmos.grainPan[k] * r.height() * 0.35f + sin((tm * 2 + k).toDouble()).toFloat() * dp(6f)
            fill.color = Color.argb(200, Color.red(col2), Color.green(col2), Color.blue(col2))
            c.drawCircle(gx, gy, dp(2.5f), fill)
        }
        c.restore()
        // Finger
        glow.maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb(if (Atmos.on) 180 else 70, 255, 255, 255)
        c.drawCircle(px, py, dp(18f), glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(5f), fill)
        text.color = Color.argb(200, 255, 255, 255)
        text.textSize = dp(9f)
        text.textAlign = Paint.Align.LEFT
        c.drawText("← DUNKEL", r.left + dp(6f), r.bottom + dp(11f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("HELL → (Filter)", r.right - dp(6f), r.bottom + dp(11f), text)
        text.textAlign = Paint.Align.LEFT
        c.drawText("↑ ENDLOS (Hall)", r.left + dp(6f), r.top + dp(12f), text)
        c.drawText("↓ NAH", r.left + dp(6f), r.bottom - dp(6f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (xy.contains(x, y)) { drag = 10; setXy(x, y); return }
                for (k in 0 until 6) if (sliders[k].contains(x, y)) { drag = k; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val px = e.getX(p); val py = e.getY(p)
                    if (drag == 10 && xy.contains(px, py)) setXy(px, py)
                    else if (drag in 0..5) {
                        val dx = px - lastX
                        set(drag, get(drag) + dx / sliders[drag].width())
                        lastX = px
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
    }

    private fun setXy(x: Float, y: Float) {
        Atmos.x = ((x - xy.left) / xy.width()).coerceIn(0f, 1f)
        Atmos.y = ((xy.bottom - y) / xy.height()).coerceIn(0f, 1f)
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                Atmos.on = !Atmos.on; haptic()
                onHint?.invoke(if (Atmos.on) "ATMOS blendet in ${String.format(java.util.Locale.GERMANY, "%.1f", Atmos.fadeSec)} s ein – XY: Filter und Raum" else "ATMOS blendet aus")
            }
            bGrab.contains(x, y) -> {
                Atmos.grab(4f); haptic()
                if (Atmos.blend < 0.2f) Atmos.blend = 0.5f
                onHint?.invoke("Die letzten 4 s des Masters sind jetzt die Körner-Quelle")
            }
            bFile.contains(x, y) -> { haptic(); onPickFile?.invoke() }
            bKey.contains(x, y) -> {
                haptic()
                val cur = if (Atmos.keyAuto) -1 else Atmos.keyManual
                val k = KEYS[(KEYS.indexOf(cur).coerceAtLeast(0) + 1) % KEYS.size]
                if (k < 0) Atmos.keyAuto = true else { Atmos.keyAuto = false; Atmos.keyManual = k }
                onHint?.invoke(if (Atmos.keyAuto) "Tonart folgt dem laufenden Deck (sonst A-Moll)" else "Tonart fest: ${Keys.name(k)}")
            }
            bPreset.contains(x, y) -> {
                haptic()
                val cur = Atmos.presetIdx
                Atmos.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                onHint?.invoke("Vorlage ${Atmos.presetIdx + 1}/${Atmos.PRESETS.size}: ${Atmos.PRESETS[Atmos.presetIdx].name}" + if (Atmos.blend == 0f && Atmos.PRESETS[Atmos.presetIdx].blend > 0.3f) " – mit 📼 SCHNIPSEL kommen die Körner dazu" else "")
            }
            bTimbre.contains(x, y) -> { haptic(); Atmos.timbre = (Atmos.timbre + 1) % Atmos.TIMBRES.size; onHint?.invoke("Klang: ${Atmos.TIMBRES[Atmos.timbre]}") }
            bChord.contains(x, y) -> { haptic(); Atmos.chordSet = (Atmos.chordSet + 1) % Atmos.CHORDSETS.size; onHint?.invoke("Akkorde: ${Atmos.CHORDSETS[Atmos.chordSet]}") }
            bWander.contains(x, y) -> {
                Atmos.wander = !Atmos.wander; haptic()
                onHint?.invoke(if (Atmos.wander) "Akkord wechselt alle 8 Takte (bleibt in der Tonart)" else "Akkord bleibt auf dem Grundton – reine Drone")
            }
        }
    }
}
