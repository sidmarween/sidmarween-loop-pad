package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SONG-BAUKASTEN: ein Song besteht aus Teilen (Intro, Hauptteil, Break, Drop, Ending …).
 * Jeder Teil ist eine große Karte: Länge mit − / +, darunter ein Schalter je Spur (Drums, Bass, Synth, Pads, ATMOS …)
 * – an heißt: spielt im ganzen Teil. Dazu ein Effekt pro Teil (Aufbau, Ein-/Ausblenden, Filter zu).
 * Reihenfolge mit ↑ ↓, Teil verdoppeln oder löschen. Nichts muss man sich merken – alles ist ein beschrifteter Knopf.
 * Die Spuren-Ansicht (Profi) zeigt dasselbe Arrangement im Detail.
 */
class SongBuilder(private val host: View, private val ed: SongEditor) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    var onPickSection: ((Int) -> Unit)? = null
    var onOpenInst: ((Int, Int) -> Unit)? = null
    var onProfi: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; typeface = Typeface.create("sans-serif", Typeface.BOLD) }

    private val BG = Color.rgb(12, 13, 17)
    private val CARD = Color.rgb(22, 23, 30)
    private val INK = Color.rgb(236, 237, 242)
    private val DIM = Color.argb(150, 236, 237, 242)
    private val OFF = Color.rgb(36, 38, 48)
    private val GREEN = Color.rgb(130, 230, 160)
    private val RED = Color.rgb(255, 95, 95)
    private val BLUE = Color.rgb(110, 200, 255)
    private val SEC_COLS = intArrayOf(Color.rgb(90, 160, 255), Color.rgb(120, 220, 140), Color.rgb(255, 200, 80), Color.rgb(255, 95, 95), Color.rgb(180, 140, 255), Color.rgb(90, 220, 220), Color.rgb(240, 130, 200))
    private val PAT_COL = intArrayOf(Color.rgb(90, 190, 255), Color.rgb(120, 230, 140), Color.rgb(255, 200, 80), Color.rgb(240, 120, 200))
    private val FX_NAMES = arrayOf("kein Effekt", "↗ Aufbau", "◢ Einblenden", "◣ Ausblenden", "↘ Filter zu")

    private var scroll = 0f

    fun open() { isOpen = true; ensureParts() }
    fun close() { isOpen = false }

    // ================================================================ Teile

    private class Part(val b0: Int, val b1: Int, val kind: Int)

    private fun parts(): List<Part> {
        val out = ArrayList<Part>()
        val n = Engine.songBars
        var b = 0
        while (b < n) {
            var e = b + 1
            while (e < n && Engine.arrSection[e] < 0) e++
            out.add(Part(b, e - 1, Engine.arrSection[b].let { if (it < 0) 1 else it }))
            b = e
        }
        return out
    }

    /** Ohne Abschnitte (z. B. nach einer Live-Aufnahme): in Teile à 8 Takte gliedern – Intro, Hauptteil …, Ending. */
    private fun ensureParts() {
        if (Engine.songRec || Engine.songBars <= 0) return
        if (Engine.arrSection[0] >= 0) return
        if (Engine.arrSection.any { it >= 0 }) { Engine.arrSection[0] = 0; return }
        val n = Engine.songBars
        var b = 0
        while (b < n) { Engine.arrSection[b] = 1; b += 8 }
        if (n > 8) { Engine.arrSection[0] = 0; Engine.arrSection[((n - 1) / 8) * 8] = 6 }
    }

    /** Spuren, die man im Baukasten schalten kann. */
    private fun tracks(): List<Int> {
        val out = ArrayList<Int>()
        for (i in listOf(1, 0, 2, 3, 4)) if (i < InstState.INSTS) {
            val t = Engine.TRK_INST + i
            if ((0 until InstState.PATS).any { InstState.patHasNotes(i, it) } || used(t)) out.add(t)
        }
        for (p in 0 until Engine.TRK_PADS) if (Engine.hasLoop(p) || used(p)) out.add(p)
        for (k in 0 until Engine.ARR_X) out.add(Engine.TRK_X + k)
        return out
    }
    private fun used(t: Int) = (0 until Engine.ARR_MAX).any { Engine.trkCell(t, it) > 0 }
    private fun isInst(t: Int) = t in Engine.TRK_INST until Engine.TRK_X

    private fun trackLabel(t: Int): String = when {
        t == Engine.TRK_INST -> "🔊 Bass 303"
        t == Engine.TRK_INST + 1 -> "🥁 Drums 909"
        t < Engine.TRK_PADS -> (if (Engine.isOneShot(t)) "⚡ " else "∿ ") + (Engine.padName(t)?.take(12) ?: "Pad ${t + 1}")
        t < Engine.TRK_X -> "🎹 Synth ${t - Engine.TRK_INST - 1}"
        t == Engine.TRK_X -> "🌌 Atmos"
        t == Engine.TRK_X + 1 -> "⬡ Euklid"
        else -> "🎤 Harmonizer"
    }

    /** Anteil der Takte, in denen die Spur im Teil spielt (0..1). */
    private fun cover(t: Int, p: Part): Float {
        var c = 0
        for (b in p.b0..p.b1) if (Engine.trkCell(t, b) > 0) c++
        return c.toFloat() / (p.b1 - p.b0 + 1)
    }

    private fun patOf(t: Int, p: Part): Int {
        for (b in p.b0..p.b1) { val v = Engine.trkCell(t, b); if (v > 0) return v }
        return InstState.curPat[t - Engine.TRK_INST] + 1
    }

    private fun setTrack(t: Int, p: Part, v: Int) {
        for (b in p.b0..p.b1) Engine.trkSet(t, b, v)
        if (v > 0) ed.setBreak(t, p.b0)
        if (p.b1 + 1 < Engine.ARR_MAX && Engine.trkCell(t, p.b1 + 1) > 0) ed.setBreak(t, p.b1 + 1)
    }

    // ---- Effekt je Teil (aus der Automation erkannt, damit er überall mitwandert)
    private val CUT_LANES = intArrayOf(2, 5, 7, 9)
    private fun laneEnds(l: Int, p: Part): Pair<Float, Float>? {
        val a = Engine.auto[l]
        val i0 = p.b0 * Engine.AUTO_RES; val i1 = (p.b1 + 1) * Engine.AUTO_RES - 1
        val f = a[i0]; val e = a[i1]
        return if (f.isNaN() || e.isNaN()) null else f to e
    }
    private fun fxOf(p: Part): Int {
        laneEnds(11, p)?.let { (f, e) -> if (e < f - 0.3f) return 3; if (f < e - 0.3f) return 2 }
        for (l in CUT_LANES) laneEnds(l, p)?.let { (f, e) -> if (e > f + 0.3f) return 1; if (f > e + 0.3f) return 4 }
        return 0
    }
    private fun setFx(p: Part, fx: Int) {
        val r = Engine.AUTO_RES
        val i0 = p.b0 * r; val i1 = (p.b1 + 1) * r
        val n = (i1 - i0).toFloat()
        for (l in CUT_LANES + intArrayOf(11)) for (i in i0 until i1) Engine.auto[l][i] = Float.NaN
        for (i in i0 until i1) {
            val x = (i - i0) / n
            when (fx) {
                1 -> for (l in CUT_LANES) Engine.auto[l][i] = 0.12f + 0.78f * x
                4 -> for (l in CUT_LANES) Engine.auto[l][i] = 0.85f - 0.7f * x
                2 -> Engine.auto[11][i] = 0.8f * x
                3 -> Engine.auto[11][i] = 0.8f * (1f - x)
            }
        }
    }

    // ================================================================ Layout & Zeichnen

    private val box = RectF()
    private val top = Array(5) { RectF() }
    private val list = RectF()
    private class Hit(val r: RectF, val kind: Int, val part: Int, val arg: Int)
    private val hits = ArrayList<Hit>()
    private val H_NAME = 1; private val H_MINUS = 2; private val H_PLUS = 3; private val H_PLAY = 4; private val H_TRACK = 5; private val H_PAT = 6
    private val H_FX = 7; private val H_UP = 8; private val H_DOWN = 9; private val H_DUP = 10; private val H_DEL = 11; private val H_ADD = 12; private val H_SUGGEST = 13
    private var contentH = 0f

    private fun rr(c: Canvas, r: RectF, rad: Float, col: Int) { fill.color = col; c.drawRoundRect(r, rad, rad, fill) }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int, size: Float = 12f) {
        rr(c, r, dp(10f), if (on) col else OFF)
        stroke.strokeWidth = dp(1f); stroke.color = if (on) col else Color.argb(60, 255, 255, 255)
        c.drawRoundRect(r, dp(10f), dp(10f), stroke)
        text.color = if (on) Color.BLACK else INK
        text.textSize = min(dp(size), r.height() * 0.42f)
        c.drawText(fitLabel(text, label, r.width() - dp(8f), text.textSize, dp(7f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        box.set(area)
        fill.color = BG
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        if (!Engine.songRec) ensureParts()
        val pad = dp(10f)
        // Kopf: ✕ · ▶ · ● · ↶ · Profi
        val bh = dp(48f)
        val ws = floatArrayOf(0.13f, 0.27f, 0.27f, 0.13f, 0.2f)
        var x = box.left + pad
        val avail = box.width() - 2 * pad - 4 * dp(6f)
        for (i in 0 until 5) { top[i].set(x, box.top + pad, x + avail * ws[i], box.top + pad + bh); x += avail * ws[i] + dp(6f) }
        val playing = Engine.seqPlaying
        btn(c, top[0], "✕", false, acc, 18f)
        btn(c, top[1], if (playing) "■ Stopp" else "▶ Song spielen", playing, GREEN, 13f)
        btn(c, top[2], if (Engine.songRec) "● nimmt auf …" else "● Live aufnehmen", Engine.songRec, RED, 13f)
        btn(c, top[3], "↶", false, BLUE, 18f)
        btn(c, top[4], "Spuren ›", false, BLUE, 12f)
        val ps = parts()
        bold.textSize = dp(19f); bold.color = INK
        c.drawText("Dein Song", box.left + pad, top[0].bottom + dp(30f), bold)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(11f); text.color = DIM
        val sec = Engine.songBars * 240.0 / max(40f, Engine.currentBpm().let { if (it > 0f) it else 120f })
        c.drawText(if (ps.isEmpty()) "noch leer" else "${ps.size} Teile · ${Engine.songBars} Takte · " + String.format(java.util.Locale.GERMANY, "%d:%02d min", (sec / 60).toInt(), (sec % 60).toInt()),
            box.left + pad + dp(118f), top[0].bottom + dp(29f), text)
        text.textAlign = Paint.Align.CENTER
        list.set(box.left + pad, top[0].bottom + dp(44f), box.right - pad, box.bottom - pad)
        hits.clear()
        c.save()
        c.clipRect(list)
        scroll = scroll.coerceIn(0f, max(0f, contentH - list.height()))
        var y = list.top - scroll
        val trks = tracks()
        val cur = if (Engine.seqPlaying && Engine.songMode) Engine.songBar() else -1
        if (ps.isEmpty()) y = drawEmpty(c, y)
        for ((pi, p) in ps.withIndex()) y = drawCard(c, pi, p, ps.size, trks, y, cur) + dp(12f)
        // „+ Teil“
        val add = RectF(list.left, y, list.right, y + dp(56f))
        rr(c, add, dp(14f), Color.rgb(26, 30, 26))
        stroke.color = GREEN; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(add, dp(14f), dp(14f), stroke)
        text.color = GREEN; text.textSize = dp(15f)
        c.drawText("＋  Teil anhängen", add.centerX(), add.centerY() + dp(5f), text)
        hits.add(Hit(add, H_ADD, -1, 0))
        y += dp(70f)
        if (ps.isNotEmpty()) {
            text.color = DIM; text.textSize = dp(10f)
            c.drawText("Tipp: Namen antippen = Art ändern · Schalter = Spur spielt im ganzen Teil · Buchstabe = Pattern A–D", list.centerX(), y, text)
            y += dp(20f)
        }
        contentH = y + scroll - list.top
        c.restore()
        if (playing) host.postInvalidateOnAnimation()
    }

    private fun drawEmpty(c: Canvas, y0: Float): Float {
        val r = RectF(list.left, y0, list.right, y0 + dp(150f))
        rr(c, r, dp(16f), CARD)
        bold.textSize = dp(15f); bold.color = INK
        c.drawText("So geht’s", r.left + dp(16f), r.top + dp(28f), bold)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(11.5f); text.color = DIM
        c.drawText(fitLabel(text, "1. Teile anlegen (Intro, Hauptteil, Ending …)", r.width() - dp(32f), dp(11.5f), dp(8f)), r.left + dp(16f), r.top + dp(50f), text)
        c.drawText(fitLabel(text, "2. pro Teil die Spuren anschalten, die spielen sollen", r.width() - dp(32f), dp(11.5f), dp(8f)), r.left + dp(16f), r.top + dp(68f), text)
        c.drawText(fitLabel(text, "3. ▶ Song spielen – oder einfach ● live aufnehmen", r.width() - dp(32f), dp(11.5f), dp(8f)), r.left + dp(16f), r.top + dp(86f), text)
        text.textAlign = Paint.Align.CENTER
        val b = RectF(r.left + dp(16f), r.bottom - dp(52f), r.right - dp(16f), r.bottom - dp(12f))
        btn(c, b, "✦ Vorschlag bauen: Intro · Hauptteil · Break · Drop · Ending", true, GREEN, 12f)
        hits.add(Hit(b, H_SUGGEST, -1, 0))
        return r.bottom + dp(14f)
    }

    private fun drawCard(c: Canvas, pi: Int, p: Part, count: Int, trks: List<Int>, y0: Float, cur: Int): Float {
        val w = list.width()
        val col = SEC_COLS[p.kind % SEC_COLS.size]
        val inner = dp(12f)
        val chipH = dp(44f)
        val cols = if (w > dp(560f)) 5 else 3
        val chipW = (w - 2 * inner - (cols - 1) * dp(6f)) / cols
        val rowsN = (trks.size + cols - 1) / cols
        val h = dp(52f) + rowsN * (chipH + dp(6f)) + dp(54f)
        val r = RectF(list.left, y0, list.right, y0 + h)
        if (r.bottom < list.top - 10 || r.top > list.bottom + 10) return r.bottom
        rr(c, r, dp(16f), CARD)
        fill.color = col
        c.drawRoundRect(r.left, r.top, r.left + dp(6f), r.bottom, dp(3f), dp(3f), fill)
        // läuft gerade?
        val playing = cur in p.b0..p.b1
        if (playing) {
            stroke.color = col; stroke.strokeWidth = dp(2.5f)
            c.drawRoundRect(r, dp(16f), dp(16f), stroke)
            val f = (Engine.autoPos / Engine.AUTO_RES - p.b0) / (p.b1 - p.b0 + 1)
            fill.color = Color.argb(60, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(r.left, r.top, r.left + r.width() * f.coerceIn(0f, 1f), r.top + dp(46f), dp(16f), dp(16f), fill)
        }
        // Kopf: Nummer · Name (antippen) · Länge − + · ▶
        bold.textSize = dp(11f); bold.color = DIM
        c.drawText("${pi + 1}", r.left + inner + dp(2f), r.top + dp(30f), bold)
        val bw = dp(40f)
        val play = RectF(r.right - inner - bw, r.top + dp(8f), r.right - inner, r.top + dp(44f))
        val plus = RectF(play.left - dp(8f) - bw, play.top, play.left - dp(8f), play.bottom)
        val lenR = RectF(plus.left - dp(70f), play.top, plus.left - dp(2f), play.bottom)
        val minus = RectF(lenR.left - dp(2f) - bw, play.top, lenR.left - dp(2f), play.bottom)
        val nameR = RectF(r.left + inner + dp(16f), r.top + dp(8f), minus.left - dp(8f), r.top + dp(44f))
        rr(c, nameR, dp(10f), Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)))
        bold.textSize = dp(14f); bold.color = Color.BLACK
        c.drawText(fitLabel(bold, Engine.SECTION_NAMES[p.kind] + " ▾", nameR.width() - dp(12f), dp(14f), dp(8f)), nameR.left + dp(8f), nameR.centerY() + dp(5f), bold)
        hits.add(Hit(nameR, H_NAME, pi, 0))
        btn(c, minus, "−", false, BLUE, 18f); hits.add(Hit(minus, H_MINUS, pi, 0))
        text.color = INK; text.textSize = dp(13f)
        c.drawText("${p.b1 - p.b0 + 1} Takte", lenR.centerX(), lenR.centerY() + dp(5f), text)
        btn(c, plus, "＋", false, BLUE, 18f); hits.add(Hit(plus, H_PLUS, pi, 0))
        btn(c, play, "▶", playing, GREEN, 16f); hits.add(Hit(play, H_PLAY, pi, 0))
        // Spur-Schalter
        var cy = r.top + dp(52f)
        for ((k, t) in trks.withIndex()) {
            val cx = r.left + inner + (k % cols) * (chipW + dp(6f))
            val ry = cy + (k / cols) * (chipH + dp(6f))
            val chip = RectF(cx, ry, cx + chipW, ry + chipH)
            val cv = cover(t, p)
            val on = cv >= 0.999f
            val part = cv > 0f && !on
            val tc = if (isInst(t)) PAT_COL[(patOf(t, p) - 1).coerceIn(0, 3)] else col
            rr(c, chip, dp(10f), if (on) tc else if (part) Color.argb(110, Color.red(tc), Color.green(tc), Color.blue(tc)) else OFF)
            stroke.color = if (on || part) tc else Color.argb(50, 255, 255, 255); stroke.strokeWidth = dp(1f)
            c.drawRoundRect(chip, dp(10f), dp(10f), stroke)
            val patW = if (isInst(t) && cv > 0f) dp(30f) else 0f
            text.color = if (on) Color.BLACK else INK
            text.textSize = dp(11.5f)
            c.drawText(fitLabel(text, trackLabel(t), chip.width() - patW - dp(8f), dp(11.5f), dp(7f)), chip.left + (chip.width() - patW) / 2, chip.centerY() - dp(1f), text)
            text.textSize = dp(8.5f); text.color = if (on) Color.argb(160, 0, 0, 0) else DIM
            c.drawText(if (on) "spielt" else if (part) "teilweise" else "aus", chip.left + (chip.width() - patW) / 2, chip.centerY() + dp(12f), text)
            hits.add(Hit(chip, H_TRACK, pi, t))
            if (patW > 0f) {
                val pr = RectF(chip.right - patW - dp(4f), chip.top + dp(6f), chip.right - dp(4f), chip.bottom - dp(6f))
                rr(c, pr, dp(7f), Color.argb(if (on) 90 else 200, 0, 0, 0))
                text.color = Color.WHITE; text.textSize = dp(13f)
                c.drawText(InstState.patLetter(patOf(t, p) - 1), pr.centerX(), pr.centerY() + dp(4.5f), text)
                hits.add(Hit(pr, H_PAT, pi, t))
            }
        }
        cy += rowsN * (chipH + dp(6f)) + dp(4f)
        // Fuß: Effekt · ↑ ↓ ⧉ 🗑
        val fx = fxOf(p)
        val sw = dp(44f)
        val fxR = RectF(r.left + inner, cy, r.right - inner - 4 * sw - 3 * dp(6f) - dp(8f), cy + dp(40f))
        btn(c, fxR, (if (fxR.width() > dp(150f)) "Effekt: " else "") + FX_NAMES[fx], fx != 0, Color.rgb(250, 225, 110), 11.5f)
        hits.add(Hit(fxR, H_FX, pi, 0))
        val acts = arrayOf("↑" to H_UP, "↓" to H_DOWN, "⧉" to H_DUP, "🗑" to H_DEL)
        var ax = r.right - inner - 4 * sw - 3 * dp(6f)
        for ((lb, id) in acts) {
            val ar = RectF(ax, cy, ax + sw, cy + dp(40f))
            val enabled = !(id == H_UP && pi == 0) && !(id == H_DOWN && pi == count - 1)
            btn(c, ar, lb, false, if (id == H_DEL) RED else BLUE, 16f)
            if (!enabled) { fill.color = Color.argb(150, 22, 23, 30); c.drawRoundRect(ar, dp(10f), dp(10f), fill) }
            else hits.add(Hit(ar, id, pi, 0))
            ax += sw + dp(6f)
        }
        text.textSize = dp(8f); text.color = DIM
        c.drawText("hoch · runter · verdoppeln · löschen", r.right - inner - (4 * sw + 3 * dp(6f)) / 2, cy + dp(50f), text)
        return r.bottom
    }

    // ================================================================ Bedienung

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    private var downX = 0f; private var downY = 0f; private var scroll0 = 0f; private var dragging = false; private var downT = 0L

    fun touch(e: MotionEvent) {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = x; downY = y; scroll0 = scroll; dragging = false; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(y - downY) > dp(10f)) dragging = true
                if (dragging) scroll = (scroll0 - (y - downY)).coerceIn(0f, max(0f, contentH - list.height()))
            }
            MotionEvent.ACTION_UP -> if (!dragging) tap(x, y, SystemClock.uptimeMillis() - downT > 500)
        }
        host.invalidate()
    }

    private fun tap(x: Float, y: Float, long: Boolean) {
        for (i in 0 until 5) if (top[i].contains(x, y)) { topBtn(i); return }
        val h = hits.lastOrNull { it.r.contains(x, y) && list.contains(x, y) } ?: return
        haptic()
        val ps = parts()
        val p = ps.getOrNull(h.part)
        when (h.kind) {
            H_SUGGEST -> { ed.scaffold(); onHint?.invoke("✦ Vorschlag gebaut – jetzt pro Teil die Schalter anpassen, ▶ zum Anhören") }
            H_ADD -> addPart()
            H_NAME -> if (p != null) onPickSection?.invoke(p.b0)
            H_MINUS -> if (p != null) { if (p.b1 > p.b0) { ed.partShorter(p.b0, p.b1) } }
            H_PLUS -> if (p != null) { if (Engine.songBars < Engine.ARR_MAX) ed.partLonger(p.b0, p.b1) else onHint?.invoke("Maximal 64 Takte") }
            H_PLAY -> if (p != null) {
                if (Engine.seqPlaying) Engine.transportStop()
                Engine.songMode = true; Engine.playFromBar = p.b0; Engine.transportPlay(false)
            }
            H_TRACK -> if (p != null) {
                val t = h.arg
                if (long && isInst(t)) { onOpenInst?.invoke(t - Engine.TRK_INST, patOf(t, p) - 1); return }
                ed.snapshot()
                val on = cover(t, p) >= 0.999f
                setTrack(t, p, if (on) 0 else if (isInst(t)) patOf(t, p) else 1)
                Engine.songMode = true
                if (!on && isInst(t) && !InstState.patHasNotes(t - Engine.TRK_INST, patOf(t, p) - 1)) onHint?.invoke("${trackLabel(t)}: Pattern ${InstState.patLetter(patOf(t, p) - 1)} ist noch leer – lange drücken öffnet die Noten")
            }
            H_PAT -> if (p != null) {
                val t = h.arg
                ed.snapshot()
                val v = patOf(t, p) % InstState.PATS + 1
                setTrack(t, p, v)
                onHint?.invoke("${trackLabel(t)} spielt hier Pattern ${InstState.patLetter(v - 1)}" + if (!InstState.patHasNotes(t - Engine.TRK_INST, v - 1)) " (noch leer – Schalter lange drücken = Noten)" else "")
            }
            H_FX -> if (p != null) {
                ed.snapshot()
                val nf = (fxOf(p) + 1) % FX_NAMES.size
                setFx(p, nf)
                onHint?.invoke(when (nf) {
                    1 -> "Aufbau: Filter von Bass und Synths öffnen sich über den Teil"
                    2 -> "Einblenden: der Teil wird von leise nach laut"
                    3 -> "Ausblenden: der Teil wird leiser bis zur Stille"
                    4 -> "Filter zu: Bass und Synths werden dumpfer"
                    else -> "Kein Effekt in diesem Teil"
                })
            }
            H_UP, H_DOWN -> if (p != null) ed.partMove(p.b0, p.b1, if (h.kind == H_UP) -1 else 1)
            H_DUP -> if (p != null) { if (Engine.songBars + (p.b1 - p.b0 + 1) <= Engine.ARR_MAX) ed.partDuplicate(p.b0, p.b1) else onHint?.invoke("Kein Platz mehr (64 Takte)") }
            H_DEL -> if (p != null) { ed.partDelete(p.b0, p.b1); onHint?.invoke("Teil gelöscht – ↶ holt ihn zurück") }
        }
    }

    private fun topBtn(i: Int) {
        haptic()
        when (i) {
            0 -> close()
            1 -> if (Engine.seqPlaying) Engine.transportStop() else {
                if (Engine.songBars <= 0) { onHint?.invoke("Erst Teile anlegen"); return }
                Engine.songMode = true; Engine.playFromBar = 0; Engine.transportPlay(false)
            }
            2 -> if (Engine.songRec) { Engine.transportStop(); ensureParts(); onHint?.invoke("Aufnahme fertig – in Teile à 8 Takte gegliedert, jetzt anpassen") } else {
                ed.snapshot()
                Engine.songRecStart(Engine.songBars.coerceAtMost(Engine.ARR_MAX - 1))
                onHint?.invoke("● Nach 1 Takt Einzählen läuft die Aufnahme: spiel Pads und Instrumente wie im Song – jeder Takt wird gespeichert · nochmal tippen beendet")
            }
            3 -> ed.undoStep()
            4 -> { close(); onProfi?.invoke() }
        }
    }

    private fun addPart() {
        ed.snapshot()
        val ps = parts()
        val at = Engine.songBars
        if (at + 4 > Engine.ARR_MAX) { onHint?.invoke("Maximal 64 Takte"); return }
        val len = min(8, Engine.ARR_MAX - at)
        val kind = when {
            ps.isEmpty() -> 0
            ps.size == 1 -> 1
            else -> 1
        }
        Engine.arrSection[at] = kind
        Engine.songBars = at + len
        // Spuren vom vorigen Teil übernehmen (meist will man daran anknüpfen)
        ps.lastOrNull()?.let { prev ->
            for (t in tracks()) {
                val v = Engine.trkCell(t, prev.b1)
                if (v > 0) { for (b in at until at + len) Engine.trkSet(t, b, v); ed.setBreak(t, at) }
            }
        }
        Engine.songMode = true
        onHint?.invoke("Neuer Teil (${Engine.SECTION_NAMES[kind]}, 8 Takte) – Name antippen ändert die Art, Schalter wählen die Spuren")
        scroll = Float.MAX_VALUE
    }
}
