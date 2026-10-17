package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DJ-Modus als Hardware-Controller (Hochformat): oben das Display mit beiden Decks,
 * 4 Regler (EQ/Filter), 3 Taster, Pitch- und Kanal-Fader links/rechts, XY-Effektfläche in der Mitte,
 * Crossfader und 18 Pads (Hot Cues, Beat-Jump, Loops – oder Sampler).
 * 18 Designs als Hardware-Oberflächen (Stahl, Holz, Glas, Neon …).
 */
class DjView(context: Context) : View(context) {

    // ================================================================ Designs

    class Skin(
        val name: String,
        val bg: Int,
        val top: Int,
        val bot: Int,
        /** 0 glatt, 1 gebürstet, 2 Holz quer, 3 Kunststoff (gealtert), 4 Glas, 5 matt, 6 Leder, 7 Holz längs */
        val tex: Int,
        /** 0 keine, 1 Holzrahmen rundum, 2 Holzwangen links/rechts, 3 Metallkante */
        val frame: Int,
        val screws: Boolean,
        /** 0 Metall, 1 Kunststoff farbig, 2 weiß mit Strich, 3 schwarz gerändelt, 4 Glas, 5 Neon-Ring, 6 Holz mit Metallring, 7 Messing/Holz, 8 Leucht-Kontur, 9 Creme gerändelt */
        val knob: Int,
        val knobCols: IntArray,
        /** 0 Metall eckig, 1 leuchtend eckig, 2 Metall rund, 3 Kunststoff rund, 4 Glas-Ring, 5 Neon rund, 6 Kontur, 7 Holz/Messing rund, 8 schwarz rund */
        val btn: Int,
        val btnCols: IntArray,
        /** 0 Metall, 1 farbig leuchtend, 2 schwarz Gummi, 3 weiß weich, 4 dunkel mit Glimmen, 5 Creme, 6 Milchglas, 7 Neon, 8 dunkel matt, 9 Pastell, 10 schwarz mit Leuchtpunkt, 11 Kontur, 12 Leinen im Holzrahmen */
        val pad: Int,
        val padCols: IntArray,
        val glow: Int,
        /** 0 Metall, 1 farbige Kappen, 2 weiß, 3 schwarz, 4 Glas, 5 Neon-Linie, 6 Messing rund, 7 Kontur, 8 Creme */
        val fader: Int,
        /** Hintergrundplatte für Fader: 0 keine, 1 Metall, 2 Holz, 3 Skala */
        val plate: Int,
        /** 0 Metall-Fadenkreuz, 1 Display cyan, 2 weiß Plus, 3 dunkel Raster, 4 Creme graviert, 5 Glas, 6 Neon-Stern, 7 schwarz mit Stern, 8 Leinen, 9 Raster orange, 10 Metall-Raster orange, 11 hell Raster */
        val xy: Int,
        val ink: Int,
        val accent: Int,
        val colA: Int,
        val colB: Int,
        val dispBg: Int,
        val dispInk: Int,
        val dark: Boolean
    )

    companion object {
        private fun c(h: Long): Int = (0xFF000000L or h).toInt()
        val SKINS: Array<Skin> = arrayOf(
            Skin("STAHL – gebürstet", c(0x0b0b0b), c(0xcfd1d3), c(0xa7a9ab), 1, 0, false, 0, intArrayOf(), 0, intArrayOf(), 0, intArrayOf(c(0xc4c6c8)), c(0xff6a10), 0, 0, 0,
                c(0x26282b), c(0xff6a10), c(0x2f80ff), c(0xff6a10), c(0x111316), c(0x9fe8ff), false),
            Skin("FARBE – Akai-Stil", c(0x0a0a0a), c(0x2e2f33), c(0x232427), 5, 0, false, 1, intArrayOf(c(0xe0402f), c(0x2f8fd8), c(0xf2c230), c(0xf06a25)), 1, intArrayOf(c(0x29e6f0), c(0xf040e0), c(0xe8f040)), 1,
                intArrayOf(c(0xf0402f), c(0x2f8fe8), c(0xf47a20), c(0xf6d23a), c(0x2fe0e0)), c(0xffffff), 1, 0, 1, c(0xe8e8e8), c(0x29e6f0), c(0x2f8fd8), c(0xf06a25), c(0x061018), c(0x40e8ff), true),
            Skin("STAHL – schwarze Pads", c(0x2a2a2a), c(0xd9dbdd), c(0xb9bbbd), 1, 3, true, 0, intArrayOf(), 2, intArrayOf(), 2, intArrayOf(c(0x111111)), c(0xff6a10), 0, 1, 0,
                c(0x2a2c2f), c(0xff6a10), c(0x2f80ff), c(0xff6a10), c(0x111316), c(0x9fe8ff), false),
            Skin("WEISS – minimal", c(0xe6e6e6), c(0xf7f7f7), c(0xeeeeee), 0, 0, false, 2, intArrayOf(), 3, intArrayOf(), 3, intArrayOf(c(0xf3f3f3)), c(0x9ab8ff), 2, 0, 2,
                c(0x6d6f73), c(0x4a7dff), c(0x4a7dff), c(0xff7a3a), c(0xf0f1f3), c(0x3a3d44), false),
            Skin("WALNUSS – blau glimmend", c(0x141010), c(0x6e4128), c(0x4e2c1a), 2, 1, false, 3, intArrayOf(), 8, intArrayOf(), 4, intArrayOf(c(0x16191d)), c(0x3fa8ff), 3, 0, 3,
                c(0xf0e2d0), c(0x3fa8ff), c(0x3fa8ff), c(0xffa040), c(0x0d1013), c(0x8fd4ff), true),
            Skin("RETRO – Creme 80er", c(0xe9e8e4), c(0xece3c9), c(0xe0d5b8), 3, 0, false, 9, intArrayOf(), 3, intArrayOf(), 5, intArrayOf(c(0xece2c6)), c(0xe0a050), 8, 3, 4,
                c(0x4a4232), c(0xd06a20), c(0x3a7ab0), c(0xd06a20), c(0x262a22), c(0xb8f070), false),
            Skin("WALNUSS – Holzknöpfe", c(0x3a3a3a), c(0x6b4129), c(0x55311d), 7, 0, false, 6, intArrayOf(), 8, intArrayOf(), 2, intArrayOf(c(0x1a1a1a)), c(0xff8a30), 3, 2, 3,
                c(0xf0e2d0), c(0xff8a30), c(0x48a8ff), c(0xff8a30), c(0x111214), c(0xffc890), true),
            Skin("GLAS – Milchglas", c(0x050608), c(0xd3d6da), c(0xb4b8bd), 4, 0, false, 4, intArrayOf(), 4, intArrayOf(), 6, intArrayOf(c(0xf2f3f5)), c(0xffffff), 4, 0, 5,
                c(0x3d4249), c(0x5a8cff), c(0x5a8cff), c(0xff7d45), c(0xe9ecef), c(0x2c3440), false),
            Skin("PORZELLAN – weiß verschraubt", c(0xf1f1f1), c(0xf8f8f8), c(0xececec), 0, 3, true, 2, intArrayOf(), 3, intArrayOf(), 3, intArrayOf(c(0xfafafa)), c(0xbfd4ff), 2, 0, 2,
                c(0x5f6268), c(0x4a7dff), c(0x4a7dff), c(0xff7a3a), c(0xf2f3f5), c(0x3a3d44), false),
            Skin("NEON – Leuchtringe", c(0x050507), c(0x101014), c(0x08080a), 5, 0, false, 5, intArrayOf(c(0xff2a3a), c(0x2a6aff), c(0x2aff6a), c(0xff7a1a)), 5, intArrayOf(c(0xb04aff), c(0xff4ad0), c(0xf0ff3a)), 7,
                intArrayOf(c(0xff2a3a), c(0x3aff5a), c(0x3a6aff), c(0xff3a3a), c(0xd6ff3a), c(0xb04aff), c(0xff8a2a), c(0x2ae0ff), c(0xff4ad0)), c(0x2ae0ff), 5, 0, 6,
                c(0xe8f8ff), c(0x2ae0ff), c(0x2a8aff), c(0xff4ad0), c(0x05080c), c(0x2ae0ff), true),
            Skin("MATT SCHWARZ – Studio", c(0x0e0e0e), c(0x1c1c1d), c(0x141415), 5, 3, true, 3, intArrayOf(), 8, intArrayOf(), 8, intArrayOf(c(0x1c1c1e)), c(0x5aa0ff), 3, 3, 7,
                c(0xd0d4da), c(0x5aa0ff), c(0x5aa0ff), c(0xff8a3a), c(0x07090c), c(0x8cc4ff), true),
            Skin("CREME – braune Knöpfe", c(0xece6da), c(0xf0e9d6), c(0xe6dcc4), 3, 0, false, 1, intArrayOf(c(0x6b3d24), c(0x6b3d24), c(0x6b3d24), c(0x6b3d24)), 8, intArrayOf(), 5, intArrayOf(c(0xddd2ba)), c(0xc89060), 3, 0, 11,
                c(0x4a3a2a), c(0xb0602a), c(0x6b3d24), c(0xc06a2a), c(0x2c2a24), c(0xf0d8a8), false),
            Skin("PASTELL – Bonbon", c(0xf2f2f2), c(0xfbfbfb), c(0xf1f1f1), 0, 0, false, 2, intArrayOf(), 3, intArrayOf(), 9,
                intArrayOf(c(0xcfe9dc), c(0xf6d3c8), c(0xc9dbe6), c(0xddd0ec), c(0xf3ebc0), c(0xf6d3dc), c(0xdfe9cf), c(0xece3d0), c(0xf6c8c4)), c(0xffffff), 2, 0, 11,
                c(0x75787d), c(0xe88aa0), c(0x7ab8d8), c(0xe8a07a), c(0xf6f6f8), c(0x55585e), false),
            Skin("STAHL – orange Glut", c(0x111111), c(0xcfd1d3), c(0x9fa1a3), 1, 3, false, 0, intArrayOf(), 2, intArrayOf(), 10, intArrayOf(c(0x1a1716)), c(0xff7a1a), 0, 1, 10,
                c(0x26282b), c(0xff7a1a), c(0x2f80ff), c(0xff7a1a), c(0x120c08), c(0xffa040), false),
            Skin("GRAPHIT – dunkel", c(0x0b0b0b), c(0x2f3033), c(0x252629), 5, 0, false, 3, intArrayOf(), 8, intArrayOf(), 8, intArrayOf(c(0x2b2c2f)), c(0x7aa8ff), 3, 0, 7,
                c(0xd8dade), c(0x7aa8ff), c(0x5a9cff), c(0xff8a3a), c(0x0a0c10), c(0x9cc8ff), true),
            Skin("MARV – orange Kontur", c(0x0b0c10), c(0x15171c), c(0x101216), 5, 0, false, 8, intArrayOf(), 6, intArrayOf(), 11, intArrayOf(c(0x15171c)), c(0xff6a10), 7, 0, 9,
                c(0xffd8c0), c(0xff6a10), c(0xff6a10), c(0xffb070), c(0x0d0f13), c(0xff8a40), true),
            Skin("MESSING – Leder, Walnuss & Leinen", c(0x2b2b2b), c(0x343434), c(0x2a2a2a), 6, 2, false, 7, intArrayOf(), 7, intArrayOf(), 12, intArrayOf(c(0xeee6d4)), c(0xe8c070), 6, 2, 8,
                c(0xeee6d4), c(0xe8b050), c(0xe8b050), c(0xc8743a), c(0x1a1712), c(0xf0d090), true),
            Skin("GLAS – blaues Licht", c(0x0a0f18), c(0xd8dde3), c(0xb9c0c8), 4, 0, false, 4, intArrayOf(c(0x4a9cff)), 4, intArrayOf(), 6, intArrayOf(c(0xe8f2ff)), c(0x4aa8ff), 4, 0, 5,
                c(0x37404c), c(0x3a8cff), c(0x3a8cff), c(0xff7d45), c(0xe4ebf2), c(0x1e3a60), false)
        )
    }

    var skinIdx = 15
        set(v) {
            field = v.coerceIn(0, SKINS.size - 1)
            texture = null
            invalidate()
        }
    private val skin: Skin get() = SKINS[skinIdx]

    // ================================================================ Rückrufe
    var onLoad: ((Int) -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onTempo: (() -> Unit)? = null
    var onPadsMode: (() -> Unit)? = null
    var onHint: ((String) -> Unit)? = null
    var onSkinMenu: (() -> Unit)? = null

    // ================================================================ Zustand der Oberfläche
    /** Fokus-Deck für die 4 Regler und die XY-Fläche. */
    private var focus = 0
    /** Pads: 0 = Hot Cues/Loops, 1 = Sampler (Pads der App). */
    private var padMode = 0
    private var xyHold = false
    private var xyX = 0.5f
    private var xyY = 0f
    private var xyActive = false
    private val tapTimes = ArrayList<Long>()

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logo: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.logo) } catch (e: Throwable) { null }
    private var texture: Bitmap? = null
    private var texW = 0
    private var texH = 0
    private val tmp = RectF()
    private val path = Path()

    // ================================================================ Layout
    private var insetTop = 0f
    private var insetBottom = 0f
    private val panel = RectF()
    private val header = RectF()
    private val bpmChip = RectF()
    private val padsBtn = RectF()
    private val menuBtn = RectF()
    private val display = RectF()
    private val lanes = arrayOf(RectF(), RectF())
    private val overview = arrayOf(RectF(), RectF())
    private val knobs = Array(4) { RectF() }
    private val tops = Array(3) { RectF() }
    private val pitch = arrayOf(RectF(), RectF())
    private val vol = arrayOf(RectF(), RectF())
    /** je Deck: PLAY, CUE, SYNC, 🎧 */
    private val side = Array(2) { Array(4) { RectF() } }
    private val xy = RectF()
    private val xfader = RectF()
    private val pads = Array(18) { RectF() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = i.top.toFloat()
            insetBottom = i.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            insetTop = insets.systemWindowInsetTop.toFloat()
            @Suppress("DEPRECATION")
            insetBottom = insets.systemWindowInsetBottom.toFloat()
        }
        layoutAll()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutAll()
        texture = null
    }

    private fun layoutAll() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val m = dp(10f)
        val fr = when (skin.frame) { 1 -> dp(12f); 2 -> dp(18f); else -> 0f }
        panel.set(fr + dp(4f), insetTop + dp(4f), w - fr - dp(4f), h - insetBottom - dp(4f))
        val L = panel.left + m
        val R = panel.right - m
        var y = panel.top + dp(6f)
        header.set(L, y, R, y + dp(40f))
        menuBtn.set(R - dp(40f), y + dp(4f), R, y + dp(36f))
        padsBtn.set(menuBtn.left - dp(78f), y + dp(4f), menuBtn.left - dp(6f), y + dp(36f))
        bpmChip.set(padsBtn.left - dp(96f), y + dp(4f), padsBtn.left - dp(6f), y + dp(36f))
        y = header.bottom + dp(6f)
        val avail = panel.bottom - m - y
        // Höhenaufteilung (passt sich an kleine Displays an)
        val dispH = max(dp(120f), avail * 0.20f)
        display.set(L, y, R, y + dispH)
        val lh = (display.height() - dp(12f)) / 2f
        for (k in 0..1) {
            val t = display.top + dp(4f) + k * (lh + dp(4f))
            lanes[k].set(display.left + dp(6f), t, display.right - dp(6f), t + lh)
            overview[k].set(lanes[k].left, lanes[k].bottom - dp(7f), lanes[k].right, lanes[k].bottom - dp(1f))
        }
        y = display.bottom + dp(8f)
        val kh = min(dp(66f), avail * 0.11f)
        val kw = (R - L) / 4f
        for (i in 0..3) knobs[i].set(L + i * kw, y, L + (i + 1) * kw, y + kh + dp(12f))
        y += kh + dp(16f)
        val th = dp(28f)
        val tw = dp(64f)
        val tg = dp(14f)
        val tx0 = (L + R) / 2f - (3 * tw + 2 * tg) / 2f
        for (i in 0..2) tops[i].set(tx0 + i * (tw + tg), y, tx0 + i * (tw + tg) + tw, y + th)
        y += th + dp(10f)
        val midH = max(dp(150f), avail * 0.27f)
        val pw = dp(30f)
        val vw = dp(36f)
        val bw = dp(44f)
        val g = dp(5f)
        pitch[0].set(L, y, L + pw, y + midH)
        vol[0].set(pitch[0].right + g, y, pitch[0].right + g + vw, y + midH)
        pitch[1].set(R - pw, y, R, y + midH)
        vol[1].set(pitch[1].left - g - vw, y, pitch[1].left - g, y + midH)
        val sbH = (midH - 3 * dp(6f)) / 4f
        for (k in 0..1) {
            val x0 = if (k == 0) vol[0].right + g else vol[1].left - g - bw
            for (i in 0..3) side[k][i].set(x0, y + i * (sbH + dp(6f)), x0 + bw, y + i * (sbH + dp(6f)) + sbH)
        }
        val xl = side[0][0].right + dp(10f)
        val xr = side[1][0].left - dp(10f)
        val xs = min(xr - xl, midH)
        xy.set((xl + xr) / 2f - xs / 2f, y + (midH - xs) / 2f, (xl + xr) / 2f + xs / 2f, y + (midH + xs) / 2f)
        y += midH + dp(10f)
        val xh = dp(40f)
        xfader.set(L + (R - L) * 0.12f, y, R - (R - L) * 0.12f, y + xh)
        y += xh + dp(10f)
        val pb = panel.bottom - m
        val rows = 3
        val cols = 6
        val pg = dp(7f)
        val phh = (pb - y - (rows - 1) * pg) / rows
        val pww = (R - L - (cols - 1) * pg) / cols
        for (r in 0 until rows) for (cI in 0 until cols) {
            val x0 = L + cI * (pww + pg)
            val y0 = y + r * (phh + pg)
            pads[r * cols + cI].set(x0, y0, x0 + pww, y0 + phh)
        }
    }

    // ================================================================ Textur (gebürstet, Holz, Kunststoff …)

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun vnoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val e = hash(xi + 1, yi + 1)
        return (a + (b - a) * sx) + ((c + (e - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun fbm(x: Float, y: Float): Float = vnoise(x, y) * 0.55f + vnoise(x * 2.1f, y * 2.1f) * 0.28f + vnoise(x * 4.3f, y * 4.3f) * 0.17f

    private fun buildTexture(w: Int, h: Int): Bitmap {
        val s = skin
        val tw = max(8, w / 2)
        val th = max(8, h / 2)
        val px = IntArray(tw * th)
        val woodDark = Color.rgb(70, 38, 22)
        val woodLight = Color.rgb(142, 90, 56)
        for (y in 0 until th) {
            val fy = y.toFloat() / th
            val base = blend(s.top, s.bot, fy)
            val br = Color.red(base); val bg = Color.green(base); val bb = Color.blue(base)
            val rowN = (hash(7, y) - 0.5f)
            for (x in 0 until tw) {
                var r = br.toFloat(); var g = bg.toFloat(); var b = bb.toFloat()
                when (s.tex) {
                    1 -> { // gebürstet: waagrechte Riefen
                        val n = rowN * 14f + (vnoise(x / 90f, y * 1.7f) - 0.5f) * 16f + (hash(x, y) - 0.5f) * 5f
                        r += n; g += n; b += n
                    }
                    2, 7 -> { // Holz
                        val u = if (s.tex == 2) x.toFloat() else y.toFloat()
                        val v = if (s.tex == 2) y.toFloat() else x.toFloat()
                        val warp = fbm(u / 160f, v / 60f) * 9f
                        val ring = sin((v / 9f + warp * 2.6f).toDouble()).toFloat() * 0.5f + 0.5f
                        val fine = (hash((u / 3).toInt(), v.toInt()) - 0.5f) * 0.12f
                        val t = (0.45f + ring * 0.4f + (fbm(u / 40f, v / 300f) - 0.5f) * 0.5f + fine).coerceIn(0f, 1f)
                        val wc = blend(woodDark, woodLight, t)
                        val mix = blend(wc, base, 0.25f)
                        r = Color.red(mix).toFloat(); g = Color.green(mix).toFloat(); b = Color.blue(mix).toFloat()
                    }
                    3 -> { // Kunststoff, leicht vergilbt mit Sprenkeln
                        val n = (fbm(x / 70f, y / 70f) - 0.5f) * 10f + (hash(x, y) - 0.5f) * 4f
                        r += n; g += n * 0.95f; b += n * 0.8f
                        if (hash(x * 3, y * 5) > 0.9985f) { r -= 50f; g -= 50f; b -= 45f }
                    }
                    4 -> { // Milchglas
                        val n = (fbm(x / 120f, y / 120f) - 0.5f) * 10f + (hash(x, y) - 0.5f) * 3f
                        val vig = 1f - 0.12f * ((x - tw / 2f) * (x - tw / 2f) / (tw * tw / 4f))
                        r = (r + n) * vig; g = (g + n) * vig; b = (b + n + 4f) * vig
                    }
                    5 -> { // matt
                        val n = (hash(x, y) - 0.5f) * 6f + (fbm(x / 120f, y / 120f) - 0.5f) * 6f
                        r += n; g += n; b += n
                    }
                    6 -> { // Leder
                        val n = (fbm(x / 6f, y / 6f) - 0.5f) * 14f + (fbm(x / 60f, y / 60f) - 0.5f) * 8f
                        r += n; g += n; b += n
                    }
                    else -> {
                        val n = (hash(x, y) - 0.5f) * 2f
                        r += n; g += n; b += n
                    }
                }
                px[y * tw + x] = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
    }

    private fun woodPaintRect(c: Canvas, r: RectF, vertical: Boolean, rad: Float) {
        val tw = max(4, (r.width() / 2).toInt())
        val th = max(4, (r.height() / 2).toInt())
        val key = "${tw}x$th$vertical"
        var bm = woodCache[key]
        if (bm == null) {
            val px = IntArray(tw * th)
            for (y in 0 until th) for (x in 0 until tw) {
                val u = if (vertical) x.toFloat() else y.toFloat()
                val v = if (vertical) y.toFloat() else x.toFloat()
                val warp = fbm(u / 50f, v / 120f) * 6f
                val ring = sin((u / 5f + warp * 2.2f).toDouble()).toFloat() * 0.5f + 0.5f
                val t = (0.4f + ring * 0.35f + (hash(u.toInt(), (v / 4).toInt()) - 0.5f) * 0.1f).coerceIn(0f, 1f)
                px[y * tw + x] = blend(Color.rgb(84, 46, 26), Color.rgb(150, 92, 54), t)
            }
            bm = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
            woodCache[key] = bm
        }
        c.save()
        path.reset()
        path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        c.drawBitmap(bm, null, r, bmpPaint)
        c.restore()
    }

    private val woodCache = HashMap<String, Bitmap>()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ================================================================ Zeichnen

    override fun onDraw(c: Canvas) {
        if (panel.width() <= 0f) layoutAll()
        val s = skin
        c.drawColor(s.bg)
        drawBody(c)
        drawHeader(c)
        drawDisplay(c)
        val focusCol = if (focus == 0) s.colA else s.colB
        val dk = Dj.decks[focus]
        val kv = floatArrayOf(dk.hi, dk.mid, dk.lo, dk.filter)
        val kl = arrayOf("HI", "MID", "LOW", "FILTER")
        for (i in 0..3) drawKnob(c, knobs[i], i, kv[i], kl[i] + " " + "AB"[focus], focusCol)
        val fxName = Fx.NAMES[dk.fxType.coerceIn(0, Fx.COUNT - 1)]
        drawTop(c, tops[0], 0, "DECK " + "AB"[focus], true, focusCol)
        drawTop(c, tops[1], 1, fxName, dk.fxAmt > 0.02f, s.accent)
        drawTop(c, tops[2], 2, if (padMode == 0) "CUES" else "SAMPLER", padMode == 1, s.accent)
        for (k in 0..1) {
            val dd = Dj.decks[k]
            val col = if (k == 0) s.colA else s.colB
            drawFader(c, pitch[k], 0.5f - dd.pitch * 0.5f, col, true, k)
            drawFader(c, vol[k], dd.fader, col, false, k)
            val blink = !dd.playing && dd.loaded() && (SystemClock.uptimeMillis() / 400) % 2 == 0L
            drawSide(c, side[k][0], "▶ ‖", dd.playing || blink, col)
            drawSide(c, side[k][1], "CUE", cuePreview[k], col)
            drawSide(c, side[k][2], "SYNC", dd.sync, col)
            drawSide(c, side[k][3], "🎧", dd.cue, col)
            // Pitch-Anzeige
            text.textSize = dp(8.5f)
            text.color = alpha(s.ink, 0.75f)
            val pr = if (dd.sync) "SYNC" else String.format(Locale.GERMANY, "%+.1f%%", dd.pitch * dd.range * 100f)
            c.drawText(pr, pitch[k].centerX(), pitch[k].bottom + dp(9f), text)
            c.drawText("LINE " + "AB"[k], vol[k].centerX(), vol[k].top - dp(3f), text)
        }
        drawXy(c)
        drawCross(c)
        drawPads(c)
        if (Dj.splitCue) {
            text.textSize = dp(9f)
            text.color = s.accent
            c.drawText("🎧 SPLIT-CUE", xfader.centerX(), xfader.top - dp(2f), text)
        }
        postInvalidateOnAnimation()
    }

    private fun drawBody(c: Canvas) {
        val s = skin
        val w = width
        val h = height
        // Rahmen / Wangen
        if (s.frame == 1) woodPaintRect(c, RectF(0f, insetTop, w.toFloat(), h - insetBottom), false, dp(18f))
        if (s.frame == 2) {
            woodPaintRect(c, RectF(0f, insetTop, panel.left + dp(2f), h - insetBottom), true, dp(8f))
            woodPaintRect(c, RectF(panel.right - dp(2f), insetTop, w.toFloat(), h - insetBottom), true, dp(8f))
        }
        val rad = if (s.frame == 1) dp(8f) else dp(16f)
        if (texture == null || texW != w || texH != h) {
            texture = try { buildTexture(panel.width().toInt(), panel.height().toInt()) } catch (e: Throwable) { null }
            texW = w
            texH = h
        }
        // Schatten unter der Platte
        fill.shader = null
        fill.color = Color.argb(if (s.dark) 140 else 60, 0, 0, 0)
        fill.maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.NORMAL)
        tmp.set(panel.left, panel.top + dp(4f), panel.right, panel.bottom + dp(6f))
        c.drawRoundRect(tmp, rad, rad, fill)
        fill.maskFilter = null
        c.save()
        path.reset()
        path.addRoundRect(panel, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val t = texture
        if (t != null) c.drawBitmap(t, null, panel, bmpPaint) else {
            fill.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, s.top, s.bot, Shader.TileMode.CLAMP)
            c.drawRect(panel, fill)
            fill.shader = null
        }
        // Glanz oben
        fill.shader = LinearGradient(0f, panel.top, 0f, panel.top + panel.height() * 0.35f, Color.argb(if (s.dark) 18 else 40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(panel, fill)
        fill.shader = null
        c.restore()
        stroke.strokeWidth = dp(if (s.frame == 3) 2.5f else 1f)
        stroke.color = if (s.frame == 3) Color.argb(160, 200, 200, 205) else Color.argb(if (s.dark) 60 else 90, if (s.dark) 255 else 0, if (s.dark) 255 else 0, if (s.dark) 255 else 0)
        c.drawRoundRect(panel, rad, rad, stroke)
        if (s.screws) {
            val o = dp(9f)
            for ((sx, sy) in listOf(panel.left + o to panel.top + o, panel.right - o to panel.top + o, panel.left + o to panel.bottom - o, panel.right - o to panel.bottom - o)) screw(c, sx, sy)
        }
    }

    private fun screw(c: Canvas, x: Float, y: Float) {
        val r = dp(4f)
        fill.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, Color.rgb(230, 230, 232), Color.rgb(110, 112, 116), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        stroke.strokeWidth = dp(1.2f)
        stroke.color = Color.argb(150, 40, 40, 40)
        c.drawLine(x - r * 0.6f, y, x + r * 0.6f, y, stroke)
        c.drawLine(x, y - r * 0.6f, x, y + r * 0.6f, stroke)
    }

    private fun drawHeader(c: Canvas) {
        val s = skin
        val lg = logo
        if (lg != null) {
            val hh = dp(30f)
            val ww = hh * lg.width / lg.height
            tmp.set(header.left, header.centerY() - hh / 2, header.left + ww, header.centerY() + hh / 2)
            if (!s.dark) {
                // auf hellen Oberflächen ein dunkles Schild hinter das Logo
                fill.color = Color.argb(220, 22, 25, 30)
                val r2 = RectF(tmp.left - dp(6f), tmp.top - dp(3f), tmp.right + dp(6f), tmp.bottom + dp(3f))
                c.drawRoundRect(r2, dp(8f), dp(8f), fill)
            }
            c.drawBitmap(lg, null, tmp, bmpPaint)
        }
        val b = Engine.currentBpm()
        chip(c, bpmChip, if (b > 0f) String.format(Locale.GERMANY, "%.1f BPM", b) else "– BPM", Engine.seqPlaying)
        chip(c, padsBtn, "▦ PADS", false)
        chip(c, menuBtn, "☰", false)
    }

    private fun chip(c: Canvas, r: RectF, label: String, on: Boolean) {
        val s = skin
        fill.shader = null
        fill.color = if (on) alpha(s.accent, 0.85f) else if (s.dark) Color.argb(120, 0, 0, 0) else Color.argb(40, 0, 0, 0)
        c.drawRoundRect(r, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(s.ink, 0.35f)
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.textSize = dp(12f)
        text.color = if (on) Color.WHITE else s.ink
        c.drawText(label, r.centerX(), r.centerY() + dp(4.2f), text)
    }

    // ---------------------------------------------------------------- Display mit beiden Decks

    private val secondsVisible = 6.0

    private fun drawDisplay(c: Canvas) {
        val s = skin
        val r = display
        val rad = dp(10f)
        // Einfassung + Bildschirm
        fill.shader = null
        fill.color = if (s.dark) Color.argb(200, 0, 0, 0) else Color.argb(70, 0, 0, 0)
        tmp.set(r.left - dp(3f), r.top - dp(3f), r.right + dp(3f), r.bottom + dp(3f))
        c.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill)
        fill.color = s.dispBg
        c.drawRoundRect(r, rad, rad, fill)
        c.save()
        path.reset()
        path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        for (k in 0..1) drawLane(c, k)
        c.restore()
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(s.dispInk, 0.35f)
        c.drawRoundRect(r, rad, rad, stroke)
    }

    private fun drawLane(c: Canvas, k: Int) {
        val s = skin
        val dd = Dj.decks[k]
        val ln = lanes[k]
        val col = if (k == 0) s.colA else s.colB
        val ink = s.dispInk
        val wave = RectF(ln.left, ln.top + dp(16f), ln.right, overview[k].top - dp(2f))
        // Kopfzeile
        mono.textSize = dp(10f)
        mono.color = col
        mono.textAlign = Paint.Align.LEFT
        c.drawText("AB"[k].toString(), ln.left + dp(2f), ln.top + dp(11f), mono)
        mono.color = ink
        val title = when {
            dd.loading -> "Lädt …"
            dd.loaded() -> dd.title
            else -> "⏏ antippen: Track laden (MP3/WAV)"
        }
        val info = if (dd.loaded()) {
            val rem = (dd.len - dd.pos) / dd.srcRate / max(0.01, dd.ratio)
            String.format(Locale.GERMANY, "%.1f  −%s", dd.effBpm(), Dj.fmt(rem))
        } else ""
        mono.textAlign = Paint.Align.RIGHT
        c.drawText(info, ln.right - dp(2f), ln.top + dp(11f), mono)
        val iw = mono.measureText(info) + dp(8f)
        mono.textAlign = Paint.Align.LEFT
        val maxW = ln.width() - iw - dp(16f)
        var t2 = title
        while (t2.length > 3 && mono.measureText(t2) > maxW) t2 = t2.substring(0, t2.length - 2)
        if (t2 != title) t2 = t2.trimEnd() + "…"
        c.drawText(t2, ln.left + dp(14f), ln.top + dp(11f), mono)
        // Wellenform um die Abspielposition
        val pk = dd.peaks
        val lw = dd.lows
        val cx = wave.centerX()
        val cy = wave.centerY()
        val hh = wave.height() / 2f
        if (dd.loaded() && pk.isNotEmpty()) {
            val fpp = secondsVisible * dd.srcRate / wave.width()
            val pos = dd.pos
            // Loop-Bereich
            if (dd.loopOn) {
                val x0 = (cx + (dd.loopIn - pos) / fpp).toFloat()
                val x1 = (cx + (dd.loopOut - pos) / fpp).toFloat()
                fill.color = alpha(col, 0.22f)
                c.drawRect(max(wave.left, x0), wave.top, min(wave.right, x1), wave.bottom, fill)
            }
            val step = max(1f, dp(1f))
            var x = wave.left
            val lowCol = col
            val highCol = blend(col, Color.WHITE, 0.75f)
            stroke.strokeWidth = step
            while (x < wave.right) {
                val f = pos + (x - cx) * fpp
                if (f >= 0 && f < dd.len) {
                    val bi = (f / Dj.BIN).toInt().coerceIn(0, pk.size - 1)
                    val v = (pk[bi].toInt() and 0xFF) / 255f
                    val lo = (lw[bi].toInt() and 0xFF) / 255f
                    stroke.color = if (f < pos) alpha(blend(highCol, lowCol, lo), 0.55f) else blend(highCol, lowCol, lo)
                    val a = max(dp(0.5f), v * hh)
                    c.drawLine(x, cy - a, x, cy + a, stroke)
                }
                x += step
            }
            // Beat-Raster
            if (dd.bpm > 0f) {
                val bf = dd.beatFrames()
                val kMin = floor((pos - wave.width() / 2 * fpp - dd.grid) / bf).toLong()
                val kMax = kMin + (wave.width() * fpp / bf).toLong() + 2
                for (kk in kMin..kMax) {
                    val f = dd.grid + kk * bf
                    val bx = (cx + (f - pos) / fpp).toFloat()
                    if (bx < wave.left || bx > wave.right) continue
                    val bar = Math.floorMod(kk, 4L) == 0L
                    stroke.strokeWidth = dp(if (bar) 1.4f else 0.8f)
                    stroke.color = alpha(ink, if (bar) 0.55f else 0.22f)
                    c.drawLine(bx, wave.top, bx, wave.top + dp(if (bar) 6f else 3f), stroke)
                    c.drawLine(bx, wave.bottom, bx, wave.bottom - dp(if (bar) 6f else 3f), stroke)
                }
            }
            // Cue und Hot Cues
            marker(c, cx + ((dd.cuePoint - pos) / fpp).toFloat(), wave, Color.rgb(255, 170, 0))
            for (h in 0..3) if (dd.hot[h] >= 0) marker(c, cx + ((dd.hot[h] - pos) / fpp).toFloat(), wave, HOT[h])
            // Übersicht
            val ov = overview[k]
            fill.color = alpha(ink, 0.12f)
            c.drawRect(ov, fill)
            val n = ov.width().toInt()
            stroke.strokeWidth = 1f
            for (i in 0 until n step 2) {
                val bi = (i.toLong() * pk.size / n).toInt().coerceIn(0, pk.size - 1)
                val v = (pk[bi].toInt() and 0xFF) / 255f
                val f = bi.toDouble() * Dj.BIN
                stroke.color = if (f < pos) alpha(col, 0.45f) else alpha(blend(col, Color.WHITE, 0.4f), 0.9f)
                val a = v * ov.height() / 2f
                c.drawLine(ov.left + i, ov.centerY() - a, ov.left + i, ov.centerY() + a, stroke)
            }
            val px = ov.left + (pos / dd.len).toFloat() * ov.width()
            stroke.strokeWidth = dp(1.5f)
            stroke.color = Color.WHITE
            c.drawLine(px, ov.top - dp(1f), px, ov.bottom + dp(1f), stroke)
        } else {
            stroke.strokeWidth = dp(1f)
            stroke.color = alpha(ink, 0.2f)
            c.drawLine(wave.left, cy, wave.right, cy, stroke)
        }
        // Abspielkopf
        stroke.strokeWidth = dp(2f)
        stroke.color = if (dd.scratching) Color.rgb(255, 80, 80) else Color.WHITE
        c.drawLine(cx, wave.top - dp(2f), cx, wave.bottom + dp(2f), stroke)
        // Pegel
        val lv = min(1f, dd.level)
        fill.color = if (lv > 0.95f) Color.rgb(255, 60, 60) else col
        c.drawRect(ln.left, wave.bottom - wave.height() * lv, ln.left + dp(3f), wave.bottom, fill)
        if (dd.loopOn) {
            mono.textSize = dp(9f)
            mono.color = col
            mono.textAlign = Paint.Align.RIGHT
            c.drawText("LOOP ${beatsLabel(dd.loopBeats)}", wave.right - dp(2f), wave.top + dp(9f), mono)
            mono.textAlign = Paint.Align.LEFT
        }
    }

    private val HOT = intArrayOf(Color.rgb(255, 60, 90), Color.rgb(60, 220, 120), Color.rgb(70, 150, 255), Color.rgb(200, 90, 255))

    private fun marker(c: Canvas, x: Float, r: RectF, col: Int) {
        if (x < r.left || x > r.right) return
        fill.color = col
        path.reset()
        path.moveTo(x - dp(4f), r.top)
        path.lineTo(x + dp(4f), r.top)
        path.lineTo(x, r.top + dp(6f))
        path.close()
        c.drawPath(path, fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(col, 0.6f)
        c.drawLine(x, r.top, x, r.bottom, stroke)
    }

    private fun beatsLabel(b: Float): String = when {
        b >= 1f -> b.toInt().toString()
        b == 0.5f -> "1/2"
        b == 0.25f -> "1/4"
        else -> "1/8"
    }

    // ---------------------------------------------------------------- Regler

    private fun drawKnob(c: Canvas, box: RectF, i: Int, v: Float, label: String, focusCol: Int) {
        val s = skin
        val r = min(box.width() * 0.36f, (box.height() - dp(12f)) * 0.5f)
        val cx = box.centerX()
        val cy = box.top + r + dp(1f)
        val ang = (-135f + 270f * v) * PI.toFloat() / 180f
        // Wert-Bogen (zur Orientierung, in jedem Design)
        stroke.strokeWidth = dp(2.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = alpha(s.ink, 0.15f)
        tmp.set(cx - r - dp(5f), cy - r - dp(5f), cx + r + dp(5f), cy + r + dp(5f))
        c.drawArc(tmp, 135f, 270f, false, stroke)
        stroke.color = focusCol
        if (i < 3 || abs(v - 0.5f) > 0.01f) {
            if (i == 3 || i < 3) {
                val start = 270f
                val sweep = (v - 0.5f) * 270f
                c.drawArc(tmp, start, sweep, false, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        val kc = if (s.knobCols.isNotEmpty()) s.knobCols[i % s.knobCols.size] else s.accent
        when (s.knob) {
            0 -> { // Metall
                shadow(c, cx, cy + dp(3f), r * 1.05f)
                fill.shader = RadialGradient(cx, cy, r * 1.08f, intArrayOf(Color.rgb(120, 122, 126), Color.rgb(70, 72, 75)), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 1.08f, fill)
                fill.shader = SweepGradient(cx, cy, intArrayOf(Color.rgb(235, 236, 238), Color.rgb(150, 152, 156), Color.rgb(240, 241, 243), Color.rgb(140, 142, 146), Color.rgb(235, 236, 238)), null)
                c.drawCircle(cx, cy, r * 0.92f, fill)
                fill.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r * 0.8f, Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.72f, fill)
                fill.shader = null
                knurl(c, cx, cy, r * 0.92f, Color.argb(70, 0, 0, 0))
                indicator(c, cx, cy, r * 0.72f, ang, Color.rgb(40, 40, 42), dp(2.2f))
            }
            1, 9 -> { // Kunststoff farbig bzw. Creme
                val base = if (s.knob == 9) Color.rgb(232, 222, 196) else kc
                shadow(c, cx, cy + dp(4f), r * 1.05f)
                fill.color = darken(base, 0.25f)
                c.drawCircle(cx, cy, r * 1.06f, fill)
                fill.shader = RadialGradient(cx - r * 0.25f, cy - r * 0.35f, r * 1.2f, lighten(base, 0.25f), darken(base, 0.1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy - dp(1f), r * 0.86f, fill)
                fill.shader = null
                knurl(c, cx, cy - dp(1f), r * 0.86f, alpha(darken(base, 0.4f), 0.5f))
                indicator(c, cx, cy - dp(1f), r * 0.75f, ang, if (s.knob == 9) Color.rgb(160, 160, 165) else lighten(base, 0.6f), dp(2.2f))
            }
            2 -> { // weiß mit Strich
                shadow(c, cx, cy + dp(3f), r)
                fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f, Color.WHITE, Color.rgb(222, 222, 224), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.92f, fill)
                fill.shader = null
                indicator(c, cx, cy, r * 0.8f, ang, Color.rgb(120, 120, 125), dp(2f))
            }
            3 -> { // schwarz gerändelt
                shadow(c, cx, cy + dp(4f), r * 1.05f)
                fill.color = Color.rgb(20, 20, 22)
                c.drawCircle(cx, cy, r * 1.04f, fill)
                fill.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r, Color.rgb(64, 64, 68), Color.rgb(18, 18, 20), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.88f, fill)
                fill.shader = null
                knurl(c, cx, cy, r * 0.88f, Color.argb(90, 255, 255, 255))
                indicator(c, cx, cy, r * 0.8f, ang, Color.rgb(235, 235, 235), dp(2f))
            }
            4 -> { // Glas
                fill.color = Color.argb(50, 255, 255, 255)
                c.drawCircle(cx, cy, r * 1.05f, fill)
                stroke.strokeWidth = dp(2f)
                stroke.color = Color.argb(200, 255, 255, 255)
                c.drawCircle(cx, cy, r, stroke)
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(120, 255, 255, 255)
                c.drawCircle(cx, cy, r * 0.72f, stroke)
                fill.shader = RadialGradient(cx, cy, r * 0.6f, Color.argb(150, 255, 255, 255), Color.argb(30, 255, 255, 255), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.6f, fill)
                fill.shader = null
                if (s.knobCols.isNotEmpty()) {
                    stroke.strokeWidth = dp(1.6f)
                    stroke.color = s.knobCols[0]
                    c.drawCircle(cx, cy, r * 0.5f, stroke)
                }
                indicator(c, cx, cy, r * 0.9f, ang, alpha(s.accent, 0.9f), dp(2f))
            }
            5 -> { // Neon-Ring
                fill.color = Color.rgb(16, 16, 20)
                c.drawCircle(cx, cy, r * 1.05f, fill)
                glowCircle(c, cx, cy, r * 0.82f, kc, dp(4f))
                glowCircle(c, cx, cy, r * 0.62f, kc, dp(2.5f))
                indicator(c, cx, cy, r * 0.95f, ang, Color.WHITE, dp(2f))
            }
            6 -> { // Holz mit Metallring
                shadow(c, cx, cy + dp(4f), r * 1.05f)
                fill.shader = SweepGradient(cx, cy, intArrayOf(Color.rgb(200, 200, 205), Color.rgb(90, 90, 95), Color.rgb(210, 210, 215), Color.rgb(90, 90, 95), Color.rgb(200, 200, 205)), null)
                c.drawCircle(cx, cy, r * 1.04f, fill)
                fill.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r, Color.rgb(150, 92, 58), Color.rgb(80, 44, 26), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.86f, fill)
                fill.shader = null
                knurl(c, cx, cy, r * 0.86f, Color.argb(70, 0, 0, 0))
                indicator(c, cx, cy, r * 0.75f, ang, Color.rgb(240, 220, 190), dp(2f))
            }
            7 -> { // Messing mit Holzkappe
                shadow(c, cx, cy + dp(4f), r * 1.05f)
                fill.shader = SweepGradient(cx, cy, intArrayOf(Color.rgb(240, 205, 120), Color.rgb(150, 110, 40), Color.rgb(250, 220, 140), Color.rgb(140, 100, 40), Color.rgb(240, 205, 120)), null)
                c.drawCircle(cx, cy, r * 1.02f, fill)
                fill.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r * 0.8f, if (i % 2 == 0) Color.rgb(160, 96, 60) else Color.rgb(250, 225, 150), if (i % 2 == 0) Color.rgb(90, 50, 30) else Color.rgb(170, 125, 50), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.66f, fill)
                fill.shader = null
                indicator(c, cx, cy, r * 0.62f, ang, Color.rgb(250, 240, 220), dp(2f))
            }
            8 -> { // Leucht-Kontur (Marv)
                fill.color = Color.rgb(16, 18, 22)
                c.drawCircle(cx, cy, r, fill)
                glowCircle(c, cx, cy, r, s.accent, dp(2.4f))
                stroke.strokeWidth = dp(1.2f)
                stroke.color = alpha(s.accent, 0.8f)
                for (t in 0..10) {
                    val a = (-135f + 27f * t) * PI.toFloat() / 180f - PI.toFloat() / 2
                    c.drawLine(cx + cos(a) * r * 0.78f, cy + sin(a) * r * 0.78f, cx + cos(a) * r * 0.88f, cy + sin(a) * r * 0.88f, stroke)
                }
                fill.color = Color.rgb(28, 32, 40)
                c.drawCircle(cx, cy, r * 0.66f, fill)
                indicator(c, cx, cy, r * 0.6f, ang, s.accent, dp(2.2f))
            }
        }
        text.textSize = dp(9.5f)
        text.color = alpha(s.ink, 0.8f)
        c.drawText(label, cx, box.bottom - dp(1f), text)
    }

    private fun shadow(c: Canvas, x: Float, y: Float, r: Float) {
        fill.shader = null
        fill.color = Color.argb(if (skin.dark) 160 else 70, 0, 0, 0)
        fill.maskFilter = BlurMaskFilter(r * 0.25f + 1f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(x, y, r, fill)
        fill.maskFilter = null
    }

    private fun knurl(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        stroke.strokeWidth = dp(0.8f)
        stroke.color = col
        val n = 48
        for (t in 0 until n) {
            val a = t * 2 * PI.toFloat() / n
            c.drawLine(cx + cos(a) * r * 0.9f, cy + sin(a) * r * 0.9f, cx + cos(a) * r, cy + sin(a) * r, stroke)
        }
    }

    private fun indicator(c: Canvas, cx: Float, cy: Float, r: Float, ang: Float, col: Int, w: Float) {
        val a = ang - PI.toFloat() / 2
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = col
        c.drawLine(cx + cos(a) * r * 0.45f, cy + sin(a) * r * 0.45f, cx + cos(a) * r, cy + sin(a) * r, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun glowCircle(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, w: Float) {
        glowP.style = Paint.Style.STROKE
        glowP.strokeWidth = w * 3f
        glowP.color = alpha(col, 0.35f)
        glowP.maskFilter = BlurMaskFilter(w * 2f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cy, r, glowP)
        glowP.maskFilter = null
        stroke.strokeWidth = w
        stroke.color = blend(col, Color.WHITE, 0.35f)
        c.drawCircle(cx, cy, r, stroke)
    }

    private fun glowRect(c: Canvas, r: RectF, rad: Float, col: Int, w: Float) {
        glowP.style = Paint.Style.STROKE
        glowP.strokeWidth = w * 3f
        glowP.color = alpha(col, 0.35f)
        glowP.maskFilter = BlurMaskFilter(w * 2f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(r, rad, rad, glowP)
        glowP.maskFilter = null
        stroke.strokeWidth = w
        stroke.color = blend(col, Color.WHITE, 0.3f)
        c.drawRoundRect(r, rad, rad, stroke)
    }

    // ---------------------------------------------------------------- Taster

    private fun drawTop(c: Canvas, r: RectF, i: Int, label: String, on: Boolean, col: Int) {
        val s = skin
        val bc = if (s.btnCols.isNotEmpty()) s.btnCols[i % s.btnCols.size] else col
        button(c, r, on, if (s.btn == 1 || s.btn == 5) bc else col, i)
        text.textSize = dp(9f)
        text.color = alpha(s.ink, 0.85f)
        var lbl = label
        while (lbl.length > 3 && text.measureText(lbl) > r.width() + dp(20f)) lbl = lbl.dropLast(1)
        c.drawText(lbl, r.centerX(), r.bottom + dp(10f), text)
    }

    private fun drawSide(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        val s = skin
        button(c, r, on, col, 9)
        text.textSize = min(dp(11f), r.height() * 0.38f)
        val lit = on && s.btn != 6
        text.color = when {
            lit && (s.btn == 1 || s.btn == 5) -> Color.rgb(20, 20, 20)
            on -> if (s.dark || s.btn == 1) Color.WHITE else col
            else -> alpha(if (s.btn == 8 || s.btn == 1 || s.dark) Color.WHITE else s.ink, 0.8f)
        }
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun button(c: Canvas, r: RectF, on: Boolean, col: Int, i: Int) {
        val s = skin
        val round = s.btn in intArrayOf(2, 3, 4, 5, 7, 8)
        val rad = if (round) min(r.width(), r.height()) / 2f else dp(5f)
        when (s.btn) {
            0, 2 -> {
                fill.color = Color.argb(110, 0, 0, 0)
                tmp.set(r.left - dp(2f), r.top - dp(2f), r.right + dp(2f), r.bottom + dp(2f))
                c.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill)
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(236, 237, 239), Color.rgb(150, 152, 156), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, fill)
                fill.shader = null
                if (on) {
                    fill.color = alpha(col, 0.55f)
                    c.drawRoundRect(r, rad, rad, fill)
                }
            }
            1 -> {
                fill.color = Color.rgb(16, 16, 18)
                tmp.set(r.left - dp(2f), r.top - dp(2f), r.right + dp(2f), r.bottom + dp(2f))
                c.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill)
                if (on) glowFill(c, r, rad, col) else {
                    fill.color = darken(col, 0.55f)
                    c.drawRoundRect(r, rad, rad, fill)
                }
            }
            3, 9 -> {
                shadowRect(c, r, rad)
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, lighten(skin.top, 0.08f), darken(skin.bot, 0.06f), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, fill)
                fill.shader = null
                if (on) {
                    fill.color = alpha(col, 0.5f)
                    c.drawRoundRect(r, rad, rad, fill)
                }
            }
            4 -> {
                fill.color = if (on) alpha(col, 0.45f) else Color.argb(60, 255, 255, 255)
                c.drawRoundRect(r, rad, rad, fill)
                stroke.strokeWidth = dp(1.6f)
                stroke.color = Color.argb(210, 255, 255, 255)
                c.drawRoundRect(r, rad, rad, stroke)
            }
            5 -> {
                if (on) glowFill(c, r, rad, col) else {
                    fill.color = darken(col, 0.6f)
                    c.drawRoundRect(r, rad, rad, fill)
                    stroke.strokeWidth = dp(1.4f)
                    stroke.color = alpha(col, 0.7f)
                    c.drawRoundRect(r, rad, rad, stroke)
                }
            }
            6 -> {
                fill.color = if (on) alpha(s.accent, 0.35f) else Color.argb(80, 0, 0, 0)
                c.drawRoundRect(r, dp(6f), dp(6f), fill)
                glowRect(c, r, dp(6f), s.accent, dp(if (on) 2.2f else 1.4f))
            }
            7 -> {
                shadowRect(c, r, rad)
                fill.shader = SweepGradient(r.centerX(), r.centerY(), intArrayOf(Color.rgb(240, 205, 120), Color.rgb(150, 110, 40), Color.rgb(250, 220, 140), Color.rgb(150, 110, 40), Color.rgb(240, 205, 120)), null)
                c.drawRoundRect(r, rad, rad, fill)
                tmp.set(r.left + dp(3f), r.top + dp(3f), r.right - dp(3f), r.bottom - dp(3f))
                fill.shader = RadialGradient(r.centerX(), r.centerY(), r.width() / 2, Color.rgb(150, 92, 58), Color.rgb(90, 50, 30), Shader.TileMode.CLAMP)
                c.drawRoundRect(tmp, rad, rad, fill)
                fill.shader = null
                if (on) glowRect(c, r, rad, col, dp(1.6f))
            }
            8 -> {
                shadowRect(c, r, rad)
                fill.shader = RadialGradient(r.centerX() - r.width() * 0.15f, r.top, r.width(), Color.rgb(70, 72, 76), Color.rgb(18, 18, 20), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, fill)
                fill.shader = null
                if (on) glowRect(c, r, rad, col, dp(1.8f))
            }
        }
    }

    private fun glowFill(c: Canvas, r: RectF, rad: Float, col: Int) {
        glowP.style = Paint.Style.FILL
        glowP.color = alpha(col, 0.5f)
        glowP.maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(r, rad, rad, glowP)
        glowP.maskFilter = null
        fill.shader = RadialGradient(r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.7f, blend(col, Color.WHITE, 0.55f), col, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
    }

    private fun shadowRect(c: Canvas, r: RectF, rad: Float) {
        fill.shader = null
        fill.color = Color.argb(if (skin.dark) 150 else 60, 0, 0, 0)
        fill.maskFilter = BlurMaskFilter(dp(3f), BlurMaskFilter.Blur.NORMAL)
        tmp.set(r.left, r.top + dp(2f), r.right, r.bottom + dp(3f))
        c.drawRoundRect(tmp, rad, rad, fill)
        fill.maskFilter = null
    }

    // ---------------------------------------------------------------- Fader

    private fun drawFader(c: Canvas, r: RectF, v: Float, col: Int, isPitch: Boolean, k: Int) {
        val s = skin
        val slotTop = r.top + dp(10f)
        val slotBot = r.bottom - dp(10f)
        val cx = r.centerX()
        // Platte
        when (s.plate) {
            1 -> {
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(226, 228, 230), Color.rgb(170, 172, 175), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1f)
                stroke.color = Color.argb(90, 0, 0, 0)
                c.drawRoundRect(r, dp(3f), dp(3f), stroke)
            }
            2 -> woodPaintRect(c, r, true, dp(4f))
            3 -> {
                stroke.strokeWidth = dp(0.8f)
                stroke.color = alpha(s.ink, 0.35f)
                val n = 10
                for (t in 0..n) {
                    val y = slotTop + (slotBot - slotTop) * t / n
                    c.drawLine(r.left + dp(3f), y, r.right - dp(3f), y, stroke)
                }
            }
        }
        // Schlitz
        when (s.fader) {
            5 -> glowRect(c, RectF(cx - dp(4f), slotTop - dp(4f), cx + dp(4f), slotBot + dp(4f)), dp(4f), col, dp(2f))
            4 -> {
                fill.color = Color.argb(70, 255, 255, 255)
                tmp.set(cx - dp(4f), slotTop - dp(4f), cx + dp(4f), slotBot + dp(4f))
                c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(200, 255, 255, 255)
                c.drawRoundRect(tmp, dp(4f), dp(4f), stroke)
                if (skinIdx == 17) {
                    fill.color = alpha(col, 0.8f)
                    c.drawRect(cx - dp(1f), slotTop + (slotBot - slotTop) * (1f - v), cx + dp(1f), slotBot, fill)
                }
            }
            7 -> glowRect(c, RectF(cx - dp(3f), slotTop, cx + dp(3f), slotBot), dp(3f), s.accent, dp(1.8f))
            else -> {
                fill.color = Color.rgb(12, 12, 12)
                tmp.set(cx - dp(2.2f), slotTop, cx + dp(2.2f), slotBot)
                c.drawRoundRect(tmp, dp(2f), dp(2f), fill)
            }
        }
        if (isPitch) {
            stroke.strokeWidth = dp(1f)
            stroke.color = alpha(s.ink, 0.5f)
            val my = (slotTop + slotBot) / 2f
            c.drawLine(r.left, my, r.left + dp(5f), my, stroke)
            c.drawLine(r.right - dp(5f), my, r.right, my, stroke)
            text.textSize = dp(8f)
            text.color = alpha(s.ink, 0.6f)
            c.drawText("−", cx, r.top + dp(7f), text)
        }
        val y = slotTop + (slotBot - slotTop) * (1f - v)
        val cw = min(r.width() + dp(4f), dp(40f))
        val ch = dp(22f)
        val cap = RectF(cx - cw / 2, y - ch / 2, cx + cw / 2, y + ch / 2)
        when (s.fader) {
            0 -> {
                shadowRect(c, cap, dp(3f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.left, cap.bottom, intArrayOf(Color.rgb(245, 246, 248), Color.rgb(170, 172, 176), Color.rgb(220, 222, 225)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1f)
                stroke.color = Color.argb(140, 60, 60, 60)
                c.drawLine(cap.left + dp(3f), y, cap.right - dp(3f), y, stroke)
            }
            1 -> {
                shadowRect(c, cap, dp(3f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.left, cap.bottom, lighten(col, 0.2f), darken(col, 0.25f), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1f)
                stroke.color = Color.argb(140, 0, 0, 0)
                c.drawLine(cap.left + dp(3f), y, cap.right - dp(3f), y, stroke)
            }
            2, 8 -> {
                shadowRect(c, cap, dp(3f))
                fill.color = if (s.fader == 8) Color.rgb(236, 226, 200) else Color.rgb(250, 250, 250)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(120, 0, 0, 0)
                c.drawLine(cap.left + dp(3f), y, cap.right - dp(3f), y, stroke)
            }
            3 -> {
                shadowRect(c, cap, dp(3f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.left, cap.bottom, Color.rgb(70, 70, 74), Color.rgb(16, 16, 18), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(200, 230, 230, 230)
                c.drawLine(cap.left + dp(4f), y, cap.right - dp(4f), y, stroke)
            }
            4 -> {
                fill.color = Color.argb(130, 255, 255, 255)
                c.drawRoundRect(cap, dp(4f), dp(4f), fill)
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(230, 255, 255, 255)
                c.drawRoundRect(cap, dp(4f), dp(4f), stroke)
            }
            5 -> {
                glowRect(c, RectF(cap.left + dp(4f), y - dp(5f), cap.right - dp(4f), y + dp(5f)), dp(3f), col, dp(2f))
            }
            6 -> {
                shadow(c, cx, y + dp(2f), dp(11f))
                fill.shader = SweepGradient(cx, y, intArrayOf(Color.rgb(250, 220, 140), Color.rgb(150, 110, 40), Color.rgb(250, 225, 150), Color.rgb(140, 100, 40), Color.rgb(250, 220, 140)), null)
                c.drawCircle(cx, y, dp(11f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(150, 80, 50, 10)
                c.drawLine(cx - dp(7f), y, cx + dp(7f), y, stroke)
            }
            7 -> {
                fill.color = Color.rgb(16, 18, 22)
                c.drawCircle(cx, y, dp(11f), fill)
                glowCircle(c, cx, y, dp(11f), s.accent, dp(2f))
                stroke.strokeWidth = dp(1.6f)
                stroke.color = s.accent
                c.drawLine(cx - dp(7f), y, cx + dp(7f), y, stroke)
            }
        }
    }

    // ---------------------------------------------------------------- XY-Fläche (Deck-Effekt)

    private fun drawXy(c: Canvas) {
        val s = skin
        val r = xy
        val rad = dp(8f)
        val col = if (focus == 0) s.colA else s.colB
        when (s.xy) {
            0, 10 -> {
                fill.color = Color.argb(110, 0, 0, 0)
                tmp.set(r.left - dp(5f), r.top - dp(5f), r.right + dp(5f), r.bottom + dp(5f))
                c.drawRoundRect(tmp, rad + dp(4f), rad + dp(4f), fill)
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(226, 228, 230), Color.rgb(160, 162, 166), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, fill)
                fill.shader = null
                grid(c, r, if (s.xy == 10) 4 else 2, if (s.xy == 10) s.accent else Color.argb(110, 40, 40, 40), s.xy == 10)
                if (s.xy == 0) {
                    stroke.strokeWidth = dp(1f)
                    stroke.color = Color.argb(110, 40, 40, 40)
                    c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.12f, stroke)
                    c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.06f, stroke)
                }
            }
            1 -> {
                fill.color = Color.rgb(4, 10, 16)
                c.drawRoundRect(r, rad, rad, fill)
                grid(c, r, 8, alpha(s.dispInk, 0.25f), false)
                glowRect(c, RectF(r.left + r.width() * 0.3f, r.top + r.height() * 0.3f, r.right - r.width() * 0.3f, r.bottom - r.height() * 0.3f), dp(6f), s.dispInk, dp(1.4f))
                stroke.strokeWidth = dp(1.2f)
                stroke.color = s.dispInk
                c.drawLine(r.centerX(), r.top + dp(6f), r.centerX(), r.bottom - dp(6f), stroke)
                c.drawLine(r.left + dp(6f), r.centerY(), r.right - dp(6f), r.centerY(), stroke)
                mono.textSize = dp(8f)
                mono.color = s.dispInk
                c.drawText("XY", r.left + dp(6f), r.bottom - dp(6f), mono)
            }
            2, 11 -> {
                shadowRect(c, r, rad)
                fill.color = if (s.xy == 11) blend(s.top, Color.WHITE, 0.3f) else Color.rgb(250, 250, 250)
                c.drawRoundRect(r, rad, rad, fill)
                if (s.xy == 11) grid(c, r, 4, Color.argb(90, 0, 0, 0), false) else grid(c, r, 2, Color.argb(70, 0, 0, 0), false)
            }
            3, 7 -> {
                fill.color = Color.argb(120, 0, 0, 0)
                tmp.set(r.left - dp(4f), r.top - dp(4f), r.right + dp(4f), r.bottom + dp(4f))
                c.drawRoundRect(tmp, rad + dp(3f), rad + dp(3f), fill)
                fill.color = Color.rgb(28, 29, 32)
                c.drawRoundRect(r, rad, rad, fill)
                if (s.xy == 3) grid(c, r, 4, Color.argb(70, 255, 255, 255), false) else star(c, r, Color.argb(160, 90, 150, 255))
            }
            4 -> {
                shadowRect(c, r, rad)
                fill.color = blend(s.top, Color.WHITE, 0.1f)
                c.drawRoundRect(r, rad, rad, fill)
                stroke.strokeWidth = dp(0.7f)
                stroke.color = Color.argb(80, 80, 60, 30)
                for (t in 1..6) {
                    val ins = r.width() * t / 16f
                    tmp.set(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins)
                    c.drawRoundRect(tmp, rad * 2, rad * 2, stroke)
                }
                grid(c, r, 2, Color.argb(90, 80, 60, 30), false)
            }
            5 -> {
                fill.color = Color.argb(70, 255, 255, 255)
                c.drawRoundRect(r, rad, rad, fill)
                stroke.strokeWidth = dp(1.6f)
                stroke.color = Color.argb(220, 255, 255, 255)
                c.drawRoundRect(r, rad, rad, stroke)
                grid(c, r, if (skinIdx == 17) 6 else 2, if (skinIdx == 17) alpha(s.accent, 0.6f) else Color.argb(120, 255, 255, 255), false)
            }
            6 -> {
                fill.color = Color.rgb(10, 12, 16)
                c.drawRoundRect(r, rad, rad, fill)
                glowRect(c, r, rad, s.accent, dp(1.6f))
                star(c, r, s.accent)
            }
            8 -> {
                woodPaintRect(c, RectF(r.left - dp(6f), r.top - dp(6f), r.right + dp(6f), r.bottom + dp(6f)), false, dp(4f))
                fill.color = Color.rgb(232, 224, 206)
                c.drawRect(r, fill)
                stroke.strokeWidth = dp(0.6f)
                stroke.color = Color.argb(30, 90, 70, 40)
                var yy = r.top
                while (yy < r.bottom) { c.drawLine(r.left, yy, r.right, yy, stroke); yy += dp(2f) }
                var xx = r.left
                while (xx < r.right) { c.drawLine(xx, r.top, xx, r.bottom, stroke); xx += dp(2f) }
                grid(c, r, 2, Color.argb(70, 120, 80, 40), false)
            }
            9 -> {
                fill.color = Color.rgb(16, 14, 14)
                c.drawRoundRect(r, rad, rad, fill)
                grid(c, r, 6, alpha(s.accent, 0.55f), true)
                glowRect(c, r, rad, s.accent, dp(2f))
            }
        }
        // Finger / Haltepunkt
        if (xyActive || xyHold) {
            val px = r.left + xyX * r.width()
            val py = r.bottom - xyY * r.height()
            glowP.style = Paint.Style.FILL
            glowP.color = alpha(col, 0.6f)
            glowP.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(px, py, dp(14f), glowP)
            glowP.maskFilter = null
            fill.color = Color.WHITE
            c.drawCircle(px, py, dp(4f), fill)
        }
        text.textSize = dp(8.5f)
        text.color = alpha(s.ink, 0.75f)
        c.drawText(if (xyHold) "FX ${"AB"[focus]} · HOLD" else "FX ${"AB"[focus]}", r.centerX(), r.bottom + dp(10f), text)
    }

    private fun grid(c: Canvas, r: RectF, n: Int, col: Int, glow: Boolean) {
        stroke.strokeWidth = dp(if (glow) 1.4f else 0.8f)
        stroke.color = col
        for (t in 1 until n) {
            val x = r.left + r.width() * t / n
            val y = r.top + r.height() * t / n
            if (glow) {
                glowP.style = Paint.Style.STROKE
                glowP.strokeWidth = dp(4f)
                glowP.color = alpha(col, 0.3f)
                glowP.maskFilter = BlurMaskFilter(dp(3f), BlurMaskFilter.Blur.NORMAL)
                c.drawLine(x, r.top, x, r.bottom, glowP)
                c.drawLine(r.left, y, r.right, y, glowP)
                glowP.maskFilter = null
            }
            c.drawLine(x, r.top + dp(2f), x, r.bottom - dp(2f), stroke)
            c.drawLine(r.left + dp(2f), y, r.right - dp(2f), y, stroke)
        }
    }

    private fun star(c: Canvas, r: RectF, col: Int) {
        stroke.strokeWidth = dp(1f)
        stroke.color = col
        val cx = r.centerX()
        val cy = r.centerY()
        val rr = r.width() * 0.3f
        for (t in 0 until 8) {
            val a = t * PI.toFloat() / 4
            c.drawLine(cx, cy, cx + cos(a) * rr, cy + sin(a) * rr, stroke)
        }
        tmp.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawRect(tmp, stroke)
    }

    // ---------------------------------------------------------------- Crossfader

    private fun drawCross(c: Canvas) {
        val s = skin
        val r = xfader
        val cy = r.centerY()
        val slotL = r.left + dp(10f)
        val slotR = r.right - dp(10f)
        when (s.plate) {
            1 -> {
                fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom, Color.rgb(226, 228, 230), Color.rgb(170, 172, 175), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, dp(3f), dp(3f), fill)
                fill.shader = null
            }
            2 -> woodPaintRect(c, r, false, dp(4f))
            3 -> {
                stroke.strokeWidth = dp(0.8f)
                stroke.color = alpha(s.ink, 0.35f)
                for (t in 0..10) {
                    val x = slotL + (slotR - slotL) * t / 10
                    c.drawLine(x, r.top + dp(4f), x, r.top + dp(9f), stroke)
                    c.drawLine(x, r.bottom - dp(9f), x, r.bottom - dp(4f), stroke)
                }
            }
        }
        when (s.fader) {
            5 -> glowRect(c, RectF(slotL, cy - dp(3f), slotR, cy + dp(3f)), dp(3f), s.accent, dp(2f))
            7 -> glowRect(c, RectF(slotL, cy - dp(3f), slotR, cy + dp(3f)), dp(3f), s.accent, dp(1.8f))
            4 -> {
                tmp.set(slotL, cy - dp(4f), slotR, cy + dp(4f))
                fill.color = Color.argb(70, 255, 255, 255)
                c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(200, 255, 255, 255)
                c.drawRoundRect(tmp, dp(4f), dp(4f), stroke)
            }
            else -> {
                fill.color = Color.rgb(12, 12, 12)
                tmp.set(slotL, cy - dp(2.2f), slotR, cy + dp(2.2f))
                c.drawRoundRect(tmp, dp(2f), dp(2f), fill)
            }
        }
        if (skinIdx == 1) {
            // Akai: leuchtende Linien links/rechts
            stroke.strokeWidth = dp(1.6f)
            stroke.color = s.colB
            c.drawLine(slotL + dp(14f), cy - dp(5f), slotR - dp(14f), cy - dp(5f), stroke)
            stroke.color = s.colA
            c.drawLine(slotL + dp(14f), cy + dp(7f), slotR - dp(14f), cy + dp(7f), stroke)
        }
        val x = slotL + (slotR - slotL) * Dj.xfade
        val cw = dp(26f)
        val ch = r.height() - dp(4f)
        val cap = RectF(x - cw / 2, cy - ch / 2, x + cw / 2, cy + ch / 2)
        when (s.fader) {
            0, 1 -> {
                shadowRect(c, cap, dp(3f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.right, cap.top, intArrayOf(Color.rgb(200, 202, 206), Color.rgb(250, 250, 252), Color.rgb(160, 162, 166)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                if (s.fader == 1) fill.shader = LinearGradient(cap.left, cap.top, cap.right, cap.top, Color.rgb(60, 60, 64), Color.rgb(24, 24, 26), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.4f)
                stroke.color = if (s.fader == 1) s.accent else Color.argb(150, 60, 60, 60)
                c.drawLine(x - dp(3f), cap.top + dp(3f), x - dp(3f), cap.bottom - dp(3f), stroke)
                c.drawLine(x + dp(3f), cap.top + dp(3f), x + dp(3f), cap.bottom - dp(3f), stroke)
            }
            2, 8 -> {
                shadowRect(c, cap, dp(3f))
                fill.color = if (s.fader == 8) Color.rgb(236, 226, 200) else Color.WHITE
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(130, 0, 0, 0)
                c.drawLine(x, cap.top + dp(3f), x, cap.bottom - dp(3f), stroke)
            }
            3 -> {
                shadowRect(c, cap, dp(3f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.right, cap.top, Color.rgb(64, 64, 68), Color.rgb(16, 16, 18), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(3f), dp(3f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(220, 230, 230, 230)
                c.drawLine(x, cap.top + dp(3f), x, cap.bottom - dp(3f), stroke)
            }
            4 -> {
                fill.color = Color.argb(140, 255, 255, 255)
                c.drawRoundRect(cap, dp(4f), dp(4f), fill)
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(230, 255, 255, 255)
                c.drawRoundRect(cap, dp(4f), dp(4f), stroke)
            }
            5 -> glowRect(c, RectF(x - dp(6f), cap.top + dp(4f), x + dp(6f), cap.bottom - dp(4f)), dp(3f), s.accent, dp(2f))
            6 -> {
                shadow(c, x, cy + dp(2f), dp(12f))
                fill.shader = SweepGradient(x, cy, intArrayOf(Color.rgb(250, 220, 140), Color.rgb(150, 110, 40), Color.rgb(250, 225, 150), Color.rgb(140, 100, 40), Color.rgb(250, 220, 140)), null)
                c.drawCircle(x, cy, dp(12f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(150, 80, 50, 10)
                c.drawLine(x, cy - dp(8f), x, cy + dp(8f), stroke)
            }
            7 -> {
                fill.color = Color.rgb(16, 18, 22)
                c.drawCircle(x, cy, dp(13f), fill)
                glowCircle(c, x, cy, dp(13f), s.accent, dp(2f))
                stroke.strokeWidth = dp(1.6f)
                stroke.color = s.accent
                c.drawLine(x, cy - dp(8f), x, cy + dp(8f), stroke)
            }
        }
        text.textSize = dp(9f)
        text.color = alpha(s.ink, 0.7f)
        c.drawText("A", r.left - dp(2f), cy + dp(3f), text)
        c.drawText("B", r.right + dp(2f), cy + dp(3f), text)
    }

    // ---------------------------------------------------------------- Pads

    private val padDown = BooleanArray(18)
    private val padFlash = FloatArray(18)

    private fun padLabel(i: Int): Pair<String, Boolean> {
        val row = i / 6
        val colI = i % 6
        val k = if (colI < 3) 0 else 1
        val j = colI % 3
        val dd = Dj.decks[k]
        if (padMode == 1) {
            if (row < 2) {
                val p = row * 6 + colI
                val st = Engine.state(p)
                val on = st == Engine.PadState.PLAYING || st == Engine.PadState.RECORDING || st == Engine.PadState.OVERDUB
                val lbl = when (st) {
                    Engine.PadState.EMPTY -> "${p + 1}"
                    Engine.PadState.RECORDING -> "● ${p + 1}"
                    Engine.PadState.ONESHOT -> "⚡${p + 1}"
                    else -> "∞${p + 1}"
                }
                return lbl to (on || Engine.levels[p] > 0.05f)
            }
            return when (colI) {
                0 -> (if (Engine.seqPlaying) "■ STOP" else "▶ PLAY") to Engine.seqPlaying
                1 -> "ACID" to InstState.acidOn
                2 -> "DRUM" to InstState.drumOn
                3 -> "METRO" to Engine.metronome
                4 -> "🎙 MIC" to Engine.monitorOn
                else -> "TAP" to false
            }
        }
        return when (row) {
            0 -> "CUE ${j + 1}" to (dd.hot[j] >= 0)
            1 -> when (j) {
                0 -> "CUE 4" to (dd.hot[3] >= 0)
                1 -> "◀◀ ${beatsLabel(dd.loopBeats)}" to false
                else -> "${beatsLabel(dd.loopBeats)} ▶▶" to false
            }
            else -> when (j) {
                0 -> "½×" to false
                1 -> "LOOP ${beatsLabel(dd.loopBeats)}" to dd.loopOn
                else -> "2×" to false
            }
        }
    }

    private fun drawPads(c: Canvas) {
        val s = skin
        for (i in 0 until 18) {
            val r = pads[i]
            val (lbl, lit0) = padLabel(i)
            val k = if (i % 6 < 3) 0 else 1
            padFlash[i] *= 0.88f
            val lit = lit0 || padDown[i] || padFlash[i] > 0.2f
            val deckCol = if (padMode == 1 && i < 12) s.accent else if (k == 0) s.colA else s.colB
            val hotCol = if (padMode == 0 && (i / 6 == 0 || i % 3 == 0 && i / 6 == 1)) HOT[if (i / 6 == 0) i % 3 else 3] else deckCol
            val palCol = if (s.padCols.size > 1) s.padCols[(i * 7 + i / 6) % s.padCols.size] else hotCol
            val rad = dp(if (s.pad == 7 || s.pad == 11) 7f else 5f)
            val pressed = padDown[i]
            val rr = if (pressed) RectF(r.left + dp(1.5f), r.top + dp(1.5f), r.right - dp(1.5f), r.bottom - dp(1.5f)) else r
            when (s.pad) {
                0 -> {
                    padWell(c, r, rad)
                    fill.shader = LinearGradient(rr.left, rr.top, rr.right, rr.bottom, Color.rgb(222, 224, 226), Color.rgb(176, 178, 182), Shader.TileMode.CLAMP)
                    c.drawRoundRect(rr, rad, rad, fill)
                    fill.shader = null
                    if (lit) {
                        fill.color = alpha(hotCol, 0.45f)
                        c.drawRoundRect(rr, rad, rad, fill)
                    }
                }
                1, 7, 9 -> {
                    padWell(c, r, rad)
                    val base = if (s.pad == 9) palCol else palCol
                    if (lit || s.pad != 9) {
                        val br = if (lit) 1f else if (s.pad == 9) 1f else 0.62f
                        if (lit && s.pad != 9) glowFill(c, rr, rad, base) else {
                            fill.shader = RadialGradient(rr.centerX(), rr.centerY(), max(rr.width(), rr.height()) * 0.7f, lighten(base, 0.15f * br), darken(base, 1f - br), Shader.TileMode.CLAMP)
                            c.drawRoundRect(rr, rad, rad, fill)
                            fill.shader = null
                        }
                    } else {
                        fill.color = base
                        c.drawRoundRect(rr, rad, rad, fill)
                    }
                    if (s.pad == 9 && lit) {
                        stroke.strokeWidth = dp(2f)
                        stroke.color = darken(base, 0.35f)
                        c.drawRoundRect(rr, rad, rad, stroke)
                    }
                }
                2, 8 -> {
                    padWell(c, r, rad)
                    fill.shader = LinearGradient(rr.left, rr.top, rr.left, rr.bottom, if (s.pad == 2) Color.rgb(34, 34, 36) else lighten(s.padCols[0], 0.06f), if (s.pad == 2) Color.rgb(14, 14, 15) else darken(s.padCols[0], 0.2f), Shader.TileMode.CLAMP)
                    c.drawRoundRect(rr, rad, rad, fill)
                    fill.shader = null
                    stroke.strokeWidth = dp(1f)
                    stroke.color = Color.argb(50, 255, 255, 255)
                    c.drawRoundRect(rr, rad, rad, stroke)
                    if (lit) glowRect(c, rr, rad, hotCol, dp(1.6f))
                }
                3, 5 -> {
                    shadowRect(c, rr, rad)
                    fill.color = s.padCols[0]
                    c.drawRoundRect(rr, rad, rad, fill)
                    stroke.strokeWidth = dp(1f)
                    stroke.color = Color.argb(60, 0, 0, 0)
                    c.drawRoundRect(rr, rad, rad, stroke)
                    if (lit) {
                        fill.color = alpha(hotCol, 0.4f)
                        c.drawRoundRect(rr, rad, rad, fill)
                    }
                }
                4, 10 -> {
                    padWell(c, r, rad)
                    fill.color = s.padCols[0]
                    c.drawRoundRect(rr, rad, rad, fill)
                    val gc = if (lit) hotCol else s.glow
                    val ga = if (lit) 0.95f else if (s.pad == 10) 0.85f else 0.45f
                    fill.shader = RadialGradient(rr.centerX(), rr.centerY(), min(rr.width(), rr.height()) * (if (s.pad == 10) 0.42f else 0.75f), alpha(gc, ga), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.drawRoundRect(rr, rad, rad, fill)
                    fill.shader = null
                }
                6 -> {
                    fill.color = Color.argb(150, 255, 255, 255)
                    c.drawRoundRect(rr, rad, rad, fill)
                    stroke.strokeWidth = dp(1f)
                    stroke.color = Color.argb(200, 255, 255, 255)
                    c.drawRoundRect(rr, rad, rad, stroke)
                    val gc = if (lit) hotCol else s.glow
                    if (lit || skinIdx == 17) {
                        fill.shader = RadialGradient(rr.centerX(), rr.centerY(), max(rr.width(), rr.height()) * 0.6f, alpha(gc, if (lit) 0.85f else 0.45f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                        c.drawRoundRect(rr, rad, rad, fill)
                        fill.shader = null
                    }
                }
                11 -> {
                    fill.color = if (lit) alpha(s.accent, 0.3f) else Color.argb(60, 0, 0, 0)
                    c.drawRoundRect(rr, rad, rad, fill)
                    glowRect(c, rr, rad, if (lit) blend(hotCol, Color.WHITE, 0.2f) else s.accent, dp(if (lit) 2.6f else 1.6f))
                }
                12 -> {
                    woodPaintRect(c, rr, false, dp(3f))
                    tmp.set(rr.left + dp(4f), rr.top + dp(4f), rr.right - dp(4f), rr.bottom - dp(4f))
                    fill.color = if (lit) blend(Color.rgb(238, 230, 212), hotCol, 0.45f) else Color.rgb(238, 230, 212)
                    c.drawRect(tmp, fill)
                    stroke.strokeWidth = dp(0.5f)
                    stroke.color = Color.argb(28, 90, 70, 40)
                    var yy = tmp.top
                    while (yy < tmp.bottom) { c.drawLine(tmp.left, yy, tmp.right, yy, stroke); yy += dp(2f) }
                }
            }
            // Beschriftung
            text.textSize = min(dp(11f), rr.height() * 0.24f)
            val dark = when (s.pad) { 0, 3, 5, 6, 9, 12 -> true; 1 -> lit; else -> false }
            text.color = if (dark) Color.argb(200, 30, 30, 34) else Color.argb(if (lit) 255 else 190, 255, 255, 255)
            c.drawText(lbl, rr.centerX(), rr.centerY() + text.textSize * 0.36f, text)
        }
        // Deck-Kennung über den Pad-Hälften
        if (padMode == 0) {
            text.textSize = dp(9f)
            text.color = s.colA
            c.drawText("DECK A", (pads[0].left + pads[2].right) / 2, pads[0].top - dp(3f), text)
            text.color = s.colB
            c.drawText("DECK B", (pads[3].left + pads[5].right) / 2, pads[0].top - dp(3f), text)
        }
    }

    private fun padWell(c: Canvas, r: RectF, rad: Float) {
        fill.shader = null
        fill.color = Color.argb(if (skin.dark) 170 else 90, 0, 0, 0)
        tmp.set(r.left - dp(2f), r.top - dp(2f), r.right + dp(2f), r.bottom + dp(2.5f))
        c.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill)
    }

    // ================================================================ Bedienung

    private enum class K { NONE, KNOB, PITCH, VOL, XF, XY, PAD, SIDE, WAVE, OVERVIEW, TOP, CHIP }

    private class Ptr(val kind: K, val idx: Int, val sub: Int, var lastX: Float, var lastY: Float, val downAt: Long)

    private val ptrs = HashMap<Int, Ptr>()
    private val lastTap = LongArray(32)
    private val cuePreview = BooleanArray(2)
    private val padLaunched = BooleanArray(12)

    private fun doubleTap(slot: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val dbl = now - lastTap[slot] < 320
        lastTap[slot] = if (dbl) 0 else now
        return dbl
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) try { requestUnbufferedDispatch(e) } catch (ignored: Throwable) {}
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> for (id in ArrayList(ptrs.keys)) up(id, 0f, 0f)
        }
        return true
    }

    private fun hit(r: RectF, x: Float, y: Float, slop: Float = dp(4f)): Boolean =
        x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop

    private fun down(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        // Pads zuerst – sie müssen sofort reagieren
        for (i in 0 until 18) if (hit(pads[i], x, y, dp(2f))) {
            ptrs[id] = Ptr(K.PAD, i, 0, x, y, now)
            padPress(i, true)
            return
        }
        for (k in 0..1) for (j in 0..3) if (hit(side[k][j], x, y)) {
            ptrs[id] = Ptr(K.SIDE, k, j, x, y, now)
            sidePress(k, j, true)
            haptic()
            return
        }
        when {
            hit(bpmChip, x, y) -> { onTempo?.invoke(); return }
            hit(padsBtn, x, y) -> { onPadsMode?.invoke(); return }
            hit(menuBtn, x, y) -> { onMenu?.invoke(); return }
        }
        for (k in 0..1) {
            if (hit(overview[k], x, y, dp(3f)) && Dj.decks[k].loaded()) {
                ptrs[id] = Ptr(K.OVERVIEW, k, 0, x, y, now)
                seekOverview(k, x)
                return
            }
            if (hit(lanes[k], x, y, 0f)) {
                val dd = Dj.decks[k]
                if (y < lanes[k].top + dp(16f) || !dd.loaded()) {
                    if (!dd.loading) onLoad?.invoke(k)
                    return
                }
                ptrs[id] = Ptr(K.WAVE, k, 0, x, y, now)
                dd.scratchTarget = dd.pos
                dd.scratching = true
                return
            }
        }
        for (i in 0..3) if (hit(knobs[i], x, y, 0f)) {
            if (doubleTap(i)) setKnob(i, 0.5f)
            ptrs[id] = Ptr(K.KNOB, i, 0, x, y, now)
            return
        }
        for (i in 0..2) if (hit(tops[i], x, y, dp(8f))) {
            ptrs[id] = Ptr(K.TOP, i, 0, x, y, now)
            postDelayed({ if (ptrs[id]?.let { it.kind == K.TOP && it.downAt == now } == true) { topLong(i); ptrs[id] = Ptr(K.NONE, 0, 0, x, y, now) } }, 500L)
            return
        }
        for (k in 0..1) {
            if (hit(pitch[k], x, y, dp(6f))) {
                if (doubleTap(8 + k)) Dj.decks[k].pitch = 0f
                ptrs[id] = Ptr(K.PITCH, k, 0, x, y, now)
                return
            }
            if (hit(vol[k], x, y, dp(6f))) {
                ptrs[id] = Ptr(K.VOL, k, 0, x, y, now)
                return
            }
        }
        if (hit(xfader, x, y, dp(8f))) {
            if (doubleTap(12)) Dj.xfade = 0.5f
            ptrs[id] = Ptr(K.XF, 0, 0, x, y, now)
            return
        }
        if (hit(xy, x, y, dp(6f))) {
            if (doubleTap(13)) {
                xyHold = !xyHold
                onHint?.invoke(if (xyHold) "FX gehalten – nochmal doppelt tippen löst" else "FX-Halten aus")
            }
            ptrs[id] = Ptr(K.XY, 0, 0, x, y, now)
            setXy(x, y)
            xyActive = true
            return
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs[id] ?: return
        val dx = x - p.lastX
        val dy = y - p.lastY
        when (p.kind) {
            K.KNOB -> {
                val dd = Dj.decks[focus]
                val cur = when (p.idx) { 0 -> dd.hi; 1 -> dd.mid; 2 -> dd.lo; else -> dd.filter }
                var nv = (cur - dy / dp(170f)).coerceIn(0f, 1f)
                // Mitte rastet leicht ein
                if (abs(nv - 0.5f) < 0.015f) nv = 0.5f
                setKnob(p.idx, nv)
            }
            K.PITCH -> {
                val dd = Dj.decks[p.idx]
                if (!dd.sync) dd.pitch = (dd.pitch + dy / (pitch[p.idx].height() - dp(20f)) * 2f).coerceIn(-1f, 1f)
            }
            K.VOL -> {
                val dd = Dj.decks[p.idx]
                dd.fader = (dd.fader - dy / (vol[p.idx].height() - dp(20f))).coerceIn(0f, 1f)
            }
            K.XF -> Dj.xfade = (Dj.xfade + dx / (xfader.width() - dp(20f))).coerceIn(0f, 1f)
            K.XY -> setXy(x, y)
            K.WAVE -> {
                val dd = Dj.decks[p.idx]
                val fpp = secondsVisible * dd.srcRate / lanes[p.idx].width()
                dd.scratchTarget = (dd.scratchTarget - dx * fpp).coerceIn(0.0, (dd.len - 2).toDouble())
            }
            K.OVERVIEW -> seekOverview(p.idx, x)
            else -> {}
        }
        p.lastX = x
        p.lastY = y
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.remove(id) ?: return
        when (p.kind) {
            K.PAD -> padPress(p.idx, false)
            K.SIDE -> sidePress(p.idx, p.sub, false)
            K.WAVE -> Dj.decks[p.idx].scratching = false
            K.XY -> {
                xyActive = false
                if (!xyHold) Dj.decks[focus].fxAmt = 0f
            }
            K.TOP -> topTap(p.idx)
            else -> {}
        }
    }

    private fun haptic() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun setKnob(i: Int, v: Float) {
        val dd = Dj.decks[focus]
        when (i) { 0 -> dd.hi = v; 1 -> dd.mid = v; 2 -> dd.lo = v; else -> dd.filter = v }
    }

    private fun setXy(x: Float, y: Float) {
        xyX = ((x - xy.left) / xy.width()).coerceIn(0f, 1f)
        xyY = ((xy.bottom - y) / xy.height()).coerceIn(0f, 1f)
        val dd = Dj.decks[focus]
        dd.fxX = xyX
        dd.fxAmt = max(0.05f, xyY)
    }

    private fun seekOverview(k: Int, x: Float) {
        val dd = Dj.decks[k]
        val f = ((x - overview[k].left) / overview[k].width()).coerceIn(0f, 1f) * dd.len
        dd.seekTo = f.toDouble()
        if (dd.sync && dd.playing) dd.syncReq = true
    }

    private fun topTap(i: Int) {
        haptic()
        when (i) {
            0 -> {
                if (!xyHold) Dj.decks[focus].fxAmt = 0f
                focus = 1 - focus
                onHint?.invoke("Regler und XY steuern jetzt Deck ${"AB"[focus]}")
            }
            1 -> {
                val dd = Dj.decks[focus]
                val ch = Dj.FX_CHOICES
                val idx = ch.indexOf(dd.fxType)
                dd.fxType = ch[(idx + 1) % ch.size]
                onHint?.invoke("Effekt Deck ${"AB"[focus]}: ${Fx.NAMES[dd.fxType]} – auf der XY-Fläche spielen")
            }
            2 -> {
                padMode = 1 - padMode
                onHint?.invoke(if (padMode == 0) "Pads: Hot Cues, Beat-Jump und Loops je Deck" else "Pads: Sampler – die 12 Pads der App, darunter Transport, ACID, DRUM, Metronom, Mikro, Tap")
            }
        }
    }

    private fun topLong(i: Int) {
        haptic()
        when (i) {
            0 -> {
                Dj.splitCue = !Dj.splitCue
                onHint?.invoke(if (Dj.splitCue) "🎧 Split-Cue: links Master, rechts Vorhören (🎧-Taster der Decks)" else "Split-Cue aus: Stereo-Master")
            }
            1 -> onSkinMenu?.invoke()
            2 -> {
                Dj.xcurve = 1 - Dj.xcurve
                onHint?.invoke(if (Dj.xcurve == 1) "Crossfader: hart (Scratch)" else "Crossfader: weich")
            }
        }
    }

    private fun sidePress(k: Int, j: Int, down: Boolean) {
        val dd = Dj.decks[k]
        when (j) {
            0 -> if (down) {
                if (!dd.loaded()) { onLoad?.invoke(k); return }
                Dj.togglePlay(dd)
            }
            1 -> {
                if (!dd.loaded()) return
                if (down) {
                    if (dd.playing) {
                        Dj.cuePress(dd)
                    } else if (abs(dd.pos - dd.cuePoint) < dd.srcRate * 0.02) {
                        // am Cue-Punkt gehalten: vorhören, solange gedrückt
                        cuePreview[k] = true
                        dd.playing = true
                    } else {
                        Dj.cuePress(dd)
                    }
                } else if (cuePreview[k]) {
                    cuePreview[k] = false
                    dd.playing = false
                    dd.seekTo = dd.cuePoint
                }
            }
            2 -> if (down) {
                if (dd.bpm <= 0f) { onHint?.invoke("Kein Tempo erkannt"); return }
                Dj.toggleSync(dd)
                onHint?.invoke(if (!dd.sync && !dd.syncReq) "SYNC ${"AB"[k]} aus" else "SYNC ${"AB"[k]}: Tempo und Takt folgen dem Master (${String.format(Locale.GERMANY, "%.1f", max(Engine.currentBpm(), dd.bpm))} BPM)")
            }
            3 -> if (down) dd.cue = !dd.cue
        }
    }

    private fun padPress(i: Int, down: Boolean) {
        padDown[i] = down
        if (down) {
            padFlash[i] = 1f
            haptic()
        }
        val row = i / 6
        val colI = i % 6
        if (padMode == 1) {
            if (row < 2) {
                val p = row * 6 + colI
                if (down) {
                    val r = Engine.liveDown(p)
                    padLaunched[p] = (r and Engine.LIVE_LAUNCHED) != 0
                } else {
                    Engine.liveUp(p, padLaunched[p])
                }
                return
            }
            if (!down) return
            when (colI) {
                0 -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
                1 -> InstState.acidOn = !InstState.acidOn
                2 -> InstState.drumOn = !InstState.drumOn
                3 -> Engine.metronome = !Engine.metronome
                4 -> {
                    Engine.monitorOn = !Engine.monitorOn
                    onHint?.invoke(if (Engine.monitorOn) "🎙 Mikrofon live – mit Kopfhörern, sonst pfeift es" else "Mikrofon aus")
                }
                else -> tap()
            }
            return
        }
        val k = if (colI < 3) 0 else 1
        val j = colI % 3
        val dd = Dj.decks[k]
        if (!dd.loaded()) {
            if (down) onLoad?.invoke(k)
            return
        }
        if (!down) {
            // Hot Cue lange gehalten = löschen
            return
        }
        when (row) {
            0 -> hotPress(k, j)
            1 -> when (j) {
                0 -> hotPress(k, 3)
                1 -> jump(dd, -1)
                else -> jump(dd, 1)
            }
            else -> when (j) {
                0 -> Dj.setLoopBeats(dd, dd.loopBeats / 2f)
                1 -> Dj.toggleLoop(dd)
                else -> Dj.setLoopBeats(dd, dd.loopBeats * 2f)
            }
        }
    }

    private fun hotPress(k: Int, h: Int) {
        val dd = Dj.decks[k]
        val now = SystemClock.uptimeMillis()
        val slot = 16 + k * 4 + h
        if (dd.hot[h] >= 0 && now - lastTap[slot] < 320) {
            // doppelt tippen löscht den Hot Cue
            Dj.hotCue(dd, h, true)
            lastTap[slot] = 0
            onHint?.invoke("Hot Cue ${h + 1} (Deck ${"AB"[k]}) gelöscht")
            return
        }
        lastTap[slot] = now
        Dj.hotCue(dd, h, false)
    }

    private fun jump(dd: Dj.Deck, dir: Int) {
        val bf = dd.beatFrames() * dd.loopBeats * dir
        val np = (dd.pos + bf).coerceIn(0.0, (dd.len - 2).toDouble())
        dd.seekTo = np
        if (dd.loopOn) {
            dd.loopIn += bf
            dd.loopOut += bf
        }
    }

    private fun tap() {
        val now = SystemClock.uptimeMillis()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
        tapTimes.add(now)
        if (tapTimes.size > 6) tapTimes.removeAt(0)
        if (tapTimes.size >= 3) {
            val iv = (tapTimes.last() - tapTimes.first()).toDouble() / (tapTimes.size - 1)
            val bpm = (60000.0 / iv).toFloat()
            if (bpm in 40f..240f) {
                Engine.setBpm(Math.round(bpm * 10f) / 10f)
                onHint?.invoke(String.format(Locale.GERMANY, "Tempo %.1f BPM", bpm))
            }
        }
    }

    /** MIDI/außen: Pad-Anzeige blinken lassen. */
    fun flashSampler(p: Int) {
        if (padMode == 1 && p in 0 until 12) padFlash[p] = 1f
    }

    // ================================================================ Farb-Helfer

    private fun alpha(c: Int, a: Float): Int = Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
        )
    }
    private fun darken(c: Int, t: Float): Int = blend(c, Color.BLACK, t)
    private fun lighten(c: Int, t: Float): Int = blend(c, Color.WHITE, t)
}
