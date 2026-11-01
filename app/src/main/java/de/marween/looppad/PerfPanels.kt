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
