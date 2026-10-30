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
import kotlin.math.max
import kotlin.math.min

/** Bedienfeld für den [Harmonizer]: XY (Formant / Tuning), Akkord-Pads in der Tonart, Tastatur, wenige Schalter. */
class HarmonizerPanel(private val host: View) {
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
    private val bClose = RectF(); private val bOn = RectF(); private val bSrc = RectF(); private val bLead = RectF()
    private val bMute = RectF(); private val bOct = RectF(); private val bLatch = RectF()
    private val sliders = Array(3) { RectF() }
    private val SL = arrayOf("LAUTSTÄRKE", "HALL", "BREITE / CHOR")
    private val xy = RectF()
    private val chords = Array(7) { RectF() }
    private val keys = Array(13) { RectF() }
    private var latch = false
    private var latched: List<Int> = emptyList()
    private var latchedIdx = -1

    /** Finger → gespielte Noten */
    private val fingers = HashMap<Int, List<Int>>()
    private var xyPtr = -1
    private var slPtr = -1; private var slIdx = -1; private var slLast = 0f

    fun open() { isOpen = true }
    fun close() { isOpen = false; for (n in fingers.values.flatten()) Harmonizer.noteOff(n); fingers.clear() }

    private val col = Color.rgb(255, 120, 190)
    private val col2 = Color.rgb(120, 200, 255)

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f)
        val bh = dp(32f)
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = box.top + pad + bh + dp(20f)
        val bs = arrayOf(bOn, bSrc, bLead, bMute, bOct, bLatch)
        val gw = (box.width() - 2 * pad - 5 * dp(5f)) / 6
        for (i in 0 until 6) bs[i].set(box.left + pad + i * (gw + dp(5f)), r1, box.left + pad + i * (gw + dp(5f)) + gw, r1 + bh)
        val r2 = r1 + bh + dp(6f)
        val sw = (box.width() - 2 * pad - 2 * dp(6f)) / 3
        for (i in 0 until 3) sliders[i].set(box.left + pad + i * (sw + dp(6f)), r2, box.left + pad + i * (sw + dp(6f)) + sw, r2 + dp(30f))
        val kH = min(dp(84f), box.height() * 0.16f)
        val cH = min(dp(58f), box.height() * 0.11f)
        val kTop = box.bottom - pad - kH
        val cTop = kTop - dp(8f) - cH
        val cw = (box.width() - 2 * pad - 6 * dp(5f)) / 7
        for (i in 0 until 7) chords[i].set(box.left + pad + i * (cw + dp(5f)), cTop, box.left + pad + i * (cw + dp(5f)) + cw, cTop + cH)
        // Tastatur C4..C5: weiße Tasten als Raster, schwarze darüber
        val white = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        val ww = (box.width() - 2 * pad) / white.size
        for ((wi, s) in white.withIndex()) keys[s].set(box.left + pad + wi * ww, kTop, box.left + pad + (wi + 1) * ww - dp(2f), box.bottom - pad)
        val black = intArrayOf(1, 3, 6, 8, 10)
        val after = intArrayOf(0, 1, 3, 4, 5)
        for ((bi, s) in black.withIndex()) {
            val cx = box.left + pad + (after[bi] + 1) * ww
            keys[s].set(cx - ww * 0.32f, kTop, cx + ww * 0.32f, kTop + kH * 0.58f)
        }
        xy.set(box.left + pad, r2 + dp(38f), box.right - pad, cTop - dp(18f))
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, cc: Int) {
        fill.shader = null
        fill.color = if (on) cc else Color.rgb(32, 30, 40)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(cc), Color.green(cc), Color.blue(cc))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(5f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun scale(key: Int): IntArray {
        val r = Keys.root(key)
        val iv = if (Keys.isMinor(key)) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return IntArray(7) { r + iv[it] }
    }

    /** Dreiklang auf Stufe [deg] der Tonart (MIDI-Noten um C4). */
    private fun triad(deg: Int): List<Int> {
        val sc = scale(Harmonizer.curKey)
        fun at(i: Int) = 60 + sc[i % 7] + 12 * (i / 7) - (if (sc[0] > 6) 12 else 0)
        return listOf(at(deg), at(deg + 2), at(deg + 4))
    }

    private val NN = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private fun chordName(deg: Int): String {
        val t = triad(deg)
        val third = t[1] - t[0]
        val fifth = t[2] - t[0]
        return NN[Math.floorMod(t[0], 12)] + when { third == 3 && fifth == 6 -> "°"; third == 3 -> "m"; else -> "" }
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(16, 12, 20)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(15f); text.color = Color.WHITE
        c.drawText("🎤 VOCAL HARMONIZER", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        val src = when (Harmonizer.source) { 3 -> "Mikrofon"; else -> "Gesang Deck " + "AB"[max(0, Harmonizer.srcDeck)] }
        val hz = Harmonizer.pitchHz
        val sing = if (Harmonizer.on && hz > 0f) {
            val m = Math.round(69 + 12 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0)).toInt()
            "singt " + NN[Math.floorMod(m, 12)] + (m / 12 - 1)
        } else if (Harmonizer.on) "wartet auf Gesang …" else "aus"
        val tg = Harmonizer.shownTargets.filter { it >= 0 }.joinToString(" ") { NN[Math.floorMod(it, 12)] + (it / 12 - 1) }
        val held = Harmonizer.heldNotes()
        val st = "$src · Tonart ${Keys.name(Harmonizer.curKey)} · $sing" + (if (tg.isNotEmpty()) " → $tg" else "") + if (held.isEmpty() && Harmonizer.on) " · ohne Noten: Terz + Quinte automatisch" else ""
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Harmonizer.on) "■ AUS" else "▶ AN", Harmonizer.on, Color.rgb(140, 230, 170))
        btn(c, bSrc, when (Harmonizer.source) { 0 -> "DECK A"; 1 -> "DECK B"; 2 -> "DECK AUTO"; else -> "🎙 MIKRO" }, false, col2)
        btn(c, bLead, "LEAD-TUNE", Harmonizer.leadTune, col)
        btn(c, bMute, "ORIGINAL " + if (Harmonizer.muteOrig) "STUMM" else "AN", Harmonizer.muteOrig, col)
        btn(c, bOct, "OKTAVE " + when (Harmonizer.octave) { -1 -> "−1"; 1 -> "+1"; else -> "0" }, Harmonizer.octave != 0, col2)
        btn(c, bLatch, "HALTEN", latch, col2)
        val sv = floatArrayOf(Harmonizer.level, Harmonizer.space, Harmonizer.width)
        for (i in 0 until 3) {
            val r = sliders[i]
            fill.color = Color.rgb(30, 26, 38); c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * sv[i], r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(9f)
            c.drawText(fitLabel(text, "${SL[i]} ${(sv[i] * 100).toInt()} %", r.width() - dp(6f), dp(9f), dp(6.5f)), r.centerX(), r.centerY() + dp(3f), text)
        }
        drawXy(c)
        for (i in 0 until 7) {
            val on = latchedIdx == i || fingers.values.any { it == triad(i) }
            btn(c, chords[i], chordName(i), on, col)
            text.textSize = dp(7.5f); text.color = if (on) Color.BLACK else Color.argb(150, 255, 255, 255)
            c.drawText(arrayOf("I", "II", "III", "IV", "V", "VI", "VII")[i], chords[i].centerX(), chords[i].bottom - dp(4f), text)
        }
        val heldSet = held.toSet()
        for (pass in 0..1) for (s in 0..12) {
            val black = s % 12 in intArrayOf(1, 3, 6, 8, 10)
            if ((pass == 0) == black) continue
            val r = keys[s]
            val on = (60 + s) in heldSet || heldSet.any { Math.floorMod(it - 60 - s, 12) == 0 }
            fill.color = when { on -> col; black -> Color.rgb(20, 20, 24); else -> Color.rgb(225, 225, 232) }
            c.drawRoundRect(r, dp(4f), dp(4f), fill)
            if (!black && s % 12 == 0) {
                text.color = Color.rgb(60, 60, 70); text.textSize = dp(8f)
                c.drawText("C" + (4 + s / 12), r.centerX(), r.bottom - dp(5f), text)
            }
        }
        host.postInvalidateOnAnimation()
    }

    private fun drawXy(c: Canvas) {
        val r = xy
        fill.shader = LinearGradient(r.left, r.bottom, r.right, r.top, Color.rgb(30, 14, 40), Color.rgb(14, 30, 48), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        fill.shader = null
        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(60, 255, 255, 255)
        c.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, stroke)
        val yr = r.bottom - r.height() * 0.85f
        stroke.color = Color.argb(90, 255, 120, 190)
        c.drawLine(r.left, yr, r.right, yr, stroke)
        val px = r.left + Harmonizer.x * r.width()
        val py = r.bottom - Harmonizer.y * r.height()
        val m = Harmonizer.meter.coerceIn(0f, 1f)
        glow.maskFilter = BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb((60 + 160 * m).toInt().coerceAtMost(230), 255, 140, 200)
        c.drawCircle(px, py, dp(16f) + dp(20f) * m, glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(5f), fill)
        text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(9f)
        text.textAlign = Paint.Align.LEFT
        c.drawText("← TIEF / MÄNNLICH", r.left + dp(6f), r.bottom + dp(11f), text)
        c.drawText("↑ ROBOTER", r.left + dp(6f), r.top + dp(12f), text)
        c.drawText("HART GETUNT", r.left + dp(6f), yr + dp(12f), text)
        c.drawText("↓ NATÜRLICH", r.left + dp(6f), r.bottom - dp(6f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("HELL / HYPERPOP →  (Formant)", r.right - dp(6f), r.bottom + dp(11f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun press(id: Int, notes: List<Int>) {
        fingers[id] = notes
        for (n in notes) Harmonizer.noteOn(n)
        haptic()
    }

    private fun release(id: Int) {
        val ns = fingers.remove(id) ?: return
        val still = fingers.values.flatten().toSet() + (if (latch) latched.toSet() else emptySet())
        for (n in ns) if (n !in still) Harmonizer.noteOff(n)
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (xy.contains(x, y)) { xyPtr = id; setXy(x, y); return }
                for (k in 0 until 3) if (sliders[k].contains(x, y)) { slPtr = id; slIdx = k; slLast = x; return }
                for (k in 0 until 7) if (chords[k].contains(x, y)) {
                    val t = triad(k)
                    if (latch) {
                        for (n in latched) Harmonizer.noteOff(n)
                        if (latchedIdx == k) { latched = emptyList(); latchedIdx = -1 }
                        else { latched = t; latchedIdx = k; for (n in t) Harmonizer.noteOn(n) }
                        haptic()
                    } else press(id, t)
                    if (!Harmonizer.on) onHint?.invoke("Erst ▶ AN tippen – dann singt der Chor diese Töne")
                    return
                }
                // schwarze Tasten zuerst (liegen oben)
                for (pass in 0..1) for (s in 0..12) {
                    val black = s % 12 in intArrayOf(1, 3, 6, 8, 10)
                    if ((pass == 0) != black) continue
                    if (keys[s].contains(x, y)) { press(id, listOf(60 + s)); return }
                }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val pid = e.getPointerId(p)
                    val px = e.getX(p); val py = e.getY(p)
                    if (pid == xyPtr) setXy(px, py)
                    else if (pid == slPtr && slIdx >= 0) {
                        val dv = (px - slLast) / sliders[slIdx].width()
                        slLast = px
                        when (slIdx) {
                            0 -> Harmonizer.level = (Harmonizer.level + dv).coerceIn(0f, 1f)
                            1 -> Harmonizer.space = (Harmonizer.space + dv).coerceIn(0f, 1f)
                            else -> Harmonizer.width = (Harmonizer.width + dv).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (id == xyPtr) xyPtr = -1
                if (id == slPtr) { slPtr = -1; slIdx = -1 }
                release(id)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) for (k in fingers.keys.toList()) release(k)
            }
        }
    }

    private fun setXy(x: Float, y: Float) {
        Harmonizer.x = ((x - xy.left) / xy.width()).coerceIn(0f, 1f)
        Harmonizer.y = ((xy.bottom - y) / xy.height()).coerceIn(0f, 1f)
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                Harmonizer.on = !Harmonizer.on; haptic()
                onHint?.invoke(if (Harmonizer.on) (if (Harmonizer.source == 3) "Harmonizer an – Mikrofon (Kopfhörer benutzen, sonst pfeift es)" else "Harmonizer an – singt mit dem Gesang des Decks · Akkord-Pads oder Tastatur halten") else "Harmonizer aus")
            }
            bSrc.contains(x, y) -> {
                Harmonizer.source = (Harmonizer.source + 1) % 4; haptic()
                onHint?.invoke(when (Harmonizer.source) {
                    3 -> "Quelle: Mikrofon – bitte Kopfhörer"
                    2 -> "Quelle: Gesang des lauteren laufenden Decks"
                    else -> "Quelle: Gesang von Deck " + "AB"[Harmonizer.source] + " (am besten mit KI-Stems)"
                })
            }
            bLead.contains(x, y) -> { Harmonizer.leadTune = !Harmonizer.leadTune; haptic(); onHint?.invoke(if (Harmonizer.leadTune) "Hauptstimme läuft getunt mit (Formant wie eingestellt) – tipp ORIGINAL STUMM für reinen Effekt-Gesang" else "Lead-Tune aus") }
            bMute.contains(x, y) -> { Harmonizer.muteOrig = !Harmonizer.muteOrig; haptic(); onHint?.invoke(if (Harmonizer.muteOrig) "Original-Gesang im Deck stumm, solange der Harmonizer läuft" else "Original-Gesang hörbar") }
            bOct.contains(x, y) -> { Harmonizer.octave = when (Harmonizer.octave) { 0 -> 1; 1 -> -1; else -> 0 }; haptic() }
            bLatch.contains(x, y) -> {
                latch = !latch; haptic()
                if (!latch) { for (n in latched) Harmonizer.noteOff(n); latched = emptyList(); latchedIdx = -1 }
                onHint?.invoke(if (latch) "HALTEN: Akkord-Pad antippen hält den Akkord, nochmal tippen löst" else "Akkorde klingen nur, solange du drückst")
            }
        }
    }
}
