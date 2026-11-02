package de.marween.looppad

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Gemeinsame Zeichen-Hilfen der Performance-Felder (FX-Generator, Vocoder, Chord-Pad). */
abstract class PerfPanel(protected val host: View) {
    var isOpen = false
        protected set
    var onHint: ((String) -> Unit)? = null
    protected val d = host.resources.displayMetrics.density
    protected fun dp(v: Float) = v * d
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    protected val box = RectF()
    protected val bClose = RectF()
    protected var drag = -1
    protected var lastX = 0f

    open fun open() { isOpen = true }
    open fun close() { isOpen = false }
    protected fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    protected fun frame(c: Canvas, area: RectF, title: String, col: Int, bg: Int, acc: Int, status: String) {
        box.set(area)
        val pad = dp(10f); val bh = dp(40f)
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = bg
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText(title, box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        c.drawText(fitLabel(text, status, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
    }

    protected fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int, size: Float = 11.5f) {
        if (r.isEmpty) return
        fill.shader = null
        fill.color = if (on) col else Color.rgb(26, 26, 34)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 120, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(size), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    protected fun slider(c: Canvas, r: RectF, label: String, v: Float, value: String, col: Int) {
        if (r.isEmpty) return
        fill.color = Color.rgb(26, 26, 36); c.drawRoundRect(r, dp(7f), dp(7f), fill)
        fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * v.coerceIn(0f, 1f), r.bottom), dp(7f), dp(7f), fill)
        text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
        c.drawText(fitLabel(text, label, r.width() * 0.68f, dp(10f), dp(6.5f)), r.left + dp(8f), r.centerY() + dp(3.5f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(value, r.right - dp(8f), r.centerY() + dp(3.5f), text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Reihe gleich breiter Rechtecke. */
    protected fun row(rs: Array<RectF>, left: Float, right: Float, top: Float, h: Float, gap: Float = dp(6f)) {
        val w = (right - left - (rs.size - 1) * gap) / rs.size
        for (k in rs.indices) rs[k].set(left + k * (w + gap), top, left + k * (w + gap) + w, top + h)
    }
}

// ================================================================ 🚀 FX-GENERATOR
class FxGenPanel(host: View) : PerfPanel(host) {
    private val pads = Array(8) { RectF() }
    private val lens = Array(5) { RectF() }
    private val bQuant = RectF(); private val bDrop = RectF(); private val bStop = RectF()
    private val sl = Array(2) { RectF() }
    private val LENS = intArrayOf(1, 2, 4, 8, 16)
    private val COLS = intArrayOf(Color.rgb(255, 120, 80), Color.rgb(120, 200, 255), Color.rgb(255, 80, 80), Color.rgb(170, 120, 255), Color.rgb(255, 210, 90), Color.rgb(90, 230, 220), Color.rgb(255, 160, 60), Color.rgb(200, 255, 140))

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(255, 140, 70)
        val bpm = Engine.currentBpm().let { if (it > 0f) it else 120f }
        frame(c, area, "🚀 FX-GENERATOR", col, Color.rgb(18, 12, 10), acc, "Übergänge im Takt · ${FxGen.bars} Takt" + (if (FxGen.bars > 1) "e" else "") + " = ${String.format(java.util.Locale.GERMANY, "%.1f", FxGen.bars * 240f / bpm)} s · " + if (FxGen.quant) "startet auf der nächsten Eins" else "startet sofort")
        val pad = dp(10f); val g = dp(6f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(22f)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("LÄNGE", l, y + dp(6f), text); text.textAlign = Paint.Align.CENTER
        y += dp(12f)
        row(lens, l, r, y, dp(38f))
        for (k in 0 until 5) btn(c, lens[k], "${LENS[k]} T", FxGen.bars == LENS[k], col)
        y += dp(38f) + g
        row(arrayOf(bQuant, bStop), l, r, y, dp(38f))
        btn(c, bQuant, if (FxGen.quant) "⏱ AB NÄCHSTER EINS" else "⚡ SOFORT", FxGen.quant, col)
        btn(c, bStop, "■ ALLE STOPPEN", false, col)
        y += dp(38f) + g
        row(sl, l, r, y, dp(36f))
        slider(c, sl[0], "INTENSITÄT", FxGen.intensity, "${(FxGen.intensity * 100).toInt()}", col)
        slider(c, sl[1], "LAUTSTÄRKE", FxGen.vol, "${(FxGen.vol * 100).toInt()}", col)
        y += dp(36f) + dp(10f)
        bDrop.set(l, y, r, y + dp(52f))
        glow.maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.NORMAL); glow.color = Color.argb(140, 255, 80, 60)
        c.drawRoundRect(bDrop, dp(12f), dp(12f), glow); glow.maskFilter = null
        btn(c, bDrop, "💥 DROP-PAKET: Riser + Snare-Build ${FxGen.bars} T → Impact + Sub-Drop", true, Color.rgb(255, 90, 70), 12f)
        y += dp(52f) + dp(10f)
        val ph = (box.bottom - pad - y - g * 3) / 4
        for (k in 0 until 8) {
            val rr = k / 2; val cc = k % 2
            val w = (r - l - g) / 2
            pads[k].set(l + cc * (w + g), y + rr * (ph + g), l + cc * (w + g) + w, y + rr * (ph + g) + ph)
            val p = FxGen.progress[k]
            btn(c, pads[k], FxGen.ICONS[k] + " " + FxGen.TYPES[k], p > -2f, COLS[k], 13f)
            if (p >= 0f) { fill.color = Color.argb(160, 255, 255, 255); c.drawRect(pads[k].left + dp(6f), pads[k].bottom - dp(6f), pads[k].left + dp(6f) + (pads[k].width() - dp(12f)) * p, pads[k].bottom - dp(3f), fill) }
            else if (p == -1f) { text.textSize = dp(9f); text.color = Color.BLACK; c.drawText("wartet auf die Eins", pads[k].centerX(), pads[k].bottom - dp(7f), text) }
        }
        host.postInvalidateOnAnimation()
    }

    fun touch(e: MotionEvent) {
        val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (k in 0 until 2) if (sl[k].contains(x, y)) { drag = k; lastX = x; return }
                val p = pads.indexOfFirst { it.contains(x, y) }
                if (p >= 0) {
                    haptic(); FxGen.fire(p)
                    if (!Engine.seqPlaying && FxGen.quant) Engine.transportPlay(false)
                    onHint?.invoke("${FxGen.ICONS[p]} ${FxGen.TYPES[p]}" + if (FxGen.quant) " ab der nächsten Eins" else "")
                    return
                }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                when {
                    bClose.contains(x, y) -> { close(); haptic() }
                    bQuant.contains(x, y) -> { haptic(); FxGen.quant = !FxGen.quant }
                    bStop.contains(x, y) -> { haptic(); FxGen.stopAll() }
                    bDrop.contains(x, y) -> {
                        haptic(); FxGen.dropPack()
                        if (!Engine.seqPlaying) Engine.transportPlay(false)
                        onHint?.invoke("💥 Drop in ${FxGen.bars} Takt" + (if (FxGen.bars > 1) "en" else "") + " – Riser und Snare-Build laufen ab der nächsten Eins")
                    }
                    else -> { val k = lens.indexOfFirst { it.contains(x, y) }; if (k >= 0) { haptic(); FxGen.bars = LENS[k] } }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val dv = (x - lastX) / sl[drag].width(); lastX = x
                if (drag == 0) FxGen.intensity = (FxGen.intensity + dv).coerceIn(0f, 1f) else FxGen.vol = (FxGen.vol + dv).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
    }
}

// ================================================================ 🎼 CHORD-PAD
class ChordPadPanel(host: View) : PerfPanel(host) {
    private val pads = Array(8) { RectF() }
    private val vo = Array(5) { RectF() }
    private val bKey = RectF(); private val bMode = RectF(); private val bBass = RectF(); private val bTrack = RectF()
    private val bRec = RectF(); private val bWrite = RectF(); private val bClear = RectF()
    private val sl = RectF()
    private val held = HashMap<Int, Int>()
    private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "I'")

    override fun close() { super.close(); for ((id, _) in held) ChordPad.release(5000 + id * 64); held.clear() }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(140, 220, 255)
        val cp = ChordPad
        frame(c, area, "🎼 CHORD-PAD", col, Color.rgb(10, 14, 22), acc, "Tonart ${InstState.scaleNoteNames[cp.rootPc()]}-${if (cp.isMinor()) "Moll" else "Dur"} · spielt auf ${InstState.synName(cp.track)}" + (if (cp.autoBass) " + BASSLINE" else "") + (if (cp.recorded.isNotEmpty()) " · Folge: " + cp.recorded.joinToString(" – ") { cp.name(it) } else ""))
        val pad = dp(10f); val g = dp(6f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(22f)
        row(arrayOf(bKey, bMode, bTrack, bBass), l, r, y, dp(38f))
        btn(c, bKey, "TON: " + (if (cp.root >= 0) InstState.scaleNoteNames[cp.root] else "AUTO"), cp.root >= 0, col)
        btn(c, bMode, if (cp.isMinor()) "MOLL" else "DUR", false, col)
        btn(c, bTrack, InstState.synName(cp.track), false, col)
        btn(c, bBass, if (cp.autoBass) "BASS AN" else "BASS AUS", cp.autoBass, col)
        y += dp(38f) + g
        row(vo, l, r, y, dp(36f))
        val short = arrayOf("3-KLANG", "7", "ADD9", "SUS", "POWER")
        for (k in 0 until 5) btn(c, vo[k], short[k], cp.voicing == k, Color.rgb(255, 180, 120))
        y += dp(36f) + g
        sl.set(l, y, r, y + dp(34f))
        slider(c, sl, "STRUM (Gitarren-Anschlag)", cp.strum, "${(cp.strum * 60).toInt()} ms", col)
        y += dp(34f) + g
        row(arrayOf(bRec, bWrite, bClear), l, r, y, dp(38f))
        btn(c, bRec, if (cp.recording) "● NIMMT AUF" else "○ FOLGE AUFNEHMEN", cp.recording, Color.rgb(255, 90, 90))
        btn(c, bWrite, "📝 IN SPUREN", false, col)
        btn(c, bClear, "⌫ FOLGE", false, col)
        y += dp(38f) + dp(12f)
        val ph = (box.bottom - pad - y - g) / 2
        val pw = (r - l - 3 * g) / 4
        for (k in 0 until 8) {
            val rr = k / 4; val cc = k % 4
            pads[k].set(l + cc * (pw + g), y + rr * (ph + g), l + cc * (pw + g) + pw, y + rr * (ph + g) + ph)
            val on = held.values.contains(k)
            val pc = if (k == 0 || k == 3 || k == 4 || k == 7) col else Color.rgb(200, 160, 255)
            if (on) { glow.maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL); glow.color = pc; c.drawRoundRect(pads[k], dp(12f), dp(12f), glow); glow.maskFilter = null }
            fill.color = if (on) pc else Color.rgb(28, 32, 44)
            c.drawRoundRect(pads[k], dp(12f), dp(12f), fill)
            stroke.strokeWidth = dp(1.5f); stroke.color = pc; c.drawRoundRect(pads[k], dp(12f), dp(12f), stroke)
            text.color = if (on) Color.BLACK else Color.WHITE
            text.textSize = min(dp(20f), pads[k].width() * 0.28f)
            c.drawText(cp.name(k), pads[k].centerX(), pads[k].centerY() + dp(4f), text)
            text.textSize = dp(10f); text.color = if (on) Color.BLACK else Color.argb(150, 255, 255, 255)
            c.drawText(ROMAN[k], pads[k].centerX(), pads[k].top + dp(16f), text)
        }
        host.postInvalidateOnAnimation()
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val cp = ChordPad
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val p = pads.indexOfFirst { it.contains(x, y) }
                if (p >= 0) {
                    // nur ein Akkord gleichzeitig: alter klingt aus
                    for ((oid, _) in held) cp.release(5000 + oid * 64)
                    held.clear()
                    held[id] = p
                    cp.play(p, 0.9f, 5000 + id * 64)
                    return
                }
                if (sl.contains(x, y)) { drag = 0; lastX = x; return }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                haptic()
                when {
                    bClose.contains(x, y) -> close()
                    bKey.contains(x, y) -> cp.root = if (cp.root >= 11) -1 else cp.root + 1
                    bMode.contains(x, y) -> { if (cp.root < 0) cp.root = cp.rootPc(); cp.minor = !cp.isMinor() }
                    bTrack.contains(x, y) -> { cp.track = when (cp.track) { InstState.KEYS_TRACK -> 1; 1 -> 0; else -> InstState.KEYS_TRACK } ; onHint?.invoke("Chord-Pad spielt auf ${InstState.synName(cp.track)} – Klang dort im Editor wählen") }
                    bBass.contains(x, y) -> cp.autoBass = !cp.autoBass
                    bRec.contains(x, y) -> { cp.recording = !cp.recording; if (cp.recording) cp.recorded.clear(); onHint?.invoke(if (cp.recording) "● Spiel deine Folge (bis 4 Akkorde zählen) – dann 📝 IN SPUREN" else "Aufnahme aus") }
                    bWrite.contains(x, y) -> {
                        Engine.pushPatternUndo("Chord-Pad")
                        cp.recording = false
                        if (cp.writeRecorded()) { if (!Engine.seqPlaying) Engine.transportPlay(false); onHint?.invoke("📝 Folge in ${InstState.synName(cp.track)} (je 1 Takt) und BASSLINE geschrieben – läuft") }
                        else onHint?.invoke("Erst mit ○ FOLGE AUFNEHMEN ein paar Akkorde spielen")
                    }
                    bClear.contains(x, y) -> cp.recorded.clear()
                    else -> { val k = vo.indexOfFirst { it.contains(x, y) }; if (k >= 0) cp.voicing = k }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag == 0) { cp.strum = (cp.strum + (e.getX(0) - lastX) / sl.width()).coerceIn(0f, 1f); lastX = e.getX(0) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (held.remove(id) != null) cp.release(5000 + id * 64)
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) drag = -1
            }
        }
    }
}

// ================================================================ 🤖 VOCODER
class VocoderPanel(host: View) : PerfPanel(host) {
    private val bOn = RectF(); private val bCar = RectF(); private val bFollow = RectF(); private val bRobot = RectF()
    private val sl = Array(4) { RectF() }
    private val SL = arrayOf("FORMANT (Stimme)", "S-LAUTE (Rauschen)", "LOSLASSEN", "ORIGINAL DAZU")
    private val keys = Array(8) { RectF() }
    private val held = HashMap<Int, Int>()

    override fun close() { super.close(); for ((_, k) in held) Vocoder.notes.remove(Integer.valueOf(midi(k))); held.clear() }

    private fun midi(k: Int): Int = ChordPad.notesOf(k).first()

    private fun get(i: Int) = when (i) { 0 -> Vocoder.formant; 1 -> Vocoder.sibilance; 2 -> Vocoder.release; else -> Vocoder.dry }
    private fun set(i: Int, v: Float) { val c = v.coerceIn(0f, 1f); when (i) { 0 -> Vocoder.formant = c; 1 -> Vocoder.sibilance = c; 2 -> Vocoder.release = c; else -> Vocoder.dry = c } }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(120, 255, 200)
        val v = Vocoder
        val src = when { v.robot -> "ROBOTER (ein Ton)"; v.followChords && ChordPad.cur.isNotEmpty() -> "folgt dem Chord-Pad"; v.notes.isNotEmpty() -> "Akkord-Tasten"; else -> "Taste halten oder Chord-Pad spielen" }
        frame(c, area, "🤖 VOCODER", col, Color.rgb(8, 18, 16), acc, (if (v.on) "an" else "aus") + " · Mikrofon → $src · Kopfhörer benutzen, sonst pfeift es")
        val pad = dp(10f); val g = dp(6f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(22f)
        row(arrayOf(bOn, bCar, bFollow, bRobot), l, r, y, dp(40f))
        btn(c, bOn, if (v.on) "■ AUS" else "▶ AN", v.on, Color.rgb(140, 230, 170))
        btn(c, bCar, "TRÄGER: " + v.CARRIERS[v.carrier], false, col)
        btn(c, bFollow, if (v.followChords) "CHORD-PAD ✓" else "CHORD-PAD –", v.followChords, col)
        btn(c, bRobot, if (v.robot) "🤖 ROBOTER" else "ROBOTER", v.robot, col)
        y += dp(40f) + g
        for (i in 0 until 4) { val rr = i / 2; val cc = i % 2; val w = (r - l - g) / 2; sl[i].set(l + cc * (w + g), y + rr * (dp(36f) + g), l + cc * (w + g) + w, y + rr * (dp(36f) + g) + dp(36f)) }
        for (i in 0 until 4) slider(c, sl[i], SL[i], get(i), "${(get(i) * 100).toInt()}", col)
        y = sl[3].bottom + dp(12f)
        // Band-Anzeige
        val bh = dp(90f)
        val bw = (r - l) / 16
        for (k in 0 until 16) {
            val lv = Vocoder.bandLevel[k]
            fill.color = Color.rgb(20, 40, 34); c.drawRect(l + k * bw + 1, y, l + (k + 1) * bw - 1, y + bh, fill)
            fill.color = col; c.drawRect(l + k * bw + 1, y + bh * (1 - lv), l + (k + 1) * bw - 1, y + bh, fill)
        }
        text.textSize = dp(9f); text.color = Color.argb(160, 255, 255, 255)
        c.drawText("16 Bänder · tief → hoch", (l + r) / 2, y + bh + dp(12f), text)
        y += bh + dp(24f)
        val kh = box.bottom - pad - y
        val kw = (r - l - 7 * dp(3f)) / 8
        for (k in 0 until 8) {
            keys[k].set(l + k * (kw + dp(3f)), y, l + k * (kw + dp(3f)) + kw, y + kh)
            val on = held.values.contains(k)
            fill.color = if (on) col else Color.rgb(24, 34, 30); c.drawRoundRect(keys[k], dp(8f), dp(8f), fill)
            text.color = if (on) Color.BLACK else Color.WHITE; text.textSize = min(dp(13f), kw * 0.3f)
            c.drawText(ChordPad.name(k), keys[k].centerX(), keys[k].centerY(), text)
        }
        host.postInvalidateOnAnimation()
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val v = Vocoder
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keys.indexOfFirst { it.contains(x, y) }
                if (k >= 0) {
                    held[id] = k
                    v.notes.clear(); for (n in ChordPad.notesOf(k)) v.notes.add(n)
                    if (!v.on) v.on = true
                    v.followChords = false
                    return
                }
                for (s in 0 until 4) if (sl[s].contains(x, y)) { drag = s; lastX = x; return }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                haptic()
                when {
                    bClose.contains(x, y) -> close()
                    bOn.contains(x, y) -> { v.on = !v.on; onHint?.invoke(if (v.on) "🤖 Sprich ins Mikro und halte eine Akkord-Taste (oder spiel das Chord-Pad)" else "Vocoder aus") }
                    bCar.contains(x, y) -> v.carrier = (v.carrier + 1) % v.CARRIERS.size
                    bFollow.contains(x, y) -> v.followChords = !v.followChords
                    bRobot.contains(x, y) -> { v.robot = !v.robot; if (v.robot) v.on = true }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) { set(drag, get(drag) + (e.getX(0) - lastX) / sl[drag].width()); lastX = e.getX(0) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (held.remove(id) != null && held.isEmpty()) v.notes.clear()
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) drag = -1
            }
        }
    }
}

// ================================================================ 🗣 VOCAL-CHOP
class VocalChopPanel(host: View) : PerfPanel(host) {
    private val bDemo = RectF(); private val bPad = RectF(); private val bMic = RectF()
    private val bPat = RectF(); private val bPatSel = RectF(); private val bMode = RectF()
    private val bGate = RectF(); private val bRev = RectF(); private val bStut = RectF(); private val bToPads = RectF()
    private val sl = Array(4) { RectF() }
    private val SL = arrayOf("TONHÖHE", "KLANG (hell)", "AUSKLANG", "LAUTSTÄRKE")
    private val wave = RectF()
    private val chops = Array(VocalChop.N) { RectF() }
    private val keys = Array(13) { RectF() }
    private val held = HashMap<Int, Int>()      // Finger → Chop
    private val heldKey = HashMap<Int, Int>()   // Finger → Taste
    private var padSrc = -1
    private var recording = false
    private var recMark = 0L
    private var recStart = 0L
    /** Einzähler: Aufnahme startet auf diesem Schlag (NaN = keiner). */
    private var pendBeat = Double.NaN
    private var envFor: VocalChop.Src? = null
    private val env = FloatArray(160)
    private val COLS = intArrayOf(Color.rgb(255, 150, 220), Color.rgb(255, 190, 120), Color.rgb(150, 220, 255), Color.rgb(190, 255, 150))
    private val NOTE = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H", "C'")

    override fun close() { super.close(); VocalChop.allOff(); held.clear(); heldKey.clear(); recording = false; pendBeat = Double.NaN }

    private fun get(i: Int) = when (i) { 0 -> (VocalChop.pitch + 12) / 24f; 1 -> VocalChop.tone; 2 -> VocalChop.release; else -> VocalChop.vol }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) { 0 -> VocalChop.pitch = Math.round(c * 24f) - 12; 1 -> VocalChop.tone = c; 2 -> VocalChop.release = c; else -> VocalChop.vol = c }
    }
    private fun value(i: Int): String = when (i) { 0 -> (if (VocalChop.pitch > 0) "+" else "") + VocalChop.pitch + " HT"; else -> "${(get(i) * 100).toInt()}" }

    private fun buildEnv(s: VocalChop.Src) {
        val d = s.data; val n = env.size
        for (k in 0 until n) {
            val a = (d.size.toLong() * k / n).toInt(); val b = (d.size.toLong() * (k + 1) / n).toInt()
            var pk = 0f; var i = a; while (i < b) { val q = abs(d[i]); if (q > pk) pk = q; i += 4 }
            env[k] = pk
        }
        envFor = s
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(255, 150, 220)
        val v = VocalChop
        val s = v.src
        // Einzähler abgelaufen → Aufnahme beginnt genau auf der Eins
        if (!pendBeat.isNaN() && Engine.beatPos(Engine.heardFrame()) >= pendBeat) {
            pendBeat = Double.NaN; recording = true; recMark = Engine.micMark(); recStart = System.nanoTime()
        }
        val beatsLeft = if (pendBeat.isNaN()) 0 else kotlin.math.ceil(pendBeat - Engine.beatPos(Engine.heardFrame())).toInt().coerceAtLeast(1)
        val status = when {
            !pendBeat.isNaN() -> "⏱ Einzählen … $beatsLeft – Aufnahme startet auf der Eins"
            recording -> "● nimmt auf … ${String.format(java.util.Locale.GERMANY, "%.1f", (System.nanoTime() - recStart) / 1e9)} s – nochmal tippen zum Zerlegen"
            s == null -> "Quelle wählen: Demo-Stimme, Loop eines Pads oder Mikrofon"
            else -> s.name + " · ${s.count} Chops" + (if (v.patOn && v.patIdx >= 0) " · Muster: " + v.PATTERNS[v.patIdx].name else "")
        }
        frame(c, area, "🗣 VOCAL-CHOP", col, Color.rgb(20, 10, 18), acc, status)
        v.decayFlash()
        val pad = dp(10f); val g = dp(6f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(22f)
        val bh = dp(36f)
        row(arrayOf(bDemo, bPad, bMic), l, r, y, bh)
        btn(c, bDemo, "◀ " + (if (v.demoIdx >= 0) v.DEMO_NAMES[v.demoIdx] else "DEMO-STIMME") + " ▶", v.demoIdx >= 0 && s != null && s.name.startsWith("Demo"), col)
        btn(c, bPad, if (padSrc >= 0) "∿ PAD ${padSrc + 1} ▶" else "∿ AUS PAD", s != null && s.name.startsWith("Pad"), col)
        btn(c, bMic, if (recording) "■ STOPP & ZERLEGEN" else if (!pendBeat.isNaN()) "⏱ $beatsLeft …" else "● MIKRO AUFNEHMEN", recording || !pendBeat.isNaN(), Color.rgb(255, 90, 90))
        y += bh + g
        row(arrayOf(bPat, bPatSel, bMode), l, r, y, bh)
        btn(c, bPat, if (v.patOn) "■ MUSTER" else "▶ MUSTER", v.patOn, Color.rgb(140, 230, 170))
        btn(c, bPatSel, "◀ " + (if (v.patIdx >= 0) v.PATTERNS[v.patIdx].name else "MUSTER-VORLAGE") + " ▶", v.patIdx >= 0, col)
        btn(c, bMode, "✂ " + v.MODE_NAMES[v.chopMode], false, col)
        y += bh + g
        row(arrayOf(bGate, bRev, bStut, bToPads), l, r, y, bh)
        btn(c, bGate, if (v.gate) "GATE" else "ONE-SHOT", v.gate, col)
        btn(c, bRev, "⇄ RÜCKWÄRTS", v.reverse, col)
        btn(c, bStut, "STUTTER " + v.STUTTER_NAMES[v.stutter], v.stutter > 0, Color.rgb(255, 210, 90))
        btn(c, bToPads, "→ PADS", false, col)
        y += bh + g
        val sw = (r - l - g) / 2; val sh = dp(32f)
        for (i in 0 until 4) { val rr = i / 2; val cc = i % 2; sl[i].set(l + cc * (sw + g), y + rr * (sh + g), l + cc * (sw + g) + sw, y + rr * (sh + g) + sh) }
        for (i in 0 until 4) slider(c, sl[i], SL[i], get(i), value(i), col)
        y = sl[3].bottom + g
        // Wellenform mit Schnitten
        wave.set(l, y, r, y + dp(46f))
        fill.color = Color.rgb(30, 18, 28); c.drawRoundRect(wave, dp(6f), dp(6f), fill)
        if (recording || !pendBeat.isNaN()) {
            // Eingangspegel während der Aufnahme (rot = zu laut)
            val lv = min(1f, Engine.inputLevel * 3f)
            fill.color = if (lv > 0.9f) Color.rgb(255, 70, 70) else Color.rgb(80, 220, 120)
            c.drawRoundRect(RectF(wave.left + dp(4f), wave.centerY() - dp(8f), wave.left + dp(4f) + (wave.width() - dp(8f)) * lv, wave.centerY() + dp(8f)), dp(4f), dp(4f), fill)
            text.textSize = dp(10f); text.color = Color.WHITE
            c.drawText(if (recording) "MIKRO-PEGEL" else "gleich geht's los …", wave.centerX(), wave.bottom - dp(4f), text)
        } else if (s != null) {
            if (envFor !== s) buildEnv(s)
            val ww = wave.width() / env.size
            val mid = wave.centerY(); val hh = wave.height() * 0.45f
            fill.color = Color.argb(170, 255, 150, 220)
            for (k in env.indices) { val e = env[k].coerceAtMost(1f) * hh; c.drawRect(wave.left + k * ww, mid - e, wave.left + (k + 1) * ww - 0.5f, mid + e, fill) }
            val len = s.data.size.toFloat()
            for (k in 0 until s.count) {
                val x0 = wave.left + wave.width() * s.starts[k] / len; val x1 = wave.left + wave.width() * s.ends[k] / len
                fill.color = Color.argb(if (k == v.sel) 90 else 35, 255, 255, 255)
                c.drawRect(x0, wave.top, x1, wave.bottom, fill)
                stroke.color = COLS[k % 4]; stroke.strokeWidth = dp(1.2f); c.drawLine(x0, wave.top, x0, wave.bottom, stroke)
            }
        } else {
            text.textSize = dp(10f); text.color = Color.argb(150, 255, 255, 255)
            c.drawText("noch keine Stimme geladen", wave.centerX(), wave.centerY() + dp(4f), text)
        }
        y = wave.bottom + g
        // 4×4 Chop-Pads und Tasten-Leiste
        val kh = dp(54f)
        val gridH = box.bottom - pad - kh - g - dp(12f) - y   // Platz für den Hinweis über den Tasten
        val cw = (r - l - 3 * g) / 4; val ch = (gridH - 3 * g) / 4
        val n = s?.count ?: 0
        for (k in 0 until VocalChop.N) {
            val rr = k / 4; val cc = k % 4
            chops[k].set(l + cc * (cw + g), y + rr * (ch + g), l + cc * (cw + g) + cw, y + rr * (ch + g) + ch)
            val pc = COLS[rr]
            val has = k < n
            val f = v.flash[k]
            if (f > 0.05f) { glow.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL); glow.color = Color.argb((200 * f).toInt(), Color.red(pc), Color.green(pc), Color.blue(pc)); c.drawRoundRect(chops[k], dp(10f), dp(10f), glow); glow.maskFilter = null }
            fill.color = if (!has) Color.rgb(24, 20, 26) else if (f > 0.3f) pc else Color.rgb(44, 30, 42)
            c.drawRoundRect(chops[k], dp(10f), dp(10f), fill)
            stroke.strokeWidth = dp(if (k == v.sel && has) 2.2f else 1f); stroke.color = if (has) pc else Color.rgb(60, 50, 60)
            c.drawRoundRect(chops[k], dp(10f), dp(10f), stroke)
            text.color = if (has && f > 0.3f) Color.BLACK else if (has) Color.WHITE else Color.argb(80, 255, 255, 255)
            text.textSize = min(dp(15f), ch * 0.3f)
            c.drawText("${k + 1}", chops[k].centerX(), chops[k].centerY() + text.textSize * 0.36f, text)
            if (v.curStep >= 0 && v.patOn && v.patIdx >= 0 && v.PATTERNS[v.patIdx].chop[v.curStep] == k && has) {
                fill.color = Color.WHITE; c.drawCircle(chops[k].right - dp(8f), chops[k].top + dp(8f), dp(3f), fill)
            }
        }
        val ky = box.bottom - pad - kh
        val kw = (r - l - 12 * dp(2f)) / 13
        for (k in 0 until 13) {
            keys[k].set(l + k * (kw + dp(2f)), ky, l + k * (kw + dp(2f)) + kw, ky + kh)
            val black = k % 12 in intArrayOf(1, 3, 6, 8, 10)
            val on = heldKey.values.contains(k)
            fill.color = if (on) col else if (black) Color.rgb(40, 30, 40) else Color.rgb(225, 215, 225)
            c.drawRoundRect(keys[k], dp(5f), dp(5f), fill)
            text.color = if (on || !black) Color.BLACK else Color.WHITE
            text.textSize = min(dp(10f), kw * 0.42f)
            c.drawText(NOTE[k], keys[k].centerX(), keys[k].bottom - dp(8f), text)
        }
        text.textSize = dp(8.5f); text.color = Color.argb(150, 255, 255, 255)
        c.drawText("Tasten spielen Chop ${v.sel + 1} in jeder Tonhöhe", (l + r) / 2, ky - dp(2f), text)
        host.postInvalidateOnAnimation()
    }

    private fun nextPad(): Int {
        for (i in 1..Engine.PADS) { val p = Math.floorMod(padSrc + i, min(12, Engine.PADS)); if (Engine.hasLoop(p)) return p }
        return -1
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val v = VocalChop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val s = v.src
                val k = chops.indexOfFirst { it.contains(x, y) }
                if (k >= 0) {
                    if (s != null && k < s.count) { held[id] = k; v.on(k, 0, id) }
                    else if (s == null) onHint?.invoke("Erst eine Quelle laden – z. B. ◀ DEMO-STIMME ▶")
                    return
                }
                val kk = keys.indexOfFirst { it.contains(x, y) }
                if (kk >= 0) {
                    if (s != null) { heldKey[id] = kk; v.on(v.sel.coerceIn(0, s.count - 1), kk, 1000 + id) }
                    return
                }
                for (q in 0 until 4) if (sl[q].contains(x, y)) { drag = q; lastX = x; return }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                haptic()
                when {
                    bClose.contains(x, y) -> close()
                    bDemo.contains(x, y) -> {
                        v.loadDemo(if (v.demoIdx < 0) 0 else if (x < bDemo.centerX()) v.demoIdx - 1 else v.demoIdx + 1)
                        onHint?.invoke("🗣 ${v.DEMO_NAMES[v.demoIdx]} – ${v.src?.count ?: 0} Silben auf den Pads")
                    }
                    bPad.contains(x, y) -> {
                        val p = nextPad()
                        if (p < 0) onHint?.invoke("Kein Pad mit Aufnahme – nimm eine Stimme auf ein Pad auf (oder KI-Stems: Gesang auf ein Pad)")
                        else { padSrc = p; if (v.loadPad(p)) onHint?.invoke("∿ Pad ${p + 1} in ${v.src?.count ?: 0} Silben zerlegt · nochmal tippen = nächstes Pad") else onHint?.invoke("Pad ${p + 1} ist zu kurz") }
                    }
                    bMic.contains(x, y) -> {
                        if (!pendBeat.isNaN()) { pendBeat = Double.NaN; onHint?.invoke("Einzählen abgebrochen") }
                        else if (!recording && Engine.seqPlaying) {
                            // läuft der Takt: einen Takt einzählen, Aufnahme beginnt auf der Eins
                            pendBeat = (Math.floor(Engine.beatPos(Engine.heardFrame()) / 4.0) + 1) * 4.0
                            onHint?.invoke("⏱ Einzählen – auf der Eins geht die Aufnahme los, dann ■ tippen")
                        }
                        else if (!recording) { recording = true; recMark = Engine.micMark(); recStart = System.nanoTime(); onHint?.invoke("● Sing oder sprich ein paar Silben – dann ■ tippen") }
                        else {
                            recording = false
                            val d = Engine.micSince(recMark, 12f)
                            onHint?.invoke(if (v.loadRecording(d)) "✂ Aufnahme in ${v.src?.count ?: 0} Silben zerlegt" else "Zu kurz oder zu leise – Mikrofon an? Nochmal versuchen")
                        }
                    }
                    bPat.contains(x, y) -> {
                        if (v.src == null) { onHint?.invoke("Erst eine Quelle laden"); return }
                        if (v.patIdx < 0) v.patIdx = 0
                        v.patOn = !v.patOn
                        if (v.patOn && !Engine.seqPlaying) Engine.transportPlay(false)
                        if (!v.patOn) v.allOff()
                        onHint?.invoke(if (v.patOn) "▶ ${v.PATTERNS[v.patIdx].name} läuft im Takt" else "Muster aus")
                    }
                    bPatSel.contains(x, y) -> {
                        v.applyPattern(if (v.patIdx < 0) 0 else if (x < bPatSel.centerX()) v.patIdx - 1 else v.patIdx + 1)
                        if (v.src == null) { v.loadDemo(0) }
                        if (!Engine.seqPlaying) Engine.transportPlay(false)
                        onHint?.invoke("🗣 Muster: ${v.PATTERNS[v.patIdx].name}")
                    }
                    bMode.contains(x, y) -> { v.chopMode = (v.chopMode + 1) % 3; v.rechop(); onHint?.invoke("✂ " + when (v.chopMode) { 0 -> "nach Silben (automatisch)"; 1 -> "in 8 gleiche Stücke"; else -> "in 16 gleiche Stücke" }) }
                    bGate.contains(x, y) -> { v.gate = !v.gate; onHint?.invoke(if (v.gate) "GATE: klingt, solange du hältst" else "ONE-SHOT: Chop spielt immer ganz") }
                    bRev.contains(x, y) -> v.reverse = !v.reverse
                    bStut.contains(x, y) -> { v.stutter = (v.stutter + 1) % 4; onHint?.invoke(if (v.stutter > 0) "Stutter ${v.STUTTER_NAMES[v.stutter]}: gehaltener Chop wiederholt sich im Takt" else "Stutter aus") }
                    bToPads.contains(x, y) -> {
                        val parts = v.chopData()
                        if (parts.isEmpty()) onHint?.invoke("Erst eine Quelle laden")
                        else { val cnt = Engine.samplesToPads(parts, 12, "Vocal-Chops → Pads"); onHint?.invoke(if (cnt > 0) "→ $cnt Chops als ⚡ One-Shots auf freie Pads gelegt (↶ macht es rückgängig)" else "Keine freien Pads") }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) { set(drag, get(drag) + (e.getX(0) - lastX) / sl[drag].width()); lastX = e.getX(0) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (held.remove(id) != null) v.off(id)
                if (heldKey.remove(id) != null) v.off(1000 + id)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { v.allOff(); held.clear(); heldKey.clear() }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) drag = -1
            }
        }
    }
}

// ================================================================ 🎛 PERFORMANCE-SZENEN (A/B-Morph)
class MixMorphPanel(host: View) : PerfPanel(host) {
    private val bA = RectF(); private val bB = RectF(); private val bPre = RectF()
    private val fader = RectF()
    private val lens = Array(5) { RectF() }
    private val LENS = intArrayOf(1, 2, 4, 8, 16)
    private val bToB = RectF(); private val bToA = RectF(); private val bStop = RectF()
    private val bJumpA = RectF(); private val bJumpB = RectF()
    private val slotB = Array(6) { RectF() }
    private var slotDown = -1
    private var slotDownAt = 0L
    private var faderPtr = -1

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(255, 200, 90)
        val m = MixMorph
        val status = when {
            m.a == null && m.b == null -> "Mix einstellen → 📸 A · anders einstellen → 📸 B · oder eine Vorlage wählen"
            m.a == null -> "Noch A speichern"
            m.b == null -> "Noch B speichern (Mix ändern, dann 📸 B)"
            else -> "${m.diffCount()} Unterschiede zwischen A und B" + if (!m.autoTo.isNaN()) " · Auto-Morph läuft" else ""
        }
        frame(c, area, "🎛 PERFORMANCE-SZENEN", col, Color.rgb(20, 16, 8), acc, status)
        val pad = dp(10f); val g = dp(6f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(22f)
        row(arrayOf(bA, bB), l, r, y, dp(42f))
        btn(c, bA, if (m.a != null) "📸 A neu speichern" else "📸 A SPEICHERN", m.a != null, Color.rgb(120, 200, 255))
        btn(c, bB, if (m.b != null) "📸 B neu speichern" else "📸 B SPEICHERN", m.b != null, Color.rgb(255, 140, 120))
        y += dp(42f) + g
        bPre.set(l, y, r, y + dp(40f))
        btn(c, bPre, "◀ VORLAGE: " + (if (m.presetIdx >= 0) m.PRESETS[m.presetIdx].name else "jetziger Mix = A, Vorlage = B") + " ▶", m.presetIdx >= 0, col)
        y += dp(40f) + dp(4f)
        if (m.presetIdx >= 0) {
            text.textSize = dp(9.5f); text.color = Color.argb(170, 255, 255, 255)
            c.drawText(fitLabel(text, m.PRESETS[m.presetIdx].desc, r - l, dp(9.5f), dp(7f)), (l + r) / 2, y + dp(9f), text)
        }
        y += dp(16f)
        // großer Morph-Regler (Crossfader)
        fader.set(l, y, r, y + dp(84f))
        fill.color = Color.rgb(34, 28, 18); c.drawRoundRect(fader, dp(12f), dp(12f), fill)
        val ready = m.ready()
        val px = fader.left + dp(48f) + (fader.width() - dp(96f)) * m.pos
        fill.shader = android.graphics.LinearGradient(fader.left, 0f, fader.right, 0f, Color.argb(120, 120, 200, 255), Color.argb(120, 255, 140, 120), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(fader.left + dp(4f), fader.centerY() - dp(4f), fader.right - dp(4f), fader.centerY() + dp(4f)), dp(4f), dp(4f), fill)
        fill.shader = null
        text.textSize = dp(22f); text.color = Color.rgb(120, 200, 255)
        c.drawText("A", fader.left + dp(17f), fader.centerY() + dp(8f), text)
        text.color = Color.rgb(255, 140, 120)
        c.drawText("B", fader.right - dp(17f), fader.centerY() + dp(8f), text)
        if (ready) {
            glow.maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.NORMAL); glow.color = col
            c.drawRoundRect(RectF(px - dp(16f), fader.top + dp(8f), px + dp(16f), fader.bottom - dp(8f)), dp(6f), dp(6f), glow); glow.maskFilter = null
            fill.color = Color.WHITE
            c.drawRoundRect(RectF(px - dp(14f), fader.top + dp(10f), px + dp(14f), fader.bottom - dp(10f)), dp(6f), dp(6f), fill)
            text.textSize = dp(10f); text.color = Color.BLACK
            c.drawText("${(m.pos * 100).toInt()}", px, fader.centerY() + dp(4f), text)
        } else {
            text.textSize = dp(10f); text.color = Color.argb(150, 255, 255, 255)
            c.drawText("erst A und B speichern", fader.centerX(), fader.bottom - dp(10f), text)
        }
        y = fader.bottom + dp(12f)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("AUTO-MORPH ÜBER", l, y + dp(6f), text); text.textAlign = Paint.Align.CENTER
        y += dp(12f)
        row(lens, l, r, y, dp(36f))
        for (k in 0 until 5) btn(c, lens[k], "${LENS[k]} T", m.autoBars == LENS[k], col)
        y += dp(36f) + g
        row(arrayOf(bToA, bToB, bStop), l, r, y, dp(46f))
        val ap = m.autoProgress()
        btn(c, bToA, "⏪ ZU A", !m.autoTo.isNaN() && m.autoTo == 0f, Color.rgb(120, 200, 255), 13f)
        btn(c, bToB, "ZU B ⏩", !m.autoTo.isNaN() && m.autoTo == 1f, Color.rgb(255, 140, 120), 13f)
        btn(c, bStop, "■ HALT", false, col)
        if (!m.autoTo.isNaN()) {
            val br = if (m.autoTo == 1f) bToB else bToA
            if (ap < 0f) { text.textSize = dp(9f); text.color = Color.BLACK; c.drawText("wartet auf die Eins", br.centerX(), br.bottom - dp(5f), text) }
            else { fill.color = Color.argb(180, 0, 0, 0); c.drawRect(br.left + dp(6f), br.bottom - dp(6f), br.left + dp(6f) + (br.width() - dp(12f)) * ap, br.bottom - dp(3f), fill) }
        }
        y += dp(46f) + g
        row(arrayOf(bJumpA, bJumpB), l, r, y, dp(40f))
        btn(c, bJumpA, "SOFORT A", ready && m.pos == 0f, Color.rgb(120, 200, 255))
        btn(c, bJumpB, "SOFORT B", ready && m.pos == 1f, Color.rgb(255, 140, 120))
        y += dp(40f) + dp(12f)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
        c.drawText("SZENEN 1–6 · antippen = hin-morphen · halten = Mix speichern", l, y + dp(4f), text); text.textAlign = Paint.Align.CENTER
        y += dp(10f)
        row(slotB, l, r, y, dp(42f), dp(5f))
        for (k in 0 until 6) {
            val has = m.slots[k] != null
            val held = slotDown == k && System.nanoTime() - slotDownAt > 450_000_000L
            btn(c, slotB[k], if (held) "📸" else "${k + 1}" + if (has) "" else " ·", has && m.curSlot == k || held, if (has) Color.rgb(200, 255, 140) else Color.rgb(90, 90, 100), 14f)
        }
        y += dp(42f) + dp(14f)
        // Unterschiede
        val diffs = m.diffNames(14)
        if (diffs.isNotEmpty()) {
            text.textAlign = Paint.Align.LEFT; text.textSize = dp(10f); text.color = Color.argb(200, 255, 255, 255)
            c.drawText("A → B ändert:", l, y, text)
            y += dp(14f)
            text.textSize = dp(10.5f); text.color = Color.WHITE
            val colW = (r - l) / 2
            for ((k, s) in diffs.withIndex()) {
                val yy = y + (k / 2) * dp(15f)
                if (yy > box.bottom - pad) break
                c.drawText("• " + s, l + (k % 2) * colW, yy, text)
            }
            text.textAlign = Paint.Align.CENTER
        }
        host.postInvalidateOnAnimation()
    }

    private fun setFromX(x: Float) {
        val v = ((x - fader.left - dp(48f)) / (fader.width() - dp(96f))).coerceIn(0f, 1f)
        MixMorph.stopAuto()
        MixMorph.setPos(v)
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val x = e.getX(i); val y = e.getY(i)
        val m = MixMorph
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (fader.contains(x, y)) {
                    if (!m.ready()) { onHint?.invoke("Erst 📸 A und 📸 B speichern – oder eine Vorlage wählen"); return }
                    faderPtr = e.getPointerId(i); setFromX(x); return
                }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                val sk = slotB.indexOfFirst { it.contains(x, y) }
                if (sk >= 0) { slotDown = sk; slotDownAt = System.nanoTime(); return }
                haptic()
                when {
                    bClose.contains(x, y) -> close()
                    bA.contains(x, y) -> { m.storeA(); m.presetIdx = -1; onHint?.invoke("📸 A gespeichert – jetzt den Mix verändern (Lautstärken, Filter, Spuren an/aus) und 📸 B") }
                    bB.contains(x, y) -> { m.storeB(); m.presetIdx = -1; onHint?.invoke(if (m.a != null) "📸 B gespeichert – Regler ziehen oder AUTO-MORPH" else "📸 B gespeichert – noch A speichern") }
                    bPre.contains(x, y) -> {
                        m.applyPreset(if (m.presetIdx < 0) 0 else if (x < bPre.centerX()) m.presetIdx - 1 else m.presetIdx + 1)
                        onHint?.invoke("🎛 A = jetziger Mix, B = ${m.PRESETS[m.presetIdx].name} · Regler ziehen oder ZU B ⏩")
                    }
                    bToB.contains(x, y) -> if (m.ready()) { m.startAuto(1f); onHint?.invoke("⏩ Ab der nächsten Eins in ${m.autoBars} Takt" + (if (m.autoBars > 1) "en" else "") + " zu B") } else onHint?.invoke("Erst A und B speichern")
                    bToA.contains(x, y) -> if (m.ready()) { m.startAuto(0f); onHint?.invoke("⏪ Ab der nächsten Eins in ${m.autoBars} Takt" + (if (m.autoBars > 1) "en" else "") + " zurück zu A") } else onHint?.invoke("Erst A und B speichern")
                    bStop.contains(x, y) -> m.stopAuto()
                    bJumpA.contains(x, y) -> if (m.ready()) { m.stopAuto(); m.setPos(0f) }
                    bJumpB.contains(x, y) -> if (m.ready()) { m.stopAuto(); m.setPos(1f) }
                    else -> { val k = lens.indexOfFirst { it.contains(x, y) }; if (k >= 0) m.autoBars = LENS[k] }
                }
            }
            MotionEvent.ACTION_MOVE -> { val pi = e.findPointerIndex(faderPtr); if (pi >= 0) setFromX(e.getX(pi)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(i) == faderPtr) faderPtr = -1
                val sk = slotDown
                if (sk >= 0 && e.actionMasked == MotionEvent.ACTION_UP) {
                    slotDown = -1
                    haptic()
                    if (System.nanoTime() - slotDownAt > 450_000_000L) { m.storeSlot(sk); onHint?.invoke("📸 Jetziger Mix in Szene ${sk + 1} gespeichert") }
                    else if (m.slots[sk] == null) onHint?.invoke("Szene ${sk + 1} ist leer – halten speichert den jetzigen Mix")
                    else { m.goSlot(sk, true); onHint?.invoke("⏩ Ab der nächsten Eins in ${m.autoBars} Takt" + (if (m.autoBars > 1) "en" else "") + " zu Szene ${sk + 1} (SOFORT B = gleich hin)") }
                }
            }
            MotionEvent.ACTION_CANCEL -> { faderPtr = -1; slotDown = -1 }
        }
    }
}

// ================================================================ 🎛 LADDER-SYNTH (Moog-Art)
class LadderPanel(host: View) : PerfPanel(host) {
    private val bPre = RectF(); private val bTrack = RectF(); private val bLine = RectF(); private val bPlay = RectF()
    private val oscW = Array(3) { RectF() }; private val oscR = Array(3) { RectF() }; private val oscL = Array(3) { RectF() }; private val oscD = Array(3) { RectF() }
    private val sl = Array(12) { RectF() }
    private val SL = arrayOf("CUTOFF", "EMPHASIS", "KONTUR", "DRIVE", "F-ATTACK", "F-DECAY", "F-SUSTAIN", "GLIDE", "A-ATTACK", "A-DECAY", "A-SUSTAIN", "RELEASE")
    private val keys = Array(13) { RectF() }
    private val bOctDn = RectF(); private val bOctUp = RectF()
    private var kOct = 0
    private val held = HashMap<Int, Int>()
    private var dragSl = -1   // 0..11 Regler, 20+k Pegel OSC k, 30+k Verstimmung/Rauschen
    private var dragX = 0f
    private val NOTE = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H", "C'")

    override fun open() { super.open(); Ladder.attach(); if (Ladder.presetIdx < 0 && !trk().hasNotes()) { Ladder.applyPreset(0) } }
    override fun close() { super.close(); for ((id, _) in held) Engine.synTrackLive(Ladder.track, false, 0, 0f, 8800 + id); held.clear() }
    private fun trk() = InstState.synTracks[Ladder.track.coerceIn(0, InstState.SYN_TRACKS - 1)]

    private fun get(i: Int): Float = with(Ladder) { when (i) { 0 -> cutoff; 1 -> emphasis; 2 -> contour; 3 -> drive; 4 -> fAtk; 5 -> fDec; 6 -> fSus; 7 -> glide; 8 -> aAtk; 9 -> aDec; 10 -> aSus; else -> release } }
    private fun set(i: Int, v0: Float) {
        val v = v0.coerceIn(0f, 1f)
        with(Ladder) { when (i) { 0 -> cutoff = v; 1 -> emphasis = v; 2 -> contour = v; 3 -> drive = v; 4 -> fAtk = v; 5 -> fDec = v; 6 -> fSus = v; 7 -> { glide = v; trk().glide = v }; 8 -> aAtk = v; 9 -> aDec = v; 10 -> aSus = v; else -> release = v } }
        Ladder.presetIdx = -1
    }
    private fun value(i: Int): String {
        val v = get(i)
        return when (i) {
            0 -> { val hz = 30.0 * Math.pow(2.0, v * 9.5); if (hz >= 1000) String.format(java.util.Locale.GERMANY, "%.1f kHz", hz / 1000) else "${hz.toInt()} Hz" }
            4, 5, 8, 9, 11 -> { val s = Ladder.secs(v); if (s >= 1) String.format(java.util.Locale.GERMANY, "%.1f s", s) else "${(s * 1000).toInt()} ms" }
            7 -> if (v < 0.01f) "aus" else "${((0.004 + v * v * 0.4) * 1000).toInt()} ms"
            else -> "${(v * 100).toInt()}"
        }
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        val col = Color.rgb(255, 170, 80)
        val L = Ladder
        frame(c, area, "🎛 LADDER (Moog-Art)", col, Color.rgb(22, 16, 10), acc,
            "spielt auf ${InstState.synName(L.track)} · MONO + GLIDE · " + (if (L.presetIdx >= 0) L.PRESETS[L.presetIdx].name else "eigener Klang"))
        val pad = dp(10f); val g = dp(5f)
        val l = box.left + pad; val r = box.right - pad
        var y = bClose.bottom + dp(20f)
        bPre.set(l, y, l + (r - l) * 0.5f - g / 2, y + dp(36f))
        row(arrayOf(bTrack, bLine, bPlay), l + (r - l) * 0.5f + g / 2, r, y, dp(36f), g)
        btn(c, bPre, "◀ " + (if (L.presetIdx >= 0) L.PRESETS[L.presetIdx].name else "VORLAGE") + " ▶", L.presetIdx >= 0, col)
        btn(c, bTrack, InstState.synName(L.track), false, col)
        btn(c, bLine, "♪ LINIE", false, col)
        val run = trk().on && Engine.seqPlaying
        btn(c, bPlay, if (run) "■" else "▶", run, Color.rgb(140, 230, 170))
        y += dp(36f) + dp(8f)
        // Oszillatoren: Wellenform · Fußlage · Pegel · Verstimmung (OSC 1: Rauschen)
        val oh = dp(32f)
        for (k in 0 until 3) {
            val yy = y + k * (oh + g)
            val w1 = dp(58f); val w2 = dp(42f)
            oscW[k].set(l, yy, l + w1, yy + oh); oscR[k].set(oscW[k].right + g, yy, oscW[k].right + g + w2, yy + oh)
            val rest = r - oscR[k].right - g
            oscL[k].set(oscR[k].right + g, yy, oscR[k].right + g + (rest - g) / 2, yy + oh)
            oscD[k].set(oscL[k].right + g, yy, r, yy + oh)
            btn(c, oscW[k], "${k + 1}·" + L.WAVE_NAMES[L.oscWave[k]], true, col, 11f)
            btn(c, oscR[k], L.RANGE_NAMES[L.oscRange[k]], false, col, 11f)
            slider(c, oscL[k], "PEGEL", L.oscLevel[k], "${(L.oscLevel[k] * 100).toInt()}", col)
            when (k) {
                0 -> slider(c, oscD[k], "RAUSCHEN", L.noise, "${(L.noise * 100).toInt()}", col)
                1 -> slider(c, oscD[k], "VERST. ↑", L.detune2, "+${(L.detune2 * 50).toInt()} ct", col)
                else -> slider(c, oscD[k], "VERST. ↓", L.detune3, "−${(L.detune3 * 50).toInt()} ct", col)
            }
        }
        y += 3 * (oh + g) + dp(6f)
        val sh = dp(31f)
        val sw = (r - l - g) / 2
        for (i in 0 until 12) {
            val rr = i / 2; val cc = i % 2
            sl[i].set(l + cc * (sw + g), y + rr * (sh + g), l + cc * (sw + g) + sw, y + rr * (sh + g) + sh)
            slider(c, sl[i], SL[i], get(i), value(i), if (i < 4) col else if (i < 8) Color.rgb(255, 210, 120) else Color.rgb(200, 220, 255))
        }
        y = sl[11].bottom + dp(8f)
        // Tastatur
        val kh = box.bottom - pad - y
        val ob = dp(34f)
        bOctDn.set(l, y, l + ob, y + kh); bOctUp.set(r - ob, y, r, y + kh)
        btn(c, bOctDn, "−", false, col, 14f); btn(c, bOctUp, "+", false, col, 14f)
        val kl = bOctDn.right + g; val kr = bOctUp.left - g
        val kw = (kr - kl - 12 * dp(2f)) / 13
        for (k in 0 until 13) {
            keys[k].set(kl + k * (kw + dp(2f)), y, kl + k * (kw + dp(2f)) + kw, y + kh)
            val black = k % 12 in intArrayOf(1, 3, 6, 8, 10)
            val on = held.values.contains(k)
            fill.color = if (on) col else if (black) Color.rgb(40, 32, 26) else Color.rgb(230, 222, 210)
            c.drawRoundRect(keys[k], dp(5f), dp(5f), fill)
            text.color = if (on || !black) Color.BLACK else Color.WHITE
            text.textSize = min(dp(9.5f), kw * 0.42f)
            c.drawText(NOTE[k], keys[k].centerX(), keys[k].bottom - dp(6f), text)
        }
        text.textSize = dp(8.5f); text.color = Color.argb(150, 255, 255, 255)
        c.drawText("Oktave " + (if (kOct > 0) "+" else "") + kOct, (l + r) / 2, y - dp(1.5f), text)
        host.postInvalidateOnAnimation()
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        val L = Ladder
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keys.indexOfFirst { it.contains(x, y) }
                if (k >= 0) {
                    L.attach()
                    held[id] = k
                    Engine.synTrackLive(L.track, true, kOct * 12 + k, 0.9f, 8800 + id)
                    return
                }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                for (s in 0 until 12) if (sl[s].contains(x, y)) { dragSl = s; dragX = x; return }
                for (q in 0 until 3) {
                    if (oscL[q].contains(x, y)) { dragSl = 20 + q; dragX = x; return }
                    if (oscD[q].contains(x, y)) { dragSl = 30 + q; dragX = x; return }
                }
                haptic()
                val wq = oscW.indexOfFirst { it.contains(x, y) }
                if (wq >= 0) { L.oscWave[wq] = (L.oscWave[wq] + 1) % L.WAVE_NAMES.size; L.presetIdx = -1; return }
                val rq = oscR.indexOfFirst { it.contains(x, y) }
                if (rq >= 0) { L.oscRange[rq] = (L.oscRange[rq] + 1) % L.RANGE_NAMES.size; L.presetIdx = -1; return }
                when {
                    bClose.contains(x, y) -> close()
                    bPre.contains(x, y) -> {
                        L.applyPreset(if (L.presetIdx < 0) 0 else if (x < bPre.centerX()) L.presetIdx - 1 else L.presetIdx + 1)
                        onHint?.invoke("🎛 ${L.PRESETS[L.presetIdx].name} – Tasten unten spielen, ♪ LINIE schreibt eine Linie, ▶ startet")
                    }
                    bTrack.contains(x, y) -> {
                        L.track = when (L.track) { InstState.BASS_TRACK -> InstState.KEYS_TRACK; InstState.KEYS_TRACK -> 0; 0 -> 1; 1 -> 2; else -> InstState.BASS_TRACK }
                        L.attach()
                        onHint?.invoke("Ladder spielt jetzt auf ${InstState.synName(L.track)}")
                    }
                    bLine.contains(x, y) -> {
                        Engine.pushPatternUndo("Ladder-Linie")
                        val old = InstState.synCur
                        InstState.synCur = L.track
                        if (L.track == InstState.BASS_TRACK || (L.presetIdx >= 0 && L.PRESETS[L.presetIdx].bass)) InstState.bassRandom() else InstState.synRandom()
                        InstState.synCur = old
                        L.attach()
                        if (!Engine.seqPlaying) Engine.transportPlay(false)
                        onHint?.invoke("♪ Neue Linie auf ${InstState.synName(L.track)} – nochmal tippen für eine andere")
                    }
                    bPlay.contains(x, y) -> {
                        val t = trk()
                        if (t.on && Engine.seqPlaying) Engine.transportStop() else { L.attach(); if (!t.hasNotes()) { val old = InstState.synCur; InstState.synCur = L.track; InstState.bassRandom(); InstState.synCur = old }; if (!Engine.seqPlaying) Engine.transportPlay(false) }
                    }
                    bOctDn.contains(x, y) -> kOct = (kOct - 1).coerceAtLeast(-2)
                    bOctUp.contains(x, y) -> kOct = (kOct + 1).coerceAtMost(2)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragSl >= 0) {
                val px = e.getX(0)
                val ds = dragSl
                when {
                    ds < 12 -> set(ds, get(ds) + (px - dragX) / sl[ds].width())
                    ds < 30 -> { val q = ds - 20; L.oscLevel[q] = (L.oscLevel[q] + (px - dragX) / oscL[q].width()).coerceIn(0f, 1f); L.presetIdx = -1 }
                    else -> { val q = ds - 30; val dv = (px - dragX) / oscD[q].width(); when (q) { 0 -> L.noise = (L.noise + dv).coerceIn(0f, 1f); 1 -> L.detune2 = (L.detune2 + dv).coerceIn(0f, 1f); else -> L.detune3 = (L.detune3 + dv).coerceIn(0f, 1f) }; L.presetIdx = -1 }
                }
                dragX = px
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (held.remove(id) != null) Engine.synTrackLive(L.track, false, 0, 0f, 8800 + id)
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) dragSl = -1
            }
        }
    }
}
