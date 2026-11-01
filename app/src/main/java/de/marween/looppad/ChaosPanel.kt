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

/** ⚡ CHAOS-TRIGGER: 4×4-Stutter-Pads, 8 Resonator-Tasten, 4 Regler, Portal-Warp-Zone. */
class ChaosPanel(private val host: View) {
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
    private val bClose = RectF()
    private val bPreset = RectF()
    private val pads = Array(16) { RectF() }
    private val res = Array(8) { RectF() }
    private val sliders = Array(4) { RectF() }
    private val SL = arrayOf("AUDIO-MARROW", "MATERIAL", "SCRATCH-INTENSITY", "STUTTER-MIX")
    private val portal = RectF()
    private var drag = -1
    private var lastX = 0f
    private val padPtr = HashMap<Int, Int>()
    private var portalPtr = -1
    private val ROWS = arrayOf("1/8", "1/16", "1/32", "1/64")
    private val SCALE = intArrayOf(0, 3, 5, 7, 10, 12, 15, 19)

    fun open() { isOpen = true; Chaos.armed = true }
    fun close() { isOpen = false; Chaos.padHeld = -1; Chaos.portalHeld = false; padPtr.clear(); portalPtr = -1 }

    private fun get(i: Int) = when (i) { 0 -> Chaos.marrow; 1 -> Chaos.material; 2 -> Chaos.scratch; else -> Chaos.wet }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) { 0 -> Chaos.marrow = c; 1 -> Chaos.material = c; 2 -> Chaos.scratch = c; else -> Chaos.wet = c }
        Chaos.presetIdx = -1
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(5f); val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        bPreset.set(box.left + pad + dp(118f), box.top + pad, bClose.left - dp(6f), box.top + pad + bh)
        val lw = dp(34f)
        val gTop = box.top + pad + bh + dp(34f)
        val pw = (w - lw - 3 * g) / 4
        val ph = min(pw, dp(70f))
        for (k in 0 until 16) {
            val r = k / 4; val c = k % 4
            pads[k].set(box.left + pad + lw + c * (pw + g), gTop + r * (ph + g), box.left + pad + lw + c * (pw + g) + pw, gTop + r * (ph + g) + ph)
        }
        val rTop = pads[15].bottom + dp(26f)
        val rw = (w - 7 * dp(4f)) / 8
        for (k in 0 until 8) res[k].set(box.left + pad + k * (rw + dp(4f)), rTop, box.left + pad + k * (rw + dp(4f)) + rw, rTop + dp(44f))
        val sTop = res[0].bottom + dp(10f)
        val sh = dp(38f)
        val sw = (w - g) / 2
        for (i in 0 until 4) {
            val r = i / 2; val c = i % 2
            sliders[i].set(box.left + pad + c * (sw + g), sTop + r * (sh + g), box.left + pad + c * (sw + g) + sw, sTop + r * (sh + g) + sh)
        }
        portal.set(box.left + pad, sliders[3].bottom + dp(10f), box.right - pad, box.bottom - pad)
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int, size: Float = 11.5f) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(26, 24, 36)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 120, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(size), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(140, 255, 120)
        val colCols = intArrayOf(Color.rgb(255, 120, 90), Color.rgb(140, 255, 120), Color.rgb(100, 180, 255), Color.rgb(255, 210, 80))
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(10, 12, 16)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("⚡ CHAOS", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val bpm = Engine.currentBpm()
        val st = "zerlegt den laufenden Master" + (if (bpm > 0f) " · ${bpm.toInt()} BPM" else "") + " · Tonart ${InstState.scaleNoteNames[Math.floorMod(Chaos.rootPc, 12)]}" +
            (if (Chaos.padHeld >= 0) " · Stutter ×${String.format(java.util.Locale.GERMANY, "%.1f", Chaos.stutterRate)}" else "")
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        btn(c, bClose, "✕", false, acc)
        btn(c, bPreset, "◀ " + (if (Chaos.presetIdx >= 0) Chaos.PRESETS[Chaos.presetIdx].name else "VORLAGE") + " ▶", Chaos.presetIdx >= 0, col)
        // Spalten-Köpfe + Reihen
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(9f)
        for (cI in 0 until 4) { text.color = colCols[cI]; c.drawText(Chaos.COL_NAMES[cI], pads[cI].centerX(), pads[cI].top - dp(6f), text) }
        text.color = Color.argb(200, 255, 255, 255)
        for (rI in 0 until 4) c.drawText(ROWS[rI], box.left + dp(10f) + dp(17f), pads[rI * 4].centerY() + dp(3f), text)
        for (k in 0 until 16) {
            val on = Chaos.padHeld == k
            val cc = colCols[k % 4]
            if (on) {
                glow.maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL)
                glow.color = cc
                c.drawRoundRect(pads[k], dp(10f), dp(10f), glow)
                glow.maskFilter = null
            }
            btn(c, pads[k], ROWS[k / 4], on, cc, 10f)
        }
        // Resonatoren
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(9.5f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("AUDIO-MARROW · Resonatoren antippen = Ton aus dem Track", res[0].left, res[0].top - dp(7f), text)
        text.textAlign = Paint.Align.CENTER
        for (k in 0 until 8) {
            val lv = Chaos.resLevel[k]
            val r = res[k]
            fill.color = Color.rgb(30 + (120 * lv).toInt(), 40 + (150 * lv).toInt(), 30 + (60 * lv).toInt())
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            stroke.strokeWidth = dp(1f); stroke.color = col
            c.drawRoundRect(r, dp(7f), dp(7f), stroke)
            text.color = Color.WHITE; text.textSize = min(dp(12f), r.width() * 0.34f)
            c.drawText(InstState.scaleNoteNames[Math.floorMod(Chaos.rootPc + SCALE[k], 12)], r.centerX(), r.centerY() + dp(4f), text)
        }
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(24, 30, 26)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            if (i == 2 && Chaos.scratchFlash > 0.1f) { stroke.color = Color.WHITE; stroke.strokeWidth = dp(2f); c.drawRoundRect(r, dp(7f), dp(7f), stroke) }
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(fitLabel(text, SL[i], r.width() * 0.72f, dp(10f), dp(6.5f)), r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(if (i == 1) (if (Chaos.material < 0.33f) "TON" else if (Chaos.material < 0.66f) "HOLZ" else "METALL") else "${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        // Portal
        val pa = Chaos.portalAmt
        val p = portal
        if (p.height() > dp(30f)) {
            fill.shader = LinearGradient(p.left, p.top, p.right, p.bottom, Color.rgb((40 + 160 * pa).toInt(), 20, (60 + 150 * pa).toInt()), Color.rgb(10, 10, (30 + 100 * pa).toInt()), Shader.TileMode.CLAMP)
            c.drawRoundRect(p, dp(14f), dp(14f), fill)
            fill.shader = null
            val df = Chaos.dropFlash
            stroke.strokeWidth = dp(2f + 4f * df); stroke.color = if (df > 0.1f) Color.WHITE else Color.rgb(200, 120, 255)
            c.drawRoundRect(p, dp(14f), dp(14f), stroke)
            // Strudel
            stroke.strokeWidth = dp(1.5f)
            val t = android.os.SystemClock.uptimeMillis() / 1000f
            for (k in 0 until 5) {
                val rr = (min(p.width(), p.height()) * 0.12f * (k + 1)) * (1f - 0.4f * pa)
                stroke.color = Color.argb((60 + 120 * pa).toInt(), 220, 160, 255)
                c.drawArc(RectF(p.centerX() - rr, p.centerY() - rr * 0.5f, p.centerX() + rr, p.centerY() + rr * 0.5f), (t * (60 + 200 * pa) + k * 50) % 360, 220f, false, stroke)
            }
            text.color = Color.WHITE; text.textSize = dp(15f)
            c.drawText(if (Chaos.portalHeld) "🌀 IM STRUDEL … loslassen = DROP auf der Eins" else if (df > 0.2f) "💥 DROP" else "🌀 PORTAL-WARP · halten", p.centerX(), p.centerY() + dp(5f), text)
        }
        host.postInvalidateOnAnimation()
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = pads.indexOfFirst { it.contains(x, y) }
                if (k >= 0) { padPtr[id] = k; Chaos.padHeld = k; haptic(); return }
                if (portal.contains(x, y)) { portalPtr = id; Chaos.portalHeld = true; haptic(); onHint?.invoke("🌀 Strudel … im Takt loslassen – der Drop kommt auf der nächsten Eins"); return }
                val r = res.indexOfFirst { it.contains(x, y) }
                if (r >= 0) { Chaos.tapRes(r); haptic(); return }
                for (s in 0 until 4) if (sliders[s].contains(x, y)) { drag = s; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN && bClose.contains(x, y)) { close(); haptic() }
                if (e.actionMasked == MotionEvent.ACTION_DOWN && bPreset.contains(x, y)) {
                    haptic()
                    val cur = Chaos.presetIdx
                    Chaos.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                    onHint?.invoke("⚡ ${Chaos.PRESETS[Chaos.presetIdx].name}")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (pI in 0 until e.pointerCount) {
                    val pid = e.getPointerId(pI)
                    if (padPtr.containsKey(pid)) {
                        val k = pads.indexOfFirst { it.contains(e.getX(pI), e.getY(pI)) }
                        if (k >= 0 && k != padPtr[pid]) { padPtr[pid] = k; Chaos.padHeld = k }
                    }
                }
                if (drag in 0..3) { val px = e.getX(0); set(drag, get(drag) + (px - lastX) / sliders[drag].width()); lastX = px }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (padPtr.remove(id) != null) Chaos.padHeld = padPtr.values.lastOrNull() ?: -1
                if (id == portalPtr) { portalPtr = -1; Chaos.portalHeld = false }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) { drag = -1; if (e.actionMasked == MotionEvent.ACTION_CANCEL) { Chaos.padHeld = -1; Chaos.portalHeld = false; padPtr.clear() } }
            }
        }
    }
}
