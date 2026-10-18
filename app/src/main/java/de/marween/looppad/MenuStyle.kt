package de.marween.looppad

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Aussehen der Menüs und Dialoge (nicht des HUD): Schriftart, Hell/Dunkel, Akzentfarbe, Schriftgröße, Ecken.
 * Schriften: Google Fonts unter der SIL Open Font License 1.1 (assets/fonts, siehe FONTS.txt).
 */
object MenuStyle {
    class Font(val name: String, val file: String?, val sys: String?, val wght: Int = 0, val scale: Float = 1f)

    val FONTS = listOf(
        Font("Standard (System)", null, "sans-serif"),
        Font("Rajdhani – technisch", "rajdhani.ttf", null, 0, 1.08f),
        Font("Orbitron – Sci-Fi", "orbitron.ttf", null, 600, 0.9f),
        Font("Exo 2 – modern", "exo2.ttf", null, 500),
        Font("Audiowide – Studio", "audiowide.ttf", null, 0, 0.92f),
        Font("Space Grotesk – klar", "spacegrotesk.ttf", null, 500),
        Font("Quicksand – rund", "quicksand.ttf", null, 600),
        Font("Comfortaa – weich", "comfortaa.ttf", null, 600, 0.95f),
        Font("Nunito – freundlich", "nunito.ttf", null, 600),
        Font("Bebas Neue – Plakat", "bebasneue.ttf", null, 0, 1.15f),
        Font("Press Start 2P – Pixel", "pressstart2p.ttf", null, 0, 0.62f),
        Font("Kompakt (System)", null, "sans-serif-condensed"),
        Font("Serif (System)", null, "serif"),
        Font("Mono (System)", null, "monospace")
    )
    val MODES = arrayOf("🌙 Dunkel", "☀ Hell", "📱 wie das System")
    val ACCENTS = intArrayOf(
        Color.rgb(255, 106, 16), Color.rgb(0, 200, 230), Color.rgb(240, 80, 160),
        Color.rgb(60, 200, 110), Color.rgb(150, 110, 250), Color.rgb(230, 180, 40)
    )
    val ACCENT_NAMES = arrayOf("Orange (Marv)", "Cyan", "Pink", "Grün", "Lila", "Gold")
    val SIZES = arrayOf("Klein", "Normal", "Groß", "Sehr groß")
    private val SIZE_F = floatArrayOf(0.88f, 1f, 1.14f, 1.3f)
    val CORNERS = arrayOf("Rund", "Leicht rund", "Eckig")
    private val CORNER_DP = floatArrayOf(22f, 10f, 2f)

    var font = 1
    var mode = 0
    var accent = 0
    var size = 1
    var corner = 0

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("marvpad", Context.MODE_PRIVATE)
        font = p.getInt("uiFont", 1).coerceIn(0, FONTS.size - 1)
        mode = p.getInt("uiMode", 0).coerceIn(0, 2)
        accent = p.getInt("uiAccent", 0).coerceIn(0, ACCENTS.size - 1)
        size = p.getInt("uiSize", 1).coerceIn(0, SIZES.size - 1)
        corner = p.getInt("uiCorner", 0).coerceIn(0, CORNERS.size - 1)
        tfCache.clear()
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("marvpad", Context.MODE_PRIVATE).edit()
            .putInt("uiFont", font).putInt("uiMode", mode).putInt("uiAccent", accent).putInt("uiSize", size).putInt("uiCorner", corner).apply()
    }

    fun isLight(ctx: Context): Boolean = when (mode) {
        1 -> true
        2 -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    @Suppress("DEPRECATION")
    fun themeRes(ctx: Context): Int = if (isLight(ctx)) AlertDialog.THEME_DEVICE_DEFAULT_LIGHT else AlertDialog.THEME_DEVICE_DEFAULT_DARK

    fun accentColor(): Int = ACCENTS[accent]
    fun bg(ctx: Context): Int = if (isLight(ctx)) Color.rgb(250, 247, 242) else Color.rgb(22, 24, 30)
    fun ink(ctx: Context): Int = if (isLight(ctx)) Color.rgb(30, 30, 36) else Color.rgb(240, 240, 244)

    private val tfCache = HashMap<Int, Typeface>()
    /** Tag für Texte, die ihre eigene Schrift behalten (Schrift-Vorschau). */
    const val KEEP = "keepfont"
    private val titleId: Int by lazy { android.content.res.Resources.getSystem().getIdentifier("alertTitle", "id", "android") }

    fun typeface(ctx: Context, idx: Int = font): Typeface = tfCache.getOrPut(idx) {
        val f = FONTS[idx]
        try {
            if (f.file != null) {
                val b = Typeface.Builder(ctx.assets, "fonts/" + f.file)
                if (f.wght > 0) b.setFontVariationSettings("'wght' ${f.wght}")
                b.build() ?: Typeface.DEFAULT
            } else Typeface.create(f.sys, Typeface.NORMAL)
        } catch (e: Throwable) {
            Typeface.DEFAULT
        }
    }

    private fun scale(): Float = SIZE_F[size] * FONTS[font].scale

    // Ausgangswerte je Text (damit wiederholtes Stylen nichts aufschaukelt)
    private class Base(val size: Float, val color: Int, val bold: Boolean)
    private val bases = WeakHashMap<TextView, Base>()

    /** Dialog an das gewählte Aussehen anpassen – auch Listen, die erst später gezeichnet werden. */
    fun attach(d: AlertDialog, ctx: Context) {
        val dens = ctx.resources.displayMetrics.density
        val gd = GradientDrawable().apply {
            setColor(bg(ctx))
            cornerRadius = CORNER_DP[corner] * dens
            setStroke((1.2f * dens).toInt(), (accentColor() and 0x00FFFFFF) or (0x66 shl 24))
        }
        d.window?.setBackgroundDrawable(InsetDrawable(gd, (16 * dens).toInt(), (24 * dens).toInt(), (16 * dens).toInt(), (24 * dens).toInt()))
        val dv = d.window?.decorView ?: return
        // vor jedem Zeichnen (auch neu eingeblendete Listenzeilen) – idempotent, daher günstig
        dv.viewTreeObserver.addOnPreDrawListener { style(dv, ctx); true }
    }

    private fun lum(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    private fun sat(c: Int): Float {
        val mx = maxOf(Color.red(c), Color.green(c), Color.blue(c))
        val mn = minOf(Color.red(c), Color.green(c), Color.blue(c))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }

    private fun mapColor(c: Int, light: Boolean, ink: Int): Int {
        val a = Color.alpha(c)
        if (sat(c) > 0.35f) {
            // Farbige Texte (Links, Hinweise): im hellen Modus abdunkeln, damit sie lesbar bleiben
            if (light && lum(c) > 0.55f) return Color.argb(a, Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
            return c
        }
        val l = lum(c)
        return if (light && l > 0.6f) Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        else if (!light && l < 0.35f) Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        else c
    }

    fun style(root: View, ctx: Context) {
        val light = isLight(ctx)
        val ink = ink(ctx)
        val acc = accentColor()
        val tf = typeface(ctx)
        val sc = scale()
        fun walk(v: View) {
            if (v is TextView && v.tag != KEEP) {
                val b = bases.getOrPut(v) { Base(v.textSize, v.currentTextColor, v.typeface?.isBold == true) }
                val want = Typeface.create(tf, if (b.bold) Typeface.BOLD else Typeface.NORMAL)
                if (v.typeface != want && v !is EditText) v.typeface = want
                val ns = b.size * sc
                if (kotlin.math.abs(v.textSize - ns) > 0.5f) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ns)
                val id = v.id
                val col = when {
                    id == android.R.id.button1 || id == android.R.id.button2 || id == android.R.id.button3 -> acc
                    id != 0 && id == titleId -> ink
                    v is Button && v.background != null && v !is CompoundButton -> b.color
                    else -> mapColor(b.color, light, ink)
                }
                if (v.currentTextColor != col) v.setTextColor(col)
            }
            if (v is ListView) {
                if (v.divider != null) v.dividerHeight = 0
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        try { walk(root) } catch (ignored: Throwable) {}
    }
}
