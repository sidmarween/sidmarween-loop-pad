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

/** Bedienfeld für [Wolf]: Quelle, Start, Vorlagen, drei große Regler, Takt-Anzeige und Aktionen an/aus. */
class WolfPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    /** Quell-Pad wählen (MainActivity zeigt die Liste). */
    var onPickSource: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bSrc = RectF(); private val bNew = RectF(); private val bHold = RectF()
    private val bRec = RectF(); private val bRecBars = RectF(); private val bPreset = RectF(); private val bMute = RectF()
    private val sliders = Array(4) { RectF() }
    private val SL_NAMES = arrayOf("CHAOS", "DICHTE", "VARIATION", "LAUTSTÄRKE")
    private val steps = Array(16) { RectF() }
    private val acts = Array(9) { RectF() }
    private var drag = -1
    private var lastX = 0f
    private var recBars = 2
    private val defW = floatArrayOf(0f, 1f, 0.8f, 1.2f, 0.6f, 0.4f, 0.4f, 0.5f, 0.6f, 0.3f)

    fun open() {
        isOpen = true
        if (Wolf.srcPad < 0 || !Engine.hasLoop(Wolf.srcPad)) Wolf.srcPad = (0 until 12).firstOrNull { Engine.hasLoop(it) && !Engine.isOneShot(it) } ?: -1
        if (Wolf.presetIdx < 0) Wolf.applyPreset(0)
    }
    fun close() { isOpen = false }

    private fun get(i: Int): Float = when (i) { 0 -> Wolf.chaos; 1 -> Wolf.density; 2 -> Wolf.variation; else -> Wolf.level }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) { 0 -> Wolf.chaos = c; 1 -> Wolf.density = c; 2 -> Wolf.variation = c; else -> Wolf.level = c }
    }
    private fun label(i: Int): String = if (i == 2) Wolf.variationLabel() else "${(get(i) * 100).toInt()} %"

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f)
        val bh = dp(42f)
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val row1 = box.top + pad + bh + dp(22f)
        val w = box.width() - 2 * pad
        val g = dp(6f)
        val gw = (w - 3 * g) / 4
        val r1 = arrayOf(bOn, bSrc, bNew, bHold)
        for (i in 0 until 4) r1[i].set(box.left + pad + i * (gw + g), row1, box.left + pad + i * (gw + g) + gw, row1 + bh)
        val row2 = row1 + bh + g
        bPreset.set(box.left + pad, row2, box.left + pad + w * 0.42f, row2 + bh)
        bMute.set(bPreset.right + g, row2, bPreset.right + g + w * 0.22f, row2 + bh)
        bRec.set(bMute.right + g, row2, box.right - pad - dp(44f), row2 + bh)
        bRecBars.set(bRec.right + g, row2, box.right - pad, row2 + bh)
        val sTop = row2 + bh + dp(10f)
        val sh = dp(48f)
        val sw = (w - g) / 2
        for (i in 0 until 4) {
            val r = i / 2; val c = i % 2
            sliders[i].set(box.left + pad + c * (sw + g), sTop + r * (sh + g), box.left + pad + c * (sw + g) + sw, sTop + r * (sh + g) + sh)
        }
        val stTop = sTop + 2 * (sh + g) + dp(22f)
        val cw = (w - 15 * dp(3f)) / 16
        val ch = min(dp(64f), cw * 3f)
        for (k in 0 until 16) steps[k].set(box.left + pad + k * (cw + dp(3f)), stTop, box.left + pad + k * (cw + dp(3f)) + cw, stTop + ch)
        val aTop = stTop + ch + dp(28f)
        val aw = (w - 2 * g) / 3
        val ah = min(dp(64f), (box.bottom - pad - aTop - 2 * g) / 3)
        for (i in 0 until 9) {
            val r = i / 3; val c = i % 3
            acts[i].set(box.left + pad + c * (aw + g), aTop + r * (ah + g), box.left + pad + c * (aw + g) + aw, aTop + r * (ah + g) + ah)
        }
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(30, 30, 36)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11.5f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6.5f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun actCol(a: Int): Int = when (a) {
        1 -> Color.rgb(90, 190, 255); 2 -> Color.rgb(200, 140, 255); 3 -> Color.rgb(255, 90, 90); 4 -> Color.rgb(255, 210, 90)
        5 -> Color.rgb(255, 150, 60); 6 -> Color.rgb(120, 230, 140); 7 -> Color.rgb(240, 120, 200); 8 -> Color.rgb(90, 230, 220); 9 -> Color.rgb(120, 120, 130)
        else -> Color.rgb(60, 60, 70)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(255, 120, 60)
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(18, 13, 12)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🐺 WOLF · Loop-Zerhacker", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val sp = Wolf.srcPad
        val st = when {
            sp < 0 || !Engine.hasLoop(sp) -> "Kein Loop als Quelle – erst einen Loop auf ein Pad aufnehmen oder laden"
            Wolf.on && !Engine.seqPlaying -> "bereit – läuft mit ▶ (Transport)"
            Wolf.on -> "zerhackt Pad ${sp + 1}" + (if (Wolf.hold) " · BEHALTEN" else " · ${Wolf.variationLabel()}") + (if (Wolf.muteSrc) " · Original stumm" else "")
            else -> "aus · Quelle Pad ${sp + 1} · " + (Engine.padName(sp) ?: "Loop")
        }
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(14f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Wolf.on) "■ STOP" else "▶ START", Wolf.on, Color.rgb(140, 230, 170))
        btn(c, bSrc, if (sp >= 0) "QUELLE: PAD ${sp + 1}" else "QUELLE …", false, col)
        btn(c, bNew, "🎲 NEU", false, col)
        btn(c, bHold, "📌 BEHALTEN", Wolf.hold, Color.rgb(255, 210, 90))
        btn(c, bPreset, "◀  " + (if (Wolf.presetIdx >= 0) Wolf.PRESETS[Wolf.presetIdx].name else "VORLAGE") + "  ▶", Wolf.presetIdx >= 0, col)
        btn(c, bMute, if (Wolf.muteSrc) "ORIGINAL STUMM" else "ORIGINAL HÖRBAR", Wolf.muteSrc, Color.rgb(160, 160, 180))
        btn(c, bRec, "⏺ AUF PAD", false, Color.rgb(255, 90, 90))
        btn(c, bRecBars, "$recBars T", false, Color.rgb(255, 90, 90))
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(34, 28, 28)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(160, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE
            text.textSize = dp(10f)
            text.textAlign = Paint.Align.LEFT
            c.drawText(SL_NAMES[i], r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(fitLabel(text, label(i), r.width() * 0.5f, dp(10f), dp(6.5f)), r.right - dp(8f), r.centerY() + dp(3.5f), text)
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("DIESER TAKT", steps[0].left, steps[0].top - dp(6f), text)
        text.textAlign = Paint.Align.CENTER
        val cur = if (Wolf.on && Engine.seqPlaying) Wolf.curStep else -1
        for (k in 0 until 16) {
            val a = Wolf.planView[k]
            val r = steps[k]
            fill.color = if (a == 0) Color.rgb(40, 40, 48) else actCol(a)
            c.drawRoundRect(r, dp(4f), dp(4f), fill)
            if (k == cur) { stroke.strokeWidth = dp(2.5f); stroke.color = Color.WHITE; c.drawRoundRect(r, dp(4f), dp(4f), stroke) }
            text.color = if (a == 0) Color.argb(120, 255, 255, 255) else Color.BLACK
            text.textSize = min(dp(13f), r.width() * 0.7f)
            c.drawText(Wolf.ACT_SHORT[a], r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("AKTIONEN (antippen = an/aus)", acts[0].left, acts[0].top - dp(8f), text)
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until 9) {
            val a = i + 1
            btn(c, acts[i], Wolf.ACT_SHORT[a] + " " + Wolf.ACT_NAMES[a], Wolf.weights[a] > 0f, actCol(a))
        }
        host.postInvalidateOnAnimation()
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (k in 0 until 4) if (sliders[k].contains(x, y)) { drag = k; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (drag in 0..3) {
                val px = e.getX(0)
                set(drag, get(drag) + (px - lastX) / sliders[drag].width())
                lastX = px
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag in 0..2) Wolf.replan()
                drag = -1
            }
        }
    }

    /** Start/Stopp inkl. Stummschalten des Originals. */
    fun setOn(v: Boolean) {
        if (!Wolf.switch(v)) { onHint?.invoke("Erst einen Loop auf ein Pad legen – dann QUELLE wählen"); return }
        if (v && !Engine.seqPlaying) Engine.transportPlay(false)
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                haptic()
                setOn(!Wolf.on)
                if (Wolf.on) onHint?.invoke("🐺 WOLF zerhackt Pad ${Wolf.srcPad + 1} im Takt – CHAOS und DICHTE aufdrehen, 📌 behält einen guten Takt")
            }
            bSrc.contains(x, y) -> { haptic(); onPickSource?.invoke() }
            bNew.contains(x, y) -> { haptic(); Wolf.holdBar(false); Wolf.reroll(); onHint?.invoke("🎲 Neue Zufallsfolge") }
            bHold.contains(x, y) -> {
                haptic()
                Wolf.holdBar(!Wolf.hold)
                onHint?.invoke(if (Wolf.hold) "📌 Dieser Takt wiederholt sich – nochmal tippen lässt den Wolf wieder los" else "Der Wolf variiert wieder")
            }
            bPreset.contains(x, y) -> {
                haptic()
                val cur = Wolf.presetIdx
                Wolf.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                Wolf.replan()
                onHint?.invoke("Vorlage ${Wolf.presetIdx + 1}/${Wolf.PRESETS.size}: ${Wolf.PRESETS[Wolf.presetIdx].name}")
            }
            bMute.contains(x, y) -> {
                haptic()
                val sp = Wolf.srcPad
                Wolf.muteSrc = !Wolf.muteSrc
                if (Wolf.on && sp >= 0 && Engine.isMuted(sp) != Wolf.muteSrc) Engine.toggleMute(sp)
                onHint?.invoke(if (Wolf.muteSrc) "Original-Pad stumm – nur der Wolf ist zu hören" else "Original läuft mit – der Wolf liegt darüber")
            }
            bRecBars.contains(x, y) -> { recBars = when (recBars) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; haptic() }
            bRec.contains(x, y) -> {
                haptic()
                if (!Wolf.on) setOn(true)
                if (!Wolf.on) return
                val p = Engine.recordInstLoop(64, recBars)
                if (p < 0) onHint?.invoke("Kein freies Pad (1–12) – erst eins leeren")
                else onHint?.invoke("Ab der nächsten Eins: $recBars Takt" + (if (recBars > 1) "e" else "") + " WOLF → Pad ${p + 1}")
            }
            else -> for (k in 0 until 9) if (acts[k].contains(x, y)) {
                val a = k + 1
                Wolf.weights[a] = if (Wolf.weights[a] > 0f) 0f else defW[a]
                Wolf.presetIdx = -1
                Wolf.replan()
                haptic()
                onHint?.invoke("${Wolf.ACT_NAMES[a]}: " + if (Wolf.weights[a] > 0f) "an" else "aus")
            }
        }
    }
}
