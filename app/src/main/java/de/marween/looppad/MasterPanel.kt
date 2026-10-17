package de.marween.looppad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Master-Bereich als Overlay: Mastering-Module, Presets, A/B (Bypass), Pegel- und Reduktionsanzeigen. */
class MasterPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null
    private var mod = MasterBus.LIM

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tmp = RectF()

    private val box = RectF()
    private val bClose = RectF()
    private val bPreset = RectF()
    private val bBypass = RectF()
    private val meters = RectF()
    private val tabs = Array(6) { RectF() }
    private val bOn = RectF()
    private val sliders = ArrayList<RectF>()
    private var dragSlider = -1
    private var ptr = -1
    private var downT = 0L
    private var tabDown = -1

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
        save(host.context)
    }

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(10f)
        var y = box.top + m
        bClose.set(box.right - m - dp(40f), y, box.right - m, y + dp(36f))
        bBypass.set(bClose.left - dp(6f) - dp(70f), y, bClose.left - dp(6f), y + dp(36f))
        bPreset.set(bBypass.left - dp(6f) - dp(120f), y, bBypass.left - dp(6f), y + dp(36f))
        y += dp(46f)
        meters.set(box.left + m, y, box.right - m, y + dp(64f))
        y = meters.bottom + dp(10f)
        val g = dp(5f)
        val tw = (box.width() - 2 * m - 5 * g) / 6
        for (i in 0..5) tabs[i].set(box.left + m + i * (tw + g), y, box.left + m + i * (tw + g) + tw, y + dp(42f))
        y += dp(52f)
        bOn.set(box.left + m, y, box.left + m + dp(110f), y + dp(34f))
        y += dp(46f)
        sliders.clear()
        val n = MasterBus.par[mod].size
        val h = min(dp(56f), (box.bottom - m - y) / n)
        for (i in 0 until n) sliders.add(RectF(box.left + m + dp(4f), y + i * h, box.right - m - dp(4f), y + (i + 1) * h - dp(8f)))
    }

    fun draw(c: Canvas, area: RectF, accent: Int) {
        layout(area)
        fill.shader = null
        fill.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(16, 17, 22)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f)
        stroke.color = accent
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f)
        text.color = Color.WHITE
        c.drawText("🎚 MASTER", box.left + dp(14f), bClose.centerY() + dp(5f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bPreset, "▾ " + MasterBus.PRESETS[MasterBus.preset.coerceIn(0, MasterBus.PRESETS.size - 1)], false, accent)
        btn(c, bBypass, if (MasterBus.bypass) "B · AUS" else "A · AN", !MasterBus.bypass && MasterBus.on.any { it }, accent)
        btn(c, bClose, "✕", false, accent)
        drawMeters(c, accent)
        for (i in 0..5) {
            val on = MasterBus.on[i]
            btn(c, tabs[i], MasterBus.MODULES[i], i == mod, accent)
            fill.color = if (on) Color.rgb(80, 230, 120) else Color.rgb(70, 72, 80)
            c.drawCircle(tabs[i].right - dp(7f), tabs[i].top + dp(7f), dp(3.5f), fill)
            if (i < 5) {
                // Signalfluss-Pfeil
                text.textSize = dp(9f)
                text.color = Color.argb(120, 255, 255, 255)
                c.drawText("›", tabs[i].right + dp(2.5f), tabs[i].centerY() + dp(3f), text)
            }
        }
        btn(c, bOn, if (MasterBus.on[mod]) "● ${MasterBus.MODULES[mod]} AN" else "○ ${MasterBus.MODULES[mod]} AUS", MasterBus.on[mod], accent)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(10f)
        text.color = Color.argb(170, 255, 255, 255)
        c.drawText(info(mod), bOn.right + dp(10f), bOn.centerY() + dp(4f), text)
        text.textAlign = Paint.Align.CENTER
        val p = MasterBus.par[mod]
        for (i in sliders.indices) {
            val r = sliders[i]
            val v = p[i].coerceIn(0f, 1f)
            text.textAlign = Paint.Align.LEFT
            text.textSize = min(dp(11f), r.height() * 0.32f)
            text.color = Color.WHITE
            c.drawText(MasterBus.NAMES[mod][i], r.left, r.top + text.textSize, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = accent
            c.drawText(MasterBus.label(mod, i), r.right, r.top + text.textSize, text)
            text.textAlign = Paint.Align.CENTER
            val bar = RectF(r.left, r.bottom - max(dp(8f), r.height() * 0.3f), r.right, r.bottom)
            fill.color = Color.rgb(40, 42, 52)
            c.drawRoundRect(bar, dp(4f), dp(4f), fill)
            fill.color = if (MasterBus.on[mod]) accent else Color.rgb(110, 112, 120)
            tmp.set(bar.left, bar.top, bar.left + bar.width() * v, bar.bottom)
            c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
            fill.color = Color.WHITE
            c.drawCircle(bar.left + bar.width() * v, bar.centerY(), bar.height() * 0.8f, fill)
        }
        host.postInvalidateOnAnimation()
    }

    private fun info(m: Int): String = when (m) {
        MasterBus.EQ -> "Tiefen säubern, Klang formen"
        MasterBus.COMP -> String.format(Locale.GERMANY, "Reduktion %.1f dB", MasterBus.compGr)
        MasterBus.MB -> String.format(Locale.GERMANY, "B %.1f · M %.1f · H %.1f dB", MasterBus.mbGr[0], MasterBus.mbGr[1], MasterBus.mbGr[2])
        MasterBus.SAT -> "Wärme und Dichte"
        MasterBus.WIDTH -> "Stereo-Bild (Bass bleibt mono)"
        else -> String.format(Locale.GERMANY, "Reduktion %.1f dB", MasterBus.limGr)
    }

    private fun db(v: Float) = if (v <= 1e-5f) -60f else (20 * kotlin.math.log10(v)).coerceAtLeast(-60f)

    private fun drawMeters(c: Canvas, accent: Int) {
        val r = meters
        fill.color = Color.rgb(10, 11, 14)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        val items = listOf(
            Triple("IN", db(MasterBus.inPeak), false),
            Triple("KOMP", -MasterBus.compGr, true),
            Triple("LIMIT", -MasterBus.limGr, true),
            Triple("OUT", db(MasterBus.outPeak), false)
        )
        val w = (r.width() - dp(16f)) / 5
        for ((k, it) in items.withIndex()) {
            val x0 = r.left + dp(8f) + k * w
            text.textSize = dp(9f)
            text.color = Color.argb(170, 255, 255, 255)
            c.drawText(it.first, x0 + w / 2, r.top + dp(12f), text)
            val bar = RectF(x0 + dp(6f), r.top + dp(18f), x0 + w - dp(6f), r.top + dp(30f))
            fill.color = Color.rgb(34, 36, 44)
            c.drawRoundRect(bar, dp(3f), dp(3f), fill)
            if (it.third) {
                // Reduktion: von rechts nach links (0 … −12 dB)
                val f = (-it.second / 12f).coerceIn(0f, 1f)
                fill.color = Color.rgb(255, 170, 40)
                c.drawRoundRect(RectF(bar.right - bar.width() * f, bar.top, bar.right, bar.bottom), dp(3f), dp(3f), fill)
            } else {
                val f = ((it.second + 60f) / 60f).coerceIn(0f, 1f)
                fill.color = if (it.second > -0.5f) Color.rgb(255, 60, 60) else if (it.second > -6f) Color.rgb(255, 200, 60) else Color.rgb(80, 230, 120)
                c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), dp(3f), dp(3f), fill)
            }
            text.textSize = dp(10f)
            text.color = Color.WHITE
            c.drawText(String.format(Locale.GERMANY, "%.1f dB", it.second), x0 + w / 2, r.top + dp(46f), text)
        }
        val x0 = r.left + dp(8f) + 4 * w
        text.textSize = dp(9f)
        text.color = Color.argb(170, 255, 255, 255)
        c.drawText("LAUTHEIT", x0 + w / 2, r.top + dp(12f), text)
        text.textSize = dp(15f)
        text.color = accent
        c.drawText(String.format(Locale.GERMANY, "%.1f", MasterBus.loud.coerceAtLeast(-60f)), x0 + w / 2, r.top + dp(36f), text)
        text.textSize = dp(8.5f)
        text.color = Color.argb(150, 255, 255, 255)
        c.drawText("dB RMS", x0 + w / 2, r.top + dp(50f), text)
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 90, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.textSize = min(dp(11.5f), (r.width() - dp(6f)) / max(2f, label.length * 0.6f))
        text.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (ptr >= 0) return
                val x = e.getX(i)
                val y = e.getY(i)
                downT = SystemClock.uptimeMillis()
                when {
                    bClose.contains(x, y) -> { close(); haptic(); return }
                    bBypass.contains(x, y) -> {
                        MasterBus.bypass = !MasterBus.bypass
                        onHint?.invoke(if (MasterBus.bypass) "B: Master umgangen – zum Vergleichen" else "A: Master-Kette aktiv")
                        haptic(); return
                    }
                    bPreset.contains(x, y) -> {
                        val nx = (MasterBus.preset + 1) % MasterBus.PRESETS.size
                        MasterBus.applyPreset(nx)
                        MasterBus.bypass = false
                        onHint?.invoke("Preset: ${MasterBus.PRESETS[nx]}")
                        haptic(); return
                    }
                    bOn.contains(x, y) -> {
                        MasterBus.on[mod] = !MasterBus.on[mod]
                        MasterBus.dirty = true
                        haptic(); return
                    }
                }
                for (t in 0..5) if (tabs[t].contains(x, y)) {
                    ptr = e.getPointerId(i)
                    tabDown = t
                    return
                }
                for (k in sliders.indices) {
                    val r = sliders[k]
                    if (x >= r.left - dp(8f) && x <= r.right + dp(8f) && y >= r.top - dp(4f) && y <= r.bottom + dp(8f)) {
                        ptr = e.getPointerId(i)
                        dragSlider = k
                        setSlider(x)
                        return
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = e.findPointerIndex(ptr)
                if (pi >= 0 && dragSlider >= 0) setSlider(e.getX(pi))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked != MotionEvent.ACTION_CANCEL && e.getPointerId(i) == ptr && tabDown >= 0) {
                    if (SystemClock.uptimeMillis() - downT > 450) {
                        // lange drücken = Modul an/aus
                        MasterBus.on[tabDown] = !MasterBus.on[tabDown]
                        MasterBus.dirty = true
                        onHint?.invoke("${MasterBus.MODULES[tabDown]} " + if (MasterBus.on[tabDown]) "an" else "aus")
                    } else mod = tabDown
                    haptic()
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(i) == ptr) {
                    ptr = -1
                    dragSlider = -1
                    tabDown = -1
                }
            }
        }
    }

    private fun setSlider(x: Float) {
        val r = sliders.getOrNull(dragSlider) ?: return
        MasterBus.par[mod][dragSlider] = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        MasterBus.dirty = true
        // Regler bewegt → Modul einschalten
        if (!MasterBus.on[mod]) MasterBus.on[mod] = true
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    companion object {
        fun save(ctx: Context) {
            ctx.getSharedPreferences("marvpad", Context.MODE_PRIVATE).edit().putString("master", MasterBus.toJson().toString()).apply()
        }

        fun load(ctx: Context) {
            val s = ctx.getSharedPreferences("marvpad", Context.MODE_PRIVATE).getString("master", null) ?: return
            try { MasterBus.fromJson(org.json.JSONObject(s)) } catch (ignored: Exception) {}
        }
    }
}
