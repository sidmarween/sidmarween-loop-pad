package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
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
        val top = insetTop + dp(84f)
        val bottom = h - insetBottom - dp(64f)
        val size = min(w - 2 * margin, bottom - top)
        val left = (w - size) / 2f
        val gridTop = top + (bottom - top - size) / 2f
        val gap = dp(12f)
        val cell = (size - 3 * gap) / 4f
        radius = cell * 0.18f
        for (i in 0 until Engine.PADS) {
            val row = i / 4
            val col = i % 4
            val x = left + col * (cell + gap)
            val y = gridTop + row * (cell + gap)
            padRects[i].set(x, y, x + cell, y + cell)
            val path = Path()
            path.addRoundRect(padRects[i], radius, radius, Path.Direction.CW)
            padMeasures[i].setPath(path, true)
            padLengths[i] = padMeasures[i].length
        }
        val by = insetTop + dp(22f)
        val bh = dp(40f)
        val bw = dp(104f)
        clearBtn.set(w - margin - bw, by, w - margin, by + bh)
        muteBtn.set(clearBtn.left - dp(10f) - bw, by, clearBtn.left - dp(10f), by + bh)
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
        for (i in 0 until Engine.PADS) drawPad(canvas, i, dt)
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        drawFooter(canvas)
        message?.let { drawMessage(canvas, it) }

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.rgb(4, 3, 10))
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
        val c = neon(time * 40f)
        titlePaint.textSize = dp(26f)
        titlePaint.color = c
        titlePaint.setShadowLayer(dp(14f), 0f, 0f, c)
        val baseline = clearBtn.centerY() + dp(9f)
        canvas.drawText("LOOP PAD", margin, baseline, titlePaint)
        titlePaint.setShadowLayer(0f, 0f, 0f, 0)
        titlePaint.color = alpha(Color.WHITE, 0.85f)
        canvas.drawText("LOOP PAD", margin, baseline, titlePaint)

        // Buttons
        val blink = 0.5f + 0.5f * sin(time * 10f)
        drawButton(canvas, clearBtn, "CLEAR", if (clearMode) Color.rgb(255, 40, 70) else neon(340f, 0.6f, 1f), clearMode, blink)
        val muteLabel = if (Engine.anyPlaying()) "ALLE STUMM" else "ALLE AN"
        drawButton(canvas, muteBtn, muteLabel, neon(time * 40f + 180f), false, 0f)

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
            val bc = neon(time * 40f + 90f)
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
        val y = height - insetBottom - dp(36f)
        textPaint.textSize = dp(11.5f)
        if (clearMode) {
            textPaint.color = Color.rgb(255, 80, 100)
            canvas.drawText("Tippe auf ein Pad, um es zu löschen", w / 2f, y, textPaint)
        } else {
            textPaint.color = alpha(Color.WHITE, 0.55f)
            canvas.drawText("Halten = Aufnahme · Halten auf Loop = Overdub", w / 2f, y, textPaint)
            canvas.drawText("2× tippen = Stumm / An", w / 2f, y + dp(16f), textPaint)
        }
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(neon(time * 40f), 0.7f)
        canvas.drawText("© marween", w / 2f, height - insetBottom - dp(4f), textPaint)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val p = pointerPad.remove(e.getPointerId(idx)) ?: return true
                release(p, e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
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
        private val NEON_WIDTHS = floatArrayOf(18f, 10f, 5f, 2.2f)
        private val NEON_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.50f, 1.0f)
    }
}
