package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** 🧫 GROOVE ORGANISM: zeigt den lebenden 909-Beat, Regeln als Regler, Stimmen sperren per Tipp auf den Namen. */
class OrganismPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF()
    private val btns = Array(5) { RectF() }
    private val sliders = Array(4) { RectF() }
    private val SL = arrayOf("MUTATION", "DICHTE", "GHOST-NOTES", "AKZENTE")
    private val grid = RectF()
    private val names = Array(InstState.DV) { RectF() }
    private var drag = -1
    private var lastX = 0f

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    private fun get(i: Int) = when (i) { 0 -> GrooveOrganism.mutation; 1 -> GrooveOrganism.density; 2 -> GrooveOrganism.ghosts; else -> GrooveOrganism.accents }
    private fun set(i: Int, v: Float) { val c = v.coerceIn(0f, 1f); when (i) { 0 -> GrooveOrganism.mutation = c; 1 -> GrooveOrganism.density = c; 2 -> GrooveOrganism.ghosts = c; else -> GrooveOrganism.accents = c } }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(6f); val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = bClose.bottom + dp(20f)
        val bw = (w - 4 * g) / 5
        for (k in 0 until 5) btns[k].set(box.left + pad + k * (bw + g), r1, box.left + pad + k * (bw + g) + bw, r1 + bh)
        val r2 = r1 + bh + g
        val sw = (w - g) / 2
        for (i in 0 until 4) { val r = i / 2; val c = i % 2; sliders[i].set(box.left + pad + c * (sw + g), r2 + r * (dp(38f) + g), box.left + pad + c * (sw + g) + sw, r2 + r * (dp(38f) + g) + dp(38f)) }
        grid.set(box.left + pad + dp(42f), sliders[3].bottom + dp(14f), box.right - pad, box.bottom - pad - dp(20f))
        val rh = grid.height() / InstState.DV
        for (v in 0 until InstState.DV) names[v].set(box.left + pad, grid.top + v * rh + dp(1f), grid.left - dp(4f), grid.top + (v + 1) * rh - dp(1f))
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(24, 30, 26)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f); stroke.color = col
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11f), r.height() * 0.38f)
        c.drawText(fitLabel(text, label, r.width() - dp(5f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(150, 255, 160)
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(10, 18, 12)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🧫 GROOVE ORGANISM · 909", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val o = GrooveOrganism
        c.drawText(fitLabel(text, (if (o.on) (if (o.frozen) "eingefroren" else "lebt") else "schläft") + " · Generation ${o.generation}" + (if (o.lastChange.isNotEmpty()) " · zuletzt ${o.lastChange}" else ""), box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, btns[0], if (o.on) "■ RUHE" else "▶ LEBEN", o.on, Color.rgb(140, 230, 170))
        btn(c, btns[1], "❄ EINFRIEREN", o.frozen, Color.rgb(140, 200, 255))
        btn(c, btns[2], "⟲ ZURÜCK", false, col)
        btn(c, btns[3], "✓ ÜBERNEHMEN", false, col)
        btn(c, btns[4], if (o.anchor) "⚓ ANKER" else "ANKER AUS", o.anchor, Color.rgb(255, 210, 90))
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(22, 34, 26); c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(SL[i], r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        val st = InstState
        val len = st.drumLen.coerceIn(16, InstState.MAX)
        val cw = grid.width() / len
        val rh = grid.height() / InstState.DV
        for (v in 0 until InstState.DV) {
            val nr = names[v]
            fill.color = if (o.locked[v]) Color.rgb(90, 60, 30) else Color.rgb(28, 36, 30)
            c.drawRoundRect(nr, dp(4f), dp(4f), fill)
            text.color = Color.WHITE; text.textSize = min(dp(10f), nr.height() * 0.55f)
            c.drawText((if (o.locked[v]) "🔒" else "") + st.drumNames[v], nr.centerX(), nr.centerY() + text.textSize * 0.36f, text)
            for (k in 0 until len) {
                val x0 = grid.left + k * cw; val y0 = grid.top + v * rh
                val on = st.drumSteps[v][k]
                val ch = o.changed.contains(v * 100 + k)
                fill.color = when {
                    on == 0 -> if (k % 4 == 0) Color.rgb(30, 36, 32) else Color.rgb(20, 24, 22)
                    on == 2 -> Color.rgb(255, 220, 120)
                    else -> Color.argb((90 + 165 * st.drumVel[v][k]).toInt().coerceIn(0, 255), 120, 255, 150)
                }
                c.drawRect(x0 + 0.8f, y0 + 0.8f, x0 + cw - 0.8f, y0 + rh - 0.8f, fill)
                if (ch) { stroke.color = Color.WHITE; stroke.strokeWidth = dp(1.5f); c.drawRect(x0 + 1f, y0 + 1f, x0 + cw - 1f, y0 + rh - 1f, stroke) }
            }
        }
        text.color = Color.argb(150, 255, 255, 255); text.textSize = dp(9.5f)
        c.drawText("Stimmen-Namen antippen = sperren · weiß umrandet = gerade mutiert", box.centerX(), box.bottom - dp(12f), text)
        host.postInvalidateOnAnimation()
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                for (s in 0 until 4) if (sliders[s].contains(x, y)) { drag = s; lastX = x; return }
                val o = GrooveOrganism
                when {
                    bClose.contains(x, y) -> { close(); haptic() }
                    btns[0].contains(x, y) -> {
                        haptic()
                        if (o.on) o.on = false else { o.start(); if (!Engine.seqPlaying) Engine.transportPlay(false) }
                        onHint?.invoke(if (o.on) "🧫 Der Beat lebt: an jeder Eins verändert er sich ein wenig – ❄ hält ihn fest" else "Organismus schläft – der Beat bleibt wie er ist")
                    }
                    btns[1].contains(x, y) -> { haptic(); o.frozen = !o.frozen }
                    btns[2].contains(x, y) -> { haptic(); Engine.pushPatternUndo("Organism zurück"); o.restore(); onHint?.invoke("⟲ Zurück zum Ausgangs-Beat") }
                    btns[3].contains(x, y) -> { haptic(); o.capture(); onHint?.invoke("✓ Dieser Stand ist jetzt der neue Ausgang") }
                    btns[4].contains(x, y) -> { haptic(); o.anchor = !o.anchor; onHint?.invoke(if (o.anchor) "⚓ Die Kick auf den Vierteln bleibt immer" else "Auch die Kick darf sich verändern") }
                    else -> for (v in 0 until InstState.DV) if (names[v].contains(x, y)) { o.locked[v] = !o.locked[v]; haptic(); onHint?.invoke(InstState.drumNames[v] + if (o.locked[v]) " gesperrt" else " darf mutieren") }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) { set(drag, get(drag) + (x - lastX) / sliders[drag].width()); lastX = x }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
    }
}
