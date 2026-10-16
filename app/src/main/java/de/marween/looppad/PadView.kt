package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
    private var insetTop = 0f
    private var insetBottom = 0f
    private var radius = 0f

    private val flash = FloatArray(Engine.PADS)
    private val pointerPad = HashMap<Int, Int>()
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
        val gap = dp(12f)

        val by = insetTop + dp(16f)
        val bh = dp(38f)
        val bw = dp(104f)
        clearBtn.set(w - margin - bw, by, w - margin, by + bh)
        muteBtn.set(clearBtn.left - dp(10f) - bw, by, clearBtn.left - dp(10f), by + bh)

        val contentTop = clearBtn.bottom + dp(34f)
        val bottom = h - insetBottom - dp(40f)
        val avail = bottom - contentTop
        val inner = w - 2 * margin

        // Reihe: 4 Drehregler + Effekt-Knopf
        val kh = dp(84f)
        val fxW = inner * 0.27f
        val kw = (inner - fxW - gap * 4) / 4f
        knobSize = min(kw, kh - dp(18f))
        for (k in 0 until 4) {
            val x = margin + k * (kw + gap)
            knobRects[k].set(x, contentTop, x + kw, contentTop + kh)
        }
        fxBtn.set(w - margin - fxW, contentTop + dp(4f), w - margin, contentTop + kh - dp(4f))

        // Pads (mit •••-Knopf darunter) und XY-Fläche teilen sich den Rest
        val colGap = dp(10f)
        val rowGap = dp(4f)
        val mb = dp(20f)
        var cell = min((inner - 3 * colGap) / 4f, (avail * 0.58f - 3 * rowGap - 4 * mb) / 4f)
        var gridH = 4 * cell + 4 * mb + 3 * rowGap
        var xyH = avail - kh - gridH - 2 * gap
        val minXy = dp(120f)
        if (xyH < minXy) {
            cell -= (minXy - xyH) / 4f
            gridH = 4 * cell + 4 * mb + 3 * rowGap
            xyH = minXy
        }
        val xyTop = contentTop + kh + gap
        xyRect.set(margin, xyTop, w - margin, xyTop + xyH)

        val gridTop = xyRect.bottom + gap
        val gridW = 4 * cell + 3 * colGap
        val left = (w - gridW) / 2f
        radius = cell * 0.18f
        for (i in 0 until Engine.PADS) {
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
        for (i in 0 until Engine.PADS) {
            drawPad(canvas, i, dt)
            drawMore(canvas, i)
        }
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        drawFooter(canvas)
        message?.let { drawMessage(canvas, it) }

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.rgb(13, 14, 18))
        val lvl = min(1f, Engine.outputLevel * 1.5f)
        val r = max(w, h) * 0.75f
        val cx1 = w * (0.5f + 0.3f * sin(time * 0.31f))
        val cy1 = h * (0.5f + 0.25f * cos(time * 0.23f))
        bgPaint.shader = RadialGradient(cx1, cy1, r, alpha(neon(time * 25f), 0.10f + 0.30f * lvl), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val cx2 = w * (0.5f + 0.35f * cos(time * 0.27f))
        val cy2 = h * (0.5f + 0.3f * sin(time * 0.19f))
        bgPaint.shader = RadialGradient(cx2, cy2, r * 0.8f, alpha(neon(time * 25f + 160f), 0.08f + 0.25f * lvl), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
    }

    private fun drawTopBar(canvas: Canvas) {
        val margin = dp(16f)
        // Logo (Marv PAD) links in der Kopfzeile, Höhe wie die Buttons
        val lh = clearBtn.height() + dp(6f)
        val lw = logo.width * lh / logo.height
        val maxW = muteBtn.left - margin - dp(8f)
        val scale = if (lw > maxW) maxW / lw else 1f
        logoDst.set(margin, clearBtn.centerY() - lh * scale / 2f, margin + lw * scale, clearBtn.centerY() + lh * scale / 2f)
        canvas.drawBitmap(logo, null, logoDst, logoPaint)

        // Buttons
        val blink = 0.5f + 0.5f * sin(time * 10f)
        drawButton(canvas, clearBtn, "CLEAR", if (clearMode) Color.rgb(255, 40, 70) else BRAND_ORANGE, clearMode, blink)
        val muteLabel = if (Engine.anyPlaying()) "ALLE STUMM" else "ALLE AN"
        drawButton(canvas, muteBtn, muteLabel, Color.WHITE, false, 0f)

        // Taktgeber-Leiste: zeigt, wo im Raster alle Loops gerade sind
        val mp = Engine.masterProgress()
        val y = clearBtn.bottom + dp(14f)
        val left = margin
        val right = width - margin
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = alpha(Color.WHITE, 0.12f)
        canvas.drawLine(left, y, right, y, strokePaint)
        if (mp >= 0f) {
            val x = left + (right - left) * mp
            val beat = if (mp < 0.05f) 1f - mp / 0.05f else 0f
            val bc = BRAND_ORANGE
            strokePaint.strokeWidth = dp(8f)
            strokePaint.color = alpha(bc, 0.2f + 0.4f * beat)
            canvas.drawLine(left, y, x, y, strokePaint)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = bc
            canvas.drawLine(left, y, x, y, strokePaint)
            dotPaint.color = Color.WHITE
            canvas.drawCircle(x, y, dp(4f) + dp(4f) * beat, dotPaint)
            textPaint.textSize = dp(10f)
            textPaint.color = alpha(Color.WHITE, 0.6f)
            canvas.drawText(String.format(Locale.GERMANY, "TAKT %.2fs", Engine.masterSeconds()), width / 2f, y + dp(14f), textPaint)
        }
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, color: Int, active: Boolean, blink: Float) {
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = alpha(color, if (active) 0.35f + 0.4f * blink else 0.08f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(7f)
        strokePaint.color = alpha(color, 0.25f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        strokePaint.strokeWidth = dp(1.8f)
        strokePaint.color = color
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(13f)
        textPaint.color = if (active) Color.WHITE else color
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4.5f), textPaint)
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

        var color = neon(h)
        var glow = 0f
        var fillA = 0f
        var progress = -1f
        when (st) {
            Engine.PadState.EMPTY -> {
                glow = 0.16f + 0.10f * pulse
                fillA = 0.05f
            }
            Engine.PadState.PLAYING -> {
                progress = Engine.progress(i)
                if (progress < 0.06f) flash[i] = max(flash[i], 0.45f * (1f - progress / 0.06f))
                glow = 0.55f + 0.45f * lvl
                fillA = 0.16f + 0.55f * lvl
            }
            Engine.PadState.MUTED -> {
                progress = Engine.progress(i)
                color = neon(h, 0.35f, 0.55f)
                glow = 0.14f
                fillA = 0.03f
            }
            Engine.PadState.RECORDING -> {
                val blink = 0.5f + 0.5f * sin(time * 14f)
                color = neon(345f + 25f * blink)
                glow = 0.75f + 0.25f * inLvl
                fillA = 0.25f + 0.55f * inLvl
            }
            Engine.PadState.OVERDUB -> {
                progress = Engine.progress(i)
                color = blend(neon(h), Color.WHITE, 0.35f + 0.3f * sin(time * 22f))
                glow = 1f
                fillA = 0.35f + 0.45f * inLvl
            }
        }
        glow = min(1.25f, glow + flash[i])
        fillA = min(1f, fillA + flash[i] * 0.6f)

        // Innenleuchten
        fillPaint.shader = RadialGradient(
            r.centerX(), r.centerY(), r.width() * 0.8f,
            alpha(color, fillA), alpha(color, fillA * 0.12f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null

        // Neon-Rand in mehreren Schichten
        val widths = NEON_WIDTHS
        val alphas = NEON_ALPHAS
        for (k in widths.indices) {
            strokePaint.strokeWidth = dp(widths[k])
            strokePaint.color = alpha(color, glow * alphas[k])
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }
        if (glow > 0.5f) {
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = alpha(Color.WHITE, (glow - 0.5f) * 1.2f)
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }

        // Kometen-Lauflicht = Abspielposition
        if (progress >= 0f && padLengths[i] > 0f) {
            val len = padLengths[i]
            val faded = st == Engine.PadState.MUTED
            for (j in 0 until 14) {
                var p = progress - j * 0.011f
                if (p < 0f) p += 1f
                padMeasures[i].getPosTan(p * len, pos, null)
                val k = 1f - j / 14f
                dotPaint.color = alpha(if (j == 0) Color.WHITE else color, k * (if (faded) 0.35f else 1f))
                canvas.drawCircle(pos[0], pos[1], dp(5.5f) * k + dp(1f), dotPaint)
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
            Engine.PadState.EMPTY -> ""
            Engine.PadState.PLAYING -> String.format(Locale.GERMANY, "%.1fs", Engine.loopSeconds(i))
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
        val c = if (has) BRAND_ORANGE else Color.WHITE
        val rr = r.height() / 2f
        fillPaint.shader = null
        fillPaint.color = alpha(c, if (has) 0.16f else 0.05f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(c, if (has) 0.8f else 0.25f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        dotPaint.color = alpha(c, if (has) 1f else 0.5f)
        val d = dp(5f)
        for (k in -1..1) canvas.drawCircle(r.centerX() + k * d, r.centerY(), dp(1.6f), dotPaint)
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
            canvas.drawText("Halten = Aufnahme · Loop halten = Overdub · 2× = Stumm", w / 2f, y, textPaint)
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
            val c = neon(Fx.HUES[Engine.fxType] + k * 35f + time * 20f)
            val cx = r.centerX()
            val cy = r.top + knobSize / 2f + dp(2f)
            val rad = knobSize * 0.40f
            tmpRect.set(cx - rad, cy - rad, cx + rad, cy + rad)

            // Knopfkörper
            fillPaint.shader = RadialGradient(cx, cy - rad * 0.3f, rad, Color.rgb(40, 36, 60), Color.rgb(10, 8, 18), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, rad * 0.78f, fillPaint)
            fillPaint.shader = null

            // Skala
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(Color.WHITE, 0.12f)
            canvas.drawArc(tmpRect, 135f, 270f, false, strokePaint)
            val glow = if (active) 1f else 0.7f
            strokePaint.strokeWidth = dp(11f)
            strokePaint.color = alpha(c, 0.18f * glow)
            canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(c, glow)
            canvas.drawArc(tmpRect, 135f, 270f * v, false, strokePaint)
            strokePaint.strokeCap = Paint.Cap.BUTT

            // Zeiger
            val ang = Math.toRadians((135.0 + 270.0 * v)).toFloat()
            val px = cx + cos(ang) * rad * 0.62f
            val py = cy + sin(ang) * rad * 0.62f
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = Color.WHITE
            canvas.drawLine(cx + cos(ang) * rad * 0.2f, cy + sin(ang) * rad * 0.2f, px, py, strokePaint)
            dotPaint.color = c
            canvas.drawCircle(px, py, dp(3.5f), dotPaint)

            // Beschriftung
            textPaint.textSize = dp(10f)
            textPaint.color = alpha(Color.WHITE, if (active) 1f else 0.7f)
            canvas.drawText(knobLabel(k), cx, r.bottom - dp(2f), textPaint)
            val step = Fx.stepLabel(Engine.fxType, k, v)
            if (active || step != null) {
                textPaint.textSize = dp(if (step != null && step.length > 3) 9f else 11f)
                textPaint.color = Color.WHITE
                canvas.drawText(step ?: "${(v * 100).toInt()}", cx, cy + dp(4f), textPaint)
            }
        }
    }

    private fun drawFxButton(canvas: Canvas, dt: Float) {
        val r = fxBtn
        if (r.width() <= 0f) return
        fxFlash = max(0f, fxFlash - dt * 2f)
        val c = neon(Fx.HUES[Engine.fxType] + 15f * sin(time * 1.5f))
        val rr = dp(16f)
        val g = 0.6f + 0.4f * fxFlash + 0.2f * xyGlow
        fillPaint.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.8f, alpha(c, 0.25f + 0.5f * fxFlash), alpha(c, 0.03f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        fillPaint.shader = null
        for (k in NEON_WIDTHS.indices) {
            strokePaint.strokeWidth = dp(NEON_WIDTHS[k]) * 0.7f
            strokePaint.color = alpha(c, g * NEON_ALPHAS[k])
            canvas.drawRoundRect(r, rr, rr, strokePaint)
        }
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(Color.WHITE, 0.6f)
        canvas.drawText("◂ EFFEKT ▸", r.centerX(), r.top + dp(20f), textPaint)
        textPaint.textSize = min(dp(17f), r.width() / 6.2f)
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(dp(10f), 0f, 0f, c)
        canvas.drawText(Fx.NAMES[Engine.fxType], r.centerX(), r.centerY() + dp(10f), textPaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(c, 0.8f)
        canvas.drawText("${Engine.fxType + 1}/${Fx.COUNT}", r.centerX(), r.bottom - dp(8f), textPaint)
    }

    private fun drawXy(canvas: Canvas, dt: Float) {
        val r = xyRect
        if (r.width() <= 0f) return
        val touching = xyPointer >= 0
        xyGlow += ((if (touching) 1f else 0f) - xyGlow) * min(1f, dt * 10f)
        val c = neon(Fx.HUES[Engine.fxType] + 20f * sin(time * 0.8f))
        val c2 = neon(Fx.HUES[Engine.fxType] + 120f + time * 30f)
        val rr = dp(20f)
        val lvl = min(1f, Engine.outputLevel * 1.5f)

        // Fläche
        fillPaint.shader = RadialGradient(
            r.left + r.width() * xyDispX, r.bottom - r.height() * xyDispY, max(r.width(), r.height()) * 0.9f,
            alpha(c, 0.10f + 0.30f * xyGlow + 0.15f * lvl), alpha(c2, 0.03f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        fillPaint.shader = null

        // Raster
        canvas.save()
        canvas.clipRect(r)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(c, 0.10f + 0.12f * xyGlow)
        for (k in 1 until 8) {
            val x = r.left + r.width() * k / 8f
            canvas.drawLine(x, r.top, x, r.bottom, strokePaint)
            val y = r.top + r.height() * k / 8f
            canvas.drawLine(r.left, y, r.right, y, strokePaint)
        }
        // Wasserzeichen
        textPaint.textSize = r.height() * 0.28f
        textPaint.color = alpha(c, 0.06f + 0.06f * xyGlow)
        canvas.drawText(Fx.NAMES[Engine.fxType], r.centerX(), r.centerY() + r.height() * 0.1f, textPaint)

        // Fadenkreuz und Finger
        val fx = r.left + r.width() * xyDispX
        val fy = r.bottom - r.height() * xyDispY
        val a = 0.25f + 0.75f * xyGlow
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = alpha(c, 0.5f * a)
        canvas.drawLine(r.left, fy, r.right, fy, strokePaint)
        canvas.drawLine(fx, r.top, fx, r.bottom, strokePaint)
        val pr = dp(14f) + dp(16f) * lvl * xyGlow
        dotPaint.color = alpha(c, 0.15f * a)
        canvas.drawCircle(fx, fy, pr * 2.2f, dotPaint)
        dotPaint.color = alpha(c, 0.35f * a)
        canvas.drawCircle(fx, fy, pr * 1.3f, dotPaint)
        dotPaint.color = alpha(Color.WHITE, 0.9f * a)
        canvas.drawCircle(fx, fy, pr * 0.45f, dotPaint)
        if (touching) {
            val ring = (time * 2f) % 1f
            strokePaint.strokeWidth = dp(2f) * (1f - ring)
            strokePaint.color = alpha(c2, 1f - ring)
            canvas.drawCircle(fx, fy, pr + dp(50f) * ring, strokePaint)
            if (Random.nextFloat() < 0.6f) burst(fx, fy, if (Random.nextBoolean()) c else c2, 2, 120f + 300f * lvl)
        }
        canvas.restore()

        // Rahmen
        for (k in NEON_WIDTHS.indices) {
            strokePaint.strokeWidth = dp(NEON_WIDTHS[k]) * 0.8f
            strokePaint.color = alpha(c, (0.45f + 0.55f * xyGlow) * NEON_ALPHAS[k])
            canvas.drawRoundRect(r, rr, rr, strokePaint)
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
        for (i in 0 until Engine.PADS) if (padRects[i].contains(x, y)) return i
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
                if (xyRect.contains(x, y)) {
                    if (xyPointer < 0) {
                        xyPointer = id
                        setXy(x, y)
                        Engine.fxTouch = true
                        burst(x, y, neon(Fx.HUES[Engine.fxType]), 20, 240f)
                    }
                    return true
                }
                for (i in 0 until Engine.PADS) {
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
                        burst(r.centerX(), r.centerY(), neon(hue(p)), 40, 300f)
                        shocks.add(Shock(RectF(r), 0f, Color.rgb(255, 40, 70)))
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    clearMode = false
                    return true
                }
                if (pointerPad.containsValue(p)) return true
                pointerPad[id] = p
                Engine.padDown(p)
                flash[p] = 1f
                burst(x, y, neon(hue(p)), 22, 260f)
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
                release(p, e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                fxPointer = -1
                removeCallbacks(fxLongPress)
                xyPointer = -1
                Engine.fxTouch = false
                pointerKnob.clear()
                val held = ArrayList(pointerPad.values)
                pointerPad.clear()
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
        private val NEON_WIDTHS = floatArrayOf(18f, 10f, 5f, 2.2f)
        private val NEON_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.50f, 1.0f)
    }
}
