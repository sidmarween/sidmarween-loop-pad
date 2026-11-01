package de.marween.looppad

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** 🌋🎸 BASS-LABOR: Bedienfeld für die Bass-Motoren MAGMA und FUNK der BASSLINE, mit Spieltasten (Slap/Pop/Ghost, Slide). */
class BassLabPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    /** BASSLINE-Editor öffnen (Noten bearbeiten). */
    var onEditNotes: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /** 0 = MAGMA, 1 = FUNK */
    var tab = 0
    private val box = RectF()
    private val bClose = RectF(); private val tMagma = RectF(); private val tFunk = RectF()
    private val bOn = RectF(); private val bNotes = RectF(); private val bPreset = RectF()
    private val visc = RectF()
    private val sliders = Array(4) { RectF() }
    private val crater = RectF()
    private val keys = Array(8) { RectF() }
    private var drag = -1          // 0..3 Regler, 10 Viskosität, 11 Krater
    private var lastX = 0f
    private var crLastX = 0f; private var crLastT = 0L
    private val INTS = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17)
    /** Finger → gespielte Taste, für Slides. */
    private val keyPtr = HashMap<Int, Int>()
    private var keyFlash = FloatArray(8)

    fun open(t: Int) {
        tab = t
        isOpen = true
        BassLab.useOn(if (tab == 0) BassLab.MAGMA else BassLab.FUNK)
        if (tab == 0 && BassLab.mPreset < 0) BassLab.applyMagma(1)
        if (tab == 1 && BassLab.fPreset < 0) BassLab.applyFunk(0)
    }
    fun close() { isOpen = false; allOff() }

    private val SLM = arrayOf("SUB-ANKER", "SPUCKE", "RÖHRE", "")
    private val SLF = arrayOf("FUNK-JUICE", "SLAP-TIGHTNESS", "THUMB SUB", "TALK (Formant)")
    private fun get(i: Int): Float = if (tab == 0) when (i) { 0 -> BassLab.sub; 1 -> BassLab.spit; else -> BassLab.drive }
        else when (i) { 0 -> BassLab.juice; 1 -> BassLab.tight; 2 -> BassLab.thumbSub; else -> BassLab.talk }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        if (tab == 0) when (i) { 0 -> BassLab.sub = c; 1 -> BassLab.spit = c; else -> BassLab.drive = c }
        else when (i) { 0 -> BassLab.juice = c; 1 -> BassLab.tight = c; 2 -> BassLab.thumbSub = c; else -> BassLab.talk = c }
        if (tab == 0) BassLab.mPreset = -1 else BassLab.fPreset = -1
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(6f); val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val r1 = box.top + pad + bh + g
        tMagma.set(box.left + pad, r1, box.left + pad + (w - g) / 2, r1 + bh)
        tFunk.set(tMagma.right + g, r1, box.right - pad, r1 + bh)
        val r2 = r1 + bh + g
        bOn.set(box.left + pad, r2, box.left + pad + w * 0.27f, r2 + bh)
        bNotes.set(bOn.right + g, r2, bOn.right + g + w * 0.2f, r2 + bh)
        bPreset.set(bNotes.right + g, r2, box.right - pad, r2 + bh)
        var y = r2 + bh + dp(10f)
        val sh = dp(42f)
        val keyH = dp(92f)
        val keyTop = box.bottom - pad - keyH
        val kw = (w - 7 * dp(3f)) / 8
        for (k in 0 until 8) keys[k].set(box.left + pad + k * (kw + dp(3f)), keyTop, box.left + pad + k * (kw + dp(3f)) + kw, keyTop + keyH)
        if (tab == 0) {
            visc.set(box.left + pad, y, box.right - pad, y + dp(64f))
            y = visc.bottom + g
            for (i in 0 until 3) { val sw = (w - 2 * g) / 3; sliders[i].set(box.left + pad + i * (sw + g), y, box.left + pad + i * (sw + g) + sw, y + sh) }
            sliders[3].setEmpty()
            y += sh + g
            crater.set(box.left + pad, y, box.right - pad, keyTop - dp(18f))
        } else {
            visc.setEmpty(); crater.setEmpty()
            for (i in 0 until 4) { val r = i / 2; val c = i % 2; val sw = (w - g) / 2; sliders[i].set(box.left + pad + c * (sw + g), y + r * (sh + g), box.left + pad + c * (sw + g) + sw, y + r * (sh + g) + sh) }
            // Funk: große Spieltasten
            val kt = sliders[3].bottom + dp(36f)
            for (k in 0 until 8) keys[k].set(keys[k].left, kt, keys[k].right, box.bottom - pad)
        }
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(30, 26, 26)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(12f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = if (tab == 0) Color.rgb(255, 110, 40) else Color.rgb(255, 210, 60)
        fill.shader = null
        fill.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = if (tab == 0) Color.rgb(22, 10, 6) else Color.rgb(20, 16, 6)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🔬 BASS-LABOR · BASSLINE", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, tMagma, "🌋 MAGMA", tab == 0, Color.rgb(255, 110, 40))
        btn(c, tFunk, "🎸 FUNK-SLAP", tab == 1, Color.rgb(255, 210, 60))
        val t = InstState.synTracks[InstState.BASS_TRACK]
        val running = t.on && Engine.seqPlaying
        btn(c, bOn, if (running) "■ STOPP" else "▶ BASSLINE", running, Color.rgb(140, 230, 170))
        btn(c, bNotes, "✏ NOTEN", false, col)
        val pn = if (tab == 0) BassLab.mPreset.let { if (it >= 0) BassLab.MAGMA_PRESETS[it].name else "VORLAGE" } else BassLab.fPreset.let { if (it >= 0) BassLab.FUNK_PRESETS[it].name else "VORLAGE" }
        btn(c, bPreset, "◀ $pn ▶", true, col)
        if (tab == 0) drawMagma(c) else {
            text.color = Color.argb(180, 255, 255, 255); text.textSize = dp(10f)
            c.drawText(fitLabel(text, "oben = POP · Mitte = SLAP · unten = GHOST · wischen = Slide", box.width() - dp(24f), dp(10f), dp(7f)), box.centerX(), sliders[3].bottom + dp(22f), text)
        }
        for (i in 0 until 4) {
            val r = sliders[i]
            if (r.isEmpty) continue
            fill.color = Color.rgb(40, 30, 26)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(160, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(fitLabel(text, if (tab == 0) SLM[i] else SLF[i], r.width() * 0.72f, dp(10f), dp(6.5f)), r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        drawKeys(c, col)
        for (k in 0 until 8) keyFlash[k] *= 0.88f
        host.postInvalidateOnAnimation()
    }

    private fun drawMagma(c: Canvas) {
        // Viskositäts-Streifen
        val r = visc
        fill.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Color.rgb(20, 40, 90), Color.rgb(200, 70, 20), Color.rgb(255, 230, 120)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(10f), dp(10f), fill)
        fill.shader = null
        val vx = r.left + BassLab.visc * r.width()
        val tm = SystemClock.uptimeMillis() / 1000f
        // Blasen
        val bubbles = (BassLab.visc * 14).toInt()
        fill.color = Color.argb(140, 255, 220, 160)
        for (k in 0 until bubbles) {
            val bx = r.left + ((k * 53 + (tm * 40 * (k % 3 + 1)).toInt()) % 1000) / 1000f * (vx - r.left).coerceAtLeast(1f)
            val by = r.bottom - ((tm * (20 + 9 * k)) % 1f) * r.height()
            c.drawCircle(bx, by, dp(2f + (k % 3)), fill)
        }
        glow.maskFilter = BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb(200, 255, 200, 120)
        c.drawCircle(vx, r.centerY(), dp(16f), glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawRoundRect(RectF(vx - dp(3f), r.top + dp(4f), vx + dp(3f), r.bottom - dp(4f)), dp(3f), dp(3f), fill)
        text.color = Color.WHITE; text.textSize = dp(10f)
        text.textAlign = Paint.Align.LEFT; c.drawText("FLÜSSIG (Sub)", r.left + dp(8f), r.top + dp(14f), text)
        text.textAlign = Paint.Align.CENTER; c.drawText("KOCHEND", r.centerX(), r.top + dp(14f), text)
        text.textAlign = Paint.Align.RIGHT; c.drawText("AUSBRUCH", r.right - dp(8f), r.top + dp(14f), text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(13f)
        c.drawText("VISKOSITÄT ${(BassLab.visc * 100).toInt()}", r.centerX(), r.bottom - dp(10f), text)
        // Krater-Pad
        val k = crater
        if (k.height() < dp(40f)) return
        fill.shader = LinearGradient(k.left, k.bottom, k.right, k.top, Color.rgb(30, 12, 8), Color.rgb(90, 30, 10), Shader.TileMode.CLAMP)
        c.drawRoundRect(k, dp(12f), dp(12f), fill)
        fill.shader = null
        val px = k.left + BassLab.craterCut * k.width()
        val py = k.bottom - BassLab.craterRes * k.height()
        val kk = BassLab.kick
        glow.maskFilter = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb((100 + 155 * kk).toInt().coerceAtMost(255), 255, 120, 40)
        c.drawCircle(px, py, dp(22f) + dp(30f) * kk, glow)
        glow.maskFilter = null
        fill.color = Color.WHITE
        c.drawCircle(px, py, dp(6f), fill)
        text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(9f)
        text.textAlign = Paint.Align.LEFT
        c.drawText("🌋 KRATER · ↑ RESONANZ (Fauchen)", k.left + dp(6f), k.top + dp(12f), text)
        c.drawText("← ZU", k.left + dp(6f), k.bottom + dp(11f), text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("AUF → · schnell nach rechts wischen = Drop-Kick", k.right - dp(6f), k.bottom + dp(11f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun rootSemi(): Int {
        if (InstState.scaleType != 0) return Math.floorMod(InstState.scaleRoot, 12).let { if (it > 6) it - 12 else it }
        if (GrooveGen.made) return GrooveGen.root.let { if (it > 6) it - 12 else it }
        return 0
    }

    private fun drawKeys(c: Canvas, col: Int) {
        val root = rootSemi()
        for (k in 0 until 8) {
            val r = keys[k]
            val fl = keyFlash[k].coerceIn(0f, 1f)
            fill.color = Color.rgb(36 + (90 * fl).toInt(), 30 + (60 * fl).toInt(), 26)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            stroke.strokeWidth = dp(1f); stroke.color = Color.argb(140, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(r, dp(7f), dp(7f), stroke)
            // Zonen
            stroke.color = Color.argb(50, 255, 255, 255)
            c.drawLine(r.left + dp(3f), r.top + r.height() / 3, r.right - dp(3f), r.top + r.height() / 3, stroke)
            c.drawLine(r.left + dp(3f), r.top + r.height() * 2 / 3, r.right - dp(3f), r.top + r.height() * 2 / 3, stroke)
            text.color = Color.WHITE; text.textSize = min(dp(12f), r.width() * 0.32f)
            c.drawText(InstState.scaleNoteNames[Math.floorMod(root + INTS[k], 12)], r.centerX(), r.centerY() + dp(4f), text)
            text.textSize = dp(7.5f); text.color = Color.argb(150, 255, 255, 255)
            c.drawText("POP", r.centerX(), r.top + dp(11f), text)
            if (r.height() > dp(150f)) c.drawText("SLAP", r.centerX(), r.top + r.height() / 2 - dp(14f), text)
            c.drawText("GHOST", r.centerX(), r.bottom - dp(5f), text)
        }
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun keyAt(x: Float, y: Float): Int = keys.indexOfFirst { RectF(it).apply { inset(-dp(1.5f), 0f) }.contains(x, y) }

    private fun velAt(k: Int, y: Float): Float {
        val r = keys[k]
        val f = (y - r.top) / r.height()
        return if (f < 1f / 3) 1f else if (f < 2f / 3) 0.75f else 0.35f
    }

    private fun noteOn(k: Int, vel: Float, tag: Int) {
        val semi = rootSemi() + INTS[k]
        Engine.synTrackLive(InstState.BASS_TRACK, true, semi, vel, tag)
        keyFlash[k] = 1f
    }

    private fun allOff() { for ((id, _) in keyPtr) Engine.synTrackLive(InstState.BASS_TRACK, false, 0, 0f, 9000 + id); keyPtr.clear() }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keyAt(x, y)
                if (k >= 0) {
                    keyPtr[id] = k
                    // eigene Spur auf diesen Motor stellen, falls jemand umgeschaltet hat
                    val want = if (tab == 0) BassLab.MAGMA else BassLab.FUNK
                    if (InstState.synTracks[InstState.BASS_TRACK].wave != want) BassLab.useOn(want)
                    noteOn(k, velAt(k, y), 9000 + id)
                    return
                }
                if (!visc.isEmpty && visc.contains(x, y)) { drag = 10; setVisc(x); return }
                if (!crater.isEmpty && crater.contains(x, y)) { drag = 11; crLastX = x; crLastT = SystemClock.uptimeMillis(); setCrater(x, y); return }
                for (s in 0 until 4) if (!sliders[s].isEmpty && sliders[s].contains(x, y)) { drag = s; lastX = x; return }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) {
                val pid = e.getPointerId(p)
                val px = e.getX(p); val py = e.getY(p)
                val held = keyPtr[pid]
                if (held != null) {
                    val k = keyAt(px, py)
                    if (k >= 0 && k != held) {
                        // Slide: neue Note, bevor die alte losgelassen wird (MONO + GLIDE = Gummiband)
                        val tagOld = 9000 + pid
                        noteOn(k, 0.75f, tagOld + 100)
                        Engine.synTrackLive(InstState.BASS_TRACK, false, 0, 0f, tagOld)
                        keyPtr[pid] = k
                        // Tag tauschen: alter Tag ist aus, der neue klingt
                        slideTags[pid] = tagOld + 100
                    }
                    continue
                }
                when (drag) {
                    10 -> setVisc(px)
                    11 -> {
                        val now = SystemClock.uptimeMillis()
                        val dt = (now - crLastT).coerceAtLeast(1)
                        val v = (px - crLastX) / dt   // px pro ms
                        if (v > dp(1.6f)) BassLab.craterKick()
                        crLastX = px; crLastT = now
                        setCrater(px, py)
                    }
                    in 0..3 -> if (p == 0) { set(drag, get(drag) + (px - lastX) / sliders[drag].width()); lastX = px }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (keyPtr.remove(id) != null) {
                    Engine.synTrackLive(InstState.BASS_TRACK, false, 0, 0f, 9000 + id)
                    slideTags.remove(id)?.let { Engine.synTrackLive(InstState.BASS_TRACK, false, 0, 0f, it) }
                }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) { drag = -1; if (e.actionMasked == MotionEvent.ACTION_CANCEL) allOff() }
            }
        }
    }
    private val slideTags = HashMap<Int, Int>()

    private fun setVisc(px: Float) {
        BassLab.visc = ((px - visc.left) / visc.width()).coerceIn(0f, 1f)
        BassLab.mPreset = -1
    }
    private fun setCrater(px: Float, py: Float) {
        BassLab.craterCut = ((px - crater.left) / crater.width()).coerceIn(0f, 1f)
        BassLab.craterRes = ((crater.bottom - py) / crater.height()).coerceIn(0f, 1f)
        BassLab.mPreset = -1
    }

    private fun tap(x: Float, y: Float) {
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            tMagma.contains(x, y) -> { haptic(); tab = 0; BassLab.useOn(BassLab.MAGMA); if (BassLab.mPreset < 0) BassLab.applyMagma(1); onHint?.invoke("🌋 MAGMA auf der BASSLINE · Streifen: flüssig → kochend → Ausbruch · Krater-Pad: Filter, schnell rechts wischen = Drop") }
            tFunk.contains(x, y) -> { haptic(); tab = 1; BassLab.useOn(BassLab.FUNK); if (BassLab.fPreset < 0) BassLab.applyFunk(0); onHint?.invoke("🎸 FUNK-SLAP auf der BASSLINE · leise Noten = Ghost, laute/hohe = Pop") }
            bOn.contains(x, y) -> {
                haptic()
                val t = InstState.synTracks[InstState.BASS_TRACK]
                if (t.on && Engine.seqPlaying) { Engine.transportStop() } else { t.on = true; if (!Engine.seqPlaying) Engine.transportPlay(false) }
            }
            bNotes.contains(x, y) -> { haptic(); close(); onEditNotes?.invoke() }
            bPreset.contains(x, y) -> {
                haptic()
                val left = x < bPreset.centerX()
                if (tab == 0) {
                    BassLab.applyMagma(if (BassLab.mPreset < 0) 0 else BassLab.mPreset + if (left) -1 else 1)
                    onHint?.invoke("🌋 ${BassLab.MAGMA_PRESETS[BassLab.mPreset].name}")
                } else {
                    BassLab.applyFunk(if (BassLab.fPreset < 0) 0 else BassLab.fPreset + if (left) -1 else 1)
                    onHint?.invoke("🎸 ${BassLab.FUNK_PRESETS[BassLab.fPreset].name}")
                }
            }
        }
    }
}
