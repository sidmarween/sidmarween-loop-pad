package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Loop Station (wie RC-505): 4 Spuren mit je einem großen Knopf – Tippen = Aufnehmen → Abspielen → Overdub → Abspielen.
 * Spuren = die Pads 13–16 (nicht im normalen Raster), Eingang Mikrofon oder App-Klang.
 */
class LoopStationPanel(private val host: View) {
    companion object {
        const val FIRST = 12
        const val TRACKS = 4
    }
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    /** Erste Mikro-Aufnahme ohne eingemessenes Mikro-Timing: erst einmessen lassen. */
    var onNeedMicCalib: (() -> Unit)? = null
    /** ⇪ EXPORT: WAV, Stems oder in die DJ-Sammlung. */
    var onExport: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bAll = RectF(); private val bUndo = RectF(); private val bExp = RectF()
    private val big = Array(TRACKS) { RectF() }
    private val play = Array(TRACKS) { RectF() }
    private val clr = Array(TRACKS) { RectF() }
    private val src = Array(TRACKS) { RectF() }
    private val fader = Array(TRACKS) { RectF() }
    private val COLS = intArrayOf(Color.rgb(255, 90, 90), Color.rgb(90, 200, 255), Color.rgb(120, 230, 120), Color.rgb(255, 200, 70))
    private var faderPtr = -1
    private var faderTrack = -1
    private var clrDownAt = 0L
    private var clrTrack = -1

    fun open() { isOpen = true }
    /** Für die Lektionen: Lage des großen Knopfs einer Spur bzw. des ganzen Fensters. */
    fun bigRect(t: Int): RectF = big[t.coerceIn(0, TRACKS - 1)]
    fun boxRect(): RectF = box
    fun close() { isOpen = false; faderPtr = -1 }

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(8f)
        val bh = dp(36f)
        val y = box.top + m
        bClose.set(box.right - m - dp(44f), y, box.right - m, y + bh)
        bUndo.set(bClose.left - dp(6f) - dp(64f), y, bClose.left - dp(6f), y + bh)
        bAll.set(bUndo.left - dp(6f) - dp(76f), y, bUndo.left - dp(6f), y + bh)
        bExp.set(bAll.left - dp(6f) - dp(76f), y, bAll.left - dp(6f), y + bh)
        val top = y + bh + dp(26f)
        val g = dp(8f)
        val cw = (box.width() - 2 * m - (TRACKS - 1) * g) / TRACKS
        for (t in 0 until TRACKS) {
            val x0 = box.left + m + t * (cw + g)
            val x1 = x0 + cw
            src[t].set(x0, top, x1, top + dp(30f))
            val fTop = src[t].bottom + dp(8f)
            val bigSide = min(cw, (box.bottom - m - fTop) * 0.42f)
            val bottom = box.bottom - m
            clr[t].set(x0, bottom - dp(40f), x1, bottom)
            play[t].set(x0, clr[t].top - dp(8f) - dp(52f), x1, clr[t].top - dp(8f))
            big[t].set(x0 + (cw - bigSide) / 2f, play[t].top - dp(10f) - bigSide, x0 + (cw + bigSide) / 2f, play[t].top - dp(10f))
            fader[t].set(x0 + cw * 0.3f, fTop, x1 - cw * 0.3f, big[t].top - dp(12f))
        }
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        val lb = fitLabel(text, label, r.width() - dp(6f), min(dp(14f), r.height() * 0.4f), dp(7f))
        c.drawText(lb, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.shader = null
        fill.color = Color.argb(215, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(16, 17, 22)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = acc
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        c.drawText(fitLabel(text, "🔁 LOOP STATION", bExp.left - box.left - dp(18f), dp(16f), dp(9f)), box.left + dp(12f), bClose.centerY() + dp(1f), text)
        // Eingangspegel
        val lv = min(1f, Engine.inputLevel * 3f)
        val mr = RectF(box.left + dp(12f), bClose.bottom + dp(8f), box.right - dp(12f), bClose.bottom + dp(14f))
        fill.color = Color.rgb(40, 42, 50)
        c.drawRoundRect(mr, dp(3f), dp(3f), fill)
        fill.color = if (lv > 0.9f) Color.rgb(255, 70, 70) else Color.rgb(80, 220, 120)
        c.drawRoundRect(mr.left, mr.top, mr.left + mr.width() * lv, mr.bottom, dp(3f), dp(3f), fill)
        text.textSize = dp(9f); text.color = Color.argb(170, 255, 255, 255)
        val bpm = Engine.currentBpm()
        c.drawText("MIC-PEGEL" + if (bpm > 0f) String.format(java.util.Locale.GERMANY, "   ·   %.1f BPM", bpm) else "   ·   erste Spur legt das Tempo fest", mr.left, mr.bottom + dp(11f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bAll, if (Engine.seqPlaying) "■ ALLE" else "▶ ALLE", Engine.seqPlaying, acc)
        btn(c, bUndo, "↶ UNDO", false, acc)
        btn(c, bExp, "⇪ EXPORT", false, acc)
        btn(c, bClose, "✕", false, acc)
        for (t in 0 until TRACKS) drawTrack(c, t)
        host.postInvalidateOnAnimation()
    }

    private fun drawTrack(c: Canvas, t: Int) {
        val p = FIRST + t
        val col = COLS[t]
        val st = Engine.state(p)
        val armed = Engine.isArmed(p)
        btn(c, src[t], "${t + 1} · " + if (Engine.padSource[p] >= 1) "APP-KLANG" else "MIKRO", false, col)
        // Fader
        val f = fader[t]
        fill.color = Color.rgb(30, 32, 40)
        c.drawRoundRect(f, dp(6f), dp(6f), fill)
        val g = (Engine.padGain(p) / 1.5f).coerceIn(0f, 1f)
        val lv = min(1f, Engine.levels[p] * 2f)
        fill.color = Color.argb(140, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRect(f.left + dp(3f), f.bottom - lv * f.height(), f.right - dp(3f), f.bottom, fill)
        val ky = f.bottom - g * f.height()
        fill.color = Color.WHITE
        c.drawRoundRect(f.left - dp(4f), ky - dp(5f), f.right + dp(4f), ky + dp(5f), dp(3f), dp(3f), fill)
        // großer Knopf mit Fortschrittsring
        val b = big[t]
        val (label, ringCol) = when {
            st == Engine.PadState.RECORDING || (armed && st == Engine.PadState.EMPTY) -> (if (armed && st == Engine.PadState.EMPTY) "WARTET" else "● REC") to Color.rgb(240, 50, 60)
            st == Engine.PadState.OVERDUB -> "DUB" to Color.rgb(255, 190, 40)
            st == Engine.PadState.PLAYING -> "PLAY" to Color.rgb(70, 220, 110)
            st == Engine.PadState.MUTED -> "STOP" to Color.rgb(120, 120, 130)
            else -> "REC" to Color.rgb(200, 60, 70)
        }
        fill.color = Color.rgb(28, 30, 38)
        c.drawOval(b, fill)
        stroke.strokeWidth = dp(7f)
        stroke.color = Color.argb(60, Color.red(ringCol), Color.green(ringCol), Color.blue(ringCol))
        val ring = RectF(b.left + dp(6f), b.top + dp(6f), b.right - dp(6f), b.bottom - dp(6f))
        c.drawOval(ring, stroke)
        val prog = if (st == Engine.PadState.PLAYING || st == Engine.PadState.OVERDUB) Engine.progress(p) else if (st == Engine.PadState.RECORDING) 1f else 0f
        stroke.color = ringCol
        if (prog > 0f) c.drawArc(ring, -90f, 360f * prog, false, stroke)
        if (st == Engine.PadState.RECORDING || st == Engine.PadState.OVERDUB) {
            val pulse = 0.5f + 0.5f * kotlin.math.sin(System.nanoTime() / 1.2e8).toFloat()
            fill.color = Color.argb((60 + 80 * pulse).toInt(), Color.red(ringCol), Color.green(ringCol), Color.blue(ringCol))
            c.drawOval(RectF(b.left + dp(16f), b.top + dp(16f), b.right - dp(16f), b.bottom - dp(16f)), fill)
        }
        text.color = Color.WHITE
        text.textSize = min(dp(20f), b.width() * 0.2f)
        c.drawText(label, b.centerX(), b.centerY() + text.textSize * 0.36f, text)
        if (Engine.hasLoop(p)) {
            text.textSize = dp(9f); text.color = Color.argb(170, 255, 255, 255)
            c.drawText(String.format(java.util.Locale.GERMANY, "%.1f s", Engine.loopSeconds(p)), b.centerX(), b.bottom + dp(1f), text)
        }
        val playing = Engine.hasLoop(p) && st != Engine.PadState.MUTED
        btn(c, play[t], if (playing) "■" else "▶", playing, col)
        val holding = clrTrack == t
        btn(c, clr[t], if (holding) "LÖSCHT …" else "CLEAR (halten)", holding, Color.rgb(230, 70, 70))
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Großer Knopf: leer → Aufnahme → Abspielen → Overdub → Abspielen … (gestoppt → Abspielen). */
    private fun tapTrack(t: Int) {
        val p = FIRST + t
        val st = Engine.state(p)
        if (st == Engine.PadState.MUTED && Engine.takesIdle(p)) {
            Engine.launchToggle(p)
            return
        }
        if (st == Engine.PadState.EMPTY && Engine.padSource[p] == 0 && !Engine.micCalibrated && onNeedMicCalib != null) {
            onNeedMicCalib?.invoke()
            return
        }
        val oldBars = Engine.liveBars
        val oldTap = Engine.tapOverdub
        Engine.liveBars = -1
        Engine.tapOverdub = true
        try {
            Engine.padDown(p, 1f, 0L)
            Engine.padUp(p)
        } finally {
            Engine.liveBars = oldBars
            Engine.tapOverdub = oldTap
        }
        when (st) {
            Engine.PadState.EMPTY -> onHint?.invoke("Spur ${t + 1}: Aufnahme – nochmal tippen schließt den Loop")
            Engine.PadState.PLAYING -> onHint?.invoke("Spur ${t + 1}: Overdub – nochmal tippen beendet ihn")
            else -> {}
        }
    }

    fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                for (t in 0 until TRACKS) {
                    val b = big[t]
                    val cx = b.centerX(); val cy = b.centerY(); val r = b.width() / 2f
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r * 1.1f) { tapTrack(t); haptic(); return }
                    if (play[t].contains(x, y)) {
                        val p = FIRST + t
                        if (Engine.hasLoop(p)) Engine.launchToggle(p)
                        haptic(); return
                    }
                    if (clr[t].contains(x, y)) {
                        clrTrack = t; clrDownAt = System.nanoTime()
                        val tok = clrDownAt
                        host.postDelayed({
                            if (clrTrack == t && clrDownAt == tok) {
                                Engine.clear(FIRST + t)
                                clrTrack = -1
                                haptic()
                                onHint?.invoke("Spur ${t + 1} gelöscht (↶ UNDO holt sie zurück)")
                            }
                        }, 600L)
                        return
                    }
                    if (src[t].contains(x, y)) {
                        val p = FIRST + t
                        Engine.padSource[p] = if (Engine.padSource[p] >= 1) 0 else 1
                        onHint?.invoke("Spur ${t + 1}: Eingang " + if (Engine.padSource[p] >= 1) "App-Klang (nimmt auf, was Marv PAD spielt)" else "Mikrofon")
                        haptic(); return
                    }
                    val f = fader[t]
                    if (x >= f.left - dp(14f) && x <= f.right + dp(14f) && y >= f.top - dp(8f) && y <= f.bottom + dp(8f)) {
                        faderPtr = e.getPointerId(i); faderTrack = t
                        setFader(y)
                        return
                    }
                }
                when {
                    bClose.contains(x, y) -> { close(); haptic() }
                    bAll.contains(x, y) -> { if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay(); haptic() }
                    bExp.contains(x, y) -> { haptic(); onExport?.invoke() }
                    bUndo.contains(x, y) -> { val l = Engine.undo(); onHint?.invoke(if (l != null) "↶ $l" else "Nichts zum Rückgängigmachen"); haptic() }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = e.findPointerIndex(faderPtr)
                if (pi >= 0) setFader(e.getY(pi))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == faderPtr) faderPtr = -1
                clrTrack = -1
            }
            MotionEvent.ACTION_CANCEL -> { faderPtr = -1; clrTrack = -1 }
        }
    }

    private fun setFader(y: Float) {
        val t = faderTrack
        if (t < 0) return
        val f = fader[t]
        val v = ((f.bottom - y) / f.height()).coerceIn(0f, 1f)
        Engine.setPadGain(FIRST + t, v * 1.5f)
    }
}
