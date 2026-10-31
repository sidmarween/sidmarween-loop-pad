package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SONG-EDITOR – Arrangement wie in einer DAW, für den Finger gebaut.
 *
 * Aufbau (oben → unten): Transport · Abschnitte (Intro, Hauptteil …) · Takt-Leiste · Spuren · Übersicht · Aktions-Leiste.
 * Jede Quelle hat eine Spur: Pads (Wellenform/Schläge), ACID/DRUM/SYNTH (Noten des Patterns), ATMOS/EUKLID/HARMONIZER,
 * darunter aufklappbare Automations-Kurven und eine MASTER-Spur.
 *
 * Bedienung ohne Werkzeug-Modi:
 *  - Block antippen = wählen (mit Griffen links/rechts). Gewählten Block ziehen = verschieben, Griff ziehen = Länge.
 *  - Doppeltipp auf eine leere Stelle = neuer Block (4 Takte). Doppeltipp auf Instrument-Block = Noten bearbeiten.
 *  - Lange drücken + ziehen = Rahmen über mehrere Spuren/Takte.
 *  - Abschnitt oben antippen = ganzer Abschnitt gewählt (verschieben, duplizieren, Länge …).
 *  - Automations-Spur: einfach mit dem Finger zeichnen.
 *  - Zwei Finger: Zeitleiste schieben und zoomen. Spurnamen ziehen: Spuren rollen.
 *  - Unten erscheinen immer genau die Knöpfe, die zur Auswahl passen. ↶ macht jeden Schritt rückgängig.
 */
class SongEditor(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    var onOpenInst: ((Int, Int) -> Unit)? = null
    var onPickSection: ((Int) -> Unit)? = null
    var onPickAuto: (() -> Unit)? = null
    /** Weitere Spuren einblenden (MainActivity zeigt die Liste). */
    var onPickTracks: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val path = Path()

    // ---------------------------------------------------------------- Farben
    private val BG = Color.rgb(12, 13, 17)
    private val PANEL = Color.rgb(20, 21, 27)
    private val LANE_A = Color.rgb(17, 18, 23)
    private val LANE_B = Color.rgb(21, 22, 28)
    private val INK = Color.rgb(235, 236, 240)
    private val DIM = Color.argb(150, 235, 236, 240)
    private val SEL = Color.rgb(110, 200, 255)
    private val COL_PAD = intArrayOf(Color.rgb(255, 150, 70), Color.rgb(255, 120, 110), Color.rgb(250, 190, 90), Color.rgb(255, 160, 140))
    private val PAT_COL = intArrayOf(Color.rgb(90, 190, 255), Color.rgb(120, 230, 140), Color.rgb(255, 200, 80), Color.rgb(240, 120, 200))
    private val COL_X = intArrayOf(Color.rgb(140, 170, 255), Color.rgb(255, 170, 90), Color.rgb(255, 120, 190))
    private val COL_AUTO = Color.rgb(250, 225, 110)
    private val SEC_COLS = intArrayOf(Color.rgb(90, 160, 255), Color.rgb(120, 220, 140), Color.rgb(255, 200, 80), Color.rgb(255, 95, 95), Color.rgb(180, 140, 255), Color.rgb(90, 220, 220), Color.rgb(240, 130, 200))

    // ---------------------------------------------------------------- Ansicht
    private var firstBar = 0f
    private var barW = 0f              // Pixel je Takt (Zoom)
    private var scrollY = 0f
    private var follow = true
    var cursorBar = 0
        private set
    /** Instrument-/Pad-Spuren mit aufgeklappter Automation. */
    private val autoOpen = HashSet<Int>()
    /** Zusätzlich eingeblendete Spuren (leere Pads, Instrumente ohne Noten). */
    val extraShown = HashSet<Int>()

    // ---------------------------------------------------------------- Auswahl
    private var selTracks: List<Int> = emptyList()
    private var selB0 = -1
    private var selB1 = -1
    /** Auswahl stammt von einem Abschnitt (dann gibt es Abschnitts-Knöpfe). */
    private var selSection = false
    private class ClipData(val track: Int, val vals: IntArray, val breaks: BooleanArray, val auto: FloatArray?)
    private var clip: List<ClipData> = emptyList()
    private var clipBars = 0

    // ---------------------------------------------------------------- Rückgängig
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private fun snap() {
        undo.addLast(Engine.arrToJson().toString())
        while (undo.size > 40) undo.removeFirst()
        redo.clear()
    }
    private fun doUndo() {
        val s = undo.removeLastOrNull() ?: run { hint("Nichts rückgängig zu machen"); return }
        redo.addLast(Engine.arrToJson().toString())
        restore(s)
        hint("↶ Rückgängig")
    }
    private fun doRedo() {
        val s = redo.removeLastOrNull() ?: return
        undo.addLast(Engine.arrToJson().toString())
        restore(s)
        hint("↷ Wiederholt")
    }
    private fun restore(s: String) {
        val mode = Engine.songMode
        try { Engine.arrFromJson(org.json.JSONObject(s)) } catch (ignored: Exception) {}
        Engine.songMode = mode
        clearSel()
    }

    private fun hint(m: String) { onHint?.invoke(m) }

    fun open() {
        isOpen = true
        cursorBar = Engine.playFromBar
        if (barW <= 0f) barW = 0f
    }
    fun close() { isOpen = false; clearSel() }

    // ================================================================ Spuren

    private enum class Kind { PAD, INST, X, AUTO, MASTER }
    /** Eine Zeile: [t] = Spur-ID der Engine (Automation: TRK_AUTO + Lane), MASTER = −1. */
    private class Row(val kind: Kind, val t: Int, val h: Float, val owner: Int = -1)

    /** Automations-Lanes, die zu einer Spur gehören. */
    private fun lanesOf(t: Int): IntArray = when {
        t == Engine.TRK_INST -> intArrayOf(2, 3)
        t == Engine.TRK_INST + 1 -> intArrayOf(4)
        t in Engine.TRK_INST + 2 until Engine.TRK_X -> (t - Engine.TRK_INST - 2).let { k -> intArrayOf(5 + 2 * k, 6 + 2 * k) }
        t == -1 -> intArrayOf(0, 1, 11)
        else -> IntArray(0)
    }

    private fun trackUsed(t: Int): Boolean = (0 until Engine.ARR_MAX).any { Engine.trkCell(t, it) > 0 }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        val rh = dp(54f); val ah = dp(40f)
        fun addTrack(kind: Kind, t: Int) {
            out.add(Row(kind, t, rh))
            if (t in autoOpen || (t == -1 && autoOpen.contains(-1))) for (l in lanesOf(t)) out.add(Row(Kind.AUTO, Engine.TRK_AUTO + l, ah, t))
        }
        for (p in 0 until Engine.TRK_PADS) if (Engine.hasLoop(p) || trackUsed(p) || p in extraShown) addTrack(Kind.PAD, p)
        for (i in 0 until InstState.INSTS) {
            val t = Engine.TRK_INST + i
            val has = (0 until InstState.PATS).any { InstState.patHasNotes(i, it) }
            if (has || trackUsed(t) || t in extraShown || i < 2) addTrack(Kind.INST, t)
        }
        for (k in 0 until Engine.ARR_X) { val t = Engine.TRK_X + k; if (trackUsed(t) || t in extraShown || k == 0) addTrack(Kind.X, t) }
        addTrack(Kind.MASTER, -1)
        return out
    }

    private fun trackName(t: Int): String = when {
        t == -1 -> "MASTER"
        t < Engine.TRK_PADS -> Engine.padName(t)?.take(14) ?: "Pad ${t + 1}"
        t < Engine.TRK_X -> InstState.instNames[t - Engine.TRK_INST].let { if (it == "ACID") "ACID 303" else if (it == "DRUM") "DRUM 909" else it }
        t < Engine.TRK_AUTO -> Engine.ARR_X_NAMES[t - Engine.TRK_X]
        else -> Engine.AUTO_NAMES[t - Engine.TRK_AUTO]
    }

    private fun trackSub(t: Int): String = when {
        t == -1 -> "FX-Fläche · Lautstärke"
        t < Engine.TRK_PADS -> "Pad ${t + 1} · " + when { !Engine.hasLoop(t) -> "leer"; Engine.isOneShot(t) -> "⚡ Schläge"; else -> String.format(java.util.Locale.GERMANY, "∞ %.0f Takt", Engine.padBarsLen(t)) }
        t < Engine.TRK_X -> "Pattern " + InstState.patLetter(InstState.curPat[t - Engine.TRK_INST]) + " · Doppeltipp = Noten"
        t < Engine.TRK_AUTO -> "an/aus im Song"
        else -> "zeichnen"
    }

    private fun trackIcon(t: Int): String = when {
        t == -1 -> "◎"
        t < Engine.TRK_PADS -> if (Engine.isOneShot(t)) "⚡" else "∿"
        t == Engine.TRK_INST -> "🔊"
        t == Engine.TRK_INST + 1 -> "🥁"
        t < Engine.TRK_X -> "🎹"
        t == Engine.TRK_X -> "🌌"
        t == Engine.TRK_X + 1 -> "⬡"
        t == Engine.TRK_X + 2 -> "🎤"
        else -> "〰"
    }

    private fun trackCol(t: Int, v: Int = 1): Int = when {
        t == -1 -> COL_AUTO
        t < Engine.TRK_PADS -> COL_PAD[t % COL_PAD.size]
        t < Engine.TRK_X -> PAT_COL[(v - 1).coerceIn(0, 3)]
        t < Engine.TRK_AUTO -> COL_X[t - Engine.TRK_X]
        else -> COL_AUTO
    }

    private fun isInst(t: Int) = t in Engine.TRK_INST until Engine.TRK_X
    private fun isAuto(t: Int) = t >= Engine.TRK_AUTO
    private fun isClipTrack(t: Int) = t in 0 until Engine.TRK_AUTO

    // ---- Blöcke (zusammenhängende Takte mit gleichem Wert, ohne Grenze dazwischen)
    private fun brk(t: Int, b: Int): Boolean = t in 0 until Engine.arrBreak.size && b in 0 until Engine.ARR_MAX && Engine.arrBreak[t][b]
    private fun setBrk(t: Int, b: Int, v: Boolean) { if (t in 0 until Engine.arrBreak.size && b in 0 until Engine.ARR_MAX) Engine.arrBreak[t][b] = v }

    /** Block, der Takt [b] auf Spur [t] enthält: (Anfang, Ende inkl.) oder null. */
    private fun clipAt(t: Int, b: Int): IntArray? {
        if (!isClipTrack(t) || b !in 0 until Engine.ARR_MAX) return null
        val v = Engine.trkCell(t, b)
        if (v <= 0) return null
        var s = b
        while (s > 0 && !brk(t, s) && Engine.trkCell(t, s - 1) == v) s--
        var e = b
        while (e + 1 < Engine.ARR_MAX && !brk(t, e + 1) && Engine.trkCell(t, e + 1) == v) e++
        return intArrayOf(s, e)
    }

    // ================================================================ Layout

    private val box = RectF()
    private val trans = RectF()
    private val tBtn = Array(8) { RectF() }
    private val secRow = RectF()
    private val ruler = RectF()
    private val heads = RectF()
    private val grid = RectF()
    private val mini = RectF()
    private val act = RectF()
    private val aBtn = Array(8) { RectF() }
    private var aCount = 0
    private val aLabels = Array(8) { "" }
    private val aIds = IntArray(8)
    private val aOn = BooleanArray(8)
    private val addSec = RectF()
    private val empty = RectF()
    private val emptyBtn = Array(3) { RectF() }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(6f)
        val headW = min(dp(104f), box.width() * 0.27f)
        trans.set(box.left + pad, box.top + pad, box.right - pad, box.top + pad + dp(42f))
        val n = 8
        val gw = (trans.width() - (n - 1) * dp(4f)) / n
        for (i in 0 until n) tBtn[i].set(trans.left + i * (gw + dp(4f)), trans.top, trans.left + i * (gw + dp(4f)) + gw, trans.bottom)
        secRow.set(box.left + pad + headW, trans.bottom + dp(6f), box.right - pad, trans.bottom + dp(6f) + dp(30f))
        ruler.set(secRow.left, secRow.bottom + dp(2f), secRow.right, secRow.bottom + dp(2f) + dp(22f))
        act.set(box.left + pad, box.bottom - pad - dp(50f), box.right - pad, box.bottom - pad)
        mini.set(box.left + pad, act.top - dp(6f) - dp(26f), box.right - pad, act.top - dp(6f))
        grid.set(ruler.left, ruler.bottom + dp(4f), ruler.right, mini.top - dp(6f))
        heads.set(box.left + pad, grid.top, grid.left - dp(4f), grid.bottom)
        if (barW <= 0f) barW = (grid.width() / 8f).coerceAtLeast(dp(28f))
        barW = barW.coerceIn(dp(10f), grid.width() / 2f)
    }

    private fun xOf(bar: Float) = grid.left + (bar - firstBar) * barW
    private fun barAtF(x: Float) = firstBar + (x - grid.left) / barW
    private fun barAt(x: Float) = floor(barAtF(x)).toInt()
    private fun visBars() = grid.width() / barW
    private fun maxFirst() = max(0f, max(Engine.songBars + 4f, visBars()).coerceAtMost(Engine.ARR_MAX.toFloat()) - visBars())

    // ================================================================ Zeichnen

    private fun rr(c: Canvas, r: RectF, rad: Float, col: Int) { fill.shader = null; fill.color = col; c.drawRoundRect(r, rad, rad, fill) }

    private fun button(c: Canvas, r: RectF, label: String, on: Boolean, col: Int, sub: String? = null) {
        rr(c, r, dp(9f), if (on) col else Color.rgb(32, 34, 43))
        stroke.strokeWidth = dp(1f)
        stroke.color = if (on) col else Color.argb(70, 255, 255, 255)
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.color = if (on) Color.BLACK else INK
        text.textSize = min(dp(12f), r.height() * 0.34f)
        val ty = if (sub != null) r.centerY() - dp(1f) else r.centerY() + text.textSize * 0.36f
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(7f)), r.centerX(), ty, text)
        if (sub != null) {
            text.textSize = dp(8f); text.color = if (on) Color.argb(170, 0, 0, 0) else DIM
            c.drawText(fitLabel(text, sub, r.width() - dp(6f), dp(8f), dp(6f)), r.centerX(), ty + dp(11f), text)
        }
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.shader = null
        fill.color = BG
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        // mitlaufen
        val pp = playPos()
        if (follow && pp >= 0f) {
            if (pp > firstBar + visBars() - 1f || pp < firstBar) firstBar = (floor(pp) - 0.5f).coerceIn(0f, maxFirst())
        }
        firstBar = firstBar.coerceIn(0f, maxFirst())
        drawTransport(c)
        drawSections(c)
        drawRuler(c, pp)
        if (Engine.songBars <= 0 || isEmptySong()) drawEmpty(c) else drawTracks(c, pp)
        drawMini(c, pp)
        drawActions(c)
        if (Engine.seqPlaying || dragging()) host.postInvalidateOnAnimation()
    }

    private fun isEmptySong(): Boolean = (0 until Engine.TRK_AUTO).none { trackUsed(it) } && (0 until Engine.AUTO_LANES).none { Engine.autoLaneUsed(it) } && Engine.arrSection.none { it >= 0 }

    private fun drawTransport(c: Canvas) {
        val playing = Engine.seqPlaying
        val rec = Engine.songRec
        button(c, tBtn[0], "✕", false, SEL, "zurück")
        button(c, tBtn[1], "↶", false, SEL, "rückgängig")
        button(c, tBtn[2], "↷", false, SEL, "wiederholen")
        button(c, tBtn[3], "⏮", false, SEL, "an den Anfang")
        button(c, tBtn[4], if (playing) "■" else "▶", playing, Color.rgb(130, 230, 160), if (playing) "Stopp" else "ab Takt ${cursorBar + 1}")
        button(c, tBtn[5], "●", rec, Color.rgb(255, 80, 80), if (rec) "nimmt auf" else "Song aufnehmen")
        val pos = playPos()
        val posTxt = if (pos >= 0f) "T ${floor(pos).toInt() + 1}.${(((pos - floor(pos)) * 4).toInt() + 1)}" else "T ${cursorBar + 1}"
        button(c, tBtn[6], posTxt, false, SEL, "${Engine.songBars} Takte")
        button(c, tBtn[7], if (follow) "⇥" else "⇥", follow, SEL, if (follow) "folgt" else "frei")
    }

    private fun drawSections(c: Canvas) {
        text.textSize = dp(9f)
        bold.textSize = dp(9f); bold.color = DIM
        c.drawText("ABSCHNITTE", box.left + dp(8f), secRow.centerY() + dp(3f), bold)
        rr(c, secRow, dp(8f), PANEL)
        c.save(); c.clipRect(secRow)
        val n = Engine.songBars
        var b = 0
        var any = false
        while (b < n) {
            val k = Engine.arrSection[b]
            var e = b + 1
            while (e < n && Engine.arrSection[e] < 0) e++
            if (k >= 0) {
                any = true
                val x0 = xOf(b.toFloat()) + dp(1.5f); val x1 = xOf(e.toFloat()) - dp(1.5f)
                val col = SEC_COLS[k % SEC_COLS.size]
                val selected = selSection && selB0 == b && selB1 == e - 1
                tmp.set(x0, secRow.top + dp(3f), x1, secRow.bottom - dp(3f))
                rr(c, tmp, dp(7f), if (selected) col else Color.argb(170, Color.red(col), Color.green(col), Color.blue(col)))
                if (selected) { stroke.color = Color.WHITE; stroke.strokeWidth = dp(2f); c.drawRoundRect(tmp, dp(7f), dp(7f), stroke) }
                bold.color = Color.BLACK; bold.textSize = dp(10f)
                c.drawText(fitLabel(bold, Engine.SECTION_NAMES[k] + " · ${e - b}", x1 - x0 - dp(8f), dp(10f), dp(6.5f)), x0 + dp(5f), secRow.centerY() + dp(3.5f), bold)
            }
            b = e
        }
        // „+ Abschnitt“ hinter dem Song-Ende
        val xe = xOf(n.toFloat())
        addSec.set(xe + dp(4f), secRow.top + dp(3f), xe + dp(4f) + dp(96f), secRow.bottom - dp(3f))
        stroke.color = Color.argb(140, 255, 255, 255); stroke.strokeWidth = dp(1.2f)
        c.drawRoundRect(addSec, dp(7f), dp(7f), stroke)
        text.color = INK; text.textSize = dp(10f)
        c.drawText("＋ Abschnitt", addSec.centerX(), addSec.centerY() + dp(3.5f), text)
        if (!any) {
            text.color = DIM; text.textSize = dp(9f); text.textAlign = Paint.Align.LEFT
            val tx = if (addSec.left - secRow.left > dp(150f)) secRow.left + dp(6f) else addSec.right + dp(8f)
            c.drawText(fitLabel(text, if (tx > addSec.left) "← Intro, Hauptteil, Ending …" else "Intro, Hauptteil, Ending … →", secRow.right - tx - dp(4f), dp(9f), dp(6.5f)), tx, secRow.centerY() + dp(3f), text)
            text.textAlign = Paint.Align.CENTER
        }
        c.restore()
    }

    private val tmp = RectF()

    private fun drawRuler(c: Canvas, pp: Float) {
        rr(c, ruler, dp(6f), Color.rgb(26, 27, 34))
        c.save(); c.clipRect(ruler.left, ruler.top, ruler.right, grid.bottom)
        val b0 = floor(firstBar).toInt()
        val step = when { barW < dp(16f) -> 8; barW < dp(30f) -> 4; barW < dp(48f) -> 2; else -> 1 }
        text.textSize = dp(9.5f); text.textAlign = Paint.Align.LEFT
        for (b in b0..b0 + visBars().toInt() + 1) {
            val x = xOf(b.toFloat())
            stroke.strokeWidth = dp(1f)
            stroke.color = if (b % 4 == 0) Color.argb(150, 255, 255, 255) else Color.argb(50, 255, 255, 255)
            c.drawLine(x, ruler.bottom - (if (b % 4 == 0) dp(10f) else dp(5f)), x, ruler.bottom, stroke)
            if (b % step == 0) { text.color = if (b >= Engine.songBars) Color.argb(70, 255, 255, 255) else INK; c.drawText("${b + 1}", x + dp(if (b == cursorBar) 9f else 3f), ruler.top + dp(11f), text) }
        }
        text.textAlign = Paint.Align.CENTER
        // Startmarke
        val xc = xOf(cursorBar.toFloat())
        fill.color = Color.rgb(130, 230, 160)
        path.reset(); path.moveTo(xc, ruler.bottom); path.lineTo(xc - dp(7f), ruler.top + dp(4f)); path.lineTo(xc + dp(7f), ruler.top + dp(4f)); path.close()
        c.drawPath(path, fill)
        // Song-Ende (ziehbar)
        val xe = xOf(Engine.songBars.toFloat())
        if (Engine.songBars <= 0 || isEmptySong()) { c.restore(); return }
        stroke.color = Color.rgb(255, 95, 95); stroke.strokeWidth = dp(2f)
        c.drawLine(xe, ruler.top, xe, grid.bottom, stroke)
        tmp.set(xe - dp(9f), ruler.top + dp(2f), xe + dp(9f), ruler.bottom - dp(2f))
        rr(c, tmp, dp(4f), Color.rgb(255, 95, 95))
        text.color = Color.BLACK; text.textSize = dp(8f)
        c.drawText("END", xe, ruler.centerY() + dp(3f), text)
        c.restore()
    }

    private fun playPos(): Float {
        if (!Engine.seqPlaying || !Engine.songMode) return -1f
        val ap = Engine.autoPos
        return if (ap >= 0f) ap / Engine.AUTO_RES else Engine.songBar().toFloat()
    }

    private fun drawEmpty(c: Canvas) {
        empty.set(grid.left - heads.width() * 0f, grid.top + dp(10f), grid.right, min(grid.bottom, grid.top + dp(250f)))
        empty.left = heads.left
        rr(c, empty, dp(14f), PANEL)
        bold.color = INK; bold.textSize = dp(16f)
        c.drawText("Noch kein Song angelegt", empty.left + dp(16f), empty.top + dp(30f), bold)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(11f); text.color = DIM
        c.drawText(fitLabel(text, "Wähle einen Start – alles lässt sich danach verschieben und ändern:", empty.width() - dp(32f), dp(11f), dp(8f)), empty.left + dp(16f), empty.top + dp(50f), text)
        text.textAlign = Paint.Align.CENTER
        val bh = dp(52f)
        val labels = arrayOf("✦  Song-Gerüst bauen" to "Intro 8 · Hauptteil 16 · Break 8 · Drop 16 · Ending 8 – aus deinen Pads & Instrumenten",
            "●  Live aufnehmen" to "spiel einfach – jeder Takt landet im Song",
            "🎬  Aus Szenen bauen" to "deine gespeicherten Szenen nacheinander")
        for (i in 0 until 3) {
            emptyBtn[i].set(empty.left + dp(16f), empty.top + dp(64f) + i * (bh + dp(8f)), empty.right - dp(16f), empty.top + dp(64f) + i * (bh + dp(8f)) + bh)
            if (emptyBtn[i].bottom > empty.bottom) empty.bottom = emptyBtn[i].bottom + dp(14f)
        }
        rr(c, empty, dp(14f), PANEL)
        bold.color = INK; bold.textSize = dp(16f)
        c.drawText("Noch kein Song angelegt", empty.left + dp(16f), empty.top + dp(30f), bold)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(11f); text.color = DIM
        c.drawText(fitLabel(text, "Wähle einen Start – alles lässt sich danach verschieben und ändern:", empty.width() - dp(32f), dp(11f), dp(8f)), empty.left + dp(16f), empty.top + dp(50f), text)
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until 3) {
            val r = emptyBtn[i]
            val col = intArrayOf(Color.rgb(130, 230, 160), Color.rgb(255, 110, 110), SEL)[i]
            rr(c, r, dp(10f), Color.argb(45, Color.red(col), Color.green(col), Color.blue(col)))
            stroke.color = col; stroke.strokeWidth = dp(1.4f); c.drawRoundRect(r, dp(10f), dp(10f), stroke)
            bold.color = INK; bold.textSize = dp(13f)
            c.drawText(labels[i].first, r.left + dp(12f), r.top + dp(21f), bold)
            text.textAlign = Paint.Align.LEFT; text.textSize = dp(9.5f); text.color = DIM
            c.drawText(fitLabel(text, labels[i].second, r.width() - dp(24f), dp(9.5f), dp(7f)), r.left + dp(12f), r.top + dp(39f), text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    private val peaksCache = HashMap<Int, Pair<Int, FloatArray>>()
    private fun peaks(p: Int): FloatArray? {
        val id = Engine.padDataId(p)
        val ch = peaksCache[p]
        if (ch != null && ch.first == id) return ch.second
        val bars = max(1, ceil(Engine.padBarsLen(p).toDouble()).toInt())
        val pk = Engine.padPeaks(p, 64 * bars) ?: return null
        var m = 1e-4f; for (v in pk) m = max(m, v)
        for (i in pk.indices) pk[i] /= m
        peaksCache[p] = id to pk
        return pk
    }

    private fun drawTracks(c: Canvas, pp: Float) {
        val rs = rows()
        val total = rs.sumOf { it.h.toDouble() }.toFloat() + dp(52f)
        scrollY = scrollY.coerceIn(0f, max(0f, total - grid.height()))
        c.save()
        c.clipRect(heads.left, grid.top, grid.right, grid.bottom)
        var y = grid.top - scrollY
        val b0 = floor(firstBar).toInt()
        val b1 = min(Engine.ARR_MAX, b0 + visBars().toInt() + 2)
        for ((ri, row) in rs.withIndex()) {
            val y0 = y; val y1 = y + row.h - dp(3f)
            y += row.h
            if (y1 < grid.top || y0 > grid.bottom) continue
            // Spurkopf
            tmp.set(heads.left, y0, heads.right, y1)
            val t = row.t
            val auto = row.kind == Kind.AUTO
            rr(c, tmp, dp(8f), if (auto) Color.rgb(24, 23, 18) else PANEL)
            val col = trackCol(t)
            fill.color = col
            c.drawRoundRect(heads.left, y0, heads.left + dp(4f), y1, dp(2f), dp(2f), fill)
            if (auto) {
                text.textAlign = Paint.Align.LEFT; text.textSize = dp(9f); text.color = COL_AUTO
                c.drawText(fitLabel(text, "〰 " + trackName(t), heads.width() - dp(12f), dp(9f), dp(6.5f)), heads.left + dp(10f), y0 + dp(16f), text)
                text.color = DIM; text.textSize = dp(8f)
                c.drawText(if (Engine.autoLaneUsed(t - Engine.TRK_AUTO)) "antippen: löschen" else "mit dem Finger zeichnen", heads.left + dp(10f), y0 + dp(28f), text)
                text.textAlign = Paint.Align.CENTER
            } else {
                val tt = if (row.kind == Kind.MASTER) -1 else t
                bold.color = INK; bold.textSize = dp(11f)
                c.drawText(fitLabel(bold, trackIcon(tt) + " " + trackName(tt), heads.width() - dp(30f), dp(11f), dp(7f)), heads.left + dp(10f), y0 + dp(19f), bold)
                text.textAlign = Paint.Align.LEFT; text.textSize = dp(8.5f); text.color = DIM
                c.drawText(fitLabel(text, trackSub(tt), heads.width() - dp(14f), dp(8.5f), dp(6f)), heads.left + dp(10f), y0 + dp(34f), text)
                text.textAlign = Paint.Align.CENTER
                if (lanesOf(tt).isNotEmpty()) {
                    val open = tt in autoOpen
                    val used = lanesOf(tt).any { Engine.autoLaneUsed(it) }
                    text.textSize = dp(10f); text.color = if (used) COL_AUTO else DIM
                    c.drawText(if (open) "▾〰" else "▸〰", heads.right - dp(14f), y1 - dp(7f), text)
                }
            }
            // Spurfläche
            fill.color = if (ri % 2 == 0) LANE_A else LANE_B
            c.drawRect(grid.left, y0, grid.right, y1, fill)
            // Abschnitts-Tönung und Taktlinien
            drawGridLines(c, y0, y1, b0, b1)
            if (auto) { drawAutoRow(c, t - Engine.TRK_AUTO, y0, y1); continue }
            if (row.kind == Kind.MASTER) {
                text.color = DIM; text.textSize = dp(9f)
                c.drawText(if (autoOpen.contains(-1)) "Automation darunter: FX X / FX Y / Lautstärke" else "▸〰 links antippen: FX-Fläche und Lautstärke automatisieren", (grid.left + grid.right) / 2, (y0 + y1) / 2 + dp(3f), text)
                continue
            }
            // Blöcke
            var b = max(0, b0 - 64).coerceAtLeast(0)
            b = max(0, b0)
            // Anfang des ersten sichtbaren Blocks suchen
            clipAt(t, b)?.let { b = it[0] }
            while (b < b1) {
                val v = Engine.trkCell(t, b)
                if (v <= 0) { b++; continue }
                val cl = clipAt(t, b) ?: intArrayOf(b, b)
                drawClip(c, t, v, cl[0], cl[1], y0, y1)
                b = cl[1] + 1
            }
        }
        // „+ Spur“ unter allen Spuren
        val ya = y + dp(6f)
        tmp.set(heads.left, ya, heads.right, ya + dp(36f))
        if (ya < grid.bottom) {
            stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = dp(1.2f)
            c.drawRoundRect(tmp, dp(8f), dp(8f), stroke)
            text.color = INK; text.textSize = dp(10f)
            c.drawText("＋ Spur", tmp.centerX(), tmp.centerY() + dp(3.5f), text)
            addTrack.set(tmp)
        } else addTrack.setEmpty()
        // Gummiband
        if (rubber) {
            fill.color = Color.argb(40, 110, 200, 255)
            c.drawRect(min(rx0, rx1), min(ry0, ry1), max(rx0, rx1), max(ry0, ry1), fill)
            stroke.color = SEL; stroke.strokeWidth = dp(1.2f)
            c.drawRect(min(rx0, rx1), min(ry0, ry1), max(rx0, rx1), max(ry0, ry1), stroke)
        }
        // Abspielkopf
        if (pp >= 0f) {
            val x = xOf(pp)
            if (x in grid.left..grid.right) {
                stroke.color = if (Engine.songRec) Color.rgb(255, 80, 80) else Color.WHITE
                stroke.strokeWidth = dp(2f)
                c.drawLine(x, grid.top, x, grid.bottom, stroke)
            }
        }
        // Startmarke
        val xc = xOf(cursorBar.toFloat())
        if (xc in grid.left..grid.right) {
            stroke.color = Color.argb(120, 130, 230, 160); stroke.strokeWidth = dp(1f)
            c.drawLine(xc, grid.top, xc, grid.bottom, stroke)
        }
        c.restore()
    }
    private val addTrack = RectF()

    private fun drawGridLines(c: Canvas, y0: Float, y1: Float, b0: Int, b1: Int) {
        for (b in b0..b1) {
            val x = xOf(b.toFloat())
            if (b < Engine.ARR_MAX && Engine.arrSection[b] >= 0) {
                stroke.color = Color.argb(90, 255, 255, 255); stroke.strokeWidth = dp(1.4f)
            } else {
                stroke.color = if (b % 4 == 0) Color.argb(40, 255, 255, 255) else Color.argb(16, 255, 255, 255); stroke.strokeWidth = dp(0.8f)
            }
            c.drawLine(x, y0, x, y1, stroke)
        }
        // hinter dem Song-Ende abdunkeln
        val xe = xOf(Engine.songBars.toFloat())
        if (xe < grid.right) { fill.color = Color.argb(110, 0, 0, 0); c.drawRect(max(grid.left, xe), y0, grid.right, y1, fill) }
    }

    private fun drawClip(c: Canvas, t: Int, v: Int, s: Int, e: Int, y0: Float, y1: Float) {
        val sel = selTracks.contains(t) && selB0 >= 0 && s >= selB0 && e <= selB1
        val off = if (sel && mode == M_MOVE) moveDx else 0f
        var x0 = xOf(s.toFloat()) + dp(1.5f) + off
        var x1 = xOf(e + 1f) - dp(1.5f) + off
        if (sel && mode == M_RESIZE_R) x1 += resizeDx
        if (sel && mode == M_RESIZE_L) x0 += resizeDx
        if (x1 < grid.left || x0 > grid.right) return
        val col = trackCol(t, v)
        tmp.set(x0, y0 + dp(2f), x1, y1 - dp(2f))
        fill.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(60, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, dp(7f), dp(7f), fill)
        fill.shader = null
        // Kopfzeile des Blocks
        val hh = dp(14f)
        c.save(); c.clipRect(tmp)
        fill.color = col
        c.drawRect(tmp.left, tmp.top, tmp.right, tmp.top + hh, fill)
        bold.color = Color.BLACK; bold.textSize = dp(9f)
        val lb = when {
            isInst(t) -> "Pattern " + InstState.patLetter(v - 1)
            t < Engine.TRK_PADS -> Engine.padName(t)?.take(16) ?: "Pad ${t + 1}"
            else -> trackName(t)
        }
        c.drawText(fitLabel(bold, lb + if (e > s) "  ·  ${e - s + 1} T" else "", tmp.width() - dp(8f), dp(9f), dp(6f)), tmp.left + dp(4f), tmp.top + dp(10.5f), bold)
        // Inhalt je Takt
        for (b in s..e) {
            val bx0 = xOf(b.toFloat()) + off; val bx1 = xOf(b + 1f) + off
            if (bx1 < grid.left || bx0 > grid.right) continue
            drawBarContent(c, t, b, v, bx0 + dp(2f), bx1 - dp(2f), tmp.top + hh + dp(2f), tmp.bottom - dp(3f), col)
        }
        c.restore()
        stroke.strokeWidth = if (sel) dp(2.2f) else dp(1.2f)
        stroke.color = if (sel) Color.WHITE else col
        c.drawRoundRect(tmp, dp(7f), dp(7f), stroke)
        if (sel && selTracks.size == 1) {
            // Griffe zum Ziehen der Länge
            for (side in 0..1) {
                val hx = if (side == 0) tmp.left else tmp.right
                tmp2.set(hx - dp(7f), (tmp.top + tmp.bottom) / 2 - dp(14f), hx + dp(7f), (tmp.top + tmp.bottom) / 2 + dp(14f))
                rr(c, tmp2, dp(5f), Color.WHITE)
                stroke.color = Color.argb(150, 0, 0, 0); stroke.strokeWidth = dp(1f)
                c.drawLine(hx - dp(2f), tmp2.top + dp(8f), hx - dp(2f), tmp2.bottom - dp(8f), stroke)
                c.drawLine(hx + dp(2f), tmp2.top + dp(8f), hx + dp(2f), tmp2.bottom - dp(8f), stroke)
            }
        }
    }
    private val tmp2 = RectF()

    private fun drawBarContent(c: Canvas, t: Int, bar: Int, v: Int, x0: Float, x1: Float, y0: Float, y1: Float, col: Int) {
        val w = x1 - x0
        val h = y1 - y0
        if (w < dp(5f) || h < dp(6f)) return
        val ink = Color.argb(230, Color.red(col) / 2 + 120, Color.green(col) / 2 + 120, Color.blue(col) / 2 + 120)
        when {
            t < Engine.TRK_PADS -> {
                if (!Engine.hasLoop(t)) return
                if (Engine.isOneShot(t)) {
                    fill.color = ink
                    for (s in 0 until 16) if (Engine.seqHit(t, s)) c.drawRect(x0 + w * s / 16f, y0 + h * 0.25f, x0 + w * s / 16f + max(dp(1.5f), w / 36f), y1 - h * 0.25f, fill)
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
                    val per = max(1, pk.size / lb)
                    val seg = Math.floorMod(bar, lb)
                    val n = min(per, max(4, (w / dp(2.2f)).toInt()))
                    stroke.strokeWidth = max(1f, w / n * 0.65f)
                    stroke.color = ink
                    val cy = (y0 + y1) / 2
                    for (k in 0 until n) {
                        val i = seg * per + k * per / n
                        val a = pk.getOrElse(i) { 0f }.coerceIn(0f, 1f) * h * 0.48f
                        val x = x0 + w * (k + 0.5f) / n
                        c.drawLine(x, cy - a, x, cy + a, stroke)
                    }
                }
            }
            t < Engine.TRK_X -> {
                val inst = t - Engine.TRK_INST
                val p = InstState.patFor(inst, v - 1) ?: return
                val len = max(1, p.len)
                fill.color = ink
                if (inst == 1) {
                    val shown = intArrayOf(0, 1, 6, 7, 8)     // BD SD CP CH OH – die wichtigsten Reihen
                    for ((ri, r) in shown.withIndex()) {
                        if (r >= p.ints.size) continue
                        val yy = y0 + h * (ri + 0.5f) / shown.size
                        for (s in 0 until 16) {
                            val i = (bar * 16 + s) % len
                            if (p.ints[r].getOrElse(i) { 0 } > 0) c.drawCircle(x0 + w * (s + 0.5f) / 16f, yy, max(dp(1f), min(w / 36f, h / shown.size / 2.4f)), fill)
                        }
                    }
                } else {
                    val gate = p.bools.getOrNull(0) ?: return
                    val semi = p.ints.getOrNull(0) ?: return
                    // Tonumfang des Patterns für die Höhe
                    var lo = 99; var hi = -99
                    for (i in 0 until len) if (gate.getOrElse(i) { false }) { lo = min(lo, semi[i]); hi = max(hi, semi[i]) }
                    if (lo > hi) return
                    val span = max(6, hi - lo)
                    for (s in 0 until 16) {
                        val i = (bar * 16 + s) % len
                        if (!gate.getOrElse(i) { false }) continue
                        val yy = y1 - h * ((semi[i] - lo + 0.5f) / (span + 1f))
                        c.drawRoundRect(x0 + w * s / 16f + dp(0.4f), yy - dp(1.6f), x0 + w * (s + 1) / 16f - dp(0.4f), yy + dp(1.6f), dp(1f), dp(1f), fill)
                    }
                }
            }
            else -> {
                stroke.color = ink; stroke.strokeWidth = dp(1.3f)
                path.reset()
                val cy = (y0 + y1) / 2
                for (k in 0..12) {
                    val x = x0 + w * k / 12f
                    val yy = cy + kotlin.math.sin((bar * 12 + k) * 0.55).toFloat() * h * 0.28f
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
        val i1 = min(a.size - 1, ((firstBar + visBars() + 1) * res).toInt())
        val top = y0 + dp(4f); val bot = y1 - dp(4f)
        stroke.color = Color.argb(30, 255, 255, 255); stroke.strokeWidth = dp(0.8f)
        c.drawLine(grid.left, (top + bot) / 2, grid.right, (top + bot) / 2, stroke)
        // Fläche unter der Kurve
        path.reset()
        var open = false
        var lastX = 0f
        for (i in i0..i1) {
            val v = a[i]
            val x = xOf(i.toFloat() / res)
            if (v.isNaN()) { if (open) { path.lineTo(lastX, bot); path.close(); open = false }; continue }
            val y = bot - (bot - top) * v.coerceIn(0f, 1f)
            if (!open) { path.moveTo(x, bot); path.lineTo(x, y); open = true } else path.lineTo(x, y)
            lastX = x
        }
        if (open) { path.lineTo(lastX, bot); path.close() }
        fill.color = Color.argb(55, Color.red(COL_AUTO), Color.green(COL_AUTO), Color.blue(COL_AUTO))
        c.drawPath(path, fill)
        stroke.color = COL_AUTO; stroke.strokeWidth = dp(2f)
        path.reset(); open = false
        for (i in i0..i1) {
            val v = a[i]
            val x = xOf(i.toFloat() / res)
            if (v.isNaN()) { open = false; continue }
            val y = bot - (bot - top) * v.coerceIn(0f, 1f)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        c.drawPath(path, stroke)
    }

    private fun drawMini(c: Canvas, pp: Float) {
        rr(c, mini, dp(7f), PANEL)
        val total = max(Engine.songBars, 4).toFloat()
        val w = mini.width()
        // Abschnitte
        var b = 0
        while (b < Engine.songBars) {
            val k = Engine.arrSection[b]
            var e = b + 1
            while (e < Engine.songBars && Engine.arrSection[e] < 0) e++
            if (k >= 0) {
                val col = SEC_COLS[k % SEC_COLS.size]
                fill.color = Color.argb(120, Color.red(col), Color.green(col), Color.blue(col))
                c.drawRect(mini.left + w * b / total, mini.top + dp(3f), mini.left + w * e / total - dp(1f), mini.bottom - dp(3f), fill)
            }
            b = e
        }
        // Belegung (dünne Linien je benutzter Spur)
        val used = (0 until Engine.TRK_AUTO).filter { trackUsed(it) }
        for ((i, t) in used.withIndex()) {
            val yy = mini.top + dp(5f) + (mini.height() - dp(10f)) * (i + 0.5f) / max(1, used.size)
            for (bb in 0 until Engine.songBars) if (Engine.trkCell(t, bb) > 0) {
                fill.color = trackCol(t, Engine.trkCell(t, bb))
                c.drawRect(mini.left + w * bb / total, yy - dp(0.8f), mini.left + w * (bb + 1) / total, yy + dp(0.8f), fill)
            }
        }
        // sichtbares Fenster
        val vx0 = mini.left + w * (firstBar / total).coerceIn(0f, 1f)
        val vx1 = mini.left + w * ((firstBar + visBars()) / total).coerceIn(0f, 1f)
        stroke.color = Color.WHITE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(vx0, mini.top + dp(1f), max(vx0 + dp(8f), vx1), mini.bottom - dp(1f), dp(5f), dp(5f), stroke)
        if (pp >= 0f) { stroke.color = Color.rgb(255, 255, 255); stroke.strokeWidth = dp(1.2f); val x = mini.left + w * pp / total; c.drawLine(x, mini.top, x, mini.bottom, stroke) }
    }

    // ================================================================ Aktions-Leiste (passend zur Auswahl)

    private val A_DUP = 1; private val A_COPY = 2; private val A_PASTE = 3; private val A_DEL = 4; private val A_PAT = 5; private val A_NOTES = 6
    private val A_LEFT = 7; private val A_RIGHT = 8; private val A_SECNAME = 9; private val A_SECPLUS = 10; private val A_SECMINUS = 11; private val A_SECDEL = 12
    private val A_SCAFFOLD = 13; private val A_ZOOMIN = 14; private val A_ZOOMOUT = 15; private val A_AUTOCLR = 16; private val A_SONG = 17; private val A_SPLIT = 18
    private val A_DESEL = 19; private val A_BLOCK = 20; private val A_INSBAR = 21; private val A_HELP = 22

    private fun actionsNow(): List<Pair<Int, String>> {
        val l = ArrayList<Pair<Int, String>>()
        if (selB0 < 0) {
            l.add(A_SONG to if (Engine.songMode) "SONG AN" else "SONG AUS")
            if (clip.isNotEmpty()) l.add(A_PASTE to "EINFÜGEN @${cursorBar + 1}")
            l.add(A_INSBAR to "＋ TAKT @${cursorBar + 1}")
            l.add(A_ZOOMOUT to "− ZOOM")
            l.add(A_ZOOMIN to "＋ ZOOM")
            l.add(A_SCAFFOLD to "✦ GERÜST")
            l.add(A_HELP to "? HILFE")
            return l
        }
        val single = selTracks.size == 1
        val t = selTracks.firstOrNull() ?: -1
        if (selSection) {
            l.add(A_SECNAME to "UMBENENNEN")
            l.add(A_DUP to "DUPLIZIEREN")
            l.add(A_SECMINUS to "− TAKT")
            l.add(A_SECPLUS to "＋ TAKT")
            l.add(A_LEFT to "◀ NACH VORN")
            l.add(A_RIGHT to "NACH HINTEN ▶")
            l.add(A_SECDEL to "LÖSCHEN")
            l.add(A_DESEL to "✓ FERTIG")
            return l
        }
        if (single && isAuto(t)) {
            l.add(A_AUTOCLR to "KURVE LÖSCHEN")
            l.add(A_COPY to "KOPIEREN")
            l.add(A_DUP to "DUPLIZIEREN")
            l.add(A_DESEL to "✓ FERTIG")
            return l
        }
        if (single && isInst(t)) {
            l.add(A_NOTES to "✎ NOTEN")
            l.add(A_PAT to "PATTERN " + InstState.patLetter((Engine.trkCell(t, selB0).coerceAtLeast(1)) - 1) + " ⟳")
        }
        l.add(A_DUP to "DUPLIZIEREN")
        l.add(A_COPY to "KOPIEREN")
        if (selB1 > selB0 && cursorBar in selB0 + 1..selB1) l.add(A_SPLIT to "✂ TEILEN")
        if (!single) l.add(A_BLOCK to "▣ ABSCHNITT")
        l.add(A_LEFT to "◀")
        l.add(A_RIGHT to "▶")
        l.add(A_DEL to "LÖSCHEN")
        return l.take(8)
    }

    private fun drawActions(c: Canvas) {
        rr(c, act, dp(10f), PANEL)
        val list = actionsNow()
        aCount = list.size
        val gw = (act.width() - dp(8f) - (aCount - 1) * dp(4f)) / aCount
        for ((i, a) in list.withIndex()) {
            aBtn[i].set(act.left + dp(4f) + i * (gw + dp(4f)), act.top + dp(5f), act.left + dp(4f) + i * (gw + dp(4f)) + gw, act.bottom - dp(5f))
            aIds[i] = a.first
            val col = when (a.first) {
                A_DEL, A_SECDEL, A_AUTOCLR -> Color.rgb(255, 110, 110)
                A_NOTES, A_SCAFFOLD -> Color.rgb(130, 230, 160)
                A_PAT -> PAT_COL[(Engine.trkCell(selTracks.firstOrNull() ?: 0, max(0, selB0)).coerceAtLeast(1) - 1).coerceIn(0, 3)]
                A_SONG -> Color.rgb(130, 230, 160)
                else -> SEL
            }
            val on = (a.first == A_SONG && Engine.songMode) || a.first == A_PAT
            button(c, aBtn[i], a.second, on, col)
        }
    }

    // ================================================================ Bedienung

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private val M_NONE = 0; private val M_TAP = 1; private val M_MOVE = 2; private val M_RESIZE_L = 3; private val M_RESIZE_R = 4
    private val M_RUBBER = 5; private val M_DRAW = 6; private val M_PAN = 7; private val M_HEADS = 8; private val M_RULER = 9
    private val M_END = 10; private val M_MINI = 11; private val M_SECTION = 12; private val M_SECMOVE = 13
    private var mode = 0
    private fun dragging() = mode == M_MOVE || mode == M_RESIZE_L || mode == M_RESIZE_R || mode == M_RUBBER || mode == M_DRAW || mode == M_SECMOVE

    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var downRow: Row? = null
    private var downBar = -1
    private var moveDx = 0f
    private var resizeDx = 0f
    private var rubber = false
    private var rx0 = 0f; private var ry0 = 0f; private var rx1 = 0f; private var ry1 = 0f
    private var scroll0 = 0f; private var scrollY0 = 0f
    private var pinchD = 0f; private var pinchW = 0f; private var pinchMid = 0f
    private var lastTapT = 0L; private var lastTapX = 0f; private var lastTapY = 0f
    private var autoLastI = -1; private var autoLastV = 0f
    private val longPress = Runnable { if (mode == M_TAP) startRubber() }

    private fun rowAtY(y: Float): Row? {
        var yy = grid.top - scrollY
        for (r in rows()) { if (y >= yy && y < yy + r.h) return r; yy += r.h }
        return null
    }
    private fun rowTop(row: Row): Float {
        var yy = grid.top - scrollY
        for (r in rows()) { if (r.t == row.t && r.kind == row.kind) return yy; yy += r.h }
        return yy
    }

    fun touch(e: MotionEvent) {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downT = SystemClock.uptimeMillis(); mode = M_NONE; moveDx = 0f; resizeDx = 0f
                for (i in 0 until 8) if (tBtn[i].contains(x, y)) { transport(i); return }
                for (i in 0 until aCount) if (aBtn[i].contains(x, y)) { action(aIds[i]); return }
                if (isEmptySong() && Engine.songBars >= 0) {
                    for (i in 0 until 3) if (emptyBtn[i].contains(x, y)) { emptyAction(i); return }
                }
                when {
                    addSec.contains(x, y) -> { addSection(); return }
                    secRow.contains(x, y) -> { mode = M_SECTION; scroll0 = firstBar; return }
                    abs(x - xOf(Engine.songBars.toFloat())) < dp(14f) && y in ruler.top..ruler.bottom -> { snap(); mode = M_END; return }
                    ruler.contains(x, y) -> { mode = M_RULER; scroll0 = firstBar; return }
                    mini.contains(x, y) -> { mode = M_MINI; miniJump(x); return }
                    addTrack.contains(x, y) -> { onPickTracks?.invoke(); return }
                    heads.contains(x, y) -> { mode = M_HEADS; scrollY0 = scrollY; downRow = rowAtY(y); return }
                    grid.contains(x, y) -> gridDown(x, y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    host.removeCallbacks(longPress)
                    if (mode == M_DRAW || mode == M_MOVE || mode == M_RESIZE_L || mode == M_RESIZE_R) undoLast()
                    rubber = false
                    mode = M_PAN
                    pinchD = max(dp(30f), abs(e.getX(0) - e.getX(1)))
                    pinchW = barW
                    pinchMid = barAtF((e.getX(0) + e.getX(1)) / 2)
                    scroll0 = firstBar; scrollY0 = scrollY
                    downX = (e.getX(0) + e.getX(1)) / 2; downY = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> move(e, x, y)
            MotionEvent.ACTION_UP -> { host.removeCallbacks(longPress); up(x, y) }
            MotionEvent.ACTION_CANCEL -> { host.removeCallbacks(longPress); mode = M_NONE; rubber = false; moveDx = 0f; resizeDx = 0f }
        }
        host.invalidate()
    }

    /** Ein gerade begonnener Schritt wird verworfen (z. B. zweiter Finger beim Zeichnen). */
    private fun undoLast() { undo.removeLastOrNull()?.let { restore(it) } }

    private fun gridDown(x: Float, y: Float) {
        val row = rowAtY(y) ?: return
        downRow = row
        val b = barAt(x)
        downBar = b
        val t = row.t
        if (row.kind == Kind.AUTO) {
            snap()
            mode = M_DRAW; autoLastI = -1
            drawAuto(row, x, y)
            return
        }
        if (row.kind == Kind.MASTER) { mode = M_TAP; return }
        // Griffe / gewählter Block
        val cl = clipAt(t, b)
        if (selB0 >= 0 && selTracks.size == 1 && selTracks[0] == t) {
            val xl = xOf(selB0.toFloat()); val xr = xOf(selB1 + 1f)
            if (abs(x - xl) < dp(16f)) { snap(); mode = M_RESIZE_L; return }
            if (abs(x - xr) < dp(16f)) { snap(); mode = M_RESIZE_R; return }
        }
        if (selB0 >= 0 && t in selTracks && b in selB0..selB1) { snap(); mode = M_MOVE; return }
        mode = M_TAP
        host.postDelayed(longPress, 380L)
    }

    private fun startRubber() {
        mode = M_RUBBER
        rubber = true
        rx0 = downX; ry0 = downY; rx1 = downX; ry1 = downY
        clearSel()
        haptic()
        hint("Rahmen ziehen: mehrere Spuren und Takte wählen")
    }

    private fun move(e: MotionEvent, x: Float, y: Float) {
        when (mode) {
            M_TAP -> if (hypot(x - downX, y - downY) > dp(10f)) { host.removeCallbacks(longPress); mode = M_PAN; scroll0 = firstBar; scrollY0 = scrollY; pinchD = 0f }
            M_PAN -> {
                if (e.pointerCount >= 2) {
                    val dd = max(dp(30f), abs(e.getX(0) - e.getX(1)))
                    val mid = (e.getX(0) + e.getX(1)) / 2
                    if (pinchD > 0f) barW = (pinchW * dd / pinchD).coerceIn(dp(10f), grid.width() / 2f)
                    firstBar = (pinchMid - (mid - grid.left) / barW).coerceIn(0f, maxFirst())
                    scrollY = scrollY0 - ((e.getY(0) + e.getY(1)) / 2 - downY)
                } else {
                    // ein Finger auf leerer Fläche: Zeitleiste schieben (waagrecht) bzw. Spuren rollen (senkrecht)
                    firstBar = (scroll0 - (x - downX) / barW).coerceIn(0f, maxFirst())
                    scrollY = scrollY0 - (y - downY)
                    follow = false
                }
            }
            M_MOVE -> { moveDx = Math.round((x - downX) / barW) * barW }
            M_RESIZE_L, M_RESIZE_R -> { resizeDx = Math.round((x - downX) / barW) * barW }
            M_RUBBER -> { rx1 = x; ry1 = y; edgeScroll(x) }
            M_DRAW -> downRow?.let { drawAuto(it, x, y) }
            M_HEADS -> scrollY = scrollY0 - (y - downY)
            M_RULER, M_SECTION -> if (abs(x - downX) > dp(12f)) { firstBar = (scroll0 - (x - downX) / barW).coerceIn(0f, maxFirst()); follow = false; if (mode == M_SECTION) mode = M_RULER }
            M_END -> { val b = Math.round(barAtF(x)).coerceIn(1, Engine.ARR_MAX); Engine.songBars = b }
            M_MINI -> miniJump(x)
        }
    }

    private fun edgeScroll(x: Float) {
        if (x > grid.right - dp(24f)) firstBar = (firstBar + 0.2f).coerceAtMost(maxFirst())
        if (x < grid.left + dp(24f)) firstBar = (firstBar - 0.2f).coerceAtLeast(0f)
    }

    private fun up(x: Float, y: Float) {
        when (mode) {
            M_TAP -> tapGrid(x, y)
            M_MOVE -> finishMove()
            M_RESIZE_L, M_RESIZE_R -> finishResize()
            M_RUBBER -> finishRubber()
            M_HEADS -> if (abs(y - downY) < dp(10f)) headTap(downRow)
            M_RULER -> if (abs(x - downX) < dp(12f)) { cursorBar = barAt(x).coerceIn(0, Engine.ARR_MAX - 1); Engine.playFromBar = cursorBar; haptic() }
            M_SECTION -> if (abs(x - downX) < dp(12f)) sectionTap(barAt(x))
            M_END -> hint("Song endet nach Takt ${Engine.songBars}")
        }
        mode = M_NONE; rubber = false; moveDx = 0f; resizeDx = 0f; autoLastI = -1
    }

    private fun tapGrid(x: Float, y: Float) {
        val row = rowAtY(y) ?: return
        val b = barAt(x).coerceIn(0, Engine.ARR_MAX - 1)
        val now = SystemClock.uptimeMillis()
        val dbl = now - lastTapT < 330 && hypot(x - lastTapX, y - lastTapY) < dp(24f)
        lastTapT = now; lastTapX = x; lastTapY = y
        val t = row.t
        if (row.kind == Kind.MASTER) { autoOpen.toggle(-1); return }
        val cl = clipAt(t, b)
        if (dbl) {
            lastTapT = 0
            if (cl != null && isInst(t)) { val inst = t - Engine.TRK_INST; onOpenInst?.invoke(inst, Engine.trkCell(t, b) - 1); return }
            if (cl == null) { createClip(t, b); return }
        }
        if (cl != null) {
            selTracks = listOf(t); selB0 = cl[0]; selB1 = cl[1]; selSection = false
            haptic()
            if (!isInst(t)) cursorBar = cursorBar
        } else {
            clearSel()
            cursorBar = b; Engine.playFromBar = b
        }
    }

    private fun HashSet<Int>.toggle(v: Int) { if (!remove(v)) add(v) }

    private fun headTap(row: Row?) {
        row ?: return
        when (row.kind) {
            Kind.AUTO -> {
                // ganze Kurve wählen
                val l = row.t - Engine.TRK_AUTO
                val a = Engine.auto[l]
                val i0 = a.indexOfFirst { !it.isNaN() }
                if (i0 < 0) { hint("Leere Kurve – einfach mit dem Finger auf die Spur zeichnen"); return }
                val i1 = a.indexOfLast { !it.isNaN() }
                selTracks = listOf(row.t); selB0 = i0 / Engine.AUTO_RES; selB1 = i1 / Engine.AUTO_RES; selSection = false
            }
            Kind.MASTER -> autoOpen.toggle(-1)
            else -> {
                if (lanesOf(row.t).isNotEmpty()) {
                    autoOpen.toggle(row.t)
                    hint(if (row.t in autoOpen) "〰 Automation von ${trackName(row.t)} eingeblendet – auf die gelbe Spur zeichnen" else "Automation ausgeblendet")
                } else {
                    // ganze Spur wählen
                    val used = (0 until Engine.ARR_MAX).filter { Engine.trkCell(row.t, it) > 0 }
                    if (used.isEmpty()) { hint("${trackName(row.t)}: Doppeltipp auf die Spur legt einen Block an"); return }
                    selTracks = listOf(row.t); selB0 = used.first(); selB1 = used.last(); selSection = false
                }
            }
        }
        haptic()
    }

    private fun clearSel() { selTracks = emptyList(); selB0 = -1; selB1 = -1; selSection = false }

    private fun createClip(t: Int, b: Int) {
        if (!isClipTrack(t)) return
        snap()
        // bis zum nächsten Block bzw. Abschnittsende, höchstens 4 Takte
        var len = 4
        for (k in 1 until 4) if (b + k >= Engine.ARR_MAX || Engine.trkCell(t, b + k) > 0 || Engine.arrSection[b + k] >= 0) { len = k; break }
        val v = if (isInst(t)) InstState.curPat[t - Engine.TRK_INST] + 1 else 1
        for (k in 0 until len) Engine.trkSet(t, b + k, v)
        setBrk(t, b, true)
        if (b + len < Engine.ARR_MAX && Engine.trkCell(t, b + len) == v) setBrk(t, b + len, true)
        if (b + len > Engine.songBars) Engine.songBars = ((b + len + 3) / 4 * 4).coerceAtMost(Engine.ARR_MAX)
        selTracks = listOf(t); selB0 = b; selB1 = b + len - 1; selSection = false
        haptic()
        hint("Block angelegt (${len} Takte) – Griffe ziehen ändert die Länge" + if (isInst(t)) ", Doppeltipp öffnet die Noten" else "")
    }

    // ---- Daten einer Spur über Takte lesen/schreiben (inkl. Blockgrenzen und Automation)
    private fun grab(t: Int, b0: Int, b1: Int): ClipData {
        val n = b1 - b0 + 1
        val vals = IntArray(n) { Engine.trkCell(t, b0 + it) }
        val br = BooleanArray(n) { brk(t, b0 + it) }
        val au = if (isAuto(t)) Engine.auto[t - Engine.TRK_AUTO].let { a -> FloatArray(n * Engine.AUTO_RES) { i -> a.getOrElse(b0 * Engine.AUTO_RES + i) { Float.NaN } } } else null
        if (n > 0 && vals[0] > 0) br[0] = true
        return ClipData(t, vals, br, au)
    }

    private fun wipe(t: Int, b0: Int, b1: Int) {
        if (isAuto(t)) {
            val a = Engine.auto[t - Engine.TRK_AUTO]
            for (i in max(0, b0) * Engine.AUTO_RES until min(Engine.ARR_MAX, b1 + 1) * Engine.AUTO_RES) a[i] = Float.NaN
            return
        }
        for (b in max(0, b0)..min(Engine.ARR_MAX - 1, b1)) { Engine.trkSet(t, b, 0); setBrk(t, b, false) }
        // folgender Block bekommt eine Grenze (sonst verschmilzt er)
        if (b1 + 1 < Engine.ARR_MAX && Engine.trkCell(t, b1 + 1) > 0) setBrk(t, b1 + 1, true)
    }

    private fun put(cd: ClipData, at: Int) {
        val t = cd.track
        if (isAuto(t)) {
            val a = Engine.auto[t - Engine.TRK_AUTO]
            val src = cd.auto ?: return
            for (i in src.indices) { val k = at * Engine.AUTO_RES + i; if (k in a.indices) a[k] = src[i] }
            return
        }
        for (k in cd.vals.indices) {
            val b = at + k
            if (b !in 0 until Engine.ARR_MAX) continue
            Engine.trkSet(t, b, cd.vals[k]); setBrk(t, b, cd.breaks[k])
        }
        val end = at + cd.vals.size
        if (end < Engine.ARR_MAX && Engine.trkCell(t, end) > 0) setBrk(t, end, true)
    }

    private fun growSong(end: Int) { if (end > Engine.songBars) Engine.songBars = ((end + 3) / 4 * 4).coerceAtMost(Engine.ARR_MAX) }

    private fun finishMove() {
        val db = Math.round(moveDx / barW)
        if (db == 0 || selB0 < 0) { undo.removeLastOrNull(); return }
        val nb0 = (selB0 + db).coerceIn(0, Engine.ARR_MAX - 1 - (selB1 - selB0))
        val data = selTracks.map { grab(it, selB0, selB1) }
        for (t in selTracks) wipe(t, selB0, selB1)
        for (cd in data) put(cd, nb0)
        val len = selB1 - selB0
        selB0 = nb0; selB1 = nb0 + len
        growSong(selB1 + 1)
        haptic()
        hint("Verschoben nach Takt ${selB0 + 1}")
    }

    private fun finishResize() {
        val db = Math.round(resizeDx / barW)
        val t = selTracks.firstOrNull() ?: return
        if (db == 0 || selB0 < 0) { undo.removeLastOrNull(); return }
        val v = Engine.trkCell(t, selB0).coerceAtLeast(1)
        if (mode == M_RESIZE_R) {
            val nb1 = (selB1 + db).coerceIn(selB0, Engine.ARR_MAX - 1)
            if (nb1 > selB1) for (b in selB1 + 1..nb1) { Engine.trkSet(t, b, v); setBrk(t, b, false) }
            else for (b in nb1 + 1..selB1) { Engine.trkSet(t, b, 0); setBrk(t, b, false) }
            if (nb1 + 1 < Engine.ARR_MAX && Engine.trkCell(t, nb1 + 1) > 0) setBrk(t, nb1 + 1, true)
            selB1 = nb1
        } else {
            val nb0 = (selB0 + db).coerceIn(0, selB1)
            if (nb0 < selB0) for (b in nb0 until selB0) { Engine.trkSet(t, b, v); setBrk(t, b, false) }
            else for (b in selB0 until nb0) { Engine.trkSet(t, b, 0); setBrk(t, b, false) }
            setBrk(t, nb0, true)
            if (nb0 > 0 && Engine.trkCell(t, nb0 - 1) > 0) setBrk(t, nb0, true)
            selB0 = nb0
        }
        growSong(selB1 + 1)
        haptic()
        hint("Länge: ${selB1 - selB0 + 1} Takte")
    }

    private fun finishRubber() {
        val rs = rows()
        val ya = min(ry0, ry1); val yb = max(ry0, ry1)
        val picked = ArrayList<Int>()
        var yy = grid.top - scrollY
        for (r in rs) {
            if (yy + r.h > ya && yy < yb && r.kind != Kind.MASTER) picked.add(r.t)
            yy += r.h
        }
        val b0 = barAt(min(rx0, rx1)).coerceIn(0, Engine.ARR_MAX - 1)
        val b1 = barAt(max(rx0, rx1)).coerceIn(0, Engine.ARR_MAX - 1)
        if (picked.isEmpty()) return
        selTracks = picked; selB0 = b0; selB1 = b1; selSection = false
        haptic()
        hint("${picked.size} Spuren × ${b1 - b0 + 1} Takte gewählt – hineinfassen und ziehen verschiebt alles")
    }

    private fun drawAuto(row: Row, x: Float, y: Float) {
        val lane = row.t - Engine.TRK_AUTO
        val y0 = rowTop(row) + dp(4f); val y1 = rowTop(row) + row.h - dp(7f)
        val i = (barAtF(x) * Engine.AUTO_RES).toInt().coerceIn(0, Engine.ARR_MAX * Engine.AUTO_RES - 1)
        val v = ((y1 - y) / (y1 - y0)).coerceIn(0f, 1f)
        val a = Engine.auto[lane]
        if (autoLastI < 0) a[i] = v
        else {
            val from = min(autoLastI, i); val to = max(autoLastI, i)
            for (k in from..to) a[k] = if (to == from) v else autoLastV + (v - autoLastV) * (k - autoLastI).toFloat() / (i - autoLastI).toFloat()
        }
        autoLastI = i; autoLastV = v
        growSong(i / Engine.AUTO_RES + 1)
        edgeScroll(x)
    }

    private fun miniJump(x: Float) {
        val total = max(Engine.songBars, 4).toFloat()
        val b = (x - mini.left) / mini.width() * total
        firstBar = (b - visBars() / 2).coerceIn(0f, maxFirst())
        follow = false
    }

    // ---- Abschnitte
    private fun sectionRange(b: Int): IntArray? {
        if (b !in 0 until Engine.songBars) return null
        var s = b
        while (s > 0 && Engine.arrSection[s] < 0) s--
        if (Engine.arrSection[s] < 0) return null
        var e = s + 1
        while (e < Engine.songBars && Engine.arrSection[e] < 0) e++
        return intArrayOf(s, e - 1)
    }

    private fun sectionTap(b: Int) {
        val r = sectionRange(b)
        if (r == null) {
            if (b in 0 until Engine.songBars) { cursorBar = b; Engine.playFromBar = b; onPickSection?.invoke(b) }
            return
        }
        selTracks = rows().filter { it.kind != Kind.MASTER }.map { it.t }.filter { !isAuto(it) || Engine.autoLaneUsed(it - Engine.TRK_AUTO) } +
            (0 until Engine.AUTO_LANES).filter { Engine.autoLaneUsed(it) }.map { Engine.TRK_AUTO + it }
        selTracks = selTracks.distinct()
        selB0 = r[0]; selB1 = r[1]; selSection = true
        cursorBar = r[0]; Engine.playFromBar = r[0]
        haptic()
        hint("${Engine.SECTION_NAMES[Engine.arrSection[r[0]]]} gewählt (Takt ${r[0] + 1}–${r[1] + 1}) – unten: duplizieren, verlängern, verschieben")
    }

    private fun addSection() {
        snap()
        val at = Engine.songBars
        if (at >= Engine.ARR_MAX - 1) { hint("Song ist schon 64 Takte lang"); return }
        val used = Engine.arrSection.filter { it >= 0 }
        val k = when {
            used.isEmpty() -> 0
            used.last() == 0 -> 1
            else -> listOf(1, 4, 3, 6).firstOrNull { it !in used } ?: 1
        }
        if (Engine.arrSection.none { it >= 0 } && at > 0) Engine.arrSection[0] = 1
        val len = min(8, Engine.ARR_MAX - at)
        Engine.arrSection[at] = if (at == 0) 0 else k
        Engine.songBars = at + len
        firstBar = max(0f, at - 1f).coerceAtMost(maxFirst())
        selTracks = emptyList(); selB0 = at; selB1 = at + len - 1; selSection = true
        sectionTap(at)
        hint("Neuer Abschnitt ${Engine.SECTION_NAMES[Engine.arrSection[at]]} (8 Takte) – UMBENENNEN wählt den Typ, Doppeltipp auf Spuren füllt ihn")
    }

    /** Abschnitt für Takt [bar] setzen (von MainActivity nach der Auswahl, k < 0 = entfernen). */
    fun setSection(bar: Int, k: Int) {
        if (bar !in 0 until Engine.ARR_MAX) return
        snap()
        if (!selSection && selB0 >= 0 && bar == selB0) {
            // Auswahl wird zum Abschnitt: Anfang markieren, danach den alten Abschnitt fortsetzen
            val after = selB1 + 1
            if (after < Engine.ARR_MAX && Engine.arrSection[after] < 0) {
                var s = selB0; while (s > 0 && Engine.arrSection[s] < 0) s--
                if (Engine.arrSection[s] >= 0 && s < selB0) Engine.arrSection[after] = Engine.arrSection[s]
            }
        }
        Engine.arrSection[bar] = k
        growSong(bar + 1)
        host.invalidate()
    }

    // ---- Knöpfe
    private fun transport(i: Int) {
        haptic()
        when (i) {
            0 -> close()
            1 -> doUndo()
            2 -> doRedo()
            3 -> { cursorBar = 0; Engine.playFromBar = 0; firstBar = 0f; if (Engine.seqPlaying) { Engine.transportStop(); Engine.songMode = true; Engine.transportPlay(false) } }
            4 -> if (Engine.seqPlaying) Engine.transportStop() else { Engine.songMode = true; Engine.playFromBar = cursorBar; follow = true; Engine.transportPlay(false) }
            5 -> if (Engine.songRec) { Engine.transportStop(); hint("Aufnahme beendet – Song: ${Engine.songBars} Takte") } else {
                snap()
                follow = true
                Engine.songRecStart(cursorBar)
                hint("● Aufnahme ab Takt ${cursorBar + 1} nach 1 Takt Einzählen – spiel deinen Song: Pads, Instrumente, ATMOS/EUKLID/HARMONIZER, FX-Fläche · ■ beendet")
            }
            6 -> hint("Takt-Leiste antippen setzt die Startmarke · rote END-Marke ziehen ändert die Songlänge")
            7 -> { follow = !follow; hint(if (follow) "Ansicht folgt dem Abspielkopf" else "Ansicht bleibt stehen") }
        }
        host.invalidate()
    }

    private fun emptyAction(i: Int) {
        haptic()
        when (i) {
            0 -> scaffold()
            1 -> transport(5)
            else -> { snap(); Engine.arrFillFromScenes(Engine.TRK_PADS); hint(if (isEmptySong()) "Keine Szenen gespeichert – speichere erst Szenen (SZENE-Knopf) oder nutze das Gerüst" else "Song aus deinen Szenen gebaut") }
        }
    }

    private fun action(id: Int) {
        haptic()
        when (id) {
            A_SONG -> { Engine.songMode = !Engine.songMode; hint(if (Engine.songMode) "Song-Modus an: ▶ spielt das Arrangement" else "Song-Modus aus: Pads und Instrumente wieder frei spielbar") }
            A_ZOOMIN -> barW = (barW * 1.5f).coerceAtMost(grid.width() / 2f)
            A_ZOOMOUT -> barW = (barW / 1.5f).coerceAtLeast(dp(10f))
            A_SCAFFOLD -> scaffold()
            A_HELP -> hint("Tipp = Block wählen · Doppeltipp leer = neuer Block · ziehen = verschieben · Griffe = Länge · lange drücken + ziehen = Rahmen · zwei Finger = schieben/zoomen · Abschnitt oben antippen = ganzer Teil")
            A_INSBAR -> { snap(); Engine.barsInsert(cursorBar, 1); shiftBreaks(cursorBar, 1); hint("Leerer Takt bei ${cursorBar + 1} eingefügt") }
            A_PASTE -> paste(cursorBar)
            A_DESEL -> clearSel()
            A_COPY -> { copySel(); }
            A_DUP -> duplicate()
            A_DEL -> { snap(); for (t in selTracks) wipe(t, selB0, selB1); clearSel(); hint("Gelöscht") }
            A_LEFT, A_RIGHT -> if (selSection) moveSection(if (id == A_LEFT) -1 else 1) else nudge(if (id == A_LEFT) -1 else 1)
            A_PAT -> {
                val t = selTracks.firstOrNull() ?: return
                snap()
                val v = Engine.trkCell(t, selB0).coerceAtLeast(1) % InstState.PATS + 1
                for (b in selB0..selB1) Engine.trkSet(t, b, v)
                hint("Block spielt jetzt Pattern ${InstState.patLetter(v - 1)}" + if (!InstState.patHasNotes(t - Engine.TRK_INST, v - 1)) " (noch leer – ✎ NOTEN)" else "")
            }
            A_NOTES -> { val t = selTracks.firstOrNull() ?: return; onOpenInst?.invoke(t - Engine.TRK_INST, Engine.trkCell(t, selB0).coerceAtLeast(1) - 1) }
            A_SPLIT -> { val t = selTracks.firstOrNull() ?: return; snap(); setBrk(t, cursorBar, true); selB1 = cursorBar - 1; hint("Geteilt bei Takt ${cursorBar + 1}") }
            A_BLOCK -> onPickSection?.invoke(selB0)
            A_AUTOCLR -> { snap(); for (t in selTracks) wipe(t, selB0, selB1); clearSel(); hint("Kurve gelöscht") }
            A_SECNAME -> onPickSection?.invoke(selB0)
            A_SECPLUS -> { snap(); Engine.barsInsert(selB1 + 1, 1); shiftBreaks(selB1 + 1, 1); copyBarInto(selB1, selB1 + 1); selB1++; hint("Abschnitt jetzt ${selB1 - selB0 + 1} Takte") }
            A_SECMINUS -> if (selB1 > selB0) { snap(); Engine.barsInsert(selB1, -1); shiftBreaks(selB1, -1); selB1--; hint("Abschnitt jetzt ${selB1 - selB0 + 1} Takte") }
            A_SECDEL -> { snap(); val n = selB1 - selB0 + 1; Engine.barsInsert(selB0, -n); shiftBreaks(selB0, -n); clearSel(); hint("Abschnitt entfernt – der Rest rückt nach") }
        }
        host.invalidate()
    }

    /** Blockgrenzen beim Einfügen/Löschen von Takten mitschieben. */
    private fun shiftBreaks(at: Int, n: Int) {
        for (row in Engine.arrBreak) {
            val old = row.copyOf()
            for (b in at until Engine.ARR_MAX) {
                val src = b - n
                row[b] = if (n > 0) (if (src >= at) old[src] else false) else old.getOrElse(b - n) { false }
            }
        }
    }

    private fun copyBarInto(from: Int, to: Int) {
        for (t in 0 until Engine.TRK_AUTO) { Engine.trkSet(t, to, Engine.trkCell(t, from)); setBrk(t, to, false) }
        for (l in 0 until Engine.AUTO_LANES) for (q in 0 until Engine.AUTO_RES) Engine.auto[l][to * Engine.AUTO_RES + q] = Engine.auto[l][from * Engine.AUTO_RES + q]
    }

    private fun copySel() {
        if (selB0 < 0) return
        clip = selTracks.map { grab(it, selB0, selB1) }
        clipBars = selB1 - selB0 + 1
        hint("$clipBars Takte kopiert – Startmarke setzen (Takt-Leiste) und EINFÜGEN")
    }

    private fun paste(at: Int) {
        if (clip.isEmpty()) return
        snap()
        for (cd in clip) put(cd, at)
        growSong(at + clipBars)
        selTracks = clip.map { it.track }; selB0 = at; selB1 = min(Engine.ARR_MAX - 1, at + clipBars - 1); selSection = false
        hint("Eingefügt ab Takt ${at + 1}")
    }

    private fun duplicate() {
        if (selB0 < 0) return
        snap()
        val n = selB1 - selB0 + 1
        if (selSection) {
            // ganzer Abschnitt: dahinter Platz schaffen (alles danach rutscht) und kopieren – inkl. Abschnitts-Marke
            if (Engine.songBars + n > Engine.ARR_MAX) { hint("Kein Platz mehr (64 Takte)"); return }
            val data = (0 until Engine.TRK_AUTO).map { grab(it, selB0, selB1) } + (0 until Engine.AUTO_LANES).map { grab(Engine.TRK_AUTO + it, selB0, selB1) }
            val sec = Engine.arrSection[selB0]
            Engine.barsInsert(selB1 + 1, n)
            shiftBreaks(selB1 + 1, n)
            for (cd in data) put(cd, selB1 + 1)
            Engine.arrSection[selB1 + 1] = sec
            val nb0 = selB1 + 1
            selB0 = nb0; selB1 = nb0 + n - 1
            hint("Abschnitt dupliziert (Takt ${selB0 + 1}–${selB1 + 1})")
            return
        }
        val at = selB1 + 1
        if (at + n > Engine.ARR_MAX) { hint("Kein Platz mehr (64 Takte)"); return }
        val data = selTracks.map { grab(it, selB0, selB1) }
        for (cd in data) put(cd, at)
        selB0 = at; selB1 = at + n - 1
        growSong(selB1 + 1)
        hint("Dupliziert – nochmal tippen hängt eine weitere Kopie an")
    }

    private fun nudge(dir: Int) {
        if (selB0 < 0) return
        val nb0 = selB0 + dir
        if (nb0 < 0 || selB1 + dir >= Engine.ARR_MAX) return
        snap()
        val data = selTracks.map { grab(it, selB0, selB1) }
        for (t in selTracks) wipe(t, selB0, selB1)
        for (cd in data) put(cd, nb0)
        selB0 += dir; selB1 += dir
        growSong(selB1 + 1)
    }

    /** Abschnitt mit dem vorigen/nächsten tauschen. */
    private fun moveSection(dir: Int) {
        val a = intArrayOf(selB0, selB1)
        val other = sectionRange(if (dir < 0) selB0 - 1 else selB1 + 1) ?: run { hint("Kein Abschnitt " + if (dir < 0) "davor" else "dahinter"); return }
        snap()
        val first = if (dir < 0) other else a
        val second = if (dir < 0) a else other
        val all = (0 until Engine.TRK_AUTO) + (0 until Engine.AUTO_LANES).map { Engine.TRK_AUTO + it }
        val d1 = all.map { grab(it, first[0], first[1]) }
        val d2 = all.map { grab(it, second[0], second[1]) }
        val s1 = Engine.arrSection[first[0]]; val s2 = Engine.arrSection[second[0]]
        for (t in all) wipe(t, first[0], second[1])
        for (b in first[0]..second[1]) Engine.arrSection[b] = -1
        val n2 = second[1] - second[0] + 1
        for (cd in d2) put(cd, first[0])
        for (cd in d1) put(cd, first[0] + n2)
        Engine.arrSection[first[0]] = s2
        Engine.arrSection[first[0] + n2] = s1
        val n = a[1] - a[0] + 1
        if (dir < 0) { selB0 = first[0]; selB1 = first[0] + n - 1 } else { selB0 = first[0] + n2; selB1 = selB0 + n - 1 }
        haptic()
        hint("Abschnitt verschoben")
    }

    // ---- Song-Gerüst: aus dem, was da ist, einen kompletten Ablauf bauen
    fun scaffold() {
        snap()
        val loopPads = (0 until Engine.TRK_PADS).filter { Engine.hasLoop(it) && !Engine.isOneShot(it) }
        val shotPads = (0 until Engine.TRK_PADS).filter { Engine.hasLoop(it) && Engine.isOneShot(it) }
        val inst = (0 until InstState.INSTS).filter { i -> (0 until InstState.PATS).any { InstState.patHasNotes(i, it) } }
        val drum = 1 in inst
        val acid = 0 in inst
        val syn = inst.filter { it >= 2 }
        Engine.arrClear()
        val secs = listOf(0 to 8, 1 to 16, 4 to 8, 3 to 16, 6 to 8)    // Intro, Hauptteil, Break, Drop, Ending
        var b = 0
        fun pat(i: Int) = InstState.curPat[i] + 1
        for ((k, len) in secs) {
            Engine.arrSection[b] = k
            for (t in 0 until Engine.TRK_AUTO) setBrk(t, b, true)
            for (q in b until b + len) {
                val first = q - b
                when (k) {
                    0 -> { // Intro: Drums, erster Loop, ATMOS; ab Takt 5 Bass
                        if (drum) Engine.trkSet(Engine.TRK_INST + 1, q, pat(1))
                        loopPads.firstOrNull()?.let { Engine.trkSet(it, q, 1) }
                        Engine.trkSet(Engine.TRK_X, q, 1)
                        if (acid && first >= 4) Engine.trkSet(Engine.TRK_INST, q, pat(0))
                    }
                    1, 3 -> { // Hauptteil / Drop: alles
                        if (drum) Engine.trkSet(Engine.TRK_INST + 1, q, pat(1))
                        if (acid) Engine.trkSet(Engine.TRK_INST, q, pat(0))
                        for (s in syn) Engine.trkSet(Engine.TRK_INST + s, q, pat(s))
                        for (p in loopPads) Engine.trkSet(p, q, 1)
                        for (p in shotPads) Engine.trkSet(p, q, 1)
                        if (k == 3) Engine.trkSet(Engine.TRK_X + 1, q, 1)
                    }
                    4 -> { // Break: keine Drums, Flächen und Melodie, ATMOS
                        for (s in syn) Engine.trkSet(Engine.TRK_INST + s, q, pat(s))
                        for (p in loopPads.drop(1)) Engine.trkSet(p, q, 1)
                        Engine.trkSet(Engine.TRK_X, q, 1)
                        // Filter-Aufbau auf ACID in den letzten 4 Takten
                        if (acid && first >= 4) {
                            Engine.trkSet(Engine.TRK_INST, q, pat(0))
                            for (r in 0 until Engine.AUTO_RES) Engine.auto[2][q * Engine.AUTO_RES + r] = 0.15f + 0.7f * ((first - 4) * Engine.AUTO_RES + r) / (4f * Engine.AUTO_RES)
                        }
                    }
                    6 -> { // Ending: Drums + erster Loop, ausblenden
                        if (drum) Engine.trkSet(Engine.TRK_INST + 1, q, pat(1))
                        loopPads.firstOrNull()?.let { Engine.trkSet(it, q, 1) }
                        Engine.trkSet(Engine.TRK_X, q, 1)
                        for (r in 0 until Engine.AUTO_RES) Engine.auto[11][q * Engine.AUTO_RES + r] = if (first < 4) 0.8f else 0.8f * (1f - ((first - 4) * Engine.AUTO_RES + r) / (4f * Engine.AUTO_RES))
                    }
                }
            }
            b += len
        }
        Engine.songBars = b
        Engine.songMode = true
        cursorBar = 0; Engine.playFromBar = 0; firstBar = 0f
        clearSel()
        hint("✦ Gerüst: Intro → Hauptteil → Break (mit Filter-Aufbau) → Drop → Ending (Fade-out) · alles verschiebbar, ↶ macht es rückgängig")
    }
}
