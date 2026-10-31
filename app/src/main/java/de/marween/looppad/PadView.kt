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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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
    private val knobRects = Array(5) { RectF() }
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
            canvas.drawText(if (on) fxCode(Engine.slotType[s]) else "AUS", c.centerX() + dp(3f), c.centerY() + dp(5f), textPaint)
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

    // ---------------------------------------------------------------- Maschine-Workflow (Funktionsleiste, Szenen, Undo)
    private val funcRects = Array(6) { RectF() }
    private val undoBtn = RectF()
    private val redoBtn = RectF()
    /** Aktiver Pad-Modus: MUTE, SOLO, ERASE oder DUPLICATE (oder keiner). */
    private var func = F_NONE
    /** SZENE: die Pads zeigen und starten Szenen. */
    private var sceneView = false
    private var funcPointer = -1
    private var funcBtn = -1
    private var funcWasOn = false
    private var funcUsed = false
    private var funcLongFired = false
    private var duplSrc = -1
    private var sceneDupSrc = -1
    private var recPointer = -1
    private var recLongFired = false
    private var undoPointer = -1
    private var undoLongFired = false
    private var scenePointer = -1
    private var scenePad = -1
    private var sceneLongFired = false
    var onHint: ((String) -> Unit)? = null
    var onSceneMenu: ((Int) -> Unit)? = null
    var onRecLongMenu: (() -> Unit)? = null

    private fun hint(msg: String) {
        onHint?.invoke(msg)
    }

    private fun funcMode(b: Int): Int = when (b) {
        1 -> F_MUTE
        2 -> F_SOLO
        3 -> F_ERASE
        4 -> F_DUPL
        else -> F_NONE
    }

    private val funcLongPress = Runnable {
        if (funcPointer < 0 || funcUsed) return@Runnable
        funcLongFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when (funcBtn) {
            0 -> {
                Engine.sceneChain = !Engine.sceneChain
                hint(if (Engine.sceneChain) "⛓ Kette an: die Szenen laufen nacheinander (Länge: Szene lange drücken)" else "⛓ Kette aus: die Szene läuft in Schleife")
            }
            1 -> {
                Engine.toggleMuteAll()
                hint(if (Engine.anyPlaying()) "Alle Loops an" else "Alle Loops stumm")
            }
            2 -> {
                Engine.clearSolo()
                hint("Alle SOLO aus")
            }
            5 -> {
                Engine.cycleRepeat()
                Engine.repeatOn = true
                hint("Note Repeat ${Engine.repeatName()}")
            }
            else -> {}
        }
        // Lange gedrückt = nur die Sonderfunktion, kein Modus-Wechsel
        if (funcBtn in 1..4 && !funcWasOn) func = F_NONE
    }

    private fun funcDown(b: Int, id: Int) {
        mixView = false
        funcPointer = id
        funcBtn = b
        funcUsed = false
        funcLongFired = false
        funcWasOn = when (b) {
            0 -> sceneView
            5 -> Engine.repeatOn
            else -> func == funcMode(b)
        }
        when (b) {
            0 -> { if (!sceneView) { sceneView = true; sceneDupSrc = -1 } }
            5 -> {}
            else -> if (func != funcMode(b)) {
                func = funcMode(b)
                duplSrc = -1
            }
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        burst(funcRects[b].centerX(), funcRects[b].centerY(), funcColor(b), 12, 160f)
        postDelayed(funcLongPress, 500L)
    }

    private fun funcUp() {
        removeCallbacks(funcLongPress)
        val b = funcBtn
        funcPointer = -1
        funcBtn = -1
        if (funcLongFired) return
        if (b == 5) {
            Engine.repeatOn = !Engine.repeatOn
            if (!Engine.repeatOn) Engine.repeatAllUp()
            hint(if (Engine.repeatOn) "Note Repeat ${Engine.repeatName()}: ⚡-Pad oder Drum-Pad halten (Rate: REPEAT lange drücken)" else "Note Repeat aus")
            return
        }
        if (funcUsed) {
            // gehalten + benutzt = nur kurz aktiv (wie die Shift-Knöpfe bei Maschine)
            if (!funcWasOn) {
                if (b == 0) sceneView = false else func = F_NONE
            }
            return
        }
        if (funcWasOn) {
            if (b == 0) sceneView = false else func = F_NONE
            duplSrc = -1
            return
        }
        when (b) {
            0 -> hint("SZENE: Pads = Szenen 1–12. Antippen startet die Szene zur nächsten Eins · lange drücken = Länge/Kette")
            1 -> hint("MUTE: Pads antippen schaltet sie stumm/an · MUTE lange drücken = alle")
            2 -> hint("SOLO: Pads antippen – nur Solo-Pads sind zu hören · SOLO lange drücken = alle aus")
            3 -> hint("ERASE: Pad antippen löscht sein Pattern, nochmal das Sample · Schritte antippen leert das Pattern")
            4 -> hint(if (sceneView) "DUPL: Quell-Szene, dann Ziel-Szene antippen" else "DUPL: Quell-Pad, dann Ziel-Pad antippen")
        }
    }

    private fun funcColor(b: Int): Int = when (b) {
        3 -> Color.rgb(255, 50, 70)
        1 -> Color.rgb(90, 160, 255)
        2 -> Color.rgb(255, 200, 40)
        5 -> Color.rgb(60, 230, 160)
        else -> overlayColors()[2]
    }

    private fun funcOn(b: Int): Boolean = when (b) {
        0 -> sceneView
        5 -> Engine.repeatOn
        else -> func == funcMode(b)
    }

    private fun drawFuncRow(canvas: Canvas) {
        if (SEC_FUNC in secHidden) return
        val cols = overlayColors()
        val blink = 0.5f + 0.5f * sin(time * 8f)
        for (b in 0 until 6) {
            val r = funcRects[b]
            if (r.width() <= 0f) continue
            val on = funcOn(b)
            val c = funcColor(b)
            val rr = dp(9f)
            fillPaint.shader = null
            if (on) {
                fillPaint.color = alpha(c, 0.22f + 0.18f * blink)
                tmpRect.set(r.left - dp(3f), r.top - dp(3f), r.right + dp(3f), r.bottom + dp(3f))
                canvas.drawRoundRect(tmpRect, rr + dp(3f), rr + dp(3f), fillPaint)
            }
            fillPaint.color = if (on) c else alpha(cols[0], 0.9f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.3f)
            strokePaint.color = if (on) Color.WHITE else alpha(c, 0.75f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            val main = FUNC_NAMES[b]
            val sub = when (b) {
                0 -> "${Engine.curScene + 1}" + (if (Engine.queuedScene >= 0) "→${Engine.queuedScene + 1}" else "") + if (Engine.sceneChain) " ⛓" else ""
                2 -> if (Engine.soloAny) "● aktiv" else ""
                5 -> Engine.repeatName()
                4 -> if (duplSrc >= 0) "P${duplSrc + 1}→?" else if (sceneDupSrc >= 0) "S${sceneDupSrc + 1}→?" else ""
                else -> ""
            }
            val txt = if (on) (if (light && theme == COMIC) Color.BLACK else Color.WHITE) else cols[1]
            textPaint.color = txt
            textPaint.textSize = min(dp(10.5f), r.width() / (main.length * 0.66f))
            val my = if (sub.isEmpty()) r.centerY() + textPaint.textSize * 0.36f else r.centerY() - dp(1f)
            canvas.drawText(main, r.centerX(), my, textPaint)
            if (sub.isNotEmpty()) {
                textPaint.textSize = min(dp(8.5f), r.width() / max(3f, sub.length * 0.62f))
                textPaint.color = alpha(txt, 0.85f)
                canvas.drawText(sub, r.centerX(), r.centerY() + dp(10f), textPaint)
            }
        }
    }
    private val designBtn = RectF()
    /** Master-Bereich (Mastering-Kette) oben im HUD. */
    private val masterBtn = RectF()
    /** Set-Aufnahme (unter MASTER). */
    private val setRecBtn = RectF()
    var onSetRec: (() -> Unit)? = null
    private val masterPanel = MasterPanel(this).also { mp -> mp.onHint = { hint(it) } }
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
    var onInfo: (() -> Unit)? = null
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
            // Claudes Design: ruhige, gut lesbare Schrift statt Monospace
            textPaint.typeface = if (field == CLAUDE) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.letterSpacing = if (field == CLAUDE) 0.03f else 0f
            layoutPads()
            invalidate()
        }
    private var insetTop = 0f
    private var insetBottom = 0f
    private var radius = 0f

    private val flash = FloatArray(Engine.PADS)
    private val pointerPad = HashMap<Int, Int>()
    private val shotPointers = HashSet<Int>()
    /** Pointer, deren Loop schon beim Berühren gestartet/gestoppt wurde. */
    private val launchedPointers = HashSet<Int>()
    private val lastTap = LongArray(Engine.PADS)
    /** Alter Lösch-Modus = jetzt ERASE in der Funktionsleiste. */
    private var clearMode: Boolean
        get() = func == F_ERASE
        set(v) { func = if (v) F_ERASE else F_NONE }
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
        insetBottom = safeBottom(insets, bottom).toFloat()
        layoutPads()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPads()
    }

    /**
     * Abstand unten: nicht nur die Navigationsleiste, auch der Wisch-Bereich der Android-Gesten (Home/Zurück)
     * plus etwas Luft – sonst landen Knöpfe ganz unten im Gestenbereich und lösen aus Versehen aus.
     */
    private fun safeBottom(insets: WindowInsets, bars: Int): Int {
        var b = bars
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                b = max(b, insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom)
                b = max(b, insets.getInsets(WindowInsets.Type.systemGestures()).bottom)
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                b = max(b, insets.mandatorySystemGestureInsets.bottom)
            }
        } catch (ignored: Throwable) {}
        return b + dp(10f).toInt()
    }

    private fun layoutPads() {
        // Große Ansicht im Querformat: Raster wie im Hochformat berechnen und dann vergrößert zeigen
        val land = focus != 0 && width > height
        val w = if (land) height.toFloat() else width.toFloat()
        val h = if (land) width.toFloat() else height.toFloat()
        if (w <= 0f || h <= 0f) return
        val margin = dp(16f)
        val gap = dp(10f)
        val inner = w - 2 * margin

        // Kopfzeile: Logo | Display | Design-Knopf
        val by = insetTop + dp(8f)
        val topH = dp(64f)
        // Logo schrumpft auf schmalen Handys, damit das Display genug Platz für seine Schrift behält
        val aspect = logo.width.toFloat() / logo.height
        val headAvail = w - 2 * margin - dp(36f + 6f + 46f + 8f + 12f)
        val lh = ((headAvail - dp(122f)) / aspect).coerceIn(dp(28f), dp(44f))
        val lw = aspect * lh
        logoDst.set(margin, by + (topH - lh) / 2f, margin + lw, by + (topH + lh) / 2f)
        val db = dp(36f)
        designBtn.set(w - margin - db, by + (topH - db) / 2f, w - margin, by + (topH + db) / 2f)
        masterBtn.set(designBtn.left - dp(6f) - dp(46f), by + dp(5f), designBtn.left - dp(6f), by + topH / 2f - dp(2f))
        setRecBtn.set(masterBtn.left, by + topH / 2f + dp(2f), masterBtn.right, by + topH - dp(5f))
        displayRect.set(logoDst.right + dp(12f), by + dp(4f), masterBtn.left - dp(8f), by + topH - dp(4f))

        fun off(r: RectF) = r.set(-9999f, -9999f, -9998f, -9998f)
        // Bereiche in der vom Nutzer gewählten Reihenfolge (ausgeblendete fallen weg, der Platz geht an XY/Pads)
        val vis = secOrder.filter { it !in secHidden }
        val kh = dp(80f)
        val rh = dp(46f)
        val seqH = if (seqFold) dp(50f) else dp(84f)
        val selH = dp(34f)
        val tabH = dp(26f)
        val colGap = dp(10f)
        val rowGap = dp(8f)
        val cols = gridCols()
        val rows = gridRows()
        val cellW = (inner - (cols - 1) * colGap) / cols
        val gridW = cols * cellW + (cols - 1) * colGap
        val left = (w - gridW) / 2f
        val top0 = by + topH + dp(8f)
        val bottom = h - insetBottom - dp(36f)
        fun fixedH(id: Int) = when (id) {
            SEC_KNOBS -> kh + dp(4f)
            SEC_FUNC -> rh + gap
            SEC_SEL -> selH
            SEC_SEQ -> seqH + gap
            else -> 0f
        }
        val avail = bottom - top0 - vis.sumOf { fixedH(it).toDouble() }.toFloat()
        val padsOn = SEC_PADS in vis
        val xyOn = SEC_XY in vis
        var cellH: Float
        var xyTot: Float   // Reiter + XY-Fläche + Abstand
        if (padsOn && xyOn) {
            cellH = min(cellW, (avail * 0.72f - gap - (rows - 1) * rowGap) / rows)
            xyTot = avail - (rows * cellH + (rows - 1) * rowGap + gap)
            val minXy = dp(110f) + tabH + dp(5f) + gap
            if (xyTot < minXy) { cellH -= (minXy - xyTot) / rows; xyTot = minXy }
        } else if (padsOn) {
            cellH = min(cellW * 1.3f, (avail - gap - (rows - 1) * rowGap) / rows)
            xyTot = 0f
        } else {
            cellH = 0f
            xyTot = max(dp(140f), avail)
        }
        val gridH = if (padsOn) rows * cellH + (rows - 1) * rowGap + gap else 0f
        // übrig gebliebenen Platz gleichmäßig zwischen die Bereiche verteilen (statt eines Lochs unten)
        val spare = avail - xyTot - gridH
        val spread = if (spare > dp(4f) && vis.isNotEmpty()) spare / vis.size else 0f
        val cell = min(cellW, max(cellH, 1f))
        // erst alles aus, dann die sichtbaren Bereiche der Reihe nach platzieren
        for (r in knobRects) off(r)
        zoomFx.setEmpty()
        off(fxBtn); for (r in funcRects) off(r)
        muteBtn.setEmpty(); clearBtn.setEmpty()
        xyRect.setEmpty(); for (r in modeRects) r.setEmpty(); zoomXy.setEmpty()
        off(selRow); for (r in selBtns) off(r)
        var gridTop = -99999f
        var seqTop = -99999f
        var y = top0
        for (id in vis) {
            when (id) {
                SEC_KNOBS -> {
                    val kw = (inner - 4 * gap) / 5f
                    knobSize = min(kw, kh - dp(16f))
                    for (k in 0 until 5) {
                        val x = margin + k * (kw + gap)
                        knobRects[k].set(x, y, x + kw, y + kh)
                    }
                    val zs = dp(22f)
                    if (zoomEnabled) zoomFx.set(knobRects[4].right - zs, knobRects[4].top, knobRects[4].right, knobRects[4].top + zs)
                }
                SEC_FUNC -> {
                    // Funktionsleiste wie bei Maschine: SZENE · MUTE · SOLO | Effekt | ERASE · DUPL · REPEAT
                    val cx = w / 2f
                    fxBtn.set(cx - rh / 2f, y, cx + rh / 2f, y + rh)
                    val side = (inner - rh) / 2f - dp(10f)
                    val fg = dp(4f)
                    val fw = (side - 2 * fg) / 3f
                    val fh = min(rh, dp(38f))
                    val fy = y + (rh - fh) / 2f
                    for (k in 0 until 3) {
                        val lx = margin + k * (fw + fg)
                        funcRects[k].set(lx, fy, lx + fw, fy + fh)
                        val rx = fxBtn.right + dp(10f) + k * (fw + fg)
                        funcRects[3 + k].set(rx, fy, rx + fw, fy + fh)
                    }
                }
                SEC_XY -> {
                    // Reiter über der XY-Fläche: Effekte | Instrumente (303 · 909 · SYNTH → großer Editor)
                    val w0 = (inner - dp(6f)) * 0.36f
                    modeRects[0].set(margin, y, margin + w0, y + tabH)
                    val zb = if (zoomEnabled) tabH + dp(4f) else 0f
                    modeRects[1].set(modeRects[0].right + dp(6f), y, w - margin - zb, y + tabH)
                    if (zoomEnabled) zoomXy.set(w - margin - tabH, y, w - margin, y + tabH)
                    xyRect.set(margin, y + tabH + dp(5f), w - margin, y + xyTot - gap)
                }
                SEC_SEL -> {
                    // Auswahl-Leiste über den Pads: SELECT · Pad · WAVE · NOTEN · FX · ⋯
                    selRow.set(left, y, left + gridW, y + selH - dp(6f))
                    val g = dp(5f)
                    val paged = gridPages() > 1
                    val ws = if (paged) floatArrayOf(1.35f, 0.9f, 1f, 1f, 0.8f, 0.6f, 0.95f) else floatArrayOf(1.35f, 0.9f, 1f, 1f, 0.8f, 0.6f)
                    val unit = (selRow.width() - (ws.size - 1) * g) / ws.sum()
                    var x = selRow.left
                    for (i in ws.indices) {
                        selBtns[i].set(x, selRow.top, x + unit * ws[i], selRow.bottom)
                        x += unit * ws[i] + g
                    }
                    if (!paged) off(selBtns[6])
                }
                SEC_PADS -> gridTop = y
                SEC_SEQ -> seqTop = y
            }
            secTop[id] = y
            y += if (id == SEC_XY) xyTot else if (id == SEC_PADS) gridH else fixedH(id)
            secBottom[id] = y
            y += spread
        }
        for (id in secHidden) { secTop[id] = -1f; secBottom[id] = -1f }
        radius = when (theme) {
            STUDIO -> cell * 0.10f
            HUD -> dp(5f)
            RETRO -> dp(4f)
            COMIC -> cell * 0.26f
            in GLASS..CLAUDE -> cell * SKINS[theme - GLASS].padRadius
            else -> cell * 0.17f
        }
        if (theme == RETRO && !xyRect.isEmpty) {
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
        val perPage = cols * rows
        if (padPage >= gridPages()) padPage = 0
        for (i in 0 until Engine.PADS) {
            // die kleinen Knöpfe unter den Pads gibt es nicht mehr (Pad-Menü/Tastatur: SELECT-Leiste)
            moreRects[i].set(-9999f, -9999f, -9998f, -9998f)
            keyRects[i].set(-9999f, -9999f, -9998f, -9998f)
            if (i >= VISIBLE_PADS || i / perPage != padPage || !padsOn) {
                padRects[i].setEmpty()
                continue
            }
            val idx = i % perPage
            val row = idx / cols
            val col = idx % cols
            val x = left + col * (cellW + colGap)
            val y = gridTop + row * (cellH + rowGap)
            padRects[i].set(x, y, x + cellW, y + cellH)
            val path = Path()
            path.addRoundRect(padRects[i], radius, radius, Path.Direction.CW)
            padMeasures[i].setPath(path, true)
            padLengths[i] = padMeasures[i].length
        }
        // Sequenzer (eigener Bereich, Reihenfolge frei)
        seqRect.set(left, seqTop, left + gridW, seqTop + seqH)
        val sp = dp(8f)
        val stripH = dp(30f)
        val sgap = dp(3f)
        val groupGap = dp(5f)
        val stepW = (seqRect.width() - 2 * sp - 15 * sgap - 3 * groupGap) / 16f
        var sx = seqRect.left + sp
        for (k in 0 until Engine.STEPS) {
            if (seqFold) stepRects[k].set(-9999f, -9999f, -9998f, -9998f)
            else stepRects[k].set(sx, seqRect.top + sp, sx + stepW, seqRect.top + sp + stripH)
            sx += stepW + sgap + if (k % 4 == 3) groupGap else 0f
        }
        val bTop = if (seqFold) seqRect.top + sp else seqRect.top + sp + stripH + dp(6f)
        val bH = seqRect.bottom - sp - bTop
        // Platz für den ⤢-Knopf und den Ein-/Ausklapp-Knopf rechts außen freihalten
        val zw = (if (zoomEnabled) dp(30f) + dp(6f) else 0f) + dp(30f) + dp(6f)
        val bW = min(dp(46f), (seqRect.width() - 2 * sp - zw) / 7.4f)
        val bg = dp(6f)
        recBtn.set(seqRect.left + sp, bTop, seqRect.left + sp + bW, bTop + bH)
        playBtn.set(recBtn.right + bg, bTop, recBtn.right + bg + bW, bTop + bH)
        stopBtn.set(playBtn.right + bg, bTop, playBtn.right + bg + bW, bTop + bH)
        val uW = bW * 0.86f
        undoBtn.set(stopBtn.right + dp(10f), bTop, stopBtn.right + dp(10f) + uW, bTop + bH)
        redoBtn.set(undoBtn.right + bg, bTop, undoBtn.right + bg + uW, bTop + bH)
        mixBtn.set(redoBtn.right + dp(10f), bTop, redoBtn.right + dp(10f) + uW * 1.1f, bTop + bH)
        if (zoomEnabled) zoomPads.set(seqRect.right - sp - dp(30f), bTop, seqRect.right - sp, bTop + bH) else zoomPads.setEmpty()
        val fr = if (zoomPads.isEmpty) seqRect.right - sp else zoomPads.left - dp(6f)
        seqFoldBtn.set(fr - dp(30f), bTop, fr, bTop + bH)
        if (SEC_SEQ in secHidden) {
            off(seqRect); for (r in stepRects) off(r)
            for (r in arrayOf(recBtn, playBtn, stopBtn, undoBtn, redoBtn, mixBtn, seqFoldBtn)) off(r)
            zoomPads.setEmpty()
        }
        // Mischpult nutzt auch den Platz der Auswahl-Leiste (die im MIX ausgeblendet ist), wenn sie direkt darüber liegt
        val gridBottom = gridTop + rows * (cellH + rowGap) - rowGap
        val selAbove = vis.indexOf(SEC_SEL) >= 0 && vis.indexOf(SEC_SEL) == vis.indexOf(SEC_PADS) - 1
        mixRect.set(left, if (selAbove) selRow.top else gridTop, left + gridW, gridBottom)
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
        if (xyRect.isEmpty || fxBtn.left < 0f) return
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
        GhostLoop.poll()
        // SELECT + gewähltes Pad: die Effekt-Fläche wirkt nur auf dieses Pad
        Engine.fxOnlyPad = if (selectMode && selPad >= 0 && Engine.hasLoop(selPad)) selPad else -1
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        time += dt

        if (focus != 0) {
            if (focusBlocked()) closeFocus() else {
                drawFocus(canvas, dt)
                postInvalidateOnAnimation()
                return
            }
        }
        drawBackground(canvas)
        drawTopBar(canvas)
        if (SEC_KNOBS !in secHidden) drawKnobs(canvas)
        if (SEC_FUNC !in secHidden) drawFxButton(canvas, dt)
        if (Engine.motionState == Engine.MOT_PLAY && xyPointer < 0) {
            xyDispX = Engine.motCurX
            xyDispY = Engine.motCurY
        }
        if (SEC_XY !in secHidden) {
            drawModeTabs(canvas)
            if (xyMode == 0) {
                drawXy(canvas, dt)
                drawSlotChips(canvas)
                drawHoldButton(canvas)
                drawMotionButton(canvas)
            } else {
                drawInstrument(canvas)
            }
        }
        if (!zoomXy.isEmpty) zoomBtn(canvas, zoomXy, overlayColors())
        if (!zoomFx.isEmpty) zoomBtn(canvas, zoomFx, overlayColors())
        if (SEC_SEL !in secHidden) drawSelRow(canvas)
        Engine.instPadsAuto()
        if (mixView) drawMixer(canvas) else for (i in 0 until VISIBLE_PADS) {
            drawPad(canvas, i, dt)
            drawInstPad(canvas, i)
            if (!sceneView) drawStatus(canvas, i)
            drawMore(canvas, i)
            drawKeyButton(canvas, i)
            if (!sceneView) drawFxBadge(canvas, i)
            drawPadOverlay(canvas, i)
            drawCueMark(canvas, i)
            drawPadName(canvas, i)
        }
        if (!mixView) drawSelOutline(canvas)
        if (SEC_SEQ !in secHidden) {
            drawSequencer(canvas)
            drawSeqFold(canvas)
        }
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        drawFooter(canvas)
        if (kbPad >= 0) drawKeyboard(canvas)
        if (edPad >= 0) drawEditor(canvas, dt)
        if (songBuilder.isOpen) songBuilder.draw(canvas, RectF(dp(4f), insetTop + dp(4f), width - dp(4f), height - insetBottom - dp(4f)), overlayColors()[2])
        if (songEd.isOpen) songEd.draw(canvas, RectF(dp(4f), insetTop + dp(4f), width - dp(4f), height - insetBottom - dp(4f)), overlayColors()[2])
        if (instEd.isOpen) instEd.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors())
        if (slicer.isOpen) slicer.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (station.isOpen) station.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (launcher.isOpen) launcher.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (ghost.isOpen) ghost.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (euclid.isOpen) euclid.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (harm.isOpen) harm.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (atmos.isOpen) atmos.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        if (!masterPanel.isOpen) drawMasterButton(canvas)
        if (masterPanel.isOpen) masterPanel.draw(canvas, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), overlayColors()[2])
        drawInfo(canvas)
        message?.let { drawMessage(canvas, it) }
        if (tourStep >= 0) drawTour(canvas)
        if (layoutEdit) drawLayoutEdit(canvas)
        if (lesson != null) drawLesson(canvas, dt)

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

        drawFuncRow(canvas)
    }

    /** Text auf [maxW] kürzen (mit „…“), bei der aktuellen Schriftgröße von [textPaint]. */
    private fun fitText(t: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (textPaint.measureText(t) <= maxW) return t
        var n = t.length
        while (n > 0 && textPaint.measureText(t, 0, n) + textPaint.measureText("…") > maxW) n--
        return if (n <= 0) "" else t.substring(0, n).trimEnd() + "…"
    }

    /**
     * Schrift im kleinen Display – für alle Designs gleich aufgeteilt, damit sich nichts überlagert:
     * oben links Kettenplatz/Effekt (gekürzt bis vor das Tempo), oben rechts Tempo, darunter der Effektname
     * (verkleinert, falls zu breit), „TEMPO ▸“ nur wenn daneben Platz ist, unten die X/Y-Werte.
     */
    private fun drawDisplayTexts(canvas: Canvas, r: RectF, pad: Float, y0: Float, info: Int, bpmCol: Int, hint: Int, name: Int, xyCol: Int, glow: Boolean) {
        val type = Engine.fxType
        val touching = xyPointer >= 0 || xyHold || autoTouching()
        val inner = r.width() - 2 * pad
        // Tempo oben rechts
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(10f)
        val bpm = fitText(bpmLabel(), inner * 0.55f)
        val bw = textPaint.measureText(bpm)
        textPaint.color = bpmCol
        canvas.drawText(bpm, r.right - pad, y0 + dp(1f), textPaint)
        // Zeile 1 links
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(9f)
        textPaint.color = info
        val num = String.format(Locale.GERMANY, "%d·FX %02d/%d %s %s", Engine.editSlot + 1, type + 1, Fx.COUNT, if (touching) "●" else "○", fxTargetLabel()).trim()
        canvas.drawText(fitText(num, inner - bw - dp(6f)), r.left + pad, y0, textPaint)
        // Effektname
        var ns = min(dp(17f), r.height() * 0.32f)
        textPaint.textSize = ns
        val nm = Fx.NAMES[type]
        val nw0 = textPaint.measureText(nm)
        if (nw0 > inner) { ns = max(dp(10f), ns * inner / nw0); textPaint.textSize = ns }
        val nmFit = fitText(nm, inner)
        val nw = textPaint.measureText(nmFit)
        textPaint.color = name
        if (glow) textPaint.setShadowLayer(dp(6f), 0f, 0f, alpha(name, 0.8f))
        canvas.drawText(nmFit, r.left + pad, y0 + dp(18f), textPaint)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        // „TEMPO ▸“ nur, wenn es neben dem Namen Platz hat
        textPaint.textSize = dp(8f)
        val tw = textPaint.measureText("TEMPO ▸")
        if (r.left + pad + nw + dp(8f) + tw <= r.right - pad) {
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = hint
            canvas.drawText("TEMPO ▸", r.right - pad, y0 + dp(12f), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        // X/Y-Werte (verkleinert bzw. gekürzt, falls zu breit)
        textPaint.textSize = dp(8.5f)
        val xy = "X ${Fx.X_LABELS[type]} ${(xyDispX * 100).toInt()}  Y ${Fx.Y_LABELS[type]} ${(xyDispY * 100).toInt()}"
        val xw = textPaint.measureText(xy)
        if (xw > inner) textPaint.textSize = max(dp(7f), dp(8.5f) * inner / xw)
        textPaint.color = xyCol
        canvas.drawText(fitText(xy, inner), r.left + pad, y0 + dp(30f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
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
        drawDisplayTexts(canvas, r, pad, r.top + dp(13f), alpha(txt, 0.7f), txt, alpha(txt, 0.5f), txt, alpha(txt, 0.8f), true)
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

    /** MASTER-Knopf oben: leuchtet, wenn die Kette aktiv ist; Balken zeigt die Limiter-Reduktion. */
    private fun drawMasterButton(canvas: Canvas) {
        val r = masterBtn
        if (r.width() <= 0f || kbPad >= 0 || edPad >= 0 || instEd.isOpen || slicer.isOpen || station.isOpen || launcher.isOpen || ghost.isOpen || atmos.isOpen || harm.isOpen || euclid.isOpen || songEd.isOpen || songBuilder.isOpen) return
        val cols = overlayColors()
        val active = MasterBus.anyOn()
        val rr = dp(8f)
        fillPaint.shader = null
        fillPaint.color = if (active) blend(cols[0], cols[2], 0.3f) else cols[0]
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = if (active) cols[2] else alpha(cols[1], 0.45f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = dp(9f)
        textPaint.color = if (active) Color.WHITE else cols[1]
        canvas.drawText("MASTER", r.centerX(), r.centerY() + dp(3f), textPaint)
        if (active) {
            val gr = (MasterBus.limGr + MasterBus.compGr).coerceIn(0f, 12f) / 12f
            fillPaint.color = Color.rgb(255, 170, 40)
            canvas.drawRect(r.left + dp(4f), r.bottom - dp(4f), r.left + dp(4f) + (r.width() - dp(8f)) * gr, r.bottom - dp(2f), fillPaint)
        }
        // Set-Aufnahme
        val q = setRecBtn
        val rec = SetRecorder.recording
        val blink = rec && (SystemClock.uptimeMillis() / 500) % 2 == 0L
        fillPaint.color = if (rec) Color.rgb(200, 30, 45) else cols[0]
        canvas.drawRoundRect(q, rr, rr, fillPaint)
        strokePaint.color = if (rec) Color.rgb(255, 90, 100) else alpha(cols[1], 0.45f)
        canvas.drawRoundRect(q, rr, rr, strokePaint)
        dotPaint.color = if (rec && !blink) Color.WHITE else Color.rgb(235, 40, 55)
        canvas.drawCircle(q.left + dp(8f), q.centerY(), dp(3.5f), dotPaint)
        textPaint.textSize = dp(8.5f)
        textPaint.color = if (rec) Color.WHITE else cols[1]
        canvas.drawText(if (rec) SetRecorder.fmt(SetRecorder.seconds()) else "REC", q.centerX() + dp(5f), q.centerY() + dp(3f), textPaint)
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
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 3) "INST" else if (Engine.padSource[i] == 2) "PADS" else if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
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

    // ---------------------------------------------------------------- Große Ansicht (Ebenen)

    /** 0 = aus, 1 = Pads, 2 = Touchpad / Instrumente, 3 = Effekt-Sektion (Regler + Funktionen). */
    var focus = 0
        private set
    /** Doppeltipp auf den Rahmen öffnet die große Ansicht (Optionen). */
    var zoomEnabled = true
        set(v) { field = v; layoutPads() }
    var onFocusChanged: ((Boolean) -> Unit)? = null
    private val focusSrc = RectF()
    private val focusDst = RectF()
    private val focusClose = RectF()
    private val focusM = android.graphics.Matrix()
    private val focusInv = android.graphics.Matrix()
    private var focusTapSec = 0
    private var focusTapT = 0L
    private val FOCUS_NAMES = arrayOf("", "▦ PADS", "🎛 TOUCHPAD / INSTRUMENTE", "🎚 EFFEKTE & FUNKTIONEN")

    private val zoomPads = RectF()
    private val zoomXy = RectF()
    private val zoomFx = RectF()

    /** Kleiner Knopf „⤢“: Sektion groß öffnen. */
    private fun zoomBtn(canvas: Canvas, r: RectF, cols: IntArray) {
        val rr = min(r.width(), r.height()) * 0.3f
        fillPaint.shader = null
        fillPaint.color = alpha(blend(cols[0], cols[1], 0.1f), 0.92f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = alpha(cols[2], 0.8f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        // Pfeile nach außen
        val c = cols[1]
        strokePaint.color = c
        strokePaint.strokeWidth = dp(1.6f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        val m = min(r.width(), r.height()) * 0.24f
        val x0 = r.centerX() - m; val x1 = r.centerX() + m
        val y0 = r.centerY() - m; val y1 = r.centerY() + m
        val a = m * 0.7f
        canvas.drawLine(x0, y0, x0 + a, y0, strokePaint); canvas.drawLine(x0, y0, x0, y0 + a, strokePaint)
        canvas.drawLine(x1, y1, x1 - a, y1, strokePaint); canvas.drawLine(x1, y1, x1, y1 - a, strokePaint)
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun focusBlocked(): Boolean = kbPad >= 0 || edPad >= 0 || instEd.isOpen || masterPanel.isOpen || slicer.isOpen || station.isOpen || launcher.isOpen || ghost.isOpen || atmos.isOpen || harm.isOpen || euclid.isOpen || songEd.isOpen || songBuilder.isOpen

    private fun sectionRect(sec: Int, out: RectF) {
        when (sec) {
            1 -> { out.set(mixRect); out.union(selRow) }
            2 -> {
                out.set(xyRect)
                out.union(modeRects[0]); out.union(modeRects[1])
                if (!specL.isEmpty) out.union(specL)
                if (!specR.isEmpty) out.union(specR)
            }
            else -> {
                out.set(knobRects[0])
                for (k in 1 until 5) out.union(knobRects[k])
                for (k in 0 until 6) out.union(funcRects[k])
                out.union(fxBtn)
            }
        }
        out.inset(-dp(6f), -dp(6f))
    }

    private fun checkFocusTap(x: Float, y: Float) {
        if (!zoomEnabled) return
        val sec = when {
            focus != 0 -> focus
            RectF(mixRect).apply { inset(-dp(12f), -dp(12f)) }.contains(x, y) -> 1
            RectF().also { sectionRect(2, it); it.inset(-dp(12f), -dp(12f)) }.contains(x, y) -> 2
            RectF().also { sectionRect(3, it); it.inset(-dp(8f), -dp(8f)) }.contains(x, y) -> 3
            else -> 0
        }
        if (sec == 0) return
        val now = SystemClock.uptimeMillis()
        if (sec == focusTapSec && now - focusTapT < 380L) {
            focusTapT = 0L
            if (focus == sec) closeFocus() else openFocus(sec)
        } else {
            focusTapSec = sec
            focusTapT = now
        }
    }

    fun openFocus(sec: Int) {
        if (sec !in 1..3) return
        focus = sec
        Engine.drumRec = drumActive()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        layoutPads()
        onFocusChanged?.invoke(true)
        hint("${FOCUS_NAMES[sec]} groß · Doppeltipp auf den Rahmen oder ✕ = zurück")
    }

    fun closeFocus() {
        if (focus == 0) return
        wvStop()
        focus = 0
        Engine.drumRec = false
        layoutPads()
        onFocusChanged?.invoke(false)
    }

    private fun updateFocusMatrix() {
        sectionRect(focus, focusSrc)
        val top = insetTop + dp(46f)
        focusDst.set(dp(8f), top, width - dp(8f), height - insetBottom - dp(8f))
        if (drumActive()) {
            val ph = focusDst.height() * (if (width > height) 0.40f else 0.30f)
            drPanel.set(focusDst.left, focusDst.top, focusDst.right, focusDst.top + ph)
            focusDst.top = drPanel.bottom + dp(8f)
            wvPanel.setEmpty()
        } else if (wvOn()) {
            drPanel.setEmpty()
            val ph = focusDst.height() * (if (width > height) 0.48f else 0.40f)
            wvPanel.set(focusDst.left, focusDst.top, focusDst.right, focusDst.top + ph)
            focusDst.top = wvPanel.bottom + dp(8f)
        } else { wvPanel.setEmpty(); drPanel.setEmpty() }
        val sc = min(focusDst.width() / focusSrc.width(), focusDst.height() / focusSrc.height())
        val tx = focusDst.centerX() - focusSrc.centerX() * sc
        val ty = focusDst.centerY() - focusSrc.centerY() * sc
        focusM.reset()
        focusM.setScale(sc, sc)
        focusM.postTranslate(tx, ty)
        focusM.invert(focusInv)
        val cs = dp(36f)
        focusClose.set(width - dp(10f) - cs, insetTop + dp(5f), width - dp(10f), insetTop + dp(5f) + cs)
        if (focus == 1) drToggle.set(focusClose.left - dp(8f) - dp(104f), focusClose.top, focusClose.left - dp(8f), focusClose.bottom)
        else drToggle.setEmpty()
    }

    private fun drawFocus(canvas: Canvas, dt: Float) {
        updateFocusMatrix()
        drawBackground(canvas)
        canvas.save()
        canvas.concat(focusM)
        when (focus) {
            1 -> {
                drawSelRow(canvas)
                if (mixView) drawMixer(canvas) else {
                    for (i in 0 until VISIBLE_PADS) {
                        drawPad(canvas, i, dt)
                        drawInstPad(canvas, i)
                        if (!sceneView) drawStatus(canvas, i)
                        drawMore(canvas, i)
                        drawKeyButton(canvas, i)
                        if (!sceneView) drawFxBadge(canvas, i)
                        drawPadOverlay(canvas, i)
                        drawCueMark(canvas, i)
                        drawPadName(canvas, i)
                    }
                    drawSelOutline(canvas)
                }
            }
            2 -> {
                if (Engine.motionState == Engine.MOT_PLAY && xyPointer < 0) {
                    xyDispX = Engine.motCurX
                    xyDispY = Engine.motCurY
                }
                drawModeTabs(canvas)
                if (xyMode == 0) {
                    drawXy(canvas, dt)
                    drawSlotChips(canvas)
                    drawHoldButton(canvas)
                    drawMotionButton(canvas)
                } else drawInstrument(canvas)
            }
            else -> {
                drawKnobs(canvas)
                drawFxButton(canvas, dt)
                drawFuncRow(canvas)
            }
        }
        drawShocks(canvas, dt)
        drawParticles(canvas, dt)
        canvas.restore()
        if (drumActive() && !drPanel.isEmpty) drawDrumPanel(canvas)
        else if (!wvPanel.isEmpty && wvOn()) drawWavePanel(canvas)
        // Kopfzeile der großen Ansicht
        val cols = overlayColors()
        if (!drToggle.isEmpty) {
            smallBtn(canvas, drToggle, if (drumMode) "🥁 DRUM AN" else "🥁 DRUM", drumMode, cols)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = cols[1]
        val t = fitLabel(textPaint, if (drumActive()) "Drum-Pads" else FOCUS_NAMES[focus], (if (drToggle.isEmpty) focusClose.left else drToggle.left) - dp(24f), dp(15f), dp(10f))
        canvas.drawText(t, dp(14f), focusClose.centerY() + textPaint.textSize * 0.36f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        fillPaint.shader = null
        fillPaint.color = blend(cols[0], cols[1], 0.12f)
        canvas.drawRoundRect(focusClose, dp(10f), dp(10f), fillPaint)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = cols[2]
        canvas.drawRoundRect(focusClose, dp(10f), dp(10f), strokePaint)
        textPaint.textSize = dp(18f)
        textPaint.color = cols[1]
        canvas.drawText("✕", focusClose.centerX(), focusClose.centerY() + dp(6f), textPaint)
        drawInfo(canvas)
        message?.let { drawMessage(canvas, it) }
        if (tourStep >= 0) drawTour(canvas)
    }

    // ---------------------------------------------------------------- Pad-Auswahl (SELECT) und Wellenform-Ansicht

    /** SELECT an: Antippen wählt ein Pad aus, statt es zu spielen. */
    var selectMode = false
    /** Ausgewähltes Pad – Ziel für Wave-Editor, Noten, Effekte und die Wellenform in der großen Ansicht. */
    var selPad = -1
    private val selRow = RectF()
    private val selBtns = Array(7) { RectF() }

    // ---- Pad-Raster, Seiten, Sequenzer einklappen
    /** 0 = 4×3 (alle 12), 1 = 3×3 groß, 2 = 4×2 Performance. */
    var padGrid = 0
        set(v) { field = v.coerceIn(0, 2); padPage = 0; layoutPads(); invalidate() }
    private var padPage = 0
    var seqFold = false
        set(v) { field = v; layoutPads(); invalidate() }
    private val seqFoldBtn = RectF()
    var onLayoutPrefs: (() -> Unit)? = null
    private fun gridCols() = if (padGrid == 1) 3 else 4
    private fun gridRows() = if (padGrid == 2) 2 else 3
    private fun gridPages() = (VISIBLE_PADS + gridCols() * gridRows() - 1) / (gridCols() * gridRows())
    private fun pageLabel(): String {
        val per = gridCols() * gridRows()
        val a = padPage * per + 1
        val b = min(VISIBLE_PADS, a + per - 1)
        return "$a–$b ▸"
    }

    private fun drawSeqFold(canvas: Canvas) {
        if (seqFoldBtn.width() <= 0f) return
        smallBtn(canvas, seqFoldBtn, if (seqFold) "▴" else "▾", false, overlayColors())
    }

    private fun drawSelRow(canvas: Canvas) {
        if (mixView) return
        val cols = overlayColors()
        val has = selPad >= 0 && Engine.hasLoop(selPad)
        smallBtn(canvas, selBtns[0], if (selectMode) "◉ SELECT" else "○ SELECT", selectMode, cols)
        textPaint.color = if (selPad >= 0) cols[2] else alpha(cols[1], 0.5f)
        val lb = fitLabel(textPaint, if (selPad >= 0) "PAD ${selPad + 1}" else "–", selBtns[1].width(), dp(12f), dp(8f))
        canvas.drawText(lb, selBtns[1].centerX(), selBtns[1].centerY() + textPaint.textSize * 0.36f, textPaint)
        if (selBtns[6].left > -1000f) smallBtn(canvas, selBtns[6], pageLabel(), padPage > 0, cols)
        val labels = arrayOf("〰 WAVE", "🎹 NOTEN", "✨ FX", "⋯")
        for (k in 0 until 4) {
            val r = selBtns[2 + k]
            smallBtn(canvas, r, labels[k], false, cols)
            if (!has && k < 2) {
                fillPaint.color = alpha(cols[0], 0.55f)
                canvas.drawRoundRect(r, dp(8f), dp(8f), fillPaint)
            }
        }
    }

    /** Eigener Startpunkt am Pad: dünne Leiste oben, Spitze ◆ an der Stelle, ab der das Pad spielt. */
    private fun drawCueMark(canvas: Canvas, i: Int) {
        val c = Engine.padCue(i)
        if (c <= 0) return
        val len = Engine.padDataLen(i)
        val r = padRects[i]
        if (len <= 0 || r.width() <= 0f) return
        val qc = Color.rgb(255, 70, 200)
        val l = r.left + radius * 0.8f
        val rr = r.right - radius * 0.8f
        val y = r.top + dp(4f)
        val x = l + (c.toFloat() / len) * (rr - l)
        fillPaint.shader = null
        fillPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRect(l, y - dp(1f), rr, y + dp(1f), fillPaint)
        fillPaint.color = qc
        canvas.drawRect(x, y - dp(1.2f), rr, y + dp(1.2f), fillPaint)
        val s = dp(4.5f)
        val path = Path()
        path.moveTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.lineTo(x - s, y); path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawSelOutline(canvas: Canvas) {
        val p = selPad
        if (p !in 0 until VISIBLE_PADS) return
        val r = padRects[p]
        if (r.width() <= 0f) return
        strokePaint.shader = null
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = overlayColors()[2]
        tmpRect.set(r.left - dp(3f), r.top - dp(3f), r.right + dp(3f), r.bottom + dp(3f))
        canvas.drawRoundRect(tmpRect, radius + dp(3f), radius + dp(3f), strokePaint)
    }

    /** Auswahl-Leiste antippen. true = verbraucht. */
    private fun selRowDown(x: Float, y: Float): Boolean {
        if (mixView || !selRow.contains(x, y)) return false
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val p = selPad
        val has = p >= 0 && Engine.hasLoop(p)
        when {
            selBtns[6].contains(x, y) -> {
                padPage = (padPage + 1) % gridPages()
                layoutPads()
                hint("Pads ${pageLabel().dropLast(2)}")
            }
            selBtns[0].contains(x, y) -> {
                selectMode = !selectMode
                hint(if (selectMode) "SELECT: Pad antippen wählt es aus (spielt nicht) – Effekt-Fläche, Wave, Noten, FX und ⋯ gelten dann nur für dieses Pad" else "SELECT aus: Pads spielen wieder, Effekt-Fläche wirkt wieder auf alle")
            }
            selBtns[2].contains(x, y) -> if (has) { closeFocus(); openEditor(p) } else hint(if (p < 0) "Erst ein Pad wählen (SELECT)" else "Pad ${p + 1} ist leer")
            selBtns[3].contains(x, y) -> if (has) { closeFocus(); openKeyboard(p) } else hint(if (p < 0) "Erst ein Pad wählen (SELECT)" else "Pad ${p + 1} ist leer")
            selBtns[4].contains(x, y) -> if (p >= 0) onPadFxEditor?.invoke(p) else hint("Erst ein Pad wählen (SELECT)")
            selBtns[5].contains(x, y) -> if (p >= 0) onPadMenu?.invoke(p) else hint("Erst ein Pad wählen (SELECT)")
        }
        return true
    }

    // ---- Wellenform-Ansicht über den Pads (große Ansicht)
    private val wvPanel = RectF()
    private val wvWave = RectF()
    /** 0..15 Einstellungen, 16 = ◆ START HIER, 17 = START zurück, 18..20 = Zoom −, +, ALLES */
    private val wvBtns = Array(21) { RectF() }
    private var wvPeaks: FloatArray? = null
    private var wvPeaksKey = 0L
    private var wvCursor = 0
    private var wvPreview = -1
    private var wvPlayFrom = 0
    private var wvPointer = -1
    // Zoom: sichtbarer Ausschnitt [wvViewFrom, wvViewFrom + len / wvZoom)
    private var wvZoom = 1f
    private var wvViewFrom = 0.0
    private var wvPinchA = -1
    private var wvPinchB = -1
    private var wvPinchD0 = 1f
    private var wvPinchZ0 = 1f
    private var wvPinchAnchor = 0.0
    private var wvLastPad = -1

    private fun wvOn(): Boolean = focus == 1 && !drumMode && selPad >= 0 && Engine.hasLoop(selPad)

    private fun wvLen(): Int = Engine.padDataLen(selPad)

    private fun wvResetView() {
        wvZoom = 1f
        wvViewFrom = 0.0
        wvPinchA = -1
        wvPinchB = -1
    }

    private fun wvMaxZoom(): Float = max(1f, wvLen() / max(1f, wvWave.width() / dp(3f)))

    private fun wvSpan(): Double = max(2.0, wvLen() / wvZoom.toDouble())

    private fun wvClampView() {
        val len = wvLen()
        wvZoom = wvZoom.coerceIn(1f, wvMaxZoom())
        wvViewFrom = wvViewFrom.coerceIn(0.0, max(0.0, len - wvSpan()))
    }

    private fun wvX(f: Double): Float = wvWave.left + ((f - wvViewFrom) / wvSpan()).toFloat() * wvWave.width()

    private fun wvFrameAt(x: Float): Int =
        (wvViewFrom + ((x - wvWave.left) / wvWave.width()).coerceIn(0f, 1f) * wvSpan()).toInt().coerceIn(0, max(0, wvLen() - 1))

    /** Ausschnitt so verschieben, dass Frame [f] sichtbar ist. */
    private fun wvShow(f: Int) {
        if (wvZoom <= 1f) return
        val sp = wvSpan()
        if (f < wvViewFrom || f > wvViewFrom + sp) wvViewFrom = f - sp * 0.25
        wvClampView()
    }

    /** Zoomen um [factor], Frame [anchor] bleibt an Bildschirm-Stelle [ax]. */
    private fun wvZoomBy(factor: Float, anchor: Double, ax: Float) {
        wvZoom = (wvZoom * factor).coerceIn(1f, wvMaxZoom())
        wvViewFrom = anchor - ((ax - wvWave.left) / wvWave.width()) * wvSpan()
        wvClampView()
    }

    private fun wvStop() {
        if (wvPreview >= 0) Engine.noteOff(wvPreview)
        wvPreview = -1
    }

    private fun wvPlay(from: Int) {
        wvStop()
        val d = Engine.loopData(selPad) ?: return
        val f = from.coerceIn(0, max(0, d.size - 2))
        wvPlayFrom = f
        wvPreview = Engine.preview(d.copyOfRange(f, d.size))
    }

    /** Spulschritt: bei Zoom feiner (1/16 statt Schlag), ohne Tempo 1 s bzw. 0,1 s. */
    private fun wvStep(): Int {
        val bl = Engine.beatLen()
        val fine = wvZoom >= 4f
        return if (bl > 0.0) (if (fine) bl / 4 else bl).toInt() else if (fine) Engine.sampleRate / 10 else Engine.sampleRate
    }

    private fun wvStepName(): String = if (Engine.beatLen() > 0.0) (if (wvZoom >= 4f) "1/16" else "SCHLAG") else (if (wvZoom >= 4f) "0,1 s" else "1 s")

    private fun layoutWave() {
        val r = wvPanel
        val top = r.top + dp(28f)
        val rowH = dp(30f)
        val gap = dp(5f)
        val rows = 3
        val waveBottom = r.bottom - dp(8f) - rows * rowH - (rows - 1) * gap - dp(8f)
        wvWave.set(r.left + dp(10f), top, r.right - dp(10f), max(top + dp(30f), waveBottom))
        // Zoom-Knöpfe rechts in der Kopfzeile
        val zw = dp(40f)
        val zh = dp(22f)
        var zx = r.right - dp(10f) - zw * 1.5f
        for (k in 2 downTo 0) {
            val ww = if (k == 2) zw * 1.5f else zw
            if (k < 2) zx -= zw + dp(4f)
            wvBtns[18 + k].set(zx, r.top + dp(3f), zx + ww, r.top + dp(3f) + zh)
        }
        var y = wvWave.bottom + dp(8f)
        // Reihe 1: Transport + Startpunkt · Reihe 2+3: je 6 Einstellungen
        val order = arrayOf(intArrayOf(0, 1, 2, 3, 16, 17), intArrayOf(4, 5, 6, 7, 8, 9), intArrayOf(10, 11, 12, 13, 14, 15))
        for (row in 0 until rows) {
            val ids = order[row]
            val n = ids.size
            val w = (r.width() - dp(20f) - (n - 1) * gap) / n
            for (k in 0 until n) {
                val x = r.left + dp(10f) + k * (w + gap)
                wvBtns[ids[k]].set(x, y, x + w, y + rowH)
            }
            y += rowH + gap
        }
    }

    private fun wvLabels(): Array<String> {
        val p = selPad
        val shot = Engine.isOneShot(p)
        val sn = wvStepName()
        return arrayOf(
            "⏮", "◀◀ $sn", if (wvPreview >= 0) "■ STOP" else "▶ AB HIER", "$sn ▶▶",
            if (shot) "⚡ ONE-SHOT" else "∞ LOOP", if (Engine.isMuted(p)) "🔇 STUMM" else "MUTE", if (Engine.solo[p]) "★ SOLO" else "SOLO",
            "⇆ RÜCKWÄRTS", "⤒ NORMAL.", "〰 EDITOR",
            "− HALBTON", "+ HALBTON", "◀ 1/16", "1/16 ▶", "VOL −", "VOL +",
            "◆ START HIER", "⌫ START", "🔍−", "🔍+", if (wvZoom > 1.01f) String.format(Locale.GERMANY, "×%.0f", wvZoom) else "ALLES"
        )
    }

    private fun drawWavePanel(canvas: Canvas) {
        val cols = overlayColors()
        val p = selPad
        val r = wvPanel
        if (p != wvLastPad) { wvLastPad = p; wvResetView(); wvCursor = Engine.padCue(p) }
        layoutWave()
        wvClampView()
        fillPaint.shader = null
        fillPaint.color = alpha(blend(cols[0], cols[1], 0.04f), 0.96f)
        canvas.drawRoundRect(r, dp(12f), dp(12f), fillPaint)
        strokePaint.strokeWidth = dp(1.3f)
        strokePaint.color = alpha(cols[2], 0.6f)
        canvas.drawRoundRect(r, dp(12f), dp(12f), strokePaint)
        val len = wvLen()
        val sr = Engine.sampleRate.toFloat()
        val cue = Engine.padCue(p)
        // Kopfzeile
        val g = Engine.padGain(p)
        val db = if (g <= 0.001f) "-∞" else String.format(Locale.GERMANY, "%+.1f dB", 20f * kotlin.math.log10(g))
        val bars = if (Engine.beatLen() > 0.0 && !Engine.isOneShot(p)) String.format(Locale.GERMANY, " · %.2f Takte", len / (Engine.beatLen() * 4)) else ""
        val semi = Engine.padSemitone(p)
        val head = "PAD ${p + 1} · " + String.format(Locale.GERMANY, "%.2f s", len / sr) + bars + " · $db" +
            (if (cue > 0) String.format(Locale.GERMANY, " · ◆ Start %.3f s", cue / sr) else "") +
            (if (semi != 0) " · ${if (semi > 0) "+" else ""}$semi HT" else "") + if (Engine.isFree(p)) " · Original-Tempo" else ""
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = cols[1]
        val ht = fitLabel(textPaint, head, wvBtns[18].left - r.left - dp(16f), dp(12f), dp(8f))
        canvas.drawText(ht, r.left + dp(10f), r.top + dp(19f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        // Wellenform (Ausschnitt)
        val w = wvWave
        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(w, dp(6f), dp(6f), fillPaint)
        val colsN = max(16, (w.width() / dp(2f)).toInt())
        val from = wvViewFrom.toInt()
        val to = (wvViewFrom + wvSpan()).toInt()
        val key = ((Engine.padDataId(p).toLong() * 31 + from) * 31 + to) * 31 + colsN
        if (key != wvPeaksKey || wvPeaks == null) {
            wvPeaks = Engine.padPeaksRange(p, from, to, colsN)
            wvPeaksKey = key
        }
        canvas.save()
        canvas.clipRect(w)
        val pk = wvPeaks
        if (pk != null) {
            var mx = 0.001f
            for (v in pk) if (v > mx) mx = v
            // gezoomt: absolute Höhe (gleicher Maßstab beim Blättern), sonst auf die Spitze skaliert
            val ref = if (wvZoom > 1.01f) max(0.05f, Engine.padPeakMax(p)) else mx
            val cw = w.width() / pk.size
            for (i in pk.indices) {
                val fx = from + (i + 0.5) * (to - from) / pk.size
                fillPaint.color = if (cue > 0 && fx < cue) alpha(cols[2], 0.35f) else cols[2]
                val hh = min(1f, pk[i] / ref) * w.height() * 0.46f
                canvas.drawRect(w.left + i * cw, w.centerY() - hh, w.left + i * cw + max(1f, cw - dp(0.6f)), w.centerY() + hh, fillPaint)
            }
        }
        // Taktlinien (gezoomt auch 1/16)
        if (Engine.beatLen() > 0.0 && !Engine.isOneShot(p) && !Engine.isFree(p) && len > 0) {
            val bl = Engine.beatLen()
            val sub = if (wvZoom >= 4f) 4 else 1
            val st = bl / sub
            var k = max(0, (from / st).toInt())
            while (k * st <= to && k < 200000) {
                val x = wvX(k * st)
                val beat = k % sub == 0
                val bar = beat && (k / sub) % 4 == 0
                strokePaint.strokeWidth = dp(if (bar) 1.2f else 0.6f)
                strokePaint.color = Color.argb(if (bar) 110 else if (beat) 50 else 22, 255, 255, 255)
                canvas.drawLine(x, w.top, x, w.bottom, strokePaint)
                if (bar && wvZoom > 1.01f) {
                    textPaint.textSize = dp(9f)
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.argb(150, 255, 255, 255)
                    canvas.drawText("${k / sub / 4 + 1}", x + dp(3f), w.top + dp(10f), textPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                }
                k++
            }
        }
        // eigener Startpunkt
        if (cue > 0) {
            val qx = wvX(cue.toDouble())
            val qc = Color.rgb(255, 70, 200)
            strokePaint.strokeWidth = dp(2.4f)
            strokePaint.color = qc
            canvas.drawLine(qx, w.top, qx, w.bottom, strokePaint)
            fillPaint.color = qc
            canvas.drawRect(qx, w.bottom - dp(14f), qx + dp(36f), w.bottom, fillPaint)
            textPaint.textSize = dp(9f)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            canvas.drawText("START", qx + dp(3f), w.bottom - dp(4f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        // LIVE (im Mix)
        val lp = Engine.padPlayPos(p)
        if (lp >= 0) {
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = Color.rgb(255, 176, 40)
            val lx = wvX(lp)
            canvas.drawLine(lx, w.top, lx, w.bottom, strokePaint)
        }
        // Vorhör-Kopf (gezoomt: Ausschnitt läuft mit)
        if (wvPreview >= 0) {
            val vp = Engine.voicePos(wvPreview)
            if (vp < 0) wvPreview = -1 else {
                val hf = wvPlayFrom + vp
                if (wvZoom > 1.01f && wvPointer < 0 && wvPinchA < 0 && hf > wvViewFrom + wvSpan()) { wvViewFrom = hf; wvClampView() }
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = Color.rgb(80, 230, 120)
                val hx = wvX(hf)
                canvas.drawLine(hx, w.top, hx, w.bottom, strokePaint)
            }
        }
        // Cursor
        val cx = wvX(wvCursor.toDouble())
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.WHITE
        canvas.drawLine(cx, w.top, cx, w.bottom, strokePaint)
        val tri = Path()
        tri.moveTo(cx - dp(6f), w.top); tri.lineTo(cx + dp(6f), w.top); tri.lineTo(cx, w.top + dp(9f)); tri.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(tri, fillPaint)
        canvas.restore()
        // Übersicht: welcher Teil ist sichtbar
        if (wvZoom > 1.01f && len > 0) {
            val oy = w.bottom - dp(3f)
            fillPaint.color = Color.argb(70, 255, 255, 255)
            canvas.drawRect(w.left, oy, w.right, w.bottom, fillPaint)
            fillPaint.color = cols[2]
            val a = w.left + (wvViewFrom / len).toFloat() * w.width()
            val b = w.left + ((wvViewFrom + wvSpan()) / len).toFloat() * w.width()
            canvas.drawRect(a, oy, max(a + dp(4f), b), w.bottom, fillPaint)
        }
        textPaint.textSize = dp(9f)
        textPaint.color = Color.argb(200, 255, 255, 255)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.GERMANY, "%.3f s", wvCursor / sr), w.right - dp(4f), w.bottom - dp(6f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        // Knöpfe
        val labels = wvLabels()
        val on = BooleanArray(21)
        on[2] = wvPreview >= 0
        on[4] = !Engine.isOneShot(p)
        on[5] = Engine.isMuted(p)
        on[6] = Engine.solo[p]
        on[16] = cue > 0 && kotlin.math.abs(cue - wvCursor) < Engine.sampleRate / 400
        on[20] = wvZoom > 1.01f
        for (i in 0 until 21) smallBtn(canvas, wvBtns[i], labels[i], on[i], cols)
        if (cue <= 0) {
            fillPaint.color = alpha(cols[0], 0.55f)
            canvas.drawRoundRect(wvBtns[17], dp(8f), dp(8f), fillPaint)
        }
    }

    /** Touch auf der Wellenform-Ansicht (Bildschirm-Koordinaten). true = Ereignis verbraucht. */
    private fun wavePanelTouch(e: MotionEvent): Boolean {
        if (!wvOn()) return false
        val am = e.actionMasked
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        val len = wvLen()
        when (am) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx)
                val y = e.getY(idx)
                if (!wvPanel.contains(x, y)) return false
                layoutWave()
                if (RectF(wvWave).apply { inset(0f, -dp(6f)) }.contains(x, y)) {
                    if (wvPointer >= 0 && wvPointer != id && wvPinchA < 0) {
                        // zweiter Finger: Zoomen (Auseinanderziehen) und Verschieben
                        val pa = e.findPointerIndex(wvPointer)
                        if (pa >= 0) {
                            wvPinchA = wvPointer
                            wvPinchB = id
                            wvPinchD0 = max(dp(10f), kotlin.math.abs(e.getX(pa) - x))
                            wvPinchZ0 = wvZoom
                            val mid = (e.getX(pa) + x) / 2f
                            wvPinchAnchor = wvViewFrom + ((mid - wvWave.left) / wvWave.width()) * wvSpan()
                            return true
                        }
                    }
                    wvPointer = id
                    wvCursor = wvFrameAt(x)
                    return true
                }
                val p = selPad
                val i = wvBtns.indexOfFirst { it.contains(x, y) }
                if (i < 0) return true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val st = wvStep()
                when (i) {
                    0 -> {
                        val c = Engine.padCue(p)
                        wvCursor = if (c > 0 && wvCursor != c) c else 0
                        wvShow(wvCursor)
                        if (wvPreview >= 0) wvPlay(wvCursor)
                    }
                    1 -> { wvCursor = max(0, wvCursor - st); wvShow(wvCursor); if (wvPreview >= 0) wvPlay(wvCursor) }
                    2 -> if (wvPreview >= 0) wvStop() else wvPlay(wvCursor)
                    3 -> { wvCursor = min(max(0, len - 2), wvCursor + st); wvShow(wvCursor); if (wvPreview >= 0) wvPlay(wvCursor) }
                    4 -> { Engine.setPadMode(p, !Engine.isOneShot(p)); hint(if (Engine.isOneShot(p)) "Pad ${p + 1}: ⚡ One-Shot" else "Pad ${p + 1}: ∞ Loop") }
                    5 -> Engine.toggleMute(p)
                    6 -> Engine.toggleSolo(p)
                    7 -> { wvStop(); if (Engine.reversePad(p)) hint("Pad ${p + 1} rückwärts – ↶ UNDO holt es zurück") }
                    8 -> { if (Engine.normalizePad(p)) hint("Pad ${p + 1} normalisiert (−1 dB Spitze)") }
                    9 -> { wvStop(); closeFocus(); openEditor(p) }
                    10, 11 -> {
                        wvStop()
                        val d = if (i == 10) -1 else 1
                        hint("Pad ${p + 1}: transponiere …")
                        Thread { if (Engine.transposePad(p, d)) post { hint("Pad ${p + 1}: ${if (Engine.padSemitone(p) != 0 && Engine.isOneShot(p)) "Tonhöhe ${Engine.padSemitone(p)} HT" else if (d > 0) "+1 Halbton" else "−1 Halbton"}") } }.start()
                    }
                    12, 13 -> {
                        if (!Engine.canShiftPad(p)) hint("Verschieben geht bei ∞ Loops ohne Slices")
                        else {
                            val bar = if (Engine.beatLen() > 0.0) (Engine.beatLen() * 4).toLong() else Engine.sampleRate.toLong()
                            Engine.shiftPad(p, if (i == 12) bar / 16 else -bar / 16)
                            hint("Pad ${p + 1} um 1/16 ${if (i == 12) "früher" else "später"}")
                        }
                    }
                    14 -> Engine.setPadGain(p, Engine.padGain(p) / 1.122f)
                    15 -> Engine.setPadGain(p, Engine.padGain(p) * 1.122f)
                    16 -> {
                        if (Engine.isSliced(p)) hint("Bei Slices gibt es keinen eigenen Startpunkt – die Slices haben feste Plätze")
                        else {
                            val f = Engine.setPadCue(p, wvCursor)
                            wvCursor = f
                            hint(if (f <= 0) "Pad ${p + 1} startet wieder am Anfang"
                            else String.format(Locale.GERMANY, "◆ Pad %d startet jetzt bei %.3f s – nächster Druck spielt ab hier", p + 1, f / Engine.sampleRate.toFloat()))
                        }
                    }
                    17 -> {
                        if (Engine.padCue(p) > 0) {
                            Engine.setPadCue(p, 0)
                            hint("Pad ${p + 1} startet wieder am Anfang")
                        }
                    }
                    18 -> wvZoomBy(0.5f, wvCursor.toDouble(), wvX(wvCursor.toDouble()).coerceIn(wvWave.left, wvWave.right))
                    19 -> {
                        val cx = wvX(wvCursor.toDouble())
                        val ax = if (cx < wvWave.left || cx > wvWave.right) wvWave.centerX() else cx
                        wvZoomBy(2f, wvCursor.toDouble(), ax)
                        if (wvZoom >= wvMaxZoom()) hint("Größter Zoom – einzelne Samples")
                    }
                    20 -> if (wvZoom > 1.01f) wvResetView() else hint("Zoomen: 🔍+ oder mit zwei Fingern auf der Welle auseinanderziehen")
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (wvPinchA >= 0) {
                    val pa = e.findPointerIndex(wvPinchA)
                    val pb = e.findPointerIndex(wvPinchB)
                    if (pa >= 0 && pb >= 0) {
                        val d = max(dp(10f), kotlin.math.abs(e.getX(pa) - e.getX(pb)))
                        val mid = (e.getX(pa) + e.getX(pb)) / 2f
                        wvZoom = (wvPinchZ0 * d / wvPinchD0).coerceIn(1f, wvMaxZoom())
                        wvViewFrom = wvPinchAnchor - ((mid - wvWave.left) / wvWave.width()) * wvSpan()
                        wvClampView()
                    }
                    return true
                }
                if (wvPointer >= 0) {
                    val pi = e.findPointerIndex(wvPointer)
                    if (pi >= 0) {
                        val x = e.getX(pi)
                        // gezoomt: am Rand weiterblättern
                        if (wvZoom > 1.01f) {
                            val edge = wvWave.width() * 0.06f
                            val sp = wvSpan()
                            if (x < wvWave.left + edge) { wvViewFrom -= sp * 0.02; wvClampView() }
                            else if (x > wvWave.right - edge) { wvViewFrom += sp * 0.02; wvClampView() }
                        }
                        wvCursor = wvFrameAt(x)
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (wvPinchA >= 0 && (id == wvPinchA || id == wvPinchB)) {
                    wvPinchA = -1
                    wvPinchB = -1
                    wvPointer = -1
                    return true
                }
                if (id == wvPointer) {
                    wvPointer = -1
                    if (wvPreview >= 0) wvPlay(wvCursor)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> { wvPointer = -1; wvPinchA = -1; wvPinchB = -1; return false }
        }
        return false
    }


    // ---------------------------------------------------------------- Drum-Modus (große Pad-Ansicht)

    /** Drum-Modus: Pads zum Einspielen – Drumset laden, Aufnahme mit feinem Raster, Spur-Übersicht. */
    var drumMode = false
        private set
    /** Anschlagstärke nach Tipp-Höhe im Pad (oben = laut). */
    /** Einspiel-Timing & Anschlag einstellen (Dialog). */
    var onTimingCalib: (() -> Unit)? = null
    private val drToggle = RectF()
    private val drPanel = RectF()
    private val drLine = RectF()
    private val drBtns = Array(12) { RectF() }
    private val QUANTS = intArrayOf(0, 2, 4, 8, 12, 16, 24, 32)
    private val DBARS = intArrayOf(1, 2, 4, 8)

    private fun drumActive(): Boolean = focus == 1 && drumMode

    fun setDrumMode(on: Boolean) {
        drumMode = on
        Engine.drumRec = drumActive()
        if (on) {
            wvStop()
            hint("🥁 Drum-Modus: ⚡-Pads spielen sofort, ● REC nimmt die Anschläge auf (läuft im Kreis, Schicht für Schicht). Leere Pads = Sample laden")
        } else hint("Drum-Modus aus")
        invalidate()
    }

    private fun quantName(q: Int): String = when {
        q <= 0 -> "FREI"
        q == 12 -> "1/8T"
        q == 24 -> "1/16T"
        else -> "1/$q"
    }

    private fun layoutDrumPanel() {
        val r = drPanel
        val rowH = dp(32f)
        val gap = dp(5f)
        val bottom = r.bottom - dp(8f)
        var y = bottom - 2 * rowH - gap
        drLine.set(r.left + dp(10f), r.top + dp(28f), r.right - dp(10f), max(r.top + dp(48f), y - dp(8f)))
        var i = 0
        for (row in 0 until 2) {
            val n = 6
            val w = (r.width() - dp(20f) - (n - 1) * gap) / n
            for (k in 0 until n) {
                val x = r.left + dp(10f) + k * (w + gap)
                drBtns[i++].set(x, y, x + w, y + rowH)
            }
            y += rowH + gap
        }
    }

    private fun drumPads(): List<Int> = (0 until VISIBLE_PADS).filter { Engine.isOneShot(it) && (Engine.hasLoop(it) || Engine.noteCount(it) > 0) }

    private fun drawDrumPanel(canvas: Canvas) {
        val cols = overlayColors()
        val r = drPanel
        layoutDrumPanel()
        fillPaint.shader = null
        fillPaint.color = alpha(blend(cols[0], cols[1], 0.04f), 0.96f)
        canvas.drawRoundRect(r, dp(12f), dp(12f), fillPaint)
        strokePaint.strokeWidth = dp(1.3f)
        strokePaint.color = alpha(if (Engine.seqRecording) Color.rgb(255, 60, 60) else cols[2], 0.7f)
        canvas.drawRoundRect(r, dp(12f), dp(12f), strokePaint)
        val bars = Engine.drumBars
        val prog = Engine.drumProgress()
        // Kopfzeile: Status
        val bpm = Engine.currentBpm()
        var head = "🥁 " + (if (bpm > 0f) String.format(Locale.GERMANY, "%.1f BPM", bpm) else "kein Tempo – Takt = 2 s") + " · $bars ${if (bars == 1) "Takt" else "Takte"} · Raster ${quantName(Engine.rollQuant)}"
        if (prog >= 0f) {
            val pos = prog * bars
            head += " · Takt ${pos.toInt() + 1}.${((pos - pos.toInt()) * 4).toInt() + 1}"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = cols[1]
        canvas.drawText(fitLabel(textPaint, head, r.width() * 0.68f, dp(12f), dp(8f)), r.left + dp(10f), r.top + dp(19f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val st = when {
            Engine.countingIn() -> "EINZÄHLEN …"
            Engine.seqRecording && Engine.seqPlaying -> "● AUFNAHME"
            Engine.seqPlaying -> "▶ LÄUFT"
            else -> "■ STEHT"
        }
        textPaint.color = if (Engine.seqRecording) Color.rgb(255, 80, 80) else cols[2]
        canvas.drawText(fitLabel(textPaint, st, r.width() * 0.3f, dp(13f), dp(8f)), r.right - dp(10f), r.top + dp(19f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        // Spur-Übersicht: eine Zeile je ⚡-Pad, Anschläge als Striche
        val tl = drLine
        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(tl, dp(6f), dp(6f), fillPaint)
        val list = drumPads()
        val lab = min(dp(78f), tl.width() * 0.22f)
        val gx = tl.left + lab
        val gw = tl.right - dp(4f) - gx
        if (list.isEmpty()) {
            textPaint.textSize = dp(12f)
            textPaint.color = alpha(cols[1], 0.75f)
            canvas.drawText(fitLabel(textPaint, "🥁 DRUMSET legt ein Set auf die Pads – oder leere Pads antippen und Samples laden", tl.width() - dp(16f), dp(12f), dp(8f)), tl.centerX(), tl.centerY() + dp(4f), textPaint)
        } else {
            // Raster
            val q = if (Engine.rollQuant > 0) Engine.rollQuant else 16
            val steps = bars * q
            if (gw / steps > dp(3f)) for (k in 0..steps) {
                val x = gx + gw * k / steps
                val bar = k % q == 0
                val beat = (k * 4) % q == 0
                strokePaint.strokeWidth = dp(if (bar) 1.4f else 0.6f)
                strokePaint.color = Color.argb(if (bar) 130 else if (beat) 55 else 20, 255, 255, 255)
                canvas.drawLine(x, tl.top + dp(2f), x, tl.bottom - dp(2f), strokePaint)
            } else for (k in 0..bars * 4) {
                val x = gx + gw * k / (bars * 4)
                strokePaint.strokeWidth = dp(if (k % 4 == 0) 1.4f else 0.6f)
                strokePaint.color = Color.argb(if (k % 4 == 0) 130 else 55, 255, 255, 255)
                canvas.drawLine(x, tl.top + dp(2f), x, tl.bottom - dp(2f), strokePaint)
            }
            val rh = (tl.height() - dp(4f)) / list.size
            for ((n, p) in list.withIndex()) {
                val y0 = tl.top + dp(2f) + n * rh
                if (p == selPad) {
                    fillPaint.color = alpha(cols[2], 0.18f)
                    canvas.drawRect(tl.left, y0, tl.right, y0 + rh, fillPaint)
                }
                textPaint.textSize = min(dp(11f), rh * 0.75f)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = if (p == selPad) cols[2] else alpha(cols[1], 0.85f)
                val nm = "${p + 1} " + (Engine.padName(p) ?: "")
                canvas.drawText(fitLabel(textPaint, nm, lab - dp(8f), textPaint.textSize, dp(6f)), tl.left + dp(5f), y0 + rh * 0.5f + textPaint.textSize * 0.36f, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                val rb = max(1, Engine.rollBars[p])
                val hit = flash[p]
                fillPaint.color = padAccent(p)
                for (ev in Engine.notesOf(p)) {
                    var k = 0
                    while (ev.pos + k * rb < bars && k < 64) {
                        val x = gx + gw * ((ev.pos + k * rb) / bars).toFloat()
                        val hh = rh * (0.35f + 0.6f * ev.vel) * 0.5f
                        canvas.drawRect(x - dp(1.2f), y0 + rh / 2 - hh, x + dp(1.2f), y0 + rh / 2 + hh, fillPaint)
                        k++
                    }
                }
                if (hit > 0f) {
                    fillPaint.color = alpha(Color.WHITE, 0.25f * hit)
                    canvas.drawRect(tl.left, y0, gx, y0 + rh, fillPaint)
                }
            }
            if (prog >= 0f) {
                val x = gx + gw * prog
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = if (Engine.seqRecording) Color.rgb(255, 70, 70) else Color.rgb(80, 230, 120)
                canvas.drawLine(x, tl.top, x, tl.bottom, strokePaint)
            }
        }
        // Knöpfe
        val rec = Engine.seqRecording
        val sel = selPad
        val labels = arrayOf(
            if (rec) "■ REC" else "● REC", if (Engine.seqPlaying) "■ STOPP" else "▶ PLAY", if (Engine.metronome) "♩ KLICK AN" else "♩ KLICK",
            if (Engine.rollQuant > 0) "AUTO-Q ${quantName(Engine.rollQuant)}" else "AUTO-Q AUS", "LÄNGE $bars T", "↶ UNDO",
            "🥁 DRUMSET", "ANSCHLAG: " + TouchVelocity.MODES[TouchVelocity.mode], "⏱ TIMING " + String.format(Locale.GERMANY, "%+.0f ms", -Engine.recOffsetMs),
            "⌗ QUANT. ${quantName(if (Engine.rollQuant > 0) Engine.rollQuant else 16)}", "⌫ LEEREN", "🎹 NOTEN"
        )
        val on = booleanArrayOf(rec, Engine.seqPlaying, Engine.metronome, Engine.rollQuant > 0, false, false, false, TouchVelocity.mode != 0, Engine.recOffsetMs != 0f, false, false, false)
        for (i in 0 until 12) {
            smallBtn(canvas, drBtns[i], labels[i], on[i], cols)
            if (i == 0 && rec) {
                fillPaint.color = Color.argb(70, 255, 40, 40)
                canvas.drawRoundRect(drBtns[i], dp(8f), dp(8f), fillPaint)
            }
        }
    }

    /** Pad-Name groß im Pad (Drum-Modus). */
    private fun instPadName(code: Int): String = when (code) {
        0 -> "BASS 303"; 1 -> "DRUMS 909"; in 2..9 -> "SYNTH ${code - 1}"
        10 -> "EUKLID"; 11 -> "ATMOS"; else -> "HARMONIZER"
    }
    private fun instPadIcon(code: Int): String = when (code) { 0 -> "🔊"; 1 -> "🥁"; in 2..9 -> "🎹"; 10 -> "⬡"; 11 -> "🌌"; else -> "🎤" }
    private fun instPadColor(code: Int): Int = when (code) {
        0 -> Color.rgb(90, 190, 255); 1 -> Color.rgb(255, 150, 70); 2 -> Color.rgb(200, 140, 255); 3 -> Color.rgb(240, 120, 200); 4 -> Color.rgb(120, 230, 140)
        10 -> Color.rgb(255, 170, 90); 11 -> Color.rgb(140, 170, 255); else -> Color.rgb(255, 120, 190)
    }

    /** Instrument-Pad: zeigt, welches Instrument hier liegt und ob es läuft. */
    private fun drawInstPad(canvas: Canvas, i: Int) {
        val code = Engine.padInst[i]
        if (code < 0) return
        val r = padRects[i]
        if (r.width() <= 0f) return
        val col = instPadColor(code)
        val on = Engine.instCodeOn(code)
        val pulse = if (on && Engine.seqPlaying) 0.5f + 0.5f * sin(time * 6f) else 0f
        val rr = min(dp(14f), r.width() * 0.16f)
        fillPaint.shader = null
        fillPaint.color = if (on) alpha(col, 0.55f + 0.2f * pulse) else alpha(col, 0.14f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(if (on) 2.5f else 1.4f)
        strokePaint.color = alpha(col, if (on) 1f else 0.6f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = min(dp(26f), r.width() * 0.3f)
        canvas.drawText(instPadIcon(code), r.centerX(), r.centerY() + textPaint.textSize * 0.15f, textPaint)
        textPaint.textSize = min(dp(12f), r.width() * 0.13f)
        textPaint.setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
        canvas.drawText(fitLabel(textPaint, instPadName(code), r.width() * 0.9f, textPaint.textSize, dp(6.5f)), r.centerX(), r.top + dp(8f) + textPaint.textSize, textPaint)
        textPaint.textSize = min(dp(11f), r.width() * 0.12f)
        textPaint.color = if (on) Color.WHITE else Color.argb(170, 255, 255, 255)
        canvas.drawText(if (on) "● AN" else "○ AUS", r.centerX(), r.bottom - dp(9f), textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawPadName(canvas: Canvas, i: Int) {
        val n = Engine.padName(i) ?: return
        val r = padRects[i]
        if (r.width() <= 0f) return
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.argb(230, 255, 255, 255)
        val t = fitLabel(textPaint, n.uppercase(Locale.GERMANY), r.width() * 0.86f, min(dp(15f), r.width() * 0.14f), dp(7f))
        textPaint.setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
        // oben im Pad – unten sitzen Eingangs- und FX-Plakette
        canvas.drawText(t, r.centerX(), r.top + dp(9f) + textPaint.textSize, textPaint)
        textPaint.clearShadowLayer()
    }

    /** Touch auf der Drum-Leiste (Bildschirm-Koordinaten). */
    private fun drumPanelTouch(e: MotionEvent): Boolean {
        val am = e.actionMasked
        if (am != MotionEvent.ACTION_DOWN && am != MotionEvent.ACTION_POINTER_DOWN) return false
        val idx = e.actionIndex
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (!drPanel.contains(x, y)) return false
        layoutDrumPanel()
        if (drLine.contains(x, y)) {
            val list = drumPads()
            if (list.isNotEmpty()) {
                val k = (((y - drLine.top) / drLine.height()) * list.size).toInt().coerceIn(0, list.size - 1)
                selPad = list[k]
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            return true
        }
        val i = drBtns.indexOfFirst { it.contains(x, y) }
        if (i < 0) return true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (i) {
            0 -> {
                Engine.seqToggleRec()
                hint(if (Engine.seqRecording) (if (Engine.countIn) "● Aufnahme – erst 1 Takt einzählen, dann einspielen. Jede Runde kommt dazu" else "● Aufnahme läuft – einspielen, jede Runde kommt dazu") else "Aufnahme beendet – ▶ läuft weiter, ↶ UNDO nimmt sie zurück")
            }
            1 -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
            2 -> { Engine.metronome = !Engine.metronome; hint(if (Engine.metronome) "Klick an" else "Klick aus") }
            3 -> {
                val k = (QUANTS.indexOf(Engine.rollQuant) + 1) % QUANTS.size
                Engine.rollQuant = QUANTS[k]
                hint(if (QUANTS[k] == 0) "AUTO-Q aus: Anschläge bleiben genau so, wie gespielt (⌗ QUANT. zieht sie später aufs Raster)"
                else "AUTO-Q ${quantName(QUANTS[k])}: jeder Anschlag rastet beim Aufnehmen auf ${quantName(QUANTS[k])} ein" + if (QUANTS[k] == 12 || QUANTS[k] == 24) " (Triolen)" else "")
            }
            4 -> {
                val k = (DBARS.indexOf(Engine.drumBars).coerceAtLeast(0) + 1) % DBARS.size
                Engine.changeDrumBars(DBARS[k])
                hint("Drum-Spur: ${DBARS[k]} ${if (DBARS[k] == 1) "Takt" else "Takte"} (längere Spur wiederholt das bisherige Muster)")
            }
            5 -> { val l = Engine.undo(); hint(if (l == null) "Nichts rückgängig zu machen" else "↶ Rückgängig: $l") }
            6 -> onDrumKit?.invoke()
            7 -> {
                TouchVelocity.mode = (TouchVelocity.mode + 1) % 3
                TouchVelocity.update(context)
                context.getSharedPreferences("marvpad", Context.MODE_PRIVATE).edit().putInt("velMode", TouchVelocity.mode).apply()
                hint(when (TouchVelocity.mode) {
                    0 -> "Anschlag FEST: immer volle Lautstärke"
                    1 -> "Anschlag HÖHE: oben im Pad = laut, unten = leise"
                    else -> "Anschlag STÄRKE: fest tippen = laut, leicht = leise (Bewegungssensor + Fingerfläche, lernt nach ein paar Schlägen mit)"
                })
            }
            8 -> onTimingCalib?.invoke()
            9 -> {
                val q = if (Engine.rollQuant > 0) Engine.rollQuant else 16
                val n = Engine.quantizeHits(q)
                hint(if (n == 0) "Noch keine eingespielten Schläge" else "⌗ $n Schläge auf ${quantName(q)} gezogen – ↶ UNDO macht es rückgängig")
            }
            10 -> onDrumClear?.invoke(sel())
            11 -> if (sel() >= 0 && Engine.hasLoop(sel())) { closeFocus(); openKeyboard(sel()) } else hint("Erst ein Pad antippen")
        }
        return true
    }

    private fun sel(): Int = selPad

    // ---------------------------------------------------------------- Mischpult-Ansicht

    /** Mischpult statt Pads: Fader, Mute, Solo und Auswahl pro Pad. */
    var mixView = false
        private set
    private val mixBtn = RectF()
    private val mixRect = RectF()
    private var mixSel = -1
    /** pad = -2: Crossfader-Griff. */
    private class MixPtr(val pad: Int, val startY: Float, val startGain: Float, val faderH: Float)
    private val mixPtr = HashMap<Int, MixPtr>()
    private var mixLastTapPad = -1
    private var mixLastTapZone = -1
    private var mixLastTapMs = 0L
    private val mixZones = Array(4) { RectF() }

    fun setMixView(on: Boolean) { if (mixView != on) toggleMixView() }

    private fun toggleMixView() {
        mixView = !mixView
        mixPtr.clear()
        if (mixView) {
            sceneView = false
            func = F_NONE
            // der alte Pad-Crossfader ist weg – nichts darf mehr leiser bleiben
            Engine.xfA = -1; Engine.xfB = -1
            hint("MIX: jede Quelle, die etwas spielt, als Kanal · großer Knopf = an/aus · Fader = Lautstärke")
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---- Kanäle: nur Quellen mit Inhalt (Pads, Instrumente mit Noten, laufende Zusatz-Instrumente)
    private class MixCh(val kind: Int, val idx: Int)   // kind 0 Pad, 1 Instrument (0 ACID, 1 DRUM, 2.. SYNTH), 2 Extra (0 EUKLID, 1 ATMOS, 2 HARMONIZER)

    private fun mixChannels(): List<MixCh> {
        val out = ArrayList<MixCh>()
        for (i in listOf(1, 0, 2, 3, 4)) if (i < InstState.INSTS && InstState.instHasNotes(i)) out.add(MixCh(1, i))
        for (k in 0 until 3) if (when (k) { 0 -> Euclid.on; 1 -> Atmos.on || Atmos.active(); else -> Harmonizer.on }) out.add(MixCh(2, k))
        for (p in 0 until VISIBLE_PADS) if (Engine.hasLoop(p)) out.add(MixCh(0, p))
        return out
    }

    private fun chName(c: MixCh): String = when (c.kind) {
        0 -> Engine.padName(c.idx)?.take(12) ?: "Pad ${c.idx + 1}"
        1 -> when (c.idx) { 0 -> "BASS 303"; 1 -> "DRUMS 909"; else -> "SYNTH ${c.idx - 1}" }
        else -> arrayOf("EUKLID", "ATMOS", "HARMONIZER")[c.idx]
    }
    private fun chSub(c: MixCh): String = when (c.kind) {
        0 -> "Pad ${c.idx + 1} · " + if (Engine.isOneShot(c.idx)) "⚡" else "∞ Loop"
        1 -> Engine.padOfInst(c.idx).let { if (it >= 0) "Pad ${it + 1}" else "Instrument" }
        else -> Engine.padOfInst(10 + c.idx).let { if (it >= 0) "Pad ${it + 1}" else "Generator" }
    }
    private fun chIcon(c: MixCh): String = when (c.kind) {
        0 -> if (Engine.isOneShot(c.idx)) "⚡" else "∿"
        1 -> when (c.idx) { 0 -> "🔊"; 1 -> "🥁"; else -> "🎹" }
        else -> arrayOf("⬡", "🌌", "🎤")[c.idx]
    }
    private fun chOn(c: MixCh): Boolean = when (c.kind) {
        0 -> !Engine.isMuted(c.idx)
        1 -> InstState.instOn(c.idx)
        else -> when (c.idx) { 0 -> Euclid.on; 1 -> Atmos.on; else -> Harmonizer.on }
    }
    private fun chSetOn(c: MixCh, v: Boolean) {
        when (c.kind) {
            0 -> if (Engine.isMuted(c.idx) == v) Engine.toggleMute(c.idx)
            1 -> { InstState.setInstOn(c.idx, v); if (v && !Engine.seqPlaying) Engine.transportPlay(false) }
            else -> when (c.idx) { 0 -> Euclid.on = v; 1 -> Atmos.on = v; else -> Harmonizer.on = v }
        }
    }
    /** Lautstärke 0..1.5 (1 = Normal). */
    private fun chGain(c: MixCh): Float = when (c.kind) {
        0 -> Engine.padGain(c.idx)
        1 -> when (c.idx) { 0 -> InstState.acidVol / 0.75f; 1 -> InstState.drumVol / 0.8f; else -> InstState.synTracks[c.idx - 2].vol / 0.6f }
        else -> when (c.idx) { 0 -> Euclid.level / 0.7f; 1 -> Atmos.level / 0.6f; else -> Harmonizer.level / 0.7f }
    }.coerceIn(0f, 1.5f)
    private fun chSetGain(c: MixCh, g0: Float) {
        val g = g0.coerceIn(0f, 1.5f)
        when (c.kind) {
            0 -> Engine.setPadGain(c.idx, g)
            1 -> when (c.idx) { 0 -> InstState.acidVol = (0.75f * g).coerceAtMost(1f); 1 -> InstState.drumVol = (0.8f * g).coerceAtMost(1f); else -> InstState.synTracks[c.idx - 2].vol = (0.6f * g).coerceAtMost(1f) }
            else -> when (c.idx) { 0 -> Euclid.level = (0.7f * g).coerceAtMost(1f); 1 -> Atmos.level = (0.6f * g).coerceAtMost(1f); else -> Harmonizer.level = (0.7f * g).coerceAtMost(1f) }
        }
    }
    /** Pegel 0..1 für die Anzeige. */
    private fun chLevel(c: MixCh): Float = when (c.kind) {
        0 -> if (chOn(c)) Engine.levels[c.idx] else 0f
        1 -> if (chOn(c) && Engine.seqPlaying) 0.35f + 0.25f * (0.5f + 0.5f * sin(time * 9f + c.idx)) else 0f
        else -> when (c.idx) { 0 -> if (Euclid.on) Euclid.laneFlash.maxOrNull() ?: 0f else 0f; 1 -> Atmos.meter; else -> Harmonizer.meter }
    }
    private fun chColor(c: MixCh): Int = when (c.kind) {
        0 -> padAccent(c.idx)
        1 -> intArrayOf(Color.rgb(90, 190, 255), Color.rgb(255, 150, 70), Color.rgb(200, 140, 255), Color.rgb(240, 120, 200), Color.rgb(120, 230, 140))[c.idx.coerceIn(0, 4)]
        else -> intArrayOf(Color.rgb(255, 170, 90), Color.rgb(140, 170, 255), Color.rgb(255, 120, 190))[c.idx]
    }

    private var mixList: List<MixCh> = emptyList()
    private val mixStrips = ArrayList<RectF>()
    private val mixOnBtn = ArrayList<RectF>()
    private val mixFader = ArrayList<RectF>()

    private fun mixLayoutAll() {
        mixList = mixChannels()
        val n = mixList.size
        mixStrips.clear(); mixOnBtn.clear(); mixFader.clear()
        if (n == 0) return
        val r = mixRect
        val minW = dp(54f)
        val perMax = max(1, ((r.width() - dp(8f)) / minW).toInt())
        val rows = when { n <= perMax -> 1; n <= 2 * perMax -> 2; else -> 3 }
        val per = (n + rows - 1) / rows
        val sw = (r.width() - dp(8f)) / per
        val rh = (r.height() - dp(8f)) / rows
        for (k in 0 until n) {
            val row = k / per; val col = k % per
            val st = RectF(r.left + dp(4f) + col * sw + dp(2f), r.top + dp(4f) + row * rh + dp(2f), r.left + dp(4f) + (col + 1) * sw - dp(2f), r.top + dp(4f) + (row + 1) * rh - dp(2f))
            mixStrips.add(st)
            val bh = min(dp(46f), st.height() * 0.24f)
            mixOnBtn.add(RectF(st.left + dp(3f), st.bottom - bh - dp(3f), st.right - dp(3f), st.bottom - dp(3f)))
            mixFader.add(RectF(st.left + dp(6f), st.top + dp(54f), st.right - dp(6f), st.bottom - bh - dp(8f)))
        }
    }

    private fun mixDown(id: Int, x: Float, y: Float) {
        mixLayoutAll()
        val k = mixStrips.indexOfFirst { RectF(it).apply { inset(-dp(2f), -dp(2f)) }.contains(x, y) }
        if (k < 0) return
        val c = mixList[k]
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (RectF(mixOnBtn[k]).apply { inset(-dp(3f), -dp(3f)) }.contains(x, y) || y < mixFader[k].top - dp(4f)) {
            val v = !chOn(c)
            chSetOn(c, v)
            burst(mixOnBtn[k].centerX(), mixOnBtn[k].centerY(), chColor(c), 14, 180f)
            hint("${chName(c)} " + if (v) "an" else "aus")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - mixLastTapMs < 320L && mixLastTapPad == k) { chSetGain(c, 1f); hint("${chName(c)}: 0 dB") }
        mixLastTapMs = now; mixLastTapPad = k
        mixPtr[id] = MixPtr(k, y, chGain(c), max(dp(40f), mixFader[k].height()))
    }

    private fun mixMove(id: Int, x: Float, y: Float) {
        val m = mixPtr[id] ?: return
        val c = mixList.getOrNull(m.pad) ?: return
        val g = (m.startGain + (m.startY - y) / m.faderH * 1.5f).coerceIn(0f, 1.5f)
        chSetGain(c, if (abs(g - 1f) < 0.025f) 1f else g)
    }

    private fun gainDb(g: Float): String {
        if (g <= 0.001f) return "-∞"
        val db = 20f * log10(g)
        return if (abs(db) < 0.05f) "0.0" else String.format(Locale.GERMANY, "%+.1f", db)
    }

    private fun drawMixButton(canvas: Canvas, cols: IntArray) {
        val r = mixBtn
        if (r.width() <= 0f) return
        val rr = dp(7f)
        val on = mixView
        fillPaint.shader = null
        fillPaint.color = if (on) alpha(cols[2], 0.85f) else blend(cols[0], cols[1], 0.08f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.3f)
        strokePaint.color = if (on) cols[2] else alpha(cols[1], 0.6f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        val fg = if (on) cols[0] else cols[1]
        val iy0 = r.top + dp(7f)
        val iy1 = r.bottom - dp(15f)
        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = alpha(fg, 0.8f)
        fillPaint.color = fg
        val kn = floatArrayOf(0.3f, 0.65f, 0.45f)
        for (k in 0 until 3) {
            val fx = r.centerX() + (k - 1) * dp(6f)
            canvas.drawLine(fx, iy0, fx, iy1, strokePaint)
            val ky = iy0 + (iy1 - iy0) * kn[k]
            canvas.drawRect(fx - dp(2.5f), ky - dp(1.6f), fx + dp(2.5f), ky + dp(1.6f), fillPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(7.5f)
        textPaint.color = alpha(fg, 0.85f)
        canvas.drawText(if (on) "PADS" else "MIX", r.centerX(), r.bottom - dp(4f), textPaint)
    }

    private fun drawMixer(canvas: Canvas) {
        val cols = overlayColors()
        val bg = cols[0]
        val fg = cols[1]
        val acc = cols[2]
        val rr = dp(10f)
        fillPaint.shader = null
        fillPaint.color = alpha(blend(bg, fg, 0.05f), 0.92f)
        canvas.drawRoundRect(mixRect, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = alpha(acc, 0.5f)
        canvas.drawRoundRect(mixRect, rr, rr, strokePaint)
        textPaint.textAlign = Paint.Align.CENTER
        mixLayoutAll()
        if (mixList.isEmpty()) {
            textPaint.textSize = dp(13f); textPaint.color = alpha(fg, 0.8f)
            canvas.drawText("Noch spielt nichts", mixRect.centerX(), mixRect.centerY() - dp(8f), textPaint)
            textPaint.textSize = dp(10f); textPaint.color = alpha(fg, 0.55f)
            canvas.drawText(fitLabel(textPaint, "Pads aufnehmen oder Noten in 303/909/SYNTH setzen – dann erscheinen hier ihre Kanäle", mixRect.width() - dp(20f), dp(10f), dp(7f)), mixRect.centerX(), mixRect.centerY() + dp(12f), textPaint)
            return
        }
        val red = Color.rgb(255, 60, 80)
        val yellow = Color.rgb(255, 200, 40)
        for ((k, c) in mixList.withIndex()) {
            val st = mixStrips[k]
            val col = chColor(c)
            val on = chOn(c)
            // Kanalzug
            fillPaint.color = alpha(on.let { if (it) col else fg }, if (on) 0.10f else 0.04f)
            canvas.drawRoundRect(st, dp(8f), dp(8f), fillPaint)
            strokePaint.strokeWidth = dp(if (on) 1.4f else 0.8f)
            strokePaint.color = alpha(if (on) col else fg, if (on) 0.8f else 0.25f)
            canvas.drawRoundRect(st, dp(8f), dp(8f), strokePaint)
            // Name
            textPaint.textSize = min(dp(11f), st.width() * 0.2f)
            textPaint.color = alpha(fg, if (on) 1f else 0.55f)
            canvas.drawText(fitLabel(textPaint, chIcon(c) + " " + chName(c), st.width() - dp(6f), textPaint.textSize, dp(6.5f)), st.centerX(), st.top + dp(16f), textPaint)
            textPaint.textSize = min(dp(8.5f), st.width() * 0.16f)
            textPaint.color = alpha(fg, 0.5f)
            canvas.drawText(fitLabel(textPaint, chSub(c), st.width() - dp(6f), textPaint.textSize, dp(6f)), st.centerX(), st.top + dp(29f), textPaint)
            // Fader + Pegel
            val f = mixFader[k]
            val g = chGain(c)
            val tw = min(dp(9f), f.width() * 0.22f)
            val tx = f.centerX() - dp(4f)
            tmpRect.set(tx - tw / 2f, f.top, tx + tw / 2f, f.bottom)
            fillPaint.color = alpha(fg, 0.12f)
            canvas.drawRoundRect(tmpRect, tw / 2f, tw / 2f, fillPaint)
            val fy = f.bottom - f.height() * (g / 1.5f)
            fillPaint.color = alpha(if (on) col else fg, if (on) 0.85f else 0.25f)
            tmpRect.set(tx - tw / 2f, fy, tx + tw / 2f, f.bottom)
            canvas.drawRoundRect(tmpRect, tw / 2f, tw / 2f, fillPaint)
            val uy = f.bottom - f.height() / 1.5f
            strokePaint.strokeWidth = dp(1f); strokePaint.color = alpha(fg, 0.35f)
            canvas.drawLine(tx - tw, uy, tx + tw, uy, strokePaint)
            val mx = tx + tw / 2f + dp(5f)
            val mw = dp(4f)
            fillPaint.color = alpha(fg, 0.08f)
            canvas.drawRect(mx, f.top, mx + mw, f.bottom, fillPaint)
            val lv = chLevel(c).coerceIn(0f, 1f)
            if (lv > 0.005f) {
                fillPaint.color = if (lv > 0.95f) red else if (lv > 0.7f) yellow else Color.rgb(60, 230, 120)
                canvas.drawRect(mx, f.bottom - f.height() * lv, mx + mw, f.bottom, fillPaint)
            }
            val cw = min(f.width() * 0.7f, dp(30f))
            val ch = dp(14f)
            tmpRect.set(tx - cw / 2f, fy - ch / 2f, tx + cw / 2f, fy + ch / 2f)
            fillPaint.color = blend(bg, fg, 0.85f)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = if (mixPtr.values.any { it.pad == k }) acc else alpha(bg, 0.7f)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), strokePaint)
            textPaint.textSize = min(dp(9f), st.width() * 0.17f)
            textPaint.color = alpha(fg, 0.6f)
            canvas.drawText(gainDb(g) + " dB", st.centerX(), f.top - dp(7f), textPaint)
            // großer AN/AUS-Knopf
            val b = mixOnBtn[k]
            fillPaint.color = if (on) col else alpha(fg, 0.07f)
            canvas.drawRoundRect(b, dp(7f), dp(7f), fillPaint)
            strokePaint.strokeWidth = dp(1.2f); strokePaint.color = alpha(if (on) col else fg, if (on) 1f else 0.4f)
            canvas.drawRoundRect(b, dp(7f), dp(7f), strokePaint)
            textPaint.textSize = min(dp(13f), b.height() * 0.42f)
            textPaint.color = if (on) Color.BLACK else alpha(fg, 0.75f)
            canvas.drawText(if (on) "AN" else "AUS", b.centerX(), b.centerY() + textPaint.textSize * 0.36f, textPaint)
        }
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
        drawSeqInfo(canvas, acc, alpha(Color.WHITE, 0.65f))
    }

    /** UNDO/REDO-Knöpfe und Status rechts neben dem Transport. */
    private fun drawSeqInfo(canvas: Canvas, headCol: Int, subCol: Int) {
        val cols = overlayColors()
        for ((r, lab, on) in arrayOf(Triple(undoBtn, "↶", Engine.undoCount > 0), Triple(redoBtn, "↷", Engine.redoCount > 0))) {
            if (r.width() <= 0f) continue
            val rr = dp(7f)
            fillPaint.shader = null
            fillPaint.color = blend(cols[0], cols[1], 0.08f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.3f)
            strokePaint.color = alpha(cols[1], if (on) 0.6f else 0.2f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.textSize = dp(18f)
            textPaint.color = alpha(cols[1], if (on) 1f else 0.3f)
            canvas.drawText(lab, r.centerX(), r.centerY() + dp(4f), textPaint)
            textPaint.textSize = dp(7.5f)
            textPaint.color = alpha(cols[1], if (on) 0.7f else 0.25f)
            canvas.drawText(if (r === undoBtn) "UNDO" else "REDO", r.centerX(), r.bottom - dp(4f), textPaint)
        }
        drawMixButton(canvas, cols)
        if (!zoomPads.isEmpty) zoomBtn(canvas, zoomPads, cols)
        val ix = mixBtn.right + dp(8f)
        val avail = seqFoldBtn.left - dp(4f) - ix
        if (avail < dp(28f)) return
        val cin = Engine.countInBeat()
        val red = Color.rgb(255, 60, 80)
        val head = when {
            Engine.songRecording -> {
                val t = Engine.songSeconds().toInt()
                String.format(Locale.GERMANY, "● SONG %d:%02d", t / 60, t % 60)
            }
            cin > 0 -> "◔ ZÄHLT $cin"
            Engine.seqRecording -> "● REC"
            Engine.seqPlaying -> "▶ PLAY"
            else -> "■ STOP"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = min(dp(11f), avail / (head.length * 0.62f))
        textPaint.color = if (cin > 0 || Engine.seqRecording || Engine.songRecording) red else headCol
        canvas.drawText(head, ix, recBtn.top + dp(12f), textPaint)
        val q = Engine.queuedScene
        val l2 = "SZENE ${Engine.curScene + 1}" + (if (q >= 0) "→${q + 1}" else "") + if (Engine.sceneChain) " ⛓" else ""
        textPaint.textSize = min(dp(9.5f), avail / (l2.length * 0.6f))
        textPaint.color = subCol
        canvas.drawText(l2, ix, recBtn.top + dp(25f), textPaint)
        val sel = Engine.seqSelected
        val l3 = if (Engine.repeatOn) "RPT ${Engine.repeatName()}" else if (sel >= 0) "Pad ${sel + 1}" else "⚡-Pad tippen"
        textPaint.textSize = min(dp(9.5f), avail / (l3.length * 0.6f))
        canvas.drawText(l3, ix, recBtn.top + dp(37f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Status-Farben wie bei Clip-Launchern: Rot = Aufnahme, Grün = Play, Gelb = Overdub, Blinken = wartet auf die Eins. */
    var statusColors = true
    private val segPath = Path()
    private val iconRect = RectF()

    private fun drawStatus(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val st = Engine.state(i)
        val q = Engine.queuedState(i)
        val armed = Engine.isArmed(i)
        val latched = Engine.isLatched(i)
        val blink = if (sin(time * 12f) > 0f) 1f else 0.25f
        val pulse = 0.5f + 0.5f * sin(time * 7f)
        val green = Color.rgb(40, 230, 110)
        val red = Color.rgb(255, 45, 65)
        val yellow = Color.rgb(255, 190, 30)
        if (st == Engine.PadState.EMPTY && !armed) {
            drawEmptyPad(canvas, i)
            return
        }
        if (!statusColors) return
        var c = 0
        var tint = 0f
        var ringA = 0f
        var arc = -1f
        when {
            armed -> { c = red; ringA = blink; tint = 0.14f * blink }
            st == Engine.PadState.RECORDING -> {
                c = red; ringA = 0.7f + 0.3f * pulse; tint = 0.16f + 0.14f * pulse
                arc = Engine.masterProgress().let { if (it >= 0f) it else Engine.recordingSeconds(i) % 1f }
            }
            st == Engine.PadState.OVERDUB || latched -> { c = yellow; ringA = 0.95f; tint = 0.18f + 0.12f * pulse; arc = Engine.progress(i) }
            q == 1 -> { c = green; ringA = blink; tint = 0.14f * blink }
            q == 0 -> { c = if (blink > 0.5f) red else green; ringA = 1f; tint = 0.06f }
            st == Engine.PadState.PLAYING -> { c = green; ringA = if (Engine.seqPlaying) 0.8f else 0.35f; tint = 0.07f; arc = if (Engine.seqPlaying) Engine.progress(i) else -1f }
            st == Engine.PadState.ONESHOT -> { val sp = Engine.shotProgress(i); if (sp >= 0f) { c = Color.rgb(255, 150, 30); ringA = 0.9f; arc = sp } }
            st == Engine.PadState.MUTED -> { c = Color.rgb(150, 155, 165); ringA = 0.45f }
        }
        if (c == 0) return
        val rr = radius
        fillPaint.shader = null
        if (tint > 0f) {
            fillPaint.color = alpha(c, tint)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
        }
        strokePaint.shader = null
        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.color = alpha(c, ringA)
        tmpRect.set(r.left + dp(1.5f), r.top + dp(1.5f), r.right - dp(1.5f), r.bottom - dp(1.5f))
        canvas.drawRoundRect(tmpRect, max(0f, rr - dp(1.5f)), max(0f, rr - dp(1.5f)), strokePaint)
        // rotierender Lichtring im Tempo
        val len = padLengths[i]
        if (arc >= 0f && len > 0f) {
            val seg = len * 0.16f
            val end = arc * len
            val start = end - seg
            strokePaint.strokeWidth = dp(5f)
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.color = alpha(blend(c, Color.WHITE, 0.35f), 0.95f)
            segPath.reset()
            if (start >= 0f) {
                padMeasures[i].getSegment(start, end, segPath, true)
            } else {
                padMeasures[i].getSegment(len + start, len, segPath, true)
                padMeasures[i].getSegment(0f, end, segPath, true)
            }
            canvas.drawPath(segPath, strokePaint)
            strokePaint.strokeCap = Paint.Cap.BUTT
        }
        if (st == Engine.PadState.MUTED && q < 0) {
            // geladen, aber gestoppt: „▶“ = antippen startet auf der Eins
            textPaint.textSize = r.width() * 0.2f
            textPaint.color = ink(0.55f)
            canvas.drawText("▶", r.centerX(), r.centerY() + r.width() * 0.07f, textPaint)
        }
    }

    /** Leeres Pad: großes + mit der Quelle (🎙 Mikro / 〰 Wave) und 📂 für Datei/Bibliothek. */
    private fun drawEmptyPad(canvas: Canvas, i: Int) {
        val r = padRects[i]
        val wave = Engine.padSource[i] >= 1
        val c = ink(0.5f)
        strokePaint.shader = null
        strokePaint.color = c
        strokePaint.strokeWidth = dp(2.4f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        val cx = r.centerX()
        val cy = r.centerY() - r.height() * 0.06f
        val a = r.width() * 0.11f
        canvas.drawLine(cx - a, cy, cx + a, cy, strokePaint)
        canvas.drawLine(cx, cy - a, cx, cy + a, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        textPaint.textSize = r.width() * 0.12f
        textPaint.color = ink(0.6f)
        val srcTxt = when (Engine.padSource[i]) {
            3 -> "🎹 " + instMaskShort(Engine.padInstMask[i])
            2 -> "🎛 " + Engine.maskPads(Engine.padSrcMask[i]).joinToString("+") { "${it + 1}" }.ifEmpty { "PADS" }
            1 -> "〰 WAVE"
            else -> "🎙 MIC"
        }
        val maxTw = r.width() * 0.9f
        if (textPaint.measureText(srcTxt) > maxTw) textPaint.textSize *= maxTw / textPaint.measureText(srcTxt)
        canvas.drawText(srcTxt, cx, cy + a + r.width() * 0.16f, textPaint)
        importRect(i, iconRect)
        val ir = iconRect
        fillPaint.shader = null
        fillPaint.color = ink(0.12f)
        canvas.drawRoundRect(ir, dp(7f), dp(7f), fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = ink(0.35f)
        canvas.drawRoundRect(ir, dp(7f), dp(7f), strokePaint)
        textPaint.textSize = ir.height() * 0.55f
        textPaint.color = ink(0.9f)
        canvas.drawText("📂", ir.centerX(), ir.centerY() + ir.height() * 0.2f, textPaint)
    }

    /** Pad, dessen Lautstärke gerade per Wischen geändert wird. */
    private var volPad = -1
    private var volShow = 0f

    // ---- Gesten auf den Pads: ← wischen = löschen, ↕ = Lautstärke, zwei Finger = Mute (lang: Solo)
    private val downXY = HashMap<Int, FloatArray>()
    /** 1 = Lautstärke, 2 = gelöscht, 3 = zweiter Finger übernimmt. */
    private val gest = HashMap<Int, Int>()
    private val volStart = HashMap<Int, Float>()
    private var twoPointer = -1
    private var twoPad = -1
    private var twoLong = false
    var onImportMenu: ((Int) -> Unit)? = null
    /** Drumset-Auswahl öffnen (Drum-Modus). */
    var onDrumKit: (() -> Unit)? = null
    /** Drum-Spuren leeren (Auswahl: gewähltes Pad oder alle). */
    var onDrumClear: ((Int) -> Unit)? = null
    var onPadFxEditor: ((Int) -> Unit)? = null
    private var fxBadgePointer = -1
    private var fxBadgePad = -1
    private var fxBadgeLong = false
    private val fxBadgeLongPress = Runnable {
        if (fxBadgePointer < 0) return@Runnable
        fxBadgeLong = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onPadFxEditor?.invoke(fxBadgePad)
    }
    var onLaunchQuantChanged: (() -> Unit)? = null
    var onPlayModeChanged: (() -> Unit)? = null
    private val quantPill = RectF()

    private fun gestVolActive(): Boolean = gest.values.any { it == 1 }

    private val twoLongPress = Runnable {
        if (twoPointer < 0) return@Runnable
        twoLong = true
        Engine.toggleSolo(twoPad)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        burst(padRects[twoPad].centerX(), padRects[twoPad].centerY(), Color.rgb(255, 200, 40), 20, 220f)
        hint(if (Engine.solo[twoPad]) "Pad ${twoPad + 1} SOLO" else "Pad ${twoPad + 1} Solo aus")
    }

    /** Fingerbewegung auf einem Pad auswerten. */
    private fun padGesture(id: Int, x: Float, y: Float) {
        val p = pointerPad[id] ?: return
        // Drum-Modus: beim Trommeln keine Wischgesten (kein versehentliches Löschen/Leiser)
        if (drumActive() && Engine.isOneShot(p)) return
        val g = gest[id]
        val d = downXY[id] ?: return
        if (g == null) {
            val dx = x - d[0]
            val dy = y - d[1]
            if (dx < -dp(40f) && abs(dx) > 2f * abs(dy)) {
                gest[id] = 2
                Engine.repeatPadUp(p)
                val r = padRects[p]
                if (Engine.hasLoop(p)) {
                    Engine.abortTake(p)
                    Engine.clear(p)
                    burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 50, 380f)
                    shocks.add(Shock(RectF(r), 0f, Color.rgb(255, 40, 70)))
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    hint("Pad ${p + 1} gelöscht – ↶ UNDO holt es zurück")
                } else if (Engine.state(p) == Engine.PadState.RECORDING) {
                    Engine.abortTake(p)
                    burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 24, 260f)
                    hint("Aufnahme verworfen")
                }
            } else if (abs(dy) > dp(18f) && abs(dy) > 1.5f * abs(dx) && Engine.hasLoop(p) && Engine.state(p) != Engine.PadState.RECORDING) {
                gest[id] = 1
                Engine.abortTake(p)
                Engine.repeatPadUp(p)
                volStart[id] = Engine.padGain(p)
                volPad = p
            }
        }
        if (gest[id] == 1) {
            Engine.setPadGain(p, (volStart[id] ?: 1f) + (d[1] - y) / dp(160f))
            volPad = p
            volShow = 1f
        }
    }

    /** Szenen-Ansicht und Mute/Solo-Kennzeichen über den Pads. */
    private fun drawPadOverlay(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val cols = overlayColors()
        if (sceneView) {
            drawSceneTile(canvas, i, cols)
            return
        }
        val lp = Engine.padLoadProg[i]
        if (lp >= 0f) {
            // Lade-Fortschritt: abgedunkelt, Balken unten, Prozent + was gerade passiert
            fillPaint.shader = null
            fillPaint.color = alpha(Color.BLACK, 0.62f)
            canvas.drawRoundRect(r, radius, radius, fillPaint)
            val bh = max(dp(6f), r.height() * 0.07f)
            tmpRect.set(r.left + dp(8f), r.bottom - bh - dp(8f), r.right - dp(8f), r.bottom - dp(8f))
            fillPaint.color = alpha(Color.WHITE, 0.18f)
            canvas.drawRoundRect(tmpRect, bh / 2, bh / 2, fillPaint)
            fillPaint.color = cols[2]
            canvas.drawRoundRect(tmpRect.left, tmpRect.top, tmpRect.left + tmpRect.width() * lp.coerceIn(0.02f, 1f), tmpRect.bottom, bh / 2, bh / 2, fillPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = min(dp(22f), r.width() * 0.2f)
            canvas.drawText("${(lp * 100).toInt()} %", r.centerX(), r.centerY() + textPaint.textSize * 0.2f, textPaint)
            textPaint.textSize = min(dp(11f), r.width() * 0.1f)
            textPaint.color = alpha(Color.WHITE, 0.8f)
            canvas.drawText(fitLabel(textPaint, "⏳ " + Engine.padLoadText[i], r.width() - dp(10f), textPaint.textSize, dp(7f)), r.centerX(), r.centerY() + textPaint.textSize * 1.9f, textPaint)
            postInvalidateOnAnimation()
            return
        }
        val has = Engine.hasLoop(i)
        if (volPad == i && volShow > 0f && has) {
            val g = Engine.padGain(i)
            val cols2 = overlayColors()
            val bw = dp(8f)
            tmpRect.set(r.left + dp(6f), r.top + dp(8f), r.left + dp(6f) + bw, r.bottom - dp(8f))
            fillPaint.shader = null
            fillPaint.color = alpha(Color.BLACK, 0.5f * volShow)
            canvas.drawRoundRect(tmpRect, bw / 2f, bw / 2f, fillPaint)
            val hgt = tmpRect.height() * (g / 1.5f)
            fillPaint.color = alpha(cols2[2], volShow)
            canvas.drawRoundRect(tmpRect.left, tmpRect.bottom - hgt, tmpRect.right, tmpRect.bottom, bw / 2f, bw / 2f, fillPaint)
            textPaint.textSize = r.width() * 0.16f
            textPaint.color = alpha(Color.WHITE, volShow)
            fillPaint.color = alpha(Color.BLACK, 0.55f * volShow)
            canvas.drawRoundRect(r.centerX() - r.width() * 0.26f, r.centerY() - r.width() * 0.13f, r.centerX() + r.width() * 0.26f, r.centerY() + r.width() * 0.1f, dp(6f), dp(6f), fillPaint)
            canvas.drawText("${(g * 100).roundToInt()}%", r.centerX(), r.centerY() + r.width() * 0.04f, textPaint)
            if (volPad == i && !gestVolActive()) volShow = max(0f, volShow - 0.02f)
        }
        // SOLO auf anderen Pads: dieses Pad ist nicht zu hören
        if (Engine.soloAny && !Engine.solo[i] && has) {
            fillPaint.shader = null
            fillPaint.color = alpha(Color.BLACK, 0.45f)
            canvas.drawRoundRect(r, radius, radius, fillPaint)
        }
        val sz = min(dp(18f), r.width() * 0.2f)
        var bx = r.right - sz * 0.5f - dp(5f)
        val by = r.top + sz * 0.5f + dp(5f)
        if (Engine.solo[i]) {
            padBadge(canvas, bx, by, sz, "S", Color.rgb(255, 200, 40))
            bx -= sz + dp(3f)
        }
        if (has && Engine.isOneShot(i) && Engine.isMuted(i)) padBadge(canvas, bx, by, sz, "M", Color.rgb(90, 160, 255))
        if (func == F_NONE) drawPadLoop(canvas, i, cols)
        // Modus-Rahmen
        val mc = when (func) {
            F_MUTE -> Color.rgb(90, 160, 255)
            F_SOLO -> Color.rgb(255, 200, 40)
            F_DUPL -> overlayColors()[2]
            else -> 0
        }
        if (mc != 0) {
            val pulse = 0.5f + 0.5f * sin(time * 6f)
            strokePaint.strokeWidth = dp(if (duplSrc == i) 4f else 1.6f)
            strokePaint.color = alpha(mc, if (duplSrc == i) 1f else 0.35f + 0.35f * pulse)
            tmpRect.set(r.left - dp(3f), r.top - dp(3f), r.right + dp(3f), r.bottom + dp(3f))
            canvas.drawRoundRect(tmpRect, radius + dp(3f), radius + dp(3f), strokePaint)
        }
    }

    private fun padBadge(canvas: Canvas, cx: Float, cy: Float, sz: Float, label: String, c: Int) {
        fillPaint.shader = null
        fillPaint.color = c
        canvas.drawCircle(cx, cy, sz * 0.5f, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, sz * 0.5f, strokePaint)
        textPaint.textSize = sz * 0.62f
        textPaint.color = Color.BLACK
        canvas.drawText(label, cx, cy + sz * 0.22f, textPaint)
    }

    private fun drawSceneTile(canvas: Canvas, i: Int, cols: IntArray) {
        val r = padRects[i]
        val exists = Engine.sceneExists(i)
        val cur = i == Engine.curScene
        val queued = i == Engine.queuedScene
        val blink = 0.5f + 0.5f * sin(time * 10f)
        val acc = cols[2]
        fillPaint.shader = null
        fillPaint.color = when {
            cur -> acc
            queued -> blend(cols[0], acc, 0.25f + 0.55f * blink)
            exists -> blend(cols[0], acc, 0.22f)
            else -> cols[0]
        }
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        strokePaint.strokeWidth = dp(if (cur) 2.5f else 1.4f)
        strokePaint.color = when {
            cur -> Color.WHITE
            exists -> acc
            else -> alpha(cols[1], 0.25f)
        }
        canvas.drawRoundRect(r, radius, radius, strokePaint)
        if (sceneDupSrc == i) {
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = alpha(Color.WHITE, 0.5f + 0.5f * blink)
            canvas.drawRoundRect(r, radius, radius, strokePaint)
        }
        val ink = if (cur) Color.WHITE else cols[1]
        textPaint.textSize = r.width() * 0.11f
        textPaint.color = alpha(ink, if (exists) 0.8f else 0.35f)
        canvas.drawText("SZENE", r.centerX(), r.top + r.height() * 0.24f, textPaint)
        textPaint.textSize = r.width() * 0.34f
        textPaint.color = alpha(ink, if (exists) 1f else 0.35f)
        canvas.drawText("${i + 1}", r.centerX(), r.centerY() + r.width() * 0.12f, textPaint)
        textPaint.textSize = r.width() * 0.11f
        textPaint.color = alpha(ink, if (exists) 0.8f else 0.4f)
        val sub = when {
            queued -> "▶ zur Eins"
            exists -> "${Engine.sceneBars(i)} Takt" + (if (Engine.sceneBars(i) > 1) "e" else "")
            else -> "+ neu"
        }
        canvas.drawText(sub, r.centerX(), r.bottom - r.height() * 0.12f, textPaint)
        // Kette: Fortschritt der laufenden Szene
        if (cur && Engine.sceneChain && Engine.seqPlaying) {
            val bars = max(1, Engine.sceneBars(i))
            val prog = ((Engine.sceneBarPos() + max(0f, Engine.masterProgress())) / bars).coerceIn(0f, 1f)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = alpha(Color.WHITE, 0.9f)
            val y = r.bottom - dp(5f)
            canvas.drawLine(r.left + dp(8f), y, r.left + dp(8f) + (r.width() - dp(16f)) * prog, y, strokePaint)
        }
    }

    private fun funcPad(p: Int) {
        if (funcPointer >= 0) funcUsed = true
        val r = padRects[p]
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (func) {
            F_MUTE -> {
                if (!Engine.hasLoop(p)) return
                Engine.toggleMute(p)
                flash[p] = 1f
                burst(r.centerX(), r.centerY(), Color.rgb(90, 160, 255), 16, 200f)
            }
            F_SOLO -> {
                Engine.toggleSolo(p)
                flash[p] = 1f
                burst(r.centerX(), r.centerY(), Color.rgb(255, 200, 40), 16, 200f)
            }
            F_ERASE -> {
                if (Engine.erasePadEvents(p)) {
                    hint("Pad ${p + 1}: Pattern gelöscht – nochmal antippen löscht das Sample")
                    burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 24, 240f)
                } else if (Engine.hasLoop(p)) {
                    Engine.clear(p)
                    burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 60, 420f)
                    burst(r.centerX(), r.centerY(), padAccent(p), 40, 300f)
                    shocks.add(Shock(RectF(r), 0f, Color.rgb(255, 40, 70)))
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    hint("Pad ${p + 1} gelöscht – ↶ UNDO holt es zurück")
                }
            }
            F_DUPL -> {
                val src = duplSrc
                when {
                    src < 0 -> if (Engine.hasLoop(p)) {
                        duplSrc = p
                        hint("Pad ${p + 1} kopieren → jetzt das Ziel-Pad antippen")
                    } else hint("Zuerst ein Pad mit Inhalt antippen")
                    src == p -> duplSrc = -1
                    else -> {
                        if (Engine.duplicatePad(src, p)) {
                            hint("Pad ${src + 1} → Pad ${p + 1} kopiert (Sample, Modus, Schritte, Noten)")
                            shocks.add(Shock(RectF(r), 0f, overlayColors()[2]))
                            burst(r.centerX(), r.centerY(), overlayColors()[2], 30, 300f)
                        }
                        duplSrc = -1
                    }
                }
            }
        }
    }

    private val sceneLongPress = Runnable {
        if (scenePointer < 0) return@Runnable
        sceneLongFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSceneMenu?.invoke(scenePad)
    }

    private fun sceneDown(p: Int, id: Int) {
        if (funcPointer >= 0) funcUsed = true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val r = padRects[p]
        when (func) {
            F_ERASE -> {
                val cur = p == Engine.curScene
                if (Engine.deleteScene(p)) {
                    hint(if (cur) "Szene ${p + 1} geleert (Pattern und Instrumente)" else "Szene ${p + 1} gelöscht")
                    burst(r.centerX(), r.centerY(), Color.rgb(255, 40, 70), 30, 300f)
                }
                return
            }
            F_DUPL -> {
                val src = sceneDupSrc
                if (src < 0) {
                    if (Engine.sceneExists(p)) {
                        sceneDupSrc = p
                        hint("Szene ${p + 1} kopieren → jetzt die Ziel-Szene antippen")
                    }
                } else {
                    if (src != p && Engine.copyScene(src, p)) hint("Szene ${src + 1} → Szene ${p + 1} kopiert")
                    sceneDupSrc = -1
                }
                return
            }
        }
        scenePointer = id
        scenePad = p
        sceneLongFired = false
        postDelayed(sceneLongPress, 500L)
    }

    private fun sceneUp() {
        removeCallbacks(sceneLongPress)
        val p = scenePad
        scenePointer = -1
        if (sceneLongFired || p < 0) return
        val from = Engine.curScene
        val res = Engine.launchScene(p)
        val r = padRects[p]
        burst(r.centerX(), r.centerY(), overlayColors()[2], 24, 260f)
        when {
            res and 2 != 0 -> hint("Neue Szene ${p + 1} = Kopie von Szene ${from + 1}" + if (res and 1 != 0) " – startet auf der nächsten Eins" else "")
            res and 1 != 0 -> hint("Szene ${p + 1} startet auf der nächsten Eins")
        }
    }

    private val recLongPress = Runnable {
        if (recPointer < 0) return@Runnable
        recLongFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onRecLongMenu?.invoke()
    }

    private fun doUndo(redo: Boolean) {
        val label = if (redo) Engine.redo() else Engine.undo()
        val b = if (redo) redoBtn else undoBtn
        if (label == null) {
            hint(if (redo) "Nichts zum Wiederherstellen" else "Nichts rückgängig zu machen")
            return
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        burst(b.centerX(), b.centerY(), overlayColors()[2], 16, 200f)
        hint((if (redo) "↷ Wiederhergestellt: " else "↶ Rückgängig: ") + label)
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
    private val playPill = RectF()

    private fun drawFooter(canvas: Canvas) {
        val w = width.toFloat()
        val top = height - insetBottom - dp(33f)
        val ph = dp(22f)
        val gp = dp(5f)
        val pw = min(dp(112f), (w - dp(16f) - 3 * gp) / 4f)
        val x0 = w / 2f - (4 * pw + 3 * gp) / 2f
        srcPill.set(x0, top, x0 + pw, top + ph)
        modePill.set(srcPill.right + gp, top, srcPill.right + gp + pw, top + ph)
        quantPill.set(modePill.right + gp, top, modePill.right + gp + pw, top + ph)
        playPill.set(quantPill.right + gp, top, quantPill.right + gp + pw, top + ph)
        val modeText = when {
            sceneView && func == F_ERASE -> "ERASE: Szene antippen = löschen / leeren"
            sceneView && func == F_DUPL -> if (sceneDupSrc < 0) "DUPL: Quell-Szene antippen" else "DUPL: Ziel-Szene antippen"
            sceneView -> "SZENEN: antippen = zur nächsten Eins starten · lange = Länge/Kette"
            func == F_MUTE -> "MUTE: Pads antippen = stumm / an"
            func == F_SOLO -> "SOLO: Pads antippen = nur diese hören"
            func == F_ERASE -> "ERASE: Pad = Pattern löschen, nochmal = Sample"
            func == F_DUPL -> if (duplSrc < 0) "DUPL: Quell-Pad antippen" else "DUPL: Ziel-Pad für Pad ${duplSrc + 1} antippen"
            else -> null
        }
        if (modeText != null) {
            textPaint.textSize = min(dp(11f), (w - dp(24f)) / (modeText.length * 0.6f))
            textPaint.color = if (func == F_ERASE) Color.rgb(255, 80, 100) else overlayColors()[2]
            canvas.drawText(modeText, w / 2f, top + dp(15f), textPaint)
        } else {
            val cols = overlayColors()
            val sum = Engine.sourceSummary(VISIBLE_PADS)
            val live = Engine.liveBars != 0
            footerPill(canvas, srcPill, when (sum) { 1 -> "🔁 ALLE WAVE"; 2 -> "🎙/🔁 GEMISCHT"; else -> "🎙 ALLE MIKRO" }, sum != 0, cols)
            val ml = when {
                Engine.liveBars < 0 -> "👆 TIPPEN"
                Engine.liveBars > 0 -> "⚡ LIVE ${Engine.liveBars} TAKT" + (if (Engine.liveBars > 1) "E" else "")
                else -> "✋ HALTEN"
            }
            footerPill(canvas, modePill, ml, live, cols)
            footerPill(canvas, quantPill, "⏱ " + Engine.LAUNCH_NAMES[Engine.launchIdx], Engine.launchIdx > 0, cols)
            footerPill(canvas, playPill, Engine.PLAY_NAMES[Engine.playMode], Engine.playMode > 0, cols)
        }
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(BRAND_ORANGE, 0.8f)
        if (infoMode) {
            textPaint.color = overlayColors()[2]
            canvas.drawText("ℹ INFO-MODUS · Element antippen · ☰ zum Beenden", w / 2f, height - insetBottom - dp(2f), textPaint)
        } else {
            canvas.drawText("© marween", w / 2f, height - insetBottom - dp(2f), textPaint)
        }
    }

    /** Trefferfläche des FX-Schalters unten rechts im Pad. */
    private val loopTmp = RectF()

    /** Pad-Loop-Leiste unten links auf laufenden Loop-Pads: [−] [⟲ Größe] [+]. */
    private fun padLoopRect(i: Int, out: RectF): Boolean {
        val r = padRects[i]
        if (r.width() <= 0f || sceneView) return false
        if (!Engine.hasLoop(i) || Engine.isOneShot(i) || Engine.isSliced(i)) return false
        val st = Engine.state(i)
        if (st != Engine.PadState.PLAYING && st != Engine.PadState.MUTED) return false
        val h = min(dp(28f), r.height() * 0.26f)
        fxBadgeRect(i, loopTmp)
        val right = loopTmp.left - dp(4f)
        out.set(r.left + dp(5f), r.bottom - h - dp(5f), right, r.bottom - dp(5f))
        return out.width() >= dp(64f)
    }

    private fun drawPadLoop(canvas: Canvas, i: Int, cols: IntArray) {
        if (!padLoopRect(i, tmpRect)) return
        val on = Engine.padLoopOn[i]
        val acc = Color.rgb(60, 220, 140)
        val r = RectF(tmpRect)
        val seg = r.width() * 0.27f
        fillPaint.shader = null
        fillPaint.color = alpha(Color.BLACK, 0.55f)
        canvas.drawRoundRect(r, dp(7f), dp(7f), fillPaint)
        val mid = RectF(r.left + seg, r.top, r.right - seg, r.bottom)
        fillPaint.color = if (on) acc else alpha(Color.WHITE, 0.12f)
        canvas.drawRoundRect(mid, dp(6f), dp(6f), fillPaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = if (on) Color.WHITE else alpha(acc, 0.8f)
        canvas.drawRoundRect(r, dp(7f), dp(7f), strokePaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = min(dp(16f), r.height() * 0.62f)
        canvas.drawText("−", r.left + seg / 2, r.centerY() + textPaint.textSize * 0.36f, textPaint)
        canvas.drawText("+", r.right - seg / 2, r.centerY() + textPaint.textSize * 0.36f, textPaint)
        textPaint.color = if (on) Color.BLACK else Color.WHITE
        val lb = "⟲ " + Engine.padLoopLabel(i)
        textPaint.textSize = min(dp(12f), r.height() * 0.48f)
        canvas.drawText(fitLabel(textPaint, lb, mid.width() - dp(4f), textPaint.textSize, dp(7f)), mid.centerX(), mid.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    private fun fxBadgeRect(i: Int, out: RectF) {
        val r = padRects[i]
        val h = min(dp(18f), r.width() * 0.2f)
        val w = h * 2.5f
        out.set(r.right - w - dp(5f), r.bottom - h - dp(5f), r.right - dp(5f), r.bottom - dp(5f))
    }

    /** 📂-Knopf oben rechts auf leeren Pads (Import / Bibliothek). */
    private fun importRect(i: Int, out: RectF) {
        val r = padRects[i]
        val sz = min(dp(28f), r.width() * 0.3f)
        out.set(r.right - sz - dp(4f), r.top + dp(4f), r.right - dp(4f), r.top + dp(4f) + sz)
    }

    /** Zeigt am Pad, ob es durch den Effekt läuft (FX) oder trocken bleibt (durchgestrichen) – antippen schaltet um. */
    private fun drawFxBadge(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val n = Engine.padFxCount(i)
        val on = Engine.padChainActive(i)
        val cols = overlayColors()
        val h = min(dp(18f), r.width() * 0.2f)
        fxBadgeRect(i, tmpRect)
        val rr = h / 2f
        fillPaint.shader = null
        fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.8f)
        canvas.drawRoundRect(tmpRect, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = if (on) Color.WHITE else alpha(cols[1], if (n > 0) 0.7f else 0.4f)
        canvas.drawRoundRect(tmpRect, rr, rr, strokePaint)
        textPaint.textSize = h * 0.5f
        textPaint.color = if (on) Color.WHITE else alpha(cols[1], if (n > 0) 0.85f else 0.55f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("FX", tmpRect.left + h * 0.32f, tmpRect.centerY() + h * 0.18f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        // drei Punkte = drei Effekt-Plätze
        val dx = tmpRect.right - h * 0.95f
        for (k in 0 until 3) {
            val filled = Engine.pfxType[i][k] >= 0
            dotPaint.color = if (filled) (if (on) Color.WHITE else cols[2]) else alpha(if (on) Color.WHITE else cols[1], 0.3f)
            canvas.drawCircle(dx + k * h * 0.28f, tmpRect.centerY(), h * 0.1f, dotPaint)
        }
    }


    /** Kleines Eingangs-Symbol oben links am Pad (🎙 Mikro / 〰 Wave). */
    private fun drawInputBadge(canvas: Canvas, i: Int) {
        val r = padRects[i]
        if (r.width() <= 0f) return
        val wave = Engine.padSource[i] >= 1
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
        textPaint.color = if (on) Color.WHITE else cols[1]
        val lb = fitLabel(textPaint, label, r.width() - dp(10f), dp(10.5f), dp(6.5f))
        canvas.drawText(lb, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    // ---------------------------------------------------------------- Regler, Effekt-Knopf, XY-Fläche

    private fun knobLabel(k: Int): String = if (xyMode == 1) ACID_KNOBS[k] else if (xyMode == 3) SYN_KNOBS[k] else if (xyMode == 2) {
        if (k < 3) "${DRUM_KNOBS[k]} ${InstState.drumNames[InstState.drumSel]}" else DRUM_KNOBS[k]
    } else when (k) {
        0 -> "MIX"
        1 -> Fx.P1_LABELS[Engine.fxType]
        2 -> Fx.P2_LABELS[Engine.fxType]
        3 -> "VOL"
        else -> "SWING"
    }

    private fun knobValue(k: Int): Float {
        val st = InstState
        return when (xyMode) {
            1 -> when (k) { 0 -> st.acidCut; 1 -> st.acidRes; 2 -> st.acidEnv; 3 -> st.acidDec; else -> st.acidVol }
            2 -> when (k) { 0 -> st.drumTune[st.drumSel]; 1 -> st.drumDecay[st.drumSel]; 2 -> st.drumLevel[st.drumSel]; 3 -> st.drumDrive; else -> st.drumVol }
            3 -> when (k) { 0 -> st.synCut; 1 -> st.synRes; 2 -> st.synAtk; 3 -> st.synRel; else -> st.synVol }
            else -> if (k == 4) Engine.swing else Engine.knobs[k]
        }
    }

    private fun setKnobValue(k: Int, v: Float) {
        val st = InstState
        when (xyMode) {
            1 -> when (k) { 0 -> st.acidCut = v; 1 -> st.acidRes = v; 2 -> st.acidEnv = v; 3 -> st.acidDec = v; else -> st.acidVol = v }
            2 -> when (k) { 0 -> st.drumTune[st.drumSel] = v; 1 -> st.drumDecay[st.drumSel] = v; 2 -> st.drumLevel[st.drumSel] = v; 3 -> st.drumDrive = v; else -> st.drumVol = v }
            3 -> when (k) { 0 -> st.synCut = v; 1 -> st.synRes = v; 2 -> st.synAtk = v; 3 -> st.synRel = v; else -> st.synVol = v }
            else -> if (k == 4) Engine.swing = v else Engine.knobs[k] = v
        }
    }

    private fun drawKnobs(canvas: Canvas) {
        for (k in 0 until 5) {
            val r = knobRects[k]
            if (r.width() <= 0f) continue
            val v = knobValue(k).coerceIn(0f, 1f)
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
            val step = if (xyMode != 0) null else if (k == 4) "${50 + (v * 25).toInt()}%" else Fx.stepLabel(Engine.fxType, k, v)
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
        canvas.drawText(fxCode(Engine.fxType), r.centerX(), r.centerY() + r.width() * 0.11f, textPaint)
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
        // Touch-Ereignisse sofort zustellen statt im Bildtakt gesammelt (spart bis zu 16 ms)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) try { requestUnbufferedDispatch(e) } catch (ignored: Throwable) {}
        if (focus != 0) {
            if (focusBlocked()) { closeFocus(); return true }
            if ((e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
                focusClose.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                closeFocus()
                return true
            }
            if (focus == 1 && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
                drToggle.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                setDrumMode(!drumMode)
                return true
            }
            if (focus == 1 && drumActive() && drumPanelTouch(e)) return true
            if (focus == 1 && wavePanelTouch(e)) return true
            val ev = MotionEvent.obtain(e)
            ev.transform(focusInv)
            try {
                val am = ev.actionMasked
                if (am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN) {
                    val ix = ev.getX(ev.actionIndex)
                    val iy = ev.getY(ev.actionIndex)
                    // außerhalb der vergrößerten Sektion: nichts Verstecktes auslösen, Doppeltipp schließt
                    if (!focusSrc.contains(ix, iy)) {
                        checkFocusTap(ix, iy)
                        return true
                    }
                }
                touchInner(ev)
            } finally { ev.recycle() }
            return true
        }
        return touchInner(e)
    }

    private fun touchInner(e: MotionEvent): Boolean {
        if (tourStep >= 0) { tourTouch(e); return true }
        if (lesson != null && lessonTouch(e)) return true
        if (layoutEdit) { layoutEditTouch(e); return true }
        if (infoMode && message == null && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            val ix = e.getX(e.actionIndex)
            val iy = e.getY(e.actionIndex)
            if (infoTouch(ix, iy)) return true
        }
        if (masterPanel.isOpen) {
            masterPanel.touch(e)
            return true
        }
        if (ghost.isOpen) {
            ghost.touch(e)
            invalidate()
            return true
        }
        if (songBuilder.isOpen && !instEd.isOpen && !songEd.isOpen) {
            songBuilder.touch(e)
            invalidate()
            return true
        }
        if (songEd.isOpen && !instEd.isOpen) {
            songEd.touch(e)
            invalidate()
            return true
        }
        if (euclid.isOpen) {
            euclid.touch(e)
            invalidate()
            return true
        }
        if (harm.isOpen) {
            harm.touch(e)
            invalidate()
            return true
        }
        if (atmos.isOpen) {
            atmos.touch(e)
            invalidate()
            return true
        }
        if (launcher.isOpen) {
            launcher.touch(e)
            invalidate()
            return true
        }
        if (station.isOpen) {
            station.touch(e)
            invalidate()
            return true
        }
        if (slicer.isOpen) {
            slicer.touch(e)
            invalidate()
            return true
        }
        if (instEd.isOpen) {
            instEd.touch(e)
            return true
        }
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
                if (selRowDown(x, y)) return true
                if (focus == 0 && zoomEnabled) {
                    val zsec = when {
                        zoomPads.contains(x, y) -> 1
                        zoomXy.contains(x, y) -> 2
                        RectF(zoomFx).apply { inset(-dp(4f), -dp(4f)) }.contains(x, y) -> 3
                        else -> 0
                    }
                    if (zsec != 0) {
                        openFocus(zsec)
                        return true
                    }
                }
                if (mixView && mixRect.contains(x, y)) {
                    mixDown(id, x, y)
                    return true
                }
                if (func == F_NONE && !sceneView && srcPill.contains(x, y)) {
                    Engine.setAllSources(if (Engine.sourceSummary(VISIBLE_PADS) == 0) 1 else 0)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, overlayColors()[2], 12, 160f)
                    onRecSettingsChanged?.invoke()
                    return true
                }
                if (func == F_NONE && !sceneView && quantPill.contains(x, y)) {
                    Engine.launchIdx = (Engine.launchIdx + 1) % Engine.LAUNCH_Q.size
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, overlayColors()[2], 12, 160f)
                    onLaunchQuantChanged?.invoke()
                    return true
                }
                if (func == F_NONE && !sceneView && playPill.contains(x, y)) {
                    Engine.playMode = (Engine.playMode + 1) % Engine.PLAY_NAMES.size
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(x, y, overlayColors()[2], 12, 160f)
                    onPlayModeChanged?.invoke()
                    return true
                }
                if (func == F_NONE && !sceneView && modePill.contains(x, y)) {
                    Engine.liveBars = when (Engine.liveBars) { 0 -> -1; -1 -> 1; 1 -> 2; 2 -> 4; 4 -> 8; 8 -> 16; else -> 0 }
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
                for (b in 0 until 6) if (funcRects[b].contains(x, y)) {
                    if (funcPointer < 0) funcDown(b, id)
                    return true
                }
                if (onInfo != null && logoDst.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onInfo?.invoke()
                    return true
                }
                if (designBtn.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDesignMenu?.invoke()
                    return true
                }
                if (setRecBtn.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSetRec?.invoke()
                    return true
                }
                if (masterBtn.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    masterPanel.open()
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
                for (k in 0 until 5) {
                    if (knobRects[k].contains(x, y)) {
                        pointerKnob[id] = k
                        knobStartY[id] = y
                        knobStartVal[id] = knobValue(k)
                        return true
                    }
                }
                if (seqRect.contains(x, y)) {
                    when {
                        recBtn.contains(x, y) -> {
                            recPointer = id
                            recLongFired = false
                            postDelayed(recLongPress, 500L)
                            burst(x, y, Color.rgb(255, 45, 70), 18, 220f)
                        }
                        undoBtn.contains(x, y) -> doUndo(false)
                        redoBtn.contains(x, y) -> doUndo(true)
                        mixBtn.contains(x, y) -> toggleMixView()
                        seqFoldBtn.contains(x, y) -> {
                            seqFold = !seqFold
                            onLayoutPrefs?.invoke()
                            hint(if (seqFold) "Sequenzer eingeklappt – die Pads werden größer (▴ klappt ihn wieder auf)" else "Sequenzer-Schritte wieder da")
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
                        func == F_ERASE && y <= stepRects[0].bottom + dp(6f) -> {
                                if (funcPointer >= 0) funcUsed = true
                                Engine.seqClear()
                                hint("Pattern geleert – ↶ UNDO holt es zurück")
                                burst(x, y, Color.rgb(255, 40, 70), 30, 300f)
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
                for (t in 0 until 2) {
                    if (modeRects[t].contains(x, y)) {
                        // Instrumente öffnen direkt den großen Editor (keine Mini-Ansicht mehr in der Pad-Ansicht)
                        if (xyMode != 0) { instRelease(); xyMode = 0 }
                        if (t == 1) {
                            val seg = instSegAt(x)
                            if (seg == 3) onInstMore?.invoke()
                            else {
                                instSel = 1 + seg
                                instEd.open(instSel)
                                lessonInstOpened = true
                            }
                        }
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        burst(x, y, overlayColors()[2], 14, 180f)
                        return true
                    }
                }
                if (xyMode != 0 && xyRect.contains(x, y)) {
                    instDown(id, x, y)
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
                if (!sceneView && func == F_NONE) {
                    for (i in 0 until VISIBLE_PADS) {
                        if (padRects[i].width() <= 0f) continue
                        if (Engine.state(i) == Engine.PadState.EMPTY && !Engine.isArmed(i)) {
                            importRect(i, iconRect)
                            if (iconRect.contains(x, y)) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onImportMenu?.invoke(i)
                                return true
                            }
                        }
                        if (padLoopRect(i, iconRect) && iconRect.contains(x, y)) {
                            val seg = iconRect.width() * 0.27f
                            when {
                                x < iconRect.left + seg -> { Engine.padLoopResize(i, -1); hint("Pad ${i + 1}: Loop ${Engine.padLoopLabel(i)}") }
                                x > iconRect.right - seg -> { Engine.padLoopResize(i, 1); hint("Pad ${i + 1}: Loop ${Engine.padLoopLabel(i)}") }
                                else -> {
                                    Engine.padLoopToggle(i)
                                    hint(if (Engine.padLoopOn[i]) "Pad ${i + 1}: ⟲ Loop ${Engine.padLoopLabel(i)} an – − / + verkleinert/vergrößert, Mitte = aus" else "Pad ${i + 1}: Loop aus – läuft im Takt weiter")
                                }
                            }
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                            return true
                        }
                        fxBadgeRect(i, iconRect)
                        iconRect.inset(-dp(3f), -dp(3f))
                        if (iconRect.contains(x, y)) {
                            // kurz = Effekte des Pads an/aus, lang = Effekte wählen (bis zu 3)
                            fxBadgePointer = id
                            fxBadgePad = i
                            fxBadgeLong = false
                            postDelayed(fxBadgeLongPress, 450L)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            return true
                        }
                    }
                }
                val p = hitPad(x, y)
                if (p < 0) {
                    checkFocusTap(x, y)
                    return true
                }
                if (sceneView) {
                    if (scenePointer < 0) sceneDown(p, id)
                    return true
                }
                if (func != F_NONE) {
                    if (Engine.padInst[p] >= 0 && func == F_ERASE) {
                        Engine.padInst[p] = -1
                        hint("Pad ${p + 1} wieder frei (Instrument-Zuordnung gelöst)")
                        return true
                    }
                    funcPad(p)
                    return true
                }
                if (Engine.padInst[p] >= 0) {
                    // Instrument-Pad: antippen = Instrument an/aus
                    val code = Engine.padInst[p]
                    val v = !Engine.instCodeOn(code)
                    Engine.setInstCodeOn(code, v)
                    flash[p] = 1f
                    val r = padRects[p]
                    burst(r.centerX(), r.centerY(), instPadColor(code), 18, 220f)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    hint(instPadName(code) + if (v) " an" else " aus")
                    return true
                }
                if (pointerPad.containsValue(p) && !(drumActive() && Engine.isOneShot(p))) {
                    // zweiter Finger auf demselben Pad: Mute (kurz) bzw. Solo (lang)
                    val first = pointerPad.entries.firstOrNull { it.value == p }?.key
                    if (first != null && gest[first] == null && twoPointer < 0) {
                        gest[first] = 3
                        Engine.abortTake(p)
                        Engine.repeatPadUp(p)
                        twoPointer = id
                        twoPad = p
                        twoLong = false
                        postDelayed(twoLongPress, 500L)
                    }
                    return true
                }
                if (p != selPad) { selPad = p; wvStop(); wvCursor = Engine.padCue(p); wvResetView() }
                if (selectMode) {
                    flash[p] = 1f
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    burst(padRects[p].centerX(), padRects[p].centerY(), overlayColors()[2], 14, 180f)
                    hint("Pad ${p + 1} ausgewählt" + if (Engine.hasLoop(p)) " – Effekt-Fläche wirkt jetzt nur auf dieses Pad · 〰 WAVE / 🎹 NOTEN / ✨ FX ebenso" else " (leer)")
                    return true
                }
                if (drumActive() && Engine.state(p) == Engine.PadState.EMPTY && !Engine.isArmed(p)) {
                    // Drum-Modus: leeres Pad = Sample laden statt aufnehmen
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onImportMenu?.invoke(p)
                    return true
                }
                pointerPad[id] = p
                downXY[id] = floatArrayOf(x, y)
                // Zeitpunkt der Berührung (nicht der Verarbeitung) – für genaues Timing beim Einspielen
                val tNs = TouchVelocity.eventNanos(e)
                val vel = if (Engine.isOneShot(p)) TouchVelocity.of(e, idx, y, padRects[p].top, padRects[p].bottom, tNs) else 1f
                val res = Engine.liveDown(p, vel, tNs)
                val shot = (res and Engine.LIVE_SHOT) != 0
                if ((res and Engine.LIVE_LAUNCHED) != 0) launchedPointers.add(id) else launchedPointers.remove(id)
                if (shot) {
                    shotPointers.add(id)
                    // Note Repeat: gehaltenes ⚡-Pad wiederholt im Raster
                    if (Engine.repeatOn) Engine.repeatPadDown(p)
                }
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
                    if (id == instPointer) instMove(e.getX(pi), e.getY(pi))
                    if (mixPtr.containsKey(id)) mixMove(id, e.getX(pi), e.getY(pi))
                    if (pointerPad.containsKey(id)) padGesture(id, e.getX(pi), e.getY(pi))
                    if (id == stepPointer && Engine.seqSelected >= 0) {
                        val dy = stepDownY - e.getY(pi)
                        if (stepDragged || abs(dy) > dp(8f)) {
                            if (!stepDragged) Engine.pushPatternUndo("Lautstärke Schritt ${stepIdx + 1}")
                            stepDragged = true
                            val base = if (stepStartVel > 0f) stepStartVel else 1f
                            Engine.setSeqVel(Engine.seqSelected, stepIdx, (base + dy / dp(90f)).coerceIn(0.05f, 1f))
                        }
                    }
                    val k = pointerKnob[id] ?: continue
                    val sy0 = knobStartY[id] ?: continue
                    val sv = knobStartVal[id] ?: continue
                    setKnobValue(k, (sv + (sy0 - e.getY(pi)) / dp(180f)).coerceIn(0f, 1f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val upId = e.getPointerId(idx)
                if (mixPtr.remove(upId) != null) return true
                if (upId == instPointer) {
                    instUp()
                    return true
                }
                if (upId == funcPointer) {
                    funcUp()
                    return true
                }
                if (upId == fxBadgePointer) {
                    removeCallbacks(fxBadgeLongPress)
                    fxBadgePointer = -1
                    val i = fxBadgePad
                    if (!fxBadgeLong && i >= 0) {
                        if (Engine.padFxCount(i) == 0) {
                            onPadFxEditor?.invoke(i)
                        } else {
                            Engine.padChainOn[i] = !Engine.padChainOn[i]
                            burst(e.getX(idx), e.getY(idx), overlayColors()[2], 12, 150f)
                            hint(if (Engine.padChainOn[i]) "Pad ${i + 1}: Effekte an (${Engine.padFxCount(i)})" else "Pad ${i + 1}: Effekte aus")
                        }
                    }
                    return true
                }
                if (upId == scenePointer) {
                    sceneUp()
                    return true
                }
                if (upId == recPointer) {
                    recPointer = -1
                    removeCallbacks(recLongPress)
                    if (!recLongFired) recPressed()
                    return true
                }
                if (upId == stepPointer) {
                    stepPointer = -1
                    val sel = Engine.seqSelected
                    if (sel >= 0 && !stepDragged) {
                        Engine.pushPatternUndo("Schritt ${stepIdx + 1}")
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
                if (upId == twoPointer) {
                    removeCallbacks(twoLongPress)
                    twoPointer = -1
                    if (!twoLong && Engine.hasLoop(twoPad)) {
                        Engine.toggleMute(twoPad)
                        val r = padRects[twoPad]
                        burst(r.centerX(), r.centerY(), Color.rgb(90, 160, 255), 18, 200f)
                        hint(if (Engine.isMuted(twoPad)) "Pad ${twoPad + 1} stumm" else "Pad ${twoPad + 1} an")
                    }
                    return true
                }
                downXY.remove(upId)
                volStart.remove(upId)
                val g = gest.remove(upId)
                if (g != null) {
                    pointerPad.remove(upId)
                    shotPointers.remove(upId)
                    return true
                }
                val p = pointerPad.remove(upId) ?: return true
                if (shotPointers.remove(upId)) {
                    Engine.repeatPadUp(p)
                    return true
                }
                release(p, e.getX(idx), e.getY(idx), launchedPointers.remove(upId))
            }
            MotionEvent.ACTION_CANCEL -> {
                mixPtr.clear()
                instRelease()
                Engine.repeatAllUp()
                removeCallbacks(funcLongPress)
                removeCallbacks(sceneLongPress)
                removeCallbacks(recLongPress)
                if (funcPointer >= 0) funcUp()
                scenePointer = -1
                recPointer = -1
                removeCallbacks(twoLongPress)
                twoPointer = -1
                removeCallbacks(fxBadgeLongPress)
                fxBadgePointer = -1
                downXY.clear()
                volStart.clear()
                for ((pid, gv) in gest) if (gv != 0) { pointerPad.remove(pid); shotPointers.remove(pid) }
                gest.clear()
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
                for (p in held) release(p, padRects[p].centerX(), padRects[p].centerY(), false)
                launchedPointers.clear()
            }
        }
        return true
    }

    private fun release(p: Int, x: Float, y: Float, launchedOnDown: Boolean) {
        val hadLoop = Engine.hasLoop(p)
        val tap = Engine.liveUp(p, launchedOnDown)
        if (tap) {
            if (hadLoop && !launchedOnDown) {
                // Antippen startet/stoppt den Loop – quantisiert auf das Start-Raster (Ableton-Prinzip)
                flash[p] = 1f
                burst(x, y, padAccent(p), 18, 200f)
            }
        } else if (Engine.isLatched(p)) {
            burst(x, y, Color.rgb(255, 190, 30), 22, 240f)
            hint("● Overdub auf Pad ${p + 1} – nochmal tippen beendet ihn")
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
            if (SEC_KNOBS !in secHidden) canvas.drawLine(m, y2, width - m, y2, strokePaint)
        }
    }

    private fun drawTopBarLight(canvas: Canvas) {
        if (theme == RETRO) {
            // Pixel-Titel wie im Entwurf
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val ts = min(logoDst.height() * 0.5f, logoDst.width() / 2.6f)
            textPaint.textSize = ts
            val bx = logoDst.left
            // zwei Zeilen (Großbuchstaben-Höhe ≈ 0,72 · Schriftgröße), Abstand 1,08 · Schriftgröße, mittig in der Logo-Fläche
            val y1 = logoDst.centerY() - (ts * 1.08f + ts * 0.72f) / 2f + ts * 0.72f
            val y2 = y1 + ts * 1.08f
            val sh = max(dp(1f), ts * 0.07f)
            textPaint.color = R_INK
            canvas.drawText("Marv", bx + sh, y1 + sh, textPaint)
            canvas.drawText("PAD", bx + sh, y2 + sh, textPaint)
            textPaint.color = R_ORANGE
            canvas.drawText("Marv", bx, y1, textPaint)
            canvas.drawText("PAD", bx, y2, textPaint)
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
        drawFuncRow(canvas)
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
        drawDisplayTexts(canvas, r, pad, r.top + dp(14f), alpha(txt, 0.7f), if (theme == COMIC) COMIC_MAGENTA else R_ORANGE, alpha(txt, 0.5f), txt, alpha(txt, 0.8f), false)
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
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 3) "INST" else if (Engine.padSource[i] == 2) "PADS" else if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
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
            canvas.drawText(fxCode(Engine.fxType), r.centerX(), r.centerY() + r.width() * 0.11f, textPaint)
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
            canvas.drawText(fxCode(Engine.fxType), r.centerX(), r.centerY() + r.width() * 0.09f, textPaint)
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
        drawSeqInfo(canvas, if (recOn) red else (if (comic) Color.BLACK else R_LCD), alpha(if (comic) Color.BLACK else R_LCD, 0.75f))
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
    /** wiederverwendet (kein neuer Path pro Pad und Bild) */
    private val progSeg = Path()

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
        drawFuncRow(canvas)
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
        canvas.save()
        canvas.clipRect(r.left + dp(2f), r.top + dp(2f), r.right - dp(2f), r.bottom - dp(2f))
        drawDisplayTexts(canvas, r, pad, r.top + dp(14f), alpha(txt, 0.65f), sk.accent, alpha(txt, 0.5f), txt, alpha(txt, 0.75f), false)
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
            KN_ARC -> {
                // flache Scheibe, der Wertebogen außen ist die eigentliche Anzeige
                dotPaint.color = sk.knobCap
                canvas.drawCircle(cx, cy, rad * 0.82f, dotPaint)
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = sk.knobRing
                canvas.drawCircle(cx, cy, rad * 0.82f, strokePaint)
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
        canvas.drawText(fxCode(Engine.fxType), r.centerX(), r.centerY() + r.width() * 0.1f, textPaint)
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
            XY_DOTS -> {
                // ruhiges Punktraster, nahe am Finger heller
                for (cx0 in 0..sk.xyCols) for (cy0 in 0..sk.xyRows) {
                    val x = gl + (gr - gl) * cx0 / sk.xyCols
                    val y = gt + (gb - gt) * cy0 / sk.xyRows
                    val dd = kotlin.math.hypot(x - fx, y - fy) / r.width()
                    val near = if (touching) (1f - dd * 3f).coerceIn(0f, 1f) else 0f
                    dotPaint.color = blend(sk.xyGrid, sk.accent, near)
                    canvas.drawCircle(x, y, dp(1.3f) + near * dp(1.6f), dotPaint)
                }
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
            PS_CLAUDE -> {
                // Zustand = Farbe: leer gestrichelt, läuft = Ton-Orange (atmet mit dem Pegel), aus = gedämpft, Aufnahme = Rot
                val fillCol = when (st) {
                    Engine.PadState.EMPTY -> base
                    Engine.PadState.MUTED -> blend(base, Color.WHITE, 0.03f)
                    Engine.PadState.RECORDING -> blend(base, Color.rgb(214, 64, 69), (0.45f + 0.25f * sin(time * 10f) + 0.2f * inLvl).coerceIn(0f, 1f))
                    Engine.PadState.OVERDUB -> blend(base, Color.rgb(214, 64, 69), (0.35f + 0.3f * inLvl).coerceIn(0f, 1f))
                    Engine.PadState.ONESHOT -> blend(base, acc, (0.10f + (if (progress >= 0f) 0.55f * lvl + 0.15f else 0f)).coerceIn(0f, 1f))
                    else -> blend(base, acc, (0.22f + 0.5f * lvl + 0.3f * flash[i]).coerceIn(0f, 1f))
                }
                fillPaint.shader = null
                fillPaint.color = fillCol
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                strokePaint.strokeWidth = dp(1.2f)
                if (st == Engine.PadState.EMPTY) {
                    strokePaint.pathEffect = dashEffect
                    strokePaint.color = alpha(sk.ink, 0.22f)
                    tmpRect.set(r.left + dp(1f), r.top + dp(1f), r.right - dp(1f), r.bottom - dp(1f))
                    canvas.drawRoundRect(tmpRect, rad, rad, strokePaint)
                    strokePaint.pathEffect = null
                } else {
                    strokePaint.color = if (st == Engine.PadState.MUTED) sk.padOutline else alpha(acc, 0.55f)
                    canvas.drawRoundRect(r, rad, rad, strokePaint)
                }
                glow = 0f
                led = Color.TRANSPARENT
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
        // Lauflicht – Claudes Design: durchgehende Linie am Rand statt Punkte
        if (sk.padStyle == PS_CLAUDE && progress >= 0f && padLengths[i] > 0f) {
            val len = padLengths[i]
            val seg = progSeg
            seg.reset()
            padMeasures[i].getSegment(0f, progress.coerceIn(0f, 1f) * len, seg, true)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.color = if (st == Engine.PadState.MUTED) alpha(sk.ink, 0.25f) else blend(acc, Color.WHITE, 0.25f)
            canvas.drawPath(seg, strokePaint)
            strokePaint.strokeCap = Paint.Cap.BUTT
        } else if (progress >= 0f && padLengths[i] > 0f) {
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
            Engine.PadState.RECORDING -> if (Engine.isArmed(i)) "BEREIT" else String.format(Locale.GERMANY, "%s %.1f", if (Engine.padSource[i] == 3) "INST" else if (Engine.padSource[i] == 2) "PADS" else if (Engine.padSource[i] == 1) "WAVE" else "REC", Engine.recordingSeconds(i))
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
        drawSeqInfo(canvas, if (recOn) red else sk.accent, alpha(txt, 0.7f))
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
                // Punch-out wie bei Maschine: Aufnahme aus, alles läuft weiter
                Engine.seqToggleRec()
                hint("Aufnahme beendet – das Pattern läuft weiter (↶ UNDO verwirft sie)")
            }
            else -> {
                val wasPlaying = Engine.seqPlaying
                Engine.seqToggleRec()
                hint(
                    if (!wasPlaying && Engine.countIn) "◔ Ein Takt Einzählen, dann läuft die Pattern-Aufnahme (REC lang drücken = Song-Aufnahme)"
                    else "● Pattern-Aufnahme läuft – nochmal REC beendet sie (REC lang drücken = Song-Aufnahme)"
                )
            }
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

    /** MIDI: Pad kurz aufleuchten lassen. */
    fun midiFlash(p: Int) {
        if (p in 0 until VISIBLE_PADS) flash[p] = 1f
    }

    fun openKeyboard(p: Int) {
        kbPad = p
        Midi.openRollPad = p
        mxSel.clear()
        mxInsert = -1.0
        mxZoom = 1f
        mxScroll = 0.0
        kbTake = false
        kbLastSemi = Engine.padSemi[p]
        layoutKeyboard()
    }

    /** Aufteilung wie in Ableton: Kopf, Werkzeuge, links Klaviatur bzw. Spuren, rechts die Matrix, unten Transport. */
    private fun layoutKeyboard() {
        val top = xyRect.bottom + dp(8f)
        kbRect.set(dp(8f), top, width - dp(8f), height - insetBottom - dp(8f))
        val hh = dp(36f)
        kbClose.set(kbRect.right - dp(8f) - hh, kbRect.top + dp(8f), kbRect.right - dp(8f), kbRect.top + dp(8f) + hh)
        kbOctUp.set(kbClose.left - dp(6f) - hh, kbClose.top, kbClose.left - dp(6f), kbClose.bottom)
        kbOctDown.set(kbOctUp.left - dp(4f) - hh, kbClose.top, kbOctUp.left - dp(4f), kbClose.bottom)
        kbMatrixBtn.set(kbOctDown.left - dp(8f) - hh * 2.4f, kbClose.top, kbOctDown.left - dp(8f), kbClose.bottom)
        kbIoBtn.set(kbMatrixBtn.left - dp(6f) - hh * 1.7f, kbClose.top, kbMatrixBtn.left - dp(6f), kbClose.bottom)
        val bh = dp(36f)
        kbTakeBtn.set(kbRect.left + dp(8f), kbRect.bottom - dp(8f) - bh, kbRect.left + dp(8f) + min(dp(150f), kbRect.width() * 0.4f), kbRect.bottom - dp(8f))
        val tb = dp(44f)
        kbStop.set(kbRect.right - dp(8f) - tb, kbTakeBtn.top, kbRect.right - dp(8f), kbTakeBtn.bottom)
        kbPlay.set(kbStop.left - dp(6f) - tb, kbTakeBtn.top, kbStop.left - dp(6f), kbTakeBtn.bottom)
        kbRec.set(kbPlay.left - dp(6f) - tb, kbTakeBtn.top, kbPlay.left - dp(6f), kbTakeBtn.bottom)
        // Werkzeugzeile
        val toolTop = kbClose.bottom + dp(8f)
        val th = dp(30f)
        val inner = RectF(kbRect.left + dp(8f), toolTop, kbRect.right - dp(8f), toolTop + th)
        val gap = dp(6f)
        fun row(rs: Array<RectF>, r: RectF) {
            val bw = (r.width() - (rs.size - 1) * gap) / rs.size
            for (i in rs.indices) rs[i].set(r.left + i * (bw + gap), r.top, r.left + i * (bw + gap) + bw, r.bottom)
        }
        var gridTop = inner.bottom + dp(8f)
        if (songView) {
            row(arrayOf(mxMode, mxBars, mxRaster, mxQuant, mxClear), inner)
            for (r in mxEditBtns) r.setEmpty()
        } else {
            // Zeile 1: Länge, Raster, Werkzeug, Zoom, Leeren · Zeile 2: Bearbeiten
            row(arrayOf(mxBars, mxRaster, mxMode, mxZoomOut, mxZoomIn, mxClear), inner)
            val r2 = RectF(inner.left, inner.bottom + dp(6f), inner.right, inner.bottom + dp(6f) + th)
            row(mxEditBtns, r2)
            gridTop = r2.bottom + dp(8f)
        }
        // Hauptbereich: links Klaviatur (bzw. Spurnamen), rechts Matrix
        kbKeysRect.set(kbRect.left + dp(8f), gridTop, kbRect.right - dp(8f), kbTakeBtn.top - dp(8f))
        val km = kbKeysRect
        val keyW = max(dp(46f), km.width() * 0.15f)
        val velH = if (songView) 0f else dp(42f)
        kbPiano.set(km.left, km.top, km.left + keyW, km.bottom - velH)
        mxGrid.set(kbPiano.right + dp(3f), km.top, km.right, km.bottom - velH)
        if (songView) mxVel.setEmpty() else mxVel.set(mxGrid.left, mxGrid.bottom + dp(4f), mxGrid.right, km.bottom)
        mxRows = (mxGrid.height() / (dp(15f) * mxRowZoom)).toInt().coerceIn(6, 72)
    }

    private fun closeKeyboard() {
        for (v in kbVoices.values) Engine.noteOff(v)
        kbVoices.clear()
        kbPointers.clear()
        kbHeld.clear()
        songPaintPointer = -1
        kbPad = -1
        Midi.openRollPad = -1
    }

    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")

    private fun isBlack(semi: Int): Boolean {
        val n = Math.floorMod(semi, 12)
        return n == 1 || n == 3 || n == 6 || n == 8 || n == 10
    }

    /** Halbton (relativ zum Original) unter einem Punkt auf der senkrechten Klaviatur, oder null. */
    private fun keyAt(x: Float, y: Float): Int? {
        if (songView) return null
        val r = kbPiano
        if (!r.contains(x, y)) return null
        val rowH = r.height() / mxRows
        val row = ((y - r.top) / rowH).toInt().coerceIn(0, mxRows - 1)
        return mxTopSemi() - row
    }

    private fun kbNoteOn(pid: Int, semi: Int) {
        // Taste markiert alle Noten dieser Tonhöhe (mehrere Finger = mehrere Reihen) – danach als Gruppe ziehen
        if (!songView && !Engine.seqRecording) {
            if (kbPointers.isEmpty()) mxSel.clear()
            val ids = Engine.notesOf(kbPad).filter { it.semi == semi }.map { it.id }
            mxSel.addAll(ids)
            if (ids.isNotEmpty() && kbPointers.isEmpty()) hint("${ids.size} Noten auf ${noteNames[Math.floorMod(semi, 12)]}${4 + Math.floorDiv(semi, 12)} markiert – eine davon ziehen verschiebt alle")
        }
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
                layoutKeyboard()
                if (songView && songTouchDown(id, x, y)) return true
                if (!songView && matrixTouch(id, x, y)) return true
                when {
                    kbIoBtn.contains(x, y) && !songView -> onRollIo?.invoke(kbPad)
                    kbMatrixBtn.contains(x, y) -> {
                        songView = !songView
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    kbClose.contains(x, y) -> closeKeyboard()
                    kbOctDown.contains(x, y) -> if (songView) songPage = max(0, songPage - 1) else kbOctave = (kbOctave - 1).coerceAtLeast(-3)
                    kbOctUp.contains(x, y) -> if (songView) songPage = min(songPages() - 1, songPage + 1) else kbOctave = (kbOctave + 1).coerceAtMost(3)
                    kbTakeBtn.contains(x, y) -> if (!songView) {
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
                var used = false
                if (mxPtr >= 0 && !songView) {
                    mxGridMove(e)
                    used = true
                }
                for (pi in 0 until e.pointerCount) {
                    val id = e.getPointerId(pi)
                    if (id == songPaintPointer) {
                        songPaint(e.getX(pi), e.getY(pi))
                        used = true
                        continue
                    }
                    val cur = kbPointers[id] ?: continue
                    val k = keyAt(e.getX(pi), e.getY(pi))
                    if (k != null && k != cur) {
                        kbNoteOff(id)
                        kbNoteOn(id, k)
                    }
                    used = true
                }
                return used
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == songPaintPointer) {
                    songPaintPointer = -1
                    return true
                }
                if (id == mxPtr || id == mxPtr2) {
                    mxGridUp(id)
                    return true
                }
                if (kbPointers.containsKey(id)) {
                    kbNoteOff(id)
                    return true
                }
                return kbPad >= 0 && kbRect.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (id in ArrayList(kbPointers.keys)) kbNoteOff(id)
                songPaintPointer = -1
                mxDrag = MX_NONE
                mxPtr = -1
                mxPtr2 = -1
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
        textPaint.textSize = min(dp(12f), (r.width() - dp(8f)) / max(2f, label.length * 0.62f))
        textPaint.color = if (active) Color.WHITE else cols[1]
        canvas.drawText(label, r.centerX(), r.centerY() + textPaint.textSize * 0.37f, textPaint)
    }

    private fun drawKeyboard(canvas: Canvas) {
        layoutKeyboard()
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
        textPaint.textSize = dp(14f)
        textPaint.color = cols[1]
        val title = if (songView) "🎬 Song" else "🎹 Pad ${kbPad + 1}"
        canvas.drawText(title, r.left + dp(12f), kbClose.centerY() - dp(1f), textPaint)
        textPaint.textSize = dp(9.5f)
        textPaint.color = alpha(cols[1], 0.7f)
        val sub = if (songView) "Seite ${songPage + 1}/${songPages()} · ${Engine.songBars} Takte" else {
            val ps = Engine.padSemi[kbPad]
            "Ton ${if (ps > 0) "+" else ""}$ps · Okt ${if (kbOctave > 0) "+" else ""}$kbOctave"
        }
        canvas.drawText(sub, r.left + dp(12f), kbClose.centerY() + dp(12f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        smallBtn(canvas, kbMatrixBtn, if (songView) "🎹 NOTEN" else "🎬 SONG", false, cols)
        if (!songView) smallBtn(canvas, kbIoBtn, "⇅ MIDI", false, cols)
        smallBtn(canvas, kbOctDown, if (songView) "◀" else "−", false, cols)
        smallBtn(canvas, kbOctUp, if (songView) "▶" else "+", false, cols)
        smallBtn(canvas, kbClose, "✕", false, cols)
        if (!songView) smallBtn(canvas, kbTakeBtn, if (kbTake) "📌 Ton fürs Pad: AN" else "📌 Ton fürs Pad", kbTake, cols)
        else smallBtn(canvas, kbTakeBtn, if (Engine.songMode) "▶ SONG-MODUS AN" else "SONG-MODUS AUS", Engine.songMode, cols)
        val recOn = Engine.seqRecording || Engine.songRecording
        smallBtn(canvas, kbRec, if (Engine.songRecording) "● S" else if (Engine.seqRecording) "● P" else "●", recOn, intArrayOf(cols[0], cols[1], Color.rgb(225, 40, 50)))
        smallBtn(canvas, kbPlay, "▶", Engine.seqPlaying, cols)
        smallBtn(canvas, kbStop, "■", false, cols)
        if (songView) drawSong(canvas, cols) else drawMatrix(canvas, cols)
        // gehaltene Töne
        if (kbHeld.isNotEmpty() && !songView) {
            val names = kbHeld.sorted().joinToString(" ") { sm -> noteNames[Math.floorMod(sm, 12)] + (4 + Math.floorDiv(sm, 12)) }
            textPaint.textSize = dp(11f)
            textPaint.color = cols[2]
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(names, kbTakeBtn.right + dp(8f), kbTakeBtn.centerY() + dp(4f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    // ---------------------------------------------------------------- Piano-Roll (wie Ableton: Klaviatur links, Matrix rechts)

    private var mxOpen: Boolean
        get() = !songView
        set(v) { songView = !v }
    private var songView = false
    private val kbMatrixBtn = RectF()
    private val kbIoBtn = RectF()
    /** Piano-Roll: Noten als MIDI-Datei importieren/exportieren (Pad). */
    var onRollIo: ((Int) -> Unit)? = null
    private val kbPiano = RectF()
    private val mxGrid = RectF()
    private val mxBars = RectF()
    private val mxRaster = RectF()
    private val mxQuant = RectF()
    private val mxClear = RectF()
    private val rasterSteps = intArrayOf(0, 2, 4, 8, 12, 16, 24, 32)
    private var mxRows = 24

    private fun rasterLabel(): String = if (Engine.rollQuant <= 0) "frei" else quantName(Engine.rollQuant)

    private fun layoutMatrix() = layoutKeyboard()

    /** Halbton der obersten Zeile. */
    private fun mxTopSemi(): Int = -12 + kbOctave * 12 + mxSemiOff + mxRows - 1

    // ---- Zoom, Auswahl, Zwischenablage
    private val mxMode = RectF()
    private val mxZoomOut = RectF()
    private val mxZoomIn = RectF()
    private val mxAll = RectF()
    private val mxCopy = RectF()
    private val mxPaste = RectF()
    private val mxDel = RectF()
    private val mxGroup = RectF()
    private val mxEditBtns = arrayOf(mxAll, mxCopy, mxPaste, mxDel, mxGroup, mxQuant)
    /** Lautstärke-Spur unter der Matrix (Balken je Note, hoch/runter ziehen). */
    private val mxVel = RectF()
    private val MX_VEL = 5

    private fun mxVelAt(x: Float, y: Float) {
        val p = kbPad
        val v = ((mxVel.bottom - dp(2f) - y) / (mxVel.height() - dp(4f))).coerceIn(0.05f, 1f)
        val pos = xToPos(x)
        val tol = mxView() * dp(8f) / mxGrid.width()
        val hits = Engine.notesOf(p).filter { pos >= it.pos - tol && pos <= it.pos + max(it.len, tol) }
        if (hits.isEmpty()) return
        // nächstgelegener Notenanfang; markierte Noten gemeinsam
        val best = hits.minByOrNull { abs(it.pos - pos) }!!
        val ids = if (best.id in mxSel && mxSel.size > 1) mxSel.toSet() else hits.filter { abs(it.pos - best.pos) < 1e-6 }.map { it.id }.toSet()
        Engine.setNoteVel(p, ids, v)
    }
    /** false = ✏ Stift (setzen/löschen/ziehen), true = ⬚ Auswahl (markieren, Rahmen ziehen, verschieben). */
    private var mxSelMode = false
    /** Horizontaler Zoom (1 = ganze Matrix sichtbar) und Startposition der Ansicht in Takten. */
    private var mxZoom = 1f
    private var mxScroll = 0.0
    /** Zeilenhöhe (vertikaler Zoom) und Verschiebung in Halbtönen. */
    private var mxRowZoom = 1f
    private var mxSemiOff = 0
    private val mxSel = HashSet<Int>()
    private var mxClip: List<Engine.NoteEv> = emptyList()
    private var mxClipSpan = 0.0
    /** Einfügemarke in Takten (-1 = keine). */
    private var mxInsert = -1.0

    private val MX_NONE = 0
    private val MX_MOVE = 1
    private val MX_RESIZE = 2
    private val MX_RUBBER = 3
    private val MX_PINCH = 4
    private var mxDrag = MX_NONE
    private var mxPtr = -1
    private var mxPtr2 = -1
    private var mxDownX = 0f
    private var mxDownY = 0f
    private var mxMoved = false
    private var mxNoteId = -1
    private var mxNoteLen0 = 0.0
    private var mxNotePos0 = 0.0
    private var mxAppliedPos = 0.0
    private var mxAppliedSemi = 0
    private var mxTapRemoves = false
    private var mxNewNote = false
    private val mxRubber = RectF()
    private var pz0 = 1f
    private var pzRow0 = 1f
    private var pzScroll0 = 0.0
    private var pzSemi0 = 0
    private var pzSpanX0 = 0f
    private var pzSpanY0 = 0f
    private var pzFocusPos0 = 0.0
    private var pzFocusY0 = 0f

    private fun mxBarsOf(): Int = Engine.rollBars[kbPad]
    private fun mxView(): Double = mxBarsOf() / mxZoom.toDouble()
    private fun clampScroll() {
        val bars = mxBarsOf().toDouble()
        mxScroll = mxScroll.coerceIn(0.0, max(0.0, bars - mxView()))
    }
    private fun posToX(pos: Double): Float = mxGrid.left + ((pos - mxScroll) / mxView()).toFloat() * mxGrid.width()
    private fun xToPos(x: Float): Double = mxScroll + ((x - mxGrid.left) / mxGrid.width()).toDouble() * mxView()
    private fun rowH(): Float = mxGrid.height() / mxRows
    private fun yToSemi(y: Float): Int = mxTopSemi() - ((y - mxGrid.top) / rowH()).toInt().coerceIn(0, mxRows - 1)
    private fun semiToY(semi: Int): Float = mxGrid.top + (mxTopSemi() - semi) * rowH()
    private fun mxQ(): Int = if (Engine.rollQuant > 0) Engine.rollQuant else 16
    private fun snap(pos: Double): Double { val q = mxQ(); return kotlin.math.floor(pos * q + 1e-9) / q }
    private fun snapRound(d: Double): Double { val q = mxQ(); return kotlin.math.round(d * q) / q.toDouble() }

    private fun noteAt(x: Float, y: Float): Engine.NoteEv? {
        val semi = yToSemi(y)
        val pos = xToPos(x)
        val slack = (mxView() * dp(4f) / mxGrid.width())
        return Engine.notesOf(kbPad).lastOrNull { it.semi == semi && pos >= it.pos - slack && pos < it.pos + it.len + slack }
    }

    private fun setZoom(z: Float, focusPos: Double? = null) {
        val fp = focusPos ?: (mxScroll + mxView() / 2)
        val frac = (fp - mxScroll) / mxView()
        mxZoom = z.coerceIn(1f, 32f)
        mxScroll = fp - frac * mxView()
        clampScroll()
    }

    private fun mxGridDown(id: Int, x: Float, y: Float) {
        val p = kbPad
        if (mxVel.contains(x, y) && mxPtr < 0) {
            mxPtr = id
            mxDrag = MX_VEL
            mxMoved = false
            Engine.pushPatternUndo("Lautstärke")
            mxVelAt(x, y)
            return
        }
        if (mxPtr >= 0 && mxPtr != id) {
            // zweiter Finger: Zoom (auseinander/zusammen) und Verschieben – solange noch nichts verschoben wurde
            if (mxDrag == MX_MOVE && mxMoved || mxDrag == MX_RESIZE && mxMoved) return
            if (mxDrag == MX_RESIZE && mxNewNote) Engine.deleteNotes(p, setOf(mxNoteId))
            mxPtr2 = id
            mxDrag = MX_PINCH
            pzSpanX0 = -1f
            return
        }
        mxPtr = id
        mxDownX = x
        mxDownY = y
        mxMoved = false
        mxNewNote = false
        mxAppliedPos = 0.0
        mxAppliedSemi = 0
        val hit = noteAt(x, y)
        if (hit != null) {
            val x1 = posToX(hit.pos + hit.len)
            val edge = max(dp(12f), (x1 - posToX(hit.pos)) * 0.25f)
            if (!mxSelMode && x > x1 - edge) {
                mxDrag = MX_RESIZE
                mxNoteId = hit.id
                mxNoteLen0 = hit.len
                mxNotePos0 = hit.pos
                return
            }
            mxDrag = MX_MOVE
            mxNoteId = hit.id
            if (mxSelMode) {
                if (hit.id in mxSel) mxTapRemoves = true
                else {
                    mxTapRemoves = false
                    mxSel.addAll(Engine.expandGroups(p, setOf(hit.id)))
                }
            } else {
                // Stift: Note (bzw. Auswahl, wenn sie dazugehört) ziehen; antippen löscht
                mxTapRemoves = true
                if (hit.id !in mxSel) {
                    mxSel.clear()
                    mxSel.addAll(Engine.expandGroups(p, setOf(hit.id)))
                }
            }
            val v = Engine.noteOn(p, hit.semi)
            postDelayed({ Engine.noteOff(v) }, 160L)
            return
        }
        if (mxSelMode) {
            mxDrag = MX_RUBBER
            mxRubber.set(x, y, x, y)
            return
        }
        // Stift auf leerem Feld: Note setzen, nach rechts ziehen = länger
        val q = mxQ()
        val pos = snap(xToPos(x).coerceIn(0.0, mxBarsOf() - 1e-6))
        val semi = yToSemi(y)
        Engine.pushPatternUndo("Note")
        val ids = Engine.addNotes(p, listOf(Engine.NoteEv(pos, 1.0 / q, semi, 1f)))
        mxSel.clear()
        mxDrag = MX_RESIZE
        mxNewNote = true
        mxNoteId = ids.firstOrNull() ?: -1
        mxNoteLen0 = 1.0 / q
        mxNotePos0 = pos
        val v = Engine.noteOn(p, semi)
        postDelayed({ Engine.noteOff(v) }, 180L)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun mxGridMove(e: MotionEvent) {
        val p = kbPad
        if (mxDrag == MX_PINCH) {
            val i1 = e.findPointerIndex(mxPtr)
            val i2 = e.findPointerIndex(mxPtr2)
            if (i1 < 0 || i2 < 0) return
            val x1 = e.getX(i1); val y1 = e.getY(i1); val x2 = e.getX(i2); val y2 = e.getY(i2)
            val sx = abs(x2 - x1); val sy = abs(y2 - y1)
            val fx = (x1 + x2) / 2; val fy = (y1 + y2) / 2
            if (pzSpanX0 < 0f) {
                pzSpanX0 = sx; pzSpanY0 = sy
                pz0 = mxZoom; pzRow0 = mxRowZoom
                pzScroll0 = mxScroll; pzSemi0 = mxSemiOff
                pzFocusPos0 = xToPos(fx)
                pzFocusY0 = fy
                return
            }
            if (pzSpanX0 > dp(40f)) mxZoom = (pz0 * sx / pzSpanX0).coerceIn(1f, 32f)
            if (pzSpanY0 > dp(40f)) {
                mxRowZoom = (pzRow0 * sy / pzSpanY0).coerceIn(0.5f, 3f)
                layoutKeyboard()
            }
            // Position unter den Fingern bleibt stehen (Verschieben mit zwei Fingern)
            val frac = ((fx - mxGrid.left) / mxGrid.width()).toDouble()
            mxScroll = pzFocusPos0 - frac * mxView()
            clampScroll()
            mxSemiOff = (pzSemi0 + ((fy - pzFocusY0) / rowH()).toInt()).coerceIn(-36, 36)
            return
        }
        val i = e.findPointerIndex(mxPtr)
        if (i < 0) return
        val x = e.getX(i); val y = e.getY(i)
        if (mxDrag == MX_VEL) {
            mxVelAt(x, y)
            return
        }
        if (!mxMoved && abs(x - mxDownX) < dp(6f) && abs(y - mxDownY) < dp(6f)) return
        if (!mxMoved) {
            mxMoved = true
            if (mxDrag == MX_MOVE) Engine.pushPatternUndo("Noten verschieben")
            else if (mxDrag == MX_RESIZE && !mxNewNote) Engine.pushPatternUndo("Notenlänge")
        }
        when (mxDrag) {
            MX_MOVE -> {
                val ids = if (mxSel.isNotEmpty()) mxSel.toSet() else setOf(mxNoteId)
                val dPos = snapRound(((x - mxDownX) / mxGrid.width()).toDouble() * mxView())
                val dSemi = -kotlin.math.round((y - mxDownY) / rowH()).toInt()
                val stepPos = dPos - mxAppliedPos
                val stepSemi = dSemi - mxAppliedSemi
                if (abs(stepPos) > 1e-9 || stepSemi != 0) {
                    Engine.moveNotes(p, ids, stepPos, stepSemi)
                    mxAppliedPos = dPos
                    if (stepSemi != 0) {
                        mxAppliedSemi = dSemi
                        Engine.notesById(p, setOf(mxNoteId)).firstOrNull()?.let { n ->
                            val v = Engine.noteOn(p, n.semi)
                            postDelayed({ Engine.noteOff(v) }, 120L)
                        }
                    }
                }
            }
            MX_RESIZE -> {
                val q = mxQ()
                val end = max(mxNotePos0 + 1.0 / q, kotlin.math.ceil(xToPos(x) * q - 1e-6) / q)
                Engine.setNoteLen(p, mxNoteId, min(end, mxBarsOf().toDouble()) - mxNotePos0)
            }
            MX_RUBBER -> {
                mxRubber.set(min(mxDownX, x), min(mxDownY, y), max(mxDownX, x), max(mxDownY, y))
                val a = xToPos(mxRubber.left); val b = xToPos(mxRubber.right)
                val sTop = yToSemi(mxRubber.top); val sBot = yToSemi(mxRubber.bottom)
                val ids = Engine.notesOf(p).filter { it.pos < b && it.pos + it.len > a && it.semi in sBot..sTop }.map { it.id }.toSet()
                mxSel.clear()
                mxSel.addAll(Engine.expandGroups(p, ids))
            }
        }
    }

    private fun mxGridUp(id: Int) {
        val p = kbPad
        if (mxDrag == MX_PINCH) {
            if (id == mxPtr || id == mxPtr2) {
                mxDrag = MX_NONE
                mxPtr = -1
                mxPtr2 = -1
            }
            return
        }
        if (id != mxPtr) return
        if (!mxMoved) {
            when (mxDrag) {
                MX_MOVE -> if (mxTapRemoves) {
                    if (mxSelMode) {
                        mxSel.removeAll(Engine.expandGroups(p, setOf(mxNoteId)))
                    } else {
                        Engine.pushPatternUndo("Note löschen")
                        Engine.deleteNotes(p, setOf(mxNoteId))
                        mxSel.remove(mxNoteId)
                    }
                }
                MX_RUBBER -> {
                    // Tipp ins Leere: Auswahl aufheben, Einfügemarke setzen
                    mxSel.clear()
                    mxInsert = snap(xToPos(mxDownX).coerceIn(0.0, mxBarsOf() - 1e-6))
                }
                MX_RESIZE -> if (!mxNewNote) {
                    Engine.pushPatternUndo("Note löschen")
                    Engine.deleteNotes(p, setOf(mxNoteId))
                }
            }
        }
        mxDrag = MX_NONE
        mxPtr = -1
    }

    private fun mxEditButton(x: Float, y: Float): Boolean {
        val p = kbPad
        val cols = overlayColors()
        when {
            mxMode.contains(x, y) -> {
                mxSelMode = !mxSelMode
                hint(if (mxSelMode) "⬚ Auswahl: Noten antippen oder Rahmen ziehen, markierte Noten verschieben · ins Leere tippen = Einfügemarke"
                     else "✏ Stift: leeres Feld setzt eine Note (ziehen = länger), Note antippen löscht, Note ziehen verschiebt")
            }
            mxZoomIn.contains(x, y) -> { setZoom(mxZoom * 2f); hint("Zoom ${zoomLabel()} · zwei Finger: zoomen und verschieben") }
            mxZoomOut.contains(x, y) -> { setZoom(mxZoom / 2f); hint("Zoom ${zoomLabel()}") }
            mxAll.contains(x, y) -> {
                val all = Engine.notesOf(p).map { it.id }
                if (all.isNotEmpty() && mxSel.containsAll(all)) mxSel.clear() else { mxSel.clear(); mxSel.addAll(all) }
            }
            mxCopy.contains(x, y) -> {
                val sel = Engine.notesById(p, mxSel)
                if (sel.isEmpty()) { hint("Erst Noten markieren (⬚ Auswahl oder ALLE)"); return true }
                val start = sel.minOf { it.pos }
                val end = sel.maxOf { it.pos + it.len }
                mxClip = sel.map { Engine.NoteEv(it.pos - start, it.len, it.semi, it.vel, it.group) }
                // Länge auf ganze Schläge aufrunden – Einfügen setzt nahtlos dahinter
                mxClipSpan = max(0.25, kotlin.math.ceil((end - start) * 4 - 1e-6) / 4)
                mxInsert = start + mxClipSpan
                if (mxInsert >= mxBarsOf()) mxInsert = 0.0
                hint("${sel.size} Noten kopiert · EINF. setzt sie an der Einfügemarke ein")
                burst(mxCopy.centerX(), mxCopy.centerY(), cols[2], 14, 160f)
            }
            mxPaste.contains(x, y) -> {
                if (mxClip.isEmpty()) { hint("Zwischenablage leer – erst KOPIE"); return true }
                val at = if (mxInsert >= 0) mxInsert else 0.0
                Engine.pushPatternUndo("Einfügen")
                val ids = Engine.addNotes(p, mxClip.map { Engine.NoteEv(it.pos + at, it.len, it.semi, it.vel, it.group) })
                mxSel.clear()
                mxSel.addAll(ids)
                mxInsert = at + mxClipSpan
                if (mxInsert >= mxBarsOf()) mxInsert = 0.0
                if (ids.size < mxClip.size) hint("${ids.size} von ${mxClip.size} Noten eingefügt – der Rest liegt hinter dem Ende (TAKTE erhöhen)")
                burst(mxPaste.centerX(), mxPaste.centerY(), cols[2], 14, 160f)
            }
            mxDel.contains(x, y) -> {
                if (mxSel.isEmpty()) { hint("Nichts markiert"); return true }
                Engine.pushPatternUndo("Noten löschen")
                Engine.deleteNotes(p, mxSel)
                mxSel.clear()
                burst(mxDel.centerX(), mxDel.centerY(), Color.rgb(230, 40, 55), 14, 160f)
            }
            mxGroup.contains(x, y) -> {
                if (mxSel.size < 2) { hint("Mindestens zwei Noten markieren"); return true }
                Engine.pushPatternUndo("Gruppe")
                val g = Engine.toggleGroup(p, mxSel)
                hint(if (g) "${mxSel.size} Noten gruppiert – sie werden immer zusammen markiert und verschoben" else "Gruppe aufgelöst")
            }
            mxQuant.contains(x, y) -> {
                val q = mxQ()
                Engine.pushPatternUndo("Quantisieren")
                if (mxSel.isNotEmpty()) {
                    Engine.quantizeNotes(p, q, mxSel.toSet())
                    hint("${mxSel.size} markierte Noten auf 1/$q quantisiert")
                } else {
                    Engine.quantizeNotes(p, q)
                    hint("Alle Noten auf 1/$q quantisiert")
                }
                // IDs nach dem Entfernen von Doppelten bereinigen
                val alive = Engine.notesOf(p).map { it.id }.toSet()
                mxSel.retainAll(alive)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                burst(mxQuant.centerX(), mxQuant.centerY(), cols[2], 18, 200f)
            }
            else -> return false
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    private fun zoomLabel(): String = if (mxZoom < 1.05f) "1×" else String.format(Locale.GERMANY, "%.0f×", mxZoom)

    private fun matrixTouch(id: Int, x: Float, y: Float): Boolean {
        val p = kbPad
        when {
            mxBars.contains(x, y) -> {
                val nb = when (Engine.rollBars[p]) { 1 -> 2; 2 -> 4; 4 -> 8; 8 -> 16; else -> 1 }
                Engine.pushPatternUndo("Matrix-Länge")
                Engine.setRollBars(p, nb)
                clampScroll()
                return true
            }
            mxRaster.contains(x, y) -> {
                val i = rasterSteps.indexOf(Engine.rollQuant).coerceAtLeast(0)
                Engine.rollQuant = rasterSteps[(i + 1) % rasterSteps.size]
                return true
            }
            mxClear.contains(x, y) -> {
                Engine.pushPatternUndo("Matrix leeren")
                Engine.clearNotes(p)
                mxSel.clear()
                burst(mxClear.centerX(), mxClear.centerY(), Color.rgb(230, 40, 55), 18, 200f)
                return true
            }
            mxGrid.contains(x, y) || mxVel.contains(x, y) -> {
                mxGridDown(id, x, y)
                return true
            }
        }
        return mxEditButton(x, y)
    }

    /** Senkrechte Klaviatur links neben der Matrix. */
    private fun drawPianoKeys(canvas: Canvas, cols: IntArray) {
        val k = kbPiano
        val rowH = k.height() / mxRows
        val top = mxTopSemi()
        fillPaint.shader = null
        fillPaint.color = Color.rgb(248, 246, 240)
        canvas.drawRect(k, fillPaint)
        for (row in 0 until mxRows) {
            val semi = top - row
            val y0 = k.top + row * rowH
            val held = kbHeld.contains(semi)
            if (isBlack(semi)) {
                fillPaint.color = if (held) cols[2] else Color.rgb(28, 28, 32)
                canvas.drawRect(k.left, y0, k.left + k.width() * 0.62f, y0 + rowH, fillPaint)
            } else if (held) {
                fillPaint.color = cols[2]
                canvas.drawRect(k.left, y0, k.right, y0 + rowH, fillPaint)
            }
            val n = Math.floorMod(semi, 12)
            // Trennlinien zwischen weißen Tasten (unter C und F)
            if (n == 0 || n == 5) {
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(110, 0, 0, 0)
                canvas.drawLine(k.left, y0 + rowH, k.right, y0 + rowH, strokePaint)
            }
            if (n == 0) {
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = min(dp(10f), rowH * 0.9f)
                textPaint.color = if (held) Color.WHITE else Color.rgb(80, 80, 80)
                canvas.drawText("C${4 + Math.floorDiv(semi, 12)}", k.right - dp(4f), y0 + rowH * 0.8f, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            }
            val slc = Engine.sliceOf(kbPad, semi)
            if (slc >= 0) {
                // Slice-Taste: Nummer des Slices
                fillPaint.color = alpha(cols[2], if (held) 0.9f else 0.28f)
                canvas.drawRect(k.left + k.width() * 0.64f, y0 + dp(1f), k.right - dp(1f), y0 + rowH - dp(1f), fillPaint)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.textSize = min(dp(9f), rowH * 0.85f)
                textPaint.color = if (isBlack(semi) && !held) Color.WHITE else Color.rgb(20, 20, 20)
                canvas.drawText("S${slc + 1}", k.left + dp(3f), y0 + rowH * 0.78f, textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            } else if (semi == Engine.padSemi[kbPad]) {
                dotPaint.color = cols[2]
                canvas.drawCircle(k.right - dp(28f), y0 + rowH / 2f, dp(2.5f), dotPaint)
            }
        }
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[1], 0.4f)
        canvas.drawRect(k, strokePaint)
    }

    private fun drawMatrix(canvas: Canvas, cols: IntArray) {
        val p = kbPad
        val bars = Engine.rollBars[p]
        clampScroll()
        smallBtn(canvas, mxBars, "$bars TAKT" + if (bars > 1) "E" else "", false, cols)
        smallBtn(canvas, mxRaster, "▦ ${rasterLabel()}", Engine.rollQuant > 0, cols)
        smallBtn(canvas, mxMode, if (mxSelMode) "⬚ AUSWAHL" else "✏ STIFT", mxSelMode, cols)
        smallBtn(canvas, mxZoomOut, "🔍−", false, cols)
        smallBtn(canvas, mxZoomIn, "🔍+ " + zoomLabel(), mxZoom > 1.05f, cols)
        smallBtn(canvas, mxClear, "LEEREN", false, cols)
        val hasSel = mxSel.isNotEmpty()
        smallBtn(canvas, mxAll, "ALLE", false, cols)
        smallBtn(canvas, mxCopy, "KOPIE", false, cols)
        smallBtn(canvas, mxPaste, if (mxClip.isNotEmpty()) "EINF. ${mxClip.size}" else "EINF.", false, cols)
        smallBtn(canvas, mxDel, if (hasSel) "LÖSCH ${mxSel.size}" else "LÖSCH", false, cols)
        smallBtn(canvas, mxGroup, "GRUPPE", false, cols)
        smallBtn(canvas, mxQuant, if (hasSel) "QUANT ${mxSel.size}" else "QUANT.", false, cols)
        drawPianoKeys(canvas, cols)
        val g = mxGrid
        fillPaint.shader = null
        fillPaint.color = Color.rgb(30, 31, 37)
        canvas.drawRect(g, fillPaint)
        canvas.save()
        canvas.clipRect(g)
        val rowH = rowH()
        val top = mxTopSemi()
        for (row in 0 until mxRows) {
            val semi = top - row
            val y0 = g.top + row * rowH
            if (isBlack(semi)) {
                fillPaint.color = Color.rgb(22, 23, 28)
                canvas.drawRect(g.left, y0, g.right, y0 + rowH, fillPaint)
            }
            strokePaint.strokeWidth = dp(if (Math.floorMod(semi, 12) == 0) 1f else 0.5f)
            strokePaint.color = Color.argb(if (Math.floorMod(semi, 12) == 0) 80 else 26, 255, 255, 255)
            canvas.drawLine(g.left, y0 + rowH, g.right, y0 + rowH, strokePaint)
        }
        // Rasterlinien im sichtbaren Ausschnitt (feiner, je weiter hineingezoomt)
        val view = mxView()
        val colW16 = g.width() / (view * 16).toFloat()
        val div = when {
            colW16 > dp(26f) -> 64
            colW16 > dp(12f) -> 32
            else -> 16
        }
        val first = kotlin.math.floor(mxScroll * div).toInt()
        val last = kotlin.math.ceil((mxScroll + view) * div).toInt()
        val q = Engine.rollQuant
        for (c in first..last) {
            val x = posToX(c.toDouble() / div)
            val (wdt, a) = when {
                c % div == 0 -> dp(1.6f) to 150
                (c * 4) % div == 0 -> dp(1f) to 80
                q > 0 && (c * q) % div == 0 -> dp(0.8f) to 45
                g.width() / (view * div).toFloat() > dp(5f) -> dp(0.5f) to 22
                else -> 0f to 0
            }
            if (a == 0) continue
            strokePaint.strokeWidth = wdt
            strokePaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawLine(x, g.top, x, g.bottom, strokePaint)
        }
        // Taktnummern (bei Zoom auch Schläge)
        textPaint.textSize = dp(8.5f)
        textPaint.color = alpha(cols[1], 0.55f)
        textPaint.textAlign = Paint.Align.LEFT
        val showBeats = g.width() / (view * 4).toFloat() > dp(28f)
        val b0 = kotlin.math.floor(mxScroll * 4).toInt()
        val b1 = kotlin.math.ceil((mxScroll + view) * 4).toInt()
        for (bt in b0..b1) {
            if (bt % 4 != 0 && !showBeats) continue
            val label = if (bt % 4 == 0) "${bt / 4 + 1}" else "${bt / 4 + 1}.${bt % 4 + 1}"
            canvas.drawText(label, posToX(bt / 4.0) + dp(2f), g.top + dp(9f), textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        val acc = cols[2]
        val list = Engine.notesOf(p)
        for (ev in list) {
            val row = top - ev.semi
            val x0 = posToX(ev.pos)
            val x1 = posToX(ev.pos + ev.len)
            if (x1 < g.left || x0 > g.right) continue
            val sel = ev.id in mxSel
            if (row < 0 || row >= mxRows) {
                textPaint.textSize = dp(9f)
                textPaint.color = if (sel) Color.WHITE else acc
                canvas.drawText(if (row < 0) "▲" else "▼", x0 + dp(4f), if (row < 0) g.top + dp(9f) else g.bottom - dp(2f), textPaint)
                continue
            }
            val y0 = g.top + row * rowH
            tmpRect.set(x0 + dp(0.5f), y0 + dp(0.5f), max(x0 + dp(3f), x1 - dp(0.5f)), y0 + rowH - dp(0.5f))
            val gc = if (ev.group > 0) Color.HSVToColor(floatArrayOf((ev.group * 67f) % 360f, 0.55f, 1f)) else Color.WHITE
            fillPaint.color = if (sel) blend(acc, Color.WHITE, 0.55f) else alpha(acc, 0.35f + 0.65f * ev.vel)
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), fillPaint)
            strokePaint.strokeWidth = dp(if (sel) 2.2f else 1f)
            strokePaint.color = if (sel) Color.WHITE else gc
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), strokePaint)
            if (ev.group > 0) {
                // Gruppe: farbiger Balken unten an der Note
                fillPaint.color = gc
                canvas.drawRect(tmpRect.left, tmpRect.bottom - max(dp(2f), rowH * 0.18f), tmpRect.right, tmpRect.bottom, fillPaint)
            }
            if (!mxSelMode && tmpRect.width() > dp(18f)) {
                // Griff zum Verlängern
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = Color.argb(150, 0, 0, 0)
                val hx = tmpRect.right - dp(4f)
                canvas.drawLine(hx, tmpRect.top + rowH * 0.25f, hx, tmpRect.bottom - rowH * 0.25f, strokePaint)
            }
        }
        // Einfügemarke
        if (mxInsert >= 0) {
            val x = posToX(mxInsert)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = Color.rgb(255, 210, 40)
            var yy = g.top
            while (yy < g.bottom) {
                canvas.drawLine(x, yy, x, min(g.bottom, yy + dp(6f)), strokePaint)
                yy += dp(10f)
            }
            dotPaint.color = Color.rgb(255, 210, 40)
            canvas.drawCircle(x, g.top + dp(3f), dp(3.5f), dotPaint)
        }
        // Auswahlrahmen
        if (mxDrag == MX_RUBBER && mxMoved) {
            fillPaint.color = alpha(acc, 0.18f)
            canvas.drawRect(mxRubber, fillPaint)
            strokePaint.strokeWidth = dp(1.4f)
            strokePaint.color = acc
            canvas.drawRect(mxRubber, strokePaint)
        }
        val pr = Engine.rollProgress(p)
        if (pr >= 0f) {
            val x = posToX(pr.toDouble() * bars)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = if (Engine.seqRecording) Color.rgb(230, 40, 55) else Color.WHITE
            canvas.drawLine(x, g.top, x, g.bottom, strokePaint)
        }
        canvas.restore()
        // Bildlaufleiste beim Zoomen
        if (mxZoom > 1.05f) {
            val a0 = (mxScroll / bars).toFloat()
            val a1 = ((mxScroll + view) / bars).toFloat()
            fillPaint.color = alpha(cols[1], 0.15f)
            canvas.drawRect(g.left, g.bottom - dp(3f), g.right, g.bottom, fillPaint)
            fillPaint.color = acc
            canvas.drawRect(g.left + g.width() * a0, g.bottom - dp(3f), g.left + g.width() * a1, g.bottom, fillPaint)
        }
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[1], 0.4f)
        canvas.drawRect(g, strokePaint)
        // Lautstärke-Spur
        val vl = mxVel
        if (vl.height() > 0f) {
            fillPaint.color = Color.rgb(22, 23, 29)
            canvas.drawRect(vl, fillPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = dp(9f)
            textPaint.color = alpha(cols[1], 0.7f)
            canvas.drawText("VEL", vl.left - dp(4f), vl.centerY() + dp(3f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.save()
            canvas.clipRect(vl)
            for (ev in list) {
                val x0 = posToX(ev.pos)
                if (x0 < vl.left - dp(4f) || x0 > vl.right) continue
                val top = vl.bottom - dp(2f) - (vl.height() - dp(4f)) * ev.vel.coerceIn(0f, 1f)
                strokePaint.strokeWidth = dp(3f)
                strokePaint.color = if (ev.id in mxSel) Color.WHITE else acc
                canvas.drawLine(x0 + dp(2f), top, x0 + dp(2f), vl.bottom - dp(2f), strokePaint)
                dotPaint.color = Color.WHITE
                canvas.drawCircle(x0 + dp(2f), top, dp(2.5f), dotPaint)
            }
            canvas.restore()
        }
        if (Engine.seqRecording) {
            textPaint.textSize = dp(9f)
            textPaint.color = Color.rgb(255, 80, 90)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("● REC · ${rasterLabel()}", g.right - dp(4f), g.bottom - dp(6f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    // ---------------------------------------------------------------- Song-Matrix (Arrangement: Spuren × Takte)

    private var songPage = 0
    private var songPaintPointer = -1
    private var songPaintVal = 0
    private val SONG_COLS = 16

    private val PAT_COLS = intArrayOf(Color.rgb(90, 190, 255), Color.rgb(120, 230, 140), Color.rgb(255, 190, 70), Color.rgb(240, 120, 200))

    private fun songPages(): Int = max(1, (Engine.songBars + SONG_COLS - 1) / SONG_COLS)

    private fun songRowName(r: Int): String = when {
        r < VISIBLE_PADS -> "P${r + 1}" + when {
            !Engine.hasLoop(r) -> ""
            Engine.isOneShot(r) -> " ⚡"
            else -> " ∞"
        }
        r < Engine.ARR_INST -> ""
        else -> (r - Engine.ARR_INST).let { i -> InstState.instNames[i] + " " + InstState.patLetter(InstState.curPat[i]) }
    }

    private fun songCell(x: Float, y: Float): Pair<Int, Int>? {
        val g = mxGrid
        if (!g.contains(x, y)) return null
        val rows = Engine.ARR_ROWS
        val r = ((y - g.top) / (g.height() / rows)).toInt().coerceIn(0, rows - 1)
        val c = ((x - g.left) / (g.width() / SONG_COLS)).toInt().coerceIn(0, SONG_COLS - 1)
        val bar = songPage * SONG_COLS + c
        if (bar >= Engine.songBars) return null
        return r to bar
    }

    // ---------------------------------------------------------------- Automation (im Song)

    private var autoView = false
    private var autoLane = 0
    private var autoErase = false
    private var autoLastIdx = -1
    private var autoLastV = 0f

    private fun autoIdx(x: Float): Int {
        val g = mxGrid
        val perPage = SONG_COLS * Engine.AUTO_RES
        val i = songPage * perPage + ((x - g.left) / g.width() * perPage).toInt().coerceIn(0, perPage - 1)
        return i.coerceIn(0, Engine.songBars * Engine.AUTO_RES - 1)
    }

    private fun autoPaint(x: Float, y: Float) {
        val g = mxGrid
        val idx = autoIdx(x)
        val v = (1f - (y - g.top) / g.height()).coerceIn(0f, 1f)
        val a = Engine.auto[autoLane]
        if (autoLastIdx < 0 || autoLastIdx == idx) a[idx] = if (autoErase) Float.NaN else v
        else {
            val st = if (autoLastIdx < idx) 1 else -1
            var i = autoLastIdx
            while (true) {
                val t = (i - autoLastIdx).toFloat() / (idx - autoLastIdx)
                a[i] = if (autoErase) Float.NaN else autoLastV + (v - autoLastV) * t
                if (i == idx) break
                i += st
            }
        }
        autoLastIdx = idx
        autoLastV = v
    }

    private fun autoTouchDown(id: Int, x: Float, y: Float): Boolean {
        when {
            mxRaster.contains(x, y) -> { autoErase = !autoErase; hint(if (autoErase) "⌫ Radierer: über die Linie wischen löscht sie dort (Regler gilt wieder)" else "✎ Zeichnen: Finger über die Fläche ziehen = Verlauf malen"); return true }
            mxQuant.contains(x, y) -> {
                Engine.autoRec = !Engine.autoRec
                hint(if (Engine.autoRec) "● XY-Aufnahme: im Song-Modus ▶ und auf der Effekt-Fläche spielen – die Bewegung wird in FX X / FX Y geschrieben" else "XY-Aufnahme aus")
                return true
            }
            mxClear.contains(x, y) -> { Engine.autoClearLane(autoLane); hint("${Engine.AUTO_NAMES[autoLane]}: Automation gelöscht"); return true }
            kbPiano.contains(x, y) -> {
                autoLane = ((y - kbPiano.top) / (kbPiano.height() / Engine.AUTO_LANES)).toInt().coerceIn(0, Engine.AUTO_LANES - 1)
                hint("${Engine.AUTO_NAMES[autoLane]}: ${Engine.AUTO_HELP[autoLane]} · wirkt im Song-Modus")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            mxGrid.contains(x, y) -> {
                autoLastIdx = -1
                autoPaint(x, y)
                songPaintPointer = id
                return true
            }
        }
        return false
    }

    private fun drawAuto(canvas: Canvas, cols: IntArray) {
        smallBtn(canvas, mxRaster, if (autoErase) "⌫ RADIERER" else "✎ ZEICHNEN", autoErase, cols)
        smallBtn(canvas, mxQuant, "● XY-AUFN.", Engine.autoRec, intArrayOf(cols[0], cols[1], Color.rgb(225, 40, 50)))
        smallBtn(canvas, mxClear, "SPUR LEEREN", false, cols)
        val k = kbPiano
        val g = mxGrid
        val n = Engine.AUTO_LANES
        val rh = k.height() / n
        for (l in 0 until n) {
            tmpRect.set(k.left, k.top + l * rh + dp(1f), k.right, k.top + (l + 1) * rh - dp(1f))
            val sel = l == autoLane
            fillPaint.color = if (sel) cols[2] else blend(cols[0], cols[1], 0.08f)
            canvas.drawRoundRect(tmpRect, dp(4f), dp(4f), fillPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = min(dp(10f), rh * 0.3f)
            textPaint.color = if (sel) Color.WHITE else alpha(cols[1], 0.8f)
            canvas.drawText(fitLabel(textPaint, Engine.AUTO_NAMES[l], tmpRect.width() - dp(12f), textPaint.textSize, dp(6f)), tmpRect.left + dp(4f), tmpRect.centerY() + textPaint.textSize * 0.36f, textPaint)
            if (Engine.autoLaneUsed(l)) {
                fillPaint.color = Color.rgb(255, 200, 60)
                canvas.drawCircle(tmpRect.right - dp(5f), tmpRect.top + dp(5f), dp(2.5f), fillPaint)
            }
            textPaint.textAlign = Paint.Align.CENTER
        }
        fillPaint.color = Color.rgb(24, 25, 31)
        canvas.drawRect(g, fillPaint)
        val colW = g.width() / SONG_COLS
        for (c in 0..SONG_COLS) {
            val bar = songPage * SONG_COLS + c
            val x0 = g.left + c * colW
            if (c < SONG_COLS && bar >= Engine.songBars) {
                fillPaint.color = Color.argb(150, 0, 0, 0)
                canvas.drawRect(x0, g.top, x0 + colW, g.bottom, fillPaint)
            }
            strokePaint.strokeWidth = dp(if (bar % 4 == 0) 1.2f else 0.5f)
            strokePaint.color = Color.argb(if (bar % 4 == 0) 110 else 40, 255, 255, 255)
            canvas.drawLine(x0, g.top, x0, g.bottom, strokePaint)
            if (c < SONG_COLS && (bar % 4 == 0 || colW > dp(18f))) {
                textPaint.textSize = dp(8.5f); textPaint.color = alpha(cols[1], 0.6f); textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("${bar + 1}", x0 + dp(2f), g.top - dp(2f), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            }
        }
        strokePaint.strokeWidth = dp(0.5f)
        strokePaint.color = Color.argb(35, 255, 255, 255)
        for (q in 1..3) canvas.drawLine(g.left, g.top + g.height() * q / 4, g.right, g.top + g.height() * q / 4, strokePaint)
        // Linie der gewählten Spur (Lücken = Regler gilt)
        val a = Engine.auto[autoLane]
        val perPage = SONG_COLS * Engine.AUTO_RES
        val dx = g.width() / perPage
        val base = songPage * perPage
        val col = PAT_COLS[autoLane % PAT_COLS.size]
        val path = android.graphics.Path()
        val fillPath = android.graphics.Path()
        var open = false
        var startX = 0f
        var lastX = 0f
        for (i in 0 until perPage) {
            val idx = base + i
            val v = if (idx < a.size) a[idx] else Float.NaN
            val x = g.left + (i + 0.5f) * dx
            if (v.isNaN()) {
                if (open) { fillPath.lineTo(lastX, g.bottom); fillPath.lineTo(startX, g.bottom); fillPath.close(); open = false }
                continue
            }
            val y = g.bottom - v * g.height()
            if (!open) { path.moveTo(x, y); fillPath.moveTo(x, y); startX = x; open = true } else { path.lineTo(x, y); fillPath.lineTo(x, y) }
            lastX = x
        }
        if (open) { fillPath.lineTo(lastX, g.bottom); fillPath.lineTo(startX, g.bottom); fillPath.close() }
        fillPaint.color = Color.argb(60, Color.red(col), Color.green(col), Color.blue(col))
        canvas.drawPath(fillPath, fillPaint)
        strokePaint.strokeWidth = dp(2.2f)
        strokePaint.color = col
        canvas.drawPath(path, strokePaint)
        // Abspielpunkt
        val pos = Engine.autoPos
        if (pos >= 0f && pos >= base && pos < base + perPage) {
            val x = g.left + (pos - base) * dx
            strokePaint.strokeWidth = dp(1.5f); strokePaint.color = Color.WHITE
            canvas.drawLine(x, g.top, x, g.bottom, strokePaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(cols[1], 0.75f)
        canvas.drawText(Engine.AUTO_NAMES[autoLane] + (if (!Engine.songMode) " · wirkt nur im SONG-MODUS" else ""), g.left + dp(4f), g.top + dp(12f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[1], 0.4f)
        canvas.drawRect(g, strokePaint)
    }

    private fun songTouchDown(id: Int, x: Float, y: Float): Boolean {
        if (mxMode.contains(x, y)) {
            autoView = !autoView
            hint(if (autoView) "〰 Automation: links Spur wählen, rechts Verlauf malen (Filter, Effekt, Lautstärke über die Takte) – läuft im Song-Modus" else "▦ Song-Matrix")
            return true
        }
        if (autoView && autoTouchDown(id, x, y)) return true
        when {
            mxBars.contains(x, y) -> {
                val opts = intArrayOf(4, 8, 16, 32, 64)
                val i = opts.indexOf(Engine.songBars)
                Engine.songBars = opts[(i + 1) % opts.size]
                songPage = min(songPage, songPages() - 1)
                return true
            }
            mxRaster.contains(x, y) -> {
                // aktuelle Lage (welche Pads laufen) in den Takt unter dem Abspielpunkt bzw. Takt 1 schreiben
                val bar = if (Engine.songMode && Engine.seqPlaying) Engine.songBar() else songPage * SONG_COLS
                Engine.arrCaptureBar(bar, VISIBLE_PADS)
                hint("Takt ${bar + 1}: aktuelle Pads übernommen")
                return true
            }
            mxQuant.contains(x, y) -> {
                Engine.arrFillFromScenes(VISIBLE_PADS)
                hint("Song aus den Szenen gebaut (jede Szene so viele Takte wie eingestellt)")
                return true
            }
            mxClear.contains(x, y) -> {
                Engine.arrClear()
                burst(mxClear.centerX(), mxClear.centerY(), Color.rgb(230, 40, 55), 18, 200f)
                return true
            }
            kbTakeBtn.contains(x, y) -> {
                Engine.songMode = !Engine.songMode
                hint(if (Engine.songMode) "Song-Modus an: ▶ spielt den Song ab Takt 1 – die Matrix schaltet die Spuren" else "Song-Modus aus: Pads wieder frei")
                return true
            }
            kbPiano.contains(x, y) -> {
                val rows = Engine.ARR_ROWS
                val r = ((y - kbPiano.top) / (kbPiano.height() / rows)).toInt().coerceIn(0, rows - 1)
                when {
                    r < VISIBLE_PADS -> if (Engine.hasLoop(r)) Engine.toggleMute(r)
                    r >= Engine.ARR_INST && x > kbPiano.right - kbPiano.width() * 0.38f -> { openInstFromSong(r - Engine.ARR_INST); return true }
                    r >= Engine.ARR_INST -> (r - Engine.ARR_INST).let { i -> InstState.setInstOn(i, !InstState.instOn(i)) }
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
        }
        val cell = songCell(x, y) ?: return false
        val cur = Engine.arrCell(cell.first, cell.second)
        songPaintVal = if (cell.first >= Engine.ARR_INST) {
            // Instrument: leer → aktuelles Pattern, dann A → B → C → D → aus
            val i = cell.first - Engine.ARR_INST
            if (cur == 0) InstState.curPat[i] + 1 else (cur + 1) % (InstState.PATS + 1)
        } else if (cur > 0) 0 else 1
        Engine.setArrCell(cell.first, cell.second, songPaintVal)
        if (cell.first >= Engine.ARR_INST && songPaintVal > 0) {
            val i = cell.first - Engine.ARR_INST
            if (!InstState.patHasNotes(i, songPaintVal - 1)) hint("${InstState.instNames[i]} Pattern ${InstState.patLetter(songPaintVal - 1)} ist noch leer – im ${InstState.instNames[i]}-Editor oben A–D wählen")
        }
        songPaintPointer = id
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    private fun songPaint(x: Float, y: Float) {
        if (autoView) { if (mxGrid.contains(x, mxGrid.centerY())) autoPaint(x, y.coerceIn(mxGrid.top, mxGrid.bottom)); return }
        val cell = songCell(x, y) ?: return
        val v = if (cell.first >= Engine.ARR_INST) songPaintVal else if (songPaintVal > 0) 1 else 0
        Engine.setArrCell(cell.first, cell.second, v)
    }

    /** Noten-Vorschau je Instrument und Pattern (wird alle ~0,3 s neu gelesen). */
    private val notePrev = Array(InstState.INSTS) { arrayOfNulls<FloatArray>(InstState.PATS) }
    private var notePrevAt = 0L

    /** Pro Schritt: −1 = nichts, sonst 0..1 (Tonhöhe bzw. Drum-Art). */
    private fun cellNotes(inst: Int, p: Int): FloatArray? {
        val now = System.nanoTime()
        if (now - notePrevAt > 300_000_000L) {
            notePrevAt = now
            for (i in 0 until InstState.INSTS) for (q in 0 until InstState.PATS) {
                val pat = InstState.patFor(i, q)
                notePrev[i][q] = if (pat == null || !pat.hasNotes(i)) null else FloatArray(pat.len) { k ->
                    when (i) {
                        1 -> { var v = -1f; for (d in pat.ints.indices.reversed()) if (pat.ints[d][k] > 0) v = d / 10f; v }
                        else -> if (pat.bools[0][k]) ((pat.ints[0][k] + 12).coerceIn(0, 48) / 48f) else -1f
                    }
                }
            }
        }
        return notePrev.getOrNull(inst)?.getOrNull(p)
    }

    private fun drawCellNotes(canvas: Canvas, inst: Int, p: Int, bar: Int, r: RectF) {
        val n = cellNotes(inst, p) ?: return
        if (r.width() < dp(8f) || r.height() < dp(6f)) return
        val bars = max(1, n.size / 16)
        val off = (bar % bars) * 16
        val sw = r.width() / 16f
        fillPaint.color = Color.argb(200, 20, 20, 26)
        for (k in 0 until 16) {
            val v = n.getOrNull(off + k) ?: break
            if (v < 0f) continue
            val x = r.left + k * sw
            val y = r.bottom - dp(1.5f) - v * (r.height() - dp(4f))
            canvas.drawRect(x + sw * 0.15f, y - dp(1.4f), x + sw * 0.85f, y, fillPaint)
        }
    }

    /** Instrument-Editor direkt aus der Song-Matrix öffnen (Spur [inst]: 0 ACID, 1 DRUM, 2.. SYNTH). */
    private fun openInstFromSong(inst: Int) {
        when (inst) {
            0 -> instEd.open(1)
            1 -> instEd.open(2)
            else -> { InstState.synCur = (inst - 2).coerceIn(0, InstState.SYN_TRACKS - 1); instEd.open(3) }
        }
        hint("✎ ${InstState.instNames[inst]} · Pattern ${InstState.patLetter(InstState.curPat[inst])} – oben A–D wechselt das Pattern, ✕ zurück zum Song")
        invalidate()
    }

    private fun drawSong(canvas: Canvas, cols: IntArray) {
        smallBtn(canvas, mxMode, if (autoView) "▦ SPUREN" else "〰 AUTOM.", autoView, cols)
        if (autoView) {
            smallBtn(canvas, mxBars, "${Engine.songBars} TAKTE", false, cols)
            drawAuto(canvas, cols)
            return
        }
        smallBtn(canvas, mxBars, "${Engine.songBars} TAKTE", false, cols)
        smallBtn(canvas, mxRaster, "＋ LIVE", false, cols)
        smallBtn(canvas, mxQuant, "AUS SZENEN", false, cols)
        smallBtn(canvas, mxClear, "LEEREN", false, cols)
        val rows = Engine.ARR_ROWS
        val k = kbPiano
        val g = mxGrid
        val rowH = g.height() / rows
        fillPaint.shader = null
        // Spurnamen links
        for (r in 0 until rows) {
            val y0 = k.top + r * rowH
            tmpRect.set(k.left, y0 + dp(1f), k.right, y0 + rowH - dp(1f))
            val on = when {
                r < VISIBLE_PADS -> Engine.hasLoop(r) && !Engine.isMuted(r)
                r < Engine.ARR_INST -> false
                else -> InstState.instOn(r - Engine.ARR_INST)
            }
            fillPaint.color = if (on) blend(cols[0], cols[2], 0.45f) else blend(cols[0], cols[1], 0.08f)
            canvas.drawRoundRect(tmpRect, dp(4f), dp(4f), fillPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = if (on) Color.WHITE else alpha(cols[1], 0.75f)
            val nameW = tmpRect.width() * (if (r >= Engine.ARR_INST) 0.6f else 0.95f) - dp(6f)
            val nm = fitLabel(textPaint, songRowName(r), nameW, min(dp(10.5f), rowH * 0.6f), dp(6f))
            canvas.drawText(nm, tmpRect.left + dp(5f), tmpRect.centerY() + textPaint.textSize * 0.36f, textPaint)
            textPaint.textSize = min(dp(10.5f), rowH * 0.6f)
            textPaint.textAlign = Paint.Align.CENTER
            if (r >= Engine.ARR_INST) {
                // ✎: Noten-Editor dieses Instruments öffnen
                val ex = tmpRect.right - tmpRect.width() * 0.19f
                fillPaint.color = alpha(cols[2], 0.85f)
                canvas.drawRoundRect(ex - tmpRect.width() * 0.17f, tmpRect.top + dp(1f), tmpRect.right - dp(1f), tmpRect.bottom - dp(1f), dp(3f), dp(3f), fillPaint)
                textPaint.color = Color.WHITE
                canvas.drawText("✎", ex, tmpRect.centerY() + textPaint.textSize * 0.36f, textPaint)
            }
        }
        fillPaint.color = Color.rgb(24, 25, 31)
        canvas.drawRect(g, fillPaint)
        val colW = g.width() / SONG_COLS
        val cur = if (Engine.songMode && Engine.seqPlaying) Engine.songBar() else -1
        for (c in 0 until SONG_COLS) {
            val bar = songPage * SONG_COLS + c
            val x0 = g.left + c * colW
            if (bar >= Engine.songBars) {
                fillPaint.color = Color.argb(150, 0, 0, 0)
                canvas.drawRect(x0, g.top, x0 + colW, g.bottom, fillPaint)
                continue
            }
            if (bar == cur) {
                fillPaint.color = Color.argb(60, 255, 255, 255)
                canvas.drawRect(x0, g.top, x0 + colW, g.bottom, fillPaint)
            }
            for (r in 0 until rows) {
                if (r in VISIBLE_PADS until Engine.ARR_INST) continue
                val v = Engine.arrCell(r, bar)
                if (v <= 0) continue
                val y0 = g.top + r * rowH
                tmpRect.set(x0 + dp(1.5f), y0 + dp(1.5f), x0 + colW - dp(1.5f), y0 + rowH - dp(1.5f))
                if (r >= Engine.ARR_INST) {
                    fillPaint.color = PAT_COLS[(v - 1).coerceIn(0, 3)]
                    canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
                    // gesetzte Noten dieses Takts als kleine Striche (Höhe = Tonhöhe, bei Drums: Kick unten)
                    drawCellNotes(canvas, r - Engine.ARR_INST, v - 1, bar, tmpRect)
                    // gleiches Pattern wie im Takt davor: nur dünn verbinden, Buchstabe beim Wechsel
                    val prev = if (bar > 0) Engine.arrCell(r, bar - 1) else 0
                    if (prev != v && tmpRect.width() > dp(7f)) {
                        textPaint.textSize = min(dp(10f), rowH * 0.7f)
                        textPaint.color = Color.rgb(20, 20, 24)
                        canvas.drawText(InstState.patLetter(v - 1), tmpRect.centerX(), tmpRect.centerY() + textPaint.textSize * 0.36f, textPaint)
                    }
                } else {
                    fillPaint.color = cols[2]
                    canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
                }
            }
            strokePaint.strokeWidth = dp(if (bar % 4 == 0) 1.2f else 0.5f)
            strokePaint.color = Color.argb(if (bar % 4 == 0) 110 else 40, 255, 255, 255)
            canvas.drawLine(x0, g.top, x0, g.bottom, strokePaint)
            if (bar % 4 == 0 || colW > dp(18f)) {
                textPaint.textSize = dp(8.5f)
                textPaint.color = alpha(cols[1], 0.6f)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("${bar + 1}", x0 + dp(2f), g.top - dp(2f), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            }
        }
        for (r in 0..rows) {
            strokePaint.strokeWidth = dp(0.5f)
            strokePaint.color = Color.argb(30, 255, 255, 255)
            canvas.drawLine(g.left, g.top + r * rowH, g.right, g.top + r * rowH, strokePaint)
        }
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[1], 0.4f)
        canvas.drawRect(g, strokePaint)
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
    private val edKnobPitch = RectF()
    private val edBtnKeylock = RectF()
    /** Pitch in Halbtönen (−12 … +12) und Keylock (Tonhöhe halten, nur Tempo ändern). */
    private var edPitch = 0
    private var edKeylock = false
    private var edPitchTapT = 0L
    /** Umrechnung Vorhör-Position → Position in den Original-Daten (bei Keylock-Vorhören). */
    private var edHeadScale = 1.0
    private val edBtnPlay = RectF()
    private val edBtnCut = RectF()
    private val edBtnSlice = RectF()
    private val edBtnPads = RectF()
    private val edBtnSave = RectF()
    /** Wave-Editor: Ausschnitt bzw. Slices in die Bibliothek speichern (Pad, Audio, Slices). */
    var onEditorSave: ((Int, FloatArray, Int, IntArray) -> Unit)? = null
    private val edBtnClose = RectF()
    private var edPeaks = FloatArray(0)
    // Lupe / Zoom: sichtbarer Bereich (Frames) und Werkzeuge
    private var edV0 = 0
    private var edV1 = 0
    /** 0 = frei, 1 = Nulldurchgang, 2 = Beat (nahe Schläge, sonst Nulldurchgang) */
    private var edSnapMode = 1
    private val edSnap: Boolean get() = edSnapMode != 0

    private fun edBeatsOn(): Boolean = Engine.beatLen() > 0.0 && edPad >= 0 && !Engine.isOneShot(edPad) && !Engine.isFree(edPad)

    private fun snapF(f0: Int): Int {
        if (edSnapMode == 2 && edBeatsOn()) {
            val bl = Engine.beatLen()
            val bf = (Math.round(f0 / bl) * bl).toInt().coerceIn(0, edData.size)
            if (abs(frameToX(bf) - frameToX(f0)) < dp(18f)) return bf
        }
        return if (edSnapMode != 0) zeroCross(f0) else f0
    }
    private val edZoomOut = RectF()
    private val edZoomIn = RectF()
    private val edToS = RectF()
    private val edToE = RectF()
    private val edSnapBtn = RectF()
    private val edCueBtn = RectF()
    /** Cue-Punkte (Frames im Audio, -1 = leer) – für die DJ-Bibliothek. */
    private val edCues = IntArray(4) { -1 }
    private var edCueMode = false
    private var edMoved = false
    private var edDownX = 0f
    private var edMarkOrig = 0
    private var edPinchF0 = 0.0
    private var edPanX = 0f
    private var edPanV0 = 0
    private var edPinch0 = 0f
    private var edPinchV0 = 0
    private var edPinchV1 = 0
    private var edDrag = 0          // 1 = Start, 2 = Ende, 3 = Slices-Regler, 4 = Takte-Regler
    private var edDragY = 0f
    private var edDragVal = 0f
    private var edPreview = -1
    /** Abspielkopf (Frame) – läuft beim Abhören mit, mit dem Finger verschiebbar. */
    private var edHead = 0
    private var edPlayFrom = 0
    private var edHeadWasPlaying = false
    private val edRuler = RectF()
    private val edBtnFromHead = RectF()
    private val edBtnLoop = RectF()
    /** Auswahl S…E läuft als Endlosschleife (um zu hören, ob der Loop nahtlos ist). */
    private var edLooping = false
    private var edRulerTap = 0L

    private fun edStopPreview() {
        if (edPreview >= 0) Engine.noteOff(edPreview)
        edPreview = -1
        edLooping = false
        edPrevGen++
    }

    /** S…E endlos abspielen – genau so, wie der Loop später auf dem Pad läuft. */
    private fun edStartLoop() {
        edStopPreview()
        val len = edEnd - edStart
        if (len < 64) return
        val d = FloatArray(len + 1)
        System.arraycopy(edData, edStart, d, 0, len)
        d[len] = edData[edStart]
        edPlayFrom = edStart
        edHead = edStart
        edPitchedPreview(d, true)
    }

    /** Abspielen ab [from] bis zum Endpunkt E (bzw. Dateiende, wenn der Kopf hinter E liegt). */
    private fun edPlayFrom(from: Int) {
        edStopPreview()
        val to = if (from < edEnd) edEnd else edData.size
        if (to - from < 2) return
        edPlayFrom = from
        edHead = from
        edPitchedPreview(edData.copyOfRange(from, to), false)
    }

    /** Vorhören mit PITCH: ohne Keylock schneller/langsamer, mit Keylock gedehnt (Tonhöhe bleibt). */
    private fun edPitchedPreview(seg: FloatArray, loop: Boolean) {
        val f = Math.pow(2.0, edPitch / 12.0)
        if (edPitch == 0) {
            edHeadScale = 1.0
            edPreview = if (loop) Engine.previewLoop(seg) else Engine.preview(seg)
            edLooping = loop && edPreview >= 0
        } else if (!edKeylock) {
            edHeadScale = 1.0
            edPreview = Engine.previewRate(seg, f, loop)
            edLooping = loop && edPreview >= 0
        } else {
            edToast("Keylock: wird gedehnt …")
            val gen = ++edPrevGen
            Thread {
                val st = Engine.pitchProcess(if (loop) seg.copyOf(seg.size - 1) else seg, edPitch.toFloat(), true)
                val d = if (loop) st.copyOf(st.size + 1).also { it[st.size] = st[0] } else st
                post {
                    if (gen != edPrevGen || edPad < 0) return@post
                    edHeadScale = f
                    edPreview = if (loop) Engine.previewLoop(d) else Engine.preview(d)
                    edLooping = loop && edPreview >= 0
                }
            }.start()
        }
    }
    private var edPrevGen = 0

    /** Kopfposition beim Abspielen nachführen (und vergrößert mitscrollen). */
    private fun edUpdateHead() {
        if (edPreview < 0) return
        val pos = Engine.voicePos(edPreview)
        if (pos < 0) {
            edPreview = -1
            return
        }
        val sp = pos * edHeadScale
        edHead = (edPlayFrom + (if (edLooping) sp % max(1, edEnd - edStart) else sp)).toInt().coerceIn(0, edData.size)
        val span = edV1 - edV0
        if (span < edData.size && (edHead > edV1 || edHead < edV0) && edDrag == 0) {
            val v0 = (edHead - span / 10).coerceIn(0, max(0, edData.size - span))
            edV0 = v0
            edV1 = v0 + span
            computePeaks()
        }
    }
    private val barOptions = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)

    fun openEditor(p: Int) {
        val d = Engine.loopData(p) ?: return
        closeKeyboard()
        edPad = p
        edData = d
        edStart = 0
        edEnd = d.size
        edV0 = 0
        edV1 = d.size
        edNorm = 0f
        edHead = 0
        edPreview = -1
        edCueMode = false
        java.util.Arrays.fill(edCues, -1)
        edSlices = max(1, Engine.padSlices(p))
        edBars = if (Engine.padSlices(p) > 1) Engine.padBars(p) else Engine.suggestBars(d.size)
        edMsg = ""
        edSnapMode = if (Engine.beatLen() > 0.0 && !Engine.isOneShot(p)) 2 else 1
        edPitch = 0
        edHeadScale = 1.0
        layoutEditor()
        computePeaks()
    }

    private fun layoutEditor() {
        val m = dp(10f)
        edRect.set(m, insetTop + m, width - m, height - insetBottom - m)
        val hh = dp(40f)
        edBtnClose.set(edRect.right - dp(10f) - hh, edRect.top + dp(10f), edRect.right - dp(10f), edRect.top + dp(10f) + hh)
        val ty = edBtnClose.bottom + dp(8f)
        val tg = dp(6f)
        val tbw = (edRect.width() - dp(24f) - 5 * tg) / 6f
        val tools = arrayOf(edZoomOut, edZoomIn, edToS, edToE, edSnapBtn, edCueBtn)
        for (i in tools.indices) tools[i].set(edRect.left + dp(12f) + i * (tbw + tg), ty, edRect.left + dp(12f) + i * (tbw + tg) + tbw, ty + dp(32f))
        edRuler.set(edRect.left + dp(12f), ty + dp(32f) + dp(8f), edRect.right - dp(12f), ty + dp(32f) + dp(30f))
        edWave.set(edRect.left + dp(12f), edRuler.bottom + dp(2f), edRect.right - dp(12f), edRuler.bottom + dp(2f) + edRect.height() * 0.30f)
        val ks = min(dp(86f), (edRect.width() - dp(24f) - dp(70f) - 3 * dp(12f)) / 3f)
        val ky = edWave.bottom + dp(52f)
        val chipW = dp(70f)
        val totalW = 3 * ks + 3 * dp(12f) + chipW
        var kx = edRect.centerX() - totalW / 2f
        edKnobSlices.set(kx, ky, kx + ks, ky + ks); kx += ks + dp(12f)
        edKnobBars.set(kx, ky, kx + ks, ky + ks); kx += ks + dp(12f)
        edKnobPitch.set(kx, ky, kx + ks, ky + ks); kx += ks + dp(12f)
        edBtnKeylock.set(kx, ky + ks / 2f - dp(22f), kx + chipW, ky + ks / 2f + dp(22f))
        val by = edKnobSlices.bottom + dp(36f)
        val bw = (edRect.width() - dp(24f) - dp(10f)) / 2f
        val bh = dp(46f)
        val pw = (bw - dp(12f)) / 3f
        edBtnPlay.set(edRect.left + dp(12f), by, edRect.left + dp(12f) + pw, by + bh)
        edBtnLoop.set(edBtnPlay.right + dp(6f), by, edBtnPlay.right + dp(6f) + pw, by + bh)
        edBtnFromHead.set(edBtnLoop.right + dp(6f), by, edRect.left + dp(12f) + bw, by + bh)
        edBtnCut.set(edBtnFromHead.right + dp(10f), by, edBtnFromHead.right + dp(10f) + bw, by + bh)
        edBtnSlice.set(edBtnPlay.left, by + bh + dp(10f), edBtnFromHead.right, by + 2 * bh + dp(10f))
        edBtnPads.set(edBtnCut.left, by + bh + dp(10f), edBtnCut.right, by + 2 * bh + dp(10f))
        edBtnSave.set(edBtnPlay.left, edBtnPads.bottom + dp(10f), edBtnCut.right, edBtnPads.bottom + dp(10f) + bh)
    }

    private fun computePeaks() {
        val cols = max(1, edWave.width().toInt() / 2)
        edPeaks = FloatArray(cols)
        val d = edData
        if (d.isEmpty()) return
        if (edV1 <= edV0) { edV0 = 0; edV1 = d.size }
        val span = edV1 - edV0
        val per = max(1, span / cols)
        for (c in 0 until cols) {
            val a = edV0 + (c.toLong() * span / cols).toInt()
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
        // Normierung auf die ganze Datei, damit die Höhe beim Zoomen gleich bleibt
        if (edNorm <= 0f) {
            var mx = 0f
            var i = 0
            val st = max(1, d.size / 20000)
            while (i < d.size) { val v = abs(d[i]); if (v > mx) mx = v; i += st }
            edNorm = max(1e-4f, mx)
        }
        for (c in edPeaks.indices) edPeaks[c] = min(1f, edPeaks[c] / edNorm)
    }

    private var edNorm = 0f

    /** Zoom so setzen, dass [center] in der Mitte liegt; [span] = sichtbare Frames. */
    private fun edSetView(center: Int, span0: Int) {
        val n = edData.size
        val minSpan = max(64, Engine.sampleRate / 400)
        val span = span0.coerceIn(min(n, minSpan), n)
        var a = center - span / 2
        a = a.coerceIn(0, max(0, n - span))
        edV0 = a
        edV1 = a + span
        computePeaks()
    }

    private fun edZoomLabel(): String {
        val z = edData.size.toFloat() / max(1, edV1 - edV0)
        return if (z < 1.05f) "1×" else if (z < 10f) String.format(Locale.GERMANY, "%.1f×", z) else "${z.toInt()}×"
    }

    /** Nächster Nulldurchgang (gegen Knackser beim Schneiden). */
    private fun zeroCross(f: Int): Int {
        val d = edData
        if (d.size < 4) return f
        val r = min(Engine.sampleRate / 200, max(32, (edV1 - edV0) / 200))
        var best = f
        var bd = Int.MAX_VALUE
        for (i in max(1, f - r)..min(d.size - 1, f + r)) {
            if ((d[i - 1] <= 0f && d[i] > 0f) || (d[i - 1] >= 0f && d[i] < 0f)) {
                val dd = abs(i - f)
                if (dd < bd) { bd = dd; best = i }
            }
        }
        return best
    }

    private fun xToFrame(x: Float): Int =
        edV0 + (((x - edWave.left) / edWave.width()).coerceIn(0f, 1f) * (edV1 - edV0)).toInt()

    private fun frameToX(f: Int): Float =
        edWave.left + edWave.width() * (if (edV1 <= edV0) 0f else (f - edV0).toFloat() / (edV1 - edV0))

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
                        edStopPreview()
                        edPad = -1
                    }
                    e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount == 2 && (edWave.contains(x, y) || edDrag in 1..2 || edDrag in 5..7) -> {
                        // Daumen + Zeigefinger: auseinander = hineinzoomen, zusammen = heraus, gemeinsam ziehen = verschieben
                        if (edDrag == 1) edStart = edMarkOrig
                        if (edDrag == 2) edEnd = edMarkOrig
                        edDrag = 6
                        edPinch0 = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        edPinchV0 = edV0
                        edPinchV1 = edV1
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        edPinchF0 = edV0 + ((cx - edWave.left) / edWave.width()).coerceIn(0f, 1f).toDouble() * (edV1 - edV0)
                    }
                    edRuler.contains(x, y) || (edWave.contains(x, y) && abs(x - frameToX(edHead)) < dp(16f) &&
                        abs(x - frameToX(edHead)) < abs(x - frameToX(edStart)) && abs(x - frameToX(edHead)) < abs(x - frameToX(edEnd))) -> {
                        // Abspielkopf greifen und verschieben; Doppeltipp auf die Leiste spielt ab dort
                        val now = SystemClock.uptimeMillis()
                        if (edRuler.contains(x, y) && now - edRulerTap < 320) {
                            edHead = xToFrame(x)
                            edPlayFrom(edHead)
                            edRulerTap = 0
                            edDrag = 0
                        } else {
                            edRulerTap = now
                            edHeadWasPlaying = edPreview >= 0
                            edStopPreview()
                            edDrag = 8
                            edHead = xToFrame(x)
                        }
                    }
                    edCueMode && edWave.contains(x, y) -> {
                        // Cue erst beim Loslassen setzen – so kann noch ein zweiter Finger zum Zoomen dazukommen
                        edDrag = 7
                        edDownX = x
                    }
                    edCueBtn.contains(x, y) -> {
                        edCueMode = !edCueMode
                        edToast(if (edCueMode) "📍 Cue-Modus: in die Wellenform tippen setzt Hot Cues 1–4 (Startpunkt = S)" else "Cue-Modus aus – Marker S/E ziehen")
                    }
                    edWave.contains(x, y) || (y > edWave.bottom && y < edWave.bottom + dp(30f) && x >= edWave.left && x <= edWave.right) -> {
                        val sx = frameToX(edStart)
                        val ex = frameToX(edEnd)
                        val zoomed = edV1 - edV0 < edData.size
                        val near = dp(36f)
                        edDrag = when {
                            abs(x - sx) <= near && abs(x - sx) <= abs(x - ex) -> 1
                            abs(x - ex) <= near -> 2
                            zoomed -> 5
                            abs(x - sx) <= abs(x - ex) -> 1
                            else -> 2
                        }
                        edMoved = false
                        edDownX = x
                        if (edDrag == 5) {
                            edPanX = x
                            edPanV0 = edV0
                        } else {
                            edMarkOrig = if (edDrag == 1) edStart else edEnd
                        }
                    }
                    edZoomOut.contains(x, y) -> edSetView(edData.size / 2, edData.size)
                    edZoomIn.contains(x, y) -> edSetView((edStart + edEnd) / 2, ((edEnd - edStart) * 1.1).toInt())
                    edToS.contains(x, y) -> edSetView(edStart, min(edV1 - edV0, max(Engine.sampleRate / 10, (edEnd - edStart) / 8)))
                    edToE.contains(x, y) -> edSetView(edEnd, min(edV1 - edV0, max(Engine.sampleRate / 10, (edEnd - edStart) / 8)))
                    edSnapBtn.contains(x, y) -> {
                        edSnapMode = when (edSnapMode) { 2 -> 0; 0 -> 1; else -> if (edBeatsOn()) 2 else 0 }
                        edToast(when (edSnapMode) {
                            2 -> "Marker rasten auf Schläge (Taktraster), sonst auf Nulldurchgänge"
                            1 -> "Marker rasten auf Nulldurchgänge (kein Knacksen)"
                            else -> "Marker frei"
                        })
                    }
                    edKnobSlices.contains(x, y) -> { edDrag = 3; edDragY = y; edDragVal = edSlices.toFloat() }
                    edKnobBars.contains(x, y) -> { edDrag = 4; edDragY = y; edDragVal = barOptions.indexOfFirst { it == edBars }.coerceAtLeast(0).toFloat() }
                    edKnobPitch.contains(x, y) -> {
                        val now = SystemClock.uptimeMillis()
                        if (now - edPitchTapT < 320L) { edPitch = 0; edToast("Pitch 0") }
                        edPitchTapT = now
                        edDrag = 9; edDragY = y; edDragVal = edPitch.toFloat()
                    }
                    edBtnKeylock.contains(x, y) -> {
                        edKeylock = !edKeylock
                        edToast(if (edKeylock) "🔒 Keylock: Pitch ändert nur das Tempo – die Tonhöhe bleibt" else "Keylock aus: Pitch ändert Tempo und Tonhöhe (wie ein Plattenspieler)")
                    }
                    edBtnPlay.contains(x, y) -> {
                        if (edPreview >= 0) edStopPreview() else edPlayFrom(edStart)
                    }
                    edBtnLoop.contains(x, y) -> {
                        if (edLooping) edStopPreview() else {
                            edStartLoop()
                            if (edLooping) edToast("Loop S…E läuft – Marker verschieben, beim Loslassen hörst du sofort den neuen Loop")
                        }
                    }
                    edBtnFromHead.contains(x, y) -> {
                        if (edPreview >= 0) edStopPreview() else edPlayFrom(edHead.coerceIn(0, max(0, edData.size - 2)))
                    }
                    edBtnCut.contains(x, y) -> {
                        val synced = !Engine.isOneShot(edPad) && !Engine.isFree(edPad) && Engine.beatLen() > 0.0
                        val pitchMsg = when {
                            edPitch == 0 -> ""
                            synced && edKeylock -> " · Pitch mit Keylock: im Raster bleibt das Tempo – keine Änderung"
                            synced -> " · ${if (edPitch > 0) "+" else ""}$edPitch HT transponiert"
                            else -> " · Pitch ${if (edPitch > 0) "+" else ""}$edPitch HT" + if (edKeylock) " (Keylock)" else ""
                        }
                        Engine.applyEdit(edPad, edStart, edEnd, 1, edBars, edPitch.toFloat(), edKeylock)
                        edPitch = 0
                        val bl = when (edBars) { 0.25f -> "¼"; 0.5f -> "½"; else -> edBars.toInt().toString() }
                        reloadEditor((if (!synced) "Zugeschnitten" else "Zugeschnitten – exakt $bl Takt" + (if (edBars > 1f) "e" else "") + ", läuft im Raster") + pitchMsg)
                    }
                    edBtnSlice.contains(x, y) -> {
                        if (Engine.isOneShot(edPad)) {
                            edToast("Slicen geht bei ∞ LOOP-Pads")
                        } else if (edSlices < 2) {
                            edToast("Mit dem SLICES-Regler mind. 2 wählen")
                        } else {
                            Engine.applyEdit(edPad, edStart, edEnd, edSlices, edBars, edPitch.toFloat(), edKeylock)
                            edPitch = 0
                            reloadEditor("$edSlices Slices – liegen jetzt als Noten S1…S$edSlices in der Piano-Roll (🎹)")
                        }
                    }
                    edBtnSave.contains(x, y) -> {
                        if (edEnd - edStart > 2) {
                            // Cue-Punkte relativ zum Ausschnitt (nur die innerhalb von S…E)
                            val cues = IntArray(4) { k -> val c = edCues[k]; if (c in edStart until edEnd) c - edStart else -1 }
                            onEditorSave?.invoke(edPad, edData.copyOfRange(edStart, edEnd), edSlices, cues)
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
                    8 -> edHead = xToFrame(x)
                    1, 2 -> if (edMoved || abs(x - edDownX) > dp(4f)) {
                        edMoved = true
                        moveMarker(x)
                    }
                    5 -> {
                        val span = edV1 - edV0
                        val nv0 = (edPanV0 - ((x - edPanX) / edWave.width() * span).toInt()).coerceIn(0, max(0, edData.size - span))
                        if (nv0 != edV0) {
                            edV0 = nv0
                            edV1 = nv0 + span
                            computePeaks()
                        }
                    }
                    6 -> if (e.pointerCount >= 2 && edPinch0 > dp(20f)) {
                        val d1 = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val span0 = edPinchV1 - edPinchV0
                        val n = edData.size
                        val span = (span0 * edPinch0 / max(dp(10f), d1)).toInt().coerceIn(min(n, max(64, Engine.sampleRate / 400)), n)
                        // die Stelle unter den Fingern bleibt unter den Fingern
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val rel = ((cx - edWave.left) / edWave.width()).coerceIn(0f, 1f)
                        val v0 = (edPinchF0 - rel * span).toInt().coerceIn(0, max(0, n - span))
                        if (v0 != edV0 || v0 + span != edV1) {
                            edV0 = v0
                            edV1 = v0 + span
                            computePeaks()
                        }
                    }
                    3 -> edSlices = (edDragVal + (edDragY - y) / dp(12f)).toInt().coerceIn(1, 32)
                    4 -> edBars = barOptions[(edDragVal + (edDragY - y) / dp(30f)).toInt().coerceIn(0, barOptions.size - 1)]
                    9 -> edPitch = Math.round(edDragVal + (edDragY - y) / dp(14f)).coerceIn(-12, 12)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                // kurzer Tipp ohne Ziehen: Marker springt an die Stelle
                if (!cancel && (edDrag == 1 || edDrag == 2) && !edMoved) moveMarker(edDownX)
                if (edDrag == 1 || edDrag == 2) edBars = Engine.suggestBars(edEnd - edStart)
                // Loop läuft: neue Marker sofort hörbar machen
                if (edLooping && (edDrag == 1 || edDrag == 2)) edStartLoop()
                if (!cancel && edDrag == 7 && e.actionMasked == MotionEvent.ACTION_UP) edCueTap(edDownX)
                // Kopf losgelassen: lief es vorher, geht es ab der neuen Stelle weiter
                if (!cancel && edDrag == 8 && edHeadWasPlaying) edPlayFrom(edHead)
                edDrag = 0
            }
        }
    }

    private fun edCueTap(x: Float) {
                val near = edCues.indexOfFirst { it >= 0 && abs(frameToX(it) - x) < dp(18f) }
                if (near >= 0) {
                    edCues[near] = -1
                    edToast("Cue ${near + 1} entfernt")
                } else {
                    val slot = edCues.indexOfFirst { it < 0 }
                    if (slot < 0) edToast("Alle 4 Cue-Punkte belegt – einen antippen zum Entfernen")
                    else {
                        var f = xToFrame(x)
                        f = snapF(f)
                        edCues[slot] = f
                        edToast("Cue ${slot + 1} gesetzt")
                    }
                }
    }

    private fun moveMarker(x: Float) {
        var f = xToFrame(x)
        f = snapF(f)
        val minLen = Engine.sampleRate / 50
        if (edDrag == 1) edStart = f.coerceIn(0, max(0, edEnd - minLen))
        else if (edDrag == 2) edEnd = f.coerceIn(min(edData.size, edStart + minLen), edData.size)
    }

    private fun reloadEditor(msg: String) {
        edStopPreview()
        edHead = 0
        val d = Engine.loopData(edPad)
        if (d != null) {
            edData = d
            edStart = 0
            edEnd = d.size
            edV0 = 0
            edV1 = d.size
            edNorm = 0f
            java.util.Arrays.fill(edCues, -1)
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
        smallBtn(canvas, edZoomOut, "⤢ GANZ " + edZoomLabel(), edV1 - edV0 < edData.size, cols)
        smallBtn(canvas, edZoomIn, "⌖ S…E", false, cols)
        smallBtn(canvas, edToS, "⌖ S", false, cols)
        smallBtn(canvas, edToE, "⌖ E", false, cols)
        smallBtn(canvas, edSnapBtn, when (edSnapMode) { 2 -> "♩ BEAT"; 1 -> "0-SNAP"; else -> "FREI" }, edSnap, cols)
        smallBtn(canvas, edCueBtn, "📍 CUE", edCueMode, cols)
        canvas.save()
        canvas.clipRect(w.left, w.top, w.right, w.bottom + dp(24f))
        val n = edPeaks.size
        val span = max(1, edV1 - edV0)
        val fine = span < w.width() * 3
        strokePaint.strokeWidth = dp(1.6f)
        if (fine) {
            // stark vergrößert: echte Wellenform als Linie
            val path = Path()
            val d = edData
            val steps = w.width().toInt()
            for (px in 0..steps) {
                val f = (edV0 + px.toLong() * span / steps).toInt().coerceIn(0, d.size - 1)
                val yy = mid - (d[f] / edNorm).coerceIn(-1f, 1f) * w.height() * 0.46f
                if (px == 0) path.moveTo(w.left + px, yy) else path.lineTo(w.left + px, yy)
            }
            strokePaint.color = acc
            canvas.drawPath(path, strokePaint)
            strokePaint.strokeWidth = dp(0.6f)
            strokePaint.color = Color.argb(70, 255, 255, 255)
            canvas.drawLine(w.left, mid, w.right, mid, strokePaint)
        } else {
            for (c in 0 until n) {
                val x = w.left + w.width() * c / n
                val hgt = edPeaks[c] * w.height() * 0.46f
                strokePaint.color = if (x in sx..ex) acc else Color.argb(90, 200, 200, 210)
                canvas.drawLine(x, mid - hgt, x, mid + hgt, strokePaint)
            }
        }
        // Bildlauf-Anzeige
        if (span < edData.size) {
            val a0 = edV0.toFloat() / edData.size
            val a1 = edV1.toFloat() / edData.size
            fillPaint.color = Color.argb(60, 255, 255, 255)
            canvas.drawRect(w.left, w.top, w.right, w.top + dp(3f), fillPaint)
            fillPaint.color = acc
            canvas.drawRect(w.left + w.width() * a0, w.top, w.left + w.width() * a1, w.top + dp(3f), fillPaint)
            // abgedunkelt außerhalb der Auswahl
            fillPaint.color = Color.argb(90, 0, 0, 0)
            if (sx > w.left) canvas.drawRect(w.left, w.top + dp(3f), min(sx, w.right), w.bottom, fillPaint)
            if (ex < w.right) canvas.drawRect(max(ex, w.left), w.top + dp(3f), w.right, w.bottom, fillPaint)
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
        // Cue-Punkte (Hot Cues 1–4)
        val cueCols = intArrayOf(Color.rgb(255, 60, 90), Color.rgb(60, 220, 120), Color.rgb(70, 150, 255), Color.rgb(200, 90, 255))
        for (k in 0..3) {
            val cf = edCues[k]
            if (cf < 0) continue
            val x = frameToX(cf)
            if (x < w.left || x > w.right) continue
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = cueCols[k]
            canvas.drawLine(x, w.top, x, w.bottom, strokePaint)
            fillPaint.color = cueCols[k]
            canvas.drawCircle(x, w.top + dp(9f), dp(8f), fillPaint)
            textPaint.textSize = dp(10f)
            textPaint.color = Color.WHITE
            canvas.drawText("${k + 1}", x, w.top + dp(12.5f), textPaint)
        }
        // Marker
        for ((x, lab) in arrayOf(sx to "S", ex to "E")) {
            if (x < w.left - dp(1f) || x > w.right + dp(1f)) continue
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
        canvas.restore()
        // Zeitleiste mit Abspielkopf
        edUpdateHead()
        val playingNow = edPreview >= 0
        val ru = edRuler
        fillPaint.color = Color.rgb(28, 30, 37)
        canvas.drawRoundRect(ru, dp(5f), dp(5f), fillPaint)
        run {
            val sr = Engine.sampleRate.toFloat()
            val spanS = (edV1 - edV0) / sr
            val step = listOf(0.001f, 0.005f, 0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f).firstOrNull { spanS / it <= 10f } ?: 10f
            var t = kotlin.math.ceil(edV0 / sr / step) * step
            textPaint.textSize = dp(8f)
            textPaint.textAlign = Paint.Align.LEFT
            while (t * sr <= edV1) {
                val x = frameToX((t * sr).toInt())
                strokePaint.strokeWidth = dp(1f)
                strokePaint.color = Color.argb(90, 255, 255, 255)
                canvas.drawLine(x, ru.bottom - dp(6f), x, ru.bottom, strokePaint)
                textPaint.color = Color.argb(140, 255, 255, 255)
                canvas.drawText(if (step < 0.1f) String.format(Locale.GERMANY, "%.3f", t) else String.format(Locale.GERMANY, "%.2f", t), x + dp(2f), ru.top + dp(9f), textPaint)
                t += step
            }
            textPaint.textAlign = Paint.Align.CENTER
        }
        // Taktraster: Schläge dünn, Taktanfänge hell (Pad-Daten beginnen auf der Eins)
        if (edBeatsOn()) {
            val bl = Engine.beatLen()
            val k0 = kotlin.math.ceil(edV0 / bl).toLong()
            val k1 = kotlin.math.floor(edV1 / bl).toLong()
            if (k1 - k0 in 0..400) {
                for (k in k0..k1) {
                    val bx = frameToX((k * bl).toInt())
                    val bar = k % 4 == 0L
                    strokePaint.strokeWidth = dp(if (bar) 1.4f else 0.8f)
                    strokePaint.color = Color.argb(if (bar) 120 else 45, 255, 255, 255)
                    canvas.drawLine(bx, w.top, bx, w.bottom, strokePaint)
                    if (bar && k1 - k0 < 120) {
                        textPaint.textSize = dp(8f)
                        textPaint.color = Color.argb(150, 255, 255, 255)
                        textPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText("${k / 4 + 1}", bx + dp(2f), w.top + dp(9f), textPaint)
                        textPaint.textAlign = Paint.Align.CENTER
                    }
                }
            }
        }
        // Live-Marker: wo das Pad gerade im Mix abgespielt wird (Echtzeit)
        val lp = if (edData.size == Engine.padDataLen(edPad)) Engine.padPlayPos(edPad) else -1.0
        if (lp >= 0.0) {
            val lc = Color.rgb(255, 176, 40)
            val lx = frameToX(lp.toInt())
            if (lx >= w.left && lx <= w.right) {
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = lc
                canvas.drawLine(lx, w.top, lx, w.bottom, strokePaint)
                fillPaint.color = lc
                canvas.drawCircle(lx, w.bottom - dp(4f), dp(4f), fillPaint)
                textPaint.textSize = dp(8f)
                textPaint.color = lc
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("LIVE", lx + dp(3f), w.bottom - dp(10f), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
            } else {
                // außerhalb des Ausschnitts: Pfeil am Rand
                textPaint.textSize = dp(12f)
                textPaint.color = lc
                canvas.drawText(if (lx < w.left) "◀ LIVE" else "LIVE ▶", if (lx < w.left) w.left + dp(24f) else w.right - dp(24f), w.bottom - dp(6f), textPaint)
            }
        }
        val hx = frameToX(edHead)
        if (hx >= w.left - dp(1f) && hx <= w.right + dp(1f)) {
            val hc = if (playingNow) Color.rgb(80, 230, 120) else Color.WHITE
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = hc
            canvas.drawLine(hx, ru.top, hx, w.bottom, strokePaint)
            val tri = Path()
            tri.moveTo(hx - dp(8f), ru.top)
            tri.lineTo(hx + dp(8f), ru.top)
            tri.lineTo(hx, ru.top + dp(12f))
            tri.close()
            fillPaint.color = hc
            canvas.drawPath(tri, fillPaint)
        }
        textPaint.textSize = dp(9f)
        textPaint.color = Color.argb(170, 255, 255, 255)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.GERMANY, "▶ %.3f s", edHead / Engine.sampleRate.toFloat()), ru.right - dp(4f), ru.bottom - dp(3f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        val secs = (edEnd - edStart).toFloat() / Engine.sampleRate
        textPaint.textSize = dp(11f)
        textPaint.color = alpha(cols[1], 0.8f)
        canvas.drawText(String.format(Locale.GERMANY, "Auswahl %.3fs · Marker ziehen · Daumen + Zeigefinger = Zoom", secs), edRect.centerX(), w.bottom + dp(42f), textPaint)

        // Regler
        editorKnob(canvas, edKnobSlices, (edSlices - 1) / 31f, "SLICES", if (edSlices <= 1) "AUS" else "$edSlices", cols)
        val bl = when (edBars) { 0.25f -> "¼"; 0.5f -> "½"; else -> edBars.toInt().toString() }
        editorKnob(canvas, edKnobBars, barOptions.indexOfFirst { it == edBars }.coerceAtLeast(0) / 5f, "TAKTE", bl, cols)
        val pv = if (edPitch == 0) "0" else (if (edPitch > 0) "+" else "") + edPitch
        editorKnob(canvas, edKnobPitch, (edPitch + 12) / 24f, "PITCH (HT)", pv, cols)
        smallBtn(canvas, edBtnKeylock, if (edKeylock) "🔒 KEY" else "KEY AUS", edKeylock, cols)

        val playing = edPreview >= 0
        val playSel = playing && !edLooping && edPlayFrom == edStart
        val playHead = playing && !edLooping && edPlayFrom != edStart
        smallBtn(canvas, edBtnPlay, if (playSel) "■ STOP" else "▶ S…E", playSel, cols)
        smallBtn(canvas, edBtnLoop, if (edLooping) "■ LOOP" else "🔁 LOOP", edLooping, cols)
        smallBtn(canvas, edBtnFromHead, if (playHead) "■ STOP" else "▶ KOPF", playHead, cols)
        smallBtn(canvas, edBtnCut, "✂ Zuschneiden", false, cols)
        smallBtn(canvas, edBtnSlice, "🔪 Slicen (Tempo)", false, cols)
        smallBtn(canvas, edBtnPads, "⇢ Slices auf Pads", false, cols)
        smallBtn(canvas, edBtnSave, if (edSlices > 1) "💾 In Bibliothek · Stems ($edSlices)" else "💾 In Bibliothek speichern", false, cols)

        textPaint.textSize = dp(10f)
        textPaint.color = alpha(cols[1], 0.65f)
        val ty = edBtnSave.bottom + dp(22f)
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


    // ================================================================ Info-Modus

    /** Im Info-Modus erklärt Antippen ein Element, statt es auszulösen. */
    var infoMode = false
        set(v) {
            field = v
            infoTitle = null
            invalidate()
        }
    private var infoTitle: String? = null
    private var infoText = ""
    private val infoHi = RectF()
    private val infoBox = RectF()
    private val infoTextPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun showInfo(r: RectF?, title: String, text: String) {
        infoTitle = title
        infoText = text
        if (r != null) infoHi.set(r) else infoHi.setEmpty()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** true = Ereignis verbraucht (Erklärung gezeigt). */
    private fun infoTouch(x: Float, y: Float): Boolean {
        // Menü und Schließen-Knöpfe funktionieren normal
        if (designBtn.contains(x, y)) return false
        if (kbPad >= 0 && kbClose.contains(x, y)) return false
        if (edPad >= 0 && edBtnClose.contains(x, y)) return false
        if (infoTitle != null && infoBox.contains(x, y)) {
            infoTitle = null
            return true
        }
        val hit = infoAt(x, y)
        if (hit == null) {
            infoTitle = null
            return true
        }
        showInfo(hit.first, hit.second, hit.third)
        return true
    }

    private fun instMaskShort(m: Int): String = listOf("303", "909", "SYN", "EUK", "ATM", "HAR").filterIndexed { k, _ -> (m shr k) and 1 != 0 }.joinToString("+").ifEmpty { "INST" }

    private fun srcName(i: Int) = when (Engine.padSource[i]) {
        3 -> "🎹 Instrumente " + instMaskShort(Engine.padInstMask[i])
        2 -> "🎛 Pads " + Engine.maskPads(Engine.padSrcMask[i]).joinToString(", ") { "${it + 1}" } + " (nach ihren eigenen Effekten)"
        1 -> "〰 Wave (App-Audio inkl. Effekte)"
        else -> "🎙 Mikrofon"
    }

    private fun infoAt(x: Float, y: Float): Triple<RectF?, String, String>? {
        // ---- Wave-Editor
        if (edPad >= 0) {
            return when {
                edZoomIn.contains(x, y) || edZoomOut.contains(x, y) -> Triple(RectF(edZoomOut.left, edZoomOut.top, edZoomIn.right, edZoomIn.bottom), "Zoom", "Mit Daumen und Zeigefinger in der Wellenform: auseinander = hineinzoomen (bis auf einzelne Samples), zusammen = heraus, gemeinsam ziehen = verschieben. ⤢ GANZ zeigt alles, ⌖ S…E die Auswahl. Vergrößert: mit einem Finger abseits der Marker verschieben.")
                edToS.contains(x, y) || edToE.contains(x, y) -> Triple(RectF(edToS.left, edToS.top, edToE.right, edToE.bottom), "Zum Marker", "Springt mit der Lupe direkt an den Start- (S) bzw. Endpunkt (E) – zum genauen Schneiden.")
                edCueBtn.contains(x, y) -> Triple(edCueBtn, "Cue-Punkte", "Cue-Modus: in die Wellenform tippen setzt Hot Cues 1–4, einen antippen entfernt ihn. Beim Speichern in die DJ-Bibliothek werden sie mitgespeichert – der Startpunkt (Cue) ist der Marker S.")
                edSnapBtn.contains(x, y) -> Triple(edSnapBtn, "SNAP", "♩ BEAT: Marker rasten auf die Schläge des Taktrasters (dünne Linien, Taktanfänge hell) – liegt kein Schlag in der Nähe, auf den nächsten Nulldurchgang. 0-SNAP: nur Nulldurchgänge (kein Knacksen). FREI: ohne Raster. Antippen schaltet um.")
                edWave.contains(x, y) || (y > edWave.bottom && y < edWave.bottom + dp(30f)) -> Triple(edWave, "Wellenform", "Zeigt das Audio des Pads. Die Marker S (Start) und E (Ende) an der Wellenform ziehen, um den Ausschnitt zu wählen. Weiße Linien zeigen die Slices.")
                edKnobSlices.contains(x, y) -> Triple(edKnobSlices, "SLICES", "Hoch/runter ziehen: teilt den Ausschnitt in 2–32 gleich lange Stücke. AUS = nicht slicen.")
                edKnobPitch.contains(x, y) -> Triple(edKnobPitch, "PITCH", "Hoch/runter ziehen: ±12 Halbtöne schneller/langsamer – wie der Pitch am Plattenspieler. ▶ und 🔁 hören sofort mit Pitch vor, ✂ Zuschneiden bzw. Slicen wendet ihn an. Doppeltipp = 0. Bei Loops im Raster bleibt das Tempo (Master) – dort wirkt PITCH ohne Keylock als Transponieren.")
                edBtnKeylock.contains(x, y) -> Triple(edBtnKeylock, "KEYLOCK", "🔒 an: Pitch ändert nur das Tempo, die Tonhöhe bleibt gleich (Time-Stretch). Aus: Tempo und Tonhöhe ändern sich zusammen.")
                edKnobBars.contains(x, y) -> Triple(edKnobBars, "TAKTE", "Wie viele Takte der gesliceten Loop lang ist (¼ bis 8). Die Slices verteilen sich darauf und folgen jedem Tempo.")
                edBtnPlay.contains(x, y) -> Triple(edBtnPlay, "Hören S…E", "Spielt den Ausschnitt von S bis E – der grüne Kopf zeigt, wo du gerade bist. Nochmal = Stopp.")
                edBtnLoop.contains(x, y) -> Triple(edBtnLoop, "🔁 Loop S…E", "Spielt den Ausschnitt von S bis E endlos – genau so, wie er später als Loop auf dem Pad läuft. So hörst du, ob der Übergang nahtlos ist. Marker verschieben: beim Loslassen läuft sofort der neue Loop. 0-SNAP hilft gegen Knacksen. Nochmal = Stopp.")
                edBtnFromHead.contains(x, y) -> Triple(edBtnFromHead, "Ab Kopf", "Spielt sofort ab der Stelle des Abspielkopfs. Den Kopf auf der Zeitleiste (oder an der Linie) mit dem Finger verschieben; Doppeltipp auf die Leiste spielt direkt ab dort.")
                edRuler.contains(x, y) -> Triple(edRuler, "Zeitleiste & Abspielkopf", "Antippen/Ziehen setzt den Abspielkopf. Läuft die Wiedergabe, geht sie nach dem Loslassen an der neuen Stelle weiter. Doppeltipp = ab hier abspielen.")
                edBtnCut.contains(x, y) -> Triple(edBtnCut, "Zuschneiden", "Behält nur den Bereich zwischen S und E und legt ihn wieder synchron aufs Pad.")
                edBtnSlice.contains(x, y) -> Triple(edBtnSlice, "Slicen (Tempo)", "Zerlegt den Loop in Slices, die auf ihren Rasterplätzen starten – so passt er sich jedem Tempo an, ohne die Tonhöhe zu ändern. Die Slices erscheinen automatisch als Noten in der Piano-Roll: Slice 1 auf C4, jeder weitere einen Halbton höher. Noten verschieben, kopieren oder löschen = Beat neu arrangieren.")
                edBtnSave.contains(x, y) -> Triple(edBtnSave, "In Bibliothek speichern", "Speichert den Ausschnitt S–E als eigenes Sample in die Bibliothek – du wählst die Kategorie. Ist SLICES eingestellt, kannst du die Stücke auch als Stems speichern oder als WAV-Dateien in einen Ordner exportieren.")
                edBtnPads.contains(x, y) -> Triple(edBtnPads, "Slices auf Pads", "Legt die Stücke als ⚡-One-Shots auf freie Pads – zum neu Anordnen im Sequenzer oder Live-Spielen.")
                else -> Triple(edRect, "Wave-Editor", "Hier schneidest du Aufnahmen und zerlegst sie in Slices. ✕ oben rechts schließt den Editor.")
            }
        }
        // ---- Tastatur / Matrix / Song
        if (kbPad >= 0 && kbRect.contains(x, y)) {
            layoutKeyboard()
            if (songView) {
                when {
                    mxBars.contains(x, y) -> return Triple(mxBars, "Song-Länge", "Wie viele Takte der Song hat: 4, 8, 16, 32 oder 64. ◀ ▶ oben blättern durch die Takte.")
                    mxRaster.contains(x, y) -> return Triple(mxRaster, "＋ LIVE", "Übernimmt, welche Pads gerade laufen (und ob ACID/DRUM an sind), in den aktuellen Takt.")
                    mxQuant.contains(x, y) -> return Triple(mxQuant, "Aus Szenen", "Baut den Song aus deinen Szenen: jede Szene so viele Takte wie bei ihr eingestellt, nacheinander.")
                    mxClear.contains(x, y) -> return Triple(mxClear, "Leeren", "Löscht das ganze Arrangement.")
                    kbTakeBtn.contains(x, y) -> return Triple(kbTakeBtn, "Song-Modus", "Ist er an, spielt ▶ den Song ab Takt 1: In jedem Takt laufen genau die Spuren, die in der Matrix markiert sind (Loops, ⚡-Pads mit ihren Schritten und Noten, ACID und DRUM).")
                    kbPiano.contains(x, y) -> return Triple(kbPiano, "Spuren", "Die 12 Pads, ACID und DRUM. Antippen schaltet die Spur live stumm/an.")
                    mxGrid.contains(x, y) -> return Triple(mxGrid, "Song-Matrix", "Spuren senkrecht, Takte waagrecht – wie die Arrangement-Ansicht in Ableton. Feld antippen schaltet die Spur in diesem Takt an/aus, über mehrere Felder ziehen malt.")
                }
            } else {
                when {
                    mxBars.contains(x, y) -> return Triple(mxBars, "Takte", "Länge der Matrix für dieses Pad: 1, 2, 4 oder 8 Takte. Die Noten laufen in dieser Länge als Schleife.")
                    mxRaster.contains(x, y) -> return Triple(mxRaster, "Raster", "Raster für die Aufnahme: frei (genau wie gespielt), 1/4, 1/8, 1/16 oder 1/32. Wird schon beim Einspielen angewendet.")
                    mxQuant.contains(x, y) -> return Triple(mxQuant, "Quantisieren", "Setzt die markierten Noten (oder, wenn nichts markiert ist, alle) auf das gewählte Raster.")
                    mxClear.contains(x, y) -> return Triple(mxClear, "Leeren", "Löscht alle Noten dieses Pads.")
                    mxMode.contains(x, y) -> return Triple(mxMode, "Werkzeug", "✏ STIFT: leeres Feld setzt eine Note (nach rechts ziehen = länger), Note antippen löscht, Note ziehen verschiebt, rechten Rand ziehen ändert die Länge. ⬚ AUSWAHL: Noten antippen markiert, Rahmen ziehen markiert mehrere, markierte Noten ziehen verschiebt sie, ins Leere tippen setzt die Einfügemarke.")
                    mxZoomIn.contains(x, y) || mxZoomOut.contains(x, y) -> return Triple(RectF(mxZoomOut.left, mxZoomOut.top, mxZoomIn.right, mxZoomIn.bottom), "Zoom", "Hinein- und herauszoomen bis 32×. Mit zwei Fingern in der Matrix: auseinander/zusammen = Zoom (waagrecht Zeit, senkrecht Tonhöhe), gemeinsam ziehen = verschieben.")
                    mxAll.contains(x, y) -> return Triple(mxAll, "Alle", "Markiert alle Noten (nochmal = Auswahl aufheben).")
                    mxCopy.contains(x, y) -> return Triple(mxCopy, "Kopieren", "Kopiert die markierten Noten in die Zwischenablage. Die Einfügemarke springt direkt dahinter.")
                    mxPaste.contains(x, y) -> return Triple(mxPaste, "Einfügen", "Setzt die kopierten Noten an der gelben Einfügemarke ein (in ⬚ AUSWAHL ins Leere tippen setzt sie). Mehrmals drücken reiht sie aneinander.")
                    mxDel.contains(x, y) -> return Triple(mxDel, "Löschen", "Löscht die markierten Noten.")
                    mxGroup.contains(x, y) -> return Triple(mxGroup, "Gruppieren", "Fasst die markierten Noten zu einer Gruppe zusammen (farbiger Balken) – sie werden dann immer gemeinsam markiert, verschoben und kopiert. Nochmal drücken löst die Gruppe auf.")
                    kbPiano.contains(x, y) -> return Triple(kbPiano, "Klaviatur", "Wie in Ableton links an der Seite: Tasten spielen das Pad-Sample in dieser Tonhöhe. Mehrere Finger = Akkord, auf- und abwischen wechselt den Ton. Bei REC landen die Töne in der Matrix.")
                    mxGrid.contains(x, y) -> return Triple(mxGrid, "Matrix (Piano-Roll)", "Tonhöhe senkrecht (passend zur Klaviatur links), Zeit waagrecht. Leeres Feld antippen setzt eine Note, eine Note antippen löscht sie. Die Linie ist die Abspielposition.")
                }
            }
            return when {
                kbMatrixBtn.contains(x, y) -> Triple(kbMatrixBtn, "NOTEN / SONG", "Wechselt zwischen der Piano-Roll dieses Pads und der Song-Matrix, in der du ganze Songs aus deinen Spuren baust.")
                kbOctDown.contains(x, y) || kbOctUp.contains(x, y) -> Triple(RectF(kbOctDown.left, kbOctDown.top, kbOctUp.right, kbOctUp.bottom), if (songView) "◀ ▶ Seite" else "Oktave − / +", if (songView) "Blättert die Song-Matrix um 16 Takte." else "Verschiebt Klaviatur und Matrix um eine Oktave.")
                kbTakeBtn.contains(x, y) -> Triple(kbTakeBtn, "📌 Ton fürs Pad", "Ist das an, wird der zuletzt gespielte Ton zur festen Tonhöhe des Pads – auch beim Antippen und im Sequenzer.")
                kbRec.contains(x, y) -> Triple(kbRec, "● REC", "Pattern-Aufnahme: deine Töne landen in der Matrix. Nochmal drücken beendet.")
                kbPlay.contains(x, y) -> Triple(kbPlay, "▶ Play", "Startet alles auf der Eins (im Song-Modus ab Takt 1 des Songs).")
                kbStop.contains(x, y) -> Triple(kbStop, "■ Stop", "Hält alles an und beendet eine laufende Aufnahme.")
                else -> Triple(kbRect, "🎹 Piano-Roll", "Klaviatur links, Matrix rechts – wie in Ableton. ✕ schließt.")
            }
        }
        if (selRow.contains(x, y)) return Triple(selRow, "Pad-Auswahl", "SELECT an: ein Pad antippen wählt es aus, ohne es zu spielen (sonst wählt jedes gespielte Pad sich selbst aus). 〰 WAVE, 🎹 NOTEN, ✨ FX und ⋯ gelten für das ausgewählte Pad. In der großen Pad-Ansicht (⤢) erscheint darüber seine Wellenform mit Vor/Zurück, Abspielen ab Cursor und vielen Einstellungen.")
        if (zoomPads.contains(x, y) || zoomXy.contains(x, y) || zoomFx.contains(x, y)) return Triple(if (zoomPads.contains(x, y)) zoomPads else if (zoomXy.contains(x, y)) zoomXy else zoomFx, "⤢ Groß", "Öffnet diese Sektion bildschirmfüllend (Pads, Touchpad bzw. Effekte) – besser lesbar und bedienbar. Alternativ: Doppeltipp auf den Rahmen. ✕ oben rechts = zurück. Abschaltbar in Einstellungen → Aussehen.")
        if (mixBtn.contains(x, y)) return Triple(mixBtn, "MIX – Mischpult", "Schaltet die Pads auf eine Mischpult-Ansicht um: pro Pad ein Fader (Finger hoch/runter = Lautstärke, doppelt tippen = 0 dB), M = stumm, S = solo, Nummer antippen = Pad auswählen. Nochmal MIX = zurück zu den Pads, das gewählte Pad ist dann aktiv.")
        if (mixView && mixRect.contains(x, y)) return Triple(mixRect, "Mischpult", "Fader: Finger hoch/runter ändert die Lautstärke des Pads (bis +3,5 dB), doppelt tippen setzt 0 dB. M = stumm, S = solo. Pad-Nummer antippen = auswählen, doppelt antippen = direkt in die Piano-Roll des Pads. Unten der Crossfader: A bzw. B antippen, dann die Pad-Nummer – Schieben blendet zwischen beiden Pads über (doppelt tippen = Mitte), rechts die Kurve SANFT / LINEAR / SCHNITT.")
        // ---- Kopfzeile
        if (logoDst.contains(x, y)) return Triple(logoDst, "Marv PAD", "Live-Looper mit 12 Pads, XY-Effektfläche, Effekt-Kette, Sequenzer und Tastatur. Antippen: Info-Seite (Version, alle Änderungen) oder Programm schließen. © marween")
        if (displayRect.contains(x, y)) {
            return if (x > displayRect.left + displayRect.width() * 0.62f)
                Triple(displayRect, "Tempo", "Zeigt das Master-Tempo. Rechts im Display tippen: Tempo einstellen (Regler, ±, Tap-Tempo), Metronom an/aus oder Automatisch (erster Loop bestimmt das Tempo).")
            else
                Triple(displayRect, "Display", "Zeigt den gewählten Kettenplatz und Effekt, ob er aktiv ist (●), auf welche Pads er wirkt (→ALLE, →P3 …), die X/Y-Werte und den Takt. Links antippen öffnet die Effektliste.")
        }
        // ---- Regler
        for (k in 0 until 5) {
            if (!knobRects[k].contains(x, y)) continue
            val t = when (k) {
                0 -> "MIX" to "Wie stark der Effekt des gewählten Kettenplatzes beigemischt wird."
                1 -> Fx.P1_LABELS[Engine.fxType] to "Erster Effekt-Parameter des gewählten Effekts (${Fx.NAMES[Engine.fxType]}). Nach oben/unten ziehen."
                2 -> Fx.P2_LABELS[Engine.fxType] to "Zweiter Effekt-Parameter des gewählten Effekts (${Fx.NAMES[Engine.fxType]}). Nach oben/unten ziehen."
                3 -> "VOL" to "Gesamtlautstärke der App."
                else -> "SWING" to "Shuffle: verschiebt jede zweite 1/16 im Sequenzer und in der Matrix nach hinten. 50 % = gerade, 75 % = stark geshuffelt."
            }
            return Triple(knobRects[k], t.first, t.second + " Werte ändern: Regler nach oben/unten ziehen.")
        }
        val funcInfo = arrayOf(
            "SZENE" to "Wie bei Maschine: die Pads werden zu 12 Szenen. Eine Szene merkt sich Sequenzer-Schritte, Matrix-Noten, ACID/DRUM-Pattern und welche Pads stumm sind. Antippen startet sie auf der nächsten Eins (leere Szene = Kopie der aktuellen). Szene lange drücken: Länge in Takten und Kette. SZENE lange drücken: Kette (Song-Modus) an/aus – die Szenen laufen dann nacheinander. Nochmal SZENE = zurück zu den Pads.",
            "MUTE" to "Danach Pads antippen schaltet sie stumm/an: Loops laufen leise im Takt weiter, ⚡-Pads werden vom Sequenzer nicht mehr gespielt. Halten + Pad = nur kurz aktiv. MUTE lange drücken = alle Loops stumm/an.",
            "SOLO" to "Danach Pads antippen: nur Solo-Pads sind zu hören (auch ACID/DRUM verstummen). SOLO lange drücken schaltet alle Solos aus.",
            "ERASE" to "Pad antippen löscht zuerst sein Pattern (Schritte und Matrix-Noten), nochmal antippen das Sample. Auf die Sequenzer-Schritte tippen leert das ganze Pattern. Mit SZENE: Szene löschen. Alles lässt sich mit ↶ UNDO zurückholen.",
            "DUPLICATE" to "Erst das Quell-Pad, dann das Ziel-Pad antippen: kopiert Sample, Modus, Tonhöhe, Schritte und Noten. Mit SZENE: Szene auf eine andere kopieren.",
            "NOTE REPEAT" to "Ist REPEAT an, wiederholt ein gehaltenes ⚡-Pad oder Drum-Pad (DRUM 909) im Raster – wie die Note-Repeat-Taste bei Maschine. Lange drücken wechselt die Rate: 1/4, 1/8, 1/16, 1/32, 1/8T, 1/16T. Bei laufender Pattern-Aufnahme landen die Schläge im Pattern."
        )
        for (b in 0 until 6) if (funcRects[b].contains(x, y)) return Triple(funcRects[b], funcInfo[b].first, funcInfo[b].second)
        if (fxBtn.contains(x, y)) return Triple(fxBtn, "Effekt-Knopf", "Links tippen = vorheriger, rechts = nächster Effekt für den gewählten Kettenplatz. Lange drücken öffnet die Liste aller 37 Effekte.")
        // ---- Umschalter & Instrumente
        for (t in 0 until 2) if (modeRects[t].contains(x, y)) {
            if (t == 0) return Triple(modeRects[0], "🎛 EFFEKTE", "Die Fläche darunter ist die XY-Effektfläche mit Effekt-Kette, HOLD und Bewegungs-Aufnahme.")
            return Triple(modeRects[1], "🎹 INSTRUMENTE", "Drei Instrumente – antippen öffnet den großen Editor: 303 (Acid-Bass mit Accent und Slide), 909 (Drumcomputer mit 11 Klängen, Presets und Finger-Pads) und SYNTH (polyfoner Synth mit Akkorden, 5 Klangarten). Alle laufen synchron mit dem Sequenzer (▶), lassen sich mit FX durch die Effekt-Kette schicken und können als Eingang eines Pads aufgenommen werden (Pad-Menü → Eingang → Instrumente).")
        }
        if (xyMode == 3 && xyRect.contains(x, y)) {
            layoutSynth()
            val hdrTxt = arrayOf("AN/AUS: ob der Synth im Sequenzer mitspielt.", "Klang: Sägezahn, Rechteck, Supersaw, Pluck oder FM-Glocke.", "Akkord: Einzelton oder Dur/Moll/Sus4/Dur7/Moll7/Oktave – jede Note wird zum Akkord.", "FX: Synth durch die Effekt-Kette schicken.", "Oktave tiefer.", "Oktave höher.", "Zufällige Melodie (Moll-Pentatonik).", "Großer Editor: Piano-Roll mit Matrix bis 4 Takte, Kopieren, Quantisieren, Lautstärke je Note, Presets, MIDI laden/exportieren, alle Klang-Einstellungen.")
            for (i in 0 until 8) if (instHdr[i].contains(x, y)) return Triple(instHdr[i], "SYNTH", hdrTxt[i])
            if (laneRect.contains(x, y)) return Triple(laneRect, "Schritte", "Schritt antippen = an/aus, hoch/runter ziehen = Tonhöhe.")
            if (accRow.contains(x, y)) return Triple(accRow, "Halten (H)", "Verlängert die Note des vorigen Schritts – so entstehen lange Töne und Flächen.")
            return Triple(playRect, "Spielfläche", "Live spielen: X = Ton (2 Oktaven), Y = Filter. Bei laufender Pattern-Aufnahme werden die Töne in die Schritte geschrieben. Die Regler oben: CUTOFF, RESO, ATTACK, RELEASE, LEVEL.")
        }
        if (xyMode == 1 && xyRect.contains(x, y)) {
            layoutAcid()
            val hdrTxt = arrayOf("AN/AUS: ob der Bass im Sequenzer mitspielt.", "Wellenform: Sägezahn (SAW) oder Rechteck (SQR).", "FX: Bass durch die Effekt-Kette schicken.", "Oktave tiefer.", "Oktave höher.", "Zufällige Acid-Linie erzeugen.", "Alle Schritte löschen.")
            for (i in 0 until 7) if (instHdr[i].contains(x, y)) return Triple(instHdr[i], "ACID", hdrTxt[i])
            if (laneRect.contains(x, y)) return Triple(laneRect, "Schritte", "Schritt antippen = an/aus, hoch/runter ziehen = Tonhöhe. Die Balkenhöhe zeigt den Ton.")
            if (accRow.contains(x, y)) return Triple(accRow, "Accent (A)", "Betont den Schritt: lauter und mit stärkerem Filter-Hub.")
            if (sliRow.contains(x, y)) return Triple(sliRow, "Slide (S)", "Gleitet vom Schritt in den nächsten (typischer 303-Slide, ohne neuen Anschlag).")
            return Triple(playRect, "Spielfläche", "Live spielen: X = Ton (2 Oktaven), Y = Filter auf/zu. Finger gleiten lassen ergibt Slides. Bei laufender Pattern-Aufnahme werden die Töne in die Schritte geschrieben.")
        }
        if (xyMode == 2 && xyRect.contains(x, y)) {
            layoutDrum()
            val hdrTxt = arrayOf("AN/AUS: ob die Drums im Sequenzer mitspielen.", "FX: Drums durch die Effekt-Kette schicken.", "Preset laden: House, Techno, Breakbeat, Electro.", "Sequenzer starten/stoppen.", "Alle Drum-Schritte löschen.")
            for (i in 0 until 5) if (instHdr[i].contains(x, y)) return Triple(instHdr[i], "DRUM", hdrTxt[i])
            for (v in 0 until InstState.DV) if (voiceRects[v].contains(x, y)) return Triple(voiceRects[v], InstState.drumLong[v], "Wählt das Instrument zum Bearbeiten (Schritte und Regler TUNE/DECAY/LEVEL).")
            if (dStepRow.contains(x, y)) return Triple(dStepRow, "Schritte", "16 Schritte des gewählten Instruments. Antippen: aus → an → Accent → aus.")
            return Triple(xyRect, "Drum-Pads", "Live trommeln. ACCENT schaltet betonte Schläge. Bei laufender Pattern-Aufnahme werden die Schläge gerastert eingetragen.")
        }
        // ---- XY-Fläche
        for (sl in 0 until Engine.SLOTS) if (slotRects[sl].contains(x, y)) {
            return Triple(slotRects[sl], "Effekt-Kette Platz ${sl + 1}", "Bis zu 3 Effekte laufen nacheinander (1 › 2 › 3). Antippen wählt den Platz – Effekt-Knopf und Regler gelten dann für ihn. Den gewählten Platz nochmal antippen schaltet ihn an/aus.")
        }
        if (holdRect.contains(x, y)) return Triple(holdRect, "🔒 HOLD", "Hält den Effekt an der letzten Position aktiv, auch ohne Finger. Auf der Fläche ziehen verschiebt ihn, nochmal HOLD schaltet aus.")
        if (motRect.contains(x, y)) return Triple(motRect, "● REC Bewegung", "Speichert deine Fingerbewegung: tippen, dann Fläche berühren startet die Aufnahme, nochmal tippen beendet sie. Sie läuft dann als ▶ AUTO im Takt. AUTO antippen löscht sie.")
        if (xyRect.contains(x, y)) return Triple(xyRect, "XY-Effektfläche", "Finger drauf = Effekt an. X (waagrecht) und Y (senkrecht) steuern zwei Parameter aller aktiven Effekte: hier ${Fx.X_LABELS[Engine.fxType]} und ${Fx.Y_LABELS[Engine.fxType]}.")
        // ---- Pads
        if (sceneView) for (i in 0 until VISIBLE_PADS) if (padRects[i].contains(x, y)) {
            return Triple(padRects[i], "Szene ${i + 1}", "Antippen startet die Szene auf der nächsten Eins (läuft nichts: sofort). Eine leere Szene wird als Kopie der aktuellen angelegt. Lange drücken: Länge in Takten, Kette, Löschen.")
        }
        for (i in 0 until VISIBLE_PADS) {
            if (keyRects[i].contains(x, y)) return Triple(keyRects[i], "🎹 Tastatur-Knopf", "Öffnet die Tastatur für Pad ${i + 1}: Sample in Tonhöhen spielen, Akkorde, Aufnahme in die Matrix.")
            if (moreRects[i].contains(x, y)) return Triple(moreRects[i], "••• Pad-Menü", "Eingang (Mikrofon/Wave), Modus (Loop/One Shot), Bibliothek, Import, Wave-Editor, Tastatur, Effekt an/aus, Export und Löschen für Pad ${i + 1}.")
            if (padRects[i].contains(x, y)) {
                val mode = when {
                    Engine.liveBars < 0 -> "TIPPEN: 1. Tipp startet die Aufnahme, 2. Tipp beendet sie."
                    Engine.liveBars > 0 -> "LIVE: antippen – die Aufnahme startet auf dem nächsten Takt und läuft ${Engine.liveBars} Takt(e)."
                    else -> "HALTEN: Aufnahme läuft, solange der Finger liegt."
                }
                fxBadgeRect(i, iconRect)
                if (iconRect.contains(x, y)) return Triple(RectF(iconRect), "FX des Pads", "Eigene Effekt-Kette für Pad ${i + 1} mit bis zu 3 Effekten (die Punkte zeigen die belegten Plätze). Antippen = an/aus, lange drücken = Effekte wählen und einstellen. Unabhängig davon wirkt die XY-Fläche auf alle Pads mit „Effekt (XY)“ im •••-Menü.")
                importRect(i, iconRect)
                if (!Engine.hasLoop(i) && iconRect.contains(x, y)) return Triple(RectF(iconRect), "📂 Laden", "WAV/MP3-Datei, Bibliothek oder eigene Aufnahme laden. Tempo und Länge werden erkannt und der Clip auf das Master-Tempo gewarpt (Tonhöhe bleibt). Danach wartet er – antippen startet ihn auf der Eins.")
                val gestures = " Gesten: ← wischen = löschen, ↕ wischen = Lautstärke, zwei Finger = stumm (lang: Solo)."
                val txt = if (!Engine.hasLoop(i)) {
                    "Leeres Pad (+). $mode Daraus wird automatisch ein synchroner Loop (1–32 Takte). Eingang: ${srcName(i)}. 📂 lädt eine Datei."
                } else if (Engine.isOneShot(i)) {
                    "⚡ One Shot: antippen spielt das Sample einmal. Im Sequenzer setzbar. Effekt: ${if (Engine.padFx[i]) "an" else "aus"}."
                } else {
                    "∞ Loop: Farben – Grün spielt, Rot nimmt auf, Gelb = Overdub, Blinken = wartet auf das Start-Raster, Grau ▶ = bereit. Gestoppt antippen = startet zur nächsten Eins. Läuft er, ${if (Engine.tapOverdub) "schaltet Antippen den Overdub ein (nochmal = aus)" else "stoppt Antippen ihn"}. Halten = Overdub, solange der Finger liegt.$gestures"
                }
                return Triple(padRects[i], "Pad ${i + 1}", txt)
            }
        }
        // ---- Sequenzer
        if (seqRect.contains(x, y)) {
            return when {
                recBtn.contains(x, y) -> Triple(recBtn, "● REC", "Antippen: Pattern-Aufnahme (⚡-Pads, Tastatur, ACID und DRUM landen im Pattern). Steht alles, wird ein Takt eingezählt. Nochmal antippen beendet die Aufnahme, alles läuft weiter. Lange drücken: Song-Aufnahme oder Pattern als Audio speichern.")
                playBtn.contains(x, y) -> Triple(playBtn, "▶ Play", "Startet alles gemeinsam auf der Eins: Loops, Sequenzer, Matrix, ACID und DRUM.")
                stopBtn.contains(x, y) -> Triple(stopBtn, "■ Stop", "Hält alles an (Loops, Sequenzer, Instrumente). ▶ startet wieder auf der Eins. Lange halten leert das Pattern.")
                undoBtn.contains(x, y) -> Triple(undoBtn, "↶ UNDO", "Macht den letzten Schritt rückgängig: Aufnahmen, Overdubs, Löschen, Laden, Schritte, Noten, Pattern-Aufnahmen.")
                redoBtn.contains(x, y) -> Triple(redoBtn, "↷ REDO", "Stellt einen rückgängig gemachten Schritt wieder her.")
                else -> Triple(seqRect, "Sequenzer", "16 Schritte pro Takt für das zuletzt gespielte ⚡-Pad. Schritt antippen = an/aus, hoch/runter ziehen = Lautstärke. Punkte zeigen Schritte anderer Pads und Matrix-Noten.")
            }
        }
        // ---- Fußzeile
        if (srcPill.contains(x, y)) return Triple(srcPill, "Eingang aller Pads", "Stellt den Eingang aller Pads um: 🎙 Mikrofon oder 〰 Wave (nimmt auf, was die App ausgibt – inkl. Effekte). Pro Pad im •••-Menü einstellbar.")
        if (quantPill.contains(x, y)) return Triple(quantPill, "⏱ Start-Raster", "Wann ein Loop startet oder stoppt, wenn du sein Pad antippst (wie Global Quantize in Ableton Live): SOFORT, 1/4, 1/2, 1, 2, 4 oder 8 Takte. Das Pad blinkt grün (startet) bzw. rot (stoppt), bis es so weit ist.")
        if (playPill.contains(x, y)) return Triple(playPill, "Abspiel-Modus", "⇉ ZUSAMMEN: angetippte Loops laufen gemeinsam. ⇄ WECHSELN: es läuft immer nur ein Loop – tippst du ein anderes Pad, hört der bisherige genau dann auf, wenn der neue im Start-Raster einsetzt. ⇄ JE REIHE: so pro Pad-Reihe (1–4, 5–8, 9–12), die Reihen laufen zusammen – ideal für Drums / Bass / Melodie. ⚡-Pads sind nie betroffen.")
        if (modePill.contains(x, y)) return Triple(modePill, "Aufnahme-Modus", "✋ HALTEN (Finger halten), 👆 TIPPEN (Start/Stopp per Tipp) oder ⚡ LIVE mit fester Länge. Loops rasten immer synchron auf 1–32 Takte ein.")
        return null
    }

    private fun drawInfo(canvas: Canvas) {
        val title = infoTitle ?: return
        val cols = overlayColors()
        // Element hervorheben
        if (!infoHi.isEmpty) {
            val pulse = 0.5f + 0.5f * sin(time * 6f)
            strokePaint.strokeWidth = dp(3f)
            strokePaint.color = alpha(cols[2], 0.6f + 0.4f * pulse)
            tmpRect.set(infoHi.left - dp(4f), infoHi.top - dp(4f), infoHi.right + dp(4f), infoHi.bottom + dp(4f))
            canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), strokePaint)
        }
        val m = dp(16f)
        val w = width - 2 * m
        infoTextPaint.textSize = dp(13.5f)
        infoTextPaint.color = Color.WHITE
        val layout = android.text.StaticLayout.Builder.obtain(infoText, 0, infoText.length, infoTextPaint, (w - dp(28f)).toInt()).build()
        val h = layout.height + dp(56f)
        // Box nicht über das erklärte Element legen
        val below = infoHi.isEmpty || infoHi.centerY() < height / 2f
        val top = if (below) min(height - insetBottom - dp(40f) - h, max(infoHi.bottom + dp(14f), height * 0.45f)) else max(insetTop + dp(8f), infoHi.top - dp(14f) - h)
        infoBox.set(m, top, m + w, top + h)
        fillPaint.shader = null
        fillPaint.color = Color.argb(240, 18, 18, 24)
        canvas.drawRoundRect(infoBox, dp(14f), dp(14f), fillPaint)
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = cols[2]
        canvas.drawRoundRect(infoBox, dp(14f), dp(14f), strokePaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(15f)
        textPaint.color = cols[2]
        canvas.drawText("ℹ $title", infoBox.left + dp(14f), infoBox.top + dp(26f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(10f)
        textPaint.color = alpha(Color.WHITE, 0.5f)
        canvas.drawText("antippen = schließen", infoBox.right - dp(14f), infoBox.top + dp(24f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.translate(infoBox.left + dp(14f), infoBox.top + dp(40f))
        layout.draw(canvas)
        canvas.restore()
    }


    // ================================================================ Virtuelle Instrumente: ACID 303 / DRUM 909

    /** 0 = Effekte, 1 = ACID, 2 = DRUM. */
    private var xyMode = 0
    /** Editor für ACID/DRUM (Klaviatur/Instrumente links, Matrix rechts). */
    private val instEd = InstEditor(this).also { ed ->
        ed.onHint = { hint(it) }
        ed.onRec = { recPressed() }
        ed.onMidi = { k -> onInstMidi?.invoke(k) }
        ed.onPresets = { k -> onInstPresets?.invoke(k) }
        ed.onKits = { onInstKits?.invoke(ed.kindNow()) }
        ed.onScale = { onInstScale?.invoke() }
        ed.onChords = { onInstChords?.invoke() }
        ed.onSidechain = { onSidechain?.invoke() }
    }
    // ---------------------------------------------------------------- Tutorial-Tour

    private var tourStep = -1
    var onTourDone: (() -> Unit)? = null
    private val tourCard = RectF()
    private val tourPrev = RectF()
    private val tourNext = RectF()
    private val tourEnd = RectF()

    private class TourStep(val rect: () -> RectF?, val title: String, val text: String)

    private fun union(rs: Array<RectF>): RectF? {
        val u = RectF()
        var any = false
        for (r in rs) if (r.width() > 0f) { if (!any) u.set(r) else u.union(r); any = true }
        return if (any) u else null
    }

    private val tour by lazy { listOf(
        TourStep({ null }, "👋 Willkommen bei Marv PAD", "Looper, Sampler, Groovebox und DJ-Tool in einem. Diese Tour zeigt dir in einer Minute das Wichtigste. „Weiter“ tippen."),
        TourStep({ union(padRects.copyOfRange(0, VISIBLE_PADS)) }, "▦ Die Pads", "Leeres Pad GEDRÜCKT HALTEN = mit dem Mikro aufnehmen, loslassen = der Loop läuft. Kurz tippen = Loop starten/stoppen (im Takt). Auf leeren Pads lädt 📂 oben rechts Songs, Samples oder Drums."),
        TourStep({ union(padRects.copyOfRange(0, VISIBLE_PADS)) }, "⟲ Loop & Lautstärke im Pad", "Laufende Pads haben unten links [− ⟲ +]: ein Stück des Loops wiederholen und die Größe ändern. Auf einem Pad hoch/runter wischen ändert seine Lautstärke."),
        TourStep({ selRow }, "◉ SELECT & Werkzeuge", "SELECT an + Pad antippen wählt es aus, ohne es zu spielen. Dann gelten Wellen-Editor (〰 WAVE), Noten (🎹), Effekte (✨ FX), ⋯-Menü und die Effekt-Fläche nur für dieses Pad."),
        TourStep({ xyRect }, "✨ Effekt-Fläche", "Finger drauf = Effekt an, bewegen = X/Y steuern (Achsen stehen in der Ecke). HOLD hält ihn, ● REC nimmt die Bewegung auf. Bis zu 3 Effekte als Kette (1 · 2 · 3)."),
        TourStep({ fxBtn }, "Effekt wählen", "Links/rechts tippen wechselt den Effekt (45 Stück: Filter, Delay, Hall, Looper, Roboter …). Lange drücken öffnet die Liste."),
        TourStep({ union(knobRects) }, "Regler", "Mischung, die zwei Effekt-Parameter, Gesamtlautstärke und Swing. Regler hoch/runter ziehen."),
        TourStep({ union(funcRects) }, "Funktionen", "SZENE: Pad-Kombinationen speichern und im Takt wechseln · MUTE/SOLO · ERASE löscht · DUPL kopiert Pads · REPEAT wiederholt im Rhythmus."),
        TourStep({ modeRects[1] }, "🎹 Instrumente", "Acid-Bass (303), Drumcomputer (909) und 3 Synth-Spuren mit Piano-Roll, Akkordfolgen, Arpeggiator und Patterns A–D. Antippen öffnet den großen Editor."),
        TourStep({ displayRect }, "Tempo & Display", "Zeigt Effekt und Tempo. Rechts im Display tippen stellt das Tempo ein (Tap-Tempo, Metronom)."),
        TourStep({ union(arrayOf(masterBtn, setRecBtn)) }, "MASTER & Set-Aufnahme", "MASTER: Equalizer, Kompressor, Limiter für den Gesamtklang. REC nimmt alles, was du spielst, als Set auf – zum Exportieren oder Teilen."),
        TourStep({ designBtn }, "☰ Menü", "Hier findest du: 🎧 DJ-Modus, 🔁 Loop Station, ✂ Slicer, 💿 Musik-Sammlung, 📤 Export, 📲 Teilen, 📡 Handys koppeln, Projekte und Einstellungen."),
        TourStep({ null }, "🎉 Los geht's!", "Tipp: Im Menü unter „Hilfe & Info“ gibt es den ℹ Info-Modus – dann erklärt jedes angetippte Element sich selbst. Diese Tour startest du dort auch jederzeit neu.")
    ) }

    fun startTour() {
        closeFocus()
        tourStep = 0
        invalidate()
    }

    private fun endTour() {
        tourStep = -1
        onTourDone?.invoke()
        invalidate()
    }

    private fun drawTour(canvas: Canvas) {
        val st = tour.getOrNull(tourStep) ?: return
        val cols = overlayColors()
        val hl = st.rect()?.let { RectF(it).apply { inset(-dp(6f), -dp(6f)) } }
        fillPaint.shader = null
        fillPaint.color = Color.argb(185, 0, 0, 0)
        val w = width.toFloat()
        val h = height.toFloat()
        if (hl == null) canvas.drawRect(0f, 0f, w, h, fillPaint) else {
            canvas.drawRect(0f, 0f, w, hl.top, fillPaint)
            canvas.drawRect(0f, hl.bottom, w, h, fillPaint)
            canvas.drawRect(0f, hl.top, hl.left, hl.bottom, fillPaint)
            canvas.drawRect(hl.right, hl.top, w, hl.bottom, fillPaint)
            val pulse = 0.5f + 0.5f * sin(time * 4f)
            strokePaint.strokeWidth = dp(2.5f + pulse * 1.5f)
            strokePaint.color = cols[2]
            canvas.drawRoundRect(hl, dp(12f), dp(12f), strokePaint)
        }
        // Karte: gegenüber vom markierten Bereich
        val m = dp(16f)
        val cardH = dp(210f)
        val top = if (hl == null) (h - cardH) / 2f else if (hl.centerY() > h / 2f) max(insetTop + m, hl.top - cardH - m) else min(h - insetBottom - m - cardH, hl.bottom + m)
        tourCard.set(m, top, w - m, top + cardH)
        fillPaint.color = Color.rgb(24, 25, 32)
        canvas.drawRoundRect(tourCard, dp(16f), dp(16f), fillPaint)
        strokePaint.strokeWidth = dp(1.5f); strokePaint.color = cols[2]
        canvas.drawRoundRect(tourCard, dp(16f), dp(16f), strokePaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(17f); textPaint.color = Color.WHITE
        canvas.drawText(st.title, tourCard.left + dp(16f), tourCard.top + dp(30f), textPaint)
        textPaint.textSize = dp(10f); textPaint.color = alpha(Color.WHITE, 0.5f)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${tourStep + 1} / ${tour.size}", tourCard.right - dp(16f), tourCard.top + dp(28f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(13.5f); textPaint.color = alpha(Color.WHITE, 0.88f)
        // Zeilenumbruch
        val maxW = tourCard.width() - dp(32f)
        var y = tourCard.top + dp(56f)
        val words = st.text.split(' ')
        var line = ""
        for (wd in words) {
            val t = if (line.isEmpty()) wd else "$line $wd"
            if (textPaint.measureText(t) > maxW && line.isNotEmpty()) {
                canvas.drawText(line, tourCard.left + dp(16f), y, textPaint)
                y += dp(19f); line = wd
            } else line = t
        }
        if (line.isNotEmpty()) canvas.drawText(line, tourCard.left + dp(16f), y, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        val bh = dp(40f)
        val by = tourCard.bottom - bh - dp(12f)
        val bw = (tourCard.width() - dp(32f) - dp(16f)) / 3f
        tourEnd.set(tourCard.left + dp(16f), by, tourCard.left + dp(16f) + bw, by + bh)
        tourPrev.set(tourEnd.right + dp(8f), by, tourEnd.right + dp(8f) + bw, by + bh)
        tourNext.set(tourPrev.right + dp(8f), by, tourCard.right - dp(16f), by + bh)
        val last = tourStep == tour.size - 1
        for ((r, lab, on) in arrayOf(Triple(tourEnd, "Beenden", false), Triple(tourPrev, "◀ Zurück", false), Triple(tourNext, if (last) "Fertig ✓" else "Weiter ▶", true))) {
            if (r == tourPrev && tourStep == 0) continue
            fillPaint.color = if (on) cols[2] else Color.rgb(44, 46, 56)
            canvas.drawRoundRect(r, dp(10f), dp(10f), fillPaint)
            textPaint.textSize = dp(14f)
            textPaint.color = if (on) Color.BLACK else Color.WHITE
            canvas.drawText(lab, r.centerX(), r.centerY() + dp(5f), textPaint)
        }
        postInvalidateOnAnimation()
    }

    private fun tourTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return
        val x = e.x
        val y = e.y
        when {
            tourNext.contains(x, y) -> if (tourStep >= tour.size - 1) endTour() else tourStep++
            tourPrev.contains(x, y) && tourStep > 0 -> tourStep--
            tourEnd.contains(x, y) -> endTour()
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    // ================================================================ Lektionen (geführtes Lernen mit Beispielen)

    /** Ein Schritt: Text, markierter Bereich, Fingeranimation, Bedingung (geschafft?), Vorbereitung, Zusatzknopf. */
    private class LStep(
        val title: String,
        val text: String,
        val target: () -> RectF? = { null },
        val gesture: Int = G_NONE,
        val done: (() -> Boolean)? = null,
        val setup: (() -> Unit)? = null,
        val action: Pair<String, String>? = null
    )

    private class Lesson(val name: String, val desc: String, val steps: () -> List<LStep>)

    /** Lektionen-Aktionen, die die Activity ausführt (Drumkit laden, Dialoge öffnen …). */
    var onLessonAction: ((String) -> Unit)? = null
    var onLessonDone: ((Int) -> Unit)? = null
    private var lesson: Lesson? = null
    private var lessonIdx = -1
    private var lSteps: List<LStep> = emptyList()
    private var lStep = 0
    private var lDoneAt = 0f
    private var lEnter = 0f
    private var lBase = 0
    private val lCard = RectF()
    private val lNext = RectF()
    private val lPrev = RectF()
    private val lEnd = RectF()
    private val lAct = RectF()

    private fun firstEmptyPad(): Int = (0 until VISIBLE_PADS).firstOrNull { !Engine.hasLoop(it) } ?: 0
    private fun loopCount(): Int = (0 until VISIBLE_PADS).count { Engine.hasLoop(it) }

    private val lessons: List<Lesson> by lazy { listOf(
        Lesson("🎵 Dein erster Song", "Beat, Bass, Akkorde, Melodie, eigene Aufnahme, Effekte, Ablauf – Schritt für Schritt (ca. 10 min)") { listOf(
            LStep("🎵 Dein erster Song", "In etwa 10 Minuten baust du einen kompletten Song: Beat → Bass → Akkorde → Melodie → eigene Aufnahme → Effekte → Ablauf → Aufnahme. Der Finger ☝ zeigt dir, wo du tippen musst. Ein grüner Haken heißt: geschafft – dann geht es von selbst weiter."),
            LStep("⏱ 1 · Das Tempo", "Jeder Song hat ein Tempo. Ich stelle 122 BPM ein – typisch für House. Später änderst du es rechts im Display (Tap-Tempo, Metronom).", { displayRect }, G_TAP,
                setup = { Engine.setBpm(122f) }),
            LStep("🎹 2 · Zu den Instrumenten", "Rechts neben den Effekten sind die Instrumente: 303 (Bass), 909 (Drums) und SYNTH. Tippe eins an – der große Editor öffnet sich. Mit ✕ kommst du zurück.", { modeRects[1] }, G_TAP,
                done = { lessonInstOpened }, setup = { lessonInstOpened = false }),
            LStep("🥁 3 · Der Beat", "Ich lege einen House-Beat in den Drumcomputer (909): Kick auf jedem Viertel, Clap auf 2 und 4, offene Hi-Hat dazwischen. Drück jetzt ▶ zum Abspielen!", { playBtn }, G_TAP,
                done = { Engine.seqPlaying },
                setup = { if (instEd.isOpen) instEd.close(); InstState.drumApplyPreset(0); InstState.drumOn = true; InstState.drumLen = 16 }),
            LStep("🎸 4 · Der Bass", "Hörst du ihn? Ein Acid-Bass (303) auf den Offbeats – das typische Pumpen zwischen den Kicks. Tippe auf 🎹 303 – im Editor änderst du Töne, Filter und Akzente.", { modeRects[1] }, G_NONE,
                setup = {
                    val st = InstState
                    st.acidClear()
                    for (k in 0 until InstState.MAX) { val b = k % 16; if (b == 2 || b == 6 || b == 10 || b == 14) { st.acidGate[k] = true; st.acidSemi[k] = if (b == 10 && k % 32 >= 16) 7 else 9; st.acidAcc[k] = b == 2 } }
                    st.acidLen = 16; st.acidOn = true
                }),
            LStep("🎼 5 · Akkorde", "Jetzt Harmonie: Am – G – F – G als warme Fläche (SYNTH 2), je ein Takt. Die Tonart A-Moll ist jetzt eingestellt – alles, was du gleich spielst, passt dazu. Mehr Akkordfolgen: großer Editor → „Akkorde“.", { modeRects[1] }, G_NONE,
                setup = {
                    InstState.synCur = 1
                    InstState.writeProgression(InstState.progressions[6], 9, 16, 0)
                    InstState.synTracks[1].on = true
                    InstState.scaleSnap = true
                }),
            LStep("✏ 6 · Deine Melodie", "Jetzt du! Tippe auf 🎹 SYNTH und setze im Editor ein paar Noten in die Piano-Roll. Dank Tonleiter klingt jeder Ton passend. Tipp: Menü → Mikro → „Summen → Melodie“ geht auch mit der Stimme.", { modeRects[1] }, G_TAP,
                done = { InstState.synTracks[0].hasNotes() },
                setup = { InstState.synCur = 0; InstState.synTracks[0].on = true }),
            LStep("🎙 7 · Eigene Aufnahme", "Jetzt wird es persönlich: HALTE ein leeres Pad gedrückt und klatsche, singe oder klopfe etwas – beim Loslassen läuft es als Loop im Takt mit.", { padRects[firstEmptyPad()] }, G_HOLD,
                done = { loopCount() > lBase },
                setup = { if (instEd.isOpen) instEd.close(); lBase = loopCount() }),
            LStep("✨ 8 · Effekte", "Tippe auf den Reiter ✨ EFFEKTE und lege dann den Finger auf die Effekt-Fläche. Bewege ihn – oben/unten und links/rechts steuern den Effekt. Ideal für einen Filter-Sweep vor dem Drop!", { if (xyMode == 0) xyRect else modeRects[0] }, G_DRAG,
                done = { xyMode == 0 && xyPointer >= 0 }),
            LStep("🎬 9 · Der Ablauf", "Ein Song lebt von Abwechslung: Intro (nur Drums) → Aufbau → Drop (alles) → Break. Mit SZENE speicherst du solche Kombinationen und wechselst sie im Takt. Einen festen Ablauf baust du in der Song-Matrix (Menü → Studio). Probier: Pads stumm schalten und wieder starten – genau so entsteht Spannung.", { union(funcRects) }, G_TAP),
            LStep("⏺ 10 · Aufnehmen", "Tippe REC (oben): Alles, was du jetzt spielst, wird als Set aufgenommen. Nochmal tippen = Stopp. Danach kannst du es unter Menü → Projekt exportieren oder teilen.", { setRecBtn }, G_TAP,
                done = { SetRecorder.recording }),
            LStep("🎉 Geschafft!", "Das ist dein erster eigener Song – mit Beat, Bass, Akkorden, Melodie, eigener Aufnahme und Effekten. Speichere ihn im Menü (Projekt → speichern). Weiter geht es mit den anderen Lektionen!")
        ) },
        Lesson("🔁 Die Loop Station", "4 Spuren, ein großer Knopf: aufnehmen, Overdub, Strophe & Refrain – nur mit dem Mikro") { listOf(
            LStep("🔁 Die Loop Station", "Sie funktioniert wie ein Looper-Pedal: 4 Spuren, jede mit einem großen Knopf. Du baust einen Song Schicht für Schicht – nur mit Stimme, Klatschen oder Instrument. Tipp: Kopfhörer auf!"),
            LStep("1 · Das Fenster", "Ich öffne die Loop Station. Jede Farbe ist eine Spur. Darunter: großer Knopf (Aufnahme/Overdub), ▶/■, Löschen und ein Fader für die Lautstärke.", { if (station.isOpen) station.boxRect() else null }, G_NONE,
                setup = { if (!station.isOpen) openStation() }),
            LStep("● 2 · Erste Spur aufnehmen", "Tippe den großen Knopf von Spur 1 und klatsche einen einfachen Rhythmus – zum Beispiel: Klatsch · · Klatsch · Klatsch · ·", { station.bigRect(0) }, G_TAP,
                done = { Engine.state(LoopStationPanel.FIRST) == Engine.PadState.RECORDING }),
            LStep("⟳ 3 · Loop schließen", "Wenn dein Rhythmus fertig ist: nochmal tippen. Der Loop schließt sich (auf ganze Takte gerundet) und läuft ab sofort im Kreis.", { station.bigRect(0) }, G_TAP,
                done = { Engine.state(LoopStationPanel.FIRST) == Engine.PadState.PLAYING }),
            LStep("➕ 4 · Overdub", "Tippe noch einmal: OVERDUB. Alles, was du jetzt machst, kommt dazu – z. B. Fingerschnipsen auf die 2 und 4.", { station.bigRect(0) }, G_TAP,
                done = { Engine.state(LoopStationPanel.FIRST) == Engine.PadState.OVERDUB }),
            LStep("✓ 5 · Overdub beenden", "Fertig? Tippen beendet den Overdub. Gefällt es nicht: ↶ UNDO oben holt den Stand vorher zurück.", { station.bigRect(0) }, G_TAP,
                done = { Engine.state(LoopStationPanel.FIRST) == Engine.PadState.PLAYING }),
            LStep("🎤 6 · Zweite Spur", "Spur 2: sing eine Basslinie dazu – z. B. „dum · dum dum · “ – gleiche Technik: tippen, singen, tippen.", { station.bigRect(1) }, G_TAP,
                done = { Engine.state(LoopStationPanel.FIRST + 1) == Engine.PadState.PLAYING }),
            LStep("🎚 7 · Strophe & Refrain", "Jetzt arrangierst du: ■/▶ unter einer Spur stoppt/startet sie im Takt. Strophe = Spur 1 + 2, Refrain = alle Spuren. Der Fader regelt die Lautstärke, Löschen (lange halten) leert eine Spur.", { if (station.isOpen) station.boxRect() else null }, G_NONE),
            LStep("🎉 Geschafft!", "Du kannst jetzt live loopen. Profi-Tipp: Spuren 3 und 4 für Melodie und Effekt-Geräusche – und mit REC oben das ganze Set aufnehmen.")
        ) },
        Lesson("🥁 Beat mit Pads & Sequenzer", "Drumkit laden, Pads spielen, Schritte setzen") { listOf(
            LStep("🥁 Beat mit Pads", "Pads können nicht nur Loops, sondern auch einzelne Klänge (⚡ One-Shots) spielen. Daraus baust du mit dem Sequenzer einen Beat."),
            LStep("1 · Drumkit laden", "Ich lege ein Drumkit auf die freien Pads (oder tippe den Knopf unten).", { union(padRects.copyOfRange(0, VISIBLE_PADS)) }, G_NONE,
                done = { loopCount() >= 3 }, action = "Drumkit laden" to "kit",
                setup = { if (loopCount() < 3) onLessonAction?.invoke("kit") }),
            LStep("👆 2 · Spielen", "Tippe die Pads – jedes ist ein Klang (Kick, Snare, Hi-Hat …). Fest tippen = laut.", { padRects[0] }, G_TAP),
            LStep("▦ 3 · Der Sequenzer", "Die Leiste unten ist der Sequenzer: 16 Schritte = 1 Takt. Ich setze die Kick auf 1 · 5 · 9 · 13 (jedes Viertel). Wähle ein Pad und tippe weitere Schritte an.", { seqRect }, G_TAP,
                setup = { for (k in intArrayOf(0, 4, 8, 12)) Engine.setSeqVel(0, k, 1f) }),
            LStep("▶ 4 · Abspielen", "Drück ▶ – der Beat läuft. Ergänze Snare (5 und 13) und Hi-Hats (jeder zweite Schritt).", { playBtn }, G_TAP, done = { Engine.seqPlaying }),
            LStep("🎉 Geschafft!", "So entstehen Beats. Tipp: Im Menü → Mikro → „Beatbox → Drums“ beatboxt du einen Beat einfach ein.")
        ) },
        Lesson("🎤 Mit der Stimme produzieren", "Summen → Melodie, Beatbox → Drums, Auto-Tune, Harmonizer") { listOf(
            LStep("🎤 Mit der Stimme", "Dein Mikro ist ein Instrument: summen wird Melodie, beatboxen wird Beat, Auto-Tune und Harmonizer machen aus deiner Stimme einen Chor. Kopfhörer auf!"),
            LStep("🎯 1 · Einmessen", "Damit alles im Takt landet: einmal 8× zum Klick klatschen.", action = "Jetzt einmessen" to "calib"),
            LStep("🥁 2 · Beatbox → Drums", "„Bum“ = Kick, „Ka“/„Psch“ = Snare, „Ts“ = Hi-Hat. 1 Takt reicht für den Anfang.", action = "Beatbox starten" to "beatbox"),
            LStep("🎵 3 · Summen → Melodie", "Summe eine kurze Melodie („da da da“) – sie landet als Noten in einer Synth-Spur.", action = "Summen starten" to "hum"),
            LStep("🎚 4 · Auto-Tune & Harmonizer", "Live auf deine Stimme: Auto-Tune zieht dich in die Tonart, der Harmonizer singt Terz und Quinte dazu.", action = "Auto-Tune öffnen" to "autotune"),
            LStep("🎉 Geschafft!", "Alle Mikro-Werkzeuge findest du im Menü unter „🎤 Mikro“.")
        ) }
    ) }

    /** Für die automatischen Tests: Schritte einer Lektion und Sprung zu einem Schritt. */
    internal fun testLessonSteps(): Int = lSteps.size
    internal fun testLessonGo(i: Int) = enterStep(i)

    /** Namen und Beschreibungen der Lektionen (für die Auswahl). */
    fun lessonList(): List<Pair<String, String>> = lessons.map { it.name to it.desc }

    fun startLesson(i: Int) {
        val l = lessons.getOrNull(i) ?: return
        closeFocus()
        tourStep = -1
        lesson = l
        lessonIdx = i
        lSteps = l.steps()
        enterStep(0)
        invalidate()
    }

    fun endLesson() {
        lesson = null
        invalidate()
    }

    private fun enterStep(i: Int) {
        lStep = i.coerceIn(0, lSteps.size - 1)
        lDoneAt = 0f
        lEnter = time
        try { lSteps[lStep].setup?.invoke() } catch (ignored: Throwable) {}
    }

    private fun drawLesson(canvas: Canvas, dt: Float) {
        val st = lSteps.getOrNull(lStep) ?: return
        val cols = overlayColors()
        val acc = cols[2]
        val ok = Color.rgb(80, 220, 120)
        val w = width.toFloat()
        val h = height.toFloat()
        val tgt = try { st.target()?.let { if (it.width() > 0f) RectF(it).apply { inset(-dp(6f), -dp(6f)) } else null } } catch (e: Throwable) { null }
        // geschafft?
        val d = st.done
        if (d != null && lDoneAt == 0f && (try { d() } catch (e: Throwable) { false })) {
            lDoneAt = time
            val cx = tgt?.centerX() ?: (w / 2); val cy = tgt?.centerY() ?: (h / 2)
            burst(cx, cy, ok, 40, 260f)
            burst(cx, cy, acc, 25, 180f)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        if (lDoneAt > 0f && time - lDoneAt > 1.1f) {
            if (lStep < lSteps.size - 1) enterStep(lStep + 1)
            invalidate()
            return
        }
        // leicht abdunkeln, Ziel freilassen und pulsierend umrahmen
        fillPaint.shader = null
        fillPaint.color = Color.argb(120, 0, 0, 0)
        if (tgt == null) canvas.drawRect(0f, 0f, w, h, fillPaint) else {
            canvas.drawRect(0f, 0f, w, tgt.top, fillPaint)
            canvas.drawRect(0f, tgt.bottom, w, h, fillPaint)
            canvas.drawRect(0f, tgt.top, tgt.left, tgt.bottom, fillPaint)
            canvas.drawRect(tgt.right, tgt.top, w, tgt.bottom, fillPaint)
            val pulse = 0.5f + 0.5f * sin(time * 4f)
            strokePaint.strokeWidth = dp(2.5f + pulse * 2f)
            strokePaint.color = if (lDoneAt > 0f) ok else acc
            canvas.drawRoundRect(tgt, dp(12f), dp(12f), strokePaint)
            if (lDoneAt <= 0f) drawFinger(canvas, tgt, st.gesture, acc)
        }
        // Karte gegenüber vom Ziel
        val m = dp(12f)
        textPaint.textSize = dp(13.5f)
        textPaint.textAlign = Paint.Align.LEFT
        val maxW = w - 2 * m - dp(28f)
        val lines = wrap(st.text, maxW)
        val cardH = dp(64f) + lines.size * dp(19f) + dp(54f) + (if (st.action != null) dp(48f) else 0f)
        val top = if (tgt == null) (h - cardH) / 2f else if (tgt.centerY() > h * 0.5f) insetTop + m else h - insetBottom - m - cardH
        // sanftes Hereingleiten
        val k = ((time - lEnter) / 0.25f).coerceIn(0f, 1f)
        val slide = (1f - k) * dp(24f) * (if (top < h / 2) -1f else 1f)
        lCard.set(m, top + slide, w - m, top + slide + cardH)
        fillPaint.color = Color.rgb(22, 23, 30)
        canvas.drawRoundRect(lCard, dp(16f), dp(16f), fillPaint)
        strokePaint.strokeWidth = dp(1.5f); strokePaint.color = if (lDoneAt > 0f) ok else acc
        canvas.drawRoundRect(lCard, dp(16f), dp(16f), strokePaint)
        // Fortschritt
        val pw = lCard.width() - dp(32f)
        fillPaint.color = Color.argb(50, 255, 255, 255)
        canvas.drawRoundRect(lCard.left + dp(16f), lCard.top + dp(10f), lCard.left + dp(16f) + pw, lCard.top + dp(13f), dp(2f), dp(2f), fillPaint)
        fillPaint.color = acc
        canvas.drawRoundRect(lCard.left + dp(16f), lCard.top + dp(10f), lCard.left + dp(16f) + pw * (lStep + if (lDoneAt > 0f) 1f else 0f) / max(1, lSteps.size - 1).toFloat().coerceAtLeast(1f), lCard.top + dp(13f), dp(2f), dp(2f), fillPaint)
        textPaint.textSize = dp(16.5f); textPaint.color = if (lDoneAt > 0f) ok else Color.WHITE
        canvas.drawText((if (lDoneAt > 0f) "✓ " else "") + st.title, lCard.left + dp(16f), lCard.top + dp(38f), textPaint)
        textPaint.textSize = dp(10f); textPaint.color = alpha(Color.WHITE, 0.5f); textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${lStep + 1}/${lSteps.size}", lCard.right - dp(44f), lCard.top + dp(36f), textPaint)
        // ✕
        lEnd.set(lCard.right - dp(38f), lCard.top + dp(18f), lCard.right - dp(10f), lCard.top + dp(46f))
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = dp(16f); textPaint.color = alpha(Color.WHITE, 0.7f)
        canvas.drawText("✕", lEnd.centerX(), lEnd.centerY() + dp(6f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(13.5f); textPaint.color = alpha(Color.WHITE, 0.9f)
        var y = lCard.top + dp(62f)
        for (ln in lines) { canvas.drawText(ln, lCard.left + dp(16f), y, textPaint); y += dp(19f) }
        // Aktion
        lAct.setEmpty()
        st.action?.let { (label, _) ->
            lAct.set(lCard.left + dp(16f), y, lCard.right - dp(16f), y + dp(38f))
            fillPaint.color = alpha(acc, 0.22f)
            canvas.drawRoundRect(lAct, dp(10f), dp(10f), fillPaint)
            strokePaint.strokeWidth = dp(1.2f); strokePaint.color = acc
            canvas.drawRoundRect(lAct, dp(10f), dp(10f), strokePaint)
            textPaint.textAlign = Paint.Align.CENTER; textPaint.color = Color.WHITE; textPaint.textSize = dp(14f)
            canvas.drawText("▶ $label", lAct.centerX(), lAct.centerY() + dp(5f), textPaint)
        }
        // Knöpfe
        val bh = dp(40f)
        val by = lCard.bottom - bh - dp(12f)
        val bw = (lCard.width() - dp(40f)) / 2f
        lPrev.set(lCard.left + dp(16f), by, lCard.left + dp(16f) + bw, by + bh)
        lNext.set(lPrev.right + dp(8f), by, lCard.right - dp(16f), by + bh)
        val last = lStep == lSteps.size - 1
        val waiting = d != null && lDoneAt == 0f
        val nextLabel = if (last) "Fertig ✓" else if (waiting) "Überspringen ▷" else "Weiter ▶"
        for ((r, lab, on) in arrayOf(Triple(lPrev, "◀ Zurück", false), Triple(lNext, nextLabel, !waiting))) {
            if (r === lPrev && lStep == 0) continue
            fillPaint.color = if (on) acc else Color.rgb(44, 46, 56)
            canvas.drawRoundRect(r, dp(10f), dp(10f), fillPaint)
            textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = dp(14f)
            textPaint.color = if (on) Color.BLACK else Color.WHITE
            canvas.drawText(lab, r.centerX(), r.centerY() + dp(5f), textPaint)
        }
        if (waiting) {
            // „Warte auf dich“-Punkte
            val dots = ((time * 2.5f).toInt() % 4)
            textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = dp(11f); textPaint.color = alpha(acc, 0.9f)
            canvas.drawText("Du bist dran" + ".".repeat(dots), lCard.left + dp(16f), lPrev.top - dp(8f), textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Animierter Finger: Tippen (pulsierend), Halten (Ring füllt sich), Ziehen (Bewegung). */
    private fun drawFinger(canvas: Canvas, r: RectF, g: Int, acc: Int) {
        if (g == G_NONE) return
        var cx = r.centerX()
        var cy = r.centerY()
        val t = time
        when (g) {
            G_TAP -> {
                val ph = (t * 1.4f) % 1f
                strokePaint.strokeWidth = dp(2f)
                strokePaint.color = alpha(Color.WHITE, (1f - ph) * 0.8f)
                canvas.drawCircle(cx, cy, dp(10f) + ph * dp(26f), strokePaint)
                cy += if (ph < 0.25f) dp(4f) * (ph / 0.25f) else dp(4f) * (1f - (ph - 0.25f) / 0.75f)
            }
            G_HOLD -> {
                val ph = (t * 0.6f) % 1f
                strokePaint.strokeWidth = dp(4f)
                strokePaint.color = alpha(acc, 0.9f)
                tmpRect.set(cx - dp(24f), cy - dp(24f), cx + dp(24f), cy + dp(24f))
                canvas.drawArc(tmpRect, -90f, 360f * ph, false, strokePaint)
            }
            G_DRAG -> {
                val a = t * 2.2f
                cx += sin(a) * r.width() * 0.3f
                cy += sin(a * 2f) * r.height() * 0.22f
                fillPaint.color = alpha(acc, 0.25f)
                canvas.drawCircle(cx, cy, dp(18f), fillPaint)
            }
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(30f)
        textPaint.color = Color.WHITE
        canvas.drawText("☝", cx + dp(8f), cy + dp(30f), textPaint)
    }

    private fun wrap(text: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (wd in para.split(' ')) {
                val t = if (line.isEmpty()) wd else "$line $wd"
                if (textPaint.measureText(t) > maxW && line.isNotEmpty()) { out.add(line); line = wd } else line = t
            }
            out.add(line)
        }
        return out
    }

    /** Berührung während einer Lektion: Karte bedienen; alles andere geht an die App (zum Mitmachen). */
    private fun lessonTouch(e: MotionEvent): Boolean {
        val down = e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        val x = e.getX(e.actionIndex)
        val y = e.getY(e.actionIndex)
        if (!lCard.contains(x, y)) return false
        if (!down) return true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val st = lSteps.getOrNull(lStep)
        when {
            lEnd.contains(x, y) -> endLesson()
            !lAct.isEmpty && lAct.contains(x, y) -> st?.action?.second?.let { onLessonAction?.invoke(it) }
            lNext.contains(x, y) -> if (lStep >= lSteps.size - 1) { val i = lessonIdx; endLesson(); onLessonDone?.invoke(i) } else enterStep(lStep + 1)
            lPrev.contains(x, y) && lStep > 0 -> enterStep(lStep - 1)
        }
        invalidate()
        return true
    }

    /** Slicer: 16 Stücke auf 16 Pads. */
    private val slicer = SlicerPanel(this).also { sp ->
        sp.onHint = { hint(it) }
        sp.onRec = { recPressed() }
        sp.onSource = { onSlicerSource?.invoke() }
        sp.onToPads = { onSlicerToPads?.invoke() }
        sp.onExport = { onPanelExport?.invoke(1) }
    }
    /** Export aus Loop Station (0) bzw. Slicer (1). */
    var onPanelExport: ((Int) -> Unit)? = null
    var onSlicerSource: (() -> Unit)? = null
    var onSlicerToPads: (() -> Unit)? = null
    fun openSlicer() { closeFocus(); slicer.open(); invalidate() }
    val slicerOpen: Boolean get() = slicer.isOpen
    fun closeSlicer() { slicer.close(); invalidate() }

    /** Loop Station: 4 Spuren mit großem Rec/Dub-Knopf (Pads 13–16). */
    private val station = LoopStationPanel(this).also { it.onHint = { m -> hint(m) }; it.onNeedMicCalib = { onLessonAction?.invoke("calibAsk") }; it.onExport = { onPanelExport?.invoke(0) } }
    fun openStation() { closeFocus(); station.open(); invalidate() }
    val stationOpen: Boolean get() = station.isOpen
    fun closeStation() { station.close(); invalidate() }

    /** Szenen-Launcher (Session-Ansicht). */
    private val launcher = LauncherPanel(this).also { it.onHint = { m -> hint(m) } }
    fun openLauncher() { closeFocus(); launcher.open(); invalidate() }
    val launcherOpen: Boolean get() = launcher.isOpen
    fun closeLauncher() { launcher.close(); invalidate() }

    val ghost = GhostPanel(this).also { it.onHint = { m -> hint(m) } }
    fun openGhost() { closeFocus(); ghost.open(); invalidate() }
    val atmos = AtmosPanel(this).also { it.onHint = { m -> hint(m) } }
    fun openAtmos() { closeFocus(); atmos.open(); invalidate() }
    val harm = HarmonizerPanel(this).also { it.onHint = { m -> hint(m) } }
    fun openHarm() { closeFocus(); harm.open(); invalidate() }
    val euclid = EuclidPanel(this).also { it.onHint = { m -> hint(m) } }
    fun openEuclid() { closeFocus(); euclid.open(); invalidate() }
    val songEd = SongEditor(this).also { se ->
        se.onHint = { m -> hint(m) }
        se.onOpenInst = { inst, pat -> InstState.selectPat(inst, pat); openInstFromSong(inst) }
    }
    fun openSongEditor() { closeFocus(); songEd.open(); invalidate() }
    val songBuilder: SongBuilder = SongBuilder(this, songEd).also { sb ->
        sb.onHint = { m -> hint(m) }
        sb.onOpenInst = { inst, pat -> InstState.selectPat(inst, pat); openInstFromSong(inst) }
        sb.onProfi = { songEd.onClose = { songEd.onClose = null; songBuilder.open(); invalidate() }; songEd.open() }
    }
    /** Song-Baukasten (Standard) – die Spuren-Ansicht ist von dort aus erreichbar. */
    fun openSongBuilder() { closeFocus(); songBuilder.open(); invalidate() }

    /** Tonleiter für die Piano-Roll wählen. */
    var onInstScale: (() -> Unit)? = null
    /** Akkordfolge für die Synth-Spur erzeugen. */
    var onInstChords: (() -> Unit)? = null
    var onSidechain: (() -> Unit)? = null
    /** ACID/DRUM: Noten als MIDI-Datei laden/exportieren (1 = ACID, 2 = DRUM). */
    var onInstMidi: ((Int) -> Unit)? = null
    /** Preset-Bibliothek für ACID (1) / DRUM (2). */
    var onInstPresets: ((Int) -> Unit)? = null
    /** Klang-Kits (2) bzw. 303-Klang (1). */
    var onInstKits: ((Int) -> Unit)? = null
    private val modeRects = Array(2) { RectF() }

    // ---------------------------------------------------------------- Ansicht anpassen (Bereiche verschieben / ausblenden)

    /** Reihenfolge aller Bereiche (auch ausgeblendeter – sie behalten ihren Platz in der Reihe). */
    val secOrder = ArrayList(SEC_DEFAULT)
    val secHidden = HashSet<Int>()
    private val secTop = FloatArray(6) { -1f }
    private val secBottom = FloatArray(6) { -1f }
    /** Gespeicherte Form: „0,1,2,3,4,5|5“ (Reihenfolge | ausgeblendet). */
    var onLayoutChanged: ((String) -> Unit)? = null
    var layoutEdit = false
        private set
    private var editDrag = -1
    private var editDragY = 0f
    private var editGrabDy = 0f
    private var editPtr = -1
    private val editDone = RectF()
    private val editReset = RectF()
    private val editEyes = Array(6) { RectF() }
    private val editChips = Array(6) { RectF() }

    fun layoutString(): String = secOrder.joinToString(",") + "|" + secHidden.joinToString(",")

    fun applyLayout(str: String?) {
        val ord = ArrayList<Int>()
        val hid = HashSet<Int>()
        if (!str.isNullOrBlank()) {
            val parts = str.split("|")
            parts[0].split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..5 && it !in ord }.forEach { ord.add(it) }
            if (parts.size > 1) parts[1].split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..5 }.forEach { hid.add(it) }
        }
        for (d in SEC_DEFAULT) if (d !in ord) ord.add(d)
        hid.remove(SEC_PADS)
        secOrder.clear(); secOrder.addAll(ord)
        secHidden.clear(); secHidden.addAll(hid)
        layoutPads()
        invalidate()
    }

    fun startLayoutEdit() {
        closeFocus()
        if (instEd.isOpen) instEd.close()
        layoutEdit = true
        editDrag = -1
        hint("Bereiche mit dem Finger nach oben/unten ziehen · 👁 blendet aus · unten wieder einblenden")
        invalidate()
    }

    fun endLayoutEdit() {
        layoutEdit = false
        editDrag = -1
        layoutPads()
        onLayoutChanged?.invoke(layoutString())
        invalidate()
    }

    private fun drawLayoutEdit(canvas: Canvas) {
        val cols = overlayColors()
        val acc = cols[2]
        val w = width.toFloat()
        val h = height.toFloat()
        fillPaint.shader = null
        // alles abdunkeln
        fillPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        // Kopf: Titel + Knöpfe
        val by = insetTop + dp(8f)
        val bh = dp(36f)
        editDone.set(w - dp(16f) - dp(96f), by + dp(14f), w - dp(16f), by + dp(14f) + bh)
        editReset.set(editDone.left - dp(8f) - dp(96f), editDone.top, editDone.left - dp(8f), editDone.bottom)
        fillPaint.color = Color.rgb(18, 19, 24)
        tmpRect.set(dp(6f), insetTop + dp(2f), w - dp(6f), by + dp(64f))
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), fillPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        val title = fitLabel(textPaint, "🧩 ANSICHT ANPASSEN", editReset.left - dp(28f), dp(15f), dp(9f))
        canvas.drawText(title, dp(18f), editDone.centerY() - dp(3f), textPaint)
        textPaint.color = Color.argb(180, 255, 255, 255)
        val sub = fitLabel(textPaint, "ziehen = verschieben · 👁 = aus", editReset.left - dp(28f), dp(10f), dp(7f))
        canvas.drawText(sub, dp(18f), editDone.centerY() + dp(12f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        editBtn(canvas, editReset, "↺ STANDARD", false, acc)
        editBtn(canvas, editDone, "✓ FERTIG", true, acc)
        // Bereiche
        for (r in editEyes) r.setEmpty()
        val vis = secOrder.filter { it !in secHidden }
        for (id in vis) {
            if (id == editDrag) continue
            drawSecBand(canvas, id, secTop[id], secBottom[id], acc, false)
        }
        if (editDrag >= 0) {
            val hgt = secBottom[editDrag] - secTop[editDrag]
            val top = editDragY - editGrabDy
            // Einfügemarke
            val target = dropIndex(top + hgt / 2f)
            val others = vis.filter { it != editDrag }
            val ly = if (target >= others.size) secBottom[others.lastOrNull() ?: editDrag] else secTop[others[target]]
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = acc
            canvas.drawLine(dp(10f), ly, w - dp(10f), ly, strokePaint)
            drawSecBand(canvas, editDrag, top, top + hgt, acc, true)
        }
        // ausgeblendete Bereiche unten als Chips
        for (r in editChips) r.setEmpty()
        val hidden = secOrder.filter { it in secHidden }
        val trayTop = h - insetBottom - dp(46f)
        fillPaint.color = Color.rgb(18, 19, 24)
        tmpRect.set(dp(6f), trayTop, w - dp(6f), h - insetBottom - dp(4f))
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), fillPaint)
        if (hidden.isEmpty()) {
            textPaint.color = Color.argb(150, 255, 255, 255)
            textPaint.textSize = dp(10f)
            canvas.drawText("Ausgeblendete Bereiche erscheinen hier – antippen holt sie zurück", w / 2f, tmpRect.centerY() + dp(3.5f), textPaint)
        } else {
            val g = dp(6f)
            val cw = (tmpRect.width() - dp(12f) - (hidden.size - 1) * g) / hidden.size
            for ((i, id) in hidden.withIndex()) {
                val x = tmpRect.left + dp(6f) + i * (cw + g)
                editChips[id].set(x, tmpRect.top + dp(6f), x + cw, tmpRect.bottom - dp(6f))
                editBtn(canvas, editChips[id], "＋ " + SEC_NAMES[id], false, acc)
            }
        }
    }

    private fun editBtn(c: Canvas, r: RectF, label: String, on: Boolean, acc: Int) {
        fillPaint.color = if (on) acc else Color.rgb(40, 42, 52)
        c.drawRoundRect(r, dp(9f), dp(9f), fillPaint)
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = if (on) Color.WHITE else alpha(acc, 0.7f)
        c.drawRoundRect(r, dp(9f), dp(9f), strokePaint)
        textPaint.color = Color.WHITE
        val lb = fitLabel(textPaint, label, r.width() - dp(8f), dp(12f), dp(7f))
        c.drawText(lb, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    private fun drawSecBand(c: Canvas, id: Int, top: Float, bottom: Float, acc: Int, lifted: Boolean) {
        if (top < 0f || bottom <= top) return
        val r = RectF(dp(8f), top + dp(2f), width - dp(8f), bottom - dp(2f))
        if (lifted) softShadow(c, r, dp(12f))
        fillPaint.color = if (lifted) alpha(acc, 0.55f) else alpha(acc, 0.22f)
        c.drawRoundRect(r, dp(12f), dp(12f), fillPaint)
        strokePaint.strokeWidth = dp(if (lifted) 3f else 2f)
        strokePaint.color = if (lifted) Color.WHITE else acc
        c.drawRoundRect(r, dp(12f), dp(12f), strokePaint)
        textPaint.color = Color.WHITE
        val eyeW = if (id == SEC_PADS) 0f else dp(84f)
        val lb = fitLabel(textPaint, "☰  " + SEC_NAMES[id], r.width() - eyeW - dp(24f), min(dp(16f), r.height() * 0.45f), dp(8f))
        textPaint.textAlign = Paint.Align.LEFT
        c.drawText(lb, r.left + dp(12f), r.centerY() + textPaint.textSize * 0.36f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        if (id != SEC_PADS && !lifted) {
            val eh = min(dp(32f), r.height() - dp(8f))
            editEyes[id].set(r.right - dp(8f) - eyeW, r.centerY() - eh / 2f, r.right - dp(8f), r.centerY() + eh / 2f)
            editBtn(c, editEyes[id], "👁 AUS", false, acc)
        }
    }

    /** Ziel-Position (Index unter den übrigen sichtbaren Bereichen) für die Mitte [cy] des gezogenen Bereichs. */
    private fun dropIndex(cy: Float): Int {
        val others = secOrder.filter { it !in secHidden && it != editDrag }
        var k = 0
        for (id in others) if ((secTop[id] + secBottom[id]) / 2f < cy) k++
        return k
    }

    private fun layoutEditTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x; val y = e.y
                when {
                    editDone.contains(x, y) -> { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); endLayoutEdit(); hint("Ansicht gespeichert"); return }
                    editReset.contains(x, y) -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        secOrder.clear(); secOrder.addAll(SEC_DEFAULT); secHidden.clear()
                        layoutPads(); hint("Standard-Ansicht"); return
                    }
                }
                for (id in 0 until 6) {
                    if (editChips[id].contains(x, y)) {
                        secHidden.remove(id); layoutPads()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        hint("${SEC_NAMES[id]} wieder eingeblendet")
                        return
                    }
                    if (editEyes[id].contains(x, y)) {
                        secHidden.add(id); layoutPads()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        hint("${SEC_NAMES[id]} ausgeblendet – unten antippen holt ihn zurück")
                        return
                    }
                }
                for (id in secOrder) {
                    if (id in secHidden) continue
                    if (y >= secTop[id] && y < secBottom[id]) {
                        editDrag = id
                        editPtr = e.getPointerId(0)
                        editDragY = y
                        editGrabDy = y - secTop[id]
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        return
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (editDrag >= 0) {
                val pi = e.findPointerIndex(editPtr)
                if (pi >= 0) editDragY = e.getY(pi)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (editDrag >= 0) {
                val id = editDrag
                val hgt = secBottom[id] - secTop[id]
                val target = dropIndex(editDragY - editGrabDy + hgt / 2f)
                // neu einsortieren: hinter den [target]-ten sichtbaren Bereich (ausgeblendete behalten ihren Platz)
                val others = secOrder.filter { it !in secHidden && it != id }
                secOrder.remove(id)
                if (target >= others.size) secOrder.add(id)
                else secOrder.add(secOrder.indexOf(others[target]), id)
                editDrag = -1
                layoutPads()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        invalidate()
    }
    /** Zuletzt gewähltes Instrument (1 = ACID, 2 = DRUM, 3 = SYNTH). */
    private var instSel = 1
    private val instSegNames = arrayOf("303", "909", "SYNTH", "MEHR")
    /** „🎹 MEHR“: ATMOS, Harmonizer, Euklid, Chiptune (MainActivity zeigt die Auswahl). */
    var onInstMore: (() -> Unit)? = null
    fun openSynthEditor() { closeFocus(); instSel = 3; instEd.open(3); invalidate() }

    private fun instSegAt(x: Float): Int {
        val r = modeRects[1]
        return (((x - r.left) / r.width()) * 4).toInt().coerceIn(0, 3)
    }

    private fun instRunning(k: Int): Boolean {
        val st = InstState
        return Engine.seqPlaying && when (k) {
            1 -> st.acidOn && st.acidHasNotes()
            2 -> st.drumOn && st.drumHasNotes()
            else -> st.synOn && st.synHasNotes()
        }
    }

    private fun drawModeTabs(canvas: Canvas) {
        val cols = overlayColors()
        // Reiter EFFEKTE
        run {
            val r = modeRects[0]
            if (r.width() <= 0f) return
            val on = xyMode == 0
            val rr = r.height() / 2f
            fillPaint.shader = null
            fillPaint.color = if (on) cols[2] else alpha(cols[0], 0.85f)
            canvas.drawRoundRect(r, rr, rr, fillPaint)
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = if (on) Color.WHITE else alpha(cols[1], 0.4f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.color = if (on) Color.WHITE else alpha(cols[1], 0.8f)
            val lb = fitLabel(textPaint, "🎛 EFFEKTE", r.width() - dp(10f), dp(11f), dp(7f))
            canvas.drawText(lb, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
        }
        // Reiter INSTRUMENTE: 303 | 909 | SYNTH – antippen öffnet den großen Editor
        val r = modeRects[1]
        if (r.width() <= 0f) return
        val rr = r.height() / 2f
        val open = true
        fillPaint.color = alpha(cols[0], 0.85f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        if (!open) {
            strokePaint.strokeWidth = dp(1.2f)
            strokePaint.color = alpha(cols[1], 0.4f)
            canvas.drawRoundRect(r, rr, rr, strokePaint)
            textPaint.color = alpha(cols[1], 0.8f)
            val lb = fitLabel(textPaint, "🎹 INSTRUMENTE", r.width() - dp(30f), dp(11f), dp(7f))
            canvas.drawText(lb, r.centerX() - dp(8f), r.centerY() + textPaint.textSize * 0.36f, textPaint)
            // laufende Instrumente als Punkte
            var dx = r.right - dp(10f)
            for (k in 3 downTo 1) if (instRunning(k)) {
                dotPaint.color = cols[2]
                canvas.drawCircle(dx, r.centerY(), dp(2.6f), dotPaint)
                dx -= dp(7f)
            }
            return
        }
        val sw = r.width() / 4f
        for (k in 0 until 4) {
            val on = false
            tmpRect.set(r.left + k * sw + dp(1.5f), r.top + dp(1.5f), r.left + (k + 1) * sw - dp(1.5f), r.bottom - dp(1.5f))
            val rr2 = tmpRect.height() / 2f
            if (on) {
                fillPaint.color = cols[2]
                canvas.drawRoundRect(tmpRect, rr2, rr2, fillPaint)
            }
            textPaint.color = if (on) Color.WHITE else alpha(cols[1], 0.8f)
            val lb = fitLabel(textPaint, (if (k == 3) "＋ " else "🎹 ") + instSegNames[k], tmpRect.width() - dp(12f), dp(11f), dp(6.5f))
            canvas.drawText(lb, tmpRect.centerX() - dp(3f), tmpRect.centerY() + textPaint.textSize * 0.36f, textPaint)
            if (if (k == 3) Atmos.on || Harmonizer.on || Euclid.on else instRunning(k + 1)) {
                dotPaint.color = if (on) Color.WHITE else cols[2]
                canvas.drawCircle(tmpRect.right - dp(8f), tmpRect.centerY(), dp(2.6f), dotPaint)
            }
        }
        strokePaint.strokeWidth = dp(1.2f)
        strokePaint.color = alpha(cols[1], 0.4f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        // Trennstriche zwischen den drei Instrumenten
        strokePaint.strokeWidth = dp(1f)
        for (k in 1..3) canvas.drawLine(r.left + k * sw, r.top + dp(5f), r.left + k * sw, r.bottom - dp(5f), strokePaint)
    }

    /** Für die Lektion: wurde ein Instrument-Editor geöffnet? */
    private var lessonInstOpened = false

    // ---- gemeinsame Bausteine
    private val instHdr = Array(8) { RectF() }
    private var instHdrN = 0
    private val laneRect = RectF()
    private val accRow = RectF()
    private val sliRow = RectF()
    private val playRect = RectF()
    private val voiceRects = Array(InstState.DV) { RectF() }
    private val dStepRow = RectF()
    private val dPadRects = Array(12) { RectF() }
    private var instPointer = -1
    private var instKind = 0     // 1 = ACID-Play, 2 = ACID-Lane, 3 = Drum-Pad
    private var acidLiveSemi = Int.MIN_VALUE
    private var laneStep = -1
    private var laneWasOn = false
    private var laneDragged = false
    private var laneDownY = 0f
    private var laneVoice = -1
    private var drumAccMode = false

    private fun layoutHeader(n: Int) {
        val r = xyRect
        val h = dp(26f)
        val gap = dp(5f)
        val w = (r.width() - dp(12f) - (n - 1) * gap) / n
        for (i in 0 until n) {
            val x = r.left + dp(6f) + i * (w + gap)
            instHdr[i].set(x, r.top + dp(6f), x + w, r.top + dp(6f) + h)
        }
        instHdrN = n
    }

    private fun chip(canvas: Canvas, r: RectF, label: String, on: Boolean, cols: IntArray) {
        val rr = dp(7f)
        fillPaint.shader = null
        fillPaint.color = if (on) cols[2] else blend(cols[0], cols[1], 0.08f)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = if (on) Color.WHITE else alpha(cols[1], 0.35f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
        textPaint.textSize = min(dp(11f), r.width() / max(3f, label.length * 0.62f))
        textPaint.color = if (on) Color.WHITE else cols[1]
        canvas.drawText(label, r.centerX(), r.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    private fun instPanel(canvas: Canvas, cols: IntArray) {
        val r = xyRect
        val rr = dp(12f)
        fillPaint.shader = null
        fillPaint.color = Color.rgb(16, 17, 22)
        canvas.drawRoundRect(r, rr, rr, fillPaint)
        strokePaint.strokeWidth = dp(1.5f)
        strokePaint.color = alpha(cols[2], 0.8f)
        canvas.drawRoundRect(r, rr, rr, strokePaint)
    }

    private fun drawInstrument(canvas: Canvas) {
        val cols = overlayColors()
        instPanel(canvas, cols)
        when (xyMode) {
            1 -> drawAcid(canvas, cols)
            3 -> drawSynth(canvas, cols)
            else -> drawDrum(canvas, cols)
        }
    }

    // ---- ACID
    private val noteNm = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")

    private fun layoutAcid() {
        layoutHeader(8)
        val r = xyRect
        val top = instHdr[0].bottom + dp(6f)
        val rest = r.bottom - dp(6f) - top
        val rowH = dp(14f)
        val laneH = max(dp(34f), (rest - 2 * rowH - dp(10f)) * 0.48f)
        laneRect.set(r.left + dp(6f), top, r.right - dp(6f), top + laneH)
        accRow.set(laneRect.left, laneRect.bottom + dp(3f), laneRect.right, laneRect.bottom + dp(3f) + rowH)
        sliRow.set(laneRect.left, accRow.bottom + dp(2f), laneRect.right, accRow.bottom + dp(2f) + rowH)
        playRect.set(laneRect.left, sliRow.bottom + dp(6f), laneRect.right, r.bottom - dp(6f))
    }

    private fun drawAcid(canvas: Canvas, cols: IntArray) {
        layoutAcid()
        val st = InstState
        val labels = arrayOf(
            if (st.acidOn) "● AN" else "○ AUS", if (st.acidSquare) "⊓ SQR" else "⩘ SAW", "FX",
            "OKT−", "OKT+", "🎲 RND", "LEEREN", "✎ EDIT"
        )
        val ons = booleanArrayOf(st.acidOn, false, st.acidFx, false, false, false, false, false)
        for (i in 0 until 8) chip(canvas, instHdr[i], labels[i], ons[i], cols)
        // Lauflicht / Schritte
        val cur = Engine.seqCurrentStep()
        val cw = laneRect.width() / InstState.STEPS
        for (k in 0 until InstState.STEPS) {
            val x0 = laneRect.left + k * cw
            tmpRect.set(x0 + dp(1f), laneRect.top, x0 + cw - dp(1f), laneRect.bottom)
            fillPaint.color = if (k == cur) Color.rgb(48, 48, 58) else if (k % 4 == 0) Color.rgb(30, 31, 38) else Color.rgb(24, 25, 31)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            if (st.acidGate[k]) {
                val v = ((st.acidSemi[k] + 12) / 36f).coerceIn(0.05f, 1f)
                val bar = RectF(tmpRect.left, tmpRect.bottom - tmpRect.height() * v, tmpRect.right, tmpRect.bottom)
                fillPaint.color = if (st.acidAcc[k]) blend(cols[2], Color.WHITE, 0.35f) else cols[2]
                canvas.drawRoundRect(bar, dp(3f), dp(3f), fillPaint)
                if (cw > dp(17f)) {
                    textPaint.textSize = dp(8.5f)
                    textPaint.color = Color.WHITE
                    val sm = st.acidSemi[k]
                    canvas.drawText(noteNm[Math.floorMod(sm, 12)] + (2 + st.acidOct + Math.floorDiv(sm, 12)), tmpRect.centerX(), tmpRect.top + dp(10f), textPaint)
                }
            }
            if (k == cur) {
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), strokePaint)
            }
            // Accent / Slide
            for ((row, on, lab) in listOf(Triple(accRow, st.acidAcc[k], "A"), Triple(sliRow, st.acidSlide[k], "S"))) {
                tmpRect.set(x0 + dp(1f), row.top, x0 + cw - dp(1f), row.bottom)
                fillPaint.color = if (on) (if (lab == "A") Color.rgb(255, 170, 40) else Color.rgb(80, 180, 255)) else Color.rgb(28, 29, 36)
                canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), fillPaint)
                if (k == 0) {
                    textPaint.textSize = dp(8f)
                    textPaint.color = if (on) Color.BLACK else alpha(Color.WHITE, 0.5f)
                    canvas.drawText(lab, tmpRect.centerX(), tmpRect.centerY() + dp(3f), textPaint)
                }
            }
        }
        // Spielfläche: Klaviatur-Streifen (X = Ton, Y = Filter)
        val pr = playRect
        val notes = 25
        val nw = pr.width() / notes
        for (k in 0 until notes) {
            val n = k % 12
            val black = n == 1 || n == 3 || n == 6 || n == 8 || n == 10
            tmpRect.set(pr.left + k * nw, pr.top, pr.left + (k + 1) * nw, pr.bottom)
            fillPaint.color = if (acidLiveSemi == k) cols[2] else if (black) Color.rgb(20, 20, 26) else Color.rgb(36, 37, 46)
            canvas.drawRect(tmpRect, fillPaint)
            if (n == 0) {
                textPaint.textSize = dp(9f)
                textPaint.color = alpha(Color.WHITE, 0.6f)
                canvas.drawText("C${2 + st.acidOct + k / 12}", tmpRect.centerX(), pr.bottom - dp(5f), textPaint)
            }
        }
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(cols[2], 0.6f)
        canvas.drawRect(pr, strokePaint)
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(Color.WHITE, 0.55f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ FILTER  · X = TON (Gleiten = Slide)", pr.left + dp(5f), pr.top + dp(11f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        if (Engine.seqRecording) {
            textPaint.textSize = dp(9f)
            textPaint.color = Color.rgb(255, 80, 90)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("● REC → Schritte", pr.right - dp(5f), pr.top + dp(11f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    // ---- SYNTH
    private fun layoutSynth() {
        layoutHeader(8)
        val r = xyRect
        val top = instHdr[0].bottom + dp(6f)
        val rest = r.bottom - dp(6f) - top
        val rowH = dp(14f)
        val laneH = max(dp(34f), (rest - rowH - dp(8f)) * 0.5f)
        laneRect.set(r.left + dp(6f), top, r.right - dp(6f), top + laneH)
        accRow.set(laneRect.left, laneRect.bottom + dp(3f), laneRect.right, laneRect.bottom + dp(3f) + rowH)
        sliRow.setEmpty()
        playRect.set(laneRect.left, accRow.bottom + dp(6f), laneRect.right, r.bottom - dp(6f))
    }

    private fun drawSynth(canvas: Canvas, cols: IntArray) {
        layoutSynth()
        val st = InstState
        val labels = arrayOf(
            if (st.synOn) "● AN" else "○ AUS", st.synWaveNames[st.synWave], if (st.synChord == 0) "♪ EINZEL" else "♫ " + st.synChordNames[st.synChord],
            "FX", "OKT−", "OKT+", "🎲 RND", "✎ EDIT"
        )
        val ons = booleanArrayOf(st.synOn, false, st.synChord != 0, st.synFx, false, false, false, false)
        for (i in 0 until 8) chip(canvas, instHdr[i], labels[i], ons[i], cols)
        val cur = Engine.instCurrentStep(st.synLen)
        val cw = laneRect.width() / InstState.STEPS
        val synCol = Color.rgb(150, 120, 255)
        for (k in 0 until InstState.STEPS) {
            val x0 = laneRect.left + k * cw
            tmpRect.set(x0 + dp(1f), laneRect.top, x0 + cw - dp(1f), laneRect.bottom)
            fillPaint.color = if (k == cur % InstState.STEPS) Color.rgb(48, 48, 58) else if (k % 4 == 0) Color.rgb(30, 31, 38) else Color.rgb(24, 25, 31)
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            if (st.synGate[k]) {
                val v = ((st.synSemi[k] + 12) / 36f).coerceIn(0.05f, 1f)
                val bar = RectF(tmpRect.left, tmpRect.bottom - tmpRect.height() * v, tmpRect.right, tmpRect.bottom)
                fillPaint.color = if (st.synTie[k]) alpha(synCol, 0.55f) else synCol
                canvas.drawRoundRect(bar, dp(3f), dp(3f), fillPaint)
                if (cw > dp(17f) && !st.synTie[k]) {
                    textPaint.textSize = dp(8.5f)
                    textPaint.color = Color.WHITE
                    val sm = st.synSemi[k]
                    canvas.drawText(noteNm[Math.floorMod(sm, 12)] + (4 + st.synOct + Math.floorDiv(sm, 12)), tmpRect.centerX(), tmpRect.top + dp(10f), textPaint)
                }
            }
            if (k == cur % InstState.STEPS && cur >= 0) {
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), strokePaint)
            }
            tmpRect.set(x0 + dp(1f), accRow.top, x0 + cw - dp(1f), accRow.bottom)
            fillPaint.color = if (st.synTie[k]) Color.rgb(120, 220, 170) else Color.rgb(28, 29, 36)
            canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), fillPaint)
            if (k == 0) {
                textPaint.textSize = dp(8f)
                textPaint.color = if (st.synTie[k]) Color.BLACK else alpha(Color.WHITE, 0.5f)
                canvas.drawText("H", tmpRect.centerX(), tmpRect.centerY() + dp(3f), textPaint)
            }
        }
        val pr = playRect
        val notes = 25
        val nw = pr.width() / notes
        for (k in 0 until notes) {
            val n = k % 12
            val black = n == 1 || n == 3 || n == 6 || n == 8 || n == 10
            tmpRect.set(pr.left + k * nw, pr.top, pr.left + (k + 1) * nw, pr.bottom)
            fillPaint.color = if (synLiveSemi == k) synCol else if (black) Color.rgb(20, 20, 26) else Color.rgb(36, 37, 46)
            canvas.drawRect(tmpRect, fillPaint)
            if (n == 0) {
                textPaint.textSize = dp(9f)
                textPaint.color = alpha(Color.WHITE, 0.6f)
                canvas.drawText("C${4 + st.synOct + k / 12}", tmpRect.centerX(), pr.bottom - dp(5f), textPaint)
            }
        }
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = alpha(synCol, 0.7f)
        canvas.drawRect(pr, strokePaint)
        textPaint.textSize = dp(9f)
        textPaint.color = alpha(Color.WHITE, 0.55f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ FILTER  · X = TON" + if (st.synChord != 0) " · Akkord ${st.synChordNames[st.synChord]}" else "", pr.left + dp(5f), pr.top + dp(11f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private var synLiveSemi = Int.MIN_VALUE

    private fun synthDown(id: Int, x: Float, y: Float) {
        val st = InstState
        layoutSynth()
        for (i in 0 until 8) if (instHdr[i].contains(x, y)) {
            when (i) {
                0 -> st.synOn = !st.synOn
                1 -> st.synWave = (st.synWave + 1) % st.synWaveNames.size
                2 -> st.synChord = (st.synChord + 1) % st.synChordNames.size
                3 -> st.synFx = !st.synFx
                4 -> st.synOct = (st.synOct - 1).coerceAtLeast(-3)
                5 -> st.synOct = (st.synOct + 1).coerceAtMost(2)
                6 -> { Engine.pushPatternUndo("SYNTH Zufall"); st.synRandom() }
                7 -> instEd.open(3)
            }
            if (i == 1) hint("SYNTH-Klang: ${st.synWaveNames[st.synWave]}")
            if (i == 2) hint("Akkord: ${st.synChordNames[st.synChord]}")
            return
        }
        val cw = laneRect.width() / InstState.STEPS
        if (laneRect.contains(x, y)) {
            val k = ((x - laneRect.left) / cw).toInt().coerceIn(0, InstState.STEPS - 1)
            Engine.pushPatternUndo("SYNTH Schritt ${k + 1}")
            instPointer = id
            instKind = 5
            laneStep = k
            laneWasOn = st.synGate[k]
            laneDragged = false
            laneDownY = y
            if (!laneWasOn) { st.synGate[k] = true; st.synTie[k] = false; st.synVel[k] = 1f }
            return
        }
        if (accRow.contains(x, y)) {
            val k = ((x - laneRect.left) / cw).toInt().coerceIn(0, InstState.STEPS - 1)
            Engine.pushPatternUndo("SYNTH Halten")
            st.synTie[k] = !st.synTie[k]
            if (st.synTie[k]) {
                st.synGate[k] = true
                st.synSemi[k] = st.synSemi[Math.floorMod(k - 1, InstState.STEPS)]
            }
            return
        }
        if (playRect.contains(x, y)) {
            instPointer = id
            instKind = 4
            val sm = acidSemiAt(x)
            synLiveSemi = sm
            st.synCutMod = (1f - (y - playRect.top) / playRect.height()) * 2f - 1f
            Engine.synthLiveOn(sm, SYN_LIVE_TAG)
            burst(x, y, Color.rgb(150, 120, 255), 8, 140f)
        }
    }

    // ---- DRUM
    private fun layoutDrum() {
        layoutHeader(6)
        val r = xyRect
        val top = instHdr[0].bottom + dp(6f)
        val vh = dp(22f)
        val gap = dp(3f)
        val vw = (r.width() - dp(12f) - (InstState.DV - 1) * gap) / InstState.DV
        for (v in 0 until InstState.DV) {
            val x = r.left + dp(6f) + v * (vw + gap)
            voiceRects[v].set(x, top, x + vw, top + vh)
        }
        dStepRow.set(r.left + dp(6f), top + vh + dp(5f), r.right - dp(6f), top + vh + dp(5f) + dp(24f))
        val pTop = dStepRow.bottom + dp(6f)
        val cols = 6
        val rows = 2
        val pw = (r.width() - dp(12f) - (cols - 1) * gap) / cols
        val ph = (r.bottom - dp(6f) - pTop - (rows - 1) * gap) / rows
        for (i in 0 until 12) {
            val c = i % cols
            val rw = i / cols
            val x = r.left + dp(6f) + c * (pw + gap)
            val y = pTop + rw * (ph + gap)
            dPadRects[i].set(x, y, x + pw, y + ph)
        }
    }

    private val drumHitT = FloatArray(InstState.DV)

    private fun drawDrum(canvas: Canvas, cols: IntArray) {
        layoutDrum()
        val st = InstState
        val presetLabel = if (st.drumPreset >= 0) "▦ ${st.presetNames[st.drumPreset]}" else "▦ PRESET"
        val labels = arrayOf(if (st.drumOn) "● AN" else "○ AUS", "FX", presetLabel, if (Engine.seqPlaying) "■ STOP" else "▶ PLAY", "LEEREN", "✎ EDIT")
        val ons = booleanArrayOf(st.drumOn, st.drumFx, false, Engine.seqPlaying, false, false)
        for (i in 0 until 6) chip(canvas, instHdr[i], labels[i], ons[i], cols)
        for (v in 0 until InstState.DV) {
            val sel = v == st.drumSel
            val has = st.drumSteps[v].any { it > 0 }
            chip(canvas, voiceRects[v], st.drumNames[v], sel, cols)
            if (has && !sel) {
                dotPaint.color = cols[2]
                canvas.drawCircle(voiceRects[v].right - dp(4f), voiceRects[v].top + dp(4f), dp(2f), dotPaint)
            }
        }
        // Schritte des gewählten Instruments
        val cur = Engine.seqCurrentStep()
        val cw = dStepRow.width() / InstState.STEPS
        val row = st.drumSteps[st.drumSel]
        for (k in 0 until InstState.STEPS) {
            tmpRect.set(dStepRow.left + k * cw + dp(1f), dStepRow.top, dStepRow.left + (k + 1) * cw - dp(1f), dStepRow.bottom)
            fillPaint.color = when (row[k]) {
                2 -> blend(cols[2], Color.WHITE, 0.4f)
                1 -> cols[2]
                else -> if (k % 4 == 0) Color.rgb(34, 35, 44) else Color.rgb(26, 27, 34)
            }
            canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), fillPaint)
            if (row[k] == 2) {
                textPaint.textSize = dp(8f)
                textPaint.color = Color.BLACK
                canvas.drawText("A", tmpRect.centerX(), tmpRect.centerY() + dp(3f), textPaint)
            }
            if (k == cur) {
                strokePaint.strokeWidth = dp(1.5f)
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(tmpRect, dp(3f), dp(3f), strokePaint)
            }
        }
        // Finger-Drum-Pads
        for (i in 0 until 12) {
            val r = dPadRects[i]
            if (i == 11) {
                chip(canvas, r, if (drumAccMode) "ACC ✓" else "ACCENT", drumAccMode, cols)
                continue
            }
            drumHitT[i] = max(0f, drumHitT[i] - 0.06f)
            val sel = i == st.drumSel
            fillPaint.color = blend(Color.rgb(36, 37, 46), cols[2], 0.25f + 0.75f * drumHitT[i])
            canvas.drawRoundRect(r, dp(8f), dp(8f), fillPaint)
            strokePaint.strokeWidth = dp(if (sel) 2f else 1f)
            strokePaint.color = if (sel) Color.WHITE else alpha(cols[2], 0.5f)
            canvas.drawRoundRect(r, dp(8f), dp(8f), strokePaint)
            textPaint.textSize = dp(13f)
            textPaint.color = Color.WHITE
            canvas.drawText(st.drumNames[i], r.centerX(), r.centerY() + dp(2f), textPaint)
            textPaint.textSize = dp(8f)
            textPaint.color = alpha(Color.WHITE, 0.6f)
            canvas.drawText(st.drumLong[i], r.centerX(), r.centerY() + dp(13f), textPaint)
        }
    }

    // ---- Touch
    private fun acidSemiAt(x: Float): Int = (((x - playRect.left) / playRect.width()) * 25).toInt().coerceIn(0, 24)

    private fun instDown(id: Int, x: Float, y: Float) {
        val st = InstState
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (xyMode == 3) {
            synthDown(id, x, y)
            return
        }
        if (xyMode == 1) {
            layoutAcid()
            for (i in 0 until 8) if (instHdr[i].contains(x, y)) {
                when (i) {
                    7 -> instEd.open(1)
                    0 -> st.acidOn = !st.acidOn
                    1 -> st.acidSquare = !st.acidSquare
                    2 -> st.acidFx = !st.acidFx
                    3 -> st.acidOct = (st.acidOct - 1).coerceAtLeast(-1)
                    4 -> st.acidOct = (st.acidOct + 1).coerceAtMost(2)
                    5 -> { Engine.pushPatternUndo("ACID Zufall"); st.acidRandom() }
                    6 -> { Engine.pushPatternUndo("ACID leeren"); st.acidClear() }
                }
                return
            }
            val cw = laneRect.width() / InstState.STEPS
            if (laneRect.contains(x, y)) {
                val k = ((x - laneRect.left) / cw).toInt().coerceIn(0, InstState.STEPS - 1)
                Engine.pushPatternUndo("ACID Schritt ${k + 1}")
                instPointer = id
                instKind = 2
                laneStep = k
                laneWasOn = st.acidGate[k]
                laneDragged = false
                laneDownY = y
                if (!laneWasOn) st.acidGate[k] = true
                return
            }
            if (accRow.contains(x, y) || sliRow.contains(x, y)) {
                val k = ((x - laneRect.left) / cw).toInt().coerceIn(0, InstState.STEPS - 1)
                Engine.pushPatternUndo("ACID Accent/Slide")
                if (accRow.contains(x, y)) st.acidAcc[k] = !st.acidAcc[k] else st.acidSlide[k] = !st.acidSlide[k]
                return
            }
            if (playRect.contains(x, y)) {
                instPointer = id
                instKind = 1
                val sm = acidSemiAt(x)
                acidLiveSemi = sm
                st.acidCutMod = (1f - (y - playRect.top) / playRect.height()) * 2f - 1f
                Engine.acidLiveOn(sm, false)
                burst(x, y, overlayColors()[2], 8, 140f)
            }
        } else {
            layoutDrum()
            for (i in 0 until 6) if (instHdr[i].contains(x, y)) {
                when (i) {
                    5 -> instEd.open(2)
                    0 -> st.drumOn = !st.drumOn
                    1 -> st.drumFx = !st.drumFx
                    2 -> onInstPresets?.invoke(2)
                    3 -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
                    4 -> { Engine.pushPatternUndo("DRUM leeren"); st.drumClear() }
                }
                return
            }
            for (v in 0 until InstState.DV) if (voiceRects[v].contains(x, y)) {
                st.drumSel = v
                Engine.drum?.trigger(v, 0, false)
                return
            }
            if (dStepRow.contains(x, y)) {
                val k = (((x - dStepRow.left) / dStepRow.width()) * InstState.STEPS).toInt().coerceIn(0, InstState.STEPS - 1)
                val row = st.drumSteps[st.drumSel]
                Engine.pushPatternUndo("DRUM Schritt ${k + 1}")
                row[k] = (row[k] + 1) % 3
                return
            }
            for (i in 0 until 12) if (dPadRects[i].contains(x, y)) {
                if (i == 11) {
                    drumAccMode = !drumAccMode
                    return
                }
                st.drumSel = i
                drumHitT[i] = 1f
                Engine.drumHit(i, drumAccMode)
                if (Engine.repeatOn) {
                    // Note Repeat: Drum-Pad halten wiederholt im Raster
                    Engine.repeatDrumDown(i, drumAccMode)
                    instPointer = id
                    instKind = 3
                    repDrumVoice = i
                }
                burst(x, y, overlayColors()[2], 10, 160f)
                return
            }
        }
    }

    private fun instMove(x: Float, y: Float) {
        val st = InstState
        if (instKind == 4) {
            val sm = acidSemiAt(x)
            st.synCutMod = ((1f - (y - playRect.top) / playRect.height()) * 2f - 1f).coerceIn(-1f, 1f)
            if (sm != synLiveSemi) {
                Engine.synthLiveOff(SYN_LIVE_TAG)
                synLiveSemi = sm
                Engine.synthLiveOn(sm, SYN_LIVE_TAG)
            }
            return
        }
        if (instKind == 5 && laneStep >= 0) {
            if (laneDragged || abs(y - laneDownY) > dp(6f)) {
                laneDragged = true
                val v = (1f - (y - laneRect.top) / laneRect.height()).coerceIn(0f, 1f)
                val sm = (v * 36f).toInt() - 12
                if (sm != st.synSemi[laneStep] || laneVoice < 0) {
                    st.synSemi[laneStep] = sm
                    if (laneVoice >= 0) Engine.synthLiveOff(SYN_LANE_TAG)
                    Engine.synthLiveOn(sm, SYN_LANE_TAG)
                    laneVoice = 1
                }
            }
            return
        }
        if (instKind == 1) {
            val sm = acidSemiAt(x)
            st.acidCutMod = ((1f - (y - playRect.top) / playRect.height()) * 2f - 1f).coerceIn(-1f, 1f)
            if (sm != acidLiveSemi) {
                acidLiveSemi = sm
                Engine.acidLiveOn(sm, true)
            }
        } else if (instKind == 2 && laneStep >= 0) {
            if (laneDragged || abs(y - laneDownY) > dp(6f)) {
                laneDragged = true
                val v = (1f - (y - laneRect.top) / laneRect.height()).coerceIn(0f, 1f)
                val sm = (v * 36f).toInt() - 12
                if (sm != st.acidSemi[laneStep]) {
                    st.acidSemi[laneStep] = sm
                    Engine.acidLiveOn(sm, laneVoice >= 0)
                    laneVoice = 1
                }
            }
        }
    }

    private var repDrumVoice = -1

    private fun instUp() {
        val st = InstState
        if (instKind == 4) {
            Engine.synthLiveOff(SYN_LIVE_TAG)
            synLiveSemi = Int.MIN_VALUE
            st.synCutMod = 0f
        } else if (instKind == 5 && laneStep >= 0) {
            if (!laneDragged && laneWasOn) {
                st.synGate[laneStep] = false
                st.synTie[laneStep] = false
            }
            if (laneVoice >= 0) Engine.synthLiveOff(SYN_LANE_TAG)
            laneVoice = -1
            laneStep = -1
        } else if (instKind == 3) {
            Engine.repeatDrumUp(repDrumVoice)
            repDrumVoice = -1
        } else if (instKind == 1) {
            Engine.acidLiveOff()
            acidLiveSemi = Int.MIN_VALUE
            st.acidCutMod = 0f
        } else if (instKind == 2 && laneStep >= 0) {
            if (!laneDragged && laneWasOn) st.acidGate[laneStep] = false
            if (laneVoice >= 0) Engine.acidLiveOff()
            laneVoice = -1
            laneStep = -1
        }
        instPointer = -1
        instKind = 0
    }

    private fun instRelease() {
        if (instPointer >= 0) instUp()
    }

    companion object {
        /** Fingeranimationen der Lektionen. */
        private const val G_NONE = 0
        private const val G_TAP = 1
        private const val G_HOLD = 2
        private const val G_DRAG = 3
        private val BRAND_ORANGE = Color.rgb(255, 95, 0)

        const val VISIBLE_PADS = 12
        // Bereiche der Pad-Ansicht (Reihenfolge und Sichtbarkeit frei wählbar)
        const val SEC_KNOBS = 0
        const val SEC_FUNC = 1
        const val SEC_XY = 2
        const val SEC_SEL = 3
        const val SEC_PADS = 4
        const val SEC_SEQ = 5
        val SEC_NAMES = arrayOf("Drehregler", "Funktionsleiste", "Effekt-Fläche & Instrumente", "Auswahl-Leiste", "Pads", "Sequenzer & Transport")
        val SEC_DEFAULT = listOf(SEC_KNOBS, SEC_FUNC, SEC_XY, SEC_SEL, SEC_PADS, SEC_SEQ)
        private const val SYN_LIVE_TAG = 1
        private const val SYN_LANE_TAG = 2
        const val F_NONE = 0
        const val F_MUTE = 1
        const val F_SOLO = 2
        const val F_ERASE = 3
        const val F_DUPL = 4
        private val FUNC_NAMES = arrayOf("SZENE", "MUTE", "SOLO", "ERASE", "DUPL", "REPEAT")
        private val ACID_KNOBS = arrayOf("CUTOFF", "RESO", "ENV MOD", "DECAY", "LEVEL")
        private val DRUM_KNOBS = arrayOf("TUNE", "DECAY", "LEVEL", "DRIVE", "VOL")
        private val SYN_KNOBS = arrayOf("CUTOFF", "RESO", "ATTACK", "RELEASE", "LEVEL")
        const val RETRO = 4
        const val COMIC = 5
        const val GLASS = 6
        const val PASTEL = 7
        const val WOOD = 8
        const val CREAM = 9
        const val NOIR = 10
        const val GREY = 11
        const val CLAUDE = 12
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
            "GRAU – Hellgrau & Rändel",
            "CLAUDES DESIGN – Ton, Elfenbein & Ruhe"
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
        private const val KN_ARC = 5
        private const val XY_GLOW = 0
        private const val XY_GRID = 1
        private const val XY_STITCH = 2
        private const val XY_DIAG = 3
        private const val XY_CROSS = 4
        private const val XY_INSET = 5
        private const val XY_DOTS = 6
        private const val PS_FLAT = 0
        private const val PS_GLASS = 1
        private const val PS_FRAMED = 2
        private const val PS_DOUBLE = 3
        private const val PS_CLAUDE = 4
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
                Color.parseColor("#DCDCDC"), Color.parseColor("#3A3A3A"), Color.parseColor("#5A5A5A"), true),
            // CLAUDES DESIGN: warmes Graphit, Elfenbein-Schrift, Ton-Orange als einziger Akzent, Bernstein für One-Shots.
            // Keine Effekte um der Effekte willen – Farbe zeigt nur Zustand (läuft, aus, nimmt auf).
            Skin(Color.parseColor("#1A1918"), Color.parseColor("#151413"), TEX_NONE, Color.parseColor("#232220"), Color.parseColor("#34322F"),
                Color.parseColor("#EDE9E0"), Color.parseColor("#F5F2EA"), Color.parseColor("#D97757"), Color.parseColor("#E5B158"),
                KN_ARC, Color.parseColor("#2A2826"), Color.parseColor("#3A3835"), Color.parseColor("#EDE9E0"), Color.parseColor("#EDE9E0"),
                XY_DOTS, Color.parseColor("#1F1E1C"), Color.parseColor("#1F1E1C"), Color.parseColor("#4A4743"), 16, 10, Color.parseColor("#34322F"), Color.parseColor("#D97757"),
                PS_CLAUDE, Color.parseColor("#242321"), null, Color.parseColor("#3A3835"), 0.16f, false,
                Color.parseColor("#262523"), Color.parseColor("#3A3835"), Color.parseColor("#EDE9E0"),
                Color.parseColor("#121110"), Color.parseColor("#E8A07E"), Color.parseColor("#34322F"), false)
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
            "RES", "GRN", "SQF", "MFX", "KRS", "TAP", "SPC", "LOP", "SLC", "RVS", "HRM", "ATN", "ROB"
        )
        private fun fxCode(t: Int): String = FX_CODES.getOrElse(t) { "FX" }
        private val NEON_WIDTHS = floatArrayOf(18f, 10f, 5f, 2.2f)
        private val NEON_ALPHAS = floatArrayOf(0.10f, 0.22f, 0.50f, 1.0f)
    }
}
