package de.marween.looppad

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Bedienfeld für [Architect]: Matrix mit 4 Spuren (eigene Längen, Wahrscheinlichkeit je Schritt), 4 Makros, XY-Dynamik. */
class ArchitectPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bNew = RectF(); private val bKey = RectF(); private val bRec = RectF(); private val bRecBars = RectF()
    private val bPreset = RectF()
    private val names = Array(Architect.T) { RectF() }
    private val lens = Array(Architect.T) { RectF() }
    private val cells = Array(Architect.T) { Array(16) { RectF() } }
    private val sliders = Array(4) { RectF() }
    private val SL = arrayOf("SNAP", "HOLZ ↔ METALL", "DECAY / AIR", "CHAOS / SWING")
    private val xy = RectF()
    private var drag = -1
    private var lastX = 0f
    private var recBars = 2
    private val COLS = intArrayOf(Color.rgb(255, 110, 80), Color.rgb(90, 200, 255), Color.rgb(255, 210, 90), Color.rgb(170, 140, 255))

    fun open() { isOpen = true; if (Architect.presetIdx < 0 && Architect.steps.all { r -> r.all { it == 0 } }) Architect.applyPreset(0) }
    fun close() { isOpen = false }

    private fun get(i: Int) = when (i) { 0 -> Architect.snap; 1 -> Architect.material; 2 -> Architect.air; else -> Architect.chaos }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) { 0 -> Architect.snap = c; 1 -> Architect.material = c; 2 -> Architect.air = c; else -> Architect.chaos = c }
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f)
        val g = dp(6f)
        val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val row1 = box.top + pad + bh + dp(20f)
        val rs = arrayOf(bOn, bNew, bKey, bRec)
        val ws = floatArrayOf(0.24f, 0.2f, 0.24f, 0.2f)
        var x0 = box.left + pad
        val avail = w - 4 * g - dp(40f)
        for (i in 0 until 4) { rs[i].set(x0, row1, x0 + avail * ws[i] / 0.88f, row1 + bh); x0 = rs[i].right + g }
        bRecBars.set(x0, row1, box.right - pad, row1 + bh)
        val row2 = row1 + bh + g
        bPreset.set(box.left + pad, row2, box.right - pad, row2 + bh)
        val mTop = row2 + bh + dp(10f)
        val rh = dp(40f)
        val nw = dp(64f); val lw = dp(48f)
        val cw = (w - nw - lw - 2 * g) / 16
        for (t in 0 until Architect.T) {
            val y = mTop + t * (rh + dp(5f))
            names[t].set(box.left + pad, y, box.left + pad + nw, y + rh)
            for (k in 0 until 16) cells[t][k].set(names[t].right + g + k * cw + dp(1f), y, names[t].right + g + (k + 1) * cw - dp(1f), y + rh)
            lens[t].set(box.right - pad - lw, y, box.right - pad, y + rh)
        }
        val sTop = mTop + Architect.T * (rh + dp(5f)) + dp(6f)
        val sh = dp(42f)
        val sw = (w - g) / 2
        for (i in 0 until 4) {
            val r = i / 2; val c = i % 2
            sliders[i].set(box.left + pad + c * (sw + g), sTop + r * (sh + g), box.left + pad + c * (sw + g) + sw, sTop + r * (sh + g) + sh)
        }
        val xyTop = sTop + 2 * (sh + g) + dp(4f)
        xy.set(box.left + pad, xyTop, box.right - pad, box.bottom - pad - dp(14f))
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(28, 30, 38)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11.5f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(120, 230, 200)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(12, 18, 20)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🧬 RHYTHM ARCHITECT", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val lensTxt = (0 until Architect.T).joinToString(" : ") { "${Architect.len[it]}" }
        val rt = Architect.rootShown
        val st = (if (Architect.on) "läuft" else "aus") + " · Spuren $lensTxt" + (if (Architect.pitchTrack) " · gestimmt auf " + (if (rt >= 0) InstState.scaleNoteNames[rt] else "– (keine Tonart)") else "")
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Architect.on) "■ STOP" else "▶ START", Architect.on, Color.rgb(140, 230, 170))
        btn(c, bNew, "🎲 NEU", false, col)
        btn(c, bKey, if (Architect.pitchTrack) "TONART: AN" else "TONART: AUS", Architect.pitchTrack, col)
        btn(c, bRec, "⏺ AUF PAD", false, Color.rgb(255, 90, 90))
        btn(c, bRecBars, "$recBars T", false, Color.rgb(255, 90, 90))
        btn(c, bPreset, "◀  " + (if (Architect.presetIdx >= 0) Architect.PRESETS[Architect.presetIdx].name else "VORLAGE WÄHLEN") + "  ▶", Architect.presetIdx >= 0, col)
        // Matrix
        for (t in 0 until Architect.T) {
            val tc = COLS[t]
            val fl = Architect.flash[t].coerceIn(0f, 1f)
            btn(c, names[t], Architect.TYPES[Architect.type[t]].name, fl > 0.3f, tc)
            val n = Architect.len[t]
            val p = if (Architect.on) Architect.pos[t] else -1
            for (k in 0 until 16) {
                val r = cells[t][k]
                val v = Architect.steps[t][k]
                fill.color = when {
                    k >= n -> Color.rgb(16, 17, 20)
                    v == 0 -> Color.rgb(36, 38, 46)
                    else -> Color.argb(70 + 46 * v, Color.red(tc), Color.green(tc), Color.blue(tc))
                }
                c.drawRoundRect(r, dp(3f), dp(3f), fill)
                if (k < n && v > 0 && Architect.acc[t][k]) { fill.color = Color.WHITE; c.drawCircle(r.centerX(), r.top + dp(5f), dp(2f), fill) }
                if (k == p) { stroke.strokeWidth = dp(2f); stroke.color = Color.WHITE; c.drawRoundRect(r, dp(3f), dp(3f), stroke) }
            }
            btn(c, lens[t], "◂ $n ▸", false, tc)
        }
        // Makros
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(24, 32, 34)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(fitLabel(text, SL[i], r.width() * 0.7f, dp(10f), dp(6.5f)), r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
        }
        text.textAlign = Paint.Align.CENTER
        drawXy(c, col)
        host.postInvalidateOnAnimation()
    }

    private fun drawXy(c: Canvas, col: Int) {
        val r = xy
        if (r.height() < dp(40f)) return
        fill.shader = LinearGradient(r.left, r.bottom, r.right, r.top, Color.rgb(18, 26, 40), Color.rgb(40, 22, 18), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        fill.shader = null
        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(50, 255, 255, 255)
        for (k in 1..3) {
            c.drawLine(r.left + r.width() * k / 4, r.top, r.left + r.width() * k / 4, r.bottom, stroke)
            c.drawLine(r.left, r.top + r.height() * k / 4, r.right, r.top + r.height() * k / 4, stroke)
        }
        val px = r.left + Architect.x * r.width()
        val py = r.bottom - Architect.y * r.height()
        glow.maskFilter = BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb((80 + 170 * Architect.meter.coerceIn(0f, 1f)).toInt().coerceAtMost(255), Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(px, py, dp(20f), glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(6f), fill)
        text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(9f)
        text.textAlign = Paint.Align.LEFT
        c.drawText("← WEICH (Textur)", r.left + dp(6f), r.bottom + dp(11f), text)
        c.drawText("↑ GEBROCHEN (Micro-Rhythmen)", r.left + dp(6f), r.top + dp(12f), text)
        c.drawText("↓ GERADE", r.left + dp(6f), r.bottom - dp(6f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("HART (Transient) →", r.right - dp(6f), r.bottom + dp(11f), text)
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
                for (k in 0 until 4) if (sliders[k].contains(x, y)) { drag = k; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) {
                val px = e.getX(p); val py = e.getY(p)
                if (drag == 10) setXy(px, py)
                else if (drag in 0..3) { set(drag, get(drag) + (px - lastX) / sliders[drag].width()); lastX = px }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
    }

    private fun setXy(px: Float, py: Float) {
        Architect.x = ((px - xy.left) / xy.width()).coerceIn(0f, 1f)
        Architect.y = ((xy.bottom - py) / xy.height()).coerceIn(0f, 1f)
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                haptic()
                Architect.on = !Architect.on
                if (Architect.on && !Engine.seqPlaying) Engine.transportPlay(false)
                onHint?.invoke(if (Architect.on) "🧬 Läuft im Takt · XY: rechts = knackiger, oben = gebrochener · Spurlängen ◂ ▸ verschieben die Muster gegeneinander" else "RHYTHM ARCHITECT gestoppt")
            }
            bNew.contains(x, y) -> { haptic(); Architect.reroll(); onHint?.invoke("🎲 Neue Muster: " + (0 until Architect.T).joinToString(" : ") { "${Architect.len[it]}" } + " Schritte") }
            bKey.contains(x, y) -> {
                haptic(); Architect.pitchTrack = !Architect.pitchTrack
                onHint?.invoke(if (Architect.pitchTrack) "Tonale Klänge (Kick, Tom, Conga, Holz, Glocke) werden auf Grundton/Quinte der Tonart gestimmt" else "Klänge in ihrer eigenen Stimmung")
            }
            bRecBars.contains(x, y) -> { recBars = when (recBars) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; haptic() }
            bRec.contains(x, y) -> {
                haptic()
                if (!Architect.on) { Architect.on = true; if (!Engine.seqPlaying) Engine.transportPlay(false) }
                val p = Engine.recordInstLoop(128, recBars)
                if (p < 0) onHint?.invoke("Kein freies Pad (1–12) – erst eins leeren")
                else onHint?.invoke("Ab der nächsten Eins: $recBars Takt" + (if (recBars > 1) "e" else "") + " RHYTHM ARCHITECT → Pad ${p + 1}")
            }
            bPreset.contains(x, y) -> {
                haptic()
                val cur = Architect.presetIdx
                Architect.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                onHint?.invoke("Vorlage ${Architect.presetIdx + 1}/${Architect.PRESETS.size}: ${Architect.PRESETS[Architect.presetIdx].name}")
            }
            else -> {
                for (t in 0 until Architect.T) {
                    if (names[t].contains(x, y)) {
                        Architect.type[t] = (Architect.type[t] + 1) % Architect.TYPES.size
                        Architect.preview(t)
                        haptic()
                        onHint?.invoke("Spur ${t + 1}: ${Architect.TYPES[Architect.type[t]].name}")
                        return
                    }
                    if (lens[t].contains(x, y)) {
                        val n = Architect.len[t] + if (x < lens[t].centerX()) -1 else 1
                        Architect.len[t] = n.coerceIn(2, 16)
                        haptic()
                        onHint?.invoke("Spur ${t + 1}: ${Architect.len[t]} Schritte")
                        return
                    }
                    for (k in 0 until 16) if (cells[t][k].contains(x, y)) {
                        haptic()
                        if (k >= Architect.len[t]) { Architect.len[t] = k + 1; onHint?.invoke("Spur ${t + 1}: ${k + 1} Schritte"); return }
                        val v = Architect.steps[t][k]
                        Architect.steps[t][k] = when (v) { 0 -> 4; 4 -> 3; 3 -> 2; 2 -> 1; else -> 0 }
                        if (Architect.steps[t][k] == 0) Architect.acc[t][k] = false
                        Architect.presetIdx = -1
                        val nv = Architect.steps[t][k]
                        onHint?.invoke(if (nv == 0) "Schritt aus" else "Schritt ${k + 1}: ${nv * 25} % Wahrscheinlichkeit")
                        return
                    }
                }
            }
        }
    }
}
