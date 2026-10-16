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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    var onPadMenu: ((Int) -> Unit)? = null
    private val fxBtn = RectF()
    private val xyRect = RectF()
    private var knobSize = 0f
    private val pointerKnob = HashMap<Int, Int>()
    private val knobStartY = HashMap<Int, Float>()
    private val knobStartVal = HashMap<Int, Float>()
    private var xyPointer = -1
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
    var onRecMenu: (() -> Unit)? = null
    var onSongStopped: (() -> Unit)? = null
    private val displayRect = RectF()
    var onDesignMenu: (() -> Unit)? = null

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
            else -> cell * 0.17f
        }
        for (i in 0 until Engine.PADS) {
            if (i >= VISIBLE_PADS) {
                padRects[i].setEmpty()
                moreRects[i].setEmpty()
                continue
            }
            val row = i / 4
            val col = i % 4
            val x = left + col * (cell + colGap)
            val y = gridTop + row * (cell + mb + rowGap)
            padRects[i].set(x, y, x + cell, y + cell)
            moreRects[i].set(x + cell * 0.22f, y + cell + dp(3f), x + cell * 0.78f, y + cell + mb - dp(1f))
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
        drawXy(canvas, dt)
        for (i in 0 until VISIBLE_PADS) {
            drawPad(canvas, i, dt)
            drawMore(canvas, i)
        }
        drawSequencer(canvas)
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        drawFooter(canvas)
        message?.let { drawMessage(canvas, it) }

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas) {
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
        val touching = xyPointer >= 0
        textPaint.textAlign = Paint.Align.LEFT
        // Zeile 1: Nummer + Name
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(txt, 0.7f)
        val num = String.format(Locale.GERMANY, "FX %02d/%d", type + 1, Fx.COUNT)
        canvas.drawText(num, r.left + pad, r.top + dp(13f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = if (touching) txt else alpha(txt, 0.35f)
        canvas.drawText(if (touching) "● AKTIV" else "○ BEREIT", r.right - pad, r.top + dp(13f), textPaint)
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
        // Palette-Symbol: vier Farbpunkte
        val d = r.width() * 0.17f
        val cols = if (theme == NEON) intArrayOf(neon(0f), neon(120f), neon(220f), neon(300f))
        else intArrayOf(ORANGE, Color.WHITE, AMBER, Color.rgb(120, 125, 135))
        var k = 0
        for (yy in -1..1 step 2) for (xx in -1..1 step 2) {
            dotPaint.color = cols[k++]
            canvas.drawCircle(r.centerX() + xx * d, r.centerY() + yy * d, d * 0.62f, dotPaint)
        }
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, active: Boolean, blink: Float) {
        if (r.width() <= 0f) return
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
    private fun padAccent(i: Int): Int = if (theme == NEON) {
        if (Engine.isOneShot(i)) neon(15f + 25f * sin(time * 1.3f + i * 0.9f)) else neon(hue(i))
    } else {
        if (Engine.isOneShot(i)) AMBER else ORANGE
    }

    private fun drawPad(canvas: Canvas, i: Int, dt: Float) {
        val r = padRects[i]
        if (r.width() <= 0f) return
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
            Engine.PadState.MUTED -> "STUMM"
            Engine.PadState.RECORDING -> String.format(Locale.GERMANY, "REC %.1f", Engine.recordingSeconds(i))
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
                on -> alpha(sc, if (playing) 1f else 0.8f)
                playing -> alpha(Color.WHITE, 0.35f)
                else -> alpha(Color.WHITE, if (k % 4 == 0) 0.10f else 0.05f)
            }
            canvas.drawRoundRect(s, dp(3f), dp(3f), fillPaint)
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
        val sub = if (sel >= 0) "Pad ${sel + 1}: Schritte tippen" else "⚡-Pad antippen"
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

    private fun drawFooter(canvas: Canvas) {
        val w = width.toFloat()
        val y = height - insetBottom - dp(22f)
        textPaint.textSize = dp(11f)
        if (clearMode) {
            textPaint.color = Color.rgb(255, 80, 100)
            canvas.drawText("Tippe auf ein Pad, um es zu löschen", w / 2f, y, textPaint)
        } else {
            textPaint.color = alpha(Color.WHITE, 0.5f)
            canvas.drawText("Halten = Aufnahme · Overdub · 2× = Stumm · ⚡ = One Shot", w / 2f, y, textPaint)
        }
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(BRAND_ORANGE, 0.8f)
        canvas.drawText("© marween", w / 2f, height - insetBottom - dp(6f), textPaint)
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
                else -> knobHud(canvas, cx, cy, rad, v, ang, active)
            }
            // Beschriftung
            textPaint.textSize = dp(10f)
            textPaint.color = alpha(Color.WHITE, if (active) 1f else 0.7f)
            canvas.drawText(knobLabel(k), cx, r.bottom - dp(1f), textPaint)
            val step = Fx.stepLabel(Engine.fxType, k, v)
            if (active || step != null) {
                textPaint.textSize = dp(if (step != null && step.length > 3) 9f else 11f)
                textPaint.color = Color.WHITE
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
        val touching = xyPointer >= 0
        xyGlow += ((if (touching) 1f else 0f) - xyGlow) * min(1f, dt * 10f)
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
        canvas.drawText("↑ Y: ${Fx.Y_LABELS[Engine.fxType]}", r.left + dp(12f), r.top + dp(18f), textPaint)
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
                if (designBtn.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDesignMenu?.invoke()
                    return true
                }
                if (displayRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onFxList?.invoke()
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
                            when {
                                Engine.songRecording -> onSongStopped?.invoke()
                                Engine.seqRecording -> Engine.seqToggleRec()
                                else -> onRecMenu?.invoke()
                            }
                            burst(x, y, Color.rgb(255, 45, 70), 18, 220f)
                        }
                        playBtn.contains(x, y) -> {
                            Engine.seqPlay()
                            burst(x, y, if (theme == NEON) Color.rgb(60, 230, 120) else ORANGE, 18, 220f)
                        }
                        stopBtn.contains(x, y) -> {
                            Engine.seqStop()
                            if (Engine.songRecording) onSongStopped?.invoke()
                            stopPointer = id
                            stopDownMs = SystemClock.uptimeMillis()
                        }
                        else -> {
                            for (k in 0 until Engine.STEPS) {
                                val sr = stepRects[k]
                                if (x >= sr.left - dp(2f) && x <= sr.right + dp(2f) && y >= sr.top - dp(6f) && y <= sr.bottom + dp(6f)) {
                                    if (Engine.seqSelected >= 0) {
                                        Engine.seqToggle(Engine.seqSelected, k)
                                        burst(sr.centerX(), sr.centerY(), padAccent(Engine.seqSelected), 8, 140f)
                                    }
                                    break
                                }
                            }
                        }
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (xyRect.contains(x, y)) {
                    if (xyPointer < 0) {
                        xyPointer = id
                        setXy(x, y)
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
                    val k = pointerKnob[id] ?: continue
                    val sy0 = knobStartY[id] ?: continue
                    val sv = knobStartVal[id] ?: continue
                    Engine.knobs[k] = (sv + (sy0 - e.getY(pi)) / dp(180f)).coerceIn(0f, 1f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val upId = e.getPointerId(idx)
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
                    Engine.fxTouch = false
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
                Engine.fxTouch = false
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
                val now = SystemClock.uptimeMillis()
                if (now - lastTap[p] < 350L) {
                    Engine.toggleMute(p)
                    lastTap[p] = 0L
                    flash[p] = 1f
                    burst(x, y, neon(hue(p)), 18, 200f)
                } else {
                    lastTap[p] = now
                }
            }
        } else {
            val c = neon(hue(p))
            shocks.add(Shock(RectF(padRects[p]), 0f, c))
            burst(padRects[p].centerX(), padRects[p].centerY(), c, 44, 380f)
            flash[p] = 1f
        }
    }

    companion object {
        private val BRAND_ORANGE = Color.rgb(255, 95, 0)

        const val VISIBLE_PADS = 12
        const val NEON = 0
        const val GLOW = 1
        const val STUDIO = 2
        const val HUD = 3
        val DESIGN_NAMES = arrayOf(
            "NEON – Regenbogen-Farbspektakel",
            "GLOW – Orange Glühen",
            "STUDIO – Grau & Orange",
            "HUD – Tech-Linien"
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
        private val FX_CODES = arrayOf("LP", "HP", "BP", "NT", "EQ", "PK", "CMB", "DLY", "STU", "FLG", "CHO", "REV", "HAL", "VOC", "DST", "BIT")
        private val NEON_WIDTHS = floatArrayOf(18f, 10f, 5f, 2.2f)
        private val NEON_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.50f, 1.0f)
    }
}
