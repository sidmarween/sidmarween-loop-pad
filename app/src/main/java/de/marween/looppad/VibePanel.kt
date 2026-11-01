package de.marween.looppad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** 🏠 VIBE-SYNTH: Akkord-Typ, 8 Regler, One-Finger-Chord-Tasten (eine Taste = ein Club-Akkord). */
class VibePanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    var onEditNotes: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bTrack = RectF(); private val bNotes = RectF(); private val bPreset = RectF()
    private val chips = Array(Vibe.CHORD_NAMES.size) { RectF() }
    private val bPump = RectF()
    private val sliders = Array(8) { RectF() }
    private val SL = arrayOf("DRIFT", "PUMP-TIEFE", "STAB ↔ ACID", "DRIVE (Konsole/Band)", "CUTOFF", "RESONANZ", "FILTER-HÜLLKURVE", "SUB")
    private val keys = Array(8) { RectF() }
    private val DEG = intArrayOf(0, 2, 3, 5, 7, 8, 10, 12)
    private val keyPtr = HashMap<Int, Int>()
    private val keyFlash = FloatArray(8)
    private var drag = -1
    private var lastX = 0f

    fun open() { isOpen = true; Vibe.attach(); if (Vibe.presetIdx < 0 && !trk().hasNotes()) Vibe.applyPreset(0) }
    fun close() { isOpen = false; for ((id, _) in keyPtr) Engine.synTrackLive(Vibe.track, false, 0, 0f, 7700 + id); keyPtr.clear() }

    private fun trk() = InstState.synTracks[Vibe.track.coerceIn(0, InstState.SYN_TRACKS - 1)]

    private fun get(i: Int): Float {
        val t = trk()
        return when (i) { 0 -> Vibe.drift; 1 -> Vibe.pump; 2 -> Vibe.mode; 3 -> Vibe.drive; 4 -> t.cut; 5 -> t.res; 6 -> t.env; else -> Vibe.sub }
    }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        val t = trk()
        when (i) {
            0 -> Vibe.drift = c; 1 -> Vibe.pump = c
            2 -> { val was = Vibe.acid(); Vibe.mode = c; if (was != Vibe.acid()) { t.mono = Vibe.acid(); if (Vibe.acid() && t.glide < 0.05f) t.glide = 0.15f; onHint?.invoke(if (Vibe.acid()) "ACID-Modus: monofon, Diode-Ladder quietscht – Resonanz hoch!" else "STAB-Modus: Club-Akkorde") } }
            3 -> Vibe.drive = c; 4 -> t.cut = c; 5 -> t.res = c; 6 -> t.env = c; else -> Vibe.sub = c
        }
        Vibe.presetIdx = -1
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(6f); val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = box.top + pad + bh + dp(20f)
        bOn.set(box.left + pad, r1, box.left + pad + w * 0.24f, r1 + bh)
        bTrack.set(bOn.right + g, r1, bOn.right + g + w * 0.26f, r1 + bh)
        bNotes.set(bTrack.right + g, r1, bTrack.right + g + w * 0.2f, r1 + bh)
        bPump.set(bNotes.right + g, r1, box.right - pad, r1 + bh)
        val r2 = r1 + bh + g
        bPreset.set(box.left + pad, r2, box.right - pad, r2 + bh)
        val r3 = r2 + bh + g
        val n = chips.size
        val cw = (w - (n - 1) * dp(4f)) / n
        for (k in 0 until n) chips[k].set(box.left + pad + k * (cw + dp(4f)), r3, box.left + pad + k * (cw + dp(4f)) + cw, r3 + dp(36f))
        val sTop = r3 + dp(36f) + dp(10f)
        val sh = dp(40f)
        val sw = (w - g) / 2
        for (i in 0 until 8) {
            val r = i / 2; val c = i % 2
            sliders[i].set(box.left + pad + c * (sw + g), sTop + r * (sh + g), box.left + pad + c * (sw + g) + sw, sTop + r * (sh + g) + sh)
        }
        val kTop = sliders[7].bottom + dp(28f)
        val kw = (w - 7 * dp(3f)) / 8
        for (k in 0 until 8) keys[k].set(box.left + pad + k * (kw + dp(3f)), kTop, box.left + pad + k * (kw + dp(3f)) + kw, box.bottom - pad)
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(24, 26, 34)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11.5f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun rootSemi(): Int {
        val r = if (InstState.scaleType != 0) InstState.scaleRoot else if (GrooveGen.made) GrooveGen.root else 9
        return Math.floorMod(r, 12).let { if (it > 6) it - 12 else it }
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(120, 200, 255)
        val col2 = Color.rgb(255, 120, 200)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.shader = LinearGradient(box.left, box.top, box.right, box.bottom, Color.rgb(10, 16, 30), Color.rgb(28, 10, 30), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        fill.shader = null
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🏠 VIBE-SYNTH · House", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val t = trk()
        val st = (if (Vibe.acid()) "ACID-Modus (monofon)" else "STAB-Modus · Akkord ${Vibe.CHORD_NAMES[Vibe.chord]}") + " · spielt auf ${InstState.synName(Vibe.track)}" + if (t.wave != Vibe.WAVE) " (Klang wird gesetzt)" else ""
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        val running = t.on && Engine.seqPlaying
        btn(c, bOn, if (running) "■ STOPP" else "▶ START", running, Color.rgb(140, 230, 170))
        btn(c, bTrack, "SPUR: " + InstState.synName(Vibe.track), false, col)
        btn(c, bNotes, "✏ NOTEN", false, col)
        btn(c, bPump, "PUMP " + arrayOf("AUS", "1/4", "1/8")[Vibe.pumpRate], Vibe.pumpRate > 0, col2)
        btn(c, bPreset, "◀  " + (if (Vibe.presetIdx >= 0) Vibe.PRESETS[Vibe.presetIdx].name else "VORLAGE (Klang + Noten)") + "  ▶", Vibe.presetIdx >= 0, col)
        val short = arrayOf("1", "m7", "m9", "sus4", "maj7", "9 M1", "m11")
        for (k in chips.indices) btn(c, chips[k], short[k], Vibe.chord == k, col2)
        for (i in 0 until 8) {
            val r = sliders[i]
            fill.color = Color.rgb(24, 28, 40)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            val cc = if (i == 2) col2 else col
            fill.color = Color.argb(150, Color.red(cc), Color.green(cc), Color.blue(cc))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(fitLabel(text, SL[i], r.width() * 0.72f, dp(10f), dp(6.5f)), r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(if (i == 2) (if (Vibe.acid()) "ACID" else "STAB") else "${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        text.color = Color.argb(180, 255, 255, 255); text.textSize = dp(10f)
        c.drawText(fitLabel(text, if (Vibe.acid()) "Tasten = Acid-Töne (monofon, gleiten)" else "Eine Taste = ein ganzer Akkord (One-Finger Club Chords)", box.width() - dp(24f), dp(10f), dp(7f)), box.centerX(), keys[0].top - dp(9f), text)
        val root = rootSemi()
        for (k in 0 until 8) {
            val r = keys[k]
            val fl = keyFlash[k].coerceIn(0f, 1f)
            fill.color = Color.rgb(30 + (100 * fl).toInt(), 34 + (60 * fl).toInt(), 50 + (80 * fl).toInt())
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            stroke.strokeWidth = dp(1f); stroke.color = Color.argb(140, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(r, dp(7f), dp(7f), stroke)
            text.color = Color.WHITE; text.textSize = min(dp(13f), r.width() * 0.32f)
            val nm = InstState.scaleNoteNames[Math.floorMod(root + DEG[k], 12)]
            c.drawText(nm + if (!Vibe.acid() && Vibe.chord > 0) short[Vibe.chord].let { if (it.startsWith("m")) "" else "" } else "", r.centerX(), r.centerY() + dp(4f), text)
            keyFlash[k] *= 0.88f
        }
        host.postInvalidateOnAnimation()
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keys.indexOfFirst { it.contains(x, y) }
                if (k >= 0) {
                    Vibe.attach()
                    keyPtr[id] = k
                    Engine.synTrackLive(Vibe.track, true, rootSemi() + DEG[k], 0.9f, 7700 + id)
                    keyFlash[k] = 1f
                    return
                }
                for (s in 0 until 8) if (sliders[s].contains(x, y)) { drag = s; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (drag in 0..7) { val px = e.getX(0); set(drag, get(drag) + (px - lastX) / sliders[drag].width()); lastX = px }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (keyPtr.remove(id) != null) Engine.synTrackLive(Vibe.track, false, 0, 0f, 7700 + id)
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) drag = -1
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                haptic()
                val t = trk()
                if (t.on && Engine.seqPlaying) Engine.transportStop() else { Vibe.attach(); t.on = true; if (!Engine.seqPlaying) Engine.transportPlay(false) }
            }
            bTrack.contains(x, y) -> {
                haptic()
                Vibe.track = (Vibe.track + 1) % InstState.SYN_TRACKS
                Vibe.attach()
                onHint?.invoke("Vibe-Synth spielt jetzt auf ${InstState.synName(Vibe.track)}")
            }
            bNotes.contains(x, y) -> { haptic(); close(); onEditNotes?.invoke() }
            bPump.contains(x, y) -> {
                haptic(); Vibe.pumpRate = (Vibe.pumpRate + 1) % 3
                if (Vibe.pumpRate > 0 && Vibe.pump < 0.1f) Vibe.pump = 0.5f
                onHint?.invoke(if (Vibe.pumpRate == 0) "Pump aus" else "Pump ${arrayOf("", "1/4", "1/8")[Vibe.pumpRate]} – drückt Filter und Lautstärke im Takt weg")
            }
            bPreset.contains(x, y) -> {
                haptic()
                Engine.pushPatternUndo("Vibe-Vorlage")
                val cur = Vibe.presetIdx
                Vibe.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                onHint?.invoke("🏠 ${Vibe.PRESETS[Vibe.presetIdx].name} · ${Vibe.PRESETS[Vibe.presetIdx].bpm} BPM empfohlen")
            }
            else -> for (k in chips.indices) if (chips[k].contains(x, y)) {
                haptic(); Vibe.chord = k; Vibe.presetIdx = -1
                if (Vibe.acid()) { Vibe.mode = 0.3f; trk().mono = false }
                onHint?.invoke("Akkord: ${Vibe.CHORD_NAMES[k]}")
            }
        }
    }
}
