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
import kotlin.math.sin

/**
 * Szenen-Launcher wie die Session-Ansicht von Ableton: Zeilen = Szenen, Spalten = Spuren (Pads mit Loop,
 * ACID, DRUM, SYNTH 1–3). ▶ links startet eine Zeile auf der nächsten Eins.
 */
class LauncherPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bStop = RectF(); private val bAdd = RectF(); private val bPlay = RectF()
    private val grid = RectF()
    private var cols: List<Int> = emptyList()   // Spalten-Index in Engine.launch (0..11 Pads, 12.. Instrumente)
    private var colW = 0f
    private var rowH = 0f
    private val launchW get() = dp(54f)
    private var downAt = 0L
    private var downRow = -1
    private val PAT_COLS = intArrayOf(Color.rgb(90, 190, 255), Color.rgb(120, 230, 140), Color.rgb(255, 190, 70), Color.rgb(240, 120, 200))

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    private fun usedCols(): List<Int> {
        val out = ArrayList<Int>()
        for (p in 0 until 12) if (Engine.hasLoop(p) || (0 until Engine.LROWS).any { Engine.launchUsed[it] && Engine.launch[it][p] > 0 }) out.add(p)
        for (i in 0 until InstState.INSTS) if ((0 until InstState.PATS).any { InstState.patHasNotes(i, it) }) out.add(12 + i)
        return out
    }

    private fun colName(c: Int): String = if (c < 12) (Engine.padName(c)?.take(6) ?: "P${c + 1}") else InstState.instNames[c - 12].replace("SYNTH ", "SYN")

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(8f)
        val bh = dp(36f)
        val y = box.top + m
        bClose.set(box.right - m - dp(44f), y, box.right - m, y + bh)
        bPlay.set(bClose.left - dp(6f) - dp(48f), y, bClose.left - dp(6f), y + bh)
        bStop.set(bPlay.left - dp(6f) - dp(84f), y, bPlay.left - dp(6f), y + bh)
        bAdd.set(box.left + m, box.bottom - m - dp(40f), box.right - m, box.bottom - m)
        grid.set(box.left + m, y + bh + dp(34f), box.right - m, bAdd.top - dp(8f))
        cols = usedCols()
        colW = if (cols.isEmpty()) 0f else (grid.width() - launchW - dp(4f)) / cols.size
        rowH = grid.height() / Engine.LROWS
    }

    private fun cellRect(r: Int, ci: Int, out: RectF) {
        val x0 = grid.left + launchW + dp(4f) + ci * colW
        out.set(x0 + dp(2f), grid.top + r * rowH + dp(2f), x0 + colW - dp(2f), grid.top + (r + 1) * rowH - dp(2f))
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val t = System.nanoTime() / 1e9f
        val blink = 0.5f + 0.5f * sin(t * 9f)
        fill.shader = null
        fill.color = Color.argb(215, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(16, 17, 22)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = acc
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🎬 SZENEN-LAUNCHER", box.left + dp(12f), bClose.centerY() + dp(1f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bStop, if (Engine.launchStopQueued()) "■ STOPPT …" else "■ ALLE AUS", Engine.launchStopQueued(), Color.rgb(230, 70, 70))
        btn(c, bPlay, if (Engine.seqPlaying) "■" else "▶", Engine.seqPlaying, acc)
        btn(c, bClose, "✕", false, acc)
        btn(c, bAdd, "＋ Was gerade läuft als neue Szene speichern", false, acc)
        if (cols.isEmpty()) {
            text.textSize = dp(13f); text.color = Color.argb(200, 255, 255, 255)
            c.drawText("Noch keine Spuren – erst Pads aufnehmen/laden oder Instrumente bespielen", box.centerX(), grid.centerY(), text)
            host.postInvalidateOnAnimation()
            return
        }
        // Spaltenköpfe
        val tmp = RectF()
        for ((ci, col) in cols.withIndex()) {
            val x0 = grid.left + launchW + dp(4f) + ci * colW
            tmp.set(x0 + dp(2f), grid.top - dp(30f), x0 + colW - dp(2f), grid.top - dp(4f))
            val on = if (col < 12) Engine.hasLoop(col) && !Engine.isMuted(col) else InstState.instOn(col - 12)
            fill.color = if (on) blend(Color.rgb(34, 36, 44), acc, 0.6f) else Color.rgb(34, 36, 44)
            c.drawRoundRect(tmp, dp(6f), dp(6f), fill)
            text.color = Color.WHITE
            text.textSize = min(dp(10f), colW * 0.28f)
            c.drawText(fitLabel(text, colName(col), tmp.width() - dp(3f), text.textSize, dp(6f)), tmp.centerX(), tmp.centerY() + text.textSize * 0.36f, text)
        }
        for (r in 0 until Engine.LROWS) {
            val used = Engine.launchUsed[r]
            val cur = Engine.launchCur == r
            val q = Engine.launchIsQueued(r)
            // Start-Knopf
            tmp.set(grid.left, grid.top + r * rowH + dp(2f), grid.left + launchW, grid.top + (r + 1) * rowH - dp(2f))
            fill.color = when {
                q -> blend(Color.rgb(34, 36, 44), Color.rgb(70, 220, 110), 0.3f + 0.6f * blink)
                cur -> Color.rgb(70, 220, 110)
                used -> Color.rgb(44, 70, 54)
                else -> Color.rgb(28, 30, 36)
            }
            c.drawRoundRect(tmp, dp(8f), dp(8f), fill)
            text.color = if (cur) Color.BLACK else if (used) Color.WHITE else Color.argb(90, 255, 255, 255)
            text.textSize = min(dp(13f), rowH * 0.36f)
            c.drawText(if (used) "▶ ${r + 1}" else "${r + 1}", tmp.centerX(), tmp.centerY() + text.textSize * 0.36f, text)
            for ((ci, col) in cols.withIndex()) {
                cellRect(r, ci, tmp)
                val v = if (used) Engine.launch[r][col] else 0
                if (v > 0) {
                    fill.color = if (col < 12) blend(acc, Color.WHITE, 0.1f) else PAT_COLS[(v - 1).coerceIn(0, 3)]
                    if (!cur) fill.color = blend(Color.rgb(30, 32, 40), fill.color, 0.7f)
                    c.drawRoundRect(tmp, dp(6f), dp(6f), fill)
                    text.color = Color.BLACK
                    text.textSize = min(dp(12f), rowH * 0.34f)
                    c.drawText(if (col < 12) "▶" else InstState.patLetter(v - 1), tmp.centerX(), tmp.centerY() + text.textSize * 0.36f, text)
                } else {
                    fill.color = if (used) Color.rgb(30, 32, 40) else Color.rgb(22, 23, 28)
                    c.drawRoundRect(tmp, dp(6f), dp(6f), fill)
                    if (used) {
                        stroke.strokeWidth = dp(1f); stroke.color = Color.argb(60, 255, 255, 255)
                        c.drawRect(tmp.centerX() - dp(4f), tmp.centerY() - dp(4f), tmp.centerX() + dp(4f), tmp.centerY() + dp(4f), stroke)
                    }
                }
            }
        }
        host.postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(13f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(7f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            // langes Drücken auf ▶ = Zeile mit aktueller Lage überschreiben bzw. leeren
            if (downRow >= 0 && System.nanoTime() - downAt > 600_000_000L) {
                val r = downRow
                if (Engine.launchUsed[r]) { Engine.launchClear(r); onHint?.invoke("Szene ${r + 1} geleert") }
                else { Engine.launchCapture(r); onHint?.invoke("Szene ${r + 1}: aktuelle Lage gespeichert") }
                haptic()
            } else if (downRow >= 0) {
                val r = downRow
                if (Engine.launchUsed[r]) {
                    Engine.launchRow(r)
                    onHint?.invoke(if (Engine.seqPlaying && Engine.launchIsQueued(r)) "Szene ${r + 1} startet auf der nächsten Eins" else "Szene ${r + 1}")
                } else {
                    Engine.launchCapture(r)
                    onHint?.invoke("Szene ${r + 1}: aktuelle Lage gespeichert · ▶ startet sie · lange drücken leert")
                }
            }
            downRow = -1
            return
        }
        if (e.actionMasked != MotionEvent.ACTION_DOWN && e.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        haptic()
        when {
            bClose.contains(x, y) -> { close(); return }
            bPlay.contains(x, y) -> { if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay(); return }
            bStop.contains(x, y) -> { Engine.launchStopAll(); onHint?.invoke("Alle Spuren aus" + if (Engine.seqPlaying) " auf der nächsten Eins" else ""); return }
            bAdd.contains(x, y) -> {
                val r = (0 until Engine.LROWS).firstOrNull { !Engine.launchUsed[it] }
                if (r == null) onHint?.invoke("Alle 8 Szenen belegt – eine lange drücken leert sie")
                else { Engine.launchCapture(r); onHint?.invoke("Szene ${r + 1} gespeichert") }
                return
            }
        }
        if (!grid.contains(x, y) && y < grid.top - dp(32f)) return
        // Spaltenkopf: Spur sofort an/aus (Pads im Takt)
        if (y < grid.top && y >= grid.top - dp(32f)) {
            val ci = ((x - grid.left - launchW - dp(4f)) / colW).toInt()
            val col = cols.getOrNull(ci) ?: return
            if (col < 12) Engine.launchToggle(col) else InstState.setInstOn(col - 12, !InstState.instOn(col - 12))
            return
        }
        val r = ((y - grid.top) / rowH).toInt().coerceIn(0, Engine.LROWS - 1)
        if (x < grid.left + launchW) { downRow = r; downAt = System.nanoTime(); return }
        val ci = ((x - grid.left - launchW - dp(4f)) / colW).toInt()
        val col = cols.getOrNull(ci) ?: return
        if (!Engine.launchUsed[r]) { Engine.launchCapture(r); java.util.Arrays.fill(Engine.launch[r], 0) }
        val v = Engine.launch[r][col]
        Engine.launch[r][col] = if (col < 12) (if (v > 0) 0 else 1) else {
            val i2 = col - 12
            if (v == 0) InstState.curPat[i2] + 1 else (v + 1) % (InstState.PATS + 1)
        }
    }
}
