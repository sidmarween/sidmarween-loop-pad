package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Bedienfeld für [Euclid]: vier Ringe (Muster live), drei große Regler, Spuren an/aus. */
class EuclidPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bPoly = RectF(); private val bNew = RectF()
    private val bPreset = RectF(); private val bKit = RectF()
    private val bRec = RectF(); private val bRecBars = RectF(); private val bSmp = RectF()
    /** Eigene Samples je Spur wählen (MainActivity). */
    var onSamples: (() -> Unit)? = null
    private var recBars = 2
    private var recPad = -1
    private val rings = Array(4) { RectF() }
    private val sliders = Array(4) { RectF() }
    private val SL = arrayOf("SCHLÄGE PRO TAKT", "TAKTLÄNGE / KOMPLEXITÄT", "MORPHING", "LAUTSTÄRKE")
    private val COLS = intArrayOf(Color.rgb(255, 150, 80), Color.rgb(120, 220, 255), Color.rgb(200, 140, 255), Color.rgb(130, 240, 160))
    private var slPtr = -1; private var slIdx = -1; private var slLast = 0f; private var slMoved = false

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f)
        val bh = dp(34f)
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = box.top + pad + bh + dp(20f)
        val gw = (box.width() - 2 * pad - 2 * dp(6f)) / 3
        val bs = arrayOf(bOn, bPoly, bNew)
        for (i in 0 until 3) bs[i].set(box.left + pad + i * (gw + dp(6f)), r1, box.left + pad + i * (gw + dp(6f)) + gw, r1 + bh)
        val r2 = r1 + bh + dp(6f)
        val w2 = box.width() - 2 * pad
        bPreset.set(box.left + pad, r2, box.left + pad + w2 * 0.58f, r2 + bh)
        bKit.set(bPreset.right + dp(6f), r2, box.right - pad, r2 + bh)
        val sh = dp(42f)
        val sTop = box.bottom - pad - 4 * sh - 3 * dp(6f)
        for (i in 0 until 4) sliders[i].set(box.left + pad, sTop + i * (sh + dp(6f)), box.right - pad, sTop + i * (sh + dp(6f)) + sh)
        val r3 = r2 + bh + dp(6f)
        bRec.set(box.left + pad, r3, box.left + pad + w2 * 0.5f, r3 + bh)
        bRecBars.set(bRec.right + dp(6f), r3, bRec.right + dp(6f) + w2 * 0.18f, r3 + bh)
        bSmp.set(bRecBars.right + dp(6f), r3, box.right - pad, r3 + bh)
        val top = r3 + bh + dp(8f)
        val h = sTop - dp(10f) - top
        val cell = min((box.width() - 2 * pad) / 2, h / 2)
        val ox = box.centerX() - cell
        for (i in 0 until 4) {
            val c = i % 2; val r = i / 2
            rings[i].set(ox + c * cell + dp(4f), top + r * cell + dp(4f), ox + (c + 1) * cell - dp(4f), top + (r + 1) * cell - dp(4f))
        }
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, cc: Int) {
        fill.color = if (on) cc else Color.rgb(30, 32, 40)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(cc), Color.green(cc), Color.blue(cc))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(12f), r.height() * 0.42f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(7f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun get(i: Int) = when (i) { 0 -> Euclid.density; 1 -> Euclid.complexity; 2 -> Euclid.morph; else -> Euclid.level }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(14, 15, 18)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = COLS[0]
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(15f); text.color = Color.WHITE
        c.drawText("⬡ EUKLID · Polyrhythmus", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val st = String.format(java.util.Locale.GERMANY, "%s · %.1f BPM · Tonart %s · ", if (Euclid.on) "läuft" else "aus", Engine.currentBpm().let { if (it > 0f) it else 120f }, Keys.name(Euclid.curKey)) +
            (0..3).joinToString("  ") { "${Euclid.NAMES[it]} ${Euclid.laneK[it]}/${Euclid.laneN[it]}" }
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(14f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Euclid.on) "■ STOPP" else "▶ START", Euclid.on, Color.rgb(140, 230, 170))
        btn(c, bPoly, if (Euclid.poly) "POLYRHYTHMUS (gestreckt)" else "POLYMETER (1/16)", Euclid.poly, COLS[2])
        btn(c, bNew, "🎲 NEU WÜRFELN", false, COLS[1])
        val armed = recPad >= 0 && Engine.isRecording(recPad)
        if (recPad >= 0 && !armed && Engine.hasLoop(recPad)) recPad = -1
        btn(c, bRec, if (armed) (if (Engine.isArmed(recPad)) "⏺ wartet auf die Eins … (Pad ${recPad + 1})" else "⏺ nimmt auf in Pad ${recPad + 1} …") else "⏺ ALS LOOP AUFNEHMEN (nur EUKLID)", armed, Color.rgb(255, 90, 90))
        btn(c, bRecBars, "$recBars TAKT" + if (recBars > 1) "E" else "", false, Color.rgb(255, 90, 90))
        val nS = (0..3).count { UserSamples.has("euk:$it") }
        btn(c, bSmp, if (nS > 0) "📂 SAMPLES ($nS)" else "📂 EIGENE SAMPLES", nS > 0, COLS[3])
        btn(c, bPreset, "◀  " + (if (Euclid.presetIdx >= 0) Euclid.PRESETS[Euclid.presetIdx].name else "VORLAGE WÄHLEN") + "  ▶", Euclid.presetIdx >= 0, COLS[3])
        btn(c, bKit, "KLANG: " + Euclid.KITS[Euclid.kit].name, false, COLS[0])
        for (l in 0 until 4) drawRing(c, l)
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(28, 30, 36); c.drawRoundRect(r, dp(8f), dp(8f), fill)
            fill.color = Color.argb(160, Color.red(COLS[i]), Color.green(COLS[i]), Color.blue(COLS[i]))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(8f), dp(8f), fill)
            text.color = Color.WHITE; text.textSize = dp(11f)
            text.textAlign = Paint.Align.LEFT
            c.drawText(SL[i], r.left + dp(10f), r.centerY() + dp(4f), text)
            text.textAlign = Paint.Align.RIGHT
            val v = when (i) {
                1 -> when { get(1) < 0.33f -> "gerade (16 · 12 · 8)"; get(1) < 0.66f -> "gemischt"; else -> "ungerade (13 · 11 · 7 · 5)" }
                else -> "${(get(i) * 100).toInt()} %"
            }
            c.drawText(v, r.right - dp(10f), r.centerY() + dp(4f), text)
            text.textAlign = Paint.Align.CENTER
        }
        host.postInvalidateOnAnimation()
    }

    private fun drawRing(c: Canvas, l: Int) {
        val r = rings[l]
        val cx = r.centerX(); val cy = r.centerY()
        val rad = min(r.width(), r.height()) * 0.38f
        val col = COLS[l]
        val on = Euclid.laneOn[l]
        stroke.strokeWidth = dp(1.2f)
        stroke.color = Color.argb(if (on) 90 else 40, Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(cx, cy, rad, stroke)
        val n = Euclid.laneN[l]
        val cur = Euclid.laneStep[l]
        // Verbindungslinien der Schläge (Vieleck)
        var px = 0f; var py = 0f; var fx = 0f; var fy = 0f; var first = true
        for (i in 0 until n) if (Euclid.laneHit[l][i]) {
            val a = (-PI / 2 + 2 * PI * i / n)
            val x = cx + cos(a).toFloat() * rad; val y = cy + sin(a).toFloat() * rad
            if (!first) { stroke.color = Color.argb(if (on) 120 else 40, Color.red(col), Color.green(col), Color.blue(col)); c.drawLine(px, py, x, y, stroke) } else { fx = x; fy = y }
            first = false; px = x; py = y
        }
        if (!first) c.drawLine(px, py, fx, fy, stroke)
        for (i in 0 until n) {
            val a = (-PI / 2 + 2 * PI * i / n)
            val x = cx + cos(a).toFloat() * rad; val y = cy + sin(a).toFloat() * rad
            val hit = Euclid.laneHit[l][i]
            fill.color = when {
                i == cur && hit && on -> Color.WHITE
                hit -> if (on) col else Color.argb(90, Color.red(col), Color.green(col), Color.blue(col))
                else -> Color.argb(70, 255, 255, 255)
            }
            c.drawCircle(x, y, if (hit) dp(5f) else dp(2.5f), fill)
            if (i == cur) { stroke.color = Color.WHITE; c.drawCircle(x, y, dp(8f), stroke) }
        }
        val fl = Euclid.laneFlash[l]
        fill.color = Color.argb((40 + 180 * fl).toInt().coerceAtMost(220), Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(cx, cy, rad * 0.32f * (0.8f + 0.4f * fl), fill)
        text.color = if (on) Color.WHITE else Color.argb(110, 255, 255, 255)
        text.textSize = dp(11f)
        c.drawText(Euclid.NAMES[l], cx, cy - dp(1f), text)
        text.textSize = dp(9f)
        c.drawText(if (on) "${Euclid.laneK[l]} / ${Euclid.laneN[l]}" else "stumm", cx, cy + dp(11f), text)
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (k in 0 until 4) if (sliders[k].contains(x, y)) { slPtr = id; slIdx = k; slLast = x; slMoved = false; return }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                when {
                    bClose.contains(x, y) -> { close(); haptic() }
                    bOn.contains(x, y) -> {
                        Euclid.on = !Euclid.on; haptic()
                        onHint?.invoke(if (Euclid.on) "EUKLID läuft im Raster" + (if (Dj.decks.any { it.playing }) " des laufenden Decks" else "") + " – Ringe antippen schaltet Spuren stumm" else "EUKLID gestoppt")
                    }
                    bPoly.contains(x, y) -> {
                        Euclid.poly = !Euclid.poly; haptic()
                        onHint?.invoke(if (Euclid.poly) "RIM und PLUCK verteilen ihre Schritte über den ganzen Takt (z. B. 5 gegen 4)" else "Alle Spuren im 1/16-Raster – Spuren mit anderer Länge verschieben sich gegen den Takt")
                    }
                    bNew.contains(x, y) -> { Euclid.regenerate(); haptic() }
                    bSmp.contains(x, y) -> { haptic(); onSamples?.invoke() }
                    bRecBars.contains(x, y) -> { recBars = when (recBars) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; haptic() }
                    bRec.contains(x, y) -> {
                        haptic()
                        if (!Euclid.on) { Euclid.on = true }
                        val p = Engine.recordInstLoop(8, recBars)
                        if (p < 0) onHint?.invoke("Kein freies Pad (1–12) – erst eins leeren")
                        else { recPad = p; onHint?.invoke("Ab der nächsten Eins: $recBars Takt" + (if (recBars > 1) "e" else "") + " EUKLID → Pad ${p + 1}. Die 909 und alles andere laufen weiter.") }
                    }
                    bPreset.contains(x, y) -> {
                        haptic()
                        val cur = Euclid.presetIdx
                        Euclid.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                        onHint?.invoke("Vorlage ${Euclid.presetIdx + 1}/${Euclid.PRESETS.size}: ${Euclid.PRESETS[Euclid.presetIdx].name}")
                    }
                    bKit.contains(x, y) -> { haptic(); Euclid.kit = (Euclid.kit + 1) % Euclid.KITS.size; onHint?.invoke("Klang: ${Euclid.KITS[Euclid.kit].name}") }
                    else -> for (k in 0 until 4) if (rings[k].contains(x, y)) { Euclid.laneOn[k] = !Euclid.laneOn[k]; haptic() }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    if (e.getPointerId(p) != slPtr || slIdx < 0) continue
                    val px = e.getX(p)
                    val dv = (px - slLast) / sliders[slIdx].width()
                    slLast = px
                    if (kotlin.math.abs(dv) > 0f) slMoved = true
                    when (slIdx) {
                        0 -> Euclid.density = (Euclid.density + dv).coerceIn(0f, 1f)
                        1 -> Euclid.complexity = (Euclid.complexity + dv).coerceIn(0f, 1f)
                        2 -> Euclid.morph = (Euclid.morph + dv).coerceIn(0f, 1f)
                        else -> Euclid.level = (Euclid.level + dv).coerceIn(0f, 1f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (id == slPtr) {
                    // Dichte/Länge geändert → Muster passend neu verteilen
                    if (slMoved && (slIdx == 0 || slIdx == 1)) Euclid.regenerate()
                    slPtr = -1; slIdx = -1
                }
            }
        }
    }
}
