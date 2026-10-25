package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bedien-Oberfläche auf einem gekoppelten Handy: zeigt eine der Oberflächen (Decks, Mixer, Pads,
 * Effekte, Drums, Pad-Mixer) und schickt jede Bedienung an den Host. Kein eigener Klang.
 */
class RemoteView(context: Context) : View(context) {
    var onLeave: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private val BG = Color.rgb(14, 15, 19)
    private val PANEL = Color.rgb(26, 28, 34)
    private val INK = Color.rgb(232, 234, 240)
    private val DIM = Color.rgb(130, 134, 146)
    private val ACC = Color.rgb(255, 106, 16)
    private val COL_A = Color.rgb(255, 120, 40)
    private val COL_B = Color.rgb(60, 160, 255)
    private val HOT = intArrayOf(Color.rgb(255, 60, 90), Color.rgb(60, 220, 120), Color.rgb(70, 150, 255), Color.rgb(200, 90, 255),
        Color.rgb(255, 170, 40), Color.rgb(40, 220, 230), Color.rgb(255, 110, 200), Color.rgb(230, 230, 80))
    private val MODE_COLS = intArrayOf(Color.rgb(255, 150, 40), Color.rgb(60, 200, 120), Color.rgb(70, 160, 255), Color.rgb(255, 70, 110))

    private var insetTop = 0f
    private var insetBottom = 0f
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = i.top.toFloat(); insetBottom = i.bottom.toFloat()
        }
        return insets
    }

    // ---------------------------------------------------------------- Bedienelemente (bei jedem Zeichnen neu registriert)

    /** kind: 0 Taste (down/up), 1 Regler senkrecht (relativ), 2 Schieber waagerecht (absolut), 3 Fader senkrecht (absolut), 4 XY, 5 Pad (Anschlag) */
    private class Ctl(val r: RectF, val kind: Int, val key: String, val value: Float = 0f, val action: ((Boolean) -> Unit)? = null, val onValue: ((Float, Float) -> Unit)? = null)
    private val ctls = ArrayList<Ctl>()
    private class Drag(val c: Ctl, val startY: Float, val startV: Float, var v: Float, var v2: Float = 0f)
    private val drags = HashMap<Int, Drag>()
    /** Werte, die gerade gezogen werden (statt der vom Host gemeldeten anzeigen). */
    private val local = HashMap<String, Float>()

    private var padMode = 0
    private var mixDeckFocus = 0

    private fun st(): JSONObject? = Link.state

    override fun onDraw(c: Canvas) {
        c.drawColor(BG)
        ctls.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val m = dp(10f)
        var y = insetTop + dp(6f)
        // Kopfzeile: Verbindung · Oberflächen · ✕
        val conn = Link.client
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(12f)
        text.color = if (conn != null) Color.rgb(90, 220, 120) else Color.rgb(255, 90, 90)
        val head = if (conn != null) "📡 verbunden" + (if (conn.viaBt) " (Bluetooth)" else " (WLAN)") else "📡 Verbindung getrennt"
        c.drawText(head, m, y + dp(14f), text)
        val bpm = st()?.optDouble("bpm", 0.0) ?: 0.0
        text.color = INK
        text.textAlign = Paint.Align.RIGHT
        c.drawText(if (bpm > 0) String.format(Locale.GERMANY, "%.1f BPM", bpm) else "– BPM", w - m - dp(50f), y + dp(14f), text)
        text.textAlign = Paint.Align.CENTER
        button(c, RectF(w - m - dp(42f), y, w - m, y + dp(26f)), "✕", false, Color.rgb(200, 50, 60)) { if (it) onLeave?.invoke() }
        y += dp(32f)
        // Oberflächen-Wahl
        val sw = (w - 2 * m - 5 * dp(4f)) / 6f
        val short = arrayOf("DECKS", "MIXER", "PADS", "FX", "DRUMS", "P-MIX")
        for (i in 0..5) {
            val r = RectF(m + i * (sw + dp(4f)), y, m + i * (sw + dp(4f)) + sw, y + dp(30f))
            button(c, r, short[i], Link.mySurface == i, ACC) { if (it) { Link.mySurface = i; invalidate() } }
        }
        y += dp(38f)
        val area = RectF(m, y, w - m, h - insetBottom - m)
        if (st() == null) {
            text.textSize = dp(14f); text.color = DIM
            c.drawText(if (conn != null) "Warte auf den Host …" else "Nicht verbunden – ✕ und neu koppeln", w / 2, area.centerY(), text)
        } else when (Link.mySurface) {
            0 -> drawDecks(c, area)
            1 -> drawMixer(c, area)
            2 -> drawPads(c, area, true)
            3 -> drawFx(c, area)
            4 -> drawPads(c, area, false)
            else -> drawPadMixer(c, area)
        }
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- Zeichen-Helfer

    private fun button(c: Canvas, r: RectF, label: String, on: Boolean, col: Int, held: Boolean = false, action: (Boolean) -> Unit) {
        val pressed = drags.values.any { it.c.r == r && it.c.kind == 0 }
        fill.color = if (on || pressed) col else PANEL
        c.drawRoundRect(r, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = if (on) lighten(col) else Color.argb(90, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.color = if (on || pressed) (if (lum(col) > 0.62f) Color.rgb(20, 20, 24) else Color.WHITE) else INK
        val t = fitLabel(text, label, r.width() - dp(6f), min(dp(15f), r.height() * 0.42f), dp(7f))
        c.drawText(t, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        if (held) {
            text.textSize = min(dp(8f), r.height() * 0.16f)
            text.color = Color.argb(160, 255, 255, 255)
            c.drawText("● HALTEN", r.centerX(), r.bottom - dp(4f), text)
        }
        ctls.add(Ctl(RectF(r), 0, label, 0f, action))
    }

    private fun lum(col: Int) = (0.299f * Color.red(col) + 0.587f * Color.green(col) + 0.114f * Color.blue(col)) / 255f
    private fun lighten(col: Int) = Color.rgb(min(255, Color.red(col) + 60), min(255, Color.green(col) + 60), min(255, Color.blue(col) + 60))

    /** Waagerechter Schieber (absolut, Mitte rastet bei [center]). */
    private fun hSlider(c: Canvas, r: RectF, key: String, v0: Float, label: String, col: Int, center: Float = -1f, send: (Float) -> Unit) {
        val v = local[key] ?: v0
        fill.color = PANEL
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        val x = r.left + dp(8f) + v * (r.width() - dp(16f))
        fill.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
        if (center >= 0f) {
            val cx = r.left + dp(8f) + center * (r.width() - dp(16f))
            c.drawRect(min(cx, x), r.top + dp(6f), max(cx, x), r.bottom - dp(6f), fill)
            stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = dp(1f)
            c.drawLine(cx, r.top + dp(3f), cx, r.bottom - dp(3f), stroke)
        } else c.drawRect(r.left + dp(4f), r.top + dp(6f), x, r.bottom - dp(6f), fill)
        fill.color = col
        c.drawRoundRect(RectF(x - dp(9f), r.top + dp(2f), x + dp(9f), r.bottom - dp(2f)), dp(4f), dp(4f), fill)
        text.textSize = dp(10f); text.color = INK; text.textAlign = Paint.Align.LEFT
        c.drawText(label, r.left + dp(6f), r.top - dp(3f), text)
        text.textAlign = Paint.Align.CENTER
        ctls.add(Ctl(RectF(r), 2, key, v, null) { nv, _ ->
            var q = nv
            if (center >= 0f && abs(q - center) < 0.025f) q = center
            local[key] = q; send(q)
        })
    }

    /** Senkrechter Fader (absolut). */
    private fun vFader(c: Canvas, r: RectF, key: String, v0: Float, label: String, col: Int, level: Float = -1f, send: (Float) -> Unit) {
        val v = local[key] ?: v0
        fill.color = PANEL
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        val y = r.bottom - dp(10f) - v * (r.height() - dp(20f))
        fill.color = Color.argb(80, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRect(r.centerX() - dp(4f), y, r.centerX() + dp(4f), r.bottom - dp(8f), fill)
        if (level >= 0f) {
            val lv = min(1f, level)
            fill.color = if (lv > 0.95f) Color.rgb(255, 60, 60) else Color.rgb(70, 220, 120)
            c.drawRect(r.right - dp(6f), r.bottom - dp(8f) - lv * (r.height() - dp(16f)), r.right - dp(3f), r.bottom - dp(8f), fill)
        }
        fill.color = col
        c.drawRoundRect(RectF(r.left + dp(5f), y - dp(10f), r.right - dp(9f), y + dp(10f)), dp(4f), dp(4f), fill)
        text.textSize = dp(10f); text.color = INK
        c.drawText(label, r.centerX(), r.bottom + dp(12f), text)
        ctls.add(Ctl(RectF(r), 3, key, v, null) { nv, _ -> local[key] = nv; send(nv) })
    }

    /** Drehregler (senkrecht ziehen, relativ; Mitte rastet). */
    private fun knob(c: Canvas, r: RectF, key: String, v0: Float, label: String, col: Int, send: (Float) -> Unit) {
        val v = local[key] ?: v0
        val rad = min(r.width(), r.height() - dp(14f)) * 0.42f
        val cx = r.centerX()
        val cy = r.top + rad + dp(2f)
        stroke.strokeWidth = dp(5f)
        stroke.color = PANEL
        val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(oval, 135f, 270f, false, stroke)
        stroke.color = col
        val a0 = 135f + 135f
        val av = 135f + 270f * v
        c.drawArc(oval, min(a0, av), abs(av - a0).coerceAtLeast(2f), false, stroke)
        text.textSize = dp(10f); text.color = INK
        c.drawText(label, cx, r.bottom - dp(1f), text)
        ctls.add(Ctl(RectF(r), 1, key, v, null) { nv, _ ->
            var q = nv
            if (abs(q - 0.5f) < 0.02f) q = 0.5f
            local[key] = q; send(q)
        })
    }

    // ---------------------------------------------------------------- Oberflächen

    private fun deck(k: Int): JSONObject? = st()?.optJSONArray("decks")?.optJSONObject(k)

    private fun drawDecks(c: Canvas, area: RectF) {
        val tabH = dp(34f)
        // Pad-Modus-Reiter (für beide Decks)
        val names = arrayOf("CUES", "LOOP", "ROLL", "FX")
        val tw = (area.width() - 3 * dp(4f)) / 4f
        val mid = area.top + (area.height() - tabH) / 2f
        for (i in 0..3) button(c, RectF(area.left + i * (tw + dp(4f)), mid, area.left + i * (tw + dp(4f)) + tw, mid + tabH), names[i], padMode == i, MODE_COLS[i]) { if (it) padMode = i }
        deckPanel(c, 0, RectF(area.left, area.top, area.right, mid - dp(6f)))
        deckPanel(c, 1, RectF(area.left, mid + tabH + dp(6f), area.right, area.bottom))
    }

    private fun deckPanel(c: Canvas, k: Int, r: RectF) {
        val o = deck(k) ?: return
        val col = if (k == 0) COL_A else COL_B
        fill.color = PANEL
        c.drawRoundRect(r, dp(10f), dp(10f), fill)
        val ok = o.optBoolean("ok")
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(12f); text.color = col
        c.drawText("AB"[k].toString(), r.left + dp(8f), r.top + dp(16f), text)
        text.color = INK
        val info = if (ok) String.format(Locale.GERMANY, "%.1f · %s · −%s", o.optDouble("bpm"), o.optString("key"), Dj.fmt(o.optDouble("rem"))) else ""
        text.textAlign = Paint.Align.RIGHT
        c.drawText(info, r.right - dp(8f), r.top + dp(16f), text)
        text.textAlign = Paint.Align.LEFT
        c.drawText(fitLabel(text, if (ok) o.optString("t") else "kein Track", r.width() * 0.55f, dp(12f), dp(8f)), r.left + dp(24f), r.top + dp(16f), text)
        text.textAlign = Paint.Align.CENTER
        // Übersicht + Position (antippen = springen)
        val wv = RectF(r.left + dp(8f), r.top + dp(22f), r.right - dp(8f), r.top + dp(50f))
        fill.color = Color.rgb(12, 13, 17)
        c.drawRoundRect(wv, dp(5f), dp(5f), fill)
        val wave = Link.waves[k]
        val pos = o.optDouble("pos").toFloat()
        if (wave.isNotEmpty()) {
            stroke.strokeWidth = max(1f, wv.width() / wave.length - 0.5f)
            for (i in wave.indices) {
                val a = (wave[i] - '0') / 9f * wv.height() / 2f
                val x = wv.left + (i + 0.5f) * wv.width() / wave.length
                stroke.color = if (i.toFloat() / wave.length < pos) Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)) else col
                c.drawLine(x, wv.centerY() - a, x, wv.centerY() + a, stroke)
            }
        }
        stroke.color = Color.WHITE; stroke.strokeWidth = dp(2f)
        val px = wv.left + pos * wv.width()
        c.drawLine(px, wv.top, px, wv.bottom, stroke)
        ctls.add(Ctl(RectF(wv), 2, "seek$k", pos, null) { nv, _ -> Link.send("DK $k SEEK $nv") })
        val ph = o.optString("ph", "")
        if (ph.isNotEmpty()) { text.textSize = dp(9f); text.color = Color.rgb(255, 210, 60); text.textAlign = Paint.Align.LEFT; c.drawText("PHRASE $ph", wv.left + dp(4f), wv.bottom - dp(3f), text); text.textAlign = Paint.Align.CENTER }
        // Transport
        val by = wv.bottom + dp(6f)
        val bh = dp(40f)
        val bw = (r.width() - dp(16f) - 4 * dp(5f)) / 5f
        val labels = arrayOf(if (o.optBoolean("pl")) "‖ PAUSE" else "▶ PLAY", "CUE", "SYNC", "🎧", if (o.optBoolean("kl")) "🔒 KEY" else "KEY")
        val ons = booleanArrayOf(o.optBoolean("pl"), false, o.optBoolean("sy"), o.optBoolean("hp"), o.optBoolean("kl"))
        for (i in 0..4) {
            val br = RectF(r.left + dp(8f) + i * (bw + dp(5f)), by, r.left + dp(8f) + i * (bw + dp(5f)) + bw, by + bh)
            button(c, br, labels[i], ons[i], col) { down ->
                when (i) {
                    0 -> if (down) Link.send("DK $k PLAY")
                    1 -> Link.send("DK $k CUE ${if (down) "d" else "u"}")
                    2 -> if (down) Link.send("DK $k SYNC")
                    3 -> if (down) Link.send("DK $k HP")
                    else -> if (down) Link.send("DK $k KL")
                }
            }
        }
        // Pitch (waagerecht) + Bend
        val py = by + bh + dp(18f)
        val pr = RectF(r.left + dp(52f), py, r.right - dp(52f), py + dp(26f))
        hSlider(c, pr, "pitch$k", (o.optDouble("pt").toFloat() + 1f) / 2f, if (o.optBoolean("sy")) "PITCH (SYNC)" else "PITCH", col, 0.5f) { v -> Link.send("DK $k PITCH ${v * 2f - 1f}") }
        button(c, RectF(r.left + dp(8f), py, r.left + dp(46f), py + dp(26f)), "◀", false, col) { Link.send("DK $k BEND ${if (it) -0.04f else 0f}") }
        button(c, RectF(r.right - dp(46f), py, r.right - dp(8f), py + dp(26f)), "▶", false, col) { Link.send("DK $k BEND ${if (it) 0.04f else 0f}") }
        // Pads 4×2
        val top = py + dp(34f)
        val pg = dp(6f)
        val pw = (r.width() - dp(16f) - 3 * pg) / 4f
        val phh = (r.bottom - dp(8f) - top - pg) / 2f
        if (phh < dp(24f)) return
        val hot = o.optJSONArray("hot")
        for (i in 0..7) {
            val pr2 = RectF(r.left + dp(8f) + (i % 4) * (pw + pg), top + (i / 4) * (phh + pg), r.left + dp(8f) + (i % 4) * (pw + pg) + pw, top + (i / 4) * (phh + pg) + phh)
            val mc = MODE_COLS[padMode]
            when (padMode) {
                0 -> button(c, pr2, "CUE ${i + 1}", (hot?.optInt(i) ?: 0) == 1, HOT[i]) { if (it) Link.send("DK $k HOT $i") }
                1 -> {
                    val jb = o.optDouble("jb", 4.0).toFloat()
                    val (lbl, on) = when (i) {
                        0 -> "◀ JUMP" to false; 1 -> "JUMP ${if (jb >= 1f) jb.toInt().toString() else jb.toString()}T" to false; 2 -> "JUMP ▶" to false
                        3 -> "SLIP" to o.optBoolean("sl"); 4 -> "LOOP ½" to false; 5 -> "LOOP ${beats(o.optDouble("lb").toFloat())}" to o.optBoolean("lp")
                        6 -> "LOOP ×2" to false; else -> "LOOP ⟲" to false
                    }
                    button(c, pr2, lbl, on, mc) { down ->
                        if (!down) return@button
                        when (i) {
                            0 -> Link.send("DK $k JUMP ${-jb}"); 2 -> Link.send("DK $k JUMP $jb")
                            1 -> { val opts = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f); Link.send("DK $k JSIZE ${opts[(opts.indexOfFirst { it == jb } + 1).coerceAtLeast(0) % opts.size]}") }
                            3 -> Link.send("DK $k SLIP"); 4 -> Link.send("DK $k LOOPH"); 5 -> Link.send("DK $k LOOP"); 6 -> Link.send("DK $k LOOPD")
                            else -> Link.send("DK $k LOOP")
                        }
                    }
                }
                2 -> {
                    val rolls = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f)
                    if (i == 7) button(c, pr2, "SLIP", o.optBoolean("sl"), mc) { if (it) Link.send("DK $k SLIP") }
                    else button(c, pr2, "ROLL ${beats(rolls[i])}", o.optDouble("rb").toFloat() == rolls[i], mc, true) { Link.send("DK $k ROLL ${if (it) rolls[i] else 0f}") }
                }
                else -> {
                    val lbls = arrayOf("ECHO OUT", "BRAKE", "BACKSPIN", "CENSOR", "BUILD ↑", "SWEEP ↓", "FX STOP", "–")
                    val ons2 = booleanArrayOf(o.optBoolean("eo"), false, false, o.optBoolean("ce"), o.optInt("sw") == 1, o.optInt("sw") == 2, false, false)
                    val cmds = arrayOf("ECHO", "BRAKE", "SPIN", "CENSOR", "BUILD", "SWEEP", "FXSTOP", "")
                    button(c, pr2, lbls[i], ons2[i], mc, i == 3) { down ->
                        if (i == 3) Link.send("DK $k CENSOR ${if (down) 1 else 0}")
                        else if (down && cmds[i].isNotEmpty()) Link.send("DK $k ${cmds[i]}")
                    }
                }
            }
        }
    }

    private fun beats(b: Float): String = when (b) { 0.125f -> "1/32"; 0.25f -> "1/16"; 0.5f -> "1/8"; 1f -> "1/4"; 2f -> "1/2"; 4f -> "1T"; 8f -> "2T"; else -> if (b >= 4f) "${(b / 4).toInt()}T" else b.toString() }

    private fun drawMixer(c: Canvas, area: RectF) {
        val half = (area.width() - dp(10f)) / 2f
        for (k in 0..1) {
            val o = deck(k) ?: continue
            val col = if (k == 0) COL_A else COL_B
            val r = RectF(area.left + k * (half + dp(10f)), area.top, area.left + k * (half + dp(10f)) + half, area.bottom - dp(110f))
            fill.color = PANEL
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            text.textSize = dp(13f); text.color = col
            c.drawText("DECK ${"AB"[k]}", r.centerX(), r.top + dp(18f), text)
            val kh = min(dp(70f), (r.height() - dp(30f)) / 7f)
            val keys = arrayOf("GAIN", "HI", "MID", "LOW", "FILTER")
            val vals = floatArrayOf(o.optDouble("gn").toFloat(), o.optDouble("hi").toFloat(), o.optDouble("mi").toFloat(), o.optDouble("lo").toFloat(), o.optDouble("fl").toFloat())
            val cmds = arrayOf("GAIN", "HI", "MID", "LOW", "FILTER")
            val kx = r.left + dp(6f)
            val kw = r.width() * 0.48f
            for (i in 0..4) {
                val kr = RectF(kx, r.top + dp(26f) + i * (kh + dp(4f)), kx + kw, r.top + dp(26f) + i * (kh + dp(4f)) + kh)
                knob(c, kr, "${cmds[i]}$k", vals[i], keys[i], if (i == 4) Color.rgb(170, 120, 255) else col) { v -> Link.send("DK $k ${cmds[i]} $v") }
            }
            val fr = RectF(r.right - r.width() * 0.42f, r.top + dp(30f), r.right - dp(10f), r.bottom - dp(64f))
            vFader(c, fr, "fader$k", o.optDouble("fd").toFloat(), "LINE", col, o.optInt("lv") / 100f) { v -> Link.send("DK $k FADER $v") }
            button(c, RectF(fr.left, r.bottom - dp(44f), fr.right, r.bottom - dp(8f)), "🎧", o.optBoolean("hp"), col) { if (it) Link.send("DK $k HP") }
        }
        val xr = RectF(area.left + dp(20f), area.bottom - dp(92f), area.right - dp(20f), area.bottom - dp(52f))
        hSlider(c, xr, "xf", (st()?.optDouble("xf") ?: 0.5).toFloat(), "CROSSFADER", ACC, 0.5f) { v -> Link.send("XFADE $v") }
        val vr = RectF(area.left + dp(20f), area.bottom - dp(30f), area.right - dp(20f), area.bottom - dp(2f))
        hSlider(c, vr, "vol", (st()?.optDouble("vol") ?: 0.8).toFloat(), "MASTER", Color.rgb(200, 200, 210)) { v -> Link.send("VOL $v") }
    }

    private val padState = arrayOf("leer", "● REC", "▶", "■", "OVERDUB", "⚡")

    private fun drawPads(c: Canvas, area: RectF, transport: Boolean) {
        val pads = st()?.optJSONArray("pads") ?: return
        var bottom = area.bottom
        if (transport) {
            val th = dp(48f)
            val tw = (area.width() - 3 * dp(6f)) / 4f
            val play = st()?.optBoolean("play") == true
            val rec = st()?.optBoolean("rec") == true
            val ty = area.bottom - th
            button(c, RectF(area.left, ty, area.left + tw, area.bottom), if (play) "■ STOP" else "▶ PLAY", play, Color.rgb(60, 200, 120)) { if (it) Link.send("T PLAY") }
            button(c, RectF(area.left + tw + dp(6f), ty, area.left + 2 * tw + dp(6f), area.bottom), "● REC", rec, Color.rgb(230, 50, 60)) { if (it) Link.send("T REC") }
            button(c, RectF(area.left + 2 * (tw + dp(6f)), ty, area.left + 3 * tw + 2 * dp(6f), area.bottom), "↶ UNDO", false, ACC) { if (it) Link.send("T UNDO") }
            button(c, RectF(area.left + 3 * (tw + dp(6f)), ty, area.right, area.bottom), "ANSCHLAG ${TouchVelocity.MODES[TouchVelocity.mode]}", TouchVelocity.mode != 0, ACC) { if (it) { TouchVelocity.mode = (TouchVelocity.mode + 1) % 3; TouchVelocity.update(context) } }
            bottom = ty - dp(10f)
        }
        val cols = if (transport) 3 else 4
        val rows = 12 / cols
        val g = dp(8f)
        val pw = (area.width() - (cols - 1) * g) / cols
        val ph = (bottom - area.top - (rows - 1) * g) / rows
        for (p in 0 until 12) {
            val a = pads.optJSONArray(p) ?: continue
            val r = RectF(area.left + (p % cols) * (pw + g), area.top + (p / cols) * (ph + g), area.left + (p % cols) * (pw + g) + pw, area.top + (p / cols) * (ph + g) + ph)
            val stt = a.optInt(0)
            val lvl = a.optInt(1) / 100f
            val name = a.optString(2)
            val on = stt == Engine.PadState.PLAYING.ordinal || stt == Engine.PadState.RECORDING.ordinal || stt == Engine.PadState.OVERDUB.ordinal
            val held = drags.values.any { it.c.key == "pad$p" }
            val col = when (stt) {
                Engine.PadState.RECORDING.ordinal -> Color.rgb(230, 50, 60)
                Engine.PadState.OVERDUB.ordinal -> Color.rgb(240, 190, 40)
                Engine.PadState.EMPTY.ordinal -> Color.rgb(60, 62, 70)
                Engine.PadState.ONESHOT.ordinal -> Color.rgb(255, 150, 40)
                else -> Color.rgb(60, 200, 120)
            }
            fill.color = if (held) lighten(col) else if (on || lvl > 0.05f) col else Color.argb(90, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(r, dp(12f), dp(12f), fill)
            if (lvl > 0.02f) {
                stroke.color = Color.WHITE; stroke.strokeWidth = dp(1f + lvl * 4f)
                c.drawRoundRect(r, dp(12f), dp(12f), stroke)
            }
            text.color = Color.WHITE
            text.textSize = min(dp(22f), ph * 0.3f)
            c.drawText("${p + 1}", r.centerX(), r.centerY(), text)
            text.textSize = min(dp(11f), ph * 0.15f)
            c.drawText(fitLabel(text, if (name.isNotEmpty()) name else padState.getOrElse(stt) { "" }, r.width() - dp(8f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 1.6f, text)
            ctls.add(Ctl(RectF(r), 5, "pad$p", 0f, null))
        }
    }

    private fun drawFx(c: Canvas, area: RectF) {
        val s = st() ?: return
        val bh = dp(44f)
        button(c, RectF(area.left, area.top, area.left + dp(60f), area.top + bh), "◀", false, ACC) { if (it) Link.send("FX -1") }
        button(c, RectF(area.right - dp(60f), area.top, area.right, area.top + bh), "▶", false, ACC) { if (it) Link.send("FX 1") }
        text.textSize = dp(16f); text.color = INK
        c.drawText(s.optString("fx"), area.centerX(), area.top + bh / 2 + dp(6f), text)
        val vr = RectF(area.left + dp(10f), area.bottom - dp(30f), area.right - dp(10f), area.bottom - dp(2f))
        hSlider(c, vr, "vol", s.optDouble("vol", 0.8).toFloat(), "MASTER", Color.rgb(200, 200, 210)) { v -> Link.send("VOL $v") }
        val xy = RectF(area.left, area.top + bh + dp(10f), area.right, vr.top - dp(22f))
        fill.color = PANEL
        c.drawRoundRect(xy, dp(14f), dp(14f), fill)
        stroke.color = Color.argb(50, 255, 255, 255); stroke.strokeWidth = dp(1f)
        for (i in 1..3) {
            c.drawLine(xy.left + xy.width() * i / 4, xy.top, xy.left + xy.width() * i / 4, xy.bottom, stroke)
            c.drawLine(xy.left, xy.top + xy.height() * i / 4, xy.right, xy.top + xy.height() * i / 4, stroke)
        }
        val fx = local["xyx"] ?: s.optDouble("fxX").toFloat()
        val fy = local["xyy"] ?: s.optDouble("fxY").toFloat()
        val touch = s.optBoolean("fxT") || drags.values.any { it.c.kind == 4 }
        fill.color = if (touch) ACC else Color.argb(120, 255, 106, 16)
        c.drawCircle(xy.left + fx * xy.width(), xy.bottom - fy * xy.height(), dp(if (touch) 26f else 16f), fill)
        text.textSize = dp(11f); text.color = DIM
        c.drawText("Finger auf die Fläche = Effekt an · loslassen = aus", xy.centerX(), xy.bottom - dp(10f), text)
        ctls.add(Ctl(RectF(xy), 4, "xy", 0f, null) { x, y -> local["xyx"] = x; local["xyy"] = y; Link.send("XY $x $y 1") })
    }

    private fun drawPadMixer(c: Canvas, area: RectF) {
        val s = st() ?: return
        val pads = s.optJSONArray("pads") ?: return
        val g = dp(6f)
        val rowH = (area.height() - dp(60f) - g) / 2f
        val sw = (area.width() - 5 * g) / 6f
        for (p in 0 until 12) {
            val a = pads.optJSONArray(p) ?: continue
            val r = RectF(area.left + (p % 6) * (sw + g), area.top + (p / 6) * (rowH + g), area.left + (p % 6) * (sw + g) + sw, area.top + (p / 6) * (rowH + g) + rowH)
            fill.color = PANEL
            c.drawRoundRect(r, dp(8f), dp(8f), fill)
            text.textSize = dp(12f); text.color = INK
            c.drawText("${p + 1}", r.centerX(), r.top + dp(14f), text)
            val bh = dp(30f)
            val fr = RectF(r.left + dp(4f), r.top + dp(20f), r.right - dp(4f), r.bottom - 2 * bh - dp(22f))
            vFader(c, fr, "pg$p", a.optInt(5) / 150f, "", ACC, a.optInt(1) / 100f) { v -> Link.send("PGAIN $p ${v * 1.5f}") }
            button(c, RectF(r.left + dp(4f), r.bottom - 2 * bh - dp(6f), r.right - dp(4f), r.bottom - bh - dp(8f)), "S", a.optInt(4) == 1, Color.rgb(255, 200, 40)) { if (it) Link.send("SOLO $p") }
            button(c, RectF(r.left + dp(4f), r.bottom - bh - dp(4f), r.right - dp(4f), r.bottom - dp(4f)), "M", a.optInt(3) == 1, Color.rgb(90, 160, 255)) { if (it) Link.send("MUTE $p") }
        }
        val xa = s.optInt("pxA", -1)
        val xb = s.optInt("pxB", -1)
        val xr = RectF(area.left + dp(10f), area.bottom - dp(34f), area.right - dp(10f), area.bottom - dp(4f))
        hSlider(c, xr, "pxf", s.optDouble("pxP", 0.5).toFloat(), if (xa >= 0 && xb >= 0) "CROSSFADER Pad ${xa + 1} ↔ Pad ${xb + 1}" else "CROSSFADER (am Host A/B im Mischpult wählen)", ACC, 0.5f) { v -> Link.send("PXF $v") }
    }

    // ---------------------------------------------------------------- Touch

    private fun hitCtl(x: Float, y: Float): Ctl? {
        for (i in ctls.indices.reversed()) {
            val c = ctls[i]
            if (x >= c.r.left - dp(3f) && x <= c.r.right + dp(3f) && y >= c.r.top - dp(3f) && y <= c.r.bottom + dp(3f)) return c
        }
        return null
    }

    private fun valueAt(c: Ctl, x: Float, y: Float): Pair<Float, Float> = when (c.kind) {
        2 -> ((x - c.r.left - dp(8f)) / (c.r.width() - dp(16f))).coerceIn(0f, 1f) to 0f
        3 -> ((c.r.bottom - dp(10f) - y) / (c.r.height() - dp(20f))).coerceIn(0f, 1f) to 0f
        4 -> ((x - c.r.left) / c.r.width()).coerceIn(0f, 1f) to ((c.r.bottom - y) / c.r.height()).coerceIn(0f, 1f)
        else -> 0f to 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                val c = hitCtl(x, y) ?: return true
                when (c.kind) {
                    0 -> { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); drags[id] = Drag(c, y, 0f, 0f); c.action?.invoke(true) }
                    5 -> {
                        val p = c.key.removePrefix("pad").toInt()
                        val tNs = TouchVelocity.eventNanos(e)
                        val vel = TouchVelocity.of(e, i, y, c.r.top, c.r.bottom, tNs)
                        Link.send("PAD $p d $vel $tNs")
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        drags[id] = Drag(c, y, 0f, 0f)
                    }
                    1 -> drags[id] = Drag(c, y, c.value, c.value)
                    else -> {
                        val (v, v2) = valueAt(c, x, y)
                        drags[id] = Drag(c, y, v, v, v2)
                        c.onValue?.invoke(v, v2)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val dr = drags[e.getPointerId(i)] ?: continue
                val x = e.getX(i)
                val y = e.getY(i)
                when (dr.c.kind) {
                    1 -> {
                        val nv = (dr.startV + (dr.startY - y) / dp(160f)).coerceIn(0f, 1f)
                        if (abs(nv - dr.v) > 0.003f) { dr.v = nv; dr.c.onValue?.invoke(nv, 0f) }
                    }
                    2, 3, 4 -> {
                        // neu einsammeln (Rechteck stammt aus dem letzten Bild)
                        val (v, v2) = valueAt(dr.c, x, y)
                        if (abs(v - dr.v) > 0.003f || abs(v2 - dr.v2) > 0.003f) { dr.v = v; dr.v2 = v2; dr.c.onValue?.invoke(v, v2) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) drags.keys.toList() else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    val dr = drags.remove(id) ?: continue
                    when (dr.c.kind) {
                        0 -> dr.c.action?.invoke(false)
                        5 -> Link.send("PAD ${dr.c.key.removePrefix("pad")} u")
                        4 -> { Link.send("XY ${dr.v} ${dr.v2} 0"); local.remove("xyx"); local.remove("xyy") }
                        else -> local.remove(dr.c.key)
                    }
                }
            }
        }
        return true
    }
}
