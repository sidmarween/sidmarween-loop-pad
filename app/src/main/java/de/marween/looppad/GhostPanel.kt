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

/** Ghost-Producer: 3 Spuren (Drums, Bass, Synth) als Mini-Step-Sequencer + Genre-Karten zum Draufziehen. */
class GhostPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    var onKeyPick: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val LANE_COLS = intArrayOf(Color.rgb(255, 110, 80), Color.rgb(90, 200, 255), Color.rgb(190, 130, 255))
    private val LANE_NAMES = arrayOf("🥁 DRUMS", "🔊 BASS (303)", "🎹 SYNTH")

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF()
    private val bFollow = RectF(); private val bKey = RectF(); private val bFilter = RectF()
    private val lanes = Array(3) { RectF() }
    private val laneMute = Array(3) { RectF() }
    private val laneVary = Array(3) { RectF() }
    private val laneGrid = Array(3) { RectF() }
    private val tabs = Array(3) { RectF() }
    private val list = RectF()
    private var tab = 0
    private var scroll = 0f
    private val cardRects = ArrayList<Pair<RectF, Int>>()

    // Ziehen
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var downCard = -1
    private var dragging = false
    private var scrolling = false
    private var dragX = 0f; private var dragY = 0f
    private var scroll0 = 0f

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    private fun cards(t: Int): List<Pair<String, String>> = when (t) {
        0 -> InstPresets.DRUMS.map { it.genre to it.name }
        1 -> InstPresets.ACIDS.map { it.genre to it.name }
        else -> InstPresets.SYNTHS.map { it.genre to it.name }
    }

    private fun drumRows(): List<Int> {
        val st = InstState
        return (0 until InstState.DV).filter { v -> (0 until min(16, st.drumLen)).any { st.drumSteps[v][it] > 0 } }.take(4)
    }

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(8f)
        val bh = dp(34f)
        var y = box.top + m
        bClose.set(box.right - m - dp(42f), y, box.right - m, y + bh)
        bOn.set(bClose.left - dp(6f) - dp(96f), y, bClose.left - dp(6f), y + bh)
        y += bh + dp(22f)
        val cw = (box.width() - 2 * m - 2 * dp(6f)) / 3f
        bFollow.set(box.left + m, y, box.left + m + cw, y + dp(30f))
        bKey.set(bFollow.right + dp(6f), y, bFollow.right + dp(6f) + cw, y + dp(30f))
        bFilter.set(bKey.right + dp(6f), y, box.right - m, y + dp(30f))
        y += dp(38f)
        val laneH = max(dp(70f), box.height() * 0.13f)
        for (i in 0 until 3) {
            lanes[i].set(box.left + m, y, box.right - m, y + laneH)
            laneMute[i].set(lanes[i].right - dp(118f), lanes[i].top + dp(4f), lanes[i].right - dp(64f), lanes[i].top + dp(26f))
            laneVary[i].set(lanes[i].right - dp(58f), lanes[i].top + dp(4f), lanes[i].right - dp(4f), lanes[i].top + dp(26f))
            laneGrid[i].set(lanes[i].left + dp(6f), lanes[i].top + dp(30f), lanes[i].right - dp(6f), lanes[i].bottom - dp(5f))
            y += laneH + dp(6f)
        }
        y += dp(4f)
        val tw = (box.width() - 2 * m - 2 * dp(6f)) / 3f
        for (i in 0 until 3) tabs[i].set(box.left + m + i * (tw + dp(6f)), y, box.left + m + i * (tw + dp(6f)) + tw, y + dp(32f))
        y += dp(38f)
        list.set(box.left + m, y, box.right - m, box.bottom - m)
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(12f), r.height() * 0.45f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(7f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        fill.shader = null
        fill.color = Color.argb(215, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(15, 16, 21)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = acc
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("👻 GHOST-PRODUCER", box.left + dp(12f), bClose.centerY() + dp(1f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val f = Ghost.followed
        val status = when {
            !Ghost.on -> "aus – Karte antippen oder auf eine Spur ziehen startet"
            f >= 0 -> String.format(java.util.Locale.GERMANY, "folgt Deck %s · %.1f BPM · Tonart %s", "AB"[f], Engine.currentBpm(), Ghost.keyName())
            else -> String.format(java.util.Locale.GERMANY, "kein Deck läuft – eigenes Tempo %.1f BPM", Engine.currentBpm())
        }
        c.drawText(fitLabel(text, status, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(15f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bOn, if (Ghost.on) "■ GHOST" else "▶ GHOST", Ghost.on, Color.rgb(120, 230, 140))
        btn(c, bClose, "✕", false, acc)
        btn(c, bFollow, "FOLGT: " + when (Ghost.follow) { 0 -> "DECK A"; 1 -> "DECK B"; else -> "AUTO" }, false, acc)
        btn(c, bKey, "TONART: " + if (Ghost.keyAuto) "AUTO" else Keys.name(Ghost.keyManual), !Ghost.keyAuto, acc)
        btn(c, bFilter, "DRUMS: " + Ghost.FILTERS[Ghost.drumFilter], Ghost.drumFilter != 0, LANE_COLS[0])
        for (i in 0 until 3) drawLane(c, i)
        for (i in 0 until 3) btn(c, tabs[i], arrayOf("DRUMS", "BASS", "SYNTH")[i] + " · Karten", tab == i, LANE_COLS[i])
        drawCards(c)
        if (dragging && downCard >= 0) {
            val (g, n) = cards(tab)[downCard]
            val r = RectF(dragX - dp(70f), dragY - dp(24f), dragX + dp(70f), dragY + dp(24f))
            fill.color = Color.argb(230, Color.red(LANE_COLS[tab]), Color.green(LANE_COLS[tab]), Color.blue(LANE_COLS[tab]))
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            text.color = Color.BLACK; text.textSize = dp(11f)
            c.drawText(fitLabel(text, n, r.width() - dp(8f), dp(11f), dp(7f)), r.centerX(), r.centerY() + dp(4f), text)
            text.textSize = dp(8f)
            c.drawText(g, r.centerX(), r.top + dp(10f), text)
        }
        host.postInvalidateOnAnimation()
    }

    private fun laneOn(i: Int): Boolean = when (i) { 0 -> InstState.drumOn; 1 -> InstState.acidOn; else -> InstState.synTracks[0].on }

    private fun drawLane(c: Canvas, i: Int) {
        val r = lanes[i]
        val col = LANE_COLS[i]
        val hot = dragging && tab == i && r.contains(dragX, dragY)
        fill.color = if (hot) Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)) else Color.rgb(24, 25, 32)
        c.drawRoundRect(r, dp(10f), dp(10f), fill)
        stroke.strokeWidth = dp(if (hot) 2.5f else 1f); stroke.color = Color.argb(if (hot) 255 else 120, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(10f), dp(10f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(11f); text.color = col
        c.drawText(LANE_NAMES[i], r.left + dp(8f), r.top + dp(18f), text)
        text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(9.5f)
        c.drawText(fitLabel(text, Ghost.laneName[i], laneMute[i].left - r.left - dp(100f), dp(9.5f), dp(7f)), r.left + dp(100f), r.top + dp(18f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, laneMute[i], if (laneOn(i)) "AN" else "STUMM", laneOn(i), col)
        btn(c, laneVary[i], "🎲", false, col)
        // Mini-Sequencer: erster Takt (16 Schritte)
        val g = laneGrid[i]
        val cur = Engine.instCurrentStep(16)
        val sw = g.width() / 16f
        val st = InstState
        if (i == 0) {
            val rows = drumRows()
            val rh = g.height() / max(1, rows.size)
            for ((ri, v) in rows.withIndex()) {
                text.textAlign = Paint.Align.LEFT; text.textSize = min(dp(8f), rh * 0.6f); text.color = Color.argb(150, 255, 255, 255)
                for (k in 0 until 16) {
                    val on = st.drumSteps[v][k] > 0
                    val cell = RectF(g.left + k * sw + dp(1f), g.top + ri * rh + dp(1f), g.left + (k + 1) * sw - dp(1f), g.top + (ri + 1) * rh - dp(1f))
                    fill.color = if (on) col else if (k % 4 == 0) Color.rgb(44, 46, 56) else Color.rgb(34, 36, 44)
                    if (k == cur) fill.color = blend(fill.color, Color.WHITE, 0.35f)
                    c.drawRoundRect(cell, dp(2f), dp(2f), fill)
                }
                text.color = Color.WHITE
                c.drawText(st.drumNames[v], g.left + dp(2f), g.top + ri * rh + rh * 0.7f, text)
                text.textAlign = Paint.Align.CENTER
            }
            if (rows.isEmpty()) { text.textSize = dp(10f); text.color = Color.argb(140, 255, 255, 255); c.drawText("Drum-Karte hierher ziehen", g.centerX(), g.centerY() + dp(4f), text) }
        } else {
            val gate = if (i == 1) st.acidGate else st.synTracks[0].gate
            val semi = if (i == 1) st.acidSemi else st.synTracks[0].semi
            val any = (0 until 16).any { gate[it] }
            for (k in 0 until 16) {
                val cell = RectF(g.left + k * sw + dp(1f), g.top + dp(1f), g.left + (k + 1) * sw - dp(1f), g.bottom - dp(1f))
                fill.color = if (k % 4 == 0) Color.rgb(44, 46, 56) else Color.rgb(34, 36, 44)
                if (k == cur) fill.color = blend(fill.color, Color.WHITE, 0.3f)
                c.drawRoundRect(cell, dp(2f), dp(2f), fill)
                if (gate[k]) {
                    val y = g.bottom - (Math.floorMod(semi[k] + 12, 24) / 24f) * g.height()
                    fill.color = col
                    c.drawRoundRect(cell.left, y - dp(4f), cell.right, y + dp(4f), dp(2f), dp(2f), fill)
                }
            }
            if (!any) { text.textSize = dp(10f); text.color = Color.argb(140, 255, 255, 255); c.drawText(if (i == 1) "Bass-Karte hierher ziehen" else "Synth-Karte hierher ziehen", g.centerX(), g.centerY() + dp(4f), text) }
        }
    }

    private fun drawCards(c: Canvas) {
        cardRects.clear()
        val cs = cards(tab)
        val cols = 2
        val g = dp(6f)
        val cw = (list.width() - g) / cols
        val ch = dp(48f)
        val rows = (cs.size + cols - 1) / cols
        val maxScroll = max(0f, rows * (ch + g) - list.height())
        scroll = scroll.coerceIn(0f, maxScroll)
        c.save()
        c.clipRect(list)
        for ((i, pr) in cs.withIndex()) {
            val r = RectF(list.left + (i % cols) * (cw + g), list.top + (i / cols) * (ch + g) - scroll, list.left + (i % cols) * (cw + g) + cw, list.top + (i / cols) * (ch + g) + ch - scroll)
            if (r.bottom < list.top || r.top > list.bottom) continue
            cardRects.add(r to i)
            val col = LANE_COLS[tab]
            fill.color = if (dragging && downCard == i) Color.rgb(50, 52, 62) else Color.rgb(30, 32, 40)
            c.drawRoundRect(r, dp(9f), dp(9f), fill)
            fill.color = col
            c.drawRoundRect(RectF(r.left, r.top, r.left + dp(5f), r.bottom), dp(3f), dp(3f), fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(9f); text.color = col
            c.drawText(pr.first, r.left + dp(11f), r.top + dp(15f), text)
            text.textSize = dp(12f); text.color = Color.WHITE
            c.drawText(fitLabel(text, pr.second, r.width() - dp(16f), dp(12f), dp(7f)), r.left + dp(11f), r.top + dp(34f), text)
            text.textAlign = Paint.Align.CENTER
        }
        c.restore()
        if (maxScroll > 0f) {
            val h = list.height() * list.height() / (list.height() + maxScroll)
            val y = list.top + (list.height() - h) * scroll / maxScroll
            fill.color = Color.argb(100, 255, 255, 255)
            c.drawRoundRect(list.right - dp(3f), y, list.right, y + h, dp(2f), dp(2f), fill)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun startGhost() {
        if (!Ghost.on) {
            Ghost.on = true
            if (!Engine.seqPlaying) Engine.seqPlay()
        }
    }

    private fun applyCard(lane: Int, i: Int) {
        Engine.pushPatternUndo("Ghost")
        when (lane) {
            0 -> Ghost.applyDrum(InstPresets.DRUMS[i])
            1 -> Ghost.applyAcid(InstPresets.ACIDS[i])
            else -> Ghost.applySynth(InstPresets.SYNTHS[i])
        }
        startGhost()
        onHint?.invoke("👻 ${Ghost.laneName[lane]} – läuft im Takt des Tracks")
        haptic()
    }

    fun touch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downT = SystemClock.uptimeMillis()
                dragging = false; scrolling = false; downCard = -1
                if (list.contains(x, y)) {
                    downCard = cardRects.firstOrNull { it.first.contains(x, y) }?.second ?: -1
                    scroll0 = scroll
                    return
                }
                tapControls(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (downCard < 0 && !list.contains(downX, downY)) return
                val dx = x - downX
                val dy = y - downY
                if (!dragging && !scrolling) {
                    val held = SystemClock.uptimeMillis() - downT > 250
                    if (downCard >= 0 && (held || abs(dx) > abs(dy) * 1.2f) && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) { dragging = true; haptic() }
                    else if (abs(dy) > dp(8f)) scrolling = true
                }
                if (dragging) { dragX = x; dragY = y }
                if (scrolling) scroll = scroll0 - dy
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && downCard >= 0) {
                    val lane = lanes.indexOfFirst { it.contains(x, y) }
                    when {
                        lane == tab -> applyCard(lane, downCard)
                        lane >= 0 -> onHint?.invoke("Diese Karte gehört auf die ${arrayOf("DRUMS", "BASS", "SYNTH")[tab]}-Spur")
                    }
                } else if (!scrolling && downCard >= 0 && list.contains(x, y)) applyCard(tab, downCard)
                dragging = false; scrolling = false; downCard = -1
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; scrolling = false; downCard = -1 }
        }
    }

    private fun tapControls(x: Float, y: Float) {
        val st = InstState
        when {
            bClose.contains(x, y) -> close()
            bOn.contains(x, y) -> {
                if (Ghost.on) { Ghost.stop(); onHint?.invoke("Ghost aus – die Spuren laufen stumm weiter, ▶ GHOST holt sie zurück"); st.drumOn = false; st.acidOn = false; st.synTracks[0].on = false }
                else { startGhost(); st.drumOn = true; st.acidOn = true; st.synTracks[0].on = true; onHint?.invoke("Ghost an – folgt dem laufenden Deck") }
            }
            bFollow.contains(x, y) -> Ghost.follow = when (Ghost.follow) { -1 -> 0; 0 -> 1; else -> -1 }
            bKey.contains(x, y) -> onKeyPick?.invoke()
            bFilter.contains(x, y) -> { Ghost.drumFilter = (Ghost.drumFilter + 1) % Ghost.FILTERS.size; onHint?.invoke("Neue Drum-Karten: " + Ghost.FILTERS[Ghost.drumFilter] + " – „nur Hats“ legt sich über die Kick des Tracks, ohne doppelte Bassdrum") }
            else -> {
                for (i in 0 until 3) {
                    if (tabs[i].contains(x, y)) { tab = i; scroll = 0f; return }
                    if (laneMute[i].contains(x, y)) {
                        when (i) { 0 -> st.drumOn = !st.drumOn; 1 -> st.acidOn = !st.acidOn; else -> st.synTracks[0].on = !st.synTracks[0].on }
                        haptic(); return
                    }
                    if (laneVary[i].contains(x, y)) {
                        Engine.pushPatternUndo("Ghost Variation")
                        when (i) { 0 -> Ghost.varyDrums(); 1 -> Ghost.varyBass(); else -> Ghost.varySynth() }
                        startGhost()
                        onHint?.invoke("🎲 ${Ghost.laneName[i]}")
                        haptic(); return
                    }
                    val g = laneGrid[i]
                    if (g.contains(x, y)) {
                        val k = ((x - g.left) / (g.width() / 16f)).toInt().coerceIn(0, 15)
                        if (i == 0) {
                            val rows = drumRows()
                            if (rows.isEmpty()) return
                            val v = rows[((y - g.top) / (g.height() / rows.size)).toInt().coerceIn(0, rows.size - 1)]
                            val nv = if (st.drumSteps[v][k] > 0) 0 else 1
                            var kk = k
                            while (kk < InstState.MAX) { st.drumSteps[v][kk] = nv; kk += 16 }
                        } else {
                            val t = st.synTracks[0]
                            val gate = if (i == 1) st.acidGate else t.gate
                            val tie = if (i == 1) st.acidTie else t.tie
                            val semi = if (i == 1) st.acidSemi else t.semi
                            val nv = !gate[k]
                            val prev = (k - 1 downTo 0).firstOrNull { gate[it] }?.let { semi[it] } ?: 0
                            var kk = k
                            while (kk < InstState.MAX) { gate[kk] = nv; tie[kk] = false; if (nv) semi[kk] = prev; kk += 16 }
                        }
                        startGhost()
                        haptic(); return
                    }
                }
            }
        }
    }
}
