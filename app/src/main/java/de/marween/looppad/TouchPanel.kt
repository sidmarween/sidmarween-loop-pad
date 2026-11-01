package de.marween.looppad

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 🖐 TOUCH-LAB: ein Feld für Morph-Matrix, Wave Terrain, Spectral Painter, Gesten und Touch Modular. */
class TouchPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()

    private val box = RectF()
    private val bClose = RectF()
    private val tabs = Array(5) { RectF() }
    private val opts = Array(5) { RectF() }
    private val bRec = RectF()
    private val sliders = Array(2) { RectF() }
    private val surf = RectF()
    private val keys = Array(10) { RectF() }
    private val COLS = intArrayOf(Color.rgb(255, 120, 80), Color.rgb(120, 200, 255), Color.rgb(180, 255, 120), Color.rgb(255, 210, 90), Color.rgb(220, 150, 255))
    private val MCOL = intArrayOf(Color.rgb(255, 230, 80), Color.rgb(255, 150, 80), Color.rgb(140, 220, 255), Color.rgb(255, 120, 200), Color.rgb(90, 140, 255), Color.rgb(160, 255, 150), Color.rgb(255, 200, 130), Color.rgb(200, 200, 210), Color.rgb(130, 255, 220))

    fun open(mode: Int) { isOpen = true; TouchLab.mode = mode }
    fun close() { isOpen = false; TouchLab.allOff(); Modular.gate = false; fingers.clear() }

    private val mode get() = TouchLab.mode
    private fun hasKeys() = mode == TouchLab.MORPH || mode == TouchLab.MODULAR

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(5f); val bh = dp(38f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = bClose.bottom + g
        val tw = (w - 4 * g) / 5
        for (k in 0 until 5) tabs[k].set(box.left + pad + k * (tw + g), r1, box.left + pad + k * (tw + g) + tw, r1 + bh)
        val r2 = r1 + bh + g
        val ow = (w - 5 * g) / 6
        for (k in 0 until 5) opts[k].set(box.left + pad + k * (ow + g), r2, box.left + pad + k * (ow + g) + ow, r2 + bh)
        bRec.set(box.left + pad + 5 * (ow + g), r2, box.right - pad, r2 + bh)
        val r3 = r2 + bh + g
        val sw = (w - g) / 2
        for (k in 0 until 2) sliders[k].set(box.left + pad + k * (sw + g), r3, box.left + pad + k * (sw + g) + sw, r3 + dp(34f))
        val kh = if (hasKeys()) dp(70f) else 0f
        val kTop = box.bottom - pad - kh
        if (hasKeys()) {
            val kw = (w - 9 * dp(3f)) / 10
            for (k in 0 until 10) keys[k].set(box.left + pad + k * (kw + dp(3f)), kTop, box.left + pad + k * (kw + dp(3f)) + kw, kTop + kh)
        } else for (k in keys) k.setEmpty()
        surf.set(box.left + pad, sliders[0].bottom + g, box.right - pad, if (hasKeys()) kTop - g else box.bottom - pad)
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        if (r.isEmpty) return
        fill.shader = null
        fill.color = if (on) col else Color.rgb(26, 26, 34)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11f), r.height() * 0.38f)
        c.drawText(fitLabel(text, label, r.width() - dp(5f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    // ---- Tonleiter (für Tasten, Terrain, Gesten)
    private fun scaleIv(): IntArray = if (InstState.scaleType != 0 && InstState.scaleType != 1) intArrayOf(0, 2, 3, 5, 7, 8, 10) else if (InstState.scaleType == 1) intArrayOf(0, 2, 4, 5, 7, 9, 11) else intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private fun root(): Int = if (InstState.scaleType != 0) InstState.scaleRoot else if (GrooveGen.made) GrooveGen.root else 9
    private fun degNote(i: Int, base: Int): Double {
        val iv = scaleIv()
        val o = Math.floorDiv(i, iv.size)
        return (base + root() + 12 * o + iv[Math.floorMod(i, iv.size)]).toDouble()
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = COLS[mode]
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(12, 12, 18)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🖐 TOUCH-LAB · " + TouchLab.MODE_NAMES[mode], box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        val tl = arrayOf("MORPH", "TERRAIN", "SPEKTRUM", "GESTEN", "MODULAR")
        for (k in 0 until 5) btn(c, tabs[k], tl[k], mode == k, COLS[k])
        // Optionen je Modus
        val ol = Array(5) { "" }
        when (mode) {
            TouchLab.MORPH -> { ol[0] = "Ecken antippen = Klang"; }
            TouchLab.TERRAIN -> { ol[0] = TouchLab.TERRAINS[TouchLab.terrain]; ol[1] = "ORBIT ${(TouchLab.orbit * 100).toInt()}" }
            TouchLab.SPECTRAL -> { ol[0] = if (TouchLab.specPlay) "■ STOP" else "▶ PLAY"; ol[1] = "${TouchLab.specBars} TAKT" + if (TouchLab.specBars > 1) "E" else ""; ol[2] = if (TouchLab.specScale) "TONLEITER" else "FREI"; ol[3] = if (erase) "⌫ RADIER" else "✏ MALEN"; ol[4] = "🎲 / LEER" }
            TouchLab.GESTURE -> { ol[0] = "KLANG: " + TouchLab.G_SOUNDS[TouchLab.gSound] }
            TouchLab.MODULAR -> { ol[0] = if (Modular.drone) "DRONE AN" else "DRONE AUS"; ol[1] = "⟲ PATCH" }
        }
        for (k in 0 until 5) if (ol[k].isNotEmpty()) btn(c, opts[k], ol[k], (mode == TouchLab.SPECTRAL && k == 0 && TouchLab.specPlay) || (mode == TouchLab.MODULAR && k == 0 && Modular.drone), col)
        btn(c, bRec, "⏺ PAD", false, Color.rgb(255, 90, 90))
        val sl = arrayOf("SPACE (Delay)" to TouchLab.space, "LAUTSTÄRKE" to TouchLab.vol)
        for (k in 0 until 2) {
            val r = sliders[k]
            fill.color = Color.rgb(26, 26, 36); c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * sl[k].second, r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(sl[k].first, r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        fill.color = Color.rgb(6, 6, 10)
        c.drawRoundRect(surf, dp(12f), dp(12f), fill)
        c.save(); c.clipRect(surf)
        when (mode) {
            TouchLab.MORPH -> drawMorph(c)
            TouchLab.TERRAIN -> drawTerrain(c)
            TouchLab.SPECTRAL -> drawSpectral(c, col)
            TouchLab.GESTURE -> drawGesture(c, col)
            else -> drawModular(c)
        }
        c.restore()
        if (hasKeys()) {
            for (k in 0 until 10) {
                val r = keys[k]
                val on = fingers.values.any { it.key == k }
                fill.color = if (on) col else Color.rgb(32, 32, 42)
                c.drawRoundRect(r, dp(7f), dp(7f), fill)
                text.color = if (on) Color.BLACK else Color.WHITE; text.textSize = min(dp(12f), r.width() * 0.35f)
                c.drawText(InstState.scaleNoteNames[Math.floorMod(degNote(k, 36).toInt(), 12)], r.centerX(), r.centerY() + dp(4f), text)
            }
        }
        host.postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- MORPH
    private fun cornerRect(k: Int): RectF {
        val s = dp(84f); val h = dp(30f)
        return when (k) {
            0 -> RectF(surf.left + dp(6f), surf.top + dp(6f), surf.left + dp(6f) + s, surf.top + dp(6f) + h)
            1 -> RectF(surf.right - dp(6f) - s, surf.top + dp(6f), surf.right - dp(6f), surf.top + dp(6f) + h)
            2 -> RectF(surf.left + dp(6f), surf.bottom - dp(6f) - h, surf.left + dp(6f) + s, surf.bottom - dp(6f))
            else -> RectF(surf.right - dp(6f) - s, surf.bottom - dp(6f) - h, surf.right - dp(6f), surf.bottom - dp(6f))
        }
    }

    private fun drawMorph(c: Canvas) {
        val cx = intArrayOf(0, 1, 0, 1); val cy = intArrayOf(0, 0, 1, 1)
        for (k in 0 until 4) {
            val m = TouchLab.corners[k]
            val px = surf.left + cx[k] * surf.width(); val py = surf.top + cy[k] * surf.height()
            glow.maskFilter = BlurMaskFilter(dp(60f), BlurMaskFilter.Blur.NORMAL)
            glow.color = Color.argb((60 + 160 * TouchLab.w[k]).toInt().coerceAtMost(255), Color.red(MCOL[m]), Color.green(MCOL[m]), Color.blue(MCOL[m]))
            c.drawCircle(px, py, surf.width() * 0.35f, glow)
            glow.maskFilter = null
        }
        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(40, 255, 255, 255)
        for (k in 1..3) { c.drawLine(surf.left + surf.width() * k / 4, surf.top, surf.left + surf.width() * k / 4, surf.bottom, stroke); c.drawLine(surf.left, surf.top + surf.height() * k / 4, surf.right, surf.top + surf.height() * k / 4, stroke) }
        for (k in 0 until 4) {
            val m = TouchLab.corners[k]
            val r = cornerRect(k)
            fill.color = Color.argb(200, Color.red(MCOL[m]), Color.green(MCOL[m]), Color.blue(MCOL[m]))
            c.drawRoundRect(r, dp(8f), dp(8f), fill)
            text.color = Color.BLACK; text.textSize = dp(11f)
            c.drawText(TouchLab.MODELS[m] + " ▸", r.centerX(), r.centerY() + dp(4f), text)
            text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(9f)
            c.drawText("${(TouchLab.w[k] * 100).toInt()} %", r.centerX(), if (k < 2) r.bottom + dp(12f) else r.top - dp(5f), text)
        }
        val px = surf.left + TouchLab.mx * surf.width()
        val py = surf.top + (1 - TouchLab.my) * surf.height()
        glow.maskFilter = BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb((120 + 135 * TouchLab.meter.coerceIn(0f, 1f)).toInt(), 255, 255, 255)
        c.drawCircle(px, py, dp(22f), glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(7f), fill)
        text.color = Color.argb(160, 255, 255, 255); text.textSize = dp(10f)
        c.drawText("Finger hier = Klang & Charakter mischen · Tasten unten spielen", surf.centerX(), surf.centerY() + surf.height() * 0.42f - dp(30f), text)
    }

    // ---------------------------------------------------------------- TERRAIN
    private var terrBmp: Bitmap? = null
    private var terrKey = -1
    private fun drawTerrain(c: Canvas) {
        if (terrBmp == null || terrKey != TouchLab.terrain) {
            val n = 96
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            for (yy in 0 until n) for (xx in 0 until n) {
                val h = (TouchLab.terrainAt(TouchLab.terrain, xx / (n - 1.0), 1 - yy / (n - 1.0)) * 0.5 + 0.5).toFloat().coerceIn(0f, 1f)
                b.setPixel(xx, yy, Color.rgb((20 + 200 * h * h).toInt(), (30 + 180 * h).toInt(), (80 + 120 * (1 - h)).toInt()))
            }
            terrBmp = b; terrKey = TouchLab.terrain
        }
        c.drawBitmap(terrBmp!!, null, surf, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = surf.left + TouchLab.tx * surf.width()
        val py = surf.top + (1 - TouchLab.ty) * surf.height()
        val r = (0.05f + TouchLab.orbit * 0.45f)
        stroke.strokeWidth = dp(2f); stroke.color = Color.WHITE
        c.drawOval(RectF(px - r * surf.width(), py - r * 0.7f * surf.height() * 0.6f, px + r * surf.width(), py + r * 0.7f * surf.height() * 0.6f), stroke)
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(6f), fill)
        text.color = Color.WHITE; text.textSize = dp(10f)
        c.drawText("← → Klangfarbe · ↑ ↓ Tonhöhe · Farbe = Höhe (Intensität)", surf.centerX(), surf.bottom - dp(8f), text)
    }

    // ---------------------------------------------------------------- SPECTRAL
    private var erase = false
    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (!surf.contains(x, y)) return null
        val s = ((x - surf.left) / surf.width() * TouchLab.NS).toInt().coerceIn(0, TouchLab.NS - 1)
        val b = ((surf.bottom - y) / surf.height() * TouchLab.NB).toInt().coerceIn(0, TouchLab.NB - 1)
        return s to b
    }
    private fun drawSpectral(c: Canvas, col: Int) {
        val cw = surf.width() / TouchLab.NS; val chh = surf.height() / TouchLab.NB
        for (s in 0 until TouchLab.NS) for (b in 0 until TouchLab.NB) {
            val v = TouchLab.spec[s][b]
            if (v <= 0.01f) continue
            fill.color = Color.argb((60 + 195 * v).toInt(), (120 + 135 * b / TouchLab.NB), (200 - 120 * b / TouchLab.NB), 255)
            c.drawRect(surf.left + s * cw + 0.5f, surf.bottom - (b + 1) * chh + 0.5f, surf.left + (s + 1) * cw - 0.5f, surf.bottom - b * chh - 0.5f, fill)
        }
        stroke.strokeWidth = dp(0.6f); stroke.color = Color.argb(40, 255, 255, 255)
        for (s in 0..TouchLab.NS step 4) c.drawLine(surf.left + s * cw, surf.top, surf.left + s * cw, surf.bottom, stroke)
        val pc = TouchLab.specCol
        if (pc >= 0) { fill.color = Color.argb(70, 255, 255, 255); c.drawRect(surf.left + pc * cw, surf.top, surf.left + (pc + 1) * cw, surf.bottom, fill) }
        text.color = Color.argb(150, 255, 255, 255); text.textSize = dp(9f); text.textAlign = Paint.Align.LEFT
        c.drawText("↑ hoch (Obertöne)", surf.left + dp(4f), surf.top + dp(11f), text)
        c.drawText("↓ tief", surf.left + dp(4f), surf.bottom - dp(4f), text)
        text.textAlign = Paint.Align.CENTER
    }
    private fun paint(x: Float, y: Float) {
        val (s, b) = cellAt(x, y) ?: return
        for (db in -1..1) {
            val bb = b + db
            if (bb !in 0 until TouchLab.NB) continue
            TouchLab.spec[s][bb] = if (erase) 0f else max(TouchLab.spec[s][bb], if (db == 0) 1f else 0.45f)
        }
    }
    private fun specRandom() {
        for (r in TouchLab.spec) java.util.Arrays.fill(r, 0f)
        val rnd = java.util.Random()
        when (rnd.nextInt(3)) {
            0 -> for (s in 0 until TouchLab.NS) { val b = (s * 5 + 3) % 25 + 5; TouchLab.spec[s][b] = 1f; TouchLab.spec[s][(b + 5) % TouchLab.NB] = 0.5f }     // Arpeggio-Treppen
            1 -> for (s in 0 until TouchLab.NS) for (b in intArrayOf(5, 7, 10, 12, 15)) if (s % 8 < 6) TouchLab.spec[s][b] = 0.8f - 0.1f * (b - 5) / 2  // Akkord-Stabs
            else -> { var b = 15; for (s in 0 until TouchLab.NS) { b = (b + rnd.nextInt(5) - 2).coerceIn(3, TouchLab.NB - 4); if (rnd.nextFloat() < 0.75f) { TouchLab.spec[s][b] = 1f; TouchLab.spec[s][b + 3] = 0.4f } } }
        }
    }

    // ---------------------------------------------------------------- GESTEN
    private fun drawGesture(c: Canvas, col: Int) {
        val iv = scaleIv().size
        val n = iv * 2 + 1
        stroke.strokeWidth = dp(1f)
        for (k in 0..n) {
            val x = surf.left + surf.width() * k / n
            stroke.color = if (k % iv == 0) Color.argb(90, 255, 255, 255) else Color.argb(30, 255, 255, 255)
            c.drawLine(x, surf.top, x, surf.bottom, stroke)
        }
        for ((_, f) in fingers) {
            if (f.key >= 0) continue
            glow.maskFilter = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
            glow.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(f.x, f.y, dp(24f) + dp(20f) * TouchLab.gMod, glow)
            glow.maskFilter = null
            fill.color = Color.WHITE; c.drawCircle(f.x, f.y, dp(6f), fill)
        }
        text.color = Color.argb(170, 255, 255, 255); text.textSize = dp(10f)
        c.drawText("← → Ton · ↑ hell · kreisen = Vibrato · schnell hoch/runter = Bend · 2–4 Finger = Akkord", surf.centerX(), surf.bottom - dp(8f), text)
        text.textSize = dp(13f)
        if (TouchLab.gMod > 0.05f) c.drawText("〰 Modulation ${(TouchLab.gMod * 100).toInt()} %", surf.centerX(), surf.top + dp(20f), text)
    }

    // ---------------------------------------------------------------- MODULAR
    private fun modRect(m: Int): RectF {
        val w = surf.width() * 0.27f; val h = surf.height() * 0.13f
        val cx = surf.left + Modular.pos[m][0] * surf.width(); val cy = surf.top + Modular.pos[m][1] * surf.height()
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    private fun outPort(m: Int): Pair<Float, Float> { val r = modRect(m); return r.right to r.centerY() }
    private fun inPort(i: Int): Pair<Float, Float> {
        val inp = Modular.INPUTS[i]
        val r = modRect(inp.module)
        val list = Modular.INPUTS.filter { it.module == inp.module }
        val k = list.indexOf(inp)
        return r.left to (r.top + r.height() * (k + 1) / (list.size + 1))
    }
    private val sagV = HashMap<Int, Float>()
    private val sagX = HashMap<Int, Float>()

    private fun drawModular(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        // Kabel (hängen durch, schwingen nach)
        for (cab in Modular.cables) {
            val key = cab[0] * 100 + cab[1]
            val (x0, y0) = outPort(cab[0]); val (x1, y1) = inPort(cab[1])
            var v = sagV[key] ?: 0f; var s = sagX[key] ?: 0f
            v += (-s * 0.08f) ; v *= 0.92f; s += v
            sagV[key] = v; sagX[key] = s
            val sag = dp(40f) + abs(x1 - x0) * 0.15f + s + dp(3f) * sin(t * 2f + key)
            path.reset(); path.moveTo(x0, y0)
            path.cubicTo(x0 + dp(40f), y0 + sag, x1 - dp(40f), y1 + sag, x1, y1)
            val sig = Modular.flash[cab[0].coerceIn(0, 7)]
            stroke.strokeWidth = dp(3f); stroke.color = MCOL[cab[0] % MCOL.size]
            stroke.alpha = (150 + 100 * sig.coerceIn(0f, 1f)).toInt().coerceAtMost(255)
            c.drawPath(path, stroke)
        }
        cableDrag?.let { (m, xy) ->
            val (x0, y0) = outPort(m)
            stroke.strokeWidth = dp(3f); stroke.color = Color.WHITE
            path.reset(); path.moveTo(x0, y0); path.cubicTo(x0 + dp(40f), y0 + dp(30f), xy.first - dp(40f), xy.second + dp(30f), xy.first, xy.second)
            c.drawPath(path, stroke)
        }
        for (m in 0 until 8) {
            val r = modRect(m)
            fill.color = Color.rgb(34, 34, 46)
            c.drawRoundRect(r, dp(8f), dp(8f), fill)
            stroke.strokeWidth = dp(1.2f); stroke.color = MCOL[m % MCOL.size]
            c.drawRoundRect(r, dp(8f), dp(8f), stroke)
            text.color = Color.WHITE; text.textSize = min(dp(10.5f), r.height() * 0.28f)
            c.drawText(Modular.NAMES[m], r.centerX(), r.top + r.height() * 0.36f, text)
            // Regler-Balken (unten)
            val kb = RectF(r.left + dp(10f), r.bottom - r.height() * 0.32f, r.right - dp(10f), r.bottom - r.height() * 0.14f)
            fill.color = Color.rgb(20, 20, 28); c.drawRoundRect(kb, dp(3f), dp(3f), fill)
            fill.color = MCOL[m % MCOL.size]; c.drawRoundRect(RectF(kb.left, kb.top, kb.left + kb.width() * Modular.knob[m], kb.bottom), dp(3f), dp(3f), fill)
            text.textSize = dp(7.5f); text.color = Color.argb(170, 255, 255, 255)
            c.drawText(fitLabel(text, Modular.KNOB[m], r.width() - dp(8f), dp(7.5f), dp(5.5f)), r.centerX(), r.top + r.height() * 0.6f, text)
            if (Modular.hasOut(m)) { val (ox, oy) = outPort(m); fill.color = Color.WHITE; c.drawCircle(ox, oy, dp(6f), fill) }
        }
        for (i in Modular.INPUTS.indices) {
            val (ix, iy) = inPort(i)
            fill.color = if (Modular.cables.any { it[1] == i }) Color.rgb(255, 210, 90) else Color.rgb(90, 90, 110)
            c.drawCircle(ix, iy, dp(5.5f), fill)
            text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(7f); text.textAlign = Paint.Align.RIGHT
            c.drawText(Modular.INPUTS[i].name, ix - dp(7f), iy + dp(2.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        text.color = Color.argb(150, 255, 255, 255); text.textSize = dp(9.5f)
        c.drawText("● Ausgang zu ● Eingang ziehen = Kabel · Eingang antippen = abziehen · Modulname ziehen = verschieben", surf.centerX(), surf.bottom - dp(6f), text)
    }

    // ---------------------------------------------------------------- Touch
    private class Finger(var x: Float, var y: Float, var key: Int = -1, var tag: Int = 0, var vx: Float = 0f, var vy: Float = 0f, var ang: Float = 0f, var t: Long = 0L)
    private val fingers = HashMap<Int, Finger>()
    private var drag = -1
    private var lastX = 0f
    private var cableDrag: Pair<Int, Pair<Float, Float>>? = null
    private var modDrag = -1
    private var knobDrag = -1
    private var gestureMain = -1
    private var bendOff = 0.0
    private var lastTerrNote = -1

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(id, x, y, e.getSize(i), e.actionMasked == MotionEvent.ACTION_DOWN)
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) move(e.getPointerId(p), e.getX(p), e.getY(p))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> up(id, x, y)
        }
    }

    private fun down(id: Int, x: Float, y: Float, size: Float, first: Boolean) {
        // Tasten
        val k = keys.indexOfFirst { !it.isEmpty && it.contains(x, y) }
        if (k >= 0) {
            val tag = 3000 + id
            fingers[id] = Finger(x, y, k, tag)
            if (mode == TouchLab.MODULAR) { Modular.note = degNote(k, 36); Modular.gate = true }
            else TouchLab.noteOn(tag, degNote(k, 48), 0.9f)
            return
        }
        for (s in 0 until 2) if (sliders[s].contains(x, y)) { drag = s; lastX = x; return }
        if (surf.contains(x, y)) { surfDown(id, x, y, size); return }
        if (!first) return
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            tabs.any { it.contains(x, y) } -> {
                val t = tabs.indexOfFirst { it.contains(x, y) }
                TouchLab.allOff(); Modular.gate = false
                TouchLab.mode = t; haptic()
                onHint?.invoke(when (t) {
                    TouchLab.MORPH -> "Morph-Matrix: Ecken antippen = Klang wählen · Finger in der Fläche mischt Lautstärke UND Charakter · Tasten spielen"
                    TouchLab.TERRAIN -> "Wave Terrain: Finger setzen = Ton · links/rechts = Klangfarbe · hoch/runter = Tonhöhe"
                    TouchLab.SPECTRAL -> "Spectral Painter: ins Spektrum malen, ▶ PLAY läuft im Takt"
                    TouchLab.GESTURE -> "Gesten: Finger = Ton, kreisen = Vibrato, schnell hoch/runter = Bend, mehrere Finger = Akkord"
                    else -> "Touch Modular: Kabel ziehen · Regler unten im Modul · Tasten oder DRONE"
                })
            }
            bRec.contains(x, y) -> {
                haptic()
                val p = Engine.recordInstLoop(512, 2)
                onHint?.invoke(if (p < 0) "Kein freies Pad (1–12) – erst eins leeren" else "Ab der nächsten Eins: 2 Takte TOUCH-LAB → Pad ${p + 1} · jetzt spielen!")
            }
            else -> { val o = opts.indexOfFirst { it.contains(x, y) }; if (o >= 0) option(o) }
        }
    }

    private fun option(o: Int) {
        haptic()
        when (mode) {
            TouchLab.TERRAIN -> when (o) {
                0 -> { TouchLab.terrain = (TouchLab.terrain + 1) % TouchLab.TERRAINS.size; onHint?.invoke("Landschaft: ${TouchLab.TERRAINS[TouchLab.terrain]}") }
                1 -> { TouchLab.orbit = if (TouchLab.orbit > 0.85f) 0.1f else TouchLab.orbit + 0.15f; onHint?.invoke("Orbit ${(TouchLab.orbit * 100).toInt()} % – größer = obertonreicher") }
            }
            TouchLab.SPECTRAL -> when (o) {
                0 -> { TouchLab.specPlay = !TouchLab.specPlay; if (TouchLab.specPlay && !Engine.seqPlaying) Engine.transportPlay(false) }
                1 -> TouchLab.specBars = if (TouchLab.specBars == 1) 2 else if (TouchLab.specBars == 2) 4 else 1
                2 -> { TouchLab.specScale = !TouchLab.specScale; onHint?.invoke(if (TouchLab.specScale) "Bänder auf der Tonleiter (immer harmonisch)" else "Bänder frei verteilt (Klangfarben, Geräusche)") }
                3 -> erase = !erase
                4 -> { if (TouchLab.spec.any { r -> r.any { it > 0f } } && lastRnd) { for (r in TouchLab.spec) java.util.Arrays.fill(r, 0f); lastRnd = false; onHint?.invoke("Leer") } else { specRandom(); lastRnd = true; onHint?.invoke("🎲 Neues Bild – nochmal tippen = leeren") } }
            }
            TouchLab.GESTURE -> if (o == 0) TouchLab.gSound = (TouchLab.gSound + 1) % TouchLab.G_SOUNDS.size
            TouchLab.MODULAR -> when (o) {
                0 -> Modular.drone = !Modular.drone
                1 -> { Modular.reset(); onHint?.invoke("Grund-Patch: OSC 1 → FILTER → DRIVE → AUSGANG, LFO → Cutoff") }
            }
        }
    }
    private var lastRnd = false

    private fun surfDown(id: Int, x: Float, y: Float, size: Float) {
        val fx = ((x - surf.left) / surf.width()).coerceIn(0f, 1f)
        val fy = ((surf.bottom - y) / surf.height()).coerceIn(0f, 1f)
        when (mode) {
            TouchLab.MORPH -> {
                for (k in 0 until 4) if (cornerRect(k).contains(x, y)) {
                    TouchLab.corners[k] = (TouchLab.corners[k] + 1) % TouchLab.MODELS.size
                    haptic(); onHint?.invoke("Ecke: ${TouchLab.MODELS[TouchLab.corners[k]]}")
                    return
                }
                fingers[id] = Finger(x, y)
                TouchLab.mx = fx; TouchLab.my = fy
            }
            TouchLab.TERRAIN -> {
                val tag = 3100 + id
                fingers[id] = Finger(x, y, -1, tag)
                TouchLab.tx = fx; TouchLab.ty = fy
                lastTerrNote = (fy * 14).toInt()
                TouchLab.noteOn(tag, degNote(lastTerrNote, 36), 0.9f)
            }
            TouchLab.SPECTRAL -> { fingers[id] = Finger(x, y); paint(x, y) }
            TouchLab.GESTURE -> {
                val tag = 3200 + id
                val f = Finger(x, y, -1, tag, t = SystemClock.uptimeMillis())
                fingers[id] = f
                val vel = (0.5f + size * 2.5f).coerceIn(0.4f, 1f)
                if (gestureMain < 0) {
                    gestureMain = id
                    TouchLab.gBright = fy
                    TouchLab.noteOn(tag, gestNote(fx), vel)
                } else {
                    // weitere Finger: Akkordtöne zum Hauptton (Terz, Quinte, Oktave)
                    val n = fingers.size - 1
                    val main = fingers[gestureMain]
                    val base = gestNote(((main?.x ?: x) - surf.left) / surf.width())
                    val add = when (n) { 1 -> if (scaleIv()[2] == 3) 3 else 4; 2 -> 7; else -> 12 }
                    TouchLab.noteOn(tag, base + add, vel * 0.85f)
                }
            }
            else -> modularDown(id, x, y)
        }
    }

    private fun gestNote(fx: Float): Double {
        val iv = scaleIv().size
        val i = (fx * (iv * 2 + 1)).toInt().coerceIn(0, iv * 2)
        return degNote(i, 48) + bendOff
    }

    private fun modularDown(id: Int, x: Float, y: Float) {
        // Ausgang?
        for (m in 0 until 7) { val (ox, oy) = outPort(m); if (hypot(ox - x, oy - y) < dp(22f)) { cableDrag = m to (x to y); fingers[id] = Finger(x, y); return } }
        // Eingang antippen = abziehen
        for (i in Modular.INPUTS.indices) { val (ix, iy) = inPort(i); if (hypot(ix - x, iy - y) < dp(16f)) { Modular.unplug(i); haptic(); return } }
        for (m in 0 until 8) {
            val r = modRect(m)
            if (r.contains(x, y)) {
                fingers[id] = Finger(x, y)
                if (y > r.top + r.height() * 0.5f) { knobDrag = m; lastX = x } else modDrag = m
                return
            }
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (drag in 0..1 && fingers[id] == null) {
            val dv = (x - lastX) / sliders[drag].width(); lastX = x
            if (drag == 0) TouchLab.space = (TouchLab.space + dv).coerceIn(0f, 1f) else TouchLab.vol = (TouchLab.vol + dv).coerceIn(0f, 1f)
            return
        }
        val f = fingers[id] ?: return
        val dx = x - f.x; val dy = y - f.y
        val fx = ((x - surf.left) / surf.width()).coerceIn(0f, 1f)
        val fy = ((surf.bottom - y) / surf.height()).coerceIn(0f, 1f)
        if (f.key >= 0) {
            // Tasten: Wischen = Slide
            val k = keys.indexOfFirst { !it.isEmpty && it.contains(x, y) }
            if (k >= 0 && k != f.key) {
                f.key = k
                if (mode == TouchLab.MODULAR) Modular.note = degNote(k, 36) else TouchLab.bend(f.tag, degNote(k, 48))
            }
            f.x = x; f.y = y
            return
        }
        when (mode) {
            TouchLab.MORPH -> { TouchLab.mx = fx; TouchLab.my = fy }
            TouchLab.TERRAIN -> {
                TouchLab.tx = fx; TouchLab.ty = fy
                val nn = (fy * 14).toInt()
                if (nn != lastTerrNote) { lastTerrNote = nn; TouchLab.bend(f.tag, degNote(nn, 36)) }
            }
            TouchLab.SPECTRAL -> paint(x, y)
            TouchLab.GESTURE -> {
                val now = SystemClock.uptimeMillis()
                val dt = (now - f.t).coerceAtLeast(1)
                // Kreisen: Richtungsänderung der Bewegung → Modulation
                if (hypot(dx, dy) > dp(2f)) {
                    val a = atan2(dy, dx)
                    var da = a - f.ang
                    while (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    while (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    f.ang = a
                    TouchLab.gMod = (TouchLab.gMod * 0.92f + min(1f, abs(da) * 0.6f) * 0.08f * 3f).coerceIn(0f, 1f)
                    // schnelles Wischen hoch/runter = Bend
                    val vy = dy / dt
                    if (abs(vy) > dp(2.2f)) bendOff = (if (vy < 0) 2.0 else -2.0)
                }
                f.t = now
                if (id == gestureMain) {
                    TouchLab.gBright = fy
                    TouchLab.bend(f.tag, gestNote(fx))
                    // Akkordtöne folgen dem Hauptfinger
                    var n = 0
                    for ((pid, g) in fingers) if (pid != id && g.key < 0) { n++; val add = when (n) { 1 -> if (scaleIv()[2] == 3) 3 else 4; 2 -> 7; else -> 12 }; TouchLab.bend(g.tag, gestNote(fx) + add) }
                }
                bendOff *= 0.85
            }
            else -> {
                cableDrag?.let { cableDrag = it.first to (x to y) }
                if (modDrag >= 0) {
                    Modular.pos[modDrag][0] = ((x - surf.left) / surf.width()).coerceIn(0.14f, 0.86f)
                    Modular.pos[modDrag][1] = ((y - surf.top) / surf.height()).coerceIn(0.08f, 0.9f)
                    // Kabel des Moduls schwingen
                    for (cab in Modular.cables) if (cab[0] == modDrag || Modular.INPUTS[cab[1]].module == modDrag) { val key = cab[0] * 100 + cab[1]; sagV[key] = (sagV[key] ?: 0f) + dy * 0.3f }
                }
                if (knobDrag >= 0) { val r = modRect(knobDrag); Modular.knob[knobDrag] = (Modular.knob[knobDrag] + (x - lastX) / r.width()).coerceIn(0f, 1f); lastX = x }
            }
        }
        f.x = x; f.y = y
    }

    private fun up(id: Int, x: Float, y: Float) {
        drag = -1
        val f = fingers.remove(id)
        if (f != null) {
            if (f.key >= 0) { if (mode == TouchLab.MODULAR) { if (fingers.values.none { it.key >= 0 }) Modular.gate = false } else TouchLab.noteOff(f.tag) }
            else when (mode) {
                TouchLab.TERRAIN, TouchLab.GESTURE -> {
                    TouchLab.noteOff(f.tag)
                    if (id == gestureMain) { for ((_, g) in fingers) if (g.key < 0) TouchLab.noteOff(g.tag); fingers.entries.removeAll { it.value.key < 0 }; gestureMain = -1; TouchLab.gMod = 0f }
                }
                TouchLab.MODULAR -> {
                    cableDrag?.let { (m, _) ->
                        val hit = Modular.INPUTS.indices.firstOrNull { val (ix, iy) = inPort(it); hypot(ix - x, iy - y) < dp(26f) }
                        if (hit != null && Modular.INPUTS[hit].module != m) { Modular.connect(m, hit); haptic(); onHint?.invoke("🔌 ${Modular.NAMES[m]} → ${Modular.NAMES[Modular.INPUTS[hit].module]} ${Modular.INPUTS[hit].name}") }
                    }
                    cableDrag = null; modDrag = -1; knobDrag = -1
                }
            }
        }
    }
}
