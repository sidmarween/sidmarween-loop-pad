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
                c(0x37404c), c(0x3a8cff), c(0x3a8cff), c(0xff7d45), c(0xe4ebf2), c(0x1e3a60), false),
            // Pastell-Creme mit Konfetti-Sprenkeln, Bonbon-Knöpfen und Chihuahua-Pads
            Skin("CHIHUAHUA MAMA", c(0xd9def0), c(0xf8efe6), c(0xf3e6e0), 8, 0, false, 1,
                intArrayOf(c(0xf3b8cb), c(0xc9b6ea), c(0xa9dccb), c(0xf2e3a2)), 3, intArrayOf(), 13,
                intArrayOf(c(0xf6c6d4), c(0xf3e7a8), c(0xbfe6da), c(0xf6cbbf), c(0xcfc0ec), c(0xbcd6f0)), c(0xf3a6c0), 9, 4, 11,
                c(0x7d6b86), c(0xe88fb2), c(0xb9a2e6), c(0xf2a98a), c(0xfdf7f1), c(0x6e5a78), false),
            // Claudes Design: warmes Graphit, Elfenbein, Ton-Orange (Deck A) und Salbei (Deck B)
            Skin("CLAUDES DESIGN – Ton, Elfenbein & Ruhe", c(0x141312), c(0x22211f), c(0x1b1a18), 5, 0, false, 8, intArrayOf(), 6, intArrayOf(), 11, intArrayOf(c(0x232220)), c(0xd97757), 7, 0, 9,
                c(0xede9e0), c(0xd97757), c(0xd97757), c(0x8fb3a3), c(0x121110), c(0xe8a07e), true)
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

    /** Ghost-Producer (Genre-Patterns als Live-Remix über den Track). */
    val ghost = GhostPanel(this).also { g -> g.onHint = { m -> onHint?.invoke(m) } }
    fun openGhost() { ghost.open(); invalidate() }
    var onTempo: (() -> Unit)? = null
    var onPadsMode: (() -> Unit)? = null
    var onHint: ((String) -> Unit)? = null
    /** STEMS-Pad „KI“: KI-Trennung einrichten/starten (Deck). */
    var onAiStems: ((Int) -> Unit)? = null
    var onSkinMenu: (() -> Unit)? = null
    var onInfo: (() -> Unit)? = null
    private val logoHit = RectF()
    /** Sampler-Pad lange gedrückt: Audio darauf laden (Pad). */
    var onSamplerLoad: ((Int) -> Unit)? = null

    // ================================================================ Zustand der Oberfläche
    /** Fokus-Deck für die 4 Regler und die XY-Fläche. */
    private var focus = 0
    /** Pads: 0 = CUES, 1 = LOOP, 2 = ROLL, 3 = FX, 4 = SAMPLER. */
    private var padMode = 0
    /** Welches Deck die (kleinen) Pads gerade steuern; groß zeigen beide Decks. */
    private var padDeck = 0
    /** Sampler (klein): Seite 0 = Pads 1–8, 1 = Pads 9–12 + Steuerung. */
    private var samplerPage = 0
    /** Pad-Bereich hochgeschoben: Regler/XY ausgeblendet, Pads groß (beide Decks bzw. alle Sampler). */
    var padsBig = false
        private set
    /** Querformat: Deck A links, Deck B rechts, Mischpult in der Mitte (wie ein Controller). */
    private var land = false
    /** Beide Decks gleichzeitig mit je 8 Pads (groß hochkant oder Querformat). */
    private val dual: Boolean get() = padsBig || land
    private var padCount = 8
    private val MODE_NAMES = arrayOf("CUES", "LOOP", "ROLL", "FX", "SAMPLER", "STEMS")
    private val MODE_COLS = intArrayOf(Color.rgb(255, 150, 40), Color.rgb(60, 200, 120), Color.rgb(70, 160, 255), Color.rgb(255, 70, 110), Color.rgb(190, 120, 255), Color.rgb(40, 210, 200))
    /** Farben der Stems (VOCALS, MELODIE, BASS, DRUMS). */
    private val STEM_COLS = intArrayOf(Color.rgb(255, 214, 90), Color.rgb(120, 200, 255), Color.rgb(255, 90, 90), Color.rgb(150, 235, 120))
    /** Stem-Pad wurde gezogen (Pegel) statt getippt. */
    private val stemDragged = BooleanArray(16)
    private val ROLLS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f)
    private val tabBar = RectF()
    private val tabDeckR = RectF()
    private val tabModeR = Array(6) { RectF() }
    private val tabBigR = RectF()
    private val tabFxStop = RectF()
    private var xyHold = false
    /** Sampler: nächstes angetipptes Pad wird neu geladen. */
    private var loadArm = false
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
    private val CONFETTI = intArrayOf(Color.rgb(240, 160, 190), Color.rgb(180, 160, 230), Color.rgb(140, 210, 190), Color.rgb(240, 215, 120), Color.rgb(150, 190, 235), Color.rgb(245, 175, 140))
    /** Chihuahua-Gesichter für die Pads (aus der Design-Vorlage „Chihuahua Mama“). */
    private val chiFaces: Array<Bitmap?> by lazy {
        val ids = intArrayOf(R.drawable.chi_1, R.drawable.chi_2, R.drawable.chi_3, R.drawable.chi_4, R.drawable.chi_5, R.drawable.chi_6,
            R.drawable.chi_7, R.drawable.chi_8, R.drawable.chi_9, R.drawable.chi_10, R.drawable.chi_11, R.drawable.chi_12)
        Array(ids.size) { i -> try { BitmapFactory.decodeResource(resources, ids[i]) } catch (e: Throwable) { null } }
    }
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
    private val masterBtn = RectF()
    private val setRecBtn = RectF()
    var onSetRec: (() -> Unit)? = null
    private val masterPanel = MasterPanel(this).also { mp -> mp.onHint = { onHint?.invoke(it) } }
    private val display = RectF()
    private val lanes = arrayOf(RectF(), RectF())
    private val overview = arrayOf(RectF(), RectF())
    private val knobs = Array(4) { RectF() }
    private val tops = Array(3) { RectF() }
    private val pitch = arrayOf(RectF(), RectF())
    private val vol = arrayOf(RectF(), RectF())
    /** je Deck: PLAY, CUE, SYNC, 🎧, FX */
    private val side = Array(2) { Array(5) { RectF() } }
    private val xy = RectF()
    private val xfader = RectF()
    private val pads = Array(16) { RectF() }

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
        land = w > h * 1.15f
        if (land) { layoutLand(w, h); return }
        val m = dp(10f)
        val fr = when (skin.frame) { 1 -> dp(12f); 2 -> dp(18f); else -> 0f }
        panel.set(fr + dp(4f), insetTop + dp(4f), w - fr - dp(4f), h - insetBottom - dp(4f))
        val L = panel.left + m
        val R = panel.right - m
        var y = panel.top + dp(6f)
        header.set(L, y, R, y + dp(52f))
        menuBtn.set(R - dp(40f), y + dp(10f), R, y + dp(42f))
        padsBtn.set(menuBtn.left - dp(78f), y + dp(10f), menuBtn.left - dp(6f), y + dp(42f))
        // MASTER und darunter die Set-Aufnahme
        masterBtn.set(padsBtn.left - dp(72f), y + dp(1f), padsBtn.left - dp(6f), y + dp(25f))
        setRecBtn.set(masterBtn.left, y + dp(27f), masterBtn.right, y + dp(51f))
        bpmChip.set(masterBtn.left - dp(92f), y + dp(10f), masterBtn.left - dp(6f), y + dp(42f))
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
        fun off(r: RectF) = r.set(-9999f, -9999f, -9998f, -9998f)
        if (padsBig) {
            for (r in knobs) off(r)
            for (r in tops) off(r)
            for (k in 0..1) { off(pitch[k]); off(vol[k]); for (r in side[k]) off(r) }
            off(xy)
            val xh = dp(34f)
            xfader.set(L + (R - L) * 0.12f, y, R - (R - L) * 0.12f, y + xh)
            y += xh + dp(8f)
        } else {
            val kh = min(dp(60f), avail * 0.10f)
            val kw = (R - L) / 4f
            for (i in 0..3) knobs[i].set(L + i * kw, y, L + (i + 1) * kw, y + kh + dp(12f))
            y += kh + dp(16f)
            val th = dp(28f)
            val tw = dp(64f)
            val tg = dp(14f)
            val tx0 = (L + R) / 2f - (3 * tw + 2 * tg) / 2f
            for (i in 0..2) tops[i].set(tx0 + i * (tw + tg), y, tx0 + i * (tw + tg) + tw, y + th)
            y += th + dp(10f)
            val midH = max(dp(140f), avail * 0.25f)
            val pw = dp(30f)
            val vw = dp(36f)
            val bw = dp(44f)
            val g = dp(5f)
            pitch[0].set(L, y, L + pw, y + midH)
            vol[0].set(pitch[0].right + g, y, pitch[0].right + g + vw, y + midH)
            pitch[1].set(R - pw, y, R, y + midH)
            vol[1].set(pitch[1].left - g - vw, y, pitch[1].left - g, y + midH)
            val sbH = (midH - 4 * dp(5f)) / 5f
            for (k in 0..1) {
                val x0 = if (k == 0) vol[0].right + g else vol[1].left - g - bw
                for (i in 0..4) side[k][i].set(x0, y + i * (sbH + dp(5f)), x0 + bw, y + i * (sbH + dp(5f)) + sbH)
            }
            val xl = side[0][0].right + dp(10f)
            val xr = side[1][0].left - dp(10f)
            val xs = min(xr - xl, midH)
            xy.set((xl + xr) / 2f - xs / 2f, y + (midH - xs) / 2f, (xl + xr) / 2f + xs / 2f, y + (midH + xs) / 2f)
            y += midH + dp(10f)
            val xh = dp(40f)
            xfader.set(L + (R - L) * 0.12f, y, R - (R - L) * 0.12f, y + xh)
            y += xh + dp(8f)
        }
        // Reiter-Leiste: Deck/Seite · CUES LOOP ROLL FX SAMPLER · groß/klein
        val tabH = dp(36f)
        tabBar.set(L, y, R, y + tabH)
        val showDeckTab = !padsBig || padMode == 4
        val dw = if (showDeckTab) dp(50f) else 0f
        val bw2 = dp(42f)
        if (showDeckTab) tabDeckR.set(L, y, L + dw, y + tabH) else tabDeckR.setEmpty()
        tabBigR.set(R - bw2, y, R, y + tabH)
        val mx0 = L + dw + (if (showDeckTab) dp(6f) else 0f)
        val mw = (tabBigR.left - dp(6f) - mx0) / 6f
        for (i in 0..5) tabModeR[i].set(mx0 + i * mw + dp(1.5f), y, mx0 + (i + 1) * mw - dp(1.5f), y + tabH)
        y += tabH + dp(8f)
        // Pads: 4 Spalten, klein 2 Reihen, groß 4 Reihen (Deck A oben, Deck B unten bzw. alle Sampler)
        val pb = panel.bottom - m
        val rows = if (padsBig) 4 else 2
        val cols = 4
        padCount = rows * cols
        val pg = dp(8f)
        val blockGap = if (padsBig && padMode != 4) dp(12f) else 0f
        val phh = (pb - y - (rows - 1) * pg - blockGap) / rows
        val pww = (R - L - (cols - 1) * pg) / cols
        for (r in 0 until rows) for (cI in 0 until cols) {
            val x0 = L + cI * (pww + pg)
            val y0 = y + r * (phh + pg) + (if (r >= 2) blockGap else 0f)
            pads[r * cols + cI].set(x0, y0, x0 + pww, y0 + phh)
        }
        for (i in padCount until pads.size) off(pads[i])
    }

    /**
     * Querformat wie ein DJ-Controller: links Deck A (Wellenform, Play/Cue/Sync/🎧/FX, 4×2 Pads,
     * Pitch außen), rechts Deck B gespiegelt, in der Mitte Reiter, Regler, Kanal-Fader, XY und Crossfader.
     */
    private fun layoutLand(w: Float, h: Float) {
        fun off(r: RectF) = r.set(-9999f, -9999f, -9998f, -9998f)
        val m = dp(8f)
        val fr = when (skin.frame) { 1 -> dp(10f); 2 -> dp(14f); else -> 0f }
        panel.set(fr + dp(4f), insetTop + dp(4f), w - fr - dp(4f), h - insetBottom - dp(4f))
        val L = panel.left + m
        val R = panel.right - m
        var y = panel.top + dp(4f)
        header.set(L, y, R, y + dp(40f))
        menuBtn.set(R - dp(40f), y + dp(4f), R, y + dp(36f))
        padsBtn.set(menuBtn.left - dp(78f), y + dp(4f), menuBtn.left - dp(6f), y + dp(36f))
        setRecBtn.set(padsBtn.left - dp(78f), y + dp(4f), padsBtn.left - dp(6f), y + dp(36f))
        masterBtn.set(setRecBtn.left - dp(78f), y + dp(4f), setRecBtn.left - dp(6f), y + dp(36f))
        bpmChip.set(masterBtn.left - dp(96f), y + dp(4f), masterBtn.left - dp(6f), y + dp(36f))
        y = header.bottom + dp(6f)
        val bottom = panel.bottom - m
        val gap = dp(10f)
        val cw = max(dp(230f), (R - L) * 0.27f)
        val dw = (R - L - cw - 2 * gap) / 2f
        display.setEmpty()
        for (k in 0..1) {
            val x0 = if (k == 0) L else R - dw
            val x1 = x0 + dw
            val lh = (bottom - y) * 0.36f
            lanes[k].set(x0 + dp(4f), y + dp(4f), x1 - dp(4f), y + lh - dp(4f))
            overview[k].set(lanes[k].left, lanes[k].bottom - dp(7f), lanes[k].right, lanes[k].bottom - dp(1f))
            val pw = dp(30f)
            val y2 = y + lh + dp(6f)
            if (k == 0) pitch[0].set(x0, y2, x0 + pw, bottom - dp(12f)) else pitch[1].set(x1 - pw, y2, x1, bottom - dp(12f))
            val ix0 = if (k == 0) x0 + pw + dp(8f) else x0
            val ix1 = if (k == 0) x1 else x1 - pw - dp(8f)
            val sh = dp(34f)
            val sg = dp(5f)
            val sw = (ix1 - ix0 - 4 * sg) / 5f
            for (i in 0..4) side[k][i].set(ix0 + i * (sw + sg), y2, ix0 + i * (sw + sg) + sw, y2 + sh)
            val py = y2 + sh + dp(8f)
            val pg = dp(7f)
            val phh = (bottom - py - pg) / 2f
            val pww = (ix1 - ix0 - 3 * pg) / 4f
            for (r in 0..1) for (cI in 0..3) {
                val px = ix0 + cI * (pww + pg)
                val pyy = py + r * (phh + pg)
                pads[k * 8 + r * 4 + cI].set(px, pyy, px + pww, pyy + phh)
            }
        }
        padCount = 16
        // Mitte
        val cx0 = L + dw + gap
        val cx1 = cx0 + cw
        val tabH = dp(32f)
        tabBar.set(cx0, y, cx1, y + tabH)
        tabDeckR.setEmpty()
        tabBigR.setEmpty()
        val mw = (cx1 - cx0) / 6f
        for (i in 0..5) tabModeR[i].set(cx0 + i * mw + dp(1.5f), y, cx0 + (i + 1) * mw - dp(1.5f), y + tabH)
        var cy = y + tabH + dp(8f)
        val kh = min(dp(52f), (bottom - cy) * 0.2f)
        val kw = (cx1 - cx0) / 4f
        for (i in 0..3) knobs[i].set(cx0 + i * kw, cy, cx0 + (i + 1) * kw, cy + kh + dp(12f))
        cy += kh + dp(16f)
        val th = dp(26f)
        val tg = dp(6f)
        val tw = (cx1 - cx0 - 2 * tg) / 3f
        for (i in 0..2) tops[i].set(cx0 + i * (tw + tg), cy, cx0 + i * (tw + tg) + tw, cy + th)
        cy += th + dp(10f)
        val xh = dp(36f)
        val vb = bottom - xh - dp(10f)
        val vw = dp(34f)
        vol[0].set(cx0, cy + dp(8f), cx0 + vw, vb)
        vol[1].set(cx1 - vw, cy + dp(8f), cx1, vb)
        val xl = vol[0].right + dp(8f)
        val xr = vol[1].left - dp(8f)
        val xs = min(xr - xl, vb - cy)
        xy.set((xl + xr) / 2f - xs / 2f, cy + (vb - cy - xs) / 2f, (xl + xr) / 2f + xs / 2f, cy + (vb - cy + xs) / 2f)
        xfader.set(cx0 + dp(6f), bottom - xh, cx1 - dp(6f), bottom)
    }

    /** Pad-Bereich groß/klein umschalten. */
    fun setPadsBig(on: Boolean) {
        if (land || padsBig == on) return
        padsBig = on
        for (i in padDown.indices) padDown[i] = false
        layoutAll()
        invalidate()
        onHint?.invoke(if (on) "Pads groß – beide Decks (bzw. alle Sampler-Pads). ⌄ oder nach unten wischen = Regler zurück" else "Regler und XY wieder da")
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
                    8 -> { // Pastell-Kunststoff mit Konfetti-Sprenkeln
                        val n = (fbm(x / 140f, y / 140f) - 0.5f) * 8f
                        r += n; g += n; b += n
                        val hv = hash(x * 7 + 3, y * 11 + 5)
                        if (hv > 0.9965f) {
                            val pc = CONFETTI[((hv - 0.9965f) * 1e5f).toInt() % CONFETTI.size]
                            r = Color.red(pc).toFloat(); g = Color.green(pc).toFloat(); b = Color.blue(pc).toFloat()
                        }
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
        GhostLoop.poll()
        if (ghost.isOpen) {
            c.drawColor(skin.bg)
            ghost.draw(c, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), skin.accent)
            return
        }
        if (masterPanel.isOpen) {
            masterPanel.draw(c, RectF(dp(6f), insetTop + dp(6f), width - dp(6f), height - insetBottom - dp(6f)), skin.accent)
            return
        }
        if (editDeck >= 0) {
            drawEditor(c)
            postInvalidateOnAnimation()
            return
        }
        val s = skin
        c.drawColor(s.bg)
        drawBody(c)
        drawHeader(c)
        drawDisplay(c)
        val focusCol = if (focus == 0) s.colA else s.colB
        val dk = Dj.decks[focus]
        val kv = floatArrayOf(dk.hi, dk.mid, dk.lo, dk.filter)
        val kl = arrayOf("HI", "MID", "LOW", "FILTER")
        if (!padsBig) for (i in 0..3) drawKnob(c, knobs[i], i, kv[i], kl[i] + " " + "AB"[focus], focusCol)
        val fxName = Fx.NAMES[dk.fxType.coerceIn(0, Fx.COUNT - 1)]
        if (!padsBig) {
            drawTop(c, tops[0], 0, "DECK " + "AB"[focus], true, focusCol)
            drawTop(c, tops[1], 1, fxName, dk.fxAmt > 0.02f, s.accent)
            drawTop(c, tops[2], 2, "PADS ⌃", false, s.accent)
        }
        if (!padsBig) for (k in 0..1) {
            val dd = Dj.decks[k]
            val col = if (k == 0) s.colA else s.colB
            drawFader(c, pitch[k], 0.5f - dd.pitch * 0.5f, col, true, k)
            drawFader(c, vol[k], dd.fader, col, false, k)
            val blink = !dd.playing && dd.loaded() && (SystemClock.uptimeMillis() / 400) % 2 == 0L
            drawSide(c, side[k][0], "▶ ‖", dd.playing || blink, col)
            drawSide(c, side[k][1], "CUE", cuePreview[k], col)
            drawSide(c, side[k][2], "SYNC", dd.sync, col)
            drawSide(c, side[k][3], "🎧", dd.cue, col)
            drawSide(c, side[k][4], "FX", dd.fxOn || dd.fxAmt > 0.02f, col)
            // Pitch-Anzeige
            text.color = alpha(s.ink, 0.75f)
            val pr = if (dd.sync) "SYNC" else String.format(Locale.GERMANY, "%+.1f%%", dd.pitch * dd.range * 100f)
            c.drawText(fitLabel(text, pr, pitch[k].width() + dp(12f), dp(8.5f), dp(6.5f)), pitch[k].centerX(), pitch[k].bottom + dp(9f), text)
            c.drawText(fitLabel(text, "LINE " + "AB"[k], vol[k].width() + dp(10f), dp(8.5f), dp(6.5f)), vol[k].centerX(), vol[k].top - dp(3f), text)
        }
        if (!padsBig) drawXy(c)
        drawCross(c)
        drawTabBar(c)
        drawPads(c)
        if (Dj.splitCue) {
            text.textSize = dp(9f)
            text.color = s.accent
            c.drawText("🎧 SPLIT-CUE", xfader.centerX(), xfader.top - dp(2f), text)
        }
        if (lesson >= 0) drawLesson(c)
        postInvalidateOnAnimation()
    }

    // ================================================================ DJ-Lektion (geführt, zum Mitmachen)

    private class LStep(val title: String, val text: String, val target: () -> RectF? = { null },
                        val done: (() -> Boolean)? = null, val setup: (() -> Unit)? = null, val action: Pair<String, String>? = null)

    /** Aktionen für die Activity (z. B. Mashup-Dialog). */
    var onLessonAction: ((String) -> Unit)? = null
    var onLessonDone: (() -> Unit)? = null
    private var lesson = -1
    private var lSteps: List<LStep> = emptyList()
    private var lDoneAt = 0L
    private var lEnter = 0L
    private val lCard = RectF(); private val lNext = RectF(); private val lPrev = RectF(); private val lEnd = RectF(); private val lAct = RectF()
    private class Spark(var x: Float, var y: Float, val vx: Float, var vy: Float, var life: Float, val col: Int)
    private val sparks = ArrayList<Spark>()

    private val djLesson: List<LStep> by lazy { listOf(
        LStep("🎧 DJ-Grundlagen", "In dieser Lektion mixt du zwei Tracks ineinander: laden, starten, synchronisieren, mit EQ und Crossfader überblenden – und am Ende Stems, ⏭ DROP und Mashup. Der Rahmen zeigt dir, wo es weitergeht."),
        LStep("1 · Track in Deck A", "Tippe auf die Wellenform von Deck A (oder ▶) und lade einen Song – am besten aus der Musik-Sammlung.", { lanes[0] },
            done = { Dj.decks[0].loaded() }, action = "Deck A laden" to "loadA"),
        LStep("▶ 2 · Starten", "Drück ▶ bei Deck A. Das ist dein laufender Track – das Publikum hört ihn.", { side[0][0] },
            done = { Dj.decks[0].playing }),
        LStep("3 · Nächster Track in Deck B", "Lade den nächsten Song in Deck B. Tipp: In der Sammlung zeigt ✨ Vorschläge Tracks mit passendem Tempo und grüner (harmonischer) Tonart.", { lanes[1] },
            done = { Dj.decks[1].loaded() }, action = "Deck B laden" to "loadB"),
        LStep("🔗 4 · SYNC", "Tippe SYNC bei Deck B: Tempo und Takt werden automatisch angeglichen – die Beats laufen gleich.", { side[1][2] },
            done = { Dj.decks[1].sync }),
        LStep("🎚 5 · Crossfader nach links", "Bevor B startet: Crossfader ganz nach links (nur A ist zu hören). So kannst du B vorbereiten, ohne dass es jemand hört.", { xfader },
            done = { Dj.xfade < 0.15f }),
        LStep("▶ 6 · B starten", "Starte Deck B – am besten auf der Eins. Dank SYNC läuft es im Takt mit A. Mit 🎧 könntest du es im Kopfhörer vorhören.", { side[1][0] },
            done = { Dj.decks[1].playing }),
        LStep("〰 7 · Bass raus bei B", "Zwei Bässe gleichzeitig klingen matschig. Wähle oben DECK B (Umschalter) und dreh LOW ganz runter (Regler nach unten ziehen).", { knobs[2] },
            done = { Dj.decks[1].lo < 0.15f }),
        LStep("↔ 8 · Überblenden", "Schieb den Crossfader langsam zur Mitte und dann nach rechts – über 8 bis 16 Takte. B wird lauter, A leiser.", { xfader },
            done = { Dj.xfade > 0.85f }),
        LStep("〰 9 · Bass tauschen", "Profi-Trick: auf einer Eins LOW von B wieder hoch (Mitte) – jetzt trägt B den Bass. Dann kannst du A stoppen. Fertig ist der Übergang!", { knobs[2] },
            done = { Dj.decks[1].lo > 0.42f }),
        LStep("🎛 10 · Stems", "Tippe unten auf den Reiter STEMS. Damit nimmst du Vocals, Melodie, Bass oder Drums einzelner Tracks heraus – live.", { tabModeR[5] },
            done = { padMode == 5 }),
        LStep("🎤 11 · Acapella", "Tippe bei einem Deck ACAPELLA: nur noch der Gesang. Nochmal tippen = alles wieder an. (Mit 🧠 KI-Stems trennt es am saubersten.)", { union(pads, padCount) },
            done = { Dj.decks.any { d -> d.stemGain[0] > 0.9f && d.stemGain[1] < 0.05f && d.stemGain[2] < 0.05f && d.stemGain[3] < 0.05f } }),
        LStep("⏭ 12 · Zum Drop", "Über der Übersicht siehst du die Song-Teile (Intro, Build-up, DROP …). Tippe ⏭ DROP in der Wellenform – der Track springt im Takt zum nächsten Drop.", { if (!dropHit[1].isEmpty) dropHit[1] else if (!dropHit[0].isEmpty) dropHit[0] else lanes[1] }),
        LStep("🎭 13 · Mashup", "Zum Schluss: Der Mashup-Assistent legt den Gesang eines Decks über den Beat des anderen – Tonart, Tempo und Einsatz macht er selbst.", action = "Mashup-Assistent öffnen" to "mashup"),
        LStep("🎉 Geschafft!", "Du kannst jetzt mixen: laden, SYNC, EQ-Übergang, Crossfader, Stems, Drops und Mashups. Für den Auftritt: Menü → DJ → GIG-MODUS.")
    ) }

    private fun union(rs: Array<RectF>, n: Int): RectF? {
        val u = RectF(); var any = false
        for (i in 0 until n) { val r = rs[i]; if (r.width() > 0f) { if (!any) u.set(r) else u.union(r); any = true } }
        return if (any) u else null
    }

    fun startLesson() {
        lesson = 0
        lSteps = djLesson
        enter(0)
        invalidate()
    }

    private fun enter(i: Int) {
        lesson = i.coerceIn(0, lSteps.size - 1)
        lDoneAt = 0L
        lEnter = SystemClock.uptimeMillis()
        try { lSteps[lesson].setup?.invoke() } catch (ignored: Throwable) {}
    }

    private fun drawLesson(c: Canvas) {
        val st = lSteps.getOrNull(lesson) ?: return
        val now = SystemClock.uptimeMillis()
        val acc = skin.accent
        val ok = Color.rgb(80, 220, 120)
        val w = width.toFloat(); val h = height.toFloat()
        val tgt = try { st.target()?.let { if (it.width() > 0f) RectF(it).apply { inset(-dp(5f), -dp(5f)) } else null } } catch (e: Throwable) { null }
        val d = st.done
        if (d != null && lDoneAt == 0L && (try { d() } catch (e: Throwable) { false })) {
            lDoneAt = now
            haptic()
            val cx = tgt?.centerX() ?: w / 2; val cy = tgt?.centerY() ?: h / 2
            val rnd = java.util.Random()
            repeat(50) { val a = rnd.nextFloat() * 6.283f; val v = dp(80f + rnd.nextFloat() * 220f); sparks.add(Spark(cx, cy, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v, 0.6f + rnd.nextFloat() * 0.6f, if (rnd.nextBoolean()) ok else acc)) }
        }
        if (lDoneAt > 0L && now - lDoneAt > 1100L) {
            if (lesson < lSteps.size - 1) enter(lesson + 1)
            return
        }
        fill.shader = null
        fill.color = Color.argb(110, 0, 0, 0)
        if (tgt == null) c.drawRect(0f, 0f, w, h, fill) else {
            c.drawRect(0f, 0f, w, tgt.top, fill); c.drawRect(0f, tgt.bottom, w, h, fill)
            c.drawRect(0f, tgt.top, tgt.left, tgt.bottom, fill); c.drawRect(tgt.right, tgt.top, w, tgt.bottom, fill)
            val pulse = 0.5f + 0.5f * kotlin.math.sin(now / 160f)
            stroke.strokeWidth = dp(2.5f + 2f * pulse)
            stroke.color = if (lDoneAt > 0L) ok else acc
            c.drawRoundRect(tgt, dp(10f), dp(10f), stroke)
            if (lDoneAt == 0L && d != null) {
                // tippender Finger
                val ph = (now % 900L) / 900f
                stroke.strokeWidth = dp(2f); stroke.color = alpha(Color.WHITE, (1f - ph) * 0.8f)
                c.drawCircle(tgt.centerX(), tgt.centerY(), dp(8f) + ph * dp(22f), stroke)
                text.textAlign = Paint.Align.CENTER; text.textSize = dp(26f); text.color = Color.WHITE
                c.drawText("☝", tgt.centerX() + dp(7f), tgt.centerY() + dp(28f) + (if (ph < 0.25f) dp(4f) * ph / 0.25f else dp(4f) * (1f - (ph - 0.25f) / 0.75f)), text)
            }
        }
        // Funken
        val iter = sparks.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.life -= 0.016f
            if (p.life <= 0f) { iter.remove(); continue }
            p.x += p.vx * 0.016f; p.y += p.vy * 0.016f; p.vy += dp(300f) * 0.016f
            fill.color = alpha(p.col, p.life.coerceIn(0f, 1f))
            c.drawCircle(p.x, p.y, dp(3f), fill)
        }
        // Karte
        val m = dp(12f)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(13f)
        val maxW = w - 2 * m - dp(28f)
        val lines = ArrayList<String>()
        var line = ""
        for (wd in st.text.split(' ')) { val t = if (line.isEmpty()) wd else "$line $wd"; if (text.measureText(t) > maxW && line.isNotEmpty()) { lines.add(line); line = wd } else line = t }
        lines.add(line)
        val cardH = dp(60f) + lines.size * dp(18f) + dp(54f) + (if (st.action != null) dp(46f) else 0f)
        val top = if (tgt == null) (h - cardH) / 2f else if (tgt.centerY() > h * 0.5f) insetTop + m else h - insetBottom - m - cardH
        lCard.set(m, top, w - m, top + cardH)
        fill.color = Color.rgb(22, 23, 30)
        c.drawRoundRect(lCard, dp(16f), dp(16f), fill)
        stroke.strokeWidth = dp(1.5f); stroke.color = if (lDoneAt > 0L) ok else acc
        c.drawRoundRect(lCard, dp(16f), dp(16f), stroke)
        val pw = lCard.width() - dp(32f)
        fill.color = Color.argb(50, 255, 255, 255)
        c.drawRect(lCard.left + dp(16f), lCard.top + dp(10f), lCard.left + dp(16f) + pw, lCard.top + dp(13f), fill)
        fill.color = acc
        c.drawRect(lCard.left + dp(16f), lCard.top + dp(10f), lCard.left + dp(16f) + pw * (lesson + (if (lDoneAt > 0L) 1 else 0)) / max(1, lSteps.size - 1).toFloat(), lCard.top + dp(13f), fill)
        text.textSize = dp(16f); text.color = if (lDoneAt > 0L) ok else Color.WHITE
        c.drawText((if (lDoneAt > 0L) "✓ " else "") + st.title, lCard.left + dp(16f), lCard.top + dp(36f), text)
        lEnd.set(lCard.right - dp(38f), lCard.top + dp(16f), lCard.right - dp(10f), lCard.top + dp(44f))
        text.textAlign = Paint.Align.CENTER; text.textSize = dp(16f); text.color = alpha(Color.WHITE, 0.7f)
        c.drawText("✕", lEnd.centerX(), lEnd.centerY() + dp(6f), text)
        text.textAlign = Paint.Align.LEFT; text.textSize = dp(13f); text.color = alpha(Color.WHITE, 0.9f)
        var y = lCard.top + dp(58f)
        for (ln in lines) { c.drawText(ln, lCard.left + dp(16f), y, text); y += dp(18f) }
        lAct.setEmpty()
        st.action?.let { (label, _) ->
            lAct.set(lCard.left + dp(16f), y, lCard.right - dp(16f), y + dp(38f))
            fill.color = alpha(acc, 0.22f); c.drawRoundRect(lAct, dp(10f), dp(10f), fill)
            stroke.strokeWidth = dp(1.2f); stroke.color = acc; c.drawRoundRect(lAct, dp(10f), dp(10f), stroke)
            text.textAlign = Paint.Align.CENTER; text.color = Color.WHITE; text.textSize = dp(14f)
            c.drawText("▶ $label", lAct.centerX(), lAct.centerY() + dp(5f), text)
        }
        val bh = dp(40f); val by = lCard.bottom - bh - dp(12f); val bw = (lCard.width() - dp(40f)) / 2f
        lPrev.set(lCard.left + dp(16f), by, lCard.left + dp(16f) + bw, by + bh)
        lNext.set(lPrev.right + dp(8f), by, lCard.right - dp(16f), by + bh)
        val last = lesson == lSteps.size - 1
        val waiting = d != null && lDoneAt == 0L
        for ((r, lab, on) in arrayOf(Triple(lPrev, "◀ Zurück", false), Triple(lNext, if (last) "Fertig ✓" else if (waiting) "Überspringen ▷" else "Weiter ▶", !waiting))) {
            if (r === lPrev && lesson == 0) continue
            fill.color = if (on) acc else Color.rgb(44, 46, 56)
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
            text.textAlign = Paint.Align.CENTER; text.textSize = dp(14f); text.color = if (on) Color.BLACK else Color.WHITE
            c.drawText(lab, r.centerX(), r.centerY() + dp(5f), text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    /** true = Berührung gehörte zur Lektions-Karte. */
    private fun lessonTouch(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i); val y = e.getY(i)
        if (!lCard.contains(x, y)) return false
        val down = e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (!down) return true
        haptic()
        val st = lSteps.getOrNull(lesson)
        when {
            lEnd.contains(x, y) -> lesson = -1
            !lAct.isEmpty && lAct.contains(x, y) -> st?.action?.second?.let { id ->
                when (id) { "loadA" -> onLoad?.invoke(0); "loadB" -> onLoad?.invoke(1); else -> onLessonAction?.invoke(id) }
            }
            lNext.contains(x, y) -> if (lesson >= lSteps.size - 1) { lesson = -1; onLessonDone?.invoke() } else enter(lesson + 1)
            lPrev.contains(x, y) && lesson > 0 -> enter(lesson - 1)
        }
        invalidate()
        return true
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
            logoHit.set(tmp)
        }
        val b = Engine.currentBpm()
        chip(c, bpmChip, if (b > 0f) String.format(Locale.GERMANY, "%.1f BPM", b) else "– BPM", Engine.seqPlaying)
        chip(c, padsBtn, "▦ PADS", false)
        chip(c, masterBtn, "MASTER", MasterBus.anyOn())
        val rec = SetRecorder.recording
        fill.shader = null
        fill.color = if (rec) Color.rgb(200, 30, 45) else if (skin.dark) Color.argb(120, 0, 0, 0) else Color.argb(40, 0, 0, 0)
        c.drawRoundRect(setRecBtn, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = if (rec) Color.rgb(255, 90, 100) else alpha(skin.ink, 0.35f)
        c.drawRoundRect(setRecBtn, dp(9f), dp(9f), stroke)
        val blink = rec && (SystemClock.uptimeMillis() / 500) % 2 == 0L
        fill.color = if (rec && !blink) Color.WHITE else Color.rgb(235, 40, 55)
        c.drawCircle(setRecBtn.left + dp(10f), setRecBtn.centerY(), dp(4f), fill)
        text.color = if (rec) Color.WHITE else skin.ink
        val recLbl = fitLabel(text, if (rec) SetRecorder.fmt(SetRecorder.seconds()) else "REC", setRecBtn.width() - dp(22f), dp(11f), dp(7f))
        c.drawText(recLbl, setRecBtn.centerX() + dp(6f), setRecBtn.centerY() + text.textSize * 0.36f, text)
        chip(c, menuBtn, "☰", false)
        if (Dj.gigMode) {
            text.textSize = dp(9f)
            text.color = Color.rgb(255, 70, 90)
            c.drawText("● GIG", bpmChip.centerX(), bpmChip.bottom + dp(9f), text)
        }
    }

    private fun chip(c: Canvas, r: RectF, label: String, on: Boolean) {
        val s = skin
        fill.shader = null
        fill.color = if (on) alpha(s.accent, 0.85f) else if (s.dark) Color.argb(120, 0, 0, 0) else Color.argb(40, 0, 0, 0)
        c.drawRoundRect(r, dp(9f), dp(9f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(s.ink, 0.35f)
        c.drawRoundRect(r, dp(9f), dp(9f), stroke)
        text.color = if (on) Color.WHITE else s.ink
        val lb = fitLabel(text, label, r.width() - dp(8f), dp(12f), dp(7f))
        c.drawText(lb, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    // ---------------------------------------------------------------- Display mit beiden Decks

    private val secondsVisible = 6.0

    private fun drawDisplay(c: Canvas) {
        val s = skin
        if (land) {
            // je Deck ein eigener Bildschirm über seiner Spalte
            for (k in 0..1) {
                val r = RectF(lanes[k].left - dp(4f), lanes[k].top - dp(4f), lanes[k].right + dp(4f), lanes[k].bottom + dp(4f))
                val rad = dp(10f)
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
                drawLane(c, k)
                c.restore()
                stroke.strokeWidth = dp(1f)
                stroke.color = alpha(s.dispInk, 0.35f)
                c.drawRoundRect(r, rad, rad, stroke)
            }
            return
        }
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
            dd.loading -> if (dd.loadProg >= 0f) "Lädt … ${(dd.loadProg * 100).toInt()} %" + (if (dd.loadProg >= 0.86f) " · Analyse" else "") else "Lädt …"
            dd.loaded() -> dd.title
            else -> "⏏ antippen: Track laden (MP3/WAV)"
        }
        val info = if (dd.loaded()) {
            val rem = (dd.len - dd.pos) / dd.srcRate / max(0.01, dd.ratio)
            String.format(Locale.GERMANY, "%.1f  −%s", dd.effBpm(), Dj.fmt(rem))
        } else ""
        mono.textAlign = Paint.Align.RIGHT
        c.drawText(info, ln.right - dp(2f), ln.top + dp(11f), mono)
        if (dd.loading && dd.loadProg >= 0f) {
            // Ladebalken quer über die Wellenform-Fläche
            fill.shader = null
            val by = wave.centerY()
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(wave.left + dp(10f), by - dp(4f), wave.right - dp(10f), by + dp(4f), dp(4f), dp(4f), fill)
            fill.color = col
            c.drawRoundRect(wave.left + dp(10f), by - dp(4f), wave.left + dp(10f) + (wave.width() - dp(20f)) * dd.loadProg.coerceIn(0.02f, 1f), by + dp(4f), dp(4f), dp(4f), fill)
            postInvalidateOnAnimation()
        }
        var iw = mono.measureText(info) + dp(8f)
        // Tonart (Camelot) – Farbe zeigt, ob sie zum anderen Deck passt
        keyHit[k].setEmpty()
        if (dd.loaded()) {
            val ek = dd.effKey()
            val other = Dj.decks[1 - k]
            val ok = if (other.loaded()) Keys.compat(other.effKey(), ek) else -1
            val kc = when (ok) {
                0, 1 -> Color.rgb(70, 220, 110)
                2 -> Color.rgb(250, 190, 40)
                3 -> Color.rgb(255, 80, 80)
                else -> alpha(ink, 0.75f)
            }
            val drift = dd.keyDrift()
            val ks = if (ek < 0) (if (dd.key < 0 && dd.keyGen > 0) "KEY…" else "KEY?") else
                Keys.camelot(ek) + (if (dd.keyShift != 0) (if (dd.keyShift > 0) "+" else "") + dd.keyShift else "") + (if (abs(drift) >= 0.25) "~" else "") + (if (dd.keylock) "🔒" else "")
            val kx = ln.right - dp(2f) - iw
            val kw = mono.measureText(ks) + dp(6f)
            tmp.set(kx - kw, ln.top + dp(1f), kx + dp(2f), ln.top + dp(14f))
            fill.color = alpha(kc, 0.18f)
            c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
            mono.color = kc
            c.drawText(ks, kx - dp(1f), ln.top + dp(11f), mono)
            mono.color = ink
            keyHit[k].set(tmp.left - dp(4f), ln.top - dp(4f), tmp.right + dp(2f), ln.top + dp(18f))
            iw += kw + dp(4f)
        }
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
                    val wc = if (Dj.colorWave && dd.mids.size == pk.size) bandColor(lo, (dd.mids[bi].toInt() and 0xFF) / 255f, (dd.highs[bi].toInt() and 0xFF) / 255f) else blend(highCol, lowCol, lo)
                    stroke.color = if (f < pos) alpha(wc, 0.55f) else wc
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
                    val bi = kk - dd.barOffset
                    val bar = Math.floorMod(bi, 4L) == 0L
                    val barNo = Math.floorDiv(bi, 4L) - dd.phraseOff
                    val phrase = bar && Math.floorMod(barNo, 16L) == 0L
                    val half = bar && Math.floorMod(barNo, 8L) == 0L
                    if (phrase || half) {
                        // Phrasengrenze (16 Takte) bzw. halbe Phrase (8 Takte): durchgehende Linie
                        stroke.strokeWidth = dp(if (phrase) 2.2f else 1.2f)
                        stroke.color = if (phrase) alpha(Color.rgb(255, 210, 60), 0.85f) else alpha(ink, 0.45f)
                        c.drawLine(bx, wave.top, bx, wave.bottom, stroke)
                        continue
                    }
                    stroke.strokeWidth = dp(if (bar) 1.4f else 0.8f)
                    stroke.color = alpha(ink, if (bar) 0.55f else 0.22f)
                    c.drawLine(bx, wave.top, bx, wave.top + dp(if (bar) 6f else 3f), stroke)
                    c.drawLine(bx, wave.bottom, bx, wave.bottom - dp(if (bar) 6f else 3f), stroke)
                }
                // Phrasen-Countdown: Takt in der Phrase und Takte bis zur nächsten
                val (inP, toNext) = Dj.phraseInfo(dd, pos)
                val barsLeft = Math.ceil(toNext / 4.0 - 1e-6).toInt()
                val soon = barsLeft <= 4 && dd.playing
                val blink = soon && (SystemClock.uptimeMillis() / 250) % 2 == 0L
                mono.textSize = dp(9f)
                mono.textAlign = Paint.Align.LEFT
                mono.color = if (soon) (if (blink) Color.rgb(255, 210, 60) else alpha(Color.rgb(255, 210, 60), 0.6f)) else alpha(ink, 0.7f)
                val beatInBar = Math.floorMod(floor(Dj.beatIndex(dd, pos)).toLong() - dd.barOffset, 4L) + 1
                // Song-Struktur: aktueller Abschnitt + Sprung-Knöpfe
                val part = Dj.partAt(dd, pos)
                val pre = if (part != null) SongParts.NAMES[part.kind] + " · " else ""
                val ptxt = "${pre}PHRASE ${inP + 1}.$beatInBar/16 · noch $barsLeft T"
                c.drawText(ptxt, wave.left + dp(6f), wave.bottom - dp(3f), mono)
                partHit[k].setEmpty(); dropHit[k].setEmpty()
                if (dd.parts.isNotEmpty()) {
                    var xx = wave.left + dp(12f) + mono.measureText(ptxt)
                    val by = wave.bottom - dp(3f)
                    fun chip(r: RectF, label: String, col: Int) {
                        val w = mono.measureText(label) + dp(10f)
                        r.set(xx, by - dp(11f), xx + w, by + dp(2f))
                        fill.shader = null
                        fill.color = alpha(col, 0.28f)
                        c.drawRoundRect(r, dp(4f), dp(4f), fill)
                        mono.color = col
                        c.drawText(label, xx + dp(5f), by - dp(1f), mono)
                        xx += w + dp(6f)
                    }
                    chip(partHit[k], "⏭ TEIL", ink)
                    val cb = floor(Dj.barAt(dd, pos))
                    if (dd.parts.any { it.kind == SongParts.DROP && it.bar0 > cb }) chip(dropHit[k], "⏭ DROP", PART_COLS[SongParts.DROP])
                }
            }
            // Cue und Hot Cues
            marker(c, cx + ((dd.cuePoint - pos) / fpp).toFloat(), wave, Color.rgb(255, 170, 0))
            for (h in dd.hot.indices) if (dd.hot[h] >= 0) marker(c, cx + ((dd.hot[h] - pos) / fpp).toFloat(), wave, HOT[h % HOT.size])
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
                val oc = if (Dj.colorWave && dd.mids.size == pk.size) bandColor((lw[bi].toInt() and 0xFF) / 255f, (dd.mids[bi].toInt() and 0xFF) / 255f, (dd.highs[bi].toInt() and 0xFF) / 255f) else blend(col, Color.WHITE, 0.4f)
                stroke.color = if (f < pos) alpha(oc, 0.45f) else alpha(oc, 0.9f)
                val a = v * ov.height() / 2f
                c.drawLine(ov.left + i, ov.centerY() - a, ov.left + i, ov.centerY() + a, stroke)
            }
            // Song-Struktur als Farbband über der Übersicht
            if (dd.parts.isNotEmpty() && dd.bpm > 0f) {
                val first = dd.grid + dd.barOffset * dd.beatFrames()
                val barF = dd.beatFrames() * 4
                for (pt in dd.parts) {
                    val x0 = ov.left + ((first + pt.bar0 * barF) / dd.len).toFloat() * ov.width()
                    val x1 = ov.left + ((first + pt.bar1 * barF) / dd.len).toFloat() * ov.width()
                    fill.shader = null
                    fill.color = PART_COLS[pt.kind]
                    c.drawRect(max(ov.left, x0), ov.top - dp(3f), min(ov.right, x1) - dp(1f), ov.top - dp(1f), fill)
                }
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

    /** Farbe aus den Frequenzanteilen: Bass rot, Mitten grün, Höhen blau (wie Serato/Rekordbox RGB). */
    private fun bandColor(lo: Float, mid: Float, hi: Float): Int {
        val r = lo * 1.25f + mid * 0.15f
        val g = mid * 1.1f + lo * 0.12f + hi * 0.25f
        val b = hi * 1.25f + mid * 0.2f
        val m = max(1e-3f, max(r, max(g, b)))
        val k = 255f / m
        return Color.rgb((r * k * 0.95f + 12).toInt().coerceIn(0, 255), (g * k * 0.92f + 12).toInt().coerceIn(0, 255), (b * k + 12).toInt().coerceIn(0, 255))
    }

    private val keyHit = arrayOf(RectF(), RectF())
    /** Song-Struktur: Sprung zum nächsten Abschnitt / Drop (je Deck). */
    private val partHit = arrayOf(RectF(), RectF())
    private val dropHit = arrayOf(RectF(), RectF())
    private val PART_COLS = intArrayOf(Color.rgb(120, 150, 200), Color.rgb(160, 160, 160), Color.rgb(250, 200, 60), Color.rgb(255, 70, 80), Color.rgb(70, 200, 220), Color.rgb(170, 120, 220))
    /** Tonart-Feld im Deck angetippt (Keylock, Key-Shift, Tonart). */
    var onKeyMenu: ((Int) -> Unit)? = null

    private val HOT = intArrayOf(Color.rgb(255, 60, 90), Color.rgb(60, 220, 120), Color.rgb(70, 150, 255), Color.rgb(200, 90, 255),
        Color.rgb(255, 170, 40), Color.rgb(40, 220, 230), Color.rgb(255, 110, 200), Color.rgb(230, 230, 80))

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
        text.color = alpha(s.ink, 0.8f)
        c.drawText(fitLabel(text, label, box.width() + dp(4f), dp(9.5f), dp(6.5f)), cx, box.bottom - dp(1f), text)
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
        text.color = alpha(s.ink, 0.85f)
        val lbl = fitLabel(text, label, r.width() + dp(10f), dp(9f), dp(6.5f))
        c.drawText(lbl, r.centerX(), r.bottom + dp(10f), text)
    }

    private fun drawSide(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        val s = skin
        button(c, r, on, col, 9)
        val sideLbl = fitLabel(text, label, r.width() - dp(6f), min(dp(11f), r.height() * 0.38f), dp(6.5f))
        val lit = on && s.btn != 6
        text.color = when {
            lit && (s.btn == 1 || s.btn == 5) -> Color.rgb(20, 20, 20)
            on -> if (s.dark || s.btn == 1) Color.WHITE else col
            else -> alpha(if (s.btn == 8 || s.btn == 1 || s.dark) Color.WHITE else s.ink, 0.8f)
        }
        c.drawText(sideLbl, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
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

    /** Farbige Bonbon-Platte (Chihuahua Mama). */
    private fun pastelPlate(c: Canvas, r: RectF, col: Int) {
        shadowRect(c, r, dp(6f))
        fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, lighten(col, 0.12f), darken(col, 0.04f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(6f), dp(6f), fill)
        fill.shader = null
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(40, 0, 0, 0)
        c.drawRoundRect(r, dp(6f), dp(6f), stroke)
    }

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
            4 -> pastelPlate(c, r, if (isPitch) blend(col, Color.WHITE, 0.45f) else col)
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
            9 -> {
                // Bonbon-Kappe: Deck A rosa, Deck B hellblau
                val cc = if (k == 0) Color.rgb(244, 190, 208) else Color.rgb(176, 206, 236)
                shadowRect(c, cap, dp(4f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.left, cap.bottom, lighten(cc, 0.25f), darken(cc, 0.08f), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(4f), dp(4f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.2f)
                stroke.color = Color.argb(70, 0, 0, 0)
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
        text.color = alpha(s.ink, 0.75f)
        c.drawText(fitLabel(text, if (xyHold) "FX ${"AB"[focus]} · HOLD" else "FX ${"AB"[focus]}", r.width(), dp(8.5f), dp(6.5f)), r.centerX(), r.bottom + dp(10f), text)
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
            4 -> pastelPlate(c, r, Color.rgb(243, 230, 166))
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
            9 -> {
                val cc = Color.rgb(201, 184, 234)
                shadowRect(c, cap, dp(4f))
                fill.shader = LinearGradient(cap.left, cap.top, cap.right, cap.top, lighten(cc, 0.2f), darken(cc, 0.1f), Shader.TileMode.CLAMP)
                c.drawRoundRect(cap, dp(4f), dp(4f), fill)
                fill.shader = null
                stroke.strokeWidth = dp(1.4f)
                stroke.color = Color.argb(120, 255, 255, 255)
                c.drawLine(x, cap.top + dp(3f), x, cap.bottom - dp(3f), stroke)
            }
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

    private val padDown = BooleanArray(16)
    private val padFlash = FloatArray(16)

    /** Was ein Pad gerade bedeutet: Beschriftung, leuchtet?, Farbe, muss gehalten werden?, Deck (-1 = Sampler), Platz. */
    private class PadSpec(val label: String, val lit: Boolean, val col: Int, val hold: Boolean, val deck: Int, val slot: Int)

    private fun padSpec(i: Int): PadSpec {
        val mc = MODE_COLS[padMode]
        if (padMode == 4) {
            val idx = if (dual) i else samplerPage * 8 + i
            if (idx < 12) {
                val p = idx
                val st = Engine.state(p)
                val on = st == Engine.PadState.PLAYING || st == Engine.PadState.RECORDING || st == Engine.PadState.OVERDUB
                val nm = Engine.padName(p)
                val lbl = when (st) {
                    Engine.PadState.EMPTY -> "${p + 1} ＋"
                    Engine.PadState.RECORDING -> "● ${p + 1}"
                    Engine.PadState.ONESHOT -> "⚡${p + 1}" + if (nm != null) " " + nm else ""
                    else -> "∞${p + 1}" + if (nm != null) " " + nm else ""
                }
                return PadSpec(lbl, on || Engine.levels[p] > 0.05f, mc, false, -1, p)
            }
            return when (idx) {
                12 -> PadSpec(if (Engine.seqPlaying) "■ STOP" else "▶ PLAY", Engine.seqPlaying, skin.accent, false, -1, idx)
                13 -> PadSpec("⏏ LADEN", loadArm, skin.accent, false, -1, idx)
                14 -> PadSpec("🎙 MIC", Engine.monitorOn, skin.accent, false, -1, idx)
                else -> PadSpec("TAP", false, skin.accent, false, -1, idx)
            }
        }
        val k = if (dual) i / 8 else padDeck
        val sl = if (dual) i % 8 else i
        val dd = Dj.decks[k]
        if (padMode == 5) {
            val g = dd.stemGain
            val busy = dd.stemProg >= 0f
            return when (sl) {
                in 0..3 -> {
                    val pct = (g[sl] * 100).toInt()
                    PadSpec(DeckStems.NAMES[sl] + if (pct in 1..99) " $pct%" else if (pct == 0) " ✕" else "", g[sl] > 0.02f && dd.loaded(), STEM_COLS[sl], false, k, sl)
                }
                4 -> PadSpec("ACAPELLA", g[0] > 0.9f && g[1] < 0.05f && g[2] < 0.05f && g[3] < 0.05f, mc, false, k, sl)
                5 -> PadSpec("INSTRUMENTAL", g[0] < 0.05f && g[1] > 0.9f && g[2] > 0.9f && g[3] > 0.9f, mc, false, k, sl)
                6 -> PadSpec("NUR BEAT", g[0] < 0.05f && g[1] < 0.05f && g[2] > 0.9f && g[3] > 0.9f, mc, false, k, sl)
                else -> {
                    val ai = dd.stemMask?.ai == true
                    PadSpec(when {
                        dd.aiProg >= 0f -> "🧠 KI ${(dd.aiProg * 100).toInt()}%"
                        busy -> "ANALYSE ${(dd.stemProg * 100).toInt()}%"
                        dd.loaded() && dd.stemMask == null -> "STEMS BERECHNEN"
                        ai -> "ALLE AN · KI"
                        dd.loaded() -> "ALLE AN · 🧠?"
                        else -> "ALLE AN"
                    }, busy || dd.aiProg >= 0f, mc, false, k, sl)
                }
            }
        }
        return when (padMode) {
            0 -> PadSpec("CUE ${sl + 1}", dd.hot[sl] >= 0, HOT[sl], false, k, sl)
            1 -> when (sl) {
                0 -> PadSpec("◀ JUMP", false, mc, false, k, sl)
                1 -> PadSpec("JUMP ${barsLabel(dd.jumpBars)}", false, mc, false, k, sl)
                2 -> PadSpec("JUMP ▶", false, mc, false, k, sl)
                3 -> PadSpec(if (dd.slipOn) "SLIP AN" else "SLIP", dd.slipOn, mc, false, k, sl)
                4 -> PadSpec("LOOP ½", false, mc, false, k, sl)
                5 -> PadSpec("LOOP ${beatsLabel(dd.loopBeats)}", dd.loopOn, mc, false, k, sl)
                6 -> PadSpec("LOOP ×2", false, mc, false, k, sl)
                else -> PadSpec("RELOOP", false, mc, false, k, sl)
            }
            2 -> if (sl < 7) PadSpec("ROLL ${rollLabel(ROLLS[sl])}", dd.rollBeats == ROLLS[sl], mc, true, k, sl)
                 else PadSpec(if (dd.slipOn) "SLIP AN" else "SLIP", dd.slipOn, mc, false, k, sl)
            else -> when (sl) {
                0 -> PadSpec("ECHO OUT", dd.echoOn, mc, false, k, sl)
                1 -> PadSpec("BRAKE", dd.stopFx == 1, mc, false, k, sl)
                2 -> PadSpec("BACKSPIN", dd.stopFx == 2, mc, false, k, sl)
                3 -> PadSpec("CENSOR", dd.censor, mc, true, k, sl)
                4 -> PadSpec("BUILD ↑ ${dd.sweepBars.toInt()}T", dd.sweepMode == 1, mc, false, k, sl)
                5 -> PadSpec("SWEEP ↓ ${dd.sweepBars.toInt()}T", dd.sweepMode == 2, mc, false, k, sl)
                6 -> PadSpec(if (dd.loopOn) "LOOP → PAD" else "4T → PAD", false, mc, false, k, sl)
                else -> PadSpec("⚡ → PAD", false, mc, false, k, sl)
            }
        }
    }

    private fun drawTabBar(c: Canvas) {
        val s = skin
        val ink = s.ink
        fun box(r: RectF, label: String, on: Boolean, col: Int) {
            if (r.isEmpty || r.left < -1000f) return
            fill.shader = null
            fill.color = if (on) col else if (s.dark) Color.argb(110, 0, 0, 0) else Color.argb(36, 0, 0, 0)
            c.drawRoundRect(r, dp(9f), dp(9f), fill)
            stroke.strokeWidth = dp(if (on) 1.6f else 1f)
            stroke.color = if (on) lighten(col, 0.3f) else alpha(col, 0.55f)
            c.drawRoundRect(r, dp(9f), dp(9f), stroke)
            text.color = if (on) (if (lum(col) > 0.6f) Color.rgb(20, 20, 24) else Color.WHITE) else ink
            val t = fitLabel(text, label, r.width() - dp(6f), dp(12.5f), dp(7.5f))
            c.drawText(t, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        }
        if (!tabDeckR.isEmpty) {
            if (padMode == 4) box(tabDeckR, if (samplerPage == 0) "1–8" else "9–12", true, MODE_COLS[4])
            else box(tabDeckR, "DECK ${"AB"[padDeck]}", true, if (padDeck == 0) s.colA else s.colB)
        }
        for (i in 0..5) box(tabModeR[i], MODE_NAMES[i], padMode == i, MODE_COLS[i])
        box(tabBigR, if (padsBig) "⌄" else "⌃", padsBig, s.accent)
        // FX-Stopp nur im FX-Modus, wenn etwas läuft
        tabFxStop.setEmpty()
        if (padMode == 3) {
            val any = Dj.decks.any { it.echoOn || it.sweepMode != 0 || it.censor || it.stopFx != 0 || it.fxOn }
            if (any) {
                val r = tabModeR[3]
                tabFxStop.set(r.right - dp(16f), r.top - dp(8f), r.right + dp(6f), r.top + dp(14f))
                fill.color = Color.rgb(230, 40, 60)
                c.drawOval(tabFxStop, fill)
                text.color = Color.WHITE
                text.textSize = dp(11f)
                c.drawText("✕", tabFxStop.centerX(), tabFxStop.centerY() + dp(4f), text)
            }
        }
    }

    private fun lum(col: Int): Float = (0.299f * Color.red(col) + 0.587f * Color.green(col) + 0.114f * Color.blue(col)) / 255f

    private fun barsLabel(b: Float): String = if (b >= 1f) "${b.toInt()} T" else if (b == 0.5f) "½ T" else "¼ T"
    private fun rollLabel(b: Float): String = when (b) { 0.125f -> "1/32"; 0.25f -> "1/16"; 0.5f -> "1/8"; 1f -> "1/4"; 2f -> "1/2"; 4f -> "1 T"; 8f -> "2 T"; else -> b.toString() }

    private fun drawPads(c: Canvas) {
        val s = skin
        for (i in 0 until padCount) {
            val r = pads[i]
            val spec = padSpec(i)
            val lbl = spec.label
            val lit0 = spec.lit
            padFlash[i] *= 0.88f
            val lit = lit0 || padDown[i] || padFlash[i] > 0.2f
            val hotCol = spec.col
            val palCol = if (s.padCols.size > 1) s.padCols[(i * 7 + i / 4) % s.padCols.size] else hotCol
            val rad = dp(if (s.pad == 7 || s.pad == 11) 9f else 7f)
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
                13 -> {
                    // Chihuahua-Kachel: Gesicht auf Pastell, gedrückt/aktiv mit rosa Rahmen
                    shadowRect(c, rr, rad)
                    val face = chiFaces[i % 12]
                    val bgc = face?.getPixel(3, face.height / 2) ?: palCol
                    fill.color = bgc
                    c.drawRoundRect(rr, rad, rad, fill)
                    if (face != null) {
                        c.save()
                        path.reset()
                        path.addRoundRect(rr, rad, rad, Path.Direction.CW)
                        c.clipPath(path)
                        val sz = min(rr.width(), rr.height())
                        tmp.set(rr.centerX() - sz / 2, rr.centerY() - sz / 2, rr.centerX() + sz / 2, rr.centerY() + sz / 2)
                        c.drawBitmap(face, null, tmp, bmpPaint)
                        if (lit) {
                            fill.color = alpha(Color.WHITE, 0.22f)
                            c.drawRoundRect(rr, rad, rad, fill)
                        }
                        c.restore()
                    }
                    stroke.strokeWidth = dp(if (lit) 2.6f else 1f)
                    stroke.color = if (lit) hotCol else Color.argb(50, 90, 60, 80)
                    c.drawRoundRect(rr, rad, rad, stroke)
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
            if (s.pad == 13) {
                // Beschriftung als kleines Schild unten, damit das Gesicht frei bleibt
                if (lbl.isNotEmpty()) {
                    val pl = fitLabel(text, lbl, rr.width() - dp(10f), min(dp(9f), rr.height() * 0.2f), dp(6f))
                    val tw2 = text.measureText(pl) + dp(8f)
                    tmp.set(rr.centerX() - tw2 / 2, rr.bottom - text.textSize - dp(6f), rr.centerX() + tw2 / 2, rr.bottom - dp(2f))
                    fill.color = Color.argb(205, 255, 250, 246)
                    c.drawRoundRect(tmp, dp(5f), dp(5f), fill)
                    text.color = Color.rgb(110, 90, 120)
                    c.drawText(pl, rr.centerX(), tmp.bottom - dp(2.5f), text)
                }
                continue
            }
            val dark = when (s.pad) { 0, 3, 5, 6, 9, 12 -> true; 1 -> lit; else -> false }
            // Funktionsfarbe als Streifen oben (auch wenn das Pad nicht leuchtet)
            fill.shader = null
            fill.color = alpha(hotCol, if (lit) 1f else 0.75f)
            tmp.set(rr.left + rad, rr.top + dp(3f), rr.right - rad, rr.top + dp(6f))
            c.drawRoundRect(tmp, dp(2f), dp(2f), fill)
            text.color = if (dark) Color.argb(220, 30, 30, 34) else Color.argb(if (lit) 255 else 210, 255, 255, 255)
            val pl = fitLabel(text, lbl, rr.width() - dp(10f), min(dp(15f), rr.height() * 0.26f), dp(8f))
            c.drawText(pl, rr.centerX(), rr.centerY() + text.textSize * 0.36f, text)
            if (spec.hold) {
                // „halten“-Hinweis
                text.textSize = min(dp(9f), rr.height() * 0.16f)
                text.color = alpha(if (dark) Color.rgb(30, 30, 34) else Color.WHITE, 0.7f)
                c.drawText("● HALTEN", rr.centerX(), rr.bottom - dp(5f), text)
            }
            if (padMode == 5 && spec.deck >= 0) {
                val dd = Dj.decks[spec.deck]
                val v = when {
                    spec.slot < 4 -> dd.stemGain[spec.slot]
                    spec.slot == 7 && dd.aiProg >= 0f -> dd.aiProg
                    spec.slot == 7 && dd.stemProg >= 0f -> dd.stemProg
                    else -> -1f
                }
                if (v >= 0f) {
                    // Pegel-/Fortschrittsbalken unten im Pad
                    tmp.set(rr.left + dp(6f), rr.bottom - dp(8f), rr.right - dp(6f), rr.bottom - dp(5f))
                    fill.color = Color.argb(70, 0, 0, 0)
                    c.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
                    tmp.right = tmp.left + tmp.width() * v.coerceIn(0f, 1f)
                    fill.color = hotCol
                    c.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
                }
            }
        }
        // Deck-Kennung: groß über jedem 4×2-Block
        if (padsBig && !land && padMode != 4) {
            text.textSize = dp(10f)
            for (k in 0..1) {
                val r0 = pads[k * 8]
                text.color = if (k == 0) s.colA else s.colB
                text.textAlign = Paint.Align.LEFT
                c.drawText("DECK ${"AB"[k]}", r0.left + dp(2f), r0.top - dp(3f), text)
                text.textAlign = Paint.Align.CENTER
            }
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

    private class Ptr(val kind: K, val idx: Int, val sub: Int, var lastX: Float, var lastY: Float, val downAt: Long) {
        val lastY0 = lastY
    }

    private val ptrs = HashMap<Int, Ptr>()
    private val lastTap = LongArray(32)
    private val hotTap = LongArray(16)
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
        if (ghost.isOpen) {
            ghost.touch(e)
            invalidate()
            return true
        }
        if (masterPanel.isOpen) {
            masterPanel.touch(e)
            return true
        }
        if (editDeck >= 0) {
            editorTouch(e)
            return true
        }
        if (lesson >= 0 && lessonTouch(e)) return true
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
        for (i in 0 until padCount) if (hit(pads[i], x, y, dp(2f))) {
            ptrs[id] = Ptr(K.PAD, i, 0, x, y, now)
            if (padMode == 4) {
                val p = padSpec(i).slot
                if (p < 12 && !Engine.hasLoop(p)) {
                    // leeres Sampler-Pad: Audio laden
                    onSamplerLoad?.invoke(p)
                    ptrs.remove(id)
                    return
                }
                if (p < 12 && loadArm) {
                    // ⏏ LADEN aktiv: dieses Pad neu belegen
                    loadArm = false
                    ptrs.remove(id)
                    onSamplerLoad?.invoke(p)
                    return
                }
            }
            padPress(i, true)
            return
        }
        if (hit(tabBar, x, y, dp(3f))) {
            ptrs[id] = Ptr(K.CHIP, 0, 0, x, y, now)
            haptic()
            when {
                !tabFxStop.isEmpty && hit(tabFxStop, x, y, dp(4f)) -> { for (dd in Dj.decks) Dj.stopAllFx(dd); onHint?.invoke("Alle Deck-Effekte aus") }
                hit(tabBigR, x, y, dp(2f)) -> setPadsBig(!padsBig)
                !tabDeckR.isEmpty && hit(tabDeckR, x, y, dp(2f)) -> {
                    if (padMode == 4) samplerPage = 1 - samplerPage else padDeck = 1 - padDeck
                    for (q in padDown.indices) padDown[q] = false
                    onHint?.invoke(if (padMode == 4) (if (samplerPage == 0) "Sampler-Pads 1–8" else "Sampler-Pads 9–12 + Steuerung") else "Pads steuern Deck ${"AB"[padDeck]}")
                }
                else -> for (m in 0..5) if (hit(tabModeR[m], x, y, dp(2f))) {
                    if (padMode != m) {
                        padMode = m
                        for (q in padDown.indices) padDown[q] = false
                        layoutAll()
                        onHint?.invoke(when (m) {
                            0 -> "CUES: 8 Hot Cues – leer antippen = setzen, doppelt tippen = löschen"
                            1 -> "LOOP: Beat Jump (Weite in der Mitte), SLIP, Loop ½ / an-aus / ×2, Reloop"
                            2 -> "ROLL: halten = Stotter-Loop 1/32 … 2 Takte, loslassen = Track läuft im Takt weiter"
                            3 -> "FX: Echo-Out, Brake, Backspin, Censor (halten), Build/Sweep, Loop/Schlag → Pad"
                            5 -> "STEMS: Vocals/Melodie/Bass/Drums – tippen = an/aus, ziehen = Pegel. Unten: Acapella, Instrumental, Beat · „ALLE AN“ bei allen an = 🧠 KI-Trennung"
                            else -> "SAMPLER: die Pads der App – leeres Pad antippen = Audio laden"
                        })
                    }
                }
            }
            return
        }
        for (k in 0..1) for (j in 0..4) if (hit(side[k][j], x, y)) {
            ptrs[id] = Ptr(K.SIDE, k, j, x, y, now)
            sidePress(k, j, true)
            haptic()
            return
        }
        when {
            hit(logoHit, x, y, dp(4f)) -> { haptic(); onInfo?.invoke(); return }
            hit(bpmChip, x, y) -> { onTempo?.invoke(); return }
            hit(padsBtn, x, y) -> { onPadsMode?.invoke(); return }
            hit(masterBtn, x, y, 0f) -> { masterPanel.open(); haptic(); return }
            hit(setRecBtn, x, y, 0f) -> { onSetRec?.invoke(); haptic(); return }
            hit(menuBtn, x, y) -> { onMenu?.invoke(); return }
        }
        for (k in 0..1) {
            if (hit(overview[k], x, y, dp(3f)) && Dj.decks[k].loaded()) {
                ptrs[id] = Ptr(K.OVERVIEW, k, 0, x, y, now)
                seekOverview(k, x)
                return
            }
            if (!dropHit[k].isEmpty && hit(dropHit[k], x, y, dp(4f))) {
                haptic()
                onHint?.invoke(Dj.jumpNextPart(Dj.decks[k], SongParts.DROP))
                return
            }
            if (!partHit[k].isEmpty && hit(partHit[k], x, y, dp(4f))) {
                haptic()
                onHint?.invoke(Dj.jumpNextPart(Dj.decks[k]))
                return
            }
            if (!keyHit[k].isEmpty && hit(keyHit[k], x, y, 0f)) {
                haptic()
                onKeyMenu?.invoke(k)
                return
            }
            if (hit(lanes[k], x, y, 0f)) {
                val dd = Dj.decks[k]
                if (y < lanes[k].top + dp(16f) || !dd.loaded()) {
                    if (!dd.loading) onLoad?.invoke(k)
                    return
                }
                ptrs[id] = Ptr(K.WAVE, k, 0, x, y, now)
                Dj.scratchStart(dd)
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
            K.PAD -> if (padMode == 5) {
                val spec = padSpec(p.idx)
                if (spec.deck >= 0 && spec.slot < 4) {
                    if (!stemDragged[p.idx] && abs(y - p.lastY0) > dp(10f)) stemDragged[p.idx] = true
                    if (stemDragged[p.idx]) {
                        val g = Dj.decks[spec.deck].stemGain
                        g[spec.slot] = (g[spec.slot] - dy / dp(140f)).coerceIn(0f, 1f)
                    }
                }
            }
            K.CHIP -> {
                // über die Reiter wischen: hoch = Pads groß, runter = Regler zurück
                if (abs(y - p.lastY0) > dp(46f)) {
                    setPadsBig(y < p.lastY0)
                    ptrs[id] = Ptr(K.NONE, 0, 0, x, y, p.downAt)
                    return
                }
            }
            else -> {}
        }
        p.lastX = x
        p.lastY = y
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.remove(id) ?: return
        when (p.kind) {
            K.PAD -> padPress(p.idx, false)
            K.SIDE -> {
                if (p.sub == 4 && SystemClock.uptimeMillis() - p.downAt > 500) fxLong(p.idx) else sidePress(p.idx, p.sub, false)
            }
            K.WAVE -> Dj.scratchEnd(Dj.decks[p.idx])
            K.XY -> {
                xyActive = false
                val dd = Dj.decks[focus]
                if (dd.fxOn) dd.fxLevel = max(0.05f, dd.fxAmt)
                if (!xyHold) dd.fxAmt = 0f
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
            2 -> setPadsBig(true)
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
                // Gig-Modus: ein hörbares Deck stoppt nur per Doppeltipp
                if (Dj.gigMode && Dj.onAir(dd) && !doubleTap(28 + k)) {
                    onHint?.invoke("GIG: Deck ${"AB"[k]} ist zu hören – zum Stoppen doppelt tippen")
                    return
                }
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
            4 -> if (!down) {
                dd.fxOn = !dd.fxOn
                onHint?.invoke(if (dd.fxOn) "FX ${"AB"[k]} an: ${Fx.NAMES[dd.fxType]} – XY-Fläche regelt ihn, lange drücken wählt den Effekt" else "FX ${"AB"[k]} aus")
            }
        }
    }

    private fun padPress(i: Int, down: Boolean) {
        padDown[i] = down
        if (down) {
            padFlash[i] = 1f
            haptic()
        }
        val spec = padSpec(i)
        if (padMode == 4) {
            val idx = spec.slot
            if (idx < 12) {
                if (down) {
                    val r = Engine.liveDown(idx)
                    padLaunched[idx] = (r and Engine.LIVE_LAUNCHED) != 0
                } else {
                    Engine.liveUp(idx, padLaunched[idx])
                }
                return
            }
            if (!down) return
            when (idx) {
                12 -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
                13 -> {
                    loadArm = !loadArm
                    if (loadArm) onHint?.invoke("Jetzt ein Pad antippen, um Audio darauf zu laden (startet synchron ab dem Startpunkt)")
                }
                14 -> {
                    Engine.monitorOn = !Engine.monitorOn
                    onHint?.invoke(if (Engine.monitorOn) "🎙 Mikrofon live – mit Kopfhörern, sonst pfeift es" else "Mikrofon aus")
                }
                else -> tap()
            }
            return
        }
        val k = spec.deck
        val sl = spec.slot
        val dd = Dj.decks[k]
        if (!dd.loaded()) {
            if (down) onLoad?.invoke(k)
            return
        }
        if (padMode == 5) {
            stemPad(i, k, sl, down)
            return
        }
        when (padMode) {
            0 -> if (down) hotPress(k, sl)
            1 -> {
                if (!down) return
                when (sl) {
                    0 -> Dj.beatJump(dd, -dd.jumpBars)
                    2 -> Dj.beatJump(dd, dd.jumpBars)
                    1 -> {
                        val opts = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f)
                        dd.jumpBars = opts[(opts.indexOfFirst { it == dd.jumpBars } + 1).coerceAtLeast(0) % opts.size]
                        onHint?.invoke("Beat Jump Deck ${"AB"[k]}: ${barsLabel(dd.jumpBars)}")
                    }
                    3 -> {
                        dd.slipOn = !dd.slipOn
                        onHint?.invoke(if (dd.slipOn) "SLIP ${"AB"[k]}: Scratchen, Rolls, Censor – danach geht es dort weiter, wo der Track ohne sie wäre" else "SLIP ${"AB"[k]} aus")
                    }
                    4 -> Dj.setLoopBeats(dd, dd.loopBeats / 2f)
                    5 -> Dj.toggleLoop(dd)
                    6 -> Dj.setLoopBeats(dd, dd.loopBeats * 2f)
                    else -> {
                        // RELOOP: letzten Loop wieder an und zu seinem Anfang springen
                        if (dd.loopOut > dd.loopIn + 16) {
                            dd.loopOn = true
                            dd.seekTo = dd.loopIn
                            if (dd.sync && dd.playing) dd.syncReq = true
                        } else onHint?.invoke("Noch kein Loop gesetzt")
                    }
                }
            }
            2 -> {
                if (sl == 7) {
                    if (down) { dd.slipOn = !dd.slipOn; onHint?.invoke(if (dd.slipOn) "SLIP ${"AB"[k]} an" else "SLIP ${"AB"[k]} aus") }
                    return
                }
                val rb = ROLLS[sl]
                if (down) Dj.rollStart(dd, rb) else if (dd.rollBeats == rb) {
                    // noch ein anderer Roll desselben Decks gedrückt? dann zu dem wechseln
                    val other = (0 until padCount).firstOrNull { it != i && padDown[it] && padSpec(it).let { o -> o.deck == k && o.slot < 7 } }
                    if (other != null) Dj.rollStart(dd, ROLLS[padSpec(other).slot]) else Dj.rollEnd(dd)
                }
            }
            else -> {
                if (sl == 3) { Dj.censorOn(dd, down); return }
                if (!down) return
                when (sl) {
                    0 -> { Dj.echoOut(dd); onHint?.invoke(if (dd.echoReq) "ECHO OUT ${"AB"[k]}: Track stoppt, das Echo läuft im Takt aus" else "Echo ${"AB"[k]} aus") }
                    1, 2 -> { Dj.brake(dd, sl == 2); if (!dd.playing) onHint?.invoke("Deck ${"AB"[k]} läuft nicht") }
                    4, 5 -> {
                        Dj.toggleSweep(dd, sl - 3)
                        onHint?.invoke(when {
                            dd.sweepMode == 0 -> "Filter zurück – Drop!"
                            sl == 4 -> "BUILD ${"AB"[k]}: Hochpass steigt über ${dd.sweepBars.toInt()} Takte – nochmal tippen = Drop"
                            else -> "SWEEP ${"AB"[k]}: Tiefpass schließt über ${dd.sweepBars.toInt()} Takte – nochmal tippen = auf"
                        })
                    }
                    else -> {
                        val one = sl == 7
                        onHint?.invoke(if (one) "Schlag → Pad …" else "Loop → Pad …")
                        Thread {
                            val p = Dj.grabToPad(dd, 4f, one)
                            post { onHint?.invoke(if (p < 0) "Kein freies Pad (1–12) oder kein Tempo" else if (one) "⚡ Pad ${p + 1}: Schlag aus Deck ${"AB"[k]} – im Reiter SAMPLER spielen" else "Pad ${p + 1}: Loop aus Deck ${"AB"[k]}, im Takt gewarpt – im Reiter SAMPLER starten") }
                        }.start()
                    }
                }
            }
        }
    }

    /** STEMS-Pads: 0–3 an/aus (beim Loslassen, wenn nicht gezogen), 4–7 Voreinstellungen. */
    private fun stemPad(i: Int, k: Int, sl: Int, down: Boolean) {
        val dd = Dj.decks[k]
        val g = dd.stemGain
        if (sl < 4) {
            if (down) { stemDragged[i] = false; return }
            if (stemDragged[i]) return
            g[sl] = if (g[sl] > 0.5f) 0f else 1f
            stemHint(dd, k)
            return
        }
        if (!down) return
        fun set(v: Float, m: Float, b: Float, d: Float) { g[0] = v; g[1] = m; g[2] = b; g[3] = d }
        val all = g.all { it > 0.99f }
        when (sl) {
            4 -> if (g[0] > 0.9f && g[1] < 0.05f && g[2] < 0.05f && g[3] < 0.05f) set(1f, 1f, 1f, 1f) else set(1f, 0f, 0f, 0f)
            5 -> if (g[0] < 0.05f && g[1] > 0.9f) set(1f, 1f, 1f, 1f) else set(0f, 1f, 1f, 1f)
            6 -> if (g[0] < 0.05f && g[1] < 0.05f && g[3] > 0.9f) set(1f, 1f, 1f, 1f) else set(0f, 0f, 1f, 1f)
            else -> {
                if (dd.stemMask == null && dd.stemProg < 0f && dd.aiProg < 0f) { Dj.stemsAsync(dd); onHint?.invoke("Stems für Deck ${"AB"[k]} werden berechnet …"); return }
                // alle schon an (oder KI noch nicht da): KI-Menü
                if (all) { onAiStems?.invoke(k); return }
                if (!all) set(1f, 1f, 1f, 1f)
            }
        }
        stemHint(dd, k)
    }

    private fun stemHint(dd: Dj.Deck, k: Int) {
        if (dd.aiProg >= 0f && dd.stemMask == null) {
            onHint?.invoke("🧠 KI-Trennung Deck ${"AB"[k]}: ${(dd.aiProg * 100).toInt()} % – die Regler wirken, sobald sie fertig ist")
            return
        }
        if (dd.stemMask == null) {
            onHint?.invoke(if (dd.stemProg >= 0f) "Stems Deck ${"AB"[k]}: Analyse läuft (${(dd.stemProg * 100).toInt()} %) – die Regler wirken, sobald sie fertig ist" else "Stems Deck ${"AB"[k]}: noch nicht berechnet – Pad unten rechts")
            return
        }
        val g = dd.stemGain
        val parts = (0..3).filter { g[it] > 0.02f }.map { DeckStems.NAMES[it] }
        val mode = if (dd.stemMask?.ai == true) " (🧠 KI)" else if (dd.aiProg >= 0f) " (schnell · KI rechnet ${(dd.aiProg * 100).toInt()} %)" else " (schnell)"
        onHint?.invoke("Deck ${"AB"[k]}: " + (if (parts.size == 4) "alle Stems an" else if (parts.isEmpty()) "alle Stems stumm" else parts.joinToString(" + ")) + mode)
    }

    /** FX-Knopf lange gedrückt: nächster Effekt für diesen Kanal. */
    private fun fxLong(k: Int) {
        haptic()
        val dd = Dj.decks[k]
        val ch = Dj.FX_CHOICES
        dd.fxType = ch[(ch.indexOf(dd.fxType) + 1) % ch.size]
        onHint?.invoke("Effekt Kanal ${"AB"[k]}: ${Fx.NAMES[dd.fxType]}")
    }

    private fun hotPress(k: Int, h: Int) {
        val dd = Dj.decks[k]
        val now = SystemClock.uptimeMillis()
        val slot = k * 8 + h
        if (dd.hot[h] >= 0 && now - hotTap[slot] < 320) {
            // doppelt tippen löscht den Hot Cue
            Dj.hotCue(dd, h, true)
            hotTap[slot] = 0
            onHint?.invoke("Hot Cue ${h + 1} (Deck ${"AB"[k]}) gelöscht")
            return
        }
        hotTap[slot] = now
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
        if (padMode != 4 || p !in 0 until 12) return
        val v = if (dual) p else p - samplerPage * 8
        if (v in 0 until padCount) padFlash[v] = 1f
    }

    // ================================================================ Track-Editor (Wave-Editor fürs Deck)

    /** Deck im Editor (-1 = zu). */
    var editDeck = -1
        private set
    /** Bibliothek: Cue-Punkte/Tempo sichern (Deck). */
    var onEditSaveLib: ((Int) -> Unit)? = null
    private var ev0 = 0.0
    private var ev1 = 1.0
    /** 0 = auf Schläge, 1 = Nulldurchgang, 2 = frei */
    private var evSnap = 0
    private var evDrag = 0
    private var evDownX = 0f
    private var evDownY = 0f
    private var evMoved = false
    private var evPan0 = 0.0
    private var evPinch0 = 0f
    private var evPinchV0 = 0.0
    private var evPinchV1 = 0.0
    private var evMarkOrig = 0.0
    private var evPinchF0 = 0.0
    @Volatile private var evBusy = ""
    private val evTaps = ArrayList<Long>()
    private val evRect = RectF()
    private val evClose = RectF()
    private val evOver = RectF()
    private val evWave = RectF()
    private val evBtns = Array(6) { Array(5) { RectF() } }
    private val evDownAt = LongArray(64)

    fun openEditor(k: Int) {
        val d = Dj.decks[k]
        if (!d.loaded()) return
        editDeck = k
        evSnap = 0
        val bf = d.beatFrames()
        val c = if (d.cuePoint > 0) d.cuePoint else d.pos
        evSetView(c, bf * 16)
        layoutEditor()
        invalidate()
    }

    private fun closeEditor() {
        editDeck = -1
    }

    private fun evDeck(): Dj.Deck = Dj.decks[editDeck.coerceIn(0, 1)]

    private fun evSetView(center: Double, span0: Double) {
        val d = evDeck()
        val len = d.len.toDouble()
        val span = span0.coerceIn(min(len, d.srcRate * 0.03), len)
        var a = center - span / 2
        a = a.coerceIn(0.0, max(0.0, len - span))
        ev0 = a
        ev1 = a + span
    }

    private fun layoutEditor() {
        val m = dp(8f)
        evRect.set(m, insetTop + m, width - m, height - insetBottom - m)
        evClose.set(evRect.right - dp(46f), evRect.top + dp(8f), evRect.right - dp(8f), evRect.top + dp(46f))
        var y = evClose.bottom + dp(26f)
        evOver.set(evRect.left + dp(10f), y, evRect.right - dp(10f), y + dp(26f))
        y = evOver.bottom + dp(8f)
        val rows = 6
        val bh = dp(38f)
        val gap = dp(6f)
        val btnH = rows * bh + (rows - 1) * gap
        val waveH = max(dp(120f), evRect.bottom - dp(10f) - btnH - dp(30f) - y)
        evWave.set(evRect.left + dp(10f), y, evRect.right - dp(10f), y + waveH)
        y = evWave.bottom + dp(38f)
        val L = evRect.left + dp(10f)
        val W = evRect.width() - dp(20f)
        for (r in 0 until rows) {
            val n = when (r) { 3 -> 4; 4 -> 2; 5 -> 2; else -> 5 }
            val bw = (W - (n - 1) * gap) / n
            for (i in 0 until 5) {
                if (i < n) evBtns[r][i].set(L + i * (bw + gap), y, L + i * (bw + gap) + bw, y + bh) else evBtns[r][i].setEmpty()
            }
            y += bh + gap
        }
    }

    private fun evX(f: Double): Float = evWave.left + ((f - ev0) / (ev1 - ev0)).toFloat() * evWave.width()
    private fun evF(x: Float): Double = ev0 + ((x - evWave.left) / evWave.width()).toDouble() * (ev1 - ev0)

    /** Nächster Anschlag um [f] (±25 ms): Block mit dem stärksten Energieanstieg. */
    private fun transientNear(d: Dj.Deck, f: Double): Double {
        val data = d.data ?: return f
        val ch = d.ch
        val blk = max(16, d.srcRate / 1000)
        val reach = d.srcRate / 40
        val i0 = max(blk, f.toInt() - reach)
        val i1 = min(d.len - blk, f.toInt() + reach)
        if (i1 <= i0) return f
        fun en(at: Int): Double {
            var s = 0.0
            var i = at
            val e = min(d.len, at + blk)
            while (i < e) { val v = data[i * ch].toDouble(); s += v * v; i++ }
            return s
        }
        var best = f
        var bestRise = 0.0
        var prev = en(i0 - blk)
        var i = i0
        while (i < i1) {
            val e = en(i)
            val rise = e - prev * 1.5
            if (rise > bestRise) { bestRise = rise; best = i.toDouble() }
            prev = e
            i += blk
        }
        return best
    }

    private fun evSnapPos(f0: Double): Double {
        val d = evDeck()
        val data = d.data ?: return f0
        val f = f0.coerceIn(0.0, (d.len - 2).toDouble())
        return when (evSnap) {
            0 -> {
                // Beat nur, wenn er auf dem Bildschirm nahe liegt – sonst auf den nächsten
                // Anschlag (Transiente) rasten, damit S auch vor den ersten Schlag passt
                val b = Dj.snapBeat(d, f)
                if (abs(evX(b) - evX(f)) < dp(16f)) b else transientNear(d, f)
            }
            1 -> {
                val ch = d.ch
                val i0 = f.toInt()
                val r = d.srcRate / 200
                var best = i0
                var bd = Int.MAX_VALUE
                for (i in max(1, i0 - r)..min(d.len - 1, i0 + r)) {
                    val a = data[(i - 1) * ch].toInt()
                    val b = data[i * ch].toInt()
                    if ((a <= 0 && b > 0) || (a >= 0 && b < 0)) {
                        val dd = abs(i - i0)
                        if (dd < bd) { bd = dd; best = i }
                    }
                }
                best.toDouble()
            }
            else -> f
        }
    }

    private val evLabels: Array<Array<String>>
        get() {
            val d = evDeck()
            val mb = Engine.currentBpm()
            return arrayOf(
                arrayOf("⤢ GANZ", "⌖ KOPF", "⌖ START", if (d.playing) "‖ PAUSE" else "▶ HÖREN", when (evSnap) { 0 -> "SNAP: BEAT"; 1 -> "SNAP: 0"; else -> "SNAP: FREI" }),
                arrayOf("HOT 1", "HOT 2", "HOT 3", "HOT 4", "S ← KOPF"),
                arrayOf("BPM −0.1", "BPM +0.1", "× 2", "÷ 2", "TAP"),
                arrayOf("◀ RASTER", "RASTER ▶", "SCHLAG 1 = S", "🔎 ERKENNEN", ""),
                arrayOf(if (mb > 0f) String.format(Locale.GERMANY, "⇄ AUF %.1f BPM WARPEN", mb) else "⇄ WARPEN (kein Master)", if (d.sync) "SYNC: AN" else "SYNC: AUS"),
                arrayOf(if (d.libId != null) "💾 IN BIBLIOTHEK SICHERN" else "💿 IN DJ-BIBLIOTHEK", "✓ FERTIG")
            )
        }

    private fun drawEditor(c: Canvas) {
        layoutEditor()
        val d = evDeck()
        val col = if (editDeck == 0) skin.colA else skin.colB
        c.drawColor(Color.argb(200, 0, 0, 0))
        fill.shader = null
        fill.color = Color.rgb(18, 20, 25)
        c.drawRoundRect(evRect, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f)
        stroke.color = col
        c.drawRoundRect(evRect, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        val title = fitLabel(text, "〰 Deck ${"AB"[editDeck]} · ${d.title}", evClose.left - evRect.left - dp(22f), dp(15f), dp(12f))
        c.drawText(title, evRect.left + dp(14f), evClose.centerY() + dp(5f), text)
        text.textSize = dp(11f)
        text.color = Color.rgb(190, 195, 205)
        val r = d.srcRate.toDouble()
        val info = if (evBusy.isNotEmpty()) evBusy else String.format(
            Locale.GERMANY, "%.2f BPM · Start %s.%02d · Raster %.3f s · Länge %s · Zoom %s",
            d.bpm, Dj.fmt(d.cuePoint / r), ((d.cuePoint / r * 100) % 100).toInt(), d.grid / r, Dj.fmt(d.seconds()), evZoomLabel()
        )
        val infoSize = text.textSize
        c.drawText(fitLabel(text, info, evRect.width() - dp(28f), infoSize, dp(8f)), evRect.left + dp(14f), evClose.bottom + dp(16f), text)
        text.textAlign = Paint.Align.CENTER
        drawBtnE(c, evClose, "✕", false, col)
        // Übersicht (ganzer Track) mit Sichtfenster
        val ov = evOver
        fill.color = Color.rgb(10, 11, 14)
        c.drawRect(ov, fill)
        val pk = d.peaks
        if (pk.isNotEmpty()) {
            stroke.strokeWidth = 1f
            val n = ov.width().toInt()
            for (i in 0 until n step 2) {
                val bi = (i.toLong() * pk.size / n).toInt().coerceIn(0, pk.size - 1)
                val v = (pk[bi].toInt() and 0xFF) / 255f
                stroke.color = alpha(col, 0.7f)
                c.drawLine(ov.left + i, ov.centerY() - v * ov.height() / 2, ov.left + i, ov.centerY() + v * ov.height() / 2, stroke)
            }
            fill.color = Color.argb(60, 255, 255, 255)
            val a0 = ov.left + (ev0 / d.len).toFloat() * ov.width()
            val a1 = ov.left + (ev1 / d.len).toFloat() * ov.width()
            c.drawRect(a0, ov.top, max(a0 + dp(2f), a1), ov.bottom, fill)
            stroke.strokeWidth = dp(1.5f)
            stroke.color = Color.WHITE
            val px = ov.left + (d.pos / d.len).toFloat() * ov.width()
            c.drawLine(px, ov.top, px, ov.bottom, stroke)
            stroke.color = Color.rgb(255, 170, 0)
            val cx0 = ov.left + (d.cuePoint / d.len).toFloat() * ov.width()
            c.drawLine(cx0, ov.top, cx0, ov.bottom, stroke)
        }
        // Hauptwellenform
        val w = evWave
        fill.color = Color.rgb(8, 9, 12)
        c.drawRoundRect(w, dp(6f), dp(6f), fill)
        c.save()
        c.clipRect(w)
        drawEvWave(c, d, w, ev0, ev1, col)
        // Raster
        if (d.bpm > 0f) {
            val bf = d.beatFrames()
            val k0 = floor((ev0 - d.grid) / bf).toLong()
            val k1 = floor((ev1 - d.grid) / bf).toLong() + 1
            val pxPerBeat = (bf / (ev1 - ev0) * w.width()).toFloat()
            if (k1 - k0 < 4000) for (k in k0..k1) {
                val x = evX(d.grid + k * bf)
                val bar = Math.floorMod(k, 4L) == 0L
                if (!bar && pxPerBeat < dp(5f)) continue
                stroke.strokeWidth = dp(if (bar) 1.4f else 0.7f)
                stroke.color = Color.argb(if (bar) 150 else 60, 255, 255, 255)
                c.drawLine(x, w.top, x, w.bottom, stroke)
                if (bar && (pxPerBeat * 4 > dp(26f) || Math.floorMod(k / 4, 4L) == 0L)) {
                    mono.textSize = dp(9f)
                    mono.color = Color.argb(170, 255, 255, 255)
                    mono.textAlign = Paint.Align.LEFT
                    c.drawText("${Math.floorDiv(k, 4L) + 1}", x + dp(2f), w.top + dp(10f), mono)
                }
            }
        }
        // Hot Cues
        for (h in 0..3) {
            val hf = d.hot[h]
            if (hf < 0) continue
            val x = evX(hf)
            stroke.strokeWidth = dp(2f)
            stroke.color = HOT[h]
            c.drawLine(x, w.top, x, w.bottom, stroke)
            fill.color = HOT[h]
            c.drawCircle(x, w.bottom - dp(10f), dp(8f), fill)
            text.textSize = dp(10f)
            text.color = Color.WHITE
            c.drawText("${h + 1}", x, w.bottom - dp(6.5f), text)
        }
        // Startpunkt (Cue)
        val sx = evX(d.cuePoint)
        stroke.strokeWidth = dp(2.5f)
        stroke.color = Color.rgb(255, 170, 0)
        c.drawLine(sx, w.top, sx, w.bottom, stroke)
        fill.color = Color.rgb(255, 170, 0)
        path.reset()
        path.moveTo(sx - dp(10f), w.top)
        path.lineTo(sx + dp(10f), w.top)
        path.lineTo(sx, w.top + dp(14f))
        path.close()
        c.drawPath(path, fill)
        text.textSize = dp(9f)
        text.color = Color.BLACK
        c.drawText("S", sx, w.top + dp(8f), text)
        // Abspielkopf
        stroke.strokeWidth = dp(1.6f)
        stroke.color = Color.WHITE
        val hx = evX(d.pos)
        c.drawLine(hx, w.top, hx, w.bottom, stroke)
        c.restore()
        text.color = Color.rgb(170, 175, 185)
        val hw = evRect.width() - dp(24f)
        c.drawText(fitLabel(text, "S ziehen = Startpunkt · weiße Linie ziehen = Abspielkopf", hw, dp(10f), dp(7.5f)), evRect.centerX(), w.bottom + dp(15f), text)
        c.drawText(fitLabel(text, "antippen = Kopf · ▶ spielt ab Kopf · 2 Finger = Zoom", hw, dp(10f), dp(7.5f)), evRect.centerX(), w.bottom + dp(29f), text)
        // Tasten
        val labels = evLabels
        for (rI in 0 until 6) for (i in 0 until 5) {
            val b = evBtns[rI][i]
            if (b.isEmpty || labels[rI].size <= i || labels[rI][i].isEmpty()) continue
            val on = when {
                rI == 1 && i < 4 -> d.hot[i] >= 0
                rI == 0 && i == 3 -> d.playing
                rI == 4 && i == 1 -> d.sync
                else -> false
            }
            val bc = if (rI == 1 && i < 4) HOT[i] else col
            drawBtnE(c, b, labels[rI][i], on, bc)
        }
    }

    private fun drawEvWave(c: Canvas, d: Dj.Deck, w: RectF, a: Double, b: Double, col: Int) {
        val data = d.data ?: return
        val ch = d.ch
        val span = b - a
        val cols = w.width().toInt()
        val fpp = span / cols
        val mid = w.centerY()
        val hh = w.height() * 0.46f
        val pk = d.peaks
        if (fpp < 1.5) {
            // stark vergrößert: Kurve
            path.reset()
            for (px in 0..cols) {
                val f = (a + px * fpp).toInt().coerceIn(0, d.len - 1)
                val v = if (ch == 2) (data[2 * f] + data[2 * f + 1]) / 65536f else data[f] / 32768f
                val y = mid - v * hh
                if (px == 0) path.moveTo(w.left + px, y) else path.lineTo(w.left + px, y)
            }
            stroke.strokeWidth = dp(1.6f)
            stroke.color = col
            c.drawPath(path, stroke)
        } else {
            stroke.strokeWidth = max(1f, dp(1f))
            val st = max(1, (fpp / 24).toInt())
            var px = 0
            while (px < cols) {
                val f0 = (a + px * fpp).toLong()
                val f1 = (a + (px + 1) * fpp).toLong()
                var mn = 0f
                var mx = 0f
                if (fpp > Dj.BIN * 2 && pk.isNotEmpty()) {
                    val bi0 = (f0 / Dj.BIN).toInt().coerceIn(0, pk.size - 1)
                    val bi1 = (f1 / Dj.BIN).toInt().coerceIn(bi0, pk.size - 1)
                    var m = 0
                    for (bi in bi0..bi1) m = max(m, pk[bi].toInt() and 0xFF)
                    mx = m / 255f
                    mn = -mx
                } else {
                    var f = max(0L, f0)
                    val e = min(d.len.toLong(), f1)
                    while (f < e) {
                        val i = f.toInt()
                        val v = if (ch == 2) (data[2 * i] + data[2 * i + 1]) / 65536f else data[i] / 32768f
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                        f += st
                    }
                }
                val inStart = f0 >= d.cuePoint
                stroke.color = if (inStart) col else alpha(col, 0.45f)
                c.drawLine(w.left + px, mid - mx * hh, w.left + px, mid - mn * hh + 0.5f, stroke)
                px++
            }
        }
        stroke.strokeWidth = dp(0.6f)
        stroke.color = Color.argb(60, 255, 255, 255)
        c.drawLine(w.left, mid, w.right, mid, stroke)
    }

    private fun drawBtnE(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(34, 37, 44)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(col, if (on) 1f else 0.45f)
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.rgb(15, 15, 15) else Color.WHITE
        val el = fitLabel(text, label, r.width() - dp(10f), dp(12f), dp(7f))
        c.drawText(el, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun evZoomLabel(): String {
        val d = evDeck()
        val z = d.len / max(1.0, ev1 - ev0)
        return if (z < 10) String.format(Locale.GERMANY, "%.1f×", z) else "${z.toInt()}×"
    }

    private fun editorTouch(e: MotionEvent) {
        val d = evDeck()
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount == 2 && (evWave.contains(x, y) || evDrag in 1..20)) {
                    // Daumen + Zeigefinger: Zoom um die Stelle zwischen den Fingern, gemeinsam ziehen = verschieben
                    if (evDrag == 1) d.cuePoint = evMarkOrig
                    if (evDrag in 10..13) d.hot[evDrag - 10] = evMarkOrig
                    evDrag = 30
                    evPinch0 = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    evPinchV0 = ev0
                    evPinchV1 = ev1
                    val cx = (e.getX(0) + e.getX(1)) / 2f
                    evPinchF0 = ev0 + ((cx - evWave.left) / evWave.width()).coerceIn(0f, 1f) * (ev1 - ev0)
                    return
                }
                evDownX = x
                evDownY = y
                evMoved = false
                if (hit(evClose, x, y)) { closeEditor(); return }
                if (hit(evOver, x, y, dp(6f))) {
                    evDrag = 25
                    evSetView(((x - evOver.left) / evOver.width()).coerceIn(0f, 1f) * d.len.toDouble(), ev1 - ev0)
                    return
                }
                if (evWave.contains(x, y)) {
                    val near = dp(26f)
                    val sx = evX(d.cuePoint)
                    var hotHit = -1
                    for (h in 0..3) if (d.hot[h] >= 0 && abs(evX(d.hot[h]) - x) < near && y > evWave.bottom - dp(40f)) hotHit = h
                    val hx = evX(d.pos)
                    evDrag = when {
                        hotHit >= 0 -> 10 + hotHit
                        abs(x - sx) < near && abs(x - sx) <= abs(x - hx) -> 1
                        abs(x - hx) < near -> 2
                        else -> 20
                    }
                    evPan0 = ev0
                    evMarkOrig = when (evDrag) { 1 -> d.cuePoint; in 10..13 -> d.hot[evDrag - 10]; else -> 0.0 }
                    return
                }
                for (r in 0 until 6) for (j in 0 until 5) if (!evBtns[r][j].isEmpty && evBtns[r][j].contains(x, y)) {
                    evDownAt[r * 5 + j] = SystemClock.uptimeMillis()
                    evDrag = 40 + r * 5 + j
                    haptic()
                    return
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (evDrag == 30) {
                    if (e.pointerCount >= 2 && evPinch0 > dp(20f)) {
                        val d1 = kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val span0 = evPinchV1 - evPinchV0
                        val len = d.len.toDouble()
                        val span = (span0 * evPinch0 / max(dp(10f), d1)).coerceIn(min(len, d.srcRate * 0.03), len)
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val rel = ((cx - evWave.left) / evWave.width()).coerceIn(0f, 1f)
                        val a = (evPinchF0 - rel * span).coerceIn(0.0, max(0.0, len - span))
                        ev0 = a
                        ev1 = a + span
                    }
                    return
                }
                val mx = e.getX(0)
                if (!evMoved && abs(mx - evDownX) < dp(6f)) return
                evMoved = true
                when (evDrag) {
                    1 -> d.cuePoint = evSnapPos(evF(mx))
                    2 -> d.seekTo = evF(mx).coerceIn(0.0, (d.len - 2).toDouble())
                    in 10..13 -> d.hot[evDrag - 10] = evSnapPos(evF(mx))
                    20 -> {
                        val span = ev1 - ev0
                        val nv = (evPan0 - (mx - evDownX) / evWave.width() * span).coerceIn(0.0, max(0.0, d.len - span))
                        ev0 = nv
                        ev1 = nv + span
                    }
                    25 -> evSetView(((mx - evOver.left) / evOver.width()).coerceIn(0f, 1f) * d.len.toDouble(), ev1 - ev0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val dr = evDrag
                evDrag = 0
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) return
                if (dr == 20 && !evMoved) {
                    // antippen: Abspielkopf hierhin
                    d.seekTo = evF(evDownX).coerceIn(0.0, (d.len - 2).toDouble())
                }
                if (dr == 1 && !evMoved) d.seekTo = d.cuePoint
                if (dr >= 40) {
                    val idx = dr - 40
                    val long = SystemClock.uptimeMillis() - evDownAt[idx] > 550
                    evButton(idx / 5, idx % 5, long)
                }
            }
        }
    }

    private fun evButton(r: Int, j: Int, long: Boolean) {
        val d = evDeck()
        val bf = d.beatFrames()
        when (r) {
            0 -> when (j) {
                0 -> evSetView(d.len / 2.0, d.len.toDouble())
                1 -> evSetView(d.pos, min(ev1 - ev0, bf * 8))
                2 -> evSetView(d.cuePoint, min(ev1 - ev0, bf * 2))
                3 -> {
                    if (d.playing) d.playing = false else {
                        if (d.sync) d.syncReq = true
                        d.playing = true
                    }
                }
                else -> {
                    evSnap = (evSnap + 1) % 3
                    onHint?.invoke(when (evSnap) { 0 -> "Marker rasten auf Schläge des Rasters"; 1 -> "Marker rasten auf Nulldurchgänge"; else -> "Marker frei" })
                }
            }
            1 -> if (j < 4) {
                if (long) {
                    d.hot[j] = -1.0
                    onHint?.invoke("Hot Cue ${j + 1} gelöscht")
                } else if (d.hot[j] < 0) {
                    d.hot[j] = evSnapPos(d.pos)
                    onHint?.invoke("Hot Cue ${j + 1} am Abspielkopf gesetzt – ziehen zum Verschieben, lange drücken löscht")
                } else {
                    d.seekTo = d.hot[j]
                    evSetView(d.hot[j], ev1 - ev0)
                }
            } else {
                d.cuePoint = evSnapPos(d.pos)
                onHint?.invoke("Startpunkt = Abspielkopf")
            }
            2 -> when (j) {
                0 -> d.bpm = max(40f, Math.round((d.bpm - 0.1f) * 100f) / 100f)
                1 -> d.bpm = min(240f, Math.round((d.bpm + 0.1f) * 100f) / 100f)
                2 -> d.bpm = min(240f, d.bpm * 2f)
                3 -> d.bpm = max(40f, d.bpm / 2f)
                else -> {
                    // im Takt mittippen, während der Track läuft – Raster folgt dem ersten Tipp
                    val now = SystemClock.uptimeMillis()
                    if (evTaps.isNotEmpty() && now - evTaps.last() > 2000) evTaps.clear()
                    evTaps.add(now)
                    if (evTaps.size > 8) evTaps.removeAt(0)
                    if (evTaps.size >= 4) {
                        val iv = (evTaps.last() - evTaps.first()).toDouble() / (evTaps.size - 1)
                        val b = (60000.0 / iv / d.ratio).toFloat()
                        if (b in 40f..240f) d.bpm = Math.round(b * 10f) / 10f
                        // Raster: letzter Tipp ist ein Schlag
                        d.grid = d.pos - Engine.outputLatencyMs() / 1000.0 * d.srcRate
                    }
                    onHint?.invoke(if (evTaps.size < 4) "Weiter im Takt tippen (${evTaps.size}/4) …" else String.format(Locale.GERMANY, "Tempo %.1f BPM", d.bpm))
                }
            }
            3 -> when (j) {
                0 -> d.grid -= d.srcRate * 0.005
                1 -> d.grid += d.srcRate * 0.005
                2 -> {
                    d.grid = d.cuePoint
                    onHint?.invoke("Raster: Schlag 1 liegt jetzt auf dem Startpunkt S")
                }
                else -> {
                    evBusy = "Erkenne Tempo und Raster …"
                    Thread {
                        Dj.reanalyze(d, false)
                        evBusy = ""
                        post { onHint?.invoke(String.format(Locale.GERMANY, "Erkannt: %.2f BPM · Raster neu gelegt", d.bpm)) }
                    }.start()
                }
            }
            4 -> if (j == 0) {
                val mb = Engine.currentBpm()
                if (mb <= 0f) {
                    onHint?.invoke("Noch kein Master-Tempo – oben BPM antippen oder Pads laufen lassen")
                    return
                }
                if (evBusy.isNotEmpty()) return
                if (abs(mb - d.bpm) < 0.01f) {
                    onHint?.invoke("Track hat schon das Master-Tempo")
                    return
                }
                val from = d.bpm
                evBusy = String.format(Locale.GERMANY, "Warpe %.2f → %.2f BPM …", from, mb)
                Thread {
                    try {
                        Dj.warpTo(d, mb) { p -> evBusy = String.format(Locale.GERMANY, "Warpe %.2f → %.2f BPM … %d %%", from, mb, (p * 100).toInt()) }
                        d.sync = true
                        d.syncReq = true
                        post { onHint?.invoke(String.format(Locale.GERMANY, "Track neu quantisiert: %.2f BPM ohne Tonhöhenänderung · SYNC an", mb)) }
                    } catch (e: OutOfMemoryError) {
                        post { onHint?.invoke("Zu wenig Speicher für diesen Track") }
                    } finally {
                        evBusy = ""
                    }
                }.start()
            } else {
                Dj.toggleSync(d)
            }
            5 -> if (j == 0) onEditSaveLib?.invoke(editDeck) else closeEditor()
        }
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
