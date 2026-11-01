package de.marween.looppad

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Bedienfeld für [Orbits]: Bahnen-Fläche (Satelliten antippen/ziehen, Brunnen setzen), Satelliten-Leiste, 4 Regler. */
class OrbitsPanel(private val host: View) {
    var isOpen = false
        private set
    var onHint: ((String) -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()

    private val box = RectF()
    private val bClose = RectF(); private val bOn = RectF(); private val bAdd = RectF(); private val bNew = RectF(); private val bSync = RectF()
    private val bPreset = RectF(); private val bCore = RectF(); private val bWell = RectF(); private val bRec = RectF(); private val bRecBars = RectF()
    private val space = RectF()
    private val bType = RectF(); private val bPer = RectF(); private val bEcc = RectF(); private val bDir = RectF(); private val bDel = RectF()
    private val sliders = Array(4) { RectF() }
    private val SL = arrayOf("STARDUST", "KOLLISION", "GRAVITATION", "LAUTSTÄRKE")
    private var sel = 0
    private var drag = -1          // 0..3 Regler, 10 = Satellit ziehen
    private var lastX = 0f
    private var recBars = 2
    private var cx = 0f; private var cy = 0f; private var rad = 1f
    private val COLS = intArrayOf(Color.rgb(255, 220, 120), Color.rgb(120, 220, 255), Color.rgb(200, 255, 140), Color.rgb(255, 150, 200), Color.rgb(170, 150, 255), Color.rgb(255, 170, 90), Color.rgb(120, 255, 220))
    private val PERIODS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.33f, 1.5f, 1.62f, 2f, 2.25f, 2.5f, 2.71f, 3f, 3.14f, 4f, 4.5f, 6f, 8f)

    fun open() { isOpen = true; if (Orbits.sats.none { it.active }) Orbits.applyPreset(0); sel = Orbits.sats.indexOfFirst { it.active }.coerceAtLeast(0) }
    fun close() { isOpen = false }

    private fun get(i: Int) = when (i) { 0 -> Orbits.stardust; 1 -> Orbits.collision; 2 -> Orbits.gravity; else -> Orbits.vol }
    private fun set(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) { 0 -> Orbits.stardust = c; 1 -> Orbits.collision = c; 2 -> Orbits.gravity = c; else -> Orbits.vol = c }
    }

    private fun layout(a: RectF) {
        box.set(a)
        val pad = dp(10f); val g = dp(6f); val bh = dp(40f)
        val w = box.width() - 2 * pad
        bClose.set(box.right - pad - bh, box.top + pad, box.right - pad, box.top + pad + bh)
        val row1 = box.top + pad + bh + dp(20f)
        val q = (w - 3 * g) / 4
        val r1 = arrayOf(bOn, bAdd, bNew, bSync)
        for (i in 0 until 4) r1[i].set(box.left + pad + i * (q + g), row1, box.left + pad + i * (q + g) + q, row1 + bh)
        val row2 = row1 + bh + g
        bPreset.set(box.left + pad, row2, box.left + pad + w * 0.36f, row2 + bh)
        bCore.set(bPreset.right + g, row2, bPreset.right + g + w * 0.2f, row2 + bh)
        bWell.set(bCore.right + g, row2, bCore.right + g + w * 0.2f, row2 + bh)
        bRec.set(bWell.right + g, row2, box.right - pad - dp(40f) - g, row2 + bh)
        bRecBars.set(bRec.right + g, row2, box.right - pad, row2 + bh)
        // unten: Regler (2 Reihen) und Satelliten-Leiste
        val sh = dp(40f)
        val sBot = box.bottom - pad
        val sw = (w - g) / 2
        for (i in 0 until 4) {
            val r = i / 2; val c = i % 2
            val top = sBot - (2 - r) * sh - (1 - r) * g
            sliders[i].set(box.left + pad + c * (sw + g), top, box.left + pad + c * (sw + g) + sw, top + sh)
        }
        val eTop = sliders[0].top - g - bh
        val ew = (w - 4 * g)
        bType.set(box.left + pad, eTop, box.left + pad + ew * 0.24f, eTop + bh)
        bPer.set(bType.right + g, eTop, bType.right + g + ew * 0.26f, eTop + bh)
        bEcc.set(bPer.right + g, eTop, bPer.right + g + ew * 0.26f, eTop + bh)
        bDir.set(bEcc.right + g, eTop, bEcc.right + g + ew * 0.12f, eTop + bh)
        bDel.set(bDir.right + g, eTop, box.right - pad, eTop + bh)
        space.set(box.left + pad, row2 + bh + g, box.right - pad, eTop - g)
        cx = space.centerX(); cy = space.centerY()
        rad = min(space.width(), space.height()) / 2f * 0.9f
    }

    private fun sx(x: Float) = cx + x * rad
    private fun sy(y: Float) = cy - y * rad

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.shader = null
        fill.color = if (on) col else Color.rgb(26, 26, 40)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 110, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = if (on) Color.BLACK else Color.WHITE
        text.textSize = min(dp(11.5f), r.height() * 0.4f)
        c.drawText(fitLabel(text, label, r.width() - dp(6f), text.textSize, dp(6f)), r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun perLabel(p: Float): String = if (p == p.toInt().toFloat()) "${p.toInt()}" else String.format(java.util.Locale.GERMANY, "%.2f", p).trimEnd('0').trimEnd(',')

    fun draw(c: Canvas, area: RectF, acc: Int) {
        layout(area)
        val col = Color.rgb(200, 160, 255)
        fill.shader = null
        fill.color = Color.argb(230, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(8, 8, 18)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f); stroke.color = col
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(16f); text.color = Color.WHITE
        c.drawText("🪐 ORBITS · Gravitations-Synth", box.left + dp(12f), bClose.centerY() + dp(5f), text)
        text.textSize = dp(10f); text.color = Color.argb(190, 255, 255, 255)
        val n = Orbits.sats.count { it.active }
        val st = (if (Orbits.on) "läuft" else "aus") + " · $n Satelliten · Kern ${Orbits.CORE_NAMES[Orbits.core]}" + (if (Orbits.colFlash > 0.2f) " · 💥 ${Orbits.colName}" else " · Fläche antippen = Brunnen setzen")
        c.drawText(fitLabel(text, st, box.width() - dp(24f), dp(10f), dp(7f)), box.left + dp(12f), bClose.bottom + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bClose, "✕", false, acc)
        btn(c, bOn, if (Orbits.on) "■ STOP" else "▶ START", Orbits.on, Color.rgb(140, 230, 170))
        btn(c, bAdd, "＋ SATELLIT", false, col)
        btn(c, bNew, "🎲 BAHNEN", false, col)
        btn(c, bSync, "⟲ SYNC", false, col)
        btn(c, bPreset, "◀ " + (if (Orbits.presetIdx >= 0) Orbits.PRESETS[Orbits.presetIdx].name else "VORLAGE") + " ▶", Orbits.presetIdx >= 0, col)
        btn(c, bCore, "KERN: " + Orbits.CORE_NAMES[Orbits.core], Orbits.core != 2, Color.rgb(255, 200, 120))
        btn(c, bWell, if (Orbits.wellPull) "BRUNNEN ⚡" else "BRUNNEN 🧊", false, if (Orbits.wellPull) Color.rgb(255, 120, 90) else Color.rgb(120, 200, 255))
        btn(c, bRec, "⏺ AUF PAD", false, Color.rgb(255, 90, 90))
        btn(c, bRecBars, "$recBars T", false, Color.rgb(255, 90, 90))
        drawSpace(c, col)
        // Satelliten-Leiste
        val s = Orbits.sats.getOrNull(sel)
        if (s != null && s.active) {
            val sc = COLS[s.type % COLS.size]
            btn(c, bType, Orbits.TYPES[s.type].name, true, sc)
            btn(c, bPer, "◂ ${perLabel(s.period)} SCHL. ▸", false, sc)
            btn(c, bEcc, "◂ ELLIPSE ${(s.e * 100).toInt()} ▸", false, sc)
            btn(c, bDir, if (s.dir > 0) "↻" else "↺", false, sc)
            btn(c, bDel, "✕", false, sc)
        } else {
            text.color = Color.argb(160, 255, 255, 255); text.textSize = dp(10f)
            c.drawText("Satellit antippen zum Bearbeiten", (bType.left + bDel.right) / 2, bType.centerY() + dp(4f), text)
        }
        for (i in 0 until 4) {
            val r = sliders[i]
            fill.color = Color.rgb(22, 22, 36)
            c.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.color = Color.argb(150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawRoundRect(RectF(r.left, r.top, r.left + r.width() * get(i), r.bottom), dp(7f), dp(7f), fill)
            text.color = Color.WHITE; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
            c.drawText(SL[i], r.left + dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${(get(i) * 100).toInt()}", r.right - dp(8f), r.centerY() + dp(3.5f), text)
            text.textAlign = Paint.Align.CENTER
        }
        host.postInvalidateOnAnimation()
    }

    private fun drawSpace(c: Canvas, col: Int) {
        // Sterne-Hintergrund
        fill.color = Color.rgb(4, 4, 12)
        c.drawRoundRect(space, dp(12f), dp(12f), fill)
        c.save(); c.clipRect(space)
        fill.color = Color.argb(90, 255, 255, 255)
        for (k in 0 until 60) {
            val x = space.left + ((k * 97) % 1000) / 1000f * space.width()
            val y = space.top + ((k * 61 + 13) % 1000) / 1000f * space.height()
            c.drawCircle(x, y, dp(if (k % 7 == 0) 1.2f else 0.6f), fill)
        }
        // Trigger-Linie nach oben
        stroke.strokeWidth = dp(1.5f); stroke.color = Color.argb(120, 255, 255, 255)
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        c.drawLine(cx, cy, cx, cy - rad * 1.05f, stroke)
        stroke.pathEffect = null
        // Brunnen
        for (w in Orbits.wells) if (w.active) {
            val wc = if (w.pull) Color.rgb(255, 110, 80) else Color.rgb(110, 190, 255)
            fill.shader = RadialGradient(sx(w.x), sy(w.y), rad * 0.18f * 1.4f, Color.argb(150, Color.red(wc), Color.green(wc), Color.blue(wc)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(sx(w.x), sy(w.y), rad * 0.18f * 1.4f, fill)
            fill.shader = null
            stroke.strokeWidth = dp(1f); stroke.color = wc
            c.drawCircle(sx(w.x), sy(w.y), rad * 0.18f, stroke)
            text.color = Color.WHITE; text.textSize = dp(12f)
            c.drawText(if (w.pull) "⚡" else "🧊", sx(w.x), sy(w.y) + dp(4f), text)
        }
        // Bahnen
        for ((i, s) in Orbits.sats.withIndex()) {
            if (!s.active) continue
            val sc = COLS[s.type % COLS.size]
            path.reset()
            val e = s.e.toDouble().coerceIn(0.0, 0.9)
            for (k in 0..64) {
                val ee = 2 * PI * k / 64
                val nu = 2 * atan2(sqrt(1 + e) * sin(ee / 2), sqrt(1 - e) * cos(ee / 2))
                val r = s.a * (1 - e * cos(ee))
                val th = s.dir * nu + s.omega
                val x = sx((r * cos(th)).toFloat()); val y = sy((r * sin(th)).toFloat())
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke.strokeWidth = dp(if (i == sel) 1.6f else 0.8f)
            stroke.color = Color.argb(if (i == sel) 170 else 70, Color.red(sc), Color.green(sc), Color.blue(sc))
            c.drawPath(path, stroke)
        }
        // Kern
        val cf = Orbits.coreFlash.coerceIn(0f, 1f)
        glow.maskFilter = BlurMaskFilter(dp(20f), BlurMaskFilter.Blur.NORMAL)
        glow.color = Color.argb((90 + 160 * cf).toInt(), 255, 190, 90)
        c.drawCircle(cx, cy, dp(18f) + dp(8f) * cf, glow)
        glow.maskFilter = null
        fill.color = if (Orbits.core == 2) Color.rgb(70, 60, 50) else Color.rgb(255, 200, 110)
        c.drawCircle(cx, cy, dp(10f) + dp(4f) * cf, fill)
        // Satelliten
        for ((i, s) in Orbits.sats.withIndex()) {
            if (!s.active) continue
            val sc = COLS[s.type % COLS.size]
            val x = sx(s.x); val y = sy(s.y)
            val f = s.flash.coerceIn(0f, 1f)
            glow.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL)
            glow.color = Color.argb((60 + 190 * f).toInt(), Color.red(sc), Color.green(sc), Color.blue(sc))
            c.drawCircle(x, y, dp(10f) + dp(10f) * f, glow)
            glow.maskFilter = null
            fill.color = sc
            c.drawCircle(x, y, dp(6.5f) + dp(3f) * f, fill)
            if (i == sel) { stroke.strokeWidth = dp(2f); stroke.color = Color.WHITE; c.drawCircle(x, y, dp(12f), stroke) }
            text.color = Color.argb(200, 255, 255, 255); text.textSize = dp(8f)
            c.drawText(Orbits.TYPES[s.type].name, x, y - dp(13f), text)
        }
        // Kollision
        val cfl = Orbits.colFlash
        if (cfl > 0.05f) {
            glow.maskFilter = BlurMaskFilter(dp(24f), BlurMaskFilter.Blur.NORMAL)
            glow.color = Color.argb((220 * cfl).toInt(), 255, 255, 255)
            c.drawCircle(sx(Orbits.colX), sy(Orbits.colY), dp(10f) + dp(40f) * (1f - cfl), glow)
            glow.maskFilter = null
        }
        c.restore()
    }

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (k in 0 until 4) if (sliders[k].contains(x, y)) { drag = k; lastX = x; return }
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return
                if (space.contains(x, y)) { spaceTap(x, y); return }
                tap(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val px = e.getX(0); val py = e.getY(0)
                if (drag in 0..3) { set(drag, get(drag) + (px - lastX) / sliders[drag].width()); lastX = px }
                else if (drag == 10) {
                    val s = Orbits.sats.getOrNull(sel) ?: return
                    // Ziehen: Abstand = Bahngröße, Richtung = Lage der Ellipse
                    val dx = (px - cx) / rad; val dy = (cy - py) / rad
                    s.a = hypot(dx, dy).coerceIn(0.15f, 1f)
                    s.omega = atan2(dy, dx)
                    Orbits.presetIdx = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
    }

    private fun spaceTap(x: Float, y: Float) {
        // Satellit getroffen?
        var best = -1; var bd = dp(26f)
        for ((i, s) in Orbits.sats.withIndex()) if (s.active) {
            val dd = hypot(sx(s.x) - x, sy(s.y) - y)
            if (dd < bd) { bd = dd; best = i }
        }
        if (best >= 0) { sel = best; drag = 10; haptic(); return }
        // Brunnen entfernen oder setzen
        val wx = (x - cx) / rad; val wy = (cy - y) / rad
        val hit = Orbits.wells.indexOfFirst { it.active && hypot(sx(it.x) - x, sy(it.y) - y) < rad * 0.18f }
        if (hit >= 0) { Orbits.wells[hit].active = false; haptic(); onHint?.invoke("Brunnen entfernt"); return }
        val free = Orbits.wells.indexOfFirst { !it.active }
        if (free < 0) { onHint?.invoke("Höchstens ${Orbits.MAX_WELL} Brunnen – einen antippen zum Entfernen"); return }
        Orbits.wells[free].apply { this.x = wx; this.y = wy; pull = Orbits.wellPull; active = true }
        haptic()
        onHint?.invoke(if (Orbits.wellPull) "⚡ Beschleuniger-Brunnen: Satelliten wirbeln hindurch und schleudern mit Akzent heraus" else "🧊 Brems-Brunnen: Satelliten werden hier zäh und langsam")
    }

    private fun tap(x: Float, y: Float) {
        val s = Orbits.sats.getOrNull(sel)
        when {
            bClose.contains(x, y) -> { close(); haptic() }
            bOn.contains(x, y) -> {
                haptic()
                Orbits.on = !Orbits.on
                if (Orbits.on && !Engine.seqPlaying) Engine.transportPlay(false)
                onHint?.invoke(if (Orbits.on) "🪐 Satelliten lösen beim Kreuzen der gestrichelten Linie aus · nah am Kern = schneller und heller" else "ORBITS gestoppt")
            }
            bAdd.contains(x, y) -> {
                haptic()
                val j = Orbits.addSat()
                if (j < 0) onHint?.invoke("Höchstens ${Orbits.MAX_SAT} Satelliten") else { sel = j; onHint?.invoke("Neuer Satellit: ${Orbits.TYPES[Orbits.sats[j].type].name} – ziehen ändert die Bahn") }
            }
            bNew.contains(x, y) -> { haptic(); Orbits.reroll(); onHint?.invoke("🎲 Neue Bahnen – Klänge bleiben") }
            bSync.contains(x, y) -> { haptic(); Orbits.sync(); onHint?.invoke("⟲ Alle Satelliten starten ab dem nächsten Schlag wieder gemeinsam") }
            bPreset.contains(x, y) -> {
                haptic()
                val cur = Orbits.presetIdx
                Orbits.applyPreset(if (cur < 0) 0 else if (x < bPreset.centerX()) cur - 1 else cur + 1)
                sel = 0
                onHint?.invoke("Vorlage ${Orbits.presetIdx + 1}/${Orbits.PRESETS.size}: ${Orbits.PRESETS[Orbits.presetIdx].name}")
            }
            bCore.contains(x, y) -> { haptic(); Orbits.core = (Orbits.core + 1) % Orbits.CORE_NAMES.size; onHint?.invoke("Kern: ${Orbits.CORE_NAMES[Orbits.core]}") }
            bWell.contains(x, y) -> { haptic(); Orbits.wellPull = !Orbits.wellPull; onHint?.invoke(if (Orbits.wellPull) "Neue Brunnen beschleunigen ⚡" else "Neue Brunnen bremsen 🧊") }
            bRecBars.contains(x, y) -> { recBars = when (recBars) { 1 -> 2; 2 -> 4; 4 -> 8; else -> 1 }; haptic() }
            bRec.contains(x, y) -> {
                haptic()
                if (!Orbits.on) { Orbits.on = true; if (!Engine.seqPlaying) Engine.transportPlay(false) }
                val p = Engine.recordInstLoop(256, recBars)
                if (p < 0) onHint?.invoke("Kein freies Pad (1–12) – erst eins leeren")
                else onHint?.invoke("Ab der nächsten Eins: $recBars Takt" + (if (recBars > 1) "e" else "") + " ORBITS → Pad ${p + 1}")
            }
            s != null && s.active && bType.contains(x, y) -> { haptic(); s.type = (s.type + 1) % Orbits.TYPES.size; onHint?.invoke("Klang: ${Orbits.TYPES[s.type].name}") }
            s != null && s.active && bPer.contains(x, y) -> {
                haptic()
                var k = PERIODS.indexOfFirst { it >= s.period - 0.001f }.coerceAtLeast(0)
                k = (k + if (x < bPer.centerX()) -1 else 1).coerceIn(0, PERIODS.size - 1)
                s.period = PERIODS[k]
                onHint?.invoke("Umlauf: ${perLabel(s.period)} Schläge" + if (s.period * 4 % 1f != 0f) " (krumm → wandert gegen den Takt)" else "")
            }
            s != null && s.active && bEcc.contains(x, y) -> {
                haptic()
                s.e = (s.e + if (x < bEcc.centerX()) -0.1f else 0.1f).coerceIn(0f, 0.85f)
                onHint?.invoke("Ellipse ${(s.e * 100).toInt()} % – je flacher, desto stärker beschleunigt der Satellit nahe am Kern")
            }
            s != null && s.active && bDir.contains(x, y) -> { haptic(); s.dir = -s.dir }
            s != null && s.active && bDel.contains(x, y) -> { haptic(); s.active = false; sel = Orbits.sats.indexOfFirst { it.active }.coerceAtLeast(0); onHint?.invoke("Satellit entfernt") }
        }
    }
}
