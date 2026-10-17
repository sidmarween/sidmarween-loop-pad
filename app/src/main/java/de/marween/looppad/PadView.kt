package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Zeichnet die 16 Neon-Pads und verarbeitet Multitouch. */
class PadView(context: Context) : View(context) {

    var message: String? = null
    var onMessageTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val padRects = Array(Engine.PADS) { RectF() }
    private val padMeasures = Array(Engine.PADS) { PathMeasure() }
    private val padLengths = FloatArray(Engine.PADS)
    private val clearBtn = RectF()
    private val knobRects = Array(4) { RectF() }
    private val moreRects = Array(Engine.PADS) { RectF() }
    private val keyRects = Array(Engine.PADS) { RectF() }
    var onPadMenu: ((Int) -> Unit)? = null
    private val fxBtn = RectF()
    private val xyRect = RectF()
    private var knobSize = 0f
    private val pointerKnob = HashMap<Int, Int>()
    private val knobStartY = HashMap<Int, Float>()
    private val knobStartVal = HashMap<Int, Float>()
    private var xyPointer = -1
    /** HOLD: Effekt bleibt aktiv, auch ohne Finger auf der XY-Fläche. */
    private var xyHold = false
    private val holdRect = RectF()

    private fun updateHoldRect() {
        val r = xyRect
        val narrow = r.width() < dp(330f)
        val w = if (narrow) (r.width() - dp(24f)) * 0.42f else dp(70f)
        val h = dp(30f)
        val top = if (narrow) r.top + dp(44f) else r.top + dp(8f)
        holdRect.set(r.right - dp(8f) - w, top, r.right - dp(8f), top + h)
    }

    private fun drawHoldButton(canvas: Canvas) {
        if (xyRect.width() <= 0f) return
        updateHoldRect()
        val r = holdRect
        val cols = overlayColors()
        val rr = r.height() / 2f
        val on = xyHold
        val pulse = 0.5f + 0.5f * sin(time * 6f)
        fillPaint.shader = null
        if (on) {
            fillPaint.color = alpha(cols[2], 0.25f + 0.2f * pulse)
            tmpRect.set(r.left - dp(4f), r.top - dp(4f), r.right + dp(4f), r.bottom + dp(4f))
            canvas.drawRoundRect(tmpRect, rr + dp(4f), rr + dp(4f), fillPaint)
        }
        fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.85f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (on) Color.WHITE else alpha(cols[1], 0.6f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        // Schloss-Symbol
        val lx = r.left + dp(16f)
        val ly = r.centerY() + dp(2f)
        val ic = if (on) Color.WHITE else cols[1]
        fillPaint.color = ic
        canvas.drawRoundRect(lx - dp(5f), ly - dp(3f), lx + dp(5f), ly + dp(5f), dp(1.5f), dp(1.5f), fillPaint)
        strokePaint.strokeWidth = dp(1.6f)
        strokePaint.color = ic
        tmpRect.set(lx - dp(3.5f), ly - dp(9f), lx + dp(3.5f), ly - dp(1f))
        canvas.drawArc(tmpRect, 180f, if (on) 180f else 150f, false, strokePaint)
        textPaint.textSize = dp(11f)
        textPaint.color = ic
        canvas.drawText("HOLD", r.centerX() + dp(9f), r.centerY() + dp(4f), textPaint)
    }

    private val motRect = RectF()

    private fun autoTouching(): Boolean = Engine.motionState == Engine.MOT_PLAY && xyPointer < 0 && Engine.motCurTouch

    private fun drawMotionButton(canvas: Canvas) {
        if (xyRect.width() <= 0f) return
        val w = if (xyRect.width() < dp(330f)) (xyRect.width() - dp(24f)) * 0.58f else dp(84f)
        motRect.set(holdRect.left - dp(8f) - w, holdRect.top, holdRect.left - dp(8f), holdRect.bottom)
        val r = motRect
        val cols = overlayColors()
        val st = Engine.motionState
        val red = Color.rgb(230, 40, 55)
        val pulse = 0.5f + 0.5f * sin(time * 8f)
        val rr = r.height() / 2f
        val fill = when (st) {
            Engine.MOT_ARMED -> blend(alpha(cols[0], 0.9f), red, 0.35f * pulse)
            Engine.MOT_REC -> blend(red, Color.WHITE, 0.25f * pulse)
            Engine.MOT_PLAY -> cols[2]
            else -> alpha(cols[0], 0.85f)
        }
        fillPaint.shader = null
        fillPaint.color = fill
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        // Fortschritt beim Abspielen
        val mp = Engine.motionProgress()
        if (mp >= 0f) {
            canvas.save()
            canvas.clipRect(r.left, r.top, r.left + r.width() * mp, r.bottom)
            fillPaint.color = alpha(Color.WHITE, 0.25f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            canvas.restore()
        }
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (st == Engine.MOT_IDLE) alpha(cols[1], 0.6f) else Color.WHITE
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        val label = when (st) {
            Engine.MOT_ARMED -> "● BEREIT"
            Engine.MOT_REC -> String.format(Locale.GERMANY, "● %.1fs", Engine.motionSeconds())
            Engine.MOT_PLAY -> "▶ AUTO"
            else -> "● REC"
        }
        textPaint.textSize = dp(11f)
        textPaint.color = if (st == Engine.MOT_IDLE) cols[1] else Color.WHITE
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4f), textPaint)
        if (st == Engine.MOT_ARMED) {
            textPaint.textSize = dp(10f)
            textPaint.color = alpha(cols[1], 0.75f)
            canvas.drawText("Fläche berühren = Aufnahme startet", xyRect.centerX(), r.bottom + dp(16f), textPaint)
        }
    }

    private fun motionPressed() {
        Engine.motionButton()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        burst(motRect.centerX(), motRect.centerY(), Color.rgb(230, 40, 55), 14, 180f)
    }

    private val slotRects = Array(Engine.SLOTS) { RectF() }

    /** Effekt-Kette: drei Plätze oben links auf der XY-Fläche. */
    private fun drawSlotChips(canvas: Canvas) {
        val r = xyRect
        if (r.width() <= 0f) return
        val cols = overlayColors()
        val h = dp(30f)
        val w = if (r.width() < dp(330f)) (r.width() - dp(26f)) / 3f else min(dp(54f), (r.width() - dp(190f)) / 3f - dp(5f)).coerceAtLeast(dp(38f))
        for (s in 0 until Engine.SLOTS) {
            val x = r.left + dp(8f) + s * (w + dp(5f))
            slotRects[s].set(x, r.top + dp(8f), x + w, r.top + dp(8f) + h)
            val c = slotRects[s]
            val on = Engine.slotOn[s]
            val sel = Engine.editSlot == s
            val rr = dp(7f)
            fillPaint.shader = null
            fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.85f)
            canvas.drawRoundRect(c, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(if (sel) 2.5f else 1f)
            strokePaint.color = if (sel) (if (on) Color.WHITE else cols[2]) else alpha(cols[1], 0.4f)
            canvas.drawRoundRect(c, rr, rr, strokePaint)
            textPaint.textSize = dp(8f)
            textPaint.color = if (on) alpha(Color.WHITE, 0.85f) else alpha(cols[1], 0.6f)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${s + 1}", c.left + dp(4f), c.top + dp(10f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(11f)
            textPaint.color = if (on) Color.WHITE else alpha(cols[1], if (sel) 0.9f else 0.5f)
            canvas.drawText(if (on) FX_CODES[Engine.slotType[s]] else "AUS", c.centerX() + dp(3f), c.centerY() + dp(5f), textPaint)
            // Pfeil zur nächsten Station
            if (s < Engine.SLOTS - 1) {
                textPaint.textSize = dp(8f)
                textPaint.color = alpha(cols[1], 0.5f)
                canvas.drawText("›", c.right + dp(2.5f), c.centerY() + dp(3f), textPaint)
            }
        }
    }

    private fun slotPressed(s: Int) {
        if (Engine.editSlot == s) {
            Engine.toggleSlot(s)
        } else {
            Engine.selectSlot(s)
        }
        fxFlash = 1f
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        burst(slotRects[s].centerX(), slotRects[s].centerY(), overlayColors()[2], 12, 160f)
    }

    private fun toggleHold() {
        xyHold = !xyHold
        Engine.fxTouch = xyHold || xyPointer >= 0
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        burst(holdRect.centerX(), holdRect.centerY(), overlayColors()[2], 16, 200f)
    }
    private var xyDispX = 0.5f
    private var xyDispY = 0.5f
    private var xyGlow = 0f
    private var fxFlash = 0f
    private var fxPointer = -1
    private var fxDownMs = 0L
    private var fxDownX = 0f
    var onFxList: (() -> Unit)? = null
    private val fxLongPress = Runnable {
        if (fxPointer >= 0) {
            fxPointer = -1
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onFxList?.invoke()
        }
    }

    fun fxChosen() {
        fxFlash = 1f
        burst(fxBtn.centerX(), fxBtn.centerY(), neon(Fx.HUES[Engine.fxType]), 30, 300f)
    }
    private val muteBtn = RectF()
    private val designBtn = RectF()
    private val seqRect = RectF()
    private val stepRects = Array(Engine.STEPS) { RectF() }
    private val recBtn = RectF()
    private val playBtn = RectF()
    private val stopBtn = RectF()
    private var stopDownMs = 0L
    private var stopPointer = -1
    private var stepPointer = -1
    private var stepIdx = 0
    private var stepDownY = 0f
    private var stepDragged = false
    private var stepStartVel = 0f
    var onRecMenu: (() -> Unit)? = null
    var onSongStopped: (() -> Unit)? = null
    var onPatternStopped: (() -> Unit)? = null
    private val displayRect = RectF()
    var onDesignMenu: (() -> Unit)? = null
    var onTempo: (() -> Unit)? = null
    var onRecSettingsChanged: (() -> Unit)? = null

    private fun bpmLabel(): String {
        val b = Engine.currentBpm()
        val t = if (b > 0f) String.format(Locale.GERMANY, "%.0f BPM", b) else "– BPM"
        return if (Engine.metronome) "♪ $t" else t
    }

    /** Gewähltes Design: NEON, GLOW, STUDIO, HUD. */
    var theme = NEON
        set(v) {
            field = v.coerceIn(0, DESIGN_NAMES.size - 1)
            layoutPads()
            invalidate()
        }
    private var insetTop = 0f
    private var insetBottom = 0f
    private var radius = 0f

    private val flash = FloatArray(Engine.PADS)
    private val pointerPad = HashMap<Int, Int>()
    private val shotPointers = HashSet<Int>()
    private val lastTap = LongArray(Engine.PADS)
    private var clearMode = false
    private var time = 0f
    private var lastFrameNanos = 0L

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    private class Shock(val rect: RectF, var t: Float, val color: Int)

    private val particles = ArrayList<Particle>()
    private val shocks = ArrayList<Shock>()

    private val hsv = FloatArray(3)
    private val pos = FloatArray(2)
    private val tmpRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.12f
    }

    private val logo: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.logo)
    private val logoDst = RectF()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        isHapticFeedbackEnabled = true
    }

    // ---------------------------------------------------------------- Layout

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var top: Int
        var bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = i.top
            bottom = i.bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 28) {
                val cut = insets.displayCutout
                if (cut != null) top = max(top, cut.safeInsetTop)
            }
        }
        insetTop = top.toFloat()
        insetBottom = bottom.toFloat()
        layoutPads()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPads()
    }

    private fun layoutPads() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val margin = dp(16f)
        val gap = dp(10f)
        val inner = w - 2 * margin

        // Kopfzeile: Logo | Display | Design-Knopf
        val by = insetTop + dp(8f)
        val topH = dp(64f)
        val lh = dp(44f)
        val lw = logo.width * lh / logo.height
        logoDst.set(margin, by + (topH - lh) / 2f, margin + lw, by + (topH + lh) / 2f)
        val db = dp(36f)
        designBtn.set(w - margin - db, by + (topH - db) / 2f, w - margin, by + (topH + db) / 2f)
        displayRect.set(logoDst.right + dp(12f), by + dp(4f), designBtn.left - dp(10f), by + topH - dp(4f))

        // Drehregler
        val knobTop = by + topH + dp(8f)
        val kh = dp(80f)
        val kw = (inner - 3 * gap) / 4f
        knobSize = min(kw, kh - dp(16f))
        for (k in 0 until 4) {
            val x = margin + k * (kw + gap)
            knobRects[k].set(x, knobTop, x + kw, knobTop + kh)
        }

        // Reihe: ALLE | Effekt-Knopf | CLEAR
        val rowTop = knobTop + kh + dp(4f)
        val rh = dp(46f)
        val cx = w / 2f
        fxBtn.set(cx - rh / 2f, rowTop, cx + rh / 2f, rowTop + rh)
        val pw = min(dp(104f), (inner - rh) / 2f - dp(14f))
        val ph = dp(32f)
        val py = rowTop + (rh - ph) / 2f
        muteBtn.set(fxBtn.left - dp(16f) - pw, py, fxBtn.left - dp(16f), py + ph)
        clearBtn.set(fxBtn.right + dp(16f), py, fxBtn.right + dp(16f) + pw, py + ph)

        // XY-Fläche und Pads (mit •••-Knopf darunter)
        val contentTop = rowTop + rh + gap
        val bottom = h - insetBottom - dp(36f)
        val avail = bottom - contentTop
        val colGap = dp(10f)
        val rowGap = dp(4f)
        val mb = dp(20f)
        val seqH = dp(84f)
        var cell = min((inner - 3 * colGap) / 4f, (avail * 0.66f - seqH - gap - 2 * rowGap - 3 * mb) / 3f)
        var gridH = 3 * cell + 3 * mb + 2 * rowGap + gap + seqH
        var xyH = avail - gridH - gap
        val minXy = dp(110f)
        if (xyH < minXy) {
            cell -= (minXy - xyH) / 3f
            gridH = 3 * cell + 3 * mb + 2 * rowGap + gap + seqH
            xyH = minXy
        }
        xyRect.set(margin, contentTop, w - margin, contentTop + xyH)

        val gridTop = xyRect.bottom + gap
        val gridW = 4 * cell + 3 * colGap
        val left = (w - gridW) / 2f
        radius = when (theme) {
            STUDIO -> cell * 0.10f
            HUD -> dp(5f)
            RETRO -> dp(4f)
            COMIC -> cell * 0.26f
            in GLASS..GREY -> cell * SKINS[theme - GLASS].padRadius
            else -> cell * 0.17f
        }
        if (theme == RETRO) {
            // XY in der Mitte, links/rechts Spektrum-Anzeigen
            val lab = dp(16f)
            val hh = xyRect.height() - lab
            val xw = min(hh, inner * 0.40f)
            val cxm = w / 2f
            val side = (inner - xw - 2 * gap) / 2f
            specL.set(margin, xyRect.top, margin + side, xyRect.top + hh)
            specR.set(w - margin - side, xyRect.top, w - margin, xyRect.top + hh)
            xyRect.set(cxm - xw / 2f, xyRect.top, cxm + xw / 2f, xyRect.top + hh)
        } else {
            specL.setEmpty()
            specR.setEmpty()
        }
        for (i in 0 until Engine.PADS) {
            if (i >= VISIBLE_PADS) {
                padRects[i].setEmpty()
                moreRects[i].setEmpty()
                keyRects[i].setEmpty()
                continue
            }
            val row = i / 4
            val col = i % 4
            val x = left + col * (cell + colGap)
            val y = gridTop + row * (cell + mb + rowGap)
            padRects[i].set(x, y, x + cell, y + cell)
            moreRects[i].set(x + cell * 0.06f, y + cell + dp(3f), x + cell * 0.47f, y + cell + mb - dp(1f))
            keyRects[i].set(x + cell * 0.53f, y + cell + dp(3f), x + cell * 0.94f, y + cell + mb - dp(1f))
            val path = Path()
            path.addRoundRect(padRects[i], radius, radius, Path.Direction.CW)
            padMeasures[i].setPath(path, true)
            padLengths[i] = padMeasures[i].length
        }
        // Sequenzer an Stelle der unteren Pad-Reihe
        val seqTop = gridTop + 3 * (cell + mb + rowGap) - rowGap + gap
        seqRect.set(left, seqTop, left + gridW, seqTop + seqH)
        val sp = dp(8f)
        val stripH = dp(30f)
        val sgap = dp(3f)
        val groupGap = dp(5f)
        val stepW = (seqRect.width() - 2 * sp - 15 * sgap - 3 * groupGap) / 16f
        var sx = seqRect.left + sp
        for (k in 0 until Engine.STEPS) {
            stepRects[k].set(sx, seqRect.top + sp, sx + stepW, seqRect.top + sp + stripH)
            sx += stepW + sgap + if (k % 4 == 3) groupGap else 0f
        }
        val bTop = seqRect.top + sp + stripH + dp(6f)
        val bH = seqRect.bottom - sp - bTop
        val bW = min(dp(52f), (seqRect.width() - 2 * sp) / 6f)
        recBtn.set(seqRect.left + sp, bTop, seqRect.left + sp + bW, bTop + bH)
        playBtn.set(recBtn.right + dp(8f), bTop, recBtn.right + dp(8f) + bW, bTop + bH)
        stopBtn.set(playBtn.right + dp(8f), bTop, playBtn.right + dp(8f) + bW, bTop + bH)
        buildHudLines(w, h)
        buildLightCaches()
        buildSkinCaches()
    }

    /** Dekorative Leiterbahnen für das HUD-Design. */
    private val hudLines = Path()
    private val hudAccents = Path()

    private fun buildHudLines(w: Float, h: Float) {
        hudLines.reset()
        hudAccents.reset()
        val m = dp(6f)
        val top = xyRect.top - dp(14f)
        val bot = h - insetBottom - dp(30f)
        // linke Seite
        hudLines.moveTo(m, bot)
        hudLines.lineTo(m, top + dp(30f))
        hudLines.lineTo(m + dp(10f), top + dp(20f))
        hudLines.lineTo(xyRect.left + dp(40f), top + dp(20f))
        hudLines.lineTo(xyRect.left + dp(52f), top + dp(8f))
        hudLines.lineTo(fxBtn.left - dp(4f), top + dp(8f))
        // rechte Seite
        hudLines.moveTo(w - m, bot)
        hudLines.lineTo(w - m, top + dp(30f))
        hudLines.lineTo(w - m - dp(10f), top + dp(20f))
        hudLines.lineTo(xyRect.right - dp(40f), top + dp(20f))
        hudLines.lineTo(xyRect.right - dp(52f), top + dp(8f))
        hudLines.lineTo(fxBtn.right + dp(4f), top + dp(8f))
        // Akzente
        hudAccents.moveTo(m, top + dp(60f)); hudAccents.lineTo(m, top + dp(110f))
        hudAccents.moveTo(w - m, bot - dp(120f)); hudAccents.lineTo(w - m, bot - dp(60f))
        hudAccents.moveTo(xyRect.right - dp(90f), top + dp(20f)); hudAccents.lineTo(xyRect.right - dp(50f), top + dp(20f))
    }

    // ---------------------------------------------------------------- Farben

    private fun neon(h: Float, s: Float = 1f, v: Float = 1f): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f
        hsv[1] = s
        hsv[2] = v
        return Color.HSVToColor(hsv)
    }

    private fun alpha(c: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private fun hue(i: Int) = i * 22.5f + time * 14f

    // ---------------------------------------------------------------- Regenbogen-Verläufe

    private val shaderMatrix = Matrix()
    private val rainbowCols = IntArray(7)
    private val warmCols = intArrayOf(
        Color.rgb(255, 40, 90), Color.rgb(255, 95, 0), Color.rgb(255, 200, 0),
        Color.rgb(255, 60, 160), Color.rgb(255, 40, 90)
    )

    private fun rainbow(sat: Float, value: Float): IntArray {
        for (k in 0 until 7) rainbowCols[k] = neon(k * 60f, sat, value)
        return rainbowCols
    }

    /** Ringsum laufender Regenbogen (oder Feuer-Verlauf für ONE SHOT). */
    private fun sweep(cx: Float, cy: Float, rot: Float, warm: Boolean, sat: Float = 1f, value: Float = 1f): Shader {
        val cols = if (warm) warmCols else rainbow(sat, value)
        val sh = SweepGradient(cx, cy, cols, null)
        shaderMatrix.setRotate(rot % 360f, cx, cy)
        sh.setLocalMatrix(shaderMatrix)
        return sh
    }

    /** Waagerecht wandernder Regenbogen für Knöpfe. */
    private fun stripe(r: RectF, warm: Boolean = false): Shader {
        val cols = if (warm) warmCols else rainbow(1f, 1f)
        val sh = LinearGradient(r.left, 0f, r.left + r.width() * 1.5f, 0f, cols, null, Shader.TileMode.MIRROR)
        shaderMatrix.setTranslate((time * dp(60f)) % (r.width() * 3f), 0f)
        sh.setLocalMatrix(shaderMatrix)
        return sh
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        time += dt

        drawBackground(canvas)
        drawTopBar(canvas)
        drawKnobs(canvas)
        drawFxButton(canvas, dt)
        if (Engine.motionState == Engine.MOT_PLAY && xyPointer < 0) {
            xyDispX = Engine.motCurX
            xyDispY = Engine.motCurY
        }
        drawXy(canvas, dt)
        drawSlotChips(canvas)
        drawHoldButton(canvas)
        drawMotionButton(canvas)
        for (i in 0 until VISIBLE_PADS) {
            drawPad(canvas, i, dt)
            drawMore(canvas, i)
            drawKeyButton(canvas, i)
            drawInputBadge(canvas, i)
            drawFxBadge(canvas, i)
        }
        drawSequencer(canvas)
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        drawFooter(canvas)
        if (kbPad >= 0) drawKeyboard(canvas)
        if (edPad >= 0) drawEditor(canvas, dt)
        message?.let { drawMessage(canvas, it) }

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas) {
        if (skinned) { drawBackgroundSkin(canvas); return }
        if (light) { drawBackgroundLight(canvas); return }
        val w = width.toFloat()
        val h = height.toFloat()
        val lvl = min(1f, Engine.outputLevel * 1.5f)
        when (theme) {
            NEON -> {
                canvas.drawColor(Color.rgb(13, 14, 18))
                val r = max(w, h) * 0.75f
                val cx1 = w * (0.5f + 0.3f * sin(time * 0.31f))
                val cy1 = h * (0.5f + 0.25f * cos(time * 0.23f))
                bgPaint.shader = RadialGradient(cx1, cy1, r, alpha(neon(time * 25f), 0.10f + 0.30f * lvl), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, bgPaint)
                val cx2 = w * (0.5f + 0.35f * cos(time * 0.27f))
                val cy2 = h * (0.5f + 0.3f * sin(time * 0.19f))
                bgPaint.shader = RadialGradient(cx2, cy2, r * 0.8f, alpha(neon(time * 25f + 160f), 0.08f + 0.25f * lvl), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
            GLOW -> {
                canvas.drawColor(Color.rgb(15, 16, 19))
                bgPaint.shader = RadialGradient(w / 2f, xyRect.centerY(), max(w, h) * 0.6f, alpha(ORANGE, 0.05f + 0.12f * lvl), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
            STUDIO -> {
                bgPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(27, 29, 32), Color.rgb(20, 21, 24), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
            else -> {
                bgPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(16, 20, 27), Color.rgb(11, 14, 19), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, bgPaint)
                bgPaint.shader = null
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = alpha(Color.rgb(120, 140, 170), 0.16f)
                canvas.drawPath(hudLines, strokePaint)
                strokePaint.strokeWidth = dp(1.6f)
                strokePaint.color = alpha(ORANGE, 0.45f + 0.3f * lvl)
                canvas.drawPath(hudAccents, strokePaint)
            }
        }
        bgPaint.shader = null
    }

    private fun drawTopBar(canvas: Canvas) {
        if (skinned) { drawTopBarSkin(canvas); return }
        if (light) { drawTopBarLight(canvas); return }
        // Kopfzeilen-Hintergrund (wie in den Entwürfen)
        if (theme != NEON) {
            fillPaint.shader = null
            fillPaint.color = when (theme) {
                STUDIO -> Color.rgb(33, 35, 39)
                HUD -> Color.rgb(18, 23, 31)
                else -> Color.rgb(22, 24, 29)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), displayRect.bottom + dp(8f), fillPaint)
            strokePaint.strokeWidth = dp(1f)
            strokePaint.color = alpha(if (theme == HUD) ORANGE else Color.WHITE, if (theme == HUD) 0.35f else 0.06f)
            val yb = displayRect.bottom + dp(8f)
            canvas.drawLine(0f, yb, width.toFloat(), yb, strokePaint)
        }
        canvas.drawBitmap(logo, null, logoDst, logoPaint)
        drawDisplay(canvas)
        drawDesignButton(canvas)

        val blink = 0.5f + 0.5f * sin(time * 10f)
        drawButton(canvas, clearBtn, "CLEAR", clearMode, blink)
        val muteLabel = if (Engine.anyPlaying()) "ALLE STUMM" else "ALLE AN"
        drawButton(canvas, muteBtn, muteLabel, false, 0f)
    }

    /** Kleines Display: aktueller Effekt, X/Y-Werte, Takt. */
    private fun drawDisplay(canvas: Canvas) {
        val r = displayRect
        if (r.width() <= dp(40f)) return
        if (skinned) { drawDisplaySkin(canvas); return }
        if (light) { drawDisplayLight(canvas); return }
        val rr = dp(8f)
        val type = Engine.fxType
        val lcdBg: Int
        val txt: Int
        when (theme) {
            NEON -> { lcdBg = Color.rgb(6, 6, 12); txt = neon(Fx.HUES[type] + 15f * sin(time)) }
            GLOW -> { lcdBg = Color.rgb(24, 12, 4); txt = Color.rgb(255, 140, 50) }
            STUDIO -> { lcdBg = Color.rgb(20, 22, 25); txt = Color.rgb(255, 120, 30) }
            else -> { lcdBg = Color.rgb(8, 12, 18); txt = Color.rgb(255, 130, 40) }
        }
        fillPaint.shader = null
        fillPaint.color = lcdBg
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        // Rahmen
        if (theme == NEON) strokePaint.shader = sweep(r.centerX(), r.centerY(), time * 60f, false)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (theme == NEON) Color.WHITE else alpha(ORANGE, 0.7f + 0.3f * fxFlash)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.shader = null

        canvas.save()
        canvas.clipRect(r.left + dp(2f), r.top + dp(2f), r.right - dp(2f), r.bottom - dp(2f))
        // Scanlines
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(txt, 0.05f)
        var yy = r.top + dp(3f)
        while (yy < r.bottom) {
            canvas.drawLine(r.left, yy, r.right, yy, strokePaint)
            yy += dp(3f)
        }
        val pad = dp(8f)
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        textPaint.textAlign = Paint.Align.LEFT
        // Zeile 1: Nummer + Name
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(txt, 0.7f)
        val num = String.format(Locale.GERMANY, "%d·FX %02d/%d %s %s", Engine.editSlot + 1, type + 1, Fx.COUNT, if (touching) "●" else "○", fxTargetLabel())
        canvas.drawText(num, r.left + pad, r.top + dp(13f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(10f)
        textPaint.color = txt
        canvas.drawText(bpmLabel(), r.right - pad, r.top + dp(14f), textPaint)
        textPaint.textSize = dp(8f)
        textPaint.color = alpha(txt, 0.5f)
        canvas.drawText("TEMPO ▸", r.right - pad, r.top + dp(25f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = min(dp(17f), r.height() * 0.32f)
        textPaint.color = txt
        textPaint.setShadowLayer(dp(6f), 0f, 0f, alpha(txt, 0.8f))
        canvas.drawText(Fx.NAMES[type], r.left + pad, r.top + dp(31f), textPaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        // Zeile 3: X/Y
        textPaint.textSize = dp(8.5f)
        textPaint.color = alpha(txt, 0.8f)
        val xy = "X ${Fx.X_LABELS[type]} ${(xyDispX * 100).toInt()}  Y ${Fx.Y_LABELS[type]} ${(xyDispY * 100).toInt()}"
        canvas.drawText(xy, r.left + pad, r.top + dp(43f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        // Taktleiste unten im Display
        val mp = Engine.masterProgress()
        val ly = r.bottom - dp(6f)
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = alpha(txt, 0.15f)
        canvas.drawLine(r.left + pad, ly, r.right - pad, ly, strokePaint)
        if (mp >= 0f) {
            val beat = if (mp < 0.05f) 1f - mp / 0.05f else 0f
            strokePaint.color = alpha(txt, 0.75f + 0.25f * beat)
            val x = r.left + pad + (r.width() - 2 * pad) * mp
            canvas.drawLine(r.left + pad, ly, x, ly, strokePaint)
            dotPaint.color = Color.WHITE
            canvas.drawCircle(x, ly, dp(2.5f) + dp(2.5f) * beat, dotPaint)
        }
        canvas.restore()
    }

    private fun drawDesignButton(canvas: Canvas) {
        if (skinned) { drawDesignButtonSkin(canvas); return }
        if (light) { drawDesignButtonLight(canvas); return }
        val r = designBtn
        val rr = dp(8f)
        fillPaint.shader = null
        fillPaint.color = if (theme == STUDIO) Color.rgb(52, 55, 60) else Color.rgb(20, 21, 26)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        if (theme == NEON) strokePaint.shader = sweep(r.centerX(), r.centerY(), -time * 80f, false)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (theme == NEON) Color.WHITE else ORANGE
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.shader = null
        drawMenuIcon(canvas, r, if (theme == NEON) Color.WHITE else ORANGE)
    }

    /** Menü-Symbol ☰ */
    private fun drawMenuIcon(canvas: Canvas, r: RectF, c: Int) {
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2.4f)
        strokePaint.color = c
        val w2 = r.width() * 0.26f
        for (q in -1..1) {
            val y = r.centerY() + q * r.height() * 0.2f
            canvas.drawLine(r.centerX() - w2, y, r.centerX() + w2, y, strokePaint)
        }
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, active: Boolean, blink: Float) {
        if (r.width() <= 0f) return
        if (skinned) { drawButtonSkin(canvas, r, label, active, blink); return }
        if (light) { drawButtonLight(canvas, r, label, active, blink); return }
        val rr = r.height() / 2f
        fillPaint.shader = null
        val red = Color.rgb(255, 40, 70)
        if (theme == NEON) {
            fillPaint.color = alpha(if (active) red else Color.WHITE, if (active) 0.35f + 0.4f * blink else 0.06f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.shader = stripe(r)
            strokePaint.strokeWidth = dp(7f)
            strokePaint.color = alpha(Color.WHITE, 0.3f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = Color.WHITE
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            strokePaint.shader = null
        } else {
            fillPaint.color = if (active) alpha(red, 0.35f + 0.4f * blink) else when (theme) {
                STUDIO -> Color.rgb(52, 55, 60)
                HUD -> Color.rgb(15, 19, 26)
                else -> Color.rgb(20, 21, 26)
            }
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            val c = if (active) red else ORANGE
            strokePaint.strokeWidth = dp(6f)
            strokePaint.color = alpha(c, if (theme == GLOW) 0.25f else 0.12f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = c
            canvas.drawRoundRect(r, rr, rr, strokePaint)
        }
        textPaint.textSize = dp(11.5f)
        textPaint.color = Color.WHITE
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4f), textPaint)
    }

    /** Leuchtfarbe eines Pads (für Partikel usw.). */
    private fun padAccent(i: Int): Int = if (skinned) (if (Engine.isOneShot(i)) skin.shot else skin.accent) else if (light) lightPadColor(i) else if (theme == NEON) {
        if (Engine.isOneShot(i)) neon(15f + 25f * sin(time * 1.3f + i * 0.9f)) else neon(hue(i))
    } else {
        if (Engine.isOneShot(i)) AMBER else ORANGE
    }

    private fun drawPad(canvas: Canvas, i: Int, dt: Float) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        if (skinned) { drawPadSkin(canvas, i, dt); return }
        if (light) { drawPadLight(canvas, i, dt); return }
        val st = Engine.state(i)
        val h = hue(i)
        flash[i] = max(0f, flash[i] - dt * 2.4f)
        val lvl = min(1f, Engine.levels[i] * 2.2f)
        val inLvl = min(1f, Engine.inputLevel * 3f)
        val pulse = 0.5f + 0.5f * sin(time * 2.2f + i * 0.7f)
        val neonMode = theme == NEON

        val shotMode = Engine.isOneShot(i)
        val warmHue = 15f + 25f * sin(time * 1.3f + i * 0.9f)
        var color = if (neonMode) (if (shotMode) neon(warmHue) else neon(h)) else (if (shotMode) AMBER else ORANGE)
        var sat = 1f
        var value = 1f
        var solid = !neonMode
        var glow = 0f
        var fillA = 0f
        var progress = -1f
        when (st) {
            Engine.PadState.EMPTY -> {
                glow = if (neonMode) 0.18f + 0.12f * pulse else 0.5f + 0.08f * pulse
                fillA = if (neonMode) 0.05f else 0f
            }
            Engine.PadState.ONESHOT -> {
                progress = Engine.shotProgress(i)
                if (progress >= 0f) {
                    glow = 0.8f + 0.4f * lvl
                    fillA = 0.2f + 0.6f * lvl
                } else {
                    glow = if (neonMode) 0.38f + 0.12f * pulse else 0.75f
                    fillA = 0.08f
                }
            }
            Engine.PadState.PLAYING -> {
                progress = Engine.progress(i)
                if (progress < 0.06f) flash[i] = max(flash[i], 0.45f * (1f - progress / 0.06f))
                glow = if (neonMode) 0.55f + 0.45f * lvl else 0.85f + 0.4f * lvl
                fillA = 0.16f + 0.55f * lvl
            }
            Engine.PadState.MUTED -> {
                progress = Engine.progress(i)
                color = if (neonMode) neon(h, 0.35f, 0.55f) else MUTE
                sat = 0.3f
                value = 0.55f
                glow = if (neonMode) 0.14f else 0.45f
                fillA = 0.03f
            }
            Engine.PadState.RECORDING -> {
                val blink = 0.5f + 0.5f * sin(time * 14f)
                color = neon(345f + 25f * blink)
                solid = true
                glow = 0.85f + 0.25f * inLvl
                fillA = 0.25f + 0.55f * inLvl
            }
            Engine.PadState.OVERDUB -> {
                progress = Engine.progress(i)
                color = blend(if (neonMode) neon(h) else ORANGE, Color.WHITE, 0.35f + 0.3f * sin(time * 22f))
                glow = 1.1f
                fillA = 0.35f + 0.45f * inLvl
            }
        }
        glow = min(1.3f, glow + flash[i])
        fillA = min(1f, fillA + flash[i] * 0.6f)

        // Grundfläche je Design
        if (!neonMode) {
            fillPaint.shader = when (theme) {
                STUDIO -> LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(68, 71, 77), Color.rgb(52, 55, 60), Shader.TileMode.CLAMP)
                HUD -> LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(17, 21, 29), Color.rgb(13, 16, 22), Shader.TileMode.CLAMP)
                else -> LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(24, 25, 30), Color.rgb(17, 18, 22), Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(r, radius, radius, fillPaint)
        }
        // Innenleuchten
        fillPaint.shader = RadialGradient(
            r.centerX(), r.centerY(), r.width() * 0.8f,
            alpha(color, fillA), alpha(color, fillA * 0.12f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null

        // Leuchtrand
        val widths = when (theme) {
            NEON -> NEON_WIDTHS
            GLOW -> GLOW_WIDTHS
            STUDIO -> STUDIO_WIDTHS
            else -> HUD_WIDTHS
        }
        val alphas = when (theme) {
            NEON -> NEON_ALPHAS
            GLOW -> GLOW_ALPHAS
            STUDIO -> STUDIO_ALPHAS
            else -> HUD_ALPHAS
        }
        // NEON: LOOP = voller Regenbogen, ONE SHOT = Feuer-Verlauf; sonst Akzentfarbe
        strokePaint.shader = if (solid) null else sweep(r.centerX(), r.centerY(), time * 70f + i * 22.5f, shotMode, sat, value)
        for (k in widths.indices) {
            strokePaint.strokeWidth = dp(widths[k])
            strokePaint.color = if (solid) alpha(color, glow * alphas[k]) else alpha(Color.WHITE, glow * alphas[k])
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }
        strokePaint.shader = null
        if (neonMode && glow > 0.5f) {
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = alpha(Color.WHITE, (glow - 0.5f) * 1.2f)
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }
        // HUD: Eck-Klammern bei aktiven Pads
        if (theme == HUD && st != Engine.PadState.EMPTY) {
            strokePaint.strokeWidth = dp(1.6f)
            strokePaint.color = alpha(color, min(1f, glow))
            val o = dp(4f)
            val l = dp(9f)
            canvas.drawLine(r.left - o, r.top - o, r.left - o + l, r.top - o, strokePaint)
            canvas.drawLine(r.left - o, r.top - o, r.left - o, r.top - o + l, strokePaint)
            canvas.drawLine(r.right + o, r.bottom + o, r.right + o - l, r.bottom + o, strokePaint)
            canvas.drawLine(r.right + o, r.bottom + o, r.right + o, r.bottom + o - l, strokePaint)
        }

        // Kometen-Lauflicht = Abspielposition
        if (progress >= 0f && padLengths[i] > 0f) {
            val len = padLengths[i]
            val faded = st == Engine.PadState.MUTED
            for (j in 0 until 14) {
                var p = progress - j * 0.011f
                if (p < 0f) {
                    if (shotMode) break
                    p += 1f
                }
                padMeasures[i].getPosTan(p * len, pos, null)
                val k = 1f - j / 14f
                val dc = if (shotMode || !neonMode) color else neon(h + j * 18f)
                dotPaint.color = alpha(if (j == 0) Color.WHITE else dc, k * (if (faded) 0.35f else 1f))
                canvas.drawCircle(pos[0], pos[1], dp(5f) * k + dp(1f), dotPaint)
            }
        }

        // Aufnahme-Ring
        if (st == Engine.PadState.RECORDING) {
            val secs = Engine.recordingSeconds(i)
            val m = r.width() * 0.22f
            tmpRect.set(r.left + m, r.top + m, r.right - m, r.bottom - m)
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(Color.WHITE, 0.25f)
            canvas.drawOval(tmpRect, strokePaint)
            strokePaint.color = Color.WHITE
            canvas.drawArc(tmpRect, -90f, (secs % 1f) * 360f, false, strokePaint)
        }

        // Clear-Modus: Pads mit Loop blinken rot
        if (clearMode && Engine.hasLoop(i)) {
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = alpha(Color.rgb(255, 40, 70), 0.5f + 0.5f * sin(time * 12f))
            val m = dp(6f)
            canvas.drawLine(r.left + m * 3, r.top + m * 3, r.right - m * 3, r.bottom - m * 3, strokePaint)
            canvas.drawLine(r.right - m * 3, r.top + m * 3, r.left + m * 3, r.bottom - m * 3, strokePaint)
        }

        // Beschriftung
        val label = when (st) {
            Engine.PadState.EMPTY -> if (shotMode) "⚡" else ""
            Engine.PadState.ONESHOT -> String.format(Locale.GERMANY, "⚡ %.1fs", Engine.loopSeconds(i))
            Engine.PadState.PLAYING -> String.format(Locale.GERMANY, "∞ %.1fs", Engine.loopSeconds(i))
            Engine.PadState.MUTED -> "AUS"
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
            Engine.PadState.OVERDUB -> "DUB"
        }
        if (label.isNotEmpty()) {
            textPaint.textSize = r.width() * 0.13f
            textPaint.color = alpha(Color.WHITE, if (st == Engine.PadState.MUTED) 0.45f else 0.9f)
            canvas.drawText(label, r.centerX(), r.bottom - r.height() * 0.12f, textPaint)
        }
    }

    private fun drawMore(canvas: Canvas, i: Int) {
        val r = moreRects[i]
        if (r.width() <= 0f) return
        if (skinned) { drawMoreSkin(canvas, i); return }
        if (light) { drawMoreLight(canvas, i); return }
        val has = Engine.hasLoop(i)
        val shot = Engine.isOneShot(i)
        val rr = r.height() / 2f
        if (theme == NEON) {
            fillPaint.shader = stripe(r, shot)
            fillPaint.color = alpha(Color.WHITE, if (has) 0.22f else 0.08f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            fillPaint.shader = null
            strokePaint.shader = stripe(r, shot)
            strokePaint.strokeWidth = dp(1.4f)
            strokePaint.color = alpha(Color.WHITE, if (has) 1f else 0.45f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            strokePaint.shader = null
            dotPaint.color = alpha(Color.WHITE, if (has) 1f else 0.6f)
        } else {
            val c = if (!has) Color.rgb(120, 124, 132) else if (shot) AMBER else ORANGE
            fillPaint.shader = null
            fillPaint.color = alpha(c, if (has) 0.16f else 0.06f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = alpha(c, if (has) 0.9f else 0.45f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            dotPaint.color = alpha(if (has) c else Color.WHITE, if (has) 1f else 0.5f)
        }
        val d = dp(5f)
        for (k in -1..1) canvas.drawCircle(r.centerX() + k * d, r.centerY(), dp(1.6f), dotPaint)
    }

    // ---------------------------------------------------------------- Sequenzer

    private fun drawSequencer(canvas: Canvas) {
        val r = seqRect
        if (r.width() <= 0f) return
        if (skinned) { drawSequencerSkin(canvas); return }
        if (light) { drawSequencerLight(canvas); return }
        val neonMode = theme == NEON
        val acc = if (neonMode) neon(time * 30f) else ORANGE
        val rr = if (theme == HUD) dp(6f) else dp(12f)
        // Panel
        fillPaint.shader = null
        fillPaint.color = when (theme) {
            NEON -> alpha(Color.BLACK, 0.45f)
            STUDIO -> Color.rgb(40, 43, 47)
            HUD -> Color.rgb(13, 17, 23)
            else -> Color.rgb(18, 19, 23)
        }
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        if (neonMode) strokePaint.shader = sweep(r.centerX(), r.centerY(), time * 40f, false)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (neonMode) alpha(Color.WHITE, 0.8f) else alpha(ORANGE, 0.75f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.shader = null

        // Schritte
        val cur = Engine.seqCurrentStep()
        val sel = Engine.seqSelected
        for (k in 0 until Engine.STEPS) {
            val s = stepRects[k]
            val sc = if (neonMode) neon(k * 22.5f + time * 20f) else if (k % 4 == 0) AMBER else ORANGE
            val on = sel >= 0 && Engine.seqHas(sel, k)
            val count = Engine.seqCount(k)
            val playing = k == cur
            fillPaint.color = when {
                on -> alpha(alpha(sc, if (playing) 1f else 0.9f), 0.28f)
                playing -> alpha(Color.WHITE, 0.35f)
                else -> alpha(Color.WHITE, if (k % 4 == 0) 0.10f else 0.05f)
            }
            canvas.drawRoundRect(s, dp(3f), dp(3f), fillPaint)
            if (on) {
                fillPaint.color = alpha(sc, if (playing) 1f else 0.9f)
                tmpRect.set(s.left, s.bottom - s.height() * Engine.seqVel(sel, k).coerceIn(0.08f, 1f), s.right, s.bottom)
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            }
            if (sel >= 0 && Engine.noteAt(sel, k)) {
                dotPaint.color = if (light) Color.BLACK else Color.WHITE
                canvas.drawCircle(s.centerX(), s.top + dp(5f), dp(2.6f), dotPaint)
            }
            strokePaint.strokeWidth = dp(if (playing) 2f else 1f)
            strokePaint.color = if (playing) Color.WHITE else alpha(sc, 0.5f)
            canvas.drawRoundRect(s, dp(3f), dp(3f), strokePaint)
            // Treffer anderer Pads als Punkte
            val others = count - (if (on) 1 else 0)
            if (others > 0) {
                dotPaint.color = alpha(Color.WHITE, 0.85f)
                for (d in 0 until min(others, 3)) canvas.drawCircle(s.centerX(), s.bottom - dp(4f) - d * dp(5f), dp(1.6f), dotPaint)
            }
        }

        // Transport-Knöpfe
        val blink = 0.5f + 0.5f * sin(time * 8f)
        val red = Color.rgb(255, 45, 70)
        val green = Color.rgb(60, 230, 120)
        transportButton(canvas, recBtn, Engine.seqRecording || Engine.songRecording, red, blink)
        transportButton(canvas, playBtn, Engine.seqPlaying, if (neonMode) green else ORANGE, 1f)
        transportButton(canvas, stopBtn, false, Color.WHITE, 0f)
        // Symbole
        val ic = min(recBtn.height(), recBtn.width()) * 0.22f
        dotPaint.color = if (Engine.seqRecording || Engine.songRecording) Color.WHITE else red
        canvas.drawCircle(recBtn.centerX(), recBtn.centerY(), ic, dotPaint)
        val tri = Path()
        tri.moveTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() - ic)
        tri.lineTo(playBtn.centerX() + ic * 1.1f, playBtn.centerY())
        tri.lineTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() + ic)
        tri.close()
        fillPaint.color = if (Engine.seqPlaying) Color.WHITE else (if (neonMode) green else ORANGE)
        canvas.drawPath(tri, fillPaint)
        fillPaint.color = alpha(Color.WHITE, 0.9f)
        canvas.drawRect(stopBtn.centerX() - ic * 0.9f, stopBtn.centerY() - ic * 0.9f, stopBtn.centerX() + ic * 0.9f, stopBtn.centerY() + ic * 0.9f, fillPaint)

        // Info rechts
        val ix = stopBtn.right + dp(10f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(11f)
        textPaint.color = acc
        val head = when {
            Engine.songRecording -> {
                val t = Engine.songSeconds().toInt()
                String.format(Locale.GERMANY, "● SONG %d:%02d", t / 60, t % 60)
            }
            Engine.seqRecording -> "● REC PATTERN"
            Engine.seqPlaying -> "▶ SEQ"
            else -> "SEQUENZER"
        }
        canvas.drawText(head, ix, recBtn.top + dp(13f), textPaint)
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(Color.WHITE, 0.65f)
        val sub = if (sel >= 0) "Pad ${sel + 1}: tippen · ↕ Lautst." else "⚡-Pad antippen"
        canvas.drawText(sub, ix, recBtn.top + dp(27f), textPaint)
        canvas.drawText("STOP halten = leeren", ix, recBtn.top + dp(40f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun transportButton(canvas: Canvas, r: RectF, active: Boolean, c: Int, blink: Float) {
        val rr = dp(8f)
        fillPaint.shader = null
        fillPaint.color = if (active) alpha(c, 0.35f + 0.35f * blink) else when (theme) {
            STUDIO -> Color.rgb(58, 61, 67)
            HUD -> Color.rgb(17, 22, 30)
            NEON -> alpha(Color.WHITE, 0.06f)
            else -> Color.rgb(26, 27, 33)
        }
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(5f)
        strokePaint.color = alpha(c, if (active) 0.35f else 0.1f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = alpha(c, if (active) 1f else 0.7f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
    }

    private fun drawShocks(canvas: Canvas, dt: Float) {
        val it = shocks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.t += dt * 1.8f
            if (s.t >= 1f) {
                it.remove()
                continue
            }
            val grow = dp(46f) * s.t
            tmpRect.set(s.rect.left - grow, s.rect.top - grow, s.rect.right + grow, s.rect.bottom + grow)
            val rr = radius + grow
            strokePaint.strokeWidth = dp(10f) * (1f - s.t)
            strokePaint.color = alpha(s.color, 0.35f * (1f - s.t))
            canvas.drawRoundRect(tmpRect, rr, rr, strokePaint)
            strokePaint.strokeWidth = dp(3f) * (1f - s.t) + dp(0.5f)
            strokePaint.color = alpha(s.color, 1f - s.t)
            canvas.drawRoundRect(tmpRect, rr, rr, strokePaint)
        }
    }

    private fun drawParticles(canvas: Canvas, dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.95f
            p.vy = p.vy * 0.95f + dp(60f) * dt
            val k = p.life / p.maxLife
            dotPaint.color = alpha(p.color, k)
            canvas.drawCircle(p.x, p.y, p.size * (0.4f + 0.6f * k), dotPaint)
        }
    }

    private val srcPill = RectF()
    private val modePill = RectF()

    private fun drawFooter(canvas: Canvas) {
        val w = width.toFloat()
        val top = height - insetBottom - dp(33f)
        val ph = dp(22f)
        val pw = min(dp(160f), (w - dp(48f)) / 2f)
        srcPill.set(w / 2f - dp(6f) - pw, top, w / 2f - dp(6f), top + ph)
        modePill.set(w / 2f + dp(6f), top, w / 2f + dp(6f) + pw, top + ph)
        if (clearMode) {
            textPaint.textSize = dp(11f)
            textPaint.color = Color.rgb(255, 80, 100)
            canvas.drawText("Tippe auf ein Pad, um es zu löschen", w / 2f, top + dp(15f), textPaint)
        } else {
            val cols = overlayColors()
            val sum = Engine.sourceSummary(VISIBLE_PADS)
            val live = Engine.liveBars > 0
            footerPill(canvas, srcPill, when (sum) { 1 -> "🔁 ALLE WAVE"; 2 -> "🎙/🔁 GEMISCHT"; else -> "🎙 ALLE MIKRO" }, sum != 0, cols)
            footerPill(canvas, modePill, if (live) "⚡ LIVE ${Engine.liveBars} TAKT" + (if (Engine.liveBars > 1) "E" else "") else "✋ HALTEN", live, cols)
        }
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(BRAND_ORANGE, 0.8f)
        canvas.drawText("© marween", w / 2f, height - insetBottom - dp(2f), textPaint)
    }

    /** Zeigt am Pad, ob es durch den Effekt läuft (FX) oder trocken bleibt (durchgestrichen). */
    private fun drawFxBadge(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val on = Engine.padFx[i]
        val all = (0 until VISIBLE_PADS).all { Engine.padFx[it] }
        if (on && all) return
        val cols = overlayColors()
        val h = min(dp(16f), r.width() * 0.18f)
        val w = h * 1.7f
        tmpRect.set(r.right - w - dp(5f), r.bottom - h - dp(5f), r.right - dp(5f), r.bottom - dp(5f))
        val rr = h / 2f
        fillPaint.shader = null
        fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.8f)
        canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = if (on) Color.WHITE else alpha(cols[1], 0.5f)
        canvas.drawRoundRect(tmpRect, rr, rr, strokePaint)
        textPaint.textSize = h * 0.62f
        textPaint.color = if (on) Color.WHITE else alpha(cols[1], 0.7f)
        canvas.drawText("FX", tmpRect.centerX(), tmpRect.centerY() + h * 0.22f, textPaint)
        if (!on) {
            strokePaint.strokeWidth = dp(1.4f)
            strokePaint.color = alpha(cols[1], 0.8f)
            canvas.drawLine(tmpRect.left + dp(3f), tmpRect.bottom - dp(3f), tmpRect.right - dp(3f), tmpRect.top + dp(3f), strokePaint)
        }
    }

    /** Kleines Eingangs-Symbol oben links am Pad (🎙 Mikro / 〰 Wave). */
    private fun drawInputBadge(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val wave = Engine.padSource[i] == 1
        val cols = overlayColors()
        val sz = min(dp(18f), r.width() * 0.2f)
        val cx = r.left + sz * 0.5f + dp(5f)
        val cy = r.bottom - sz * 0.5f - dp(5f)
        if (!wave && Engine.hasLoop(i)) return
        fillPaint.shader = null
        fillPaint.color = if (wave) cols[2] else alpha(cols[0], 0.7f)
        canvas.drawCircle(cx, cy, sz * 0.5f, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = if (wave) Color.WHITE else alpha(cols[1], 0.5f)
        canvas.drawCircle(cx, cy, sz * 0.5f, strokePaint)
        val ic = if (wave) Color.WHITE else alpha(cols[1], 0.8f)
        strokePaint.color = ic
        strokePaint.strokeCap = Paint.Cap.ROUND
        if (wave) {
            // kleine Welle
            strokePaint.strokeWidth = dp(1.5f)
            val pth = Path()
            val w2 = sz * 0.32f
            pth.moveTo(cx - w2, cy)
            pth.cubicTo(cx - w2 * 0.5f, cy - sz * 0.3f, cx - w2 * 0.1f, cy - sz * 0.3f, cx, cy)
            pth.cubicTo(cx + w2 * 0.1f, cy + sz * 0.3f, cx + w2 * 0.5f, cy + sz * 0.3f, cx + w2, cy)
            canvas.drawPath(pth, strokePaint)
        } else {
            // kleines Mikrofon
            strokePaint.strokeWidth = dp(1.3f)
            val mw = sz * 0.14f
            tmpRect.set(cx - mw, cy - sz * 0.28f, cx + mw, cy + sz * 0.04f)
            canvas.drawRoundRect(tmpRect, mw, mw, strokePaint)
            tmpRect.set(cx - mw * 1.9f, cy - sz * 0.12f, cx + mw * 1.9f, cy + sz * 0.16f)
            canvas.drawArc(tmpRect, 0f, 180f, false, strokePaint)
            canvas.drawLine(cx, cy + sz * 0.16f, cx, cy + sz * 0.26f, strokePaint)
        }
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun footerPill(canvas: Canvas, r: RectF, label: String, on: Boolean, cols: IntArray) {
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.85f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = if (on) Color.WHITE else alpha(cols[1], 0.5f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(10.5f)
        textPaint.color = if (on) Color.WHITE else cols[1]
        canvas.drawText(label, r.centerX(), r.centerY() + dp(3.8f), textPaint)
    }

    // ---------------------------------------------------------------- Regler, Effekt-Knopf, XY-Fläche

    private fun knobLabel(k: Int): String = when (k) {
        0 -> "MIX"
        1 -> Fx.P1_LABELS[Engine.fxType]
        2 -> Fx.P2_LABELS[Engine.fxType]
        else -> "VOL"
    }

    private fun drawKnobs(canvas: Canvas) {
        for (k in 0 until 4) {
            val r = knobRects[k]
            if (r.width() <= 0f) continue
            val v = Engine.knobs[k].coerceIn(0f, 1f)
            val active = pointerKnob.containsValue(k)
            val cx = r.centerX()
            val cy = r.top + knobSize / 2f + dp(2f)
            val rad = knobSize * 0.42f
            val ang = Math.toRadians((135.0 + 270.0 * v)).toFloat()
            when (theme) {
                NEON -> knobNeon(canvas, k, cx, cy, rad, v, ang, active)
                GLOW -> knobGlow(canvas, cx, cy, rad, v, ang, active)
                STUDIO -> knobStudio(canvas, cx, cy, rad, v, ang, active)
                HUD -> knobHud(canvas, cx, cy, rad, v, ang, active)
                RETRO -> knobRetro(canvas, cx, cy, rad, v, ang, active)
                COMIC -> knobComic(canvas, k, cx, cy, rad, v, ang, active)
                else -> knobSkin(canvas, cx, cy, rad, v, ang, active)
            }
            // Beschriftung
            textPaint.textSize = dp(10f)
            textPaint.color = ink(if (active) 1f else 0.75f)
            canvas.drawText(knobLabel(k), cx, r.bottom - dp(1f), textPaint)
            val step = Fx.stepLabel(Engine.fxType, k, v)
            if (active || step != null) {
                textPaint.textSize = dp(if (step != null && step.length > 3) 9f else 11f)
                textPaint.color = if (skinned) skin.knobText else if (light) Color.BLACK else Color.WHITE
                canvas.drawText(step ?: "${(v * 100).toInt()}", cx, cy + dp(4f), textPaint)
            }
        }
    }

    private fun knobNeon(canvas: Canvas, k: Int, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val c = neon(Fx.HUES[Engine.fxType] + k * 35f + time * 20f)
        tmpRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        fillPaint.shader = RadialGradient(cx, cy - rad * 0.3f, rad, Color.rgb(40, 36, 60), Color.rgb(10, 8, 18), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.78f, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = alpha(Color.WHITE, 0.12f)
        canvas.drawArc(tmpRect, 135f, 270f, false, strokePaint)
        val glow = if (active) 1f else 0.75f
        strokePaint.shader = sweep(cx, cy, 120f + k * 40f + time * 20f, false)
        strokePaint.strokeWidth = dp(11f)
        strokePaint.color = alpha(Color.WHITE, 0.22f * glow)
        canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = alpha(Color.WHITE, glow)
        canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
        strokePaint.shader = null
        strokePaint.strokeCap = Paint.Cap.BUTT
        val px = cx + cos(ang) * rad * 0.62f
        val py = cy + sin(ang) * rad * 0.62f
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = Color.WHITE
        canvas.drawLine(cx + cos(ang) * rad * 0.2f, cy + sin(ang) * rad * 0.2f, px, py, strokePaint)
        dotPaint.color = c
        canvas.drawCircle(px, py, dp(3.5f), dotPaint)
    }

    /** Entwurf 1: dunkler Knopf, orange leuchtender Außenring mit Skala. */
    private fun knobGlow(canvas: Canvas, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val g = if (active) 1f else 0.8f
        // Außenring mit Glühen
        for ((wdt, a) in arrayOf(dp(10f) to 0.12f, dp(5f) to 0.25f, dp(2f) to 1f)) {
            strokePaint.strokeWidth = wdt
            strokePaint.color = alpha(ORANGE, a * g)
            canvas.drawCircle(cx, cy, rad, strokePaint)
        }
        // Skala: 15 Striche über 270°, bis zum Wert hell
        strokePaint.strokeWidth = dp(1.6f)
        for (t in 0..14) {
            val f = t / 14f
            val a = Math.toRadians(135.0 + 270.0 * f).toFloat()
            strokePaint.color = alpha(ORANGE, if (f <= v + 0.001f) 0.95f else 0.3f)
            canvas.drawLine(cx + cos(a) * rad * 0.80f, cy + sin(a) * rad * 0.80f, cx + cos(a) * rad * 0.90f, cy + sin(a) * rad * 0.90f, strokePaint)
        }
        // Knopfkörper
        fillPaint.shader = RadialGradient(cx, cy - rad * 0.35f, rad * 0.75f, Color.rgb(48, 52, 62), Color.rgb(14, 15, 19), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.68f, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(ORANGE, 0.5f)
        canvas.drawCircle(cx, cy, rad * 0.68f, strokePaint)
        // Zeiger
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = ORANGE
        canvas.drawLine(cx + cos(ang) * rad * 0.35f, cy + sin(ang) * rad * 0.35f, cx + cos(ang) * rad * 0.62f, cy + sin(ang) * rad * 0.62f, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    /** Entwurf 2: grauer, geriffelter Studio-Knopf mit dünnem orangem Ring. */
    private fun knobStudio(canvas: Canvas, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val g = if (active) 1f else 0.7f
        strokePaint.strokeWidth = dp(6f)
        strokePaint.color = alpha(ORANGE, 0.15f * g)
        canvas.drawCircle(cx, cy, rad, strokePaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = alpha(ORANGE, g)
        canvas.drawCircle(cx, cy, rad, strokePaint)
        // Wert als Bogen
        tmpRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = ORANGE
        canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
        // Körper
        fillPaint.shader = RadialGradient(cx, cy - rad * 0.4f, rad, Color.rgb(84, 88, 95), Color.rgb(38, 40, 44), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.86f, fillPaint)
        fillPaint.shader = null
        // Riffelung
        strokePaint.strokeWidth = dp(1.1f)
        strokePaint.color = Color.argb(150, 20, 21, 24)
        val rot = Math.toDegrees(ang.toDouble()).toFloat()
        for (t in 0 until 40) {
            val a = Math.toRadians((rot + t * 9f).toDouble()).toFloat()
            canvas.drawLine(cx + cos(a) * rad * 0.70f, cy + sin(a) * rad * 0.70f, cx + cos(a) * rad * 0.85f, cy + sin(a) * rad * 0.85f, strokePaint)
        }
        fillPaint.shader = RadialGradient(cx, cy - rad * 0.3f, rad * 0.7f, Color.rgb(78, 82, 89), Color.rgb(50, 53, 58), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.66f, fillPaint)
        fillPaint.shader = null
        dotPaint.color = ORANGE
        canvas.drawCircle(cx + cos(ang) * rad * 0.5f, cy + sin(ang) * rad * 0.5f, dp(2.6f), dotPaint)
    }

    /** Entwurf 3: HUD-Ring mit Markierung, die über den Rand hinausragt. */
    private fun knobHud(canvas: Canvas, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val g = if (active) 1f else 0.8f
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(Color.rgb(140, 155, 180), 0.25f)
        canvas.drawCircle(cx, cy, rad * 1.08f, strokePaint)
        for ((wdt, a) in arrayOf(dp(7f) to 0.15f, dp(2f) to 1f)) {
            strokePaint.strokeWidth = wdt
            strokePaint.color = alpha(ORANGE, a * g)
            canvas.drawCircle(cx, cy, rad * 0.9f, strokePaint)
        }
        fillPaint.shader = RadialGradient(cx, cy - rad * 0.3f, rad, Color.rgb(30, 34, 42), Color.rgb(10, 12, 16), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.78f, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = ORANGE
        canvas.drawLine(cx + cos(ang) * rad * 0.45f, cy + sin(ang) * rad * 0.45f, cx + cos(ang) * rad * 1.18f, cy + sin(ang) * rad * 1.18f, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawFxButton(canvas: Canvas, dt: Float) {
        val r = fxBtn
        if (r.width() <= 0f) return
        fxFlash = max(0f, fxFlash - dt * 2f)
        if (skinned) { drawFxButtonSkin(canvas); return }
        if (light) { drawFxButtonLight(canvas); return }
        val rr = dp(10f)
        val g = 0.7f + 0.5f * fxFlash + 0.3f * xyGlow
        if (theme == NEON) {
            val c = neon(Fx.HUES[Engine.fxType] + 15f * sin(time * 1.5f))
            fillPaint.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.8f, alpha(c, 0.25f + 0.5f * fxFlash), alpha(c, 0.03f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            fillPaint.shader = null
            strokePaint.shader = sweep(r.centerX(), r.centerY(), time * 90f, false)
            for (k in NEON_WIDTHS.indices) {
                strokePaint.strokeWidth = dp(NEON_WIDTHS[k]) * 0.6f
                strokePaint.color = alpha(Color.WHITE, g * NEON_ALPHAS[k])
                canvas.drawRoundRect(r, rr, rr, strokePaint)
            }
            strokePaint.shader = null
        } else {
            fillPaint.shader = null
            fillPaint.color = when (theme) {
                STUDIO -> Color.rgb(60, 63, 69)
                HUD -> Color.rgb(15, 19, 26)
                else -> Color.rgb(20, 21, 26)
            }
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            fillPaint.color = alpha(ORANGE, 0.1f + 0.4f * fxFlash)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            for ((wdt, a) in arrayOf(dp(9f) to 0.12f, dp(4f) to 0.3f, dp(1.6f) to 1f)) {
                strokePaint.strokeWidth = wdt
                strokePaint.color = alpha(ORANGE, min(1f, a * g))
                canvas.drawRoundRect(r, rr, rr, strokePaint)
            }
        }
        textPaint.textSize = r.width() * 0.3f
        textPaint.color = Color.WHITE
        canvas.drawText(FX_CODES[Engine.fxType], r.centerX(), r.centerY() + r.width() * 0.11f, textPaint)
        textPaint.textSize = dp(8f)
        textPaint.color = alpha(Color.WHITE, 0.6f)
        canvas.drawText("◂  ▸", r.centerX(), r.bottom - dp(5f), textPaint)
    }

    private fun drawXy(canvas: Canvas, dt: Float) {
        val r = xyRect
        if (r.width() <= 0f) return
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        xyGlow += ((if (touching) 1f else 0f) - xyGlow) * min(1f, dt * 10f)
        if (skinned) { drawXySkin(canvas, dt); return }
        if (light) { drawXyLight(canvas, dt); return }
        val neonMode = theme == NEON
        val c = if (neonMode) neon(Fx.HUES[Engine.fxType] + 20f * sin(time * 0.8f)) else ORANGE
        val c2 = if (neonMode) neon(Fx.HUES[Engine.fxType] + 120f + time * 30f) else AMBER
        val rr = if (theme == HUD) dp(10f) else dp(18f)
        val lvl = min(1f, Engine.outputLevel * 1.5f)
        val fx = r.left + r.width() * xyDispX
        val fy = r.bottom - r.height() * xyDispY

        // Fläche
        if (!neonMode) {
            fillPaint.shader = null
            fillPaint.color = when (theme) {
                STUDIO -> Color.rgb(45, 48, 53)
                HUD -> Color.rgb(13, 17, 23)
                else -> Color.rgb(17, 18, 22)
            }
            canvas.drawRoundRect(r, rr, rr, fillPaint)
        }
        val baseA = if (neonMode) 0.10f else if (theme == GLOW) 0.08f else 0.03f
        fillPaint.shader = RadialGradient(
            fx, fy, max(r.width(), r.height()) * (if (neonMode) 0.9f else 0.6f),
            alpha(c, baseA + 0.30f * xyGlow + 0.15f * lvl), alpha(c2, 0f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        fillPaint.shader = null

        canvas.save()
        canvas.clipRect(r)
        // Raster je Design
        when (theme) {
            NEON, GLOW -> {
                strokePaint.strokeWidth = dp(if (theme == GLOW) 1.3f else 1f)
                strokePaint.color = alpha(c, if (theme == GLOW) 0.45f + 0.2f * xyGlow else 0.10f + 0.12f * xyGlow)
                for (k in 1 until 8) {
                    val x = r.left + r.width() * k / 8f
                    canvas.drawLine(x, r.top, x, r.bottom, strokePaint)
                }
                for (k in 1 until 5) {
                    val y = r.top + r.height() * k / 5f
                    canvas.drawLine(r.left, y, r.right, y, strokePaint)
                }
            }
            else -> {
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = alpha(Color.rgb(170, 180, 195), if (theme == STUDIO) 0.12f else 0.08f)
                for (k in 1 until 16) {
                    val x = r.left + r.width() * k / 16f
                    canvas.drawLine(x, r.top, x, r.bottom, strokePaint)
                }
                for (k in 1 until 8) {
                    val y = r.top + r.height() * k / 8f
                    canvas.drawLine(r.left, y, r.right, y, strokePaint)
                }
                if (theme == HUD) {
                    // Fadenkreuz-Marken wie im Entwurf
                    strokePaint.strokeWidth = dp(1.2f)
                    strokePaint.color = alpha(ORANGE, 0.75f)
                    val my = r.centerY()
                    val m = dp(7f)
                    for (q in 1..3) {
                        val mx = r.left + r.width() * q / 4f
                        canvas.drawLine(mx - m, my, mx + m, my, strokePaint)
                        canvas.drawLine(mx, my - m, mx, my + m, strokePaint)
                        canvas.drawLine(mx, r.top, mx, r.top + dp(6f), strokePaint)
                        canvas.drawLine(mx, r.bottom - dp(6f), mx, r.bottom, strokePaint)
                    }
                    strokePaint.color = alpha(ORANGE, 0.25f)
                    strokePaint.strokeWidth = dp(0.8f)
                    canvas.drawLine(r.left, my, r.right, my, strokePaint)
                }
            }
        }
        // Wasserzeichen
        if (neonMode) {
            textPaint.textSize = r.height() * 0.28f
            textPaint.color = alpha(c, 0.06f + 0.06f * xyGlow)
            canvas.drawText(Fx.NAMES[Engine.fxType], r.centerX(), r.centerY() + r.height() * 0.1f, textPaint)
        }

        // Finger / Cursor
        val a = 0.3f + 0.7f * xyGlow
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = alpha(c, 0.5f * a)
        canvas.drawLine(r.left, fy, r.right, fy, strokePaint)
        canvas.drawLine(fx, r.top, fx, r.bottom, strokePaint)
        val pr = dp(12f) + dp(14f) * lvl * xyGlow
        if (theme == GLOW || neonMode) {
            // Funkeln (Entwurf 1)
            dotPaint.color = alpha(c, 0.18f * a)
            canvas.drawCircle(fx, fy, pr * 2.4f, dotPaint)
            dotPaint.color = alpha(c, 0.4f * a)
            canvas.drawCircle(fx, fy, pr * 1.2f, dotPaint)
            fillPaint.shader = LinearGradient(fx - pr * 3f, fy, fx + pr * 3f, fy,
                intArrayOf(Color.TRANSPARENT, alpha(Color.WHITE, 0.9f * a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(fx - pr * 3f, fy - dp(1f), fx + pr * 3f, fy + dp(1f), fillPaint)
            fillPaint.shader = LinearGradient(fx, fy - pr * 3f, fx, fy + pr * 3f,
                intArrayOf(Color.TRANSPARENT, alpha(Color.WHITE, 0.9f * a), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(fx - dp(1f), fy - pr * 3f, fx + dp(1f), fy + pr * 3f, fillPaint)
            fillPaint.shader = null
            dotPaint.color = alpha(Color.WHITE, 0.95f * a)
            canvas.drawCircle(fx, fy, pr * 0.35f, dotPaint)
        } else if (theme == STUDIO) {
            dotPaint.color = alpha(ORANGE, 0.25f * a)
            canvas.drawCircle(fx, fy, pr * 1.6f, dotPaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = alpha(ORANGE, a)
            canvas.drawCircle(fx, fy, pr * 0.9f, strokePaint)
            dotPaint.color = alpha(Color.WHITE, a)
            canvas.drawCircle(fx, fy, dp(3f), dotPaint)
        } else {
            strokePaint.strokeWidth = dp(1.6f)
            strokePaint.color = alpha(ORANGE, a)
            val q = pr * 0.9f
            tmpRect.set(fx - q, fy - q, fx + q, fy + q)
            canvas.drawRect(tmpRect, strokePaint)
            dotPaint.color = alpha(Color.WHITE, a)
            canvas.drawCircle(fx, fy, dp(2.5f), dotPaint)
        }
        if (touching) {
            val ring = (time * 2f) % 1f
            strokePaint.strokeWidth = dp(2f) * (1f - ring)
            strokePaint.color = alpha(c2, 1f - ring)
            canvas.drawCircle(fx, fy, pr + dp(50f) * ring, strokePaint)
            if (Random.nextFloat() < 0.6f) burst(fx, fy, if (Random.nextBoolean()) c else c2, 2, 120f + 300f * lvl)
        }
        canvas.restore()

        // Rahmen
        if (neonMode) {
            strokePaint.shader = sweep(r.centerX(), r.centerY(), -time * 50f, false)
            for (k in NEON_WIDTHS.indices) {
                strokePaint.strokeWidth = dp(NEON_WIDTHS[k]) * 0.8f
                strokePaint.color = alpha(Color.WHITE, (0.45f + 0.55f * xyGlow) * NEON_ALPHAS[k])
                canvas.drawRoundRect(r, rr, rr, strokePaint)
            }
            strokePaint.shader = null
        } else {
            val ws = if (theme == GLOW) GLOW_WIDTHS else if (theme == STUDIO) STUDIO_WIDTHS else HUD_WIDTHS
            val al = if (theme == GLOW) GLOW_ALPHAS else if (theme == STUDIO) STUDIO_ALPHAS else HUD_ALPHAS
            for (k in ws.indices) {
                strokePaint.strokeWidth = dp(ws[k])
                strokePaint.color = alpha(ORANGE, min(1f, (0.75f + 0.4f * xyGlow) * al[k]))
                canvas.drawRoundRect(r, rr, rr, strokePaint)
            }
        }

        // Achsen
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(Color.WHITE, 0.55f)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("X: ${Fx.X_LABELS[Engine.fxType]} →", r.right - dp(12f), r.bottom - dp(10f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ Y: ${Fx.Y_LABELS[Engine.fxType]}", r.left + dp(12f), r.bottom - dp(10f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun setXy(x: Float, y: Float) {
        val r = xyRect
        xyDispX = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        xyDispY = ((r.bottom - y) / r.height()).coerceIn(0f, 1f)
        Engine.fxX = xyDispX
        Engine.fxY = xyDispY
    }

    private fun drawMessage(canvas: Canvas, msg: String) {
        fillPaint.shader = null
        fillPaint.color = alpha(Color.BLACK, 0.8f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        textPaint.textSize = dp(16f)
        textPaint.color = neon(time * 40f)
        val lines = msg.split("\n")
        var y = height / 2f - (lines.size - 1) * dp(12f)
        for (line in lines) {
            canvas.drawText(line, width / 2f, y, textPaint)
            y += dp(24f)
        }
    }

    // ---------------------------------------------------------------- Effekte

    private fun burst(x: Float, y: Float, color: Int, count: Int, speed: Float) {
        if (particles.size > 700) return
        for (n in 0 until count) {
            val a = Random.nextFloat() * 6.2832f
            val v = dp(speed) * (0.3f + Random.nextFloat())
            val life = 0.4f + Random.nextFloat() * 0.6f
            val c = if (Random.nextFloat() < 0.25f) Color.WHITE else color
            particles.add(Particle(x, y, cos(a) * v, sin(a) * v, life, life, c, dp(2f + Random.nextFloat() * 3f)))
        }
    }

    // ---------------------------------------------------------------- Touch

    private fun hitPad(x: Float, y: Float): Int {
        for (i in 0 until VISIBLE_PADS) if (padRects[i].contains(x, y)) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (edPad >= 0) {
            editorTouch(e)
            return true
        }
        if (kbPad >= 0 && keyboardTouch(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                val x = e.getX(idx)
                val y = e.getY(idx)
                if (message != null) {
                    onMessageTap?.invoke()
                    return true
                }
                if (!clearMode && srcPill.contains(x, y)) {
                    Engine.setAllSources(if (Engine.sourceSummary(VISIBLE_PADS) == 0) 1 else 0)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, overlayColors()[2], 12, 160f)
                    onRecSettingsChanged?.invoke()
                    return true
                }
                if (!clearMode && modePill.contains(x, y)) {
                    Engine.liveBars = when (Engine.liveBars) { 0 -> 1; 1 -> 2; 2 -> 4; 4 -> 8; 8 -> 16; else -> 0 }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, overlayColors()[2], 12, 160f)
                    onRecSettingsChanged?.invoke()
                    return true
                }
                for (i in 0 until VISIBLE_PADS) {
                    val kr = keyRects[i]
                    if (x >= kr.left - dp(4f) && x <= kr.right + dp(4f) && y >= kr.top - dp(4f) && y <= kr.bottom + dp(4f)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (Engine.hasLoop(i)) openKeyboard(i) else burst(kr.centerX(), kr.centerY(), Color.GRAY, 6, 100f)
                        return true
                    }
                }
                if (designBtn.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDesignMenu?.invoke()
                    return true
                }
                if (displayRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (x > displayRect.left + displayRect.width() * 0.62f) onTempo?.invoke() else onFxList?.invoke()
                    return true
                }
                if (clearBtn.contains(x, y)) {
                    clearMode = !clearMode
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, Color.rgb(255, 40, 70), 16, 220f)
                    return true
                }
                if (muteBtn.contains(x, y)) {
                    Engine.toggleMuteAll()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, neon(time * 40f + 180f), 16, 220f)
                    return true
                }
                if (fxBtn.contains(x, y)) {
                    fxPointer = id
                    fxDownMs = SystemClock.uptimeMillis()
                    fxDownX = x
                    postDelayed(fxLongPress, 450L)
                    return true
                }
                for (k in 0 until 4) {
                    if (knobRects[k].contains(x, y)) {
                        pointerKnob[id] = k
                        knobStartY[id] = y
                        knobStartVal[id] = Engine.knobs[k]
                        return true
                    }
                }
                if (seqRect.contains(x, y)) {
                    when {
                        recBtn.contains(x, y) -> {
                            recPressed()
                            burst(x, y, Color.rgb(255, 45, 70), 18, 220f)
                        }
                        playBtn.contains(x, y) -> {
                            Engine.seqPlay()
                            burst(x, y, if (theme == NEON) Color.rgb(60, 230, 120) else ORANGE, 18, 220f)
                        }
                        stopBtn.contains(x, y) -> {
                            val wasPatternRec = Engine.seqRecording
                            Engine.seqStop()
                            if (Engine.songRecording) onSongStopped?.invoke()
                            else if (wasPatternRec) onPatternStopped?.invoke()
                            stopPointer = id
                            stopDownMs = SystemClock.uptimeMillis()
                        }
                        else -> {
                            for (k in 0 until Engine.STEPS) {
                                val sr = stepRects[k]
                                if (x >= sr.left - dp(2f) && x <= sr.right + dp(2f) && y >= sr.top - dp(6f) && y <= sr.bottom + dp(6f)) {
                                    if (Engine.seqSelected >= 0) {
                                        stepPointer = id
                                        stepIdx = k
                                        stepDownY = y
                                        stepDragged = false
                                        stepStartVel = Engine.seqVel(Engine.seqSelected, k)
                                    }
                                    break
                                }
                            }
                        }
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                for (sl in 0 until Engine.SLOTS) {
                    if (slotRects[sl].contains(x, y) && xyRect.contains(x, y)) {
                        slotPressed(sl)
                        return true
                    }
                }
                if (holdRect.contains(x, y) && xyRect.contains(x, y)) {
                    toggleHold()
                    return true
                }
                if (motRect.contains(x, y) && xyRect.contains(x, y)) {
                    motionPressed()
                    return true
                }
                if (xyRect.contains(x, y)) {
                    if (xyPointer < 0) {
                        xyPointer = id
                        setXy(x, y)
                        Engine.liveTouch = true
                        Engine.fxTouch = true
                        burst(x, y, neon(Fx.HUES[Engine.fxType]), 20, 240f)
                    }
                    return true
                }
                for (i in 0 until VISIBLE_PADS) {
                    val mr = moreRects[i]
                    // etwas großzügigere Trefferfläche für den kleinen Knopf
                    if (x >= mr.left - dp(6f) && x <= mr.right + dp(6f) && y >= mr.top - dp(4f) && y <= mr.bottom + dp(4f)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onPadMenu?.invoke(i)
                        return true
                    }
                }
                val p = hitPad(x, y)
                if (p < 0) return true
                if (clearMode) {
                    if (Engine.hasLoop(p)) {
                        Engine.clear(p)
                        val r = padRects[p]
                        burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 60, 420f)
                        burst(r.centerX(), r.centerY(), padAccent(p), 40, 300f)
                        shocks.add(Shock(RectF(r), 0f, Color.rgb(255, 40, 70)))
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    clearMode = false
                    return true
                }
                if (pointerPad.containsValue(p)) return true
                pointerPad[id] = p
                val shot = Engine.padDown(p)
                if (shot) shotPointers.add(id)
                flash[p] = 1f
                if (shot) {
                    burst(x, y, if (theme == NEON) warmCols[Random.nextInt(warmCols.size)] else AMBER, 30, 340f)
                    shocks.add(Shock(RectF(padRects[p]), 0f, Color.rgb(255, 95, 0)))
                } else {
                    burst(x, y, padAccent(p), 22, 260f)
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_MOVE -> {
                for (pi in 0 until e.pointerCount) {
                    val id = e.getPointerId(pi)
                    if (id == xyPointer) setXy(e.getX(pi), e.getY(pi))
                    if (id == stepPointer && Engine.seqSelected >= 0) {
                        val dy = stepDownY - e.getY(pi)
                        if (stepDragged || abs(dy) > dp(8f)) {
                            stepDragged = true
                            val base = if (stepStartVel > 0f) stepStartVel else 1f
                            Engine.setSeqVel(Engine.seqSelected, stepIdx, (base + dy / dp(90f)).coerceIn(0.05f, 1f))
                        }
                    }
                    val k = pointerKnob[id] ?: continue
                    val sy0 = knobStartY[id] ?: continue
                    val sv = knobStartVal[id] ?: continue
                    Engine.knobs[k] = (sv + (sy0 - e.getY(pi)) / dp(180f)).coerceIn(0f, 1f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val upId = e.getPointerId(idx)
                if (upId == stepPointer) {
                    stepPointer = -1
                    val sel = Engine.seqSelected
                    if (sel >= 0 && !stepDragged) {
                        Engine.seqToggle(sel, stepIdx)
                        val sr = stepRects[stepIdx]
                        burst(sr.centerX(), sr.centerY(), padAccent(sel), 8, 140f)
                    }
                    return true
                }
                if (upId == stopPointer) {
                    stopPointer = -1
                    if (SystemClock.uptimeMillis() - stopDownMs >= 600L) {
                        Engine.seqClear()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        burst(stopBtn.centerX(), stopBtn.centerY(), Color.WHITE, 30, 300f)
                    }
                    return true
                }
                if (upId == fxPointer) {
                    fxPointer = -1
                    removeCallbacks(fxLongPress)
                    if (SystemClock.uptimeMillis() - fxDownMs < 450L) {
                        Engine.selectFx(Engine.fxType + if (fxDownX < fxBtn.centerX()) -1 else 1)
                        fxFlash = 1f
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        burst(fxDownX, fxBtn.centerY(), neon(Fx.HUES[Engine.fxType]), 26, 280f)
                    }
                    return true
                }
                if (upId == xyPointer) {
                    xyPointer = -1
                    Engine.liveTouch = false
                    Engine.fxTouch = xyHold
                    return true
                }
                if (pointerKnob.remove(upId) != null) {
                    knobStartY.remove(upId)
                    knobStartVal.remove(upId)
                    return true
                }
                val p = pointerPad.remove(upId) ?: return true
                if (shotPointers.remove(upId)) return true
                release(p, e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                fxPointer = -1
                removeCallbacks(fxLongPress)
                xyPointer = -1
                Engine.liveTouch = false
                Engine.fxTouch = xyHold
                pointerKnob.clear()
                val held = ArrayList<Int>()
                for ((pid, pad) in pointerPad) if (!shotPointers.contains(pid)) held.add(pad)
                pointerPad.clear()
                shotPointers.clear()
                for (p in held) release(p, padRects[p].centerX(), padRects[p].centerY())
            }
        }
        return true
    }

    private fun release(p: Int, x: Float, y: Float) {
        val hadLoop = Engine.hasLoop(p)
        val tap = Engine.padUp(p)
        if (tap) {
            if (hadLoop) {
                // Antippen schaltet den Loop an/aus – er läuft immer im Raster weiter und steigt synchron ein
                Engine.toggleMute(p)
                flash[p] = 1f
                burst(x, y, padAccent(p), 18, 200f)
            }
        } else {
            val c = neon(hue(p))
            shocks.add(Shock(RectF(padRects[p]), 0f, c))
            burst(padRects[p].centerX(), padRects[p].centerY(), c, 44, 380f)
            flash[p] = 1f
        }
    }


    // ================================================================ Helle Designs: RETRO & COMIC

    private val light: Boolean get() = theme == RETRO || theme == COMIC
    private val specL = RectF()
    private val specR = RectF()
    private var comicBg: Bitmap? = null
    private var comicXy: Bitmap? = null

    private fun ink(a: Float): Int = when {
        theme == RETRO -> alpha(R_INK, a)
        theme == COMIC -> alpha(Color.BLACK, a)
        skinned -> alpha(skin.ink, a)
        else -> alpha(Color.WHITE, a)
    }

    private fun lightPadColor(i: Int): Int =
        if (theme == RETRO) (if ((i / 4 + i % 4) % 2 == 0) R_GREEN else R_ORANGE) else COMIC_PADS[i % COMIC_PADS.size]

    private fun buildLightCaches() {
        comicBg?.recycle(); comicBg = null
        comicXy?.recycle(); comicXy = null
        if (theme != COMIC || width <= 0 || height <= 0) return
        // Raster-Punkte (Halftone) für Hintergrund
        val bg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        c.drawColor(Color.rgb(253, 236, 170))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(247, 205, 110)
        val step = dp(11f)
        var row = 0
        var y = 0f
        while (y < height + step) {
            var x = if (row % 2 == 0) 0f else step / 2f
            while (x < width + step) {
                c.drawCircle(x, y, step * 0.28f, p)
                x += step
            }
            y += step * 0.87f
            row++
        }
        comicBg = bg
        // Cyan-Fläche mit Punkten, zur Mitte hin größer
        val xr = xyRect
        if (xr.width() > 0f) {
            val xb = Bitmap.createBitmap(xr.width().toInt().coerceAtLeast(1), xr.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val xc = Canvas(xb)
            xc.drawColor(Color.rgb(46, 200, 240))
            p.color = Color.rgb(20, 140, 205)
            val st = dp(9f)
            val cxm = xb.width / 2f
            val cym = xb.height / 2f
            val maxD = kotlin.math.hypot(cxm, cym)
            var r2 = 0
            var yy = 0f
            while (yy < xb.height + st) {
                var xx = if (r2 % 2 == 0) 0f else st / 2f
                while (xx < xb.width + st) {
                    val d = kotlin.math.hypot(xx - cxm, yy - cym) / maxD
                    xc.drawCircle(xx, yy, st * (0.12f + 0.26f * d), p)
                    xx += st
                }
                yy += st * 0.87f
                r2++
            }
            comicXy = xb
        }
    }

    /** Comic-Schatten: versetzte schwarze Form. */
    private fun comicShadow(canvas: Canvas, r: RectF, rr: Float) {
        fillPaint.shader = null
        fillPaint.color = Color.BLACK
        tmpRect.set(r.left + dp(3f), r.top + dp(4f), r.right + dp(3f), r.bottom + dp(4f))
        canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
    }

    private fun drawBackgroundLight(canvas: Canvas) {
        if (theme == COMIC) {
            val b = comicBg
            if (b != null) canvas.drawBitmap(b, 0f, 0f, null) else canvas.drawColor(Color.rgb(253, 236, 170))
        } else {
            canvas.drawColor(R_BG)
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = Color.rgb(70, 70, 70)
            val m = dp(16f)
            val y1 = displayRect.bottom + dp(6f)
            val y2 = knobRects[0].bottom + dp(3f)
            canvas.drawLine(m, y1, width - m, y1, strokePaint)
            canvas.drawLine(m, y2, width - m, y2, strokePaint)
        }
    }

    private fun drawTopBarLight(canvas: Canvas) {
        if (theme == RETRO) {
            // Pixel-Titel wie im Entwurf
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = logoDst.height() * 0.62f
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val bx = logoDst.left
            val by = logoDst.centerY() + textPaint.textSize * 0.36f
            textPaint.color = R_INK
            canvas.drawText("Marv", bx + dp(2f), by - textPaint.textSize * 0.5f + dp(2f), textPaint)
            canvas.drawText("PAD", bx + dp(2f), by + textPaint.textSize * 0.5f + dp(2f), textPaint)
            textPaint.color = R_ORANGE
            canvas.drawText("Marv", bx, by - textPaint.textSize * 0.5f, textPaint)
            canvas.drawText("PAD", bx, by + textPaint.textSize * 0.5f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        } else {
            val rr = dp(10f)
            tmpRect.set(logoDst.left - dp(6f), logoDst.top - dp(4f), logoDst.right + dp(6f), logoDst.bottom + dp(4f))
            val badge = RectF(tmpRect)
            comicShadow(canvas, badge, rr)
            fillPaint.color = Color.rgb(25, 25, 30)
            canvas.drawRoundRect(badge, rr, rr, fillPaint)
            canvas.drawBitmap(logo, null, logoDst, logoPaint)
        }
        drawDisplay(canvas)
        drawDesignButton(canvas)
        val blink = 0.5f + 0.5f * sin(time * 10f)
        drawButton(canvas, clearBtn, "CLEAR", clearMode, blink)
        drawButton(canvas, muteBtn, if (Engine.anyPlaying()) "ALLE STUMM" else "ALLE AN", false, 0f)
    }

    private fun drawDisplayLight(canvas: Canvas) {
        val r = displayRect
        val type = Engine.fxType
        val txt: Int
        val rr: Float
        if (theme == RETRO) {
            rr = dp(6f)
            fillPaint.shader = null
            fillPaint.color = R_SCREEN
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(3.5f)
            strokePaint.color = R_ORANGE
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            txt = R_LCD
        } else {
            rr = dp(8f)
            comicShadow(canvas, r, rr)
            fillPaint.color = Color.WHITE
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = Color.BLACK
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            txt = Color.BLACK
        }
        canvas.save()
        canvas.clipRect(r.left + dp(3f), r.top + dp(3f), r.right - dp(3f), r.bottom - dp(3f))
        val pad = dp(9f)
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(txt, 0.7f)
        canvas.drawText(String.format(Locale.GERMANY, "%d·FX %02d/%d %s %s", Engine.editSlot + 1, type + 1, Fx.COUNT, if (touching) "●" else "○", fxTargetLabel()), r.left + pad, r.top + dp(14f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(10f)
        textPaint.color = if (theme == COMIC) COMIC_MAGENTA else R_ORANGE
        canvas.drawText(bpmLabel(), r.right - pad, r.top + dp(15f), textPaint)
        textPaint.textSize = dp(8f)
        textPaint.color = alpha(txt, 0.5f)
        canvas.drawText("TEMPO ▸", r.right - pad, r.top + dp(26f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = min(dp(17f), r.height() * 0.32f)
        textPaint.color = txt
        canvas.drawText(Fx.NAMES[type], r.left + pad, r.top + dp(32f), textPaint)
        textPaint.textSize = dp(8.5f)
        textPaint.color = alpha(txt, 0.8f)
        canvas.drawText("X ${Fx.X_LABELS[type]} ${(xyDispX * 100).toInt()}  Y ${Fx.Y_LABELS[type]} ${(xyDispY * 100).toInt()}", r.left + pad, r.top + dp(44f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        val mp = Engine.masterProgress()
        val ly = r.bottom - dp(7f)
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = alpha(txt, 0.15f)
        canvas.drawLine(r.left + pad, ly, r.right - pad, ly, strokePaint)
        if (mp >= 0f) {
            strokePaint.color = if (theme == COMIC) COMIC_PADS[0] else R_LCD
            canvas.drawLine(r.left + pad, ly, r.left + pad + (r.width() - 2 * pad) * mp, ly, strokePaint)
        }
        canvas.restore()
    }

    private fun drawDesignButtonLight(canvas: Canvas) {
        val r = designBtn
        val rr = dp(8f)
        if (theme == COMIC) comicShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = if (theme == COMIC) Color.WHITE else Color.rgb(190, 190, 190)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(if (theme == COMIC) 3f else 1.6f)
        strokePaint.color = if (theme == COMIC) Color.BLACK else Color.rgb(60, 60, 60)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        drawMenuIcon(canvas, r, if (theme == COMIC) Color.BLACK else R_INK)
    }

    private fun drawButtonLight(canvas: Canvas, r: RectF, label: String, active: Boolean, blink: Float) {
        val red = Color.rgb(235, 40, 50)
        if (theme == COMIC) {
            val rr = r.height() / 2f
            comicShadow(canvas, r, rr)
            fillPaint.color = if (active) red else Color.WHITE
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = Color.BLACK
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.color = if (active) Color.WHITE else Color.BLACK
        } else {
            val rr = dp(4f)
            fillPaint.shader = null
            fillPaint.color = if (active) alpha(red, 0.6f + 0.4f * blink) else Color.rgb(190, 190, 190)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.6f)
            strokePaint.color = Color.rgb(60, 60, 60)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.color = if (active) Color.WHITE else R_INK
        }
        textPaint.textSize = dp(11.5f)
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4f), textPaint)
    }

    private fun drawPadLight(canvas: Canvas, i: Int, dt: Float) {
        val r = padRects[i]
        val st = Engine.state(i)
        flash[i] = max(0f, flash[i] - dt * 2.4f)
        val lvl = min(1f, Engine.levels[i] * 2.2f)
        val inLvl = min(1f, Engine.inputLevel * 3f)
        val base = lightPadColor(i)
        val bgc = if (theme == RETRO) R_BG else Color.rgb(253, 236, 170)
        val shotMode = Engine.isOneShot(i)
        var progress = -1f
        var fill = base
        var outline = if (theme == COMIC) Color.BLACK else Color.rgb(45, 50, 45)
        when (st) {
            Engine.PadState.EMPTY -> fill = blend(base, bgc, 0.55f)
            Engine.PadState.ONESHOT -> {
                progress = Engine.shotProgress(i)
                fill = if (progress >= 0f) blend(base, Color.WHITE, 0.15f + 0.45f * lvl) else base
            }
            Engine.PadState.PLAYING -> {
                progress = Engine.progress(i)
                if (progress < 0.06f) flash[i] = max(flash[i], 0.45f * (1f - progress / 0.06f))
                fill = blend(base, Color.WHITE, 0.4f * lvl)
            }
            Engine.PadState.MUTED -> {
                progress = Engine.progress(i)
                fill = blend(base, Color.rgb(150, 150, 150), 0.75f)
            }
            Engine.PadState.RECORDING -> {
                val blink = 0.5f + 0.5f * sin(time * 14f)
                fill = blend(Color.rgb(235, 30, 45), Color.WHITE, 0.15f + 0.35f * blink * (1f - inLvl))
                outline = Color.rgb(140, 0, 10)
            }
            Engine.PadState.OVERDUB -> {
                progress = Engine.progress(i)
                fill = blend(base, Color.WHITE, 0.3f + 0.3f * sin(time * 22f) + 0.2f * inLvl)
            }
        }
        fill = blend(fill, Color.WHITE, flash[i] * 0.6f)

        if (theme == COMIC) {
            comicShadow(canvas, r, radius)
            fillPaint.color = fill
            canvas.drawRoundRect(r, radius, radius, fillPaint)
            strokePaint.strokeWidth = dp(3.5f)
            strokePaint.color = outline
            canvas.drawRoundRect(r, radius, radius, strokePaint)
            // Glanz oben links
            val ins = r.width() * 0.14f
            tmpRect.set(r.left + ins, r.top + ins * 0.8f, r.right - ins, r.bottom - ins)
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = alpha(Color.WHITE, 0.75f)
            canvas.drawArc(tmpRect, 200f, 55f, false, strokePaint)
            strokePaint.strokeCap = Paint.Cap.BUTT
            dotPaint.color = alpha(Color.WHITE, 0.8f)
            canvas.drawCircle(r.left + ins * 1.1f, r.top + ins * 1.9f, dp(2f), dotPaint)
        } else {
            fillPaint.shader = null
            fillPaint.color = fill
            canvas.drawRoundRect(r, radius, radius, fillPaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = outline
            canvas.drawRoundRect(r, radius, radius, strokePaint)
            val ins = dp(6f)
            tmpRect.set(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins)
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = alpha(Color.BLACK, 0.35f)
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), strokePaint)
            // Mittelpunkt in Gegenfarbe
            dotPaint.color = if (base == R_GREEN) R_ORANGE else R_GREEN
            canvas.drawCircle(r.centerX(), r.centerY(), dp(4f), dotPaint)
            strokePaint.strokeWidth = dp(1f)
            strokePaint.color = alpha(Color.BLACK, 0.6f)
            canvas.drawCircle(r.centerX(), r.centerY(), dp(4f), strokePaint)
        }
        // One-Shot-Markierung
        if (shotMode) {
            textPaint.textSize = r.width() * 0.16f
            textPaint.color = Color.BLACK
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("⚡", r.left + r.width() * 0.08f, r.top + r.width() * 0.22f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        // Lauflicht
        if (progress >= 0f && padLengths[i] > 0f) {
            val len = padLengths[i]
            for (j in 0 until 10) {
                var p = progress - j * 0.012f
                if (p < 0f) {
                    if (shotMode) break
                    p += 1f
                }
                padMeasures[i].getPosTan(p * len, pos, null)
                val k = 1f - j / 10f
                dotPaint.color = alpha(if (j == 0) Color.WHITE else Color.BLACK, k)
                canvas.drawCircle(pos[0], pos[1], dp(4.5f) * k + dp(1f), dotPaint)
            }
        }
        if (st == Engine.PadState.RECORDING) {
            val secs = Engine.recordingSeconds(i)
            val m = r.width() * 0.24f
            tmpRect.set(r.left + m, r.top + m, r.right - m, r.bottom - m)
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(Color.WHITE, 0.4f)
            canvas.drawOval(tmpRect, strokePaint)
            strokePaint.color = Color.WHITE
            canvas.drawArc(tmpRect, -90f, (secs % 1f) * 360f, false, strokePaint)
        }
        if (clearMode && Engine.hasLoop(i)) {
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(Color.BLACK, 0.5f + 0.5f * sin(time * 12f))
            val m = dp(18f)
            canvas.drawLine(r.left + m, r.top + m, r.right - m, r.bottom - m, strokePaint)
            canvas.drawLine(r.right - m, r.top + m, r.left + m, r.bottom - m, strokePaint)
        }
        val label = when (st) {
            Engine.PadState.EMPTY -> ""
            Engine.PadState.ONESHOT -> String.format(Locale.GERMANY, "%.1fs", Engine.loopSeconds(i))
            Engine.PadState.PLAYING -> String.format(Locale.GERMANY, "∞ %.1fs", Engine.loopSeconds(i))
            Engine.PadState.MUTED -> "AUS"
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
            Engine.PadState.OVERDUB -> "DUB"
        }
        if (label.isNotEmpty()) {
            textPaint.textSize = r.width() * 0.13f
            textPaint.color = if (st == Engine.PadState.RECORDING) Color.WHITE else Color.BLACK
            canvas.drawText(label, r.centerX(), r.bottom - r.height() * 0.13f, textPaint)
        }
    }

    private fun drawMoreLight(canvas: Canvas, i: Int) {
        val r = moreRects[i]
        val has = Engine.hasLoop(i)
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = if (has) lightPadColor(i) else if (theme == COMIC) Color.WHITE else Color.rgb(190, 190, 190)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(if (theme == COMIC) 2.2f else 1.2f)
        strokePaint.color = if (theme == COMIC) Color.BLACK else Color.rgb(60, 60, 60)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        dotPaint.color = Color.BLACK
        val d = dp(5f)
        for (k in -1..1) canvas.drawCircle(r.centerX() + k * d, r.centerY(), dp(1.6f), dotPaint)
    }

    private fun knobRetro(canvas: Canvas, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        fillPaint.shader = null
        fillPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, rad * 0.8f, fillPaint)
        strokePaint.strokeWidth = dp(5f)
        strokePaint.color = R_ORANGE
        canvas.drawCircle(cx, cy, rad * 0.86f, strokePaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = Color.rgb(60, 60, 60)
        canvas.drawCircle(cx, cy, rad * 0.86f + dp(3f), strokePaint)
        canvas.drawCircle(cx, cy, rad * 0.86f - dp(3f), strokePaint)
        // grüner Wertbogen + Zeiger
        tmpRect.set(cx - rad * 0.55f, cy - rad * 0.55f, cx + rad * 0.55f, cy + rad * 0.55f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = if (active) R_GREEN else R_INK
        canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
        strokePaint.strokeWidth = dp(3f)
        canvas.drawLine(cx + cos(ang) * rad * 0.3f, cy + sin(ang) * rad * 0.3f, cx + cos(ang) * rad * 0.68f, cy + sin(ang) * rad * 0.68f, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun knobComic(canvas: Canvas, k: Int, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val c = COMIC_PADS[k % 4]
        dotPaint.color = Color.BLACK
        canvas.drawCircle(cx + dp(2.5f), cy + dp(3.5f), rad, dotPaint)
        fillPaint.shader = null
        fillPaint.color = blend(c, Color.WHITE, 0.15f)
        canvas.drawCircle(cx, cy, rad, fillPaint)
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, rad, strokePaint)
        // Zahnkranz: Zähne bis zum Wert weiß
        strokePaint.strokeCap = Paint.Cap.ROUND
        for (t in 0..10) {
            val f = t / 10f
            val a = Math.toRadians(135.0 + 270.0 * f).toFloat()
            val x1 = cx + cos(a) * rad * 0.62f
            val y1 = cy + sin(a) * rad * 0.62f
            val x2 = cx + cos(a) * rad * 0.84f
            val y2 = cy + sin(a) * rad * 0.84f
            strokePaint.strokeWidth = dp(4.5f)
            strokePaint.color = Color.BLACK
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = if (f <= v + 0.001f) Color.WHITE else c
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
        strokePaint.strokeCap = Paint.Cap.BUTT
        // Glänzende Kugel
        fillPaint.shader = RadialGradient(cx - rad * 0.15f, cy - rad * 0.2f, rad * 0.55f, blend(c, Color.WHITE, 0.35f), c, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.48f, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, rad * 0.48f, strokePaint)
        dotPaint.color = alpha(Color.WHITE, 0.9f)
        canvas.drawOval(cx - rad * 0.28f, cy - rad * 0.36f, cx - rad * 0.02f, cy - rad * 0.18f, dotPaint)
        // Zeiger
        val px = cx + cos(ang) * rad * 0.34f
        val py = cy + sin(ang) * rad * 0.34f
        dotPaint.color = Color.BLACK
        canvas.drawCircle(px, py, dp(if (active) 4f else 3f), dotPaint)
    }

    private fun drawFxButtonLight(canvas: Canvas) {
        val r = fxBtn
        if (theme == RETRO) {
            val rr = dp(4f)
            fillPaint.shader = null
            fillPaint.color = blend(R_ORANGE, Color.WHITE, 0.3f * fxFlash)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.8f)
            strokePaint.color = Color.rgb(60, 60, 60)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.textSize = r.width() * 0.32f
            textPaint.color = Color.rgb(40, 40, 40)
            canvas.drawText(FX_CODES[Engine.fxType], r.centerX(), r.centerY() + r.width() * 0.11f, textPaint)
        } else {
            val rr = dp(4f)
            comicShadow(canvas, r, rr)
            fillPaint.color = Color.WHITE
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            // Explosions-Stern
            val star = Path()
            val n = 12
            val ro = r.width() * (0.46f + 0.05f * fxFlash)
            val ri = r.width() * 0.26f
            for (q in 0 until n * 2) {
                val a = (Math.PI * q / n - Math.PI / 2 + time * 0.3).toFloat()
                val rad = if (q % 2 == 0) ro else ri
                val x = r.centerX() + cos(a) * rad
                val y = r.centerY() + sin(a) * rad
                if (q == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            canvas.save()
            canvas.clipRect(r)
            fillPaint.color = Color.rgb(255, 222, 30)
            canvas.drawPath(star, fillPaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = Color.BLACK
            canvas.drawPath(star, strokePaint)
            canvas.restore()
            strokePaint.strokeWidth = dp(3f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.textSize = r.width() * 0.26f
            textPaint.color = Color.BLACK
            canvas.drawText(FX_CODES[Engine.fxType], r.centerX(), r.centerY() + r.width() * 0.09f, textPaint)
        }
    }

    // ---------------------------------------------------------------- Spektrum (FFT)

    private val fftN = 1024
    private val fftRe = FloatArray(fftN)
    private val fftIm = FloatArray(fftN)
    private val fftBuf = FloatArray(fftN)
    private val fftWin = FloatArray(fftN) { (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (fftN - 1))).toFloat() }
    private val fftCos = FloatArray(fftN / 2) { kotlin.math.cos(2.0 * Math.PI * it / fftN).toFloat() }
    private val fftSin = FloatArray(fftN / 2) { (-kotlin.math.sin(2.0 * Math.PI * it / fftN)).toFloat() }
    private val bandsN = 24
    private val specOut = FloatArray(bandsN)
    private val specIn = FloatArray(bandsN)
    private val peakOut = FloatArray(bandsN)
    private val peakIn = FloatArray(bandsN)

    private fun fft() {
        val n = fftN
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = tr
                val ti = fftIm[i]; fftIm[i] = fftIm[j]; fftIm[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = fftCos[k * step]
                    val wi = fftSin[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = fftRe[b] * wr - fftIm[b] * wi
                    val xi = fftRe[b] * wi + fftIm[b] * wr
                    fftRe[b] = fftRe[a] - xr
                    fftIm[b] = fftIm[a] - xi
                    fftRe[a] += xr
                    fftIm[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun analyze(input: Boolean, levels: FloatArray, peaks: FloatArray, dt: Float) {
        if (input) Engine.copyInput(fftBuf) else Engine.copyOutput(fftBuf)
        for (i in 0 until fftN) {
            fftRe[i] = fftBuf[i] * fftWin[i]
            fftIm[i] = 0f
        }
        fft()
        val sr = Engine.sampleRate.toFloat()
        val binHz = sr / fftN
        for (b in 0 until bandsN) {
            val f0 = 40f * 400f.pow(b / bandsN.toFloat())
            val f1 = 40f * 400f.pow((b + 1) / bandsN.toFloat())
            val k0 = (f0 / binHz).toInt().coerceIn(1, fftN / 2 - 1)
            val k1 = max(k0 + 1, (f1 / binHz).toInt().coerceIn(1, fftN / 2))
            var m = 0f
            for (k in k0 until k1) {
                val mag = kotlin.math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k])
                if (mag > m) m = mag
            }
            val db = 20f * kotlin.math.log10(m * 4f / fftN + 1e-6f)
            val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)
            levels[b] = max(norm, levels[b] - dt * 1.6f)
            peaks[b] = max(levels[b], peaks[b] - dt * 0.35f)
        }
    }

    private fun drawSpectrum(canvas: Canvas, r: RectF, levels: FloatArray, peaks: FloatArray, label: String) {
        val rr = dp(8f)
        fillPaint.shader = null
        fillPaint.color = R_SCREEN
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = R_ORANGE
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = Color.rgb(60, 60, 60)
        tmpRect.set(r.left - dp(2.5f), r.top - dp(2.5f), r.right + dp(2.5f), r.bottom + dp(2.5f))
        canvas.drawRoundRect(tmpRect, rr + dp(2f), rr + dp(2f), strokePaint)
        val ins = dp(7f)
        val left = r.left + ins
        val bottom = r.bottom - ins
        val hgt = r.height() - 2 * ins
        val bw = (r.width() - 2 * ins) / bandsN
        for (b in 0 until bandsN) {
            val x = left + b * bw
            val top = bottom - hgt * levels[b]
            fillPaint.color = R_LCD
            canvas.drawRect(x + bw * 0.12f, top, x + bw * 0.88f, bottom, fillPaint)
            val py = bottom - hgt * peaks[b]
            fillPaint.color = Color.rgb(170, 255, 190)
            canvas.drawRect(x + bw * 0.12f, py - dp(2f), x + bw * 0.88f, py, fillPaint)
        }
        // Segment-Linien
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = R_SCREEN
        var yy = bottom - dp(4f)
        while (yy > r.top + ins) {
            canvas.drawLine(left, yy, r.right - ins, yy, strokePaint)
            yy -= dp(4f)
        }
        textPaint.textSize = dp(10f)
        textPaint.color = R_INK
        canvas.drawText(label, r.centerX(), r.bottom + dp(13f), textPaint)
    }

    private fun drawXyLight(canvas: Canvas, dt: Float) {
        val r = xyRect
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        val fx = r.left + r.width() * xyDispX
        val fy = r.bottom - r.height() * xyDispY
        if (theme == RETRO) {
            analyze(false, specOut, peakOut, dt)
            analyze(true, specIn, peakIn, dt)
            drawSpectrum(canvas, specL, specOut, peakOut, "SPECTRUM OUT")
            drawSpectrum(canvas, specR, specIn, peakIn, "SPECTRUM MIC")
            val rr = dp(4f)
            fillPaint.shader = null
            fillPaint.color = Color.rgb(58, 58, 58)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            canvas.save()
            canvas.clipRect(r)
            strokePaint.strokeWidth = dp(1f)
            strokePaint.color = alpha(R_LCD, 0.75f)
            val ins = dp(6f)
            for (k in 0..10) {
                val x = r.left + ins + (r.width() - 2 * ins) * k / 10f
                canvas.drawLine(x, r.top + ins, x, r.bottom - ins, strokePaint)
                val y = r.top + ins + (r.height() - 2 * ins) * k / 10f
                canvas.drawLine(r.left + ins, y, r.right - ins, y, strokePaint)
            }
            if (touching) {
                dotPaint.color = alpha(R_ORANGE, 0.3f)
                canvas.drawCircle(fx, fy, dp(20f), dotPaint)
            }
            dotPaint.color = R_ORANGE
            canvas.drawCircle(fx, fy, dp(if (touching) 10f else 8f), dotPaint)
            canvas.restore()
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = Color.rgb(45, 45, 45)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.textSize = dp(10f)
            textPaint.color = R_INK
            canvas.drawText("XY PAD · ${Fx.X_LABELS[Engine.fxType]} / ${Fx.Y_LABELS[Engine.fxType]}", r.centerX(), r.bottom + dp(13f), textPaint)
        } else {
            val rr = dp(26f)
            comicShadow(canvas, r, rr)
            val clip = Path()
            clip.addRoundRect(r, rr, rr, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val b = comicXy
            if (b != null) canvas.drawBitmap(b, r.left, r.top, null) else canvas.drawColor(Color.rgb(46, 200, 240))
            if (touching) {
                fillPaint.shader = RadialGradient(fx, fy, r.width() * 0.4f, alpha(Color.WHITE, 0.55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(r, fillPaint)
                fillPaint.shader = null
            }
            canvas.restore()
            strokePaint.strokeWidth = dp(5f)
            strokePaint.color = Color.BLACK
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            // Fadenkreuz (magenta mit schwarzer Kontur)
            val cr = dp(if (touching) 22f else 18f)
            for ((wdt, col) in arrayOf(dp(7f) to Color.BLACK, dp(3.5f) to COMIC_MAGENTA)) {
                strokePaint.strokeWidth = wdt
                strokePaint.color = col
                strokePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawCircle(fx, fy, cr, strokePaint)
                canvas.drawCircle(fx, fy, cr * 0.3f, strokePaint)
                canvas.drawLine(fx - cr * 1.6f, fy, fx - cr * 0.6f, fy, strokePaint)
                canvas.drawLine(fx + cr * 0.6f, fy, fx + cr * 1.6f, fy, strokePaint)
                canvas.drawLine(fx, fy - cr * 1.6f, fx, fy - cr * 0.6f, strokePaint)
                canvas.drawLine(fx, fy + cr * 0.6f, fx, fy + cr * 1.6f, strokePaint)
            }
            strokePaint.strokeCap = Paint.Cap.BUTT
            if (touching && Random.nextFloat() < 0.5f) burst(fx, fy, COMIC_PADS[Random.nextInt(4)], 2, 200f)
            textPaint.textSize = dp(10f)
            textPaint.color = Color.BLACK
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("X: ${Fx.X_LABELS[Engine.fxType]} →", r.right - dp(16f), r.bottom - dp(12f), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("↑ Y: ${Fx.Y_LABELS[Engine.fxType]}", r.left + dp(16f), r.bottom - dp(12f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawSequencerLight(canvas: Canvas) {
        val r = seqRect
        val comic = theme == COMIC
        val rr = if (comic) dp(12f) else dp(6f)
        if (comic) comicShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = if (comic) Color.WHITE else R_SCREEN
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(if (comic) 3f else 3.5f)
        strokePaint.color = if (comic) Color.BLACK else R_ORANGE
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        val cur = Engine.seqCurrentStep()
        val sel = Engine.seqSelected
        for (k in 0 until Engine.STEPS) {
            val s = stepRects[k]
            val on = sel >= 0 && Engine.seqHas(sel, k)
            val count = Engine.seqCount(k)
            val playing = k == cur
            val onCol = if (comic) COMIC_PADS[(k / 4) % 4] else R_LCD
            fillPaint.color = when {
                on -> alpha(onCol, 0.28f)
                playing -> if (comic) Color.rgb(230, 230, 230) else alpha(R_LCD, 0.35f)
                else -> if (comic) Color.WHITE else alpha(R_LCD, if (k % 4 == 0) 0.14f else 0.07f)
            }
            canvas.drawRoundRect(s, dp(3f), dp(3f), fillPaint)
            if (on) {
                fillPaint.color = onCol
                tmpRect.set(s.left, s.bottom - s.height() * Engine.seqVel(sel, k).coerceIn(0.08f, 1f), s.right, s.bottom)
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            }
            if (sel >= 0 && Engine.noteAt(sel, k)) {
                dotPaint.color = if (light) Color.BLACK else Color.WHITE
                canvas.drawCircle(s.centerX(), s.top + dp(5f), dp(2.6f), dotPaint)
            }
            strokePaint.strokeWidth = dp(if (playing) 2.5f else if (comic) 1.6f else 1f)
            strokePaint.color = if (playing) (if (comic) COMIC_MAGENTA else Color.WHITE) else if (comic) Color.BLACK else alpha(R_LCD, 0.5f)
            canvas.drawRoundRect(s, dp(3f), dp(3f), strokePaint)
            val others = count - (if (on) 1 else 0)
            if (others > 0) {
                dotPaint.color = if (comic) Color.BLACK else Color.WHITE
                for (d in 0 until min(others, 3)) canvas.drawCircle(s.centerX(), s.bottom - dp(4f) - d * dp(5f), dp(1.6f), dotPaint)
            }
        }
        val red = Color.rgb(235, 30, 45)
        val green = if (comic) Color.rgb(40, 190, 80) else R_GREEN
        val recOn = Engine.seqRecording || Engine.songRecording
        val blink = 0.5f + 0.5f * sin(time * 8f)
        for ((btn, act) in arrayOf(recBtn to recOn, playBtn to Engine.seqPlaying, stopBtn to false)) {
            val brr = dp(5f)
            fillPaint.color = when {
                btn === recBtn && act -> blend(red, Color.WHITE, 0.3f * blink)
                btn === playBtn && act -> green
                comic -> Color.WHITE
                else -> Color.rgb(190, 190, 190)
            }
            canvas.drawRoundRect(btn, brr, brr, fillPaint)
            strokePaint.strokeWidth = dp(if (comic) 2.5f else 1.4f)
            strokePaint.color = if (comic) Color.BLACK else Color.rgb(60, 60, 60)
            canvas.drawRoundRect(btn, brr, brr, strokePaint)
        }
        val ic = min(recBtn.height(), recBtn.width()) * 0.22f
        dotPaint.color = if (recOn) Color.WHITE else red
        canvas.drawCircle(recBtn.centerX(), recBtn.centerY(), ic, dotPaint)
        val tri = Path()
        tri.moveTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() - ic)
        tri.lineTo(playBtn.centerX() + ic * 1.1f, playBtn.centerY())
        tri.lineTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() + ic)
        tri.close()
        fillPaint.color = if (Engine.seqPlaying) Color.WHITE else green
        canvas.drawPath(tri, fillPaint)
        fillPaint.color = Color.rgb(40, 40, 40)
        canvas.drawRect(stopBtn.centerX() - ic * 0.9f, stopBtn.centerY() - ic * 0.9f, stopBtn.centerX() + ic * 0.9f, stopBtn.centerY() + ic * 0.9f, fillPaint)
        val ix = stopBtn.right + dp(10f)
        val txt = if (comic) Color.BLACK else R_LCD
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(11f)
        textPaint.color = if (recOn) red else txt
        val head = when {
            Engine.songRecording -> {
                val t = Engine.songSeconds().toInt()
                String.format(Locale.GERMANY, "● SONG %d:%02d", t / 60, t % 60)
            }
            Engine.seqRecording -> "● REC PATTERN"
            Engine.seqPlaying -> "▶ SEQ"
            else -> "SEQUENZER"
        }
        canvas.drawText(head, ix, recBtn.top + dp(13f), textPaint)
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(txt, 0.75f)
        canvas.drawText(if (sel >= 0) "Pad ${sel + 1}: tippen · ↕ Lautst." else "⚡-Pad antippen", ix, recBtn.top + dp(27f), textPaint)
        canvas.drawText("STOP halten = leeren", ix, recBtn.top + dp(40f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }


    // ================================================================ Produkt-Designs (GLAS, PASTELL, HOLZ, CREME, NOIR, GRAU)

    private class Skin(
        val bg: Int, val bg2: Int, val texture: Int, val panel: Int, val panelBorder: Int,
        val ink: Int, val padInk: Int, val accent: Int, val shot: Int,
        val knob: Int, val knobCap: Int, val knobRing: Int, val knobPointer: Int, val knobText: Int,
        val xyStyle: Int, val xyFill: Int, val xyFill2: Int, val xyGrid: Int, val xyCols: Int, val xyRows: Int, val xyBorder: Int, val xyDot: Int,
        val padStyle: Int, val padFill: Int, val palette: IntArray?, val padOutline: Int, val padRadius: Float, val shadow: Boolean,
        val btnFill: Int, val btnOutline: Int, val btnText: Int,
        val lcdBg: Int, val lcdText: Int, val lcdBorder: Int, val logoBadge: Boolean
    )

    private val skinned: Boolean get() = theme >= GLASS
    private val skin: Skin get() = SKINS[(theme - GLASS).coerceIn(0, SKINS.size - 1)]
    private var woodBmp: Bitmap? = null
    private var woodShader: Shader? = null
    private var linenShader: Shader? = null
    private val dashEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f)

    private fun buildSkinCaches() {
        if (!skinned || width <= 0 || height <= 0) return
        val sk = skin
        if (sk.texture != TEX_NONE && woodBmp == null) {
            // Holzmaserung prozedural erzeugen
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(WOOD_BASE)
            val rnd = Random(7)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val path = Path()
            var y = -20f
            while (y < height + 20f) {
                path.reset()
                val amp = 3f + rnd.nextFloat() * 14f
                val freq = 0.002f + rnd.nextFloat() * 0.006f
                val ph = rnd.nextFloat() * 6.28f
                path.moveTo(0f, y)
                var x = 0f
                while (x <= width) {
                    path.lineTo(x, y + amp * sin(x * freq + ph) + 4f * sin(x * freq * 3.1f + ph * 2f))
                    x += 12f
                }
                val dark = rnd.nextFloat() < 0.6f
                p.color = if (dark) Color.argb((20 + rnd.nextInt(50)), 30, 14, 6) else Color.argb((10 + rnd.nextInt(30)), 255, 210, 160)
                p.strokeWidth = 1f + rnd.nextFloat() * 3f
                c.drawPath(path, p)
                y += 2f + rnd.nextFloat() * 5f
            }
            woodBmp = b
            woodShader = android.graphics.BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        }
        if (linenShader == null) {
            val n = 64
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.rgb(234, 226, 210))
            val rnd = Random(3)
            val p = Paint()
            for (q in 0 until 260) {
                val horiz = rnd.nextBoolean()
                val x = rnd.nextInt(n).toFloat()
                val y = rnd.nextInt(n).toFloat()
                val l = 3f + rnd.nextInt(8)
                p.color = if (rnd.nextBoolean()) Color.argb(28, 120, 100, 70) else Color.argb(40, 255, 255, 245)
                if (horiz) c.drawRect(x, y, x + l, y + 1f, p) else c.drawRect(x, y, x + 1f, y + l, p)
            }
            linenShader = android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    private fun softShadow(canvas: Canvas, r: RectF, rr: Float, strength: Float = 1f) {
        fillPaint.shader = null
        for (q in 1..3) {
            fillPaint.color = Color.argb((14 * strength).toInt(), 0, 0, 0)
            val o = dp(q * 1.6f)
            tmpRect.set(r.left - o * 0.3f, r.top + o * 0.6f, r.right + o * 0.3f, r.bottom + o * 1.4f)
            canvas.drawRoundRect(tmpRect, rr + o, rr + o, fillPaint)
        }
    }

    private fun drawBackgroundSkin(canvas: Canvas) {
        val sk = skin
        val w = width.toFloat()
        val h = height.toFloat()
        if (sk.texture != TEX_NONE && woodShader != null) {
            bgPaint.shader = woodShader
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            bgPaint.shader = null
            if (sk.texture == TEX_WOOD_FRAME) {
                val m = dp(9f)
                tmpRect.set(m, m + insetTop * 0.2f, w - m, h - m)
                val rr = dp(16f)
                fillPaint.shader = null
                fillPaint.color = Color.argb(90, 0, 0, 0)
                canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
                tmpRect.inset(dp(2f), dp(2f))
                fillPaint.color = sk.panel
                canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
            }
        } else {
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, sk.bg, sk.bg2, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            bgPaint.shader = null
            if (Color.alpha(sk.panel) > 0) {
                val m = dp(7f)
                val r = RectF(m, displayRect.bottom + dp(10f), w - m, h - insetBottom - dp(30f))
                val rr = dp(18f)
                softShadow(canvas, r, rr)
                fillPaint.color = sk.panel
                canvas.drawRoundRect(r, rr, rr, fillPaint)
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = sk.panelBorder
                canvas.drawRoundRect(r, rr, rr, strokePaint)
            }
        }
    }

    private fun drawTopBarSkin(canvas: Canvas) {
        val sk = skin
        if (sk.logoBadge) {
            val rr = dp(10f)
            val badge = RectF(logoDst.left - dp(6f), logoDst.top - dp(4f), logoDst.right + dp(6f), logoDst.bottom + dp(4f))
            softShadow(canvas, badge, rr, 1.5f)
            fillPaint.color = Color.rgb(28, 30, 34)
            canvas.drawRoundRect(badge, rr, rr, fillPaint)
        }
        canvas.drawBitmap(logo, null, logoDst, logoPaint)
        drawDisplay(canvas)
        drawDesignButton(canvas)
        val blink = 0.5f + 0.5f * sin(time * 10f)
        drawButton(canvas, clearBtn, "CLEAR", clearMode, blink)
        drawButton(canvas, muteBtn, if (Engine.anyPlaying()) "ALLE STUMM" else "ALLE AN", false, 0f)
    }

    private fun drawDisplaySkin(canvas: Canvas) {
        val sk = skin
        val r = displayRect
        val rr = dp(8f)
        if (sk.shadow) softShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = sk.lcdBg
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = sk.lcdBorder
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        val txt = sk.lcdText
        val type = Engine.fxType
        val pad = dp(9f)
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        canvas.save()
        canvas.clipRect(r.left + dp(2f), r.top + dp(2f), r.right - dp(2f), r.bottom - dp(2f))
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(txt, 0.65f)
        canvas.drawText(String.format(Locale.GERMANY, "%d·FX %02d/%d %s %s", Engine.editSlot + 1, type + 1, Fx.COUNT, if (touching) "●" else "○", fxTargetLabel()), r.left + pad, r.top + dp(14f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(10f)
        textPaint.color = sk.accent
        canvas.drawText(bpmLabel(), r.right - pad, r.top + dp(15f), textPaint)
        textPaint.textSize = dp(8f)
        textPaint.color = alpha(txt, 0.5f)
        canvas.drawText("TEMPO ▸", r.right - pad, r.top + dp(26f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = min(dp(17f), r.height() * 0.32f)
        textPaint.color = txt
        canvas.drawText(Fx.NAMES[type], r.left + pad, r.top + dp(32f), textPaint)
        textPaint.textSize = dp(8.5f)
        textPaint.color = alpha(txt, 0.75f)
        canvas.drawText("X ${Fx.X_LABELS[type]} ${(xyDispX * 100).toInt()}  Y ${Fx.Y_LABELS[type]} ${(xyDispY * 100).toInt()}", r.left + pad, r.top + dp(44f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        val mp = Engine.masterProgress()
        val ly = r.bottom - dp(7f)
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = alpha(txt, 0.15f)
        canvas.drawLine(r.left + pad, ly, r.right - pad, ly, strokePaint)
        if (mp >= 0f) {
            strokePaint.color = sk.accent
            canvas.drawLine(r.left + pad, ly, r.left + pad + (r.width() - 2 * pad) * mp, ly, strokePaint)
        }
        canvas.restore()
    }

    private fun drawDesignButtonSkin(canvas: Canvas) {
        val sk = skin
        val r = designBtn
        val rr = dp(8f)
        if (sk.shadow) softShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = sk.btnFill
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = sk.btnOutline
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        drawMenuIcon(canvas, r, sk.btnText)
    }

    private fun drawButtonSkin(canvas: Canvas, r: RectF, label: String, active: Boolean, blink: Float) {
        val sk = skin
        val rr = r.height() / 2f
        if (sk.shadow) softShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = if (active) blend(Color.rgb(220, 40, 50), Color.WHITE, 0.25f * blink) else sk.btnFill
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = sk.btnOutline
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(11.5f)
        textPaint.color = if (active) Color.WHITE else sk.btnText
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4f), textPaint)
    }

    private fun knobSkin(canvas: Canvas, cx: Float, cy: Float, rad: Float, v: Float, ang: Float, active: Boolean) {
        val sk = skin
        // Wertebogen (dezente Rückmeldung)
        tmpRect.set(cx - rad * 1.1f, cy - rad * 1.1f, cx + rad * 1.1f, cy + rad * 1.1f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = alpha(sk.ink, 0.12f)
        canvas.drawArc(tmpRect, 135f, 270f, false, strokePaint)
        strokePaint.color = alpha(sk.accent, if (active) 1f else 0.75f)
        canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        // Schatten
        dotPaint.color = Color.argb(45, 0, 0, 0)
        canvas.drawCircle(cx + dp(1.5f), cy + dp(3f), rad * 0.92f, dotPaint)
        when (sk.knob) {
            KN_CAP -> {
                fillPaint.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.2f, blend(sk.knobCap, Color.WHITE, 0.6f), blend(sk.knobCap, Color.BLACK, 0.12f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.88f, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(40, 0, 0, 0)
                canvas.drawCircle(cx, cy, rad * 0.88f, strokePaint)
            }
            KN_KNURL, KN_GREYCAP -> {
                fillPaint.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.2f, blend(sk.knobRing, Color.WHITE, 0.5f), blend(sk.knobRing, Color.BLACK, 0.12f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.92f, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(70, 0, 0, 0)
                for (t in 0 until 44) {
                    val a = (t * Math.PI * 2 / 44).toFloat() + ang
                    canvas.drawLine(cx + cos(a) * rad * 0.74f, cy + sin(a) * rad * 0.74f, cx + cos(a) * rad * 0.9f, cy + sin(a) * rad * 0.9f, strokePaint)
                }
                fillPaint.shader = RadialGradient(cx - rad * 0.2f, cy - rad * 0.3f, rad * 0.8f, blend(sk.knobCap, Color.WHITE, 0.3f), sk.knobCap, Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.66f, fillPaint)
                fillPaint.shader = null
                strokePaint.color = Color.argb(60, 0, 0, 0)
                canvas.drawCircle(cx, cy, rad * 0.66f, strokePaint)
            }
            KN_BRASS -> {
                fillPaint.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(
                    Color.rgb(150, 110, 40), Color.rgb(240, 210, 130), Color.rgb(170, 125, 45), Color.rgb(235, 200, 120), Color.rgb(150, 110, 40)
                ), null)
                canvas.drawCircle(cx, cy, rad * 0.95f, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(90, 60, 40, 10)
                for (t in 0 until 60) {
                    val a = (t * Math.PI * 2 / 60).toFloat()
                    canvas.drawLine(cx + cos(a) * rad * 0.86f, cy + sin(a) * rad * 0.86f, cx + cos(a) * rad * 0.95f, cy + sin(a) * rad * 0.95f, strokePaint)
                }
                fillPaint.shader = RadialGradient(cx - rad * 0.2f, cy - rad * 0.3f, rad * 0.8f, Color.rgb(120, 70, 40), Color.rgb(62, 32, 16), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.74f, fillPaint)
                fillPaint.shader = RadialGradient(cx - rad * 0.15f, cy - rad * 0.2f, rad * 0.45f, Color.rgb(250, 225, 150), Color.rgb(175, 130, 50), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.4f, fillPaint)
                fillPaint.shader = null
            }
            else -> { // KN_GLASS
                fillPaint.shader = RadialGradient(cx, cy - rad * 0.3f, rad * 1.1f, Color.argb(230, 255, 255, 255), Color.argb(200, 205, 214, 226), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.95f, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, rad * 0.95f, strokePaint)
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(cx, cy, rad * 0.72f, strokePaint)
                fillPaint.shader = RadialGradient(cx - rad * 0.2f, cy - rad * 0.3f, rad * 0.7f, Color.rgb(240, 242, 245), Color.rgb(196, 202, 210), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, rad * 0.6f, fillPaint)
                fillPaint.shader = null
            }
        }
        // Zeiger
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = sk.knobPointer
        val r0 = if (sk.knob == KN_BRASS) rad * 0.12f else rad * 0.25f
        val r1 = if (sk.knob == KN_BRASS) rad * 0.38f else if (sk.knob == KN_CAP) rad * 0.82f else rad * 0.6f
        canvas.drawLine(cx + cos(ang) * r0, cy + sin(ang) * r0, cx + cos(ang) * r1, cy + sin(ang) * r1, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawFxButtonSkin(canvas: Canvas) {
        val sk = skin
        val r = fxBtn
        val rr = dp(8f)
        if (sk.shadow) softShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = blend(sk.btnFill, sk.accent, 0.35f * fxFlash + 0.15f * xyGlow)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        if (sk.padStyle == PS_FRAMED) {
            fillPaint.shader = linenShader
            tmpRect.set(r.left + dp(6f), r.top + dp(6f), r.right - dp(6f), r.bottom - dp(6f))
            canvas.drawRoundRect(tmpRect, dp(5f), dp(5f), fillPaint)
            fillPaint.shader = null
        }
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = sk.btnOutline
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = r.width() * 0.28f
        textPaint.color = if (sk.padStyle == PS_FRAMED) sk.padInk else sk.btnText
        canvas.drawText(FX_CODES[Engine.fxType], r.centerX(), r.centerY() + r.width() * 0.1f, textPaint)
    }

    private fun drawXySkin(canvas: Canvas, dt: Float) {
        val sk = skin
        val r = xyRect
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        val fx = r.left + r.width() * xyDispX
        val fy = r.bottom - r.height() * xyDispY
        val rr = dp(if (sk.xyStyle == XY_GLOW) 14f else 10f)
        if (sk.shadow) softShadow(canvas, r, rr)
        // Fläche
        when (sk.xyStyle) {
            XY_GLOW -> fillPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, sk.xyFill, sk.xyFill2, Shader.TileMode.CLAMP)
            XY_STITCH -> fillPaint.shader = linenShader
            else -> { fillPaint.shader = null; fillPaint.color = sk.xyFill }
        }
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        fillPaint.shader = null
        canvas.save()
        val clip = Path()
        clip.addRoundRect(r, rr, rr, Path.Direction.CW)
        canvas.clipPath(clip)
        val ins = dp(10f)
        val gl = r.left + ins
        val gt = r.top + ins
        val gr = r.right - ins
        val gb = r.bottom - ins
        strokePaint.color = sk.xyGrid
        when (sk.xyStyle) {
            XY_GLOW, XY_GRID -> {
                strokePaint.strokeWidth = dp(if (sk.xyStyle == XY_GLOW) 1f else 0.8f)
                if (sk.xyStyle == XY_GLOW) strokePaint.color = alpha(sk.xyGrid, 0.55f + 0.3f * xyGlow)
                for (k in 0..sk.xyCols) { val x = gl + (gr - gl) * k / sk.xyCols; canvas.drawLine(x, gt, x, gb, strokePaint) }
                for (k in 0..sk.xyRows) { val y = gt + (gb - gt) * k / sk.xyRows; canvas.drawLine(gl, y, gr, y, strokePaint) }
                if (sk.xyStyle == XY_GRID) {
                    // kleine Markierungen wie im Entwurf
                    textPaint.textSize = dp(9f)
                    textPaint.color = alpha(sk.xyGrid, 1f)
                    for (k in 0..sk.xyCols step 2) canvas.drawText("×", gl + (gr - gl) * k / sk.xyCols, gb + dp(8f), textPaint)
                }
            }
            XY_STITCH -> {
                strokePaint.strokeWidth = dp(1.3f)
                strokePaint.pathEffect = dashEffect
                for (k in 1 until sk.xyCols) { val x = r.left + r.width() * k / sk.xyCols; canvas.drawLine(x, r.top, x, r.bottom, strokePaint) }
                for (k in 1 until sk.xyRows) { val y = r.top + r.height() * k / sk.xyRows; canvas.drawLine(r.left, y, r.right, y, strokePaint) }
                tmpRect.set(r.left + dp(5f), r.top + dp(5f), r.right - dp(5f), r.bottom - dp(5f))
                canvas.drawRoundRect(tmpRect, rr - dp(3f), rr - dp(3f), strokePaint)
                strokePaint.pathEffect = null
                textPaint.typeface = Typeface.SERIF
                textPaint.textSize = dp(12f)
                textPaint.color = Color.rgb(180, 140, 70)
                canvas.drawText("XY PAD", r.centerX(), r.top + dp(22f), textPaint)
                textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            XY_DIAG -> {
                strokePaint.strokeWidth = dp(0.9f)
                for (q in 0..2) {
                    val o = dp(6f) + q * dp(4f)
                    tmpRect.set(r.left + o, r.top + o, r.right - o, r.bottom - o)
                    canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), strokePaint)
                }
                val o = dp(22f)
                canvas.drawLine(r.left + o, r.top + o, r.right - o, r.bottom - o, strokePaint)
                canvas.drawLine(r.right - o, r.top + o, r.left + o, r.bottom - o, strokePaint)
                canvas.drawLine(r.centerX(), r.top + dp(10f), r.centerX(), r.bottom - dp(10f), strokePaint)
                canvas.drawLine(r.left + dp(10f), r.centerY(), r.right - dp(10f), r.centerY(), strokePaint)
            }
            XY_CROSS -> {
                strokePaint.strokeWidth = dp(1f)
                canvas.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, strokePaint)
                canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), strokePaint)
            }
            else -> { // XY_INSET
                strokePaint.strokeWidth = dp(1.2f)
                tmpRect.set(r.left + dp(7f), r.top + dp(7f), r.right - dp(7f), r.bottom - dp(7f))
                canvas.drawRoundRect(tmpRect, dp(5f), dp(5f), strokePaint)
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = alpha(sk.xyGrid, 0.6f)
                for (k in 1 until sk.xyCols) { val x = tmpRect.left + tmpRect.width() * k / sk.xyCols; canvas.drawLine(x, tmpRect.top, x, tmpRect.bottom, strokePaint) }
                for (k in 1 until sk.xyRows) { val y = tmpRect.top + tmpRect.height() * k / sk.xyRows; canvas.drawLine(tmpRect.left, y, tmpRect.right, y, strokePaint) }
                strokePaint.strokeWidth = dp(1.4f)
                strokePaint.color = sk.xyBorder
                val c = dp(10f)
                canvas.drawLine(r.centerX() - c, r.centerY(), r.centerX() + c, r.centerY(), strokePaint)
                canvas.drawLine(r.centerX(), r.centerY() - c, r.centerX(), r.centerY() + c, strokePaint)
            }
        }
        // Berührungspunkt
        val a = 0.45f + 0.55f * xyGlow
        if (touching) {
            fillPaint.shader = RadialGradient(fx, fy, r.width() * 0.35f, alpha(sk.accent, 0.35f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(r, fillPaint)
            fillPaint.shader = null
            strokePaint.strokeWidth = dp(1f)
            strokePaint.color = alpha(sk.xyDot, 0.5f)
            canvas.drawLine(r.left, fy, r.right, fy, strokePaint)
            canvas.drawLine(fx, r.top, fx, r.bottom, strokePaint)
            val ring = (time * 2f) % 1f
            strokePaint.strokeWidth = dp(2f) * (1f - ring)
            strokePaint.color = alpha(sk.accent, 1f - ring)
            canvas.drawCircle(fx, fy, dp(14f) + dp(40f) * ring, strokePaint)
        }
        if (sk.xyStyle == XY_GLOW || sk.xyStyle == XY_CROSS) {
            dotPaint.color = alpha(sk.xyDot, 0.25f * a)
            canvas.drawCircle(fx, fy, dp(16f), dotPaint)
            dotPaint.color = alpha(sk.xyDot, 0.5f * a)
            canvas.drawCircle(fx, fy, dp(8f), dotPaint)
            dotPaint.color = alpha(Color.WHITE, a)
            canvas.drawCircle(fx, fy, dp(3.5f), dotPaint)
        } else {
            dotPaint.color = Color.argb((60 * a).toInt(), 0, 0, 0)
            canvas.drawCircle(fx + dp(1.5f), fy + dp(2.5f), dp(9f), dotPaint)
            dotPaint.color = alpha(sk.xyDot, a)
            canvas.drawCircle(fx, fy, dp(9f), dotPaint)
            dotPaint.color = alpha(Color.WHITE, 0.6f * a)
            canvas.drawCircle(fx - dp(2.5f), fy - dp(2.5f), dp(2.5f), dotPaint)
        }
        canvas.restore()
        strokePaint.strokeWidth = dp(if (sk.xyStyle == XY_GLOW) 3f else 1.4f)
        strokePaint.color = sk.xyBorder
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(if (sk.xyStyle == XY_CROSS || sk.xyStyle == XY_GLOW) Color.WHITE else sk.ink, 0.6f)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("X: ${Fx.X_LABELS[Engine.fxType]} →", r.right - dp(14f), r.bottom - dp(12f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ Y: ${Fx.Y_LABELS[Engine.fxType]}", r.left + dp(14f), r.bottom - dp(12f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawPadSkin(canvas: Canvas, i: Int, dt: Float) {
        val sk = skin
        val r = padRects[i]
        val st = Engine.state(i)
        flash[i] = max(0f, flash[i] - dt * 2.4f)
        val lvl = min(1f, Engine.levels[i] * 2.2f)
        val inLvl = min(1f, Engine.inputLevel * 3f)
        val shotMode = Engine.isOneShot(i)
        val acc = if (shotMode) sk.shot else sk.accent
        val pal = sk.palette
        val base = if (pal != null) pal[i % pal.size] else sk.padFill
        var progress = -1f
        var glowCol = acc
        var glow = 0f
        var led = Color.TRANSPARENT
        when (st) {
            Engine.PadState.EMPTY -> {}
            Engine.PadState.ONESHOT -> {
                progress = Engine.shotProgress(i)
                led = acc
                if (progress >= 0f) glow = 0.35f + 0.5f * lvl
            }
            Engine.PadState.PLAYING -> {
                progress = Engine.progress(i)
                if (progress < 0.06f) flash[i] = max(flash[i], 0.4f * (1f - progress / 0.06f))
                led = acc
                glow = 0.2f + 0.55f * lvl
            }
            Engine.PadState.MUTED -> {
                progress = Engine.progress(i)
                led = Color.rgb(140, 140, 140)
            }
            Engine.PadState.RECORDING -> {
                glowCol = Color.rgb(235, 35, 50)
                led = glowCol
                glow = 0.5f + 0.3f * sin(time * 14f) + 0.2f * inLvl
            }
            Engine.PadState.OVERDUB -> {
                progress = Engine.progress(i)
                glowCol = blend(acc, Color.WHITE, 0.4f)
                led = Color.rgb(235, 35, 50)
                glow = 0.6f + 0.3f * inLvl
            }
        }
        glow = min(1f, glow + flash[i] * 0.7f)
        val rad = radius
        var inner = r
        when (sk.padStyle) {
            PS_FRAMED -> {
                if (sk.shadow) softShadow(canvas, r, rad, 1.6f)
                fillPaint.shader = woodShader
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                fillPaint.shader = null
                fillPaint.color = Color.argb(70, 20, 8, 2)
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(70, 255, 220, 170)
                canvas.drawRoundRect(r, rad, rad, strokePaint)
                val ins = r.width() * 0.13f
                inner = RectF(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins)
                fillPaint.color = Color.argb(110, 0, 0, 0)
                tmpRect.set(inner.left, inner.top + dp(2f), inner.right, inner.bottom + dp(2.5f))
                canvas.drawRoundRect(tmpRect, rad * 0.7f, rad * 0.7f, fillPaint)
                fillPaint.shader = linenShader
                canvas.drawRoundRect(inner, rad * 0.7f, rad * 0.7f, fillPaint)
                fillPaint.shader = null
                strokePaint.color = sk.padOutline
                strokePaint.strokeWidth = dp(1.2f)
                canvas.drawRoundRect(inner, rad * 0.7f, rad * 0.7f, strokePaint)
            }
            PS_GLASS -> {
                fillPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(235, 250, 252, 255), Color.argb(215, 226, 234, 244), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                fillPaint.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.5f, alpha(sk.accent, 0.28f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(r, rad, rad, strokePaint)
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = sk.padOutline
                tmpRect.set(r.left + dp(2f), r.top + dp(2f), r.right - dp(2f), r.bottom - dp(2f))
                canvas.drawRoundRect(tmpRect, rad, rad, strokePaint)
            }
            else -> {
                if (sk.shadow) softShadow(canvas, r, rad, 1.4f)
                fillPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, blend(base, Color.WHITE, 0.12f), blend(base, Color.BLACK, 0.05f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                fillPaint.shader = null
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = sk.padOutline
                canvas.drawRoundRect(r, rad, rad, strokePaint)
                if (sk.padStyle == PS_DOUBLE) {
                    tmpRect.set(r.left + dp(3f), r.top + dp(3f), r.right - dp(3f), r.bottom - dp(3f))
                    strokePaint.color = Color.argb(150, 255, 255, 255)
                    canvas.drawRoundRect(tmpRect, rad, rad, strokePaint)
                }
            }
        }
        // Leuchten bei Aktivität
        if (glow > 0.01f) {
            fillPaint.shader = RadialGradient(inner.centerX(), inner.centerY(), inner.width() * 0.75f, alpha(glowCol, 0.75f * glow), alpha(glowCol, 0.08f * glow), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(inner, rad, rad, fillPaint)
            fillPaint.shader = null
        }
        // Status-LED
        if (led != Color.TRANSPARENT) {
            val lx = inner.right - dp(8f)
            val ly = inner.top + dp(8f)
            dotPaint.color = alpha(led, 0.35f)
            canvas.drawCircle(lx, ly, dp(6f), dotPaint)
            dotPaint.color = led
            canvas.drawCircle(lx, ly, dp(3f), dotPaint)
        }
        if (shotMode) {
            textPaint.textSize = inner.width() * 0.17f
            textPaint.color = alpha(sk.padInk, 0.8f)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("⚡", inner.left + dp(5f), inner.top + inner.width() * 0.22f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        // Lauflicht
        if (progress >= 0f && padLengths[i] > 0f) {
            val len = padLengths[i]
            val faded = st == Engine.PadState.MUTED
            for (j in 0 until 10) {
                var p = progress - j * 0.012f
                if (p < 0f) {
                    if (shotMode) break
                    p += 1f
                }
                padMeasures[i].getPosTan(p * len, pos, null)
                val k = 1f - j / 10f
                dotPaint.color = alpha(if (j == 0) (if (sk.padStyle == PS_GLASS) sk.accent else sk.padInk) else acc, k * (if (faded) 0.35f else 1f))
                canvas.drawCircle(pos[0], pos[1], dp(4f) * k + dp(1f), dotPaint)
            }
        }
        if (st == Engine.PadState.RECORDING) {
            val secs = Engine.recordingSeconds(i)
            val m = inner.width() * 0.24f
            tmpRect.set(inner.left + m, inner.top + m, inner.right - m, inner.bottom - m)
            strokePaint.strokeWidth = dp(3.5f)
            strokePaint.color = alpha(Color.WHITE, 0.4f)
            canvas.drawOval(tmpRect, strokePaint)
            strokePaint.color = Color.WHITE
            canvas.drawArc(tmpRect, -90f, (secs % 1f) * 360f, false, strokePaint)
        }
        if (clearMode && Engine.hasLoop(i)) {
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = alpha(Color.rgb(220, 40, 50), 0.5f + 0.5f * sin(time * 12f))
            val m = inner.width() * 0.25f
            canvas.drawLine(inner.left + m, inner.top + m, inner.right - m, inner.bottom - m, strokePaint)
            canvas.drawLine(inner.right - m, inner.top + m, inner.left + m, inner.bottom - m, strokePaint)
        }
        val label = when (st) {
            Engine.PadState.EMPTY -> ""
            Engine.PadState.ONESHOT -> String.format(Locale.GERMANY, "%.1fs", Engine.loopSeconds(i))
            Engine.PadState.PLAYING -> String.format(Locale.GERMANY, "∞ %.1fs", Engine.loopSeconds(i))
            Engine.PadState.MUTED -> "AUS"
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
            Engine.PadState.OVERDUB -> "DUB"
        }
        if (label.isNotEmpty()) {
            textPaint.textSize = inner.width() * 0.14f
            textPaint.color = alpha(sk.padInk, if (st == Engine.PadState.MUTED) 0.5f else 0.9f)
            canvas.drawText(label, inner.centerX(), inner.bottom - inner.height() * 0.13f, textPaint)
        }
    }

    private fun drawMoreSkin(canvas: Canvas, i: Int) {
        val sk = skin
        val r = moreRects[i]
        val has = Engine.hasLoop(i)
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = sk.btnFill
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(sk.btnOutline, if (has) 1f else 0.6f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        dotPaint.color = if (has) (if (Engine.isOneShot(i)) sk.shot else sk.accent) else alpha(sk.btnText, 0.6f)
        val d = dp(5f)
        for (k in -1..1) canvas.drawCircle(r.centerX() + k * d, r.centerY(), dp(1.6f), dotPaint)
    }

    private fun drawSequencerSkin(canvas: Canvas) {
        val sk = skin
        val r = seqRect
        val rr = dp(10f)
        if (sk.shadow) softShadow(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = sk.btnFill
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = sk.btnOutline
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        val cur = Engine.seqCurrentStep()
        val sel = Engine.seqSelected
        val txt = sk.btnText
        for (k in 0 until Engine.STEPS) {
            val s = stepRects[k]
            val on = sel >= 0 && Engine.seqHas(sel, k)
            val count = Engine.seqCount(k)
            val playing = k == cur
            fillPaint.color = when {
                on -> alpha(sk.accent, 0.28f)
                playing -> alpha(txt, 0.3f)
                else -> alpha(txt, if (k % 4 == 0) 0.12f else 0.06f)
            }
            canvas.drawRoundRect(s, dp(3f), dp(3f), fillPaint)
            if (on) {
                fillPaint.color = sk.accent
                tmpRect.set(s.left, s.bottom - s.height() * Engine.seqVel(sel, k).coerceIn(0.08f, 1f), s.right, s.bottom)
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            }
            if (sel >= 0 && Engine.noteAt(sel, k)) {
                dotPaint.color = if (light) Color.BLACK else Color.WHITE
                canvas.drawCircle(s.centerX(), s.top + dp(5f), dp(2.6f), dotPaint)
            }
            strokePaint.strokeWidth = dp(if (playing) 2f else 1f)
            strokePaint.color = if (playing) txt else alpha(txt, 0.3f)
            canvas.drawRoundRect(s, dp(3f), dp(3f), strokePaint)
            val others = count - (if (on) 1 else 0)
            if (others > 0) {
                dotPaint.color = txt
                for (d in 0 until min(others, 3)) canvas.drawCircle(s.centerX(), s.bottom - dp(4f) - d * dp(5f), dp(1.6f), dotPaint)
            }
        }
        val red = Color.rgb(225, 40, 50)
        val recOn = Engine.seqRecording || Engine.songRecording
        val blink = 0.5f + 0.5f * sin(time * 8f)
        for ((btn, act) in arrayOf(recBtn to recOn, playBtn to Engine.seqPlaying, stopBtn to false)) {
            val brr = dp(6f)
            fillPaint.color = when {
                btn === recBtn && act -> blend(red, Color.WHITE, 0.3f * blink)
                btn === playBtn && act -> sk.accent
                else -> blend(sk.btnFill, sk.btnText, 0.07f)
            }
            canvas.drawRoundRect(btn, brr, brr, fillPaint)
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = sk.btnOutline
            canvas.drawRoundRect(btn, brr, brr, strokePaint)
        }
        val ic = min(recBtn.height(), recBtn.width()) * 0.22f
        dotPaint.color = if (recOn) Color.WHITE else red
        canvas.drawCircle(recBtn.centerX(), recBtn.centerY(), ic, dotPaint)
        val tri = Path()
        tri.moveTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() - ic)
        tri.lineTo(playBtn.centerX() + ic * 1.1f, playBtn.centerY())
        tri.lineTo(playBtn.centerX() - ic * 0.8f, playBtn.centerY() + ic)
        tri.close()
        fillPaint.color = if (Engine.seqPlaying) Color.WHITE else txt
        canvas.drawPath(tri, fillPaint)
        fillPaint.color = txt
        canvas.drawRect(stopBtn.centerX() - ic * 0.9f, stopBtn.centerY() - ic * 0.9f, stopBtn.centerX() + ic * 0.9f, stopBtn.centerY() + ic * 0.9f, fillPaint)
        val ix = stopBtn.right + dp(10f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(11f)
        textPaint.color = if (recOn) red else sk.accent
        val head = when {
            Engine.songRecording -> {
                val t = Engine.songSeconds().toInt()
                String.format(Locale.GERMANY, "● SONG %d:%02d", t / 60, t % 60)
            }
            Engine.seqRecording -> "● REC PATTERN"
            Engine.seqPlaying -> "▶ SEQ"
            else -> "SEQUENZER"
        }
        canvas.drawText(head, ix, recBtn.top + dp(13f), textPaint)
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(txt, 0.7f)
        canvas.drawText(if (sel >= 0) "Pad ${sel + 1}: tippen · ↕ Lautst." else "⚡-Pad antippen", ix, recBtn.top + dp(27f), textPaint)
        canvas.drawText("STOP halten = leeren", ix, recBtn.top + dp(40f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }


    // ================================================================ Effekt-Ziel

    private fun fxTargetLabel(): String {
        var on = 0
        var last = -1
        for (p in 0 until VISIBLE_PADS) if (Engine.padFx[p]) { on++; last = p }
        return when (on) {
            VISIBLE_PADS -> "→ALLE"
            0 -> "→AUS"
            1 -> "→P${last + 1}"
            else -> "→${on}P"
        }
    }

    // ================================================================ Tastatur

    /** REC-Knopf (Sequenzer und Tastatur): Aufnahme beenden oder Auswahl Pattern/Song öffnen. */
    private fun recPressed() {
        when {
            Engine.songRecording -> onSongStopped?.invoke()
            Engine.seqRecording -> {
                Engine.seqToggleRec()
                onPatternStopped?.invoke()
            }
            else -> onRecMenu?.invoke()
        }
    }

    private val kbRec = RectF()
    private val kbPlay = RectF()
    private val kbStop = RectF()
    private val kbEvents = HashMap<Int, Engine.NoteEv>()

    private var kbPad = -1
    private var kbOctave = 0
    private var kbTake = false
    private val kbRect = RectF()
    private val kbKeysRect = RectF()
    private val kbOctDown = RectF()
    private val kbOctUp = RectF()
    private val kbTakeBtn = RectF()
    private val kbClose = RectF()
    private val kbPointers = HashMap<Int, Int>()   // Pointer -> Halbton
    private val kbVoices = HashMap<Int, Int>()     // Pointer -> Stimme
    private val kbHeld = HashSet<Int>()
    private var kbLastSemi = 0

    fun openKeyboard(p: Int) {
        kbPad = p
        kbTake = false
        kbLastSemi = Engine.padSemi[p]
        val top = xyRect.bottom + dp(8f)
        kbRect.set(dp(8f), top, width - dp(8f), height - insetBottom - dp(8f))
        val hh = dp(40f)
        kbClose.set(kbRect.right - dp(8f) - hh, kbRect.top + dp(8f), kbRect.right - dp(8f), kbRect.top + dp(8f) + hh)
        kbOctUp.set(kbClose.left - dp(8f) - hh, kbClose.top, kbClose.left - dp(8f), kbClose.bottom)
        kbOctDown.set(kbOctUp.left - dp(6f) - hh, kbClose.top, kbOctUp.left - dp(6f), kbClose.bottom)
        kbTakeBtn.set(kbRect.left + dp(8f), kbRect.bottom - dp(8f) - dp(38f), kbRect.left + dp(8f) + dp(150f), kbRect.bottom - dp(8f))
        val tb = dp(46f)
        kbStop.set(kbRect.right - dp(8f) - tb, kbTakeBtn.top, kbRect.right - dp(8f), kbTakeBtn.bottom)
        kbPlay.set(kbStop.left - dp(6f) - tb, kbTakeBtn.top, kbStop.left - dp(6f), kbTakeBtn.bottom)
        kbRec.set(kbPlay.left - dp(6f) - tb, kbTakeBtn.top, kbPlay.left - dp(6f), kbTakeBtn.bottom)
        kbKeysRect.set(kbRect.left + dp(8f), kbClose.bottom + dp(10f), kbRect.right - dp(8f), kbTakeBtn.top - dp(16f))
    }

    private fun closeKeyboard() {
        for (v in kbVoices.values) Engine.noteOff(v)
        kbVoices.clear()
        kbPointers.clear()
        kbHeld.clear()
        kbPad = -1
    }

    private val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackOffsets = intArrayOf(1, 3, -1, 6, 8, 10, -1)
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")

    /** Halbton (relativ zum Original) unter einem Punkt, oder null. */
    private fun keyAt(x: Float, y: Float): Int? {
        val r = kbKeysRect
        if (!r.contains(x, y)) return null
        val whites = 14
        val kw = r.width() / whites
        val base = -12 + kbOctave * 12
        val wi = ((x - r.left) / kw).toInt().coerceIn(0, whites - 1)
        if (y < r.top + r.height() * 0.6f) {
            // Schwarze Taste?
            val fx = (x - r.left) / kw
            val left = fx.toInt()
            val frac = fx - left
            val oct = left / 7
            if (frac > 0.62f) {
                val b = blackOffsets[left % 7]
                if (b >= 0) return base + oct * 12 + b
            }
            if (frac < 0.38f && left > 0) {
                val b = blackOffsets[(left - 1) % 7]
                if (b >= 0) return base + ((left - 1) / 7) * 12 + b
            }
        }
        return base + (wi / 7) * 12 + whiteOffsets[wi % 7]
    }

    private fun kbNoteOn(pid: Int, semi: Int) {
        kbPointers[pid] = semi
        kbVoices[pid] = Engine.noteOn(kbPad, semi)
        Engine.recordNoteOn(kbPad, semi)?.let {
            kbEvents[pid] = it
            Engine.seqSelected = kbPad
        }
        kbLastSemi = semi
        if (kbTake) Engine.setPadPitch(kbPad, semi)
        recountHeld()
    }

    private fun kbNoteOff(pid: Int) {
        kbVoices.remove(pid)?.let { Engine.noteOff(it) }
        kbEvents.remove(pid)?.let { Engine.recordNoteOff(it) }
        kbPointers.remove(pid)
        recountHeld()
    }

    private fun recountHeld() {
        kbHeld.clear()
        kbHeld.addAll(kbPointers.values)
    }

    /** true, wenn das Ereignis von der Tastatur verarbeitet wurde. */
    private fun keyboardTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                val x = e.getX(idx)
                val y = e.getY(idx)
                if (!kbRect.contains(x, y)) return false
                when {
                    kbClose.contains(x, y) -> closeKeyboard()
                    kbOctDown.contains(x, y) -> kbOctave = (kbOctave - 1).coerceAtLeast(-2)
                    kbOctUp.contains(x, y) -> kbOctave = (kbOctave + 1).coerceAtMost(2)
                    kbTakeBtn.contains(x, y) -> {
                        kbTake = !kbTake
                        if (kbTake) Engine.setPadPitch(kbPad, kbLastSemi)
                    }
                    kbRec.contains(x, y) -> recPressed()
                    kbPlay.contains(x, y) -> Engine.seqPlay()
                    kbStop.contains(x, y) -> {
                        val wasPatternRec = Engine.seqRecording
                        Engine.seqStop()
                        if (Engine.songRecording) onSongStopped?.invoke()
                        else if (wasPatternRec) onPatternStopped?.invoke()
                    }
                    else -> {
                        val k = keyAt(x, y)
                        if (k != null) {
                            kbNoteOn(id, k)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (pi in 0 until e.pointerCount) {
                    val id = e.getPointerId(pi)
                    val cur = kbPointers[id] ?: continue
                    val k = keyAt(e.getX(pi), e.getY(pi))
                    if (k != null && k != cur) {
                        kbNoteOff(id)
                        kbNoteOn(id, k)
                    }
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (kbPointers.containsKey(id)) {
                    kbNoteOff(id)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                for (id in ArrayList(kbPointers.keys)) kbNoteOff(id)
                return false
            }
        }
        return false
    }

    private fun overlayColors(): IntArray {
        // Panel, Text, Akzent
        return when {
            skinned -> intArrayOf(skin.btnFill, skin.btnText, skin.accent)
            light -> if (theme == COMIC) intArrayOf(Color.WHITE, Color.BLACK, COMIC_MAGENTA) else intArrayOf(R_BG, R_INK, R_ORANGE)
            theme == NEON -> intArrayOf(Color.rgb(14, 12, 24), Color.WHITE, neon(time * 40f))
            else -> intArrayOf(Color.rgb(20, 21, 26), Color.WHITE, ORANGE)
        }
    }

    private fun smallBtn(canvas: Canvas, r: RectF, label: String, active: Boolean, cols: IntArray) {
        val rr = dp(8f)
        fillPaint.shader = null
        fillPaint.color = if (active) cols[2] else blend(cols[0], cols[1], 0.08f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = alpha(cols[1], 0.5f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(12f)
        textPaint.color = if (active) Color.WHITE else cols[1]
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4.5f), textPaint)
    }

    private fun drawKeyboard(canvas: Canvas) {
        val cols = overlayColors()
        val r = kbRect
        val rr = dp(14f)
        softShadowAny(canvas, r, rr)
        fillPaint.shader = null
        fillPaint.color = cols[0]
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = cols[2]
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        // Kopf
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(15f)
        textPaint.color = cols[1]
        canvas.drawText("🎹 Pad ${kbPad + 1}", r.left + dp(14f), kbClose.centerY() - dp(2f), textPaint)
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(cols[1], 0.7f)
        val ps = Engine.padSemi[kbPad]
        canvas.drawText("Tonhöhe Pad: ${if (ps > 0) "+" else ""}$ps · Oktave ${if (kbOctave > 0) "+" else ""}$kbOctave", r.left + dp(14f), kbClose.centerY() + dp(13f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        smallBtn(canvas, kbOctDown, "−", false, cols)
        smallBtn(canvas, kbOctUp, "+", false, cols)
        smallBtn(canvas, kbClose, "✕", false, cols)
        smallBtn(canvas, kbTakeBtn, if (kbTake) "📌 Ton fürs Pad: AN" else "📌 Ton fürs Pad", kbTake, cols)
        val recOn = Engine.seqRecording || Engine.songRecording
        smallBtn(canvas, kbRec, if (Engine.songRecording) "● S" else if (Engine.seqRecording) "● P" else "●", recOn, intArrayOf(cols[0], cols[1], Color.rgb(225, 40, 50)))
        smallBtn(canvas, kbPlay, "▶", Engine.seqPlaying, cols)
        smallBtn(canvas, kbStop, "■", false, cols)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(cols[1], 0.6f)
        val info = when {
            Engine.songRecording -> String.format(Locale.GERMANY, "Song %d:%02d", Engine.songSeconds().toInt() / 60, Engine.songSeconds().toInt() % 60)
            Engine.seqRecording -> "Pattern: Töne werden aufgenommen"
            else -> "Mehrere Finger = Akkord"
        }
        canvas.drawText(info, kbRect.left + dp(14f), kbTakeBtn.top - dp(4f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Tasten
        val k = kbKeysRect
        val whites = 14
        val kw = k.width() / whites
        val base = -12 + kbOctave * 12
        for (wi in 0 until whites) {
            val semi = base + (wi / 7) * 12 + whiteOffsets[wi % 7]
            tmpRect.set(k.left + wi * kw + dp(1f), k.top, k.left + (wi + 1) * kw - dp(1f), k.bottom)
            fillPaint.color = if (kbHeld.contains(semi)) cols[2] else Color.rgb(248, 246, 240)
            canvas.drawRoundRect(tmpRect, dp(5f), dp(5f), fillPaint)
            strokePaint.strokeWidth = dp(1f)
            strokePaint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRoundRect(tmpRect, dp(5f), dp(5f), strokePaint)
            if (wi % 7 == 0) {
                textPaint.textSize = dp(10f)
                textPaint.color = if (kbHeld.contains(semi)) Color.WHITE else Color.rgb(90, 90, 90)
                val octNum = 4 + (semi + 120) / 12 - 10
                canvas.drawText("C$octNum", tmpRect.centerX(), tmpRect.bottom - dp(8f), textPaint)
            }
            if (semi == Engine.padSemi[kbPad]) {
                dotPaint.color = cols[2]
                canvas.drawCircle(tmpRect.centerX(), tmpRect.bottom - dp(24f), dp(3f), dotPaint)
            }
        }
        for (wi in 0 until whites) {
            val b = blackOffsets[wi % 7]
            if (b < 0 || wi == whites - 1) continue
            val semi = base + (wi / 7) * 12 + b
            val cx = k.left + (wi + 1) * kw
            tmpRect.set(cx - kw * 0.32f, k.top, cx + kw * 0.32f, k.top + k.height() * 0.6f)
            fillPaint.color = if (kbHeld.contains(semi)) cols[2] else Color.rgb(28, 28, 32)
            canvas.drawRoundRect(tmpRect, dp(4f), dp(4f), fillPaint)
        }
        // aktueller Ton
        if (kbHeld.isNotEmpty()) {
            val names = kbHeld.sorted().joinToString(" ") { sm -> noteNames[Math.floorMod(sm, 12)] + (4 + Math.floorDiv(sm, 12)) }
            textPaint.textSize = dp(12f)
            textPaint.color = cols[2]
            canvas.drawText(names, r.centerX(), kbClose.bottom + dp(2f), textPaint)
        }
    }

    private fun softShadowAny(canvas: Canvas, r: RectF, rr: Float) {
        fillPaint.shader = null
        for (q in 1..4) {
            fillPaint.color = Color.argb(22, 0, 0, 0)
            val o = dp(q * 2f)
            tmpRect.set(r.left - o * 0.5f, r.top - o * 0.2f, r.right + o * 0.5f, r.bottom + o * 1.2f)
            canvas.drawRoundRect(tmpRect, rr + o, rr + o, fillPaint)
        }
    }

    private fun drawKeyButton(canvas: Canvas, i: Int) {
        val r = keyRects[i]
        if (r.width() <= 0f) return
        val cols = overlayColors()
        val has = Engine.hasLoop(i)
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = if (kbPad == i) cols[2] else blend(cols[0], cols[1], 0.06f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[1], if (has) 0.6f else 0.2f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        // Mini-Klaviatur
        val kw = min(r.width() * 0.5f, dp(22f))
        val kh = r.height() * 0.56f
        val left = r.centerX() - kw / 2f
        val top = r.centerY() - kh / 2f
        fillPaint.color = alpha(Color.WHITE, if (has) 0.95f else 0.35f)
        canvas.drawRect(left, top, left + kw, top + kh, fillPaint)
        strokePaint.color = alpha(Color.BLACK, if (has) 0.7f else 0.3f)
        strokePaint.strokeWidth = dp(0.8f)
        canvas.drawRect(left, top, left + kw, top + kh, strokePaint)
        for (q in 1..3) canvas.drawLine(left + kw * q / 4f, top, left + kw * q / 4f, top + kh, strokePaint)
        fillPaint.color = alpha(Color.BLACK, if (has) 0.85f else 0.35f)
        for (q in intArrayOf(1, 2)) {
            val bx = left + kw * q / 4f
            canvas.drawRect(bx - kw * 0.07f, top, bx + kw * 0.07f, top + kh * 0.58f, fillPaint)
        }
    }

    // ================================================================ Wave-Editor

    private var edPad = -1
    private var edData = FloatArray(0)
    private var edStart = 0
    private var edEnd = 0
    private var edSlices = 1
    private var edBars = 1f
    private var edMsg = ""
    private var edMsgT = 0f
    private val edRect = RectF()
    private val edWave = RectF()
    private val edKnobSlices = RectF()
    private val edKnobBars = RectF()
    private val edBtnPlay = RectF()
    private val edBtnCut = RectF()
    private val edBtnSlice = RectF()
    private val edBtnPads = RectF()
    private val edBtnClose = RectF()
    private var edPeaks = FloatArray(0)
    private var edDrag = 0          // 1 = Start, 2 = Ende, 3 = Slices-Regler, 4 = Takte-Regler
    private var edDragY = 0f
    private var edDragVal = 0f
    private var edPreview = -1
    private val barOptions = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)

    fun openEditor(p: Int) {
        val d = Engine.loopData(p) ?: return
        closeKeyboard()
        edPad = p
        edData = d
        edStart = 0
        edEnd = d.size
        edSlices = max(1, Engine.padSlices(p))
        edBars = if (Engine.padSlices(p) > 1) Engine.padBars(p) else Engine.suggestBars(d.size)
        edMsg = ""
        layoutEditor()
        computePeaks()
    }

    private fun layoutEditor() {
        val m = dp(10f)
        edRect.set(m, insetTop + m, width - m, height - insetBottom - m)
        val hh = dp(40f)
        edBtnClose.set(edRect.right - dp(10f) - hh, edRect.top + dp(10f), edRect.right - dp(10f), edRect.top + dp(10f) + hh)
        edWave.set(edRect.left + dp(12f), edBtnClose.bottom + dp(14f), edRect.right - dp(12f), edBtnClose.bottom + dp(14f) + edRect.height() * 0.38f)
        val ks = dp(96f)
        val ky = edWave.bottom + dp(52f)
        edKnobSlices.set(edRect.centerX() - ks - dp(20f), ky, edRect.centerX() - dp(20f), ky + ks)
        edKnobBars.set(edRect.centerX() + dp(20f), ky, edRect.centerX() + dp(20f) + ks, ky + ks)
        val by = edKnobSlices.bottom + dp(36f)
        val bw = (edRect.width() - dp(24f) - dp(10f)) / 2f
        val bh = dp(46f)
        edBtnPlay.set(edRect.left + dp(12f), by, edRect.left + dp(12f) + bw, by + bh)
        edBtnCut.set(edBtnPlay.right + dp(10f), by, edBtnPlay.right + dp(10f) + bw, by + bh)
        edBtnSlice.set(edBtnPlay.left, by + bh + dp(10f), edBtnPlay.right, by + 2 * bh + dp(10f))
        edBtnPads.set(edBtnCut.left, by + bh + dp(10f), edBtnCut.right, by + 2 * bh + dp(10f))
    }

    private fun computePeaks() {
        val cols = max(1, edWave.width().toInt() / 2)
        edPeaks = FloatArray(cols)
        val d = edData
        if (d.isEmpty()) return
        val per = max(1, d.size / cols)
        for (c in 0 until cols) {
            val a = (c.toLong() * d.size / cols).toInt()
            var mx = 0f
            var i = a
            val e = min(d.size, a + per)
            while (i < e) {
                val v = abs(d[i])
                if (v > mx) mx = v
                i += max(1, per / 64)
            }
            edPeaks[c] = mx
        }
        var norm = 0f
        for (v in edPeaks) if (v > norm) norm = v
        if (norm > 0f) for (c in edPeaks.indices) edPeaks[c] = edPeaks[c] / norm
    }

    private fun xToFrame(x: Float): Int =
        (((x - edWave.left) / edWave.width()).coerceIn(0f, 1f) * edData.size).toInt()

    private fun frameToX(f: Int): Float =
        edWave.left + edWave.width() * (if (edData.isEmpty()) 0f else f.toFloat() / edData.size)

    private fun edToast(m: String) {
        edMsg = m
        edMsgT = 2.5f
    }

    private fun editorTouch(e: MotionEvent) {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx)
                val y = e.getY(idx)
                when {
                    edBtnClose.contains(x, y) -> {
                        if (edPreview >= 0) Engine.noteOff(edPreview)
                        edPad = -1
                    }
                    edWave.contains(x, y) || (y > edWave.bottom && y < edWave.bottom + dp(30f) && x >= edWave.left && x <= edWave.right) -> {
                        val sx = frameToX(edStart)
                        val ex = frameToX(edEnd)
                        edDrag = if (abs(x - sx) <= abs(x - ex)) 1 else 2
                        moveMarker(x)
                    }
                    edKnobSlices.contains(x, y) -> { edDrag = 3; edDragY = y; edDragVal = edSlices.toFloat() }
                    edKnobBars.contains(x, y) -> { edDrag = 4; edDragY = y; edDragVal = barOptions.indexOfFirst { it == edBars }.coerceAtLeast(0).toFloat() }
                    edBtnPlay.contains(x, y) -> {
                        if (edPreview >= 0) Engine.noteOff(edPreview)
                        if (edEnd - edStart > 2) edPreview = Engine.preview(edData.copyOfRange(edStart, edEnd))
                    }
                    edBtnCut.contains(x, y) -> {
                        Engine.applyEdit(edPad, edStart, edEnd, 1, edBars)
                        reloadEditor("Zugeschnitten")
                    }
                    edBtnSlice.contains(x, y) -> {
                        if (Engine.isOneShot(edPad)) {
                            edToast("Slicen geht bei ∞ LOOP-Pads")
                        } else if (edSlices < 2) {
                            edToast("Mit dem SLICES-Regler mind. 2 wählen")
                        } else {
                            Engine.applyEdit(edPad, edStart, edEnd, edSlices, edBars)
                            reloadEditor("$edSlices Slices – folgt jetzt dem Tempo")
                        }
                    }
                    edBtnPads.contains(x, y) -> {
                        val n = max(2, edSlices)
                        val c = Engine.distributeSlices(edPad, edStart, edEnd, n, VISIBLE_PADS)
                        edToast(if (c > 0) "$c Slices als ⚡ auf freie Pads gelegt" else "Keine freien Pads")
                    }
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = e.getX(0)
                val y = e.getY(0)
                when (edDrag) {
                    1, 2 -> moveMarker(x)
                    3 -> edSlices = (edDragVal + (edDragY - y) / dp(12f)).toInt().coerceIn(1, 32)
                    4 -> edBars = barOptions[(edDragVal + (edDragY - y) / dp(30f)).toInt().coerceIn(0, barOptions.size - 1)]
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (edDrag == 1 || edDrag == 2) edBars = Engine.suggestBars(edEnd - edStart)
                edDrag = 0
            }
        }
    }

    private fun moveMarker(x: Float) {
        val f = xToFrame(x)
        val minLen = Engine.sampleRate / 50
        if (edDrag == 1) edStart = f.coerceIn(0, max(0, edEnd - minLen))
        else if (edDrag == 2) edEnd = f.coerceIn(min(edData.size, edStart + minLen), edData.size)
    }

    private fun reloadEditor(msg: String) {
        val d = Engine.loopData(edPad)
        if (d != null) {
            edData = d
            edStart = 0
            edEnd = d.size
            computePeaks()
        }
        edToast(msg)
    }

    private fun drawEditor(canvas: Canvas, dt: Float) {
        if (edRect.width() <= 0f) layoutEditor()
        val cols = overlayColors()
        val acc = cols[2]
        fillPaint.shader = null
        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val rr = dp(16f)
        fillPaint.color = cols[0]
        canvas.drawRoundRect(edRect, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = acc
        canvas.drawRoundRect(edRect, rr, rr, strokePaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(16f)
        textPaint.color = cols[1]
        canvas.drawText("〰 Wave-Editor · Pad ${edPad + 1}", edRect.left + dp(16f), edBtnClose.centerY() + dp(5f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        smallBtn(canvas, edBtnClose, "✕", false, cols)

        // Wellenform
        val w = edWave
        fillPaint.color = Color.rgb(16, 17, 22)
        canvas.drawRoundRect(w, dp(8f), dp(8f), fillPaint)
        val sx = frameToX(edStart)
        val ex = frameToX(edEnd)
        val mid = w.centerY()
        val n = edPeaks.size
        strokePaint.strokeWidth = dp(1.6f)
        for (c in 0 until n) {
            val x = w.left + w.width() * c / n
            val hgt = edPeaks[c] * w.height() * 0.46f
            strokePaint.color = if (x in sx..ex) acc else Color.argb(90, 200, 200, 210)
            canvas.drawLine(x, mid - hgt, x, mid + hgt, strokePaint)
        }
        // Slice-Linien
        if (edSlices > 1) {
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = Color.argb(200, 255, 255, 255)
            for (k in 1 until edSlices) {
                val x = sx + (ex - sx) * k / edSlices
                canvas.drawLine(x, w.top + dp(4f), x, w.bottom - dp(4f), strokePaint)
            }
        }
        // Marker
        for ((x, lab) in arrayOf(sx to "S", ex to "E")) {
            strokePaint.strokeWidth = dp(2.5f)
            strokePaint.color = Color.WHITE
            canvas.drawLine(x, w.top, x, w.bottom, strokePaint)
            val tri = Path()
            tri.moveTo(x, w.bottom)
            tri.lineTo(x - dp(12f), w.bottom + dp(22f))
            tri.lineTo(x + dp(12f), w.bottom + dp(22f))
            tri.close()
            fillPaint.color = acc
            canvas.drawPath(tri, fillPaint)
            textPaint.textSize = dp(10f)
            textPaint.color = Color.WHITE
            canvas.drawText(lab, x, w.bottom + dp(19f), textPaint)
        }
        val secs = (edEnd - edStart).toFloat() / Engine.sampleRate
        textPaint.textSize = dp(11f)
        textPaint.color = alpha(cols[1], 0.8f)
        canvas.drawText(String.format(Locale.GERMANY, "Auswahl %.2fs · Marker an Wellenform ziehen", secs), edRect.centerX(), w.bottom + dp(42f), textPaint)

        // Regler
        editorKnob(canvas, edKnobSlices, (edSlices - 1) / 31f, "SLICES", if (edSlices <= 1) "AUS" else "$edSlices", cols)
        val bl = when (edBars) { 0.25f -> "¼"; 0.5f -> "½"; else -> edBars.toInt().toString() }
        editorKnob(canvas, edKnobBars, barOptions.indexOfFirst { it == edBars }.coerceAtLeast(0) / 5f, "TAKTE", bl, cols)

        smallBtn(canvas, edBtnPlay, "▶ Hören", false, cols)
        smallBtn(canvas, edBtnCut, "✂ Zuschneiden", false, cols)
        smallBtn(canvas, edBtnSlice, "🔪 Slicen (Tempo)", false, cols)
        smallBtn(canvas, edBtnPads, "⇢ Slices auf Pads", false, cols)

        textPaint.textSize = dp(10f)
        textPaint.color = alpha(cols[1], 0.65f)
        val ty = edBtnPads.bottom + dp(22f)
        canvas.drawText("Slicen: Stücke starten auf ihrem Rasterplatz – der Loop", edRect.centerX(), ty, textPaint)
        canvas.drawText("passt sich jedem Tempo an, ohne die Tonhöhe zu ändern.", edRect.centerX(), ty + dp(14f), textPaint)
        if (edMsgT > 0f) {
            edMsgT -= dt
            textPaint.textSize = dp(13f)
            textPaint.color = acc
            canvas.drawText(edMsg, edRect.centerX(), ty + dp(40f), textPaint)
        }
    }

    private fun editorKnob(canvas: Canvas, r: RectF, v: Float, label: String, value: String, cols: IntArray) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.width() * 0.4f
        tmpRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = dp(6f)
        strokePaint.color = alpha(cols[1], 0.15f)
        canvas.drawArc(tmpRect, 135f, 270f, false, strokePaint)
        strokePaint.color = cols[2]
        canvas.drawArc(tmpRect, 135f, 270f * v.coerceIn(0f, 1f), false, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        fillPaint.shader = null
        fillPaint.color = blend(cols[0], cols[1], 0.1f)
        canvas.drawCircle(cx, cy, rad * 0.7f, fillPaint)
        textPaint.textSize = dp(18f)
        textPaint.color = cols[1]
        canvas.drawText(value, cx, cy + dp(6f), textPaint)
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(cols[1], 0.75f)
        canvas.drawText(label, cx, r.bottom + dp(14f), textPaint)
    }

    companion object {
        private val BRAND_ORANGE = Color.rgb(255, 95, 0)

        const val VISIBLE_PADS = 12
        const val RETRO = 4
        const val COMIC = 5
        const val GLASS = 6
        const val PASTEL = 7
        const val WOOD = 8
        const val CREAM = 9
        const val NOIR = 10
        const val GREY = 11
        const val NEON = 0
        const val GLOW = 1
        const val STUDIO = 2
        const val HUD = 3
        val DESIGN_NAMES = arrayOf(
            "NEON – Regenbogen-Farbspektakel",
            "GLOW – Orange Glühen",
            "STUDIO – Grau & Orange",
            "HUD – Tech-Linien",
            "RETRO – Grau, Grün & Orange mit Spektrum",
            "COMIC – Pop-Art",
            "GLAS – Milchglas & Blau",
            "PASTELL – Weiß & Pastell",
            "HOLZ – Walnuss, Messing & Leinen",
            "CREME – Beige & Rändel",
            "NOIR – Schwarz im Walnussrahmen",
            "GRAU – Hellgrau & Rändel"
        )
        private val ORANGE = Color.rgb(255, 106, 16)
        private val AMBER = Color.rgb(255, 196, 40)
        private val MUTE = Color.rgb(105, 108, 118)
        private val GLOW_WIDTHS = floatArrayOf(14f, 7f, 3f, 1.6f)
        private val GLOW_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.45f, 1.0f)
        private val STUDIO_WIDTHS = floatArrayOf(8f, 3f, 1.4f)
        private val STUDIO_ALPHAS = floatArrayOf(0.12f, 0.28f, 1.0f)
        private val HUD_WIDTHS = floatArrayOf(6f, 1.3f)
        private val HUD_ALPHAS = floatArrayOf(0.15f, 1.0f)

        private const val TEX_NONE = 0
        private const val TEX_WOOD = 1
        private const val TEX_WOOD_FRAME = 2
        private const val KN_CAP = 0
        private const val KN_KNURL = 1
        private const val KN_BRASS = 2
        private const val KN_GLASS = 3
        private const val KN_GREYCAP = 4
        private const val XY_GLOW = 0
        private const val XY_GRID = 1
        private const val XY_STITCH = 2
        private const val XY_DIAG = 3
        private const val XY_CROSS = 4
        private const val XY_INSET = 5
        private const val PS_FLAT = 0
        private const val PS_GLASS = 1
        private const val PS_FRAMED = 2
        private const val PS_DOUBLE = 3
        private val WOOD_BASE = Color.parseColor("#5A3522")

        private val SKINS = arrayOf(
            // GLAS
            Skin(Color.parseColor("#F4F7FB"), Color.parseColor("#E2E9F2"), TEX_NONE, Color.argb(130, 255, 255, 255), Color.parseColor("#C3D5EA"),
                Color.parseColor("#3A4A5E"), Color.parseColor("#3A4A5E"), Color.parseColor("#3D8BFF"), Color.parseColor("#00B3D6"),
                KN_GLASS, Color.parseColor("#F7F9FC"), Color.parseColor("#D9E0E8"), Color.parseColor("#3D8BFF"), Color.parseColor("#3A4A5E"),
                XY_GLOW, Color.parseColor("#A9BBD6"), Color.parseColor("#6A84B2"), Color.parseColor("#CDE6FF"), 16, 14, Color.WHITE, Color.parseColor("#BFE3FF"),
                PS_GLASS, Color.parseColor("#EEF3F9"), null, Color.parseColor("#C6D4E6"), 0.10f, false,
                Color.parseColor("#F3F7FC"), Color.parseColor("#BFD0E4"), Color.parseColor("#3A4A5E"),
                Color.parseColor("#DCE6F2"), Color.parseColor("#2C5AA0"), Color.parseColor("#B5C8DE"), true),
            // PASTELL
            Skin(Color.parseColor("#EFEFEF"), Color.parseColor("#E5E5E5"), TEX_NONE, Color.parseColor("#FBFBFB"), Color.parseColor("#E2E2E2"),
                Color.parseColor("#555555"), Color.parseColor("#3A3A3A"), Color.parseColor("#FF5C8A"), Color.parseColor("#7A5CFF"),
                KN_CAP, Color.parseColor("#FBFBFB"), Color.parseColor("#FBFBFB"), Color.parseColor("#9A9A9A"), Color.parseColor("#555555"),
                XY_GRID, Color.parseColor("#F6F6F6"), Color.parseColor("#F6F6F6"), Color.parseColor("#D2D2D2"), 16, 10, Color.parseColor("#BDBDBD"), Color.parseColor("#FF5C8A"),
                PS_FLAT, Color.parseColor("#EEEEEE"), intArrayOf(Color.parseColor("#FFBFDC"), Color.parseColor("#FFCBB3"), Color.parseColor("#FFEFA3"), Color.parseColor("#BFEBC9"), Color.parseColor("#A8EDE6"), Color.parseColor("#B7DDF0"), Color.parseColor("#DCC6F0"), Color.parseColor("#D3A8F2"), Color.parseColor("#FFB3B8"), Color.parseColor("#D6E8BC"), Color.parseColor("#EADBCB"), Color.parseColor("#CADDF0")), Color.parseColor("#444444"), 0.08f, true,
                Color.parseColor("#FFFFFF"), Color.parseColor("#D0D0D0"), Color.parseColor("#555555"),
                Color.parseColor("#F7F7F7"), Color.parseColor("#444444"), Color.parseColor("#D0D0D0"), true),
            // HOLZ
            Skin(Color.parseColor("#5A3522"), Color.parseColor("#4A2A1A"), TEX_WOOD, Color.TRANSPARENT, Color.TRANSPARENT,
                Color.parseColor("#F0E6D2"), Color.parseColor("#4A3826"), Color.parseColor("#E3B65A"), Color.parseColor("#FF9A3C"),
                KN_BRASS, Color.parseColor("#5C3420"), Color.parseColor("#C9A050"), Color.parseColor("#3A2412"), Color.parseColor("#F0E6D2"),
                XY_STITCH, Color.parseColor("#EAE2D1"), Color.parseColor("#EAE2D1"), Color.parseColor("#BFAE8E"), 4, 4, Color.parseColor("#CDBFA5"), Color.parseColor("#B8862E"),
                PS_FRAMED, Color.parseColor("#ECE5D6"), null, Color.parseColor("#2B1810"), 0.12f, true,
                Color.parseColor("#3A2214"), Color.parseColor("#C9A050"), Color.parseColor("#F0E6D2"),
                Color.parseColor("#1E140C"), Color.parseColor("#E3B65A"), Color.parseColor("#C9A050"), false),
            // CREME
            Skin(Color.parseColor("#EDE7D8"), Color.parseColor("#E5DECC"), TEX_NONE, Color.parseColor("#F2ECDF"), Color.parseColor("#D9D0BC"),
                Color.parseColor("#4A4234"), Color.parseColor("#3D362B"), Color.parseColor("#C8742A"), Color.parseColor("#B8902F"),
                KN_KNURL, Color.parseColor("#EFE8D8"), Color.parseColor("#E2D9C3"), Color.parseColor("#3D362B"), Color.parseColor("#3D362B"),
                XY_DIAG, Color.parseColor("#F1EBDD"), Color.parseColor("#F1EBDD"), Color.parseColor("#8C8370"), 4, 4, Color.parseColor("#3D362B"), Color.parseColor("#C8742A"),
                PS_FLAT, Color.parseColor("#F0E9DA"), null, Color.parseColor("#3D362B"), 0.10f, true,
                Color.parseColor("#F2ECDF"), Color.parseColor("#3D362B"), Color.parseColor("#4A4234"),
                Color.parseColor("#E6DECB"), Color.parseColor("#4A4234"), Color.parseColor("#3D362B"), true),
            // NOIR
            Skin(Color.parseColor("#1C1C1E"), Color.parseColor("#1C1C1E"), TEX_WOOD_FRAME, Color.parseColor("#1C1C1E"), Color.parseColor("#0E0E10"),
                Color.parseColor("#E8E8E8"), Color.parseColor("#E0E0E0"), Color.parseColor("#F5F5F5"), Color.parseColor("#FFB347"),
                KN_CAP, Color.parseColor("#F2F2F2"), Color.parseColor("#F2F2F2"), Color.parseColor("#8E8E8E"), Color.parseColor("#333333"),
                XY_CROSS, Color.parseColor("#212123"), Color.parseColor("#212123"), Color.parseColor("#4E4E52"), 2, 2, Color.parseColor("#0B0B0D"), Color.parseColor("#FFFFFF"),
                PS_FLAT, Color.parseColor("#232325"), null, Color.parseColor("#0B0B0D"), 0.08f, true,
                Color.parseColor("#28282A"), Color.parseColor("#0B0B0D"), Color.parseColor("#E8E8E8"),
                Color.parseColor("#121214"), Color.parseColor("#E8E8E8"), Color.parseColor("#3A3A3C"), false),
            // GRAU
            Skin(Color.parseColor("#E4E4E4"), Color.parseColor("#DADADA"), TEX_NONE, Color.parseColor("#EDEDED"), Color.parseColor("#CFCFCF"),
                Color.parseColor("#4A4A4A"), Color.parseColor("#3A3A3A"), Color.parseColor("#FF6A10"), Color.parseColor("#FFB020"),
                KN_GREYCAP, Color.parseColor("#7B7B7B"), Color.parseColor("#F3F3F3"), Color.parseColor("#EDEDED"), Color.WHITE,
                XY_INSET, Color.parseColor("#ECECEC"), Color.parseColor("#ECECEC"), Color.parseColor("#9A9A9A"), 4, 4, Color.parseColor("#5A5A5A"), Color.parseColor("#FF6A10"),
                PS_DOUBLE, Color.parseColor("#D9D9D9"), null, Color.parseColor("#4A4A4A"), 0.05f, false,
                Color.parseColor("#E2E2E2"), Color.parseColor("#5A5A5A"), Color.parseColor("#4A4A4A"),
                Color.parseColor("#DCDCDC"), Color.parseColor("#3A3A3A"), Color.parseColor("#5A5A5A"), true)
        )
        private val R_BG = Color.rgb(207, 207, 207)
        private val R_GREEN = Color.rgb(24, 200, 75)
        private val R_ORANGE = Color.rgb(255, 115, 10)
        private val R_INK = Color.rgb(16, 110, 45)
        private val R_SCREEN = Color.rgb(22, 22, 22)
        private val R_LCD = Color.rgb(34, 224, 90)
        private val COMIC_MAGENTA = Color.rgb(230, 0, 126)
        private val COMIC_PADS = intArrayOf(
            Color.rgb(232, 32, 42), Color.rgb(30, 144, 232), Color.rgb(255, 122, 0), Color.rgb(255, 212, 0),
            Color.rgb(255, 122, 0), Color.rgb(255, 212, 0), Color.rgb(232, 32, 42), Color.rgb(30, 144, 232),
            Color.rgb(30, 144, 232), Color.rgb(255, 122, 0), Color.rgb(255, 212, 0), Color.rgb(232, 32, 42)
        )
        private val FX_CODES = arrayOf(
            "LP", "HP", "BP", "NT", "EQ", "PK", "CMB", "DLY", "STU", "FLG", "CHO", "REV", "HAL", "VOC", "DST", "BIT",
            "PHS", "TRM", "VIB", "EQ3", "FLT", "SAT", "OD", "GDL", "PIT", "STR", "SMR", "FRZ", "RNG", "FM", "SHF", "FSH",
            "RES", "GRN", "SQF", "MFX", "KRS"
        )
        private val NEON_WIDTHS = floatArrayOf(18f, 10f, 5f, 2.2f)
        private val NEON_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.50f, 1.0f)
    }
}
