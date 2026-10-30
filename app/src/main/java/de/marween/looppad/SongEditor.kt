package de.marween.looppad

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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * SONG-EDITOR (Arrangement wie in einer DAW): jede Quelle auf einer eigenen Spur – Pads (Wellenform bzw. Schläge),
 * ACID/DRUM/SYNTH (Noten des Patterns im jeweiligen Takt), ATMOS/EUKLID/HARMONIZER und Automations-Spuren.
 * Werkzeuge: AUSWAHL (Rahmen ziehen, Block verschieben), MALEN (Takte setzen / Automation zeichnen), RADIERER.
 * Abschnitte (Intro, Hauptteil, Ending …) als Leiste oben – antippen wählt den ganzen Abschnitt als Block.
 * ● SONG-AUFNAHME schreibt live jeden gespielten Takt ins Arrangement.
 */
class SongEditor(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    /** Instrument-Pattern bearbeiten (inst 0 ACID, 1 DRUM, 2.. SYNTH; pat 0..3). */
    var onOpenInst: ((Int, Int) -> Unit)? = null
    /** Abschnitt für Takt [bar] wählen (MainActivity zeigt die Namen). */
    var onPickSection: ((Int) -> Unit)? = null
    /** Automations-Spur hinzufügen (MainActivity zeigt die Liste). */
    var onPickAuto: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()

    // ---- Ansicht
    private var firstBar = 0f
    private var barsVis = 16
    private var firstRow = 0
    var cursorBar = 0
        private set
    private var tool = 0           // 0 AUSWAHL, 1 MALEN, 2 RADIERER
    private var paintPat = 1       // Pattern A..D für Instrument-Spuren
    /** Automations-Spuren, die angezeigt werden (benutzte immer). */
    val autoShown = BooleanArray(Engine.AUTO_LANES)

    // ---- Auswahl (Spur-IDs + Takte)
    private var selRows: List<Int> = emptyList()
    private var selB0 = -1
    private var selB1 = -1
    private var clip: List<Pair<Int, Engine.Region>> = emptyList()
    private var clipBars = 0

    // ---- Rechtecke
    private val box = RectF()
    private val tb = Array(16) { RectF() }
    private val names = RectF()
    private val grid = RectF()
    private val ruler = RectF()
    private val secRow = RectF()
    private val scrollBar = RectF()

    private val COL_PAD = Color.rgb(255, 150, 70)
    private val COL_INST = intArrayOf(Color.rgb(90, 190, 255), Color.rgb(120, 230, 140), Color.rgb(200, 140, 255), Color.rgb(240, 120, 200), Color.rgb(255, 210, 90))
    private val COL_X = intArrayOf(Color.rgb(140, 170, 255), Color.rgb(255, 170, 90), Color.rgb(255, 120, 190))
    private val COL_AUTO = Color.rgb(250, 230, 120)
    private val SEC_COLS = intArrayOf(Color.rgb(90, 160, 255), Color.rgb(120, 220, 140), Color.rgb(255, 200, 80), Color.rgb(255, 90, 90), Color.rgb(180, 140, 255), Color.rgb(90, 220, 220), Color.rgb(240, 130, 200))

    fun open() { isOpen = true; cursorBar = Engine.playFromBar }
    fun close() { isOpen = false }

    /** Sichtbare Spuren (IDs): Pads mit Inhalt oder im Song benutzt, alle Instrumente, Extras, benutzte/gewählte Automation. */
    private fun rows(): List<Int> {
        val out = ArrayList<Int>()
        for (p in 0 until Engine.TRK_PADS) if (Engine.hasLoop(p) || (0 until Engine.ARR_MAX).any { Engine.trkCell(p, it) > 0 }) out.add(p)
        for (i in 0 until InstState.INSTS) out.add(Engine.TRK_INST + i)
        for (k in 0 until Engine.ARR_X) out.add(Engine.TRK_X + k)
        for (l in 0 until Engine.AUTO_LANES) if (autoShown[l] || Engine.autoLaneUsed(l)) out.add(Engine.TRK_AUTO + l)
        return out
    }

    private fun rowName(t: Int): String = when {
        t < Engine.TRK_PADS -> "PAD ${t + 1}" + (if (!Engine.hasLoop(t)) " (leer)" else if (Engine.isOneShot(t)) " ⚡" else " ∞") + (Engine.padName(t)?.let { " · " + it.take(10) } ?: "")
        t < Engine.TRK_X -> InstState.instNames[t - Engine.TRK_INST]
        t < Engine.TRK_AUTO -> Engine.ARR_X_NAMES[t - Engine.TRK_X]
        else -> "〰 " + Engine.AUTO_NAMES[t - Engine.TRK_AUTO]
    }

    private fun rowCol(t: Int): Int = when {
        t < Engine.TRK_PADS -> COL_PAD
        t < Engine.TRK_X -> COL_INST[(t - Engine.TRK_INST).coerceIn(0, COL_INST.size - 1)]
        t < Engine.TRK_AUTO -> COL_X[t - Engine.TRK_X]
        else -> COL_AUTO
    }

    private fun isAuto(t: Int) = t >= Engine.TRK_AUTO
    private fun isInst(t: Int) = t in Engine.TRK_INST until Engine.TRK_X

    private val rowH get() = dp(46f)
    private fun visRows(): Int = max(1, (grid.height() / rowH).toInt())

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(8f)
        val bh = dp(30f)
        // Werkzeugleiste: zwei Reihen à 8
        val gw = (box.width() - 2 * pad - 7 * dp(4f)) / 8
        for (i in 0 until 16) {
            val r = i / 8; val c = i % 8
            val y = box.top + pad + r * (bh + dp(4f))
            tb[i].set(box.left + pad + c * (gw + dp(4f)), y, box.left + pad + c * (gw + dp(4f)) + gw, y + bh)
        }
        val top = box.top + pad + 2 * (bh + dp(4f)) + dp(2f)
        val nw = min(dp(96f), box.width() * 0.24f)
        secRow.set(box.left + pad + nw, top, box.right - pad, top + dp(20f))
        ruler.set(secRow.left, secRow.bottom, secRow.right, secRow.bottom + dp(18f))
        scrollBar.set(ruler.left, box.bottom - pad - dp(14f), ruler.right, box.bottom - pad)
        grid.set(ruler.left, ruler.bottom + dp(2f), ruler.right, scrollBar.top - dp(4f))
        names.set(box.left + pad, grid.top, grid.left - dp(4f), grid.bottom)
    }

    private fun barW() = grid.width() / barsVis
    private fun xOf(bar: Float) = grid.left + (bar - firstBar) * barW()
    private fun barAt(x: Float): Int = floor(firstBar + (x - grid.left) / barW()).toInt()
    private fun barAtF(x: Float): Float = firstBar + (x - grid.left) / barW()

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(30, 32, 40)
        c.drawRoundRect(r, dp(7f), dp(7f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 100, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(7f), dp(7f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(10.5f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(4f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private val TB = arrayOf("▶", "● REC", "SONG", "AUSWAHL", "MALEN", "RADIER", "PAT", "✕",
        "KOPIEREN", "EINFÜGEN", "DUPLIZ.", "LÖSCHEN", "▣ BLOCK", "＋TAKT", "−TAKT", "＋AUTO")

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.color = Color.argb(235, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(13, 14, 18)
        c.drawRoundRect(box, dp(12f), dp(12f), fill)
        val playing = Engine.seqPlaying
        val rec = Engine.songRec
        for (i in 0 until 16) {
            val (lb, on, col) = when (i) {
                0 -> Triple(if (playing) "■ STOPP" else "▶ AB T${cursorBar + 1}", playing, Color.rgb(140, 230, 170))
                1 -> Triple(if (rec) "● NIMMT AUF" else "● SONG-REC", rec, Color.rgb(255, 80, 80))
                2 -> Triple(if (Engine.songMode) "SONG AN" else "SONG AUS", Engine.songMode, acc)
                3 -> Triple("⬚ AUSWAHL", tool == 0, Color.rgb(120, 200, 255))
                4 -> Triple("✎ MALEN", tool == 1, Color.rgb(120, 230, 140))
                5 -> Triple("⌫ RADIER", tool == 2, Color.rgb(255, 140, 120))
                6 -> Triple("PATTERN " + InstState.patLetter(paintPat - 1), true, COL_INST[paintPat - 1])
                7 -> Triple("✕", false, acc)
                8 -> Triple("KOPIEREN", false, acc)
                9 -> Triple(if (clip.isEmpty()) "EINFÜGEN" else "EINF. ($clipBars T)", clip.isNotEmpty(), acc)
                10 -> Triple("DUPLIZ.", false, acc)
                11 -> Triple("LÖSCHEN", false, acc)
                12 -> Triple("▣ BLOCK", false, acc)
                13 -> Triple("＋ TAKT", false, acc)
                14 -> Triple("− TAKT", false, acc)
                else -> Triple("＋ AUTOM.", false, COL_AUTO)
            }
            btn(c, tb[i], lb, on, col)
        }
        drawSections(c)
        drawRuler(c)
        drawTracks(c)
        drawScroll(c)
        // Status
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(9f); text.color = Color.argb(180, 255, 255, 255)
        val st = (if (rec) "● Aufnahme: alles, was du spielst, landet Takt für Takt im Song · " else "") +
            "Song ${Engine.songBars} Takte" + (if (selB0 >= 0) " · Auswahl T${selB0 + 1}–${selB1 + 1}, ${selRows.size} Spuren" else "") +
            " · Doppeltipp auf Instrument-Block = Noten bearbeiten"
        c.drawText(fitLabel(text, st, box.width() - dp(16f), dp(9f), dp(6.5f)), names.left, box.bottom - dp(3f), text)
        text.textAlign = Paint.Align.CENTER
        if (playing || rec) host.postInvalidateOnAnimation()
    }

    private fun drawSections(c: Canvas) {
        fill.color = Color.rgb(22, 23, 30)
        c.drawRect(secRow, fill)
        text.textSize = dp(9f)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb(160, 255, 255, 255)
        c.drawText("ABSCHNITT", names.left, secRow.centerY() + dp(3f), text)
        val n = Engine.songBars
        var b = 0
        while (b < n) {
            val k = Engine.arrSection[b]
            if (k < 0) { b++; continue }
            var e = b + 1
            while (e < n && Engine.arrSection[e] < 0) e++
            val x0 = max(secRow.left, xOf(b.toFloat())); val x1 = min(secRow.right, xOf(e.toFloat()))
            if (x1 > x0) {
                val col = SEC_COLS[k % SEC_COLS.size]
                fill.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))
                c.drawRoundRect(x0 + dp(1f), secRow.top + dp(2f), x1 - dp(1f), secRow.bottom - dp(2f), dp(5f), dp(5f), fill)
                text.color = Color.BLACK
                c.drawText(fitLabel(text, Engine.SECTION_NAMES[k], x1 - x0 - dp(6f), dp(9f), dp(6f)), x0 + dp(4f), secRow.centerY() + dp(3f), text)
            }
            b = e
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawRuler(c: Canvas) {
        fill.color = Color.rgb(28, 29, 36)
        c.drawRect(ruler, fill)
        text.textSize = dp(9f)
        val b0 = floor(firstBar).toInt()
        for (b in b0..b0 + barsVis + 1) {
            val x = xOf(b.toFloat())
            if (x < ruler.left - 1 || x > ruler.right + 1) continue
            stroke.strokeWidth = dp(1f)
            stroke.color = if (b % 4 == 0) Color.argb(160, 255, 255, 255) else Color.argb(60, 255, 255, 255)
            c.drawLine(x, ruler.top, x, ruler.bottom, stroke)
            if (barsVis <= 16 || b % 4 == 0) {
                text.color = if (b >= Engine.songBars) Color.argb(80, 255, 255, 255) else Color.WHITE
                text.textAlign = Paint.Align.LEFT
                c.drawText("${b + 1}", x + dp(2f), ruler.bottom - dp(5f), text)
            }
        }
        // Song-Ende
        val xe = xOf(Engine.songBars.toFloat())
        if (xe in ruler.left..ruler.right) { stroke.color = Color.rgb(255, 90, 90); stroke.strokeWidth = dp(2f); c.drawLine(xe, ruler.top, xe, grid.bottom, stroke) }
        // Cursor (Start) und Abspielposition
        val xc = xOf(cursorBar.toFloat())
        if (xc in ruler.left..ruler.right) {
            fill.color = Color.rgb(140, 230, 170)
            path.reset(); path.moveTo(xc, ruler.bottom); path.lineTo(xc - dp(6f), ruler.top); path.lineTo(xc + dp(6f), ruler.top); path.close()
            c.drawPath(path, fill)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb(160, 255, 255, 255)
        c.drawText("TAKT", names.left, ruler.centerY() + dp(3f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun playPos(): Float {
        if (!Engine.seqPlaying || !Engine.songMode) return -1f
        val ap = Engine.autoPos
        return if (ap >= 0f) ap / Engine.AUTO_RES else Engine.songBar().toFloat()
    }

    private val peaksCache = HashMap<Int, Pair<Int, FloatArray>>()
    private fun peaks(p: Int): FloatArray? {
        val id = Engine.padDataId(p)
        val c = peaksCache[p]
        if (c != null && c.first == id) return c.second
        val bars = max(1, ceil(Engine.padBarsLen(p).toDouble()).toInt())
        val pk = Engine.padPeaks(p, 48 * bars) ?: return null
        peaksCache[p] = id to pk
        return pk
    }

    private fun drawTracks(c: Canvas) {
        val rs = rows()
        firstRow = firstRow.coerceIn(0, max(0, rs.size - visRows()))
        c.save()
        c.clipRect(names.left, grid.top, grid.right, grid.bottom)
        val bw = barW()
        val b0 = floor(firstBar).toInt()
        val pp = playPos()
        for (vi in 0 until visRows() + 1) {
            val ri = firstRow + vi
            if (ri >= rs.size) break
            val t = rs[ri]
            val y0 = grid.top + vi * rowH
            val y1 = y0 + rowH - dp(2f)
            val col = rowCol(t)
            // Name
            fill.color = Color.rgb(24, 25, 32)
            c.drawRoundRect(names.left, y0, names.right, y1, dp(5f), dp(5f), fill)
            fill.color = col
            c.drawRect(names.left, y0, names.left + dp(3f), y1, fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(9.5f); text.color = Color.WHITE
            c.drawText(fitLabel(text, rowName(t), names.width() - dp(8f), dp(9.5f), dp(6.5f)), names.left + dp(6f), y0 + dp(14f), text)
            text.textSize = dp(8f); text.color = Color.argb(150, 255, 255, 255)
            val sub = when {
                isInst(t) -> "Pattern " + InstState.patLetter(InstState.curPat[t - Engine.TRK_INST]) + " aktiv"
                isAuto(t) -> "zeichnen mit ✎"
                t < Engine.TRK_PADS && Engine.hasLoop(t) -> String.format(java.util.Locale.GERMANY, "%.1f Takte", Engine.padBarsLen(t))
                else -> ""
            }
            c.drawText(fitLabel(text, sub, names.width() - dp(8f), dp(8f), dp(6f)), names.left + dp(6f), y0 + dp(27f), text)
            text.textAlign = Paint.Align.CENTER
            // Hintergrund der Spur
            fill.color = if (ri % 2 == 0) Color.rgb(18, 19, 25) else Color.rgb(21, 22, 29)
            c.drawRect(grid.left, y0, grid.right, y1, fill)
            if (isAuto(t)) { drawAutoRow(c, t - Engine.TRK_AUTO, y0, y1); continue }
            // Blöcke: zusammenhängende Takte mit gleichem Wert
            var b = max(0, b0)
            val bEnd = min(Engine.ARR_MAX, b0 + barsVis + 2)
            while (b < bEnd) {
                val v = Engine.trkCell(t, b)
                if (v <= 0) { b++; continue }
                var e = b + 1
                while (e < bEnd && Engine.trkCell(t, e) == v) e++
                val x0 = xOf(b.toFloat()) + dp(1f); val x1 = xOf(e.toFloat()) - dp(1f)
                val bc = if (isInst(t)) COL_INST[(v - 1).coerceIn(0, 3)] else col
                fill.color = Color.argb(70, Color.red(bc), Color.green(bc), Color.blue(bc))
                c.drawRoundRect(x0, y0 + dp(1f), x1, y1 - dp(1f), dp(5f), dp(5f), fill)
                stroke.strokeWidth = dp(1.2f); stroke.color = bc
                c.drawRoundRect(x0, y0 + dp(1f), x1, y1 - dp(1f), dp(5f), dp(5f), stroke)
                // Inhalt je Takt
                for (bb in b until e) drawBarContent(c, t, bb, v, xOf(bb.toFloat()), xOf(bb + 1f), y0 + dp(3f), y1 - dp(3f), bc)
                if (isInst(t)) {
                    text.textSize = dp(8f); text.color = Color.WHITE; text.textAlign = Paint.Align.LEFT
                    c.drawText(InstState.patLetter(v - 1), x0 + dp(3f), y0 + dp(10f), text)
                    text.textAlign = Paint.Align.CENTER
                }
                b = e
            }
            // Takt-Linien
            stroke.strokeWidth = dp(0.6f)
            for (bb in b0..b0 + barsVis + 1) {
                val x = xOf(bb.toFloat())
                stroke.color = if (bb % 4 == 0) Color.argb(55, 255, 255, 255) else Color.argb(22, 255, 255, 255)
                c.drawLine(x, y0, x, y1, stroke)
            }
        }
        // Auswahl
        if (selB0 >= 0) {
            val x0 = xOf(selB0.toFloat()) + dragDx; val x1 = xOf(selB1 + 1f) + dragDx
            for (t in selRows) {
                val ri = rs.indexOf(t) - firstRow
                if (ri < 0 || ri > visRows()) continue
                val y0 = grid.top + ri * rowH
                fill.color = Color.argb(60, 120, 200, 255)
                c.drawRect(x0, y0, x1, y0 + rowH - dp(2f), fill)
                stroke.color = Color.rgb(120, 200, 255); stroke.strokeWidth = dp(1.5f)
                c.drawRect(x0, y0, x1, y0 + rowH - dp(2f), stroke)
            }
        }
        if (rubber) {
            stroke.color = Color.WHITE; stroke.strokeWidth = dp(1f)
            c.drawRect(min(rx0, rx1), min(ry0, ry1), max(rx0, rx1), max(ry0, ry1), stroke)
        }
        // Abspielkopf
        if (pp >= 0f) {
            val x = xOf(pp)
            if (x in grid.left..grid.right) { stroke.color = if (Engine.songRec) Color.rgb(255, 80, 80) else Color.WHITE; stroke.strokeWidth = dp(2f); c.drawLine(x, grid.top, x, grid.bottom, stroke) }
            // mitlaufen
            if (x > grid.right - barW() || x < grid.left) firstBar = max(0f, floor(pp) - 1f)
        }
        c.restore()
    }

    private fun drawBarContent(c: Canvas, t: Int, bar: Int, v: Int, x0: Float, x1: Float, y0: Float, y1: Float, col: Int) {
        val w = x1 - x0
        if (w < dp(6f)) return
        val h = y1 - y0
        when {
            t < Engine.TRK_PADS -> {
                if (!Engine.hasLoop(t)) return
                if (Engine.isOneShot(t)) {
                    // Schläge: Sequenzer-Schritte + eingespielte Noten
                    fill.color = col
                    for (s in 0 until 16) if (Engine.seqHit(t, s)) c.drawRect(x0 + w * s / 16f, y0 + h * 0.3f, x0 + w * s / 16f + max(dp(1.5f), w / 40f), y1 - h * 0.3f, fill)
                    val rb = max(1, Engine.rollBars[t])
                    val within = Math.floorMod(bar, rb)
                    for (n in Engine.notesOf(t)) {
                        if (floor(n.pos).toInt() != within) continue
                        val fx = x0 + w * (n.pos - within).toFloat()
                        val yy = y1 - h * ((n.semi + 12).coerceIn(0, 36) / 36f)
                        c.drawRect(fx, yy - dp(1.5f), fx + max(dp(2f), w * n.len.toFloat()), yy + dp(1.5f), fill)
                    }
                } else {
                    val pk = peaks(t) ?: return
                    val lb = max(1, ceil(Engine.padBarsLen(t).toDouble()).toInt())
                    val per = pk.size / lb
                    val seg = Math.floorMod(bar, lb)
                    val n = min(per, max(4, (w / dp(2f)).toInt()))
                    stroke.strokeWidth = max(1f, w / n * 0.7f)
                    stroke.color = col
                    val cy = (y0 + y1) / 2
                    for (k in 0 until n) {
                        val i = seg * per + k * per / n
                        val a = pk.getOrElse(i) { 0f }.coerceIn(0f, 1f) * h * 0.5f
                        val x = x0 + w * (k + 0.5f) / n
                        c.drawLine(x, cy - a, x, cy + a, stroke)
                    }
                }
            }
            t < Engine.TRK_X -> {
                val inst = t - Engine.TRK_INST
                val p = InstState.patFor(inst, v - 1) ?: return
                val len = max(1, p.len)
                fill.color = col
                if (inst == 1) {
                    val rows = p.ints.size
                    for (r in 0 until rows) for (s in 0 until 16) {
                        val i = (bar * 16 + s) % len
                        if (p.ints[r].getOrElse(i) { 0 } > 0) {
                            val yy = y0 + h * (r + 0.5f) / rows
                            c.drawCircle(x0 + w * (s + 0.5f) / 16f, yy, max(dp(1f), min(w / 40f, h / rows / 2f)), fill)
                        }
                    }
                } else {
                    val gate = p.bools.getOrNull(0) ?: return
                    val semi = p.ints.getOrNull(0) ?: return
                    for (s in 0 until 16) {
                        val i = (bar * 16 + s) % len
                        if (!gate.getOrElse(i) { false }) continue
                        val yy = y1 - h * ((semi.getOrElse(i) { 0 } + 12).coerceIn(0, 36) / 36f)
                        c.drawRect(x0 + w * s / 16f + dp(0.5f), yy - dp(1.5f), x0 + w * (s + 1) / 16f - dp(0.5f), yy + dp(1.5f), fill)
                    }
                }
            }
            else -> {
                // ATMOS/EUKLID/HARMONIZER: Wellenlinie
                stroke.color = col; stroke.strokeWidth = dp(1.2f)
                path.reset()
                val cy = (y0 + y1) / 2
                for (k in 0..12) {
                    val x = x0 + w * k / 12f
                    val yy = cy + kotlin.math.sin((bar * 12 + k) * 0.7).toFloat() * h * 0.25f
                    if (k == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                }
                c.drawPath(path, stroke)
            }
        }
    }

    private fun drawAutoRow(c: Canvas, lane: Int, y0: Float, y1: Float) {
        val a = Engine.auto[lane]
        val res = Engine.AUTO_RES
        val i0 = max(0, (floor(firstBar) * res).toInt())
        val i1 = min(a.size - 1, ((firstBar + barsVis + 1) * res).toInt())
        stroke.color = COL_AUTO; stroke.strokeWidth = dp(1.6f)
        path.reset()
        var open = false
        for (i in i0..i1) {
            val v = a[i]
            val x = xOf(i.toFloat() / res)
            if (v.isNaN()) { open = false; continue }
            val y = y1 - (y1 - y0) * v.coerceIn(0f, 1f)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        c.drawPath(path, stroke)
    }

    private fun drawScroll(c: Canvas) {
        fill.color = Color.rgb(30, 31, 38)
        c.drawRoundRect(scrollBar, dp(6f), dp(6f), fill)
        val total = max(Engine.songBars + 8, barsVis).toFloat().coerceAtMost(Engine.ARR_MAX.toFloat())
        val x0 = scrollBar.left + scrollBar.width() * (firstBar / total)
        val x1 = scrollBar.left + scrollBar.width() * ((firstBar + barsVis) / total).coerceAtMost(1f)
        fill.color = Color.argb(150, 255, 255, 255)
        c.drawRoundRect(x0, scrollBar.top, max(x0 + dp(10f), x1), scrollBar.bottom, dp(6f), dp(6f), fill)
        text.textSize = dp(8f); text.color = Color.BLACK
        c.drawText("$barsVis Takte sichtbar · Zoom: zwei Finger", (x0 + x1) / 2, scrollBar.centerY() + dp(3f), text)
    }

    // ================================================================ Bedienung

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var mode = 0       // 1 Rahmen, 2 Block ziehen, 3 Malen, 4 Spuren scrollen, 5 Zeitleiste scrollen, 6 Pinch, 7 Automation malen
    private var rubber = false
    private var rx0 = 0f; private var ry0 = 0f; private var rx1 = 0f; private var ry1 = 0f
    private var dragDx = 0f
    private var paintVal = 0
    private var paintRow = -1
    private var lastTap = 0L; private var lastTapBar = -1; private var lastTapRow = -1
    private var pinch0 = 0f; private var pinchBars = 16; private var scroll0 = 0f; private var row0 = 0
    private var autoLastI = -1; private var autoLastV = 0f

    private fun rowAt(y: Float): Int {
        val rs = rows()
        val i = firstRow + floor((y - grid.top) / rowH).toInt()
        return if (i in rs.indices) rs[i] else -1
    }

    fun touch(e: MotionEvent) {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downT = SystemClock.uptimeMillis(); mode = 0; dragDx = 0f
                for (i in 0 until 16) if (tb[i].contains(x, y)) { toolbar(i); return }
                when {
                    secRow.contains(x, y) -> { sectionTap(barAt(x)); return }
                    ruler.contains(x, y) -> { cursorBar = barAt(x).coerceIn(0, Engine.ARR_MAX - 1); Engine.playFromBar = cursorBar; mode = 5; scroll0 = firstBar; haptic(); return }
                    scrollBar.contains(x, y) -> { mode = 5; scroll0 = firstBar; return }
                    names.contains(x, y) -> { mode = 4; row0 = firstRow; return }
                    grid.contains(x, y) -> gridDown(x, y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    rubber = false
                    mode = 6
                    pinch0 = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(20f))
                    pinchBars = barsVis
                    scroll0 = firstBar
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                1 -> { rx1 = x; ry1 = y }
                2 -> dragDx = Math.round((x - downX) / barW()) * barW()
                3 -> paintAt(x, y)
                4 -> firstRow = (row0 - ((y - downY) / rowH).toInt()).coerceAtLeast(0)
                5 -> {
                    if (ruler.contains(downX, downY)) { if (abs(x - downX) > dp(14f)) firstBar = (scroll0 - (x - downX) / barW()).coerceIn(0f, (Engine.ARR_MAX - barsVis).toFloat().coerceAtLeast(0f)) }
                    else firstBar = (scroll0 + (x - downX) / scrollBar.width() * max(Engine.songBars + 8, barsVis)).coerceIn(0f, (Engine.ARR_MAX - barsVis).toFloat().coerceAtLeast(0f))
                }
                6 -> if (e.pointerCount >= 2) {
                    val dd = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(20f))
                    val want = (pinchBars * pinch0 / dd)
                    barsVis = intArrayOf(4, 8, 16, 32, 64).minByOrNull { abs(it - want) } ?: 16
                    firstBar = firstBar.coerceIn(0f, (Engine.ARR_MAX - barsVis).toFloat().coerceAtLeast(0f))
                }
                7 -> autoPaint(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    1 -> finishRubber()
                    2 -> finishMove()
                }
                mode = 0; rubber = false; dragDx = 0f; paintRow = -1; autoLastI = -1
            }
        }
    }

    private fun gridDown(x: Float, y: Float) {
        val t = rowAt(y)
        val b = barAt(x)
        if (t < 0 || b !in 0 until Engine.ARR_MAX) return
        // Doppeltipp auf Instrument-Block → Noten bearbeiten
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 350 && lastTapBar == b && lastTapRow == t) {
            lastTap = 0
            val v = Engine.trkCell(t, b)
            if (isInst(t)) {
                val inst = t - Engine.TRK_INST
                onOpenInst?.invoke(inst, if (v > 0) v - 1 else InstState.curPat[inst])
                return
            }
        }
        lastTap = now; lastTapBar = b; lastTapRow = t
        if (isAuto(t) && tool != 0) { mode = 7; autoPaint(x, y); return }
        when (tool) {
            0 -> {
                if (selB0 >= 0 && b in selB0..selB1 && t in selRows) { mode = 2; return }
                mode = 1; rubber = true; rx0 = x; ry0 = y; rx1 = x; ry1 = y
            }
            else -> {
                mode = 3
                paintRow = t
                paintVal = if (tool == 2) 0 else if (Engine.trkCell(t, b) > 0 && !(isInst(t) && Engine.trkCell(t, b) != paintPat)) 0 else (if (isInst(t)) paintPat else 1)
                paintAt(x, y)
            }
        }
    }

    private fun paintAt(x: Float, y: Float) {
        val b = barAt(x)
        if (paintRow < 0 || b !in 0 until Engine.ARR_MAX) return
        if (Engine.trkCell(paintRow, b) != paintVal) {
            Engine.trkSet(paintRow, b, paintVal)
            if (paintVal > 0 && b >= Engine.songBars) Engine.songBars = ((b + 1 + 3) / 4 * 4).coerceIn(1, Engine.ARR_MAX)
        }
    }

    private fun autoPaint(x: Float, y: Float) {
        val t = rowAt(downY)
        if (!isAuto(t)) return
        val lane = t - Engine.TRK_AUTO
        val ri = rows().indexOf(t) - firstRow
        val y0 = grid.top + ri * rowH; val y1 = y0 + rowH - dp(2f)
        val i = (barAtF(x) * Engine.AUTO_RES).toInt().coerceIn(0, Engine.ARR_MAX * Engine.AUTO_RES - 1)
        val v = ((y1 - y) / (y1 - y0)).coerceIn(0f, 1f)
        val a = Engine.auto[lane]
        val erase = tool == 2
        val from = if (autoLastI >= 0) min(autoLastI, i) else i
        val to = if (autoLastI >= 0) max(autoLastI, i) else i
        for (k in from..to) {
            a[k] = if (erase) Float.NaN else if (autoLastI >= 0 && to > from) {
                val f = (k - autoLastI).toFloat() / (i - autoLastI).toFloat()
                autoLastV + (v - autoLastV) * f
            } else v
        }
        autoLastI = i; autoLastV = v
        val bar = i / Engine.AUTO_RES
        if (!erase && bar >= Engine.songBars) Engine.songBars = ((bar + 1 + 3) / 4 * 4).coerceIn(1, Engine.ARR_MAX)
    }

    private fun finishRubber() {
        val rs = rows()
        val ya = min(ry0, ry1); val yb = max(ry0, ry1)
        val i0 = (firstRow + floor((ya - grid.top) / rowH).toInt()).coerceIn(0, rs.size - 1)
        val i1 = (firstRow + floor((yb - grid.top) / rowH).toInt()).coerceIn(0, rs.size - 1)
        val b0 = barAt(min(rx0, rx1)).coerceIn(0, Engine.ARR_MAX - 1)
        val b1 = barAt(max(rx0, rx1)).coerceIn(0, Engine.ARR_MAX - 1)
        if (abs(rx1 - rx0) < dp(6f) && abs(ry1 - ry0) < dp(6f)) {
            // einfacher Tipp: den ganzen Block unter dem Finger wählen
            val t = rs[i0]
            val v = Engine.trkCell(t, b0)
            if (v <= 0) { selB0 = -1; selRows = emptyList(); cursorBar = b0; Engine.playFromBar = b0; return }
            var s = b0; while (s > 0 && Engine.trkCell(t, s - 1) == v) s--
            var en = b0; while (en + 1 < Engine.ARR_MAX && Engine.trkCell(t, en + 1) == v) en++
            selRows = listOf(t); selB0 = s; selB1 = en
            onHint?.invoke("Block gewählt (Takt ${s + 1}–${en + 1}) – ziehen verschiebt, KOPIEREN/DUPLIZ./LÖSCHEN wirken darauf")
            return
        }
        selRows = rs.subList(i0, i1 + 1).toList()
        selB0 = b0; selB1 = b1
        haptic()
        onHint?.invoke("${selRows.size} Spuren × ${b1 - b0 + 1} Takte gewählt – ziehen verschiebt den Block")
    }

    private fun finishMove() {
        val db = Math.round(dragDx / barW())
        if (db == 0 || selB0 < 0) return
        val nb0 = (selB0 + db).coerceIn(0, Engine.ARR_MAX - 1)
        val regs = selRows.map { it to Engine.regionCopy(it, it, selB0, selB1) }
        for (t in selRows) Engine.regionClear(t, t, selB0, selB1)
        for ((_, r) in regs) Engine.regionPaste(r, nb0, false)
        val len = selB1 - selB0
        selB0 = nb0; selB1 = min(Engine.ARR_MAX - 1, nb0 + len)
        haptic()
        onHint?.invoke("Block nach Takt ${selB0 + 1} verschoben")
    }

    private fun sectionTap(b: Int) {
        if (b !in 0 until Engine.ARR_MAX) return
        // liegt der Takt in einem Abschnitt? → ganzen Abschnitt als Block wählen
        var s = b
        while (s > 0 && Engine.arrSection[s] < 0) s--
        if (Engine.arrSection[s] >= 0 && b < Engine.songBars) {
            var e = s + 1
            while (e < Engine.songBars && Engine.arrSection[e] < 0) e++
            selRows = rows().filter { !isAuto(it) || Engine.autoLaneUsed(it - Engine.TRK_AUTO) }
            selB0 = s; selB1 = e - 1
            cursorBar = s; Engine.playFromBar = s
            haptic()
            onHint?.invoke("Abschnitt ${Engine.SECTION_NAMES[Engine.arrSection[s]]} (Takt ${s + 1}–$e) gewählt – ziehen verschiebt ihn, DUPLIZ. wiederholt ihn · ▣ BLOCK benennt um")
        } else {
            cursorBar = b; Engine.playFromBar = b
            onPickSection?.invoke(b)
        }
    }

    /** Abschnitt ab Takt [bar] setzen (k < 0 = entfernen). */
    fun setSection(bar: Int, k: Int) {
        if (bar !in 0 until Engine.ARR_MAX) return
        Engine.arrSection[bar] = k
        if (k >= 0 && bar >= Engine.songBars) Engine.songBars = ((bar + 4 + 3) / 4 * 4).coerceIn(1, Engine.ARR_MAX)
        host.invalidate()
    }

    private fun toolbar(i: Int) {
        haptic()
        when (i) {
            0 -> if (Engine.seqPlaying) Engine.transportStop() else {
                Engine.songMode = true
                Engine.playFromBar = cursorBar
                Engine.transportPlay(false)
            }
            1 -> if (Engine.songRec) { Engine.transportStop(); onHint?.invoke("Aufnahme beendet – Song hat jetzt ${Engine.songBars} Takte") } else {
                Engine.songRecStart(cursorBar)
                onHint?.invoke("● Song-Aufnahme ab Takt ${cursorBar + 1} (1 Takt Einzählen): Pads, Instrumente, ATMOS/EUKLID/HARMONIZER und FX-Fläche werden Takt für Takt geschrieben · ■ beendet")
            }
            2 -> { Engine.songMode = !Engine.songMode; onHint?.invoke(if (Engine.songMode) "Song-Modus an: ▶ spielt das Arrangement" else "Song-Modus aus: Pads und Instrumente wieder frei") }
            3 -> tool = 0
            4 -> { tool = 1; onHint?.invoke("✎ MALEN: Takte antippen/ziehen setzt Blöcke (Instrumente mit Pattern ${InstState.patLetter(paintPat - 1)}), auf Automations-Spuren Verlauf zeichnen") }
            5 -> tool = 2
            6 -> { paintPat = paintPat % 4 + 1; onHint?.invoke("Malen setzt Pattern ${InstState.patLetter(paintPat - 1)}") }
            7 -> close()
            8 -> copySel()
            9 -> paste(cursorBar)
            10 -> if (selB0 >= 0) { copySel(); paste(selB1 + 1); val len = selB1 - selB0; selB0 = selB1 + 1; selB1 = min(Engine.ARR_MAX - 1, selB0 + len) }
            11 -> if (selB0 >= 0) { for (t in selRows) Engine.regionClear(t, t, selB0, selB1); onHint?.invoke("Auswahl gelöscht") }
            12 -> if (selB0 >= 0) onPickSection?.invoke(selB0) else onHint?.invoke("Erst einen Bereich wählen (⬚ AUSWAHL, Rahmen ziehen) – ▣ BLOCK macht daraus einen benannten Abschnitt")
            13 -> { Engine.barsInsert(cursorBar, 1); onHint?.invoke("Leerer Takt bei ${cursorBar + 1} eingefügt – alles danach rutscht nach hinten") }
            14 -> { Engine.barsInsert(cursorBar, -1); onHint?.invoke("Takt ${cursorBar + 1} entfernt – alles danach rückt nach") }
            15 -> onPickAuto?.invoke()
        }
        host.invalidate()
    }

    private fun copySel() {
        if (selB0 < 0) { onHint?.invoke("Nichts gewählt"); return }
        clip = selRows.map { it to Engine.regionCopy(it, it, selB0, selB1) }
        clipBars = selB1 - selB0 + 1
        onHint?.invoke("$clipBars Takte × ${selRows.size} Spuren kopiert – Takt-Leiste antippen, dann EINFÜGEN")
    }

    private fun paste(at: Int) {
        if (clip.isEmpty()) { onHint?.invoke("Zwischenablage leer – erst KOPIEREN"); return }
        for ((_, r) in clip) Engine.regionPaste(r, at, false)
        selRows = clip.map { it.first }; selB0 = at; selB1 = min(Engine.ARR_MAX - 1, at + clipBars - 1)
        onHint?.invoke("Eingefügt ab Takt ${at + 1}")
    }
}
