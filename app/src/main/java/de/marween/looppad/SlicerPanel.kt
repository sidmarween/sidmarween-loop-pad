package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Vollbild-Ansicht des Slicers: Wellenform mit 16 Stücken und 16 große Pads (4 × 4). */
class SlicerPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    var onSource: (() -> Unit)? = null
    var onRec: (() -> Unit)? = null
    var onToPads: (() -> Unit)? = null
    /** ⇪ EXPORT: Pattern/Slices als WAV oder in die DJ-Sammlung. */
    var onExport: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bPlay = RectF(); private val bRec = RectF(); private val bExp = RectF()
    private val bSrc = RectF(); private val bPrev = RectF(); private val bNext = RectF(); private val bLen = RectF()
    private val bChoke = RectF(); private val bClear = RectF(); private val bFx = RectF(); private val bPads = RectF(); private val bGrid = RectF()

    // ---- Raster-Ansicht des Slicer-Patterns
    /** true = Raster (Schritte je Slice) statt der 16 großen Pads. */
    var gridMode = false
        private set
    private val gridArea = RectF()
    private val labelW get() = dp(34f)
    private var gridPage = 0
    private val pageBtns = Array(4) { RectF() }
    private var cellW = 0f
    private var cellH = 0f
    private var gridTop = 0f
    private var gPtr = -1
    private var gSlice = -1
    private var gStep = -1
    private var gDownY = 0f
    private var gStartVel = 1f
    private var gWasOn = false
    private var gDragged = false
    private var labelDown = -1
    private var labelDownAt = 0L
    private val wave = RectF()
    private val pads = Array(Slicer.N) { RectF() }
    private val flash = FloatArray(Slicer.N)
    private val held = HashMap<Int, Int>()
    private var lastT = 0L

    private val COLS = IntArray(Slicer.N) { Color.HSVToColor(floatArrayOf((it * 360f / Slicer.N + 10f) % 360f, 0.65f, 1f)) }

    fun open() { isOpen = true; held.clear() }
    fun close() { isOpen = false; held.clear() }

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(8f)
        var y = box.top + m
        val bh = dp(34f)
        bClose.set(box.right - m - dp(42f), y, box.right - m, y + bh)
        bPlay.set(bClose.left - dp(6f) - dp(42f), y, bClose.left - dp(6f), y + bh)
        bRec.set(bPlay.left - dp(6f) - dp(42f), y, bPlay.left - dp(6f), y + bh)
        bExp.set(bRec.left - dp(6f) - dp(76f), y, bRec.left - dp(6f), y + bh)
        y += bh + dp(8f)
        val row = arrayOf(bSrc, bPrev, bNext, bLen, bChoke, bFx, bGrid, bClear, bPads)
        val g = dp(5f)
        val w = (box.width() - 2 * m - (row.size - 1) * g) / row.size
        for (i in row.indices) row[i].set(box.left + m + i * (w + g), y, box.left + m + i * (w + g) + w, y + dp(32f))
        y += dp(40f)
        wave.set(box.left + m, y, box.right - m, y + max(dp(64f), box.height() * 0.13f))
        y = wave.bottom + dp(10f)
        // Raster: Seiten-Knöpfe (bei mehr als 16 Schritten), darunter 16 Zeilen
        val pages = (Slicer.len + 15) / 16
        var gy = y
        if (pages > 1) {
            val pw = (box.width() - 2 * m - labelW - 3 * dp(6f)) / 4f
            for (k in 0 until 4) {
                if (k < pages) pageBtns[k].set(box.left + m + labelW + k * (pw + dp(6f)), gy, box.left + m + labelW + k * (pw + dp(6f)) + pw, gy + dp(26f))
                else pageBtns[k].setEmpty()
            }
            gy += dp(32f)
        } else for (r in pageBtns) r.setEmpty()
        if (gridPage >= pages) gridPage = 0
        gridArea.set(box.left + m, gy, box.right - m, box.bottom - m)
        gridTop = gy
        cellH = gridArea.height() / Slicer.N
        cellW = (gridArea.width() - labelW) / 16f
        val pg = dp(8f)
        val side = min((box.width() - 2 * m - 3 * pg) / 4f, (box.bottom - m - y - 3 * pg) / 4f)
        val gx = box.centerX() - (4 * side + 3 * pg) / 2f
        for (i in 0 until Slicer.N) {
            // unten links = 1 (wie beim MPC)
            val c = i % 4
            val r = 3 - i / 4
            pads[i].set(gx + c * (side + pg), y + r * (side + pg), gx + c * (side + pg) + side, y + r * (side + pg) + side)
        }
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 100, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on && (Color.red(col) + Color.green(col)) > 380) Color.BLACK else Color.WHITE
        val lb = fitLabel(text, label, r.width() - dp(6f), dp(12f), dp(7f))
        c.drawText(lb, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val now = System.nanoTime()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1e9f).coerceIn(0f, 0.1f)
        lastT = now
        fill.shader = null
        fill.color = Color.argb(200, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(17, 18, 23)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = acc
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        val tw = bExp.left - box.left - dp(18f)
        text.color = Color.WHITE
        c.drawText(fitLabel(text, "✂ SLICER", tw, dp(15f), dp(9f)), box.left + dp(12f), bClose.centerY() - dp(1f), text)
        text.color = Color.argb(180, 255, 255, 255)
        val sub = if (Slicer.data == null) "Quelle wählen: ein Pad mit Loop/Song" else
            "Pad ${Slicer.srcPad + 1} · Takt ${Slicer.startBar + 1}–${Slicer.startBar + Slicer.bars} von ${Slicer.srcBars()} · 16 Slices"
        c.drawText(fitLabel(text, sub, tw, dp(9.5f), dp(7f)), box.left + dp(12f), bClose.centerY() + dp(12f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bRec, if (Engine.seqRecording) "● REC" else "●", Engine.seqRecording, Color.rgb(225, 40, 50))
        btn(c, bPlay, if (Engine.seqPlaying) "■" else "▶", Engine.seqPlaying, acc)
        btn(c, bClose, "✕", false, acc)
        btn(c, bExp, "⇪ EXPORT", false, acc)
        btn(c, bSrc, "QUELLE", false, acc)
        btn(c, bPrev, "◀ TAKT", false, acc)
        btn(c, bNext, "TAKT ▶", false, acc)
        btn(c, bLen, "${Slicer.bars} TAKT" + if (Slicer.bars > 1) "E" else "", false, acc)
        btn(c, bChoke, if (Slicer.choke) "CHOKE" else "POLY", Slicer.choke, acc)
        btn(c, bFx, "FX-BUS", Slicer.fx, acc)
        btn(c, bGrid, if (gridMode) "▦ PADS" else "▤ RASTER", gridMode, acc)
        btn(c, bClear, "LEEREN", false, acc)
        btn(c, bPads, "→ PADS", false, acc)
        drawWave(c, acc)
        if (gridMode) {
            drawGrid(c, acc, now)
            host.postInvalidateOnAnimation()
            return
        }
        // Pads
        val lh = Slicer.lastHit
        if (lh in 0 until Slicer.N && now - Slicer.lastHitAt < 30_000_000L) flash[lh] = 1f
        for (i in 0 until Slicer.N) {
            flash[i] = max(0f, flash[i] - dt * 3f)
            val r = pads[i]
            val col = COLS[i]
            val down = held.containsValue(i)
            val f = if (down) 1f else flash[i]
            fill.color = blend(Color.rgb(30, 32, 40), col, 0.25f + 0.65f * f)
            c.drawRoundRect(r, dp(12f), dp(12f), fill)
            stroke.strokeWidth = dp(if (f > 0.5f) 3f else 1.5f)
            stroke.color = col
            c.drawRoundRect(r, dp(12f), dp(12f), stroke)
            text.color = if (f > 0.6f) Color.BLACK else Color.WHITE
            text.textSize = min(dp(22f), r.width() * 0.3f)
            c.drawText("${i + 1}", r.centerX(), r.centerY() + text.textSize * 0.36f, text)
            // Treffer im Pattern als Punkte
            val hits = (0 until Slicer.len).count { Slicer.steps[i][it] > 0f }
            if (hits > 0) {
                text.textSize = min(dp(9f), r.width() * 0.13f)
                text.color = Color.argb(200, 255, 255, 255)
                c.drawText("●×$hits", r.centerX(), r.bottom - dp(6f), text)
            }
        }
        if (Slicer.data == null) {
            text.textSize = dp(13f); text.color = Color.WHITE
            c.drawText("Zuerst QUELLE wählen", box.centerX(), wave.centerY(), text)
        }
        host.postInvalidateOnAnimation()
    }

    /** Raster: Zeile = Slice (Farbe wie das Pad), Spalte = 1/16-Schritt; Helligkeit = Lautstärke. */
    private fun drawGrid(c: Canvas, acc: Int, now: Long) {
        val len = Slicer.len
        val pages = (len + 15) / 16
        for (k in 0 until pages) btn(c, pageBtns[k], "TAKT ${k + 1}", k == gridPage, acc)
        val cur = Engine.instCurrentStep(len)
        val lh = Slicer.lastHit
        val recent = now - Slicer.lastHitAt < 120_000_000L
        for (sl in 0 until Slicer.N) {
            // unten = Slice 1 (wie bei den Pads unten links)
            val row = Slicer.N - 1 - sl
            val y0 = gridTop + row * cellH
            val col = COLS[sl]
            // Beschriftung (antippen = vorhören, lange halten = Zeile leeren)
            val lr = RectF(gridArea.left, y0 + 1f, gridArea.left + labelW - dp(4f), y0 + cellH - 1f)
            fill.color = if (recent && lh == sl) col else blend(Color.rgb(30, 32, 40), col, 0.35f)
            c.drawRoundRect(lr, dp(4f), dp(4f), fill)
            text.color = if (recent && lh == sl) Color.BLACK else Color.WHITE
            text.textSize = min(dp(11f), cellH * 0.6f)
            c.drawText("${sl + 1}", lr.centerX(), lr.centerY() + text.textSize * 0.36f, text)
            for (k in 0 until 16) {
                val step = gridPage * 16 + k
                if (step >= len) break
                val x0 = gridArea.left + labelW + k * cellW
                val r = RectF(x0 + 1.5f, y0 + 1.5f, x0 + cellW - 1.5f, y0 + cellH - 1.5f)
                val v = Slicer.steps[sl][step]
                val beat = k % 4 == 0
                fill.color = when {
                    v > 0f -> blend(Color.rgb(40, 42, 50), col, 0.35f + 0.65f * v)
                    beat -> Color.rgb(38, 40, 48)
                    else -> Color.rgb(28, 30, 37)
                }
                c.drawRoundRect(r, dp(3f), dp(3f), fill)
                if (step == cur) {
                    stroke.strokeWidth = dp(1.5f); stroke.color = Color.WHITE
                    c.drawRoundRect(r, dp(3f), dp(3f), stroke)
                }
            }
        }
        // Takt-Trennlinien (alle 4 Schritte)
        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(70, 255, 255, 255)
        for (k in 1 until 4) {
            val x = gridArea.left + labelW + k * 4 * cellW
            c.drawLine(x, gridTop, x, gridArea.bottom, stroke)
        }
    }

    /** Raster berührt: Zelle (Slice, Schritt) oder −1. */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (!gridArea.contains(x, y) || x < gridArea.left + labelW) return null
        val row = ((y - gridTop) / cellH).toInt().coerceIn(0, Slicer.N - 1)
        val k = ((x - gridArea.left - labelW) / cellW).toInt().coerceIn(0, 15)
        val step = gridPage * 16 + k
        if (step >= Slicer.len) return null
        return (Slicer.N - 1 - row) to step
    }

    private fun gridTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i); val y = e.getY(i)
                for (k in pageBtns.indices) if (pageBtns[k].contains(x, y)) { gridPage = k; haptic(); return true }
                if (gridArea.contains(x, y) && x < gridArea.left + labelW) {
                    val row = ((y - gridTop) / cellH).toInt().coerceIn(0, Slicer.N - 1)
                    val sl = Slicer.N - 1 - row
                    Slicer.queue(-1, sl, 0.9f)
                    labelDown = sl; labelDownAt = System.nanoTime()
                    val tok = labelDownAt
                    host.postDelayed({
                        if (labelDown == sl && labelDownAt == tok) {
                            java.util.Arrays.fill(Slicer.steps[sl], 0f)
                            labelDown = -1
                            host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onHint?.invoke("Slice ${sl + 1}: alle Schläge gelöscht")
                        }
                    }, 650L)
                    haptic()
                    return true
                }
                val cell = cellAt(x, y) ?: return false
                if (gPtr >= 0) return true
                gPtr = e.getPointerId(i)
                gSlice = cell.first; gStep = cell.second
                gDownY = y
                gWasOn = Slicer.steps[gSlice][gStep] > 0f
                gStartVel = if (gWasOn) Slicer.steps[gSlice][gStep] else 1f
                gDragged = false
                if (!gWasOn) { Slicer.steps[gSlice][gStep] = 1f; Slicer.queue(-1, gSlice, 1f) }
                haptic()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = e.findPointerIndex(gPtr)
                if (pi < 0) return gPtr >= 0
                val dy = gDownY - e.getY(pi)
                if (abs(dy) > dp(8f)) gDragged = true
                if (gDragged) {
                    // Wischen hoch/runter = Lautstärke
                    val v = (gStartVel + dy / dp(120f)).coerceIn(0.05f, 1f)
                    Slicer.steps[gSlice][gStep] = v
                    onHint?.invoke("Slice ${gSlice + 1} · Schritt ${gStep + 1}: ${(v * 100).toInt()} %")
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                labelDown = -1
                if (gPtr >= 0 && (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == gPtr)) {
                    // kurzer Tipp auf einen gesetzten Schritt löscht ihn
                    if (gWasOn && !gDragged && e.actionMasked != MotionEvent.ACTION_CANCEL) Slicer.steps[gSlice][gStep] = 0f
                    gPtr = -1
                }
                return gridArea.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))
            }
        }
        return false
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun drawWave(c: Canvas, acc: Int) {
        fill.color = Color.rgb(12, 13, 17)
        c.drawRoundRect(wave, dp(6f), dp(6f), fill)
        val data = Slicer.data ?: return
        val n = data.size
        val cols = wave.width().toInt().coerceAtLeast(1)
        for (i in 0 until Slicer.N) {
            val x0 = wave.left + Slicer.bounds[i].toFloat() / n * wave.width()
            val x1 = wave.left + Slicer.bounds[i + 1].toFloat() / n * wave.width()
            fill.color = Color.argb(if (i % 2 == 0) 40 else 22, Color.red(COLS[i]), Color.green(COLS[i]), Color.blue(COLS[i]))
            c.drawRect(x0, wave.top, x1, wave.bottom, fill)
            text.textSize = dp(8f); text.color = COLS[i]
            if (x1 - x0 > dp(10f)) c.drawText("${i + 1}", (x0 + x1) / 2, wave.top + dp(10f), text)
            stroke.strokeWidth = dp(1f); stroke.color = Color.argb(150, 255, 255, 255)
            c.drawLine(x0, wave.top, x0, wave.bottom, stroke)
        }
        stroke.strokeWidth = max(1f, wave.width() / cols)
        for (px in 0 until cols step 2) {
            val a = (px.toLong() * n / cols).toInt()
            val b = min(n, ((px + 2).toLong() * n / cols).toInt())
            var pk = 0f
            var i = a
            while (i < b) { pk = max(pk, abs(data[i])); i += 4 }
            val h = min(1f, pk) * wave.height() * 0.45f
            val si = (0 until Slicer.N).firstOrNull { Slicer.bounds[it + 1] > a } ?: 0
            stroke.color = COLS[si]
            c.drawLine(wave.left + px, wave.centerY() - h, wave.left + px, wave.centerY() + h, stroke)
        }
        // Pattern-Lauflicht
        val cur = Engine.instCurrentStep(Slicer.len)
        if (cur >= 0) {
            val x = wave.left + (cur + 0.5f) / Slicer.len * wave.width()
            stroke.color = Color.WHITE; stroke.strokeWidth = dp(1.5f)
            c.drawLine(x, wave.top, x, wave.bottom, stroke)
        }
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        if (gridMode && Slicer.data != null && gridTouch(e)) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val p = if (gridMode) -1 else pads.indexOfFirst { it.contains(x, y) }
                if (p >= 0) {
                    if (Slicer.data == null) { onHint?.invoke("Zuerst QUELLE wählen"); return }
                    val tNs = TouchVelocity.eventNanos(e)
                    val vel = TouchVelocity.of(e, i, y, pads[p].top, pads[p].bottom, tNs)
                    Engine.slicerHit(p, vel)
                    held[e.getPointerId(i)] = p
                    flash[p] = 1f
                    haptic()
                    return
                }
                if (button(x, y)) haptic()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> held.remove(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> held.clear()
        }
    }

    private fun button(x: Float, y: Float): Boolean {
        when {
            bClose.contains(x, y) -> close()
            bPlay.contains(x, y) -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
            bRec.contains(x, y) -> onRec?.invoke()
            bExp.contains(x, y) -> onExport?.invoke()
            bSrc.contains(x, y) -> onSource?.invoke()
            bPrev.contains(x, y) || bNext.contains(x, y) -> {
                if (Slicer.data == null) return true
                val tot = Slicer.srcBars()
                Slicer.startBar = (Slicer.startBar + if (bPrev.contains(x, y)) -Slicer.bars else Slicer.bars).coerceIn(0, max(0, tot - Slicer.bars))
                Slicer.rebuild()
                onHint?.invoke("Abschnitt: Takt ${Slicer.startBar + 1}–${Slicer.startBar + Slicer.bars}")
            }
            bLen.contains(x, y) -> {
                Slicer.bars = when (Slicer.bars) { 1 -> 2; 2 -> 4; else -> 1 }
                Slicer.rebuild()
                onHint?.invoke("${Slicer.bars} Takt" + (if (Slicer.bars > 1) "e" else "") + " in 16 Slices (je ${if (Slicer.bars == 1) "1/16" else if (Slicer.bars == 2) "1/8" else "1/4"})")
            }
            bChoke.contains(x, y) -> { Slicer.choke = !Slicer.choke; onHint?.invoke(if (Slicer.choke) "CHOKE: ein neuer Slice beendet den vorigen" else "POLY: Slices klingen übereinander aus") }
            bFx.contains(x, y) -> Slicer.fx = !Slicer.fx
            bGrid.contains(x, y) -> {
                gridMode = !gridMode
                onHint?.invoke(if (gridMode) "Raster: antippen setzt/löscht einen Schlag · hoch/runter wischen = Lautstärke · Zahl links = vorhören (lange halten = Zeile leeren)" else "Pads")
            }
            bClear.contains(x, y) -> { Slicer.clearPattern(); onHint?.invoke("Slicer-Pattern geleert") }
            bPads.contains(x, y) -> onToPads?.invoke()
            else -> return false
        }
        return true
    }
}
