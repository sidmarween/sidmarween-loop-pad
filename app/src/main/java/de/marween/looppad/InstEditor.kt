package de.marween.looppad

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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Editor für ACID 303 und DRUM 909: links Klaviatur bzw. Instrumente, rechts die Matrix –
 * Noten setzen, verlängern, verschieben, markieren, kopieren, einfügen, löschen, quantisieren,
 * Länge bis 4 Takte, Zoom, MIDI-Datei laden/exportieren und die typischen Klang-Einstellungen.
 */
class InstEditor(private val host: View) {
    /** 0 = zu, 1 = ACID, 2 = DRUM, 3 = SYNTH */
    var kind = 0
        private set
    /** Melodische Instrumente (ACID und SYNTH) teilen sich die Piano-Roll. */
    private val mel: Boolean get() = kind == 1 || kind == 3
    private val dummyAcc = BooleanArray(InstState.MAX)
    private val dummySlide = BooleanArray(InstState.MAX)
    private val aGate: BooleanArray get() = if (kind == 3) InstState.synGate else InstState.acidGate
    private val aSemi: IntArray get() = if (kind == 3) InstState.synSemi else InstState.acidSemi
    private val aTie: BooleanArray get() = if (kind == 3) InstState.synTie else InstState.acidTie
    private val aVel: FloatArray get() = if (kind == 3) InstState.synVel else InstState.acidVel
    private val aAcc: BooleanArray get() = if (kind == 3) dummyAcc.also { java.util.Arrays.fill(it, false) } else InstState.acidAcc
    private val aSlide: BooleanArray get() = if (kind == 3) dummySlide.also { java.util.Arrays.fill(it, false) } else InstState.acidSlide

    private fun liveOn(semi: Int, slide: Boolean) {
        if (kind == 3) {
            Engine.synthLiveOff(SYN_TAG)
            Engine.synthLiveOn(semi, SYN_TAG)
        } else Engine.acidLiveOn(semi, slide)
    }

    private fun liveOff() {
        if (kind == 3) Engine.synthLiveOff(SYN_TAG) else Engine.acidLiveOff()
    }
    val isOpen: Boolean get() = kind != 0

    var onHint: ((String) -> Unit)? = null
    var onMidi: ((Int) -> Unit)? = null
    var onRec: (() -> Unit)? = null
    /** Preset-Auswahl (1 = ACID, 2 = DRUM). */
    var onPresets: ((Int) -> Unit)? = null
    /** Tonleiter wählen (Piano-Roll). */
    var onScale: (() -> Unit)? = null

    private val d = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tmp = RectF()

    // ---------------------------------------------------------------- Layout
    private val box = RectF()
    private val bClose = RectF()
    private val bMidi = RectF()
    private val bSound = RectF()
    private val bPlay = RectF()
    private val bRec = RectF()
    private val bLen = RectF()
    private val bRaster = RectF()
    private val bMode = RectF()
    private val bZoomOut = RectF()
    private val bZoomIn = RectF()
    private val bClear = RectF()
    private val bAll = RectF()
    private val bCopy = RectF()
    private val bPaste = RectF()
    private val bDel = RectF()
    private val bExtra = RectF()
    private val bQuant = RectF()
    private val keys = RectF()
    private val grid = RectF()
    private val accLane = RectF()
    private val sliLane = RectF()
    private val scroll = RectF()
    private val velLane = RectF()
    /** Werkzeugleiste für die Auswahl: ◀ ▶ ▲ ▼ (Oktave) DUPL UNDO */
    private val tools = Array(8) { RectF() }
    /** Taktleiste über der Matrix: tippen = Einfügemarke, ziehen = Zeitbereich markieren */
    private val ruler = RectF()
    /** Senkrechter Bildlauf rechts (Piano-Roll): ziehen = Tonhöhenbereich wählen */
    private val vLane = RectF()

    // ---------------------------------------------------------------- Zustand
    private var sound = false
    private var selMode = false
    private var zoom = 1
    private var col0 = 0
    /** Raster fürs Quantisieren in Schritten: 1 = 1/16, 2 = 1/8, 4 = 1/4 */
    private var qStep = 2
    private var topSemi = 24
    /** Zeilenhöhe der Piano-Roll in dp (zwei Finger senkrecht spreizen = größer). */
    private var rowPx = 17f
    private var pendingFit = false
    private val sel = HashSet<Int>()
    private var insert = -1
    private class AClip(val rel: Int, val semi: Int, val len: Int, val acc: Boolean, val slide: Boolean, val vel: Float = 1f)
    private class DClip(val voice: Int, val rel: Int, val v: Int, val vel: Float = 1f)
    private var aClip: List<AClip> = emptyList()
    private var dClip: List<DClip> = emptyList()
    private var clipSpan = 0

    fun open(k: Int) {
        kind = k
        sel.clear()
        insert = -1
        sound = false
        col0 = 0
        zoom = 1
        topSemi = 24
        pendingFit = true
    }

    fun close() {
        if (mel) liveOff()
        kind = 0
        drag = 0
    }

    private val len: Int get() = when (kind) { 1 -> InstState.acidLen; 3 -> InstState.synLen; else -> InstState.drumLen }
    private val visCols: Int get() = max(4, len / zoom)

    private fun layout(area: RectF) {
        box.set(area)
        val m = dp(8f)
        val bh = dp(34f)
        var y = box.top + m
        val hb = dp(40f)
        bClose.set(box.right - m - hb, y, box.right - m, y + bh)
        bSound.set(bClose.left - dp(6f) - dp(72f), y, bClose.left - dp(6f), y + bh)
        bMidi.set(bSound.left - dp(6f) - dp(62f), y, bSound.left - dp(6f), y + bh)
        bPlay.set(bMidi.left - dp(6f) - hb, y, bMidi.left - dp(6f), y + bh)
        bRec.set(bPlay.left - dp(6f) - hb, y, bPlay.left - dp(6f), y + bh)
        y += bh + dp(8f)
        val row = { rs: Array<RectF>, top: Float ->
            val g = dp(5f)
            val w = (box.width() - 2 * m - (rs.size - 1) * g) / rs.size
            for (i in rs.indices) rs[i].set(box.left + m + i * (w + g), top, box.left + m + i * (w + g) + w, top + dp(30f))
        }
        row(arrayOf(bLen, bRaster, bMode, bZoomOut, bZoomIn, bClear), y)
        y += dp(36f)
        row(arrayOf(bAll, bCopy, bPaste, bDel, bExtra, bQuant), y)
        y += dp(36f)
        row(tools.copyOfRange(0, if (mel) 8 else 6), y)
        if (!mel) { tools[6].setEmpty(); tools[7].setEmpty() }
        y += dp(36f)
        val bottom = box.bottom - m
        val keyW = if (mel) max(dp(44f), box.width() * 0.13f) else max(dp(70f), box.width() * 0.2f)
        val velH = dp(44f)
        val vw = if (mel) dp(20f) else 0f
        ruler.set(box.left + m + keyW + dp(3f), y, box.right - m - vw, y + dp(18f))
        y += dp(20f)
        keys.set(box.left + m, y, box.left + m + keyW, bottom - dp(12f) - velH - dp(4f) - (if (kind == 1) dp(40f) else 0f))
        grid.set(keys.right + dp(3f), y, box.right - m - vw, keys.bottom)
        if (mel) vLane.set(grid.right + dp(4f), grid.top, box.right - m, grid.bottom) else vLane.setEmpty()
        var ly = grid.bottom
        if (kind == 1) {
            accLane.set(grid.left, ly + dp(4f), grid.right, ly + dp(20f))
            sliLane.set(grid.left, accLane.bottom + dp(3f), grid.right, accLane.bottom + dp(19f))
            ly = sliLane.bottom
        } else {
            accLane.setEmpty()
            sliLane.setEmpty()
        }
        velLane.set(grid.left, ly + dp(4f), grid.right, ly + dp(4f) + velH)
        scroll.set(grid.left, velLane.bottom + dp(4f), grid.right, velLane.bottom + dp(10f))
    }

    private val SEMI_MIN = -24
    private val SEMI_MAX = 36
    private val rowsA: Int get() = (grid.height() / dp(rowPx)).toInt().coerceIn(6, SEMI_MAX - SEMI_MIN + 1)
    private fun clampTop() { topSemi = topSemi.coerceIn(min(SEMI_MAX, SEMI_MIN + rowsA - 1), SEMI_MAX) }
    private fun rowH(): Float = if (mel) grid.height() / rowsA else grid.height() / InstState.DV
    private fun colW(): Float = grid.width() / visCols
    private fun colAt(x: Float): Int = (col0 + ((x - grid.left) / colW()).toInt()).coerceIn(0, len - 1)
    private fun colX(c: Int): Float = grid.left + (c - col0) * colW()
    private fun semiAt(y: Float): Int = topSemi - ((y - grid.top) / rowH()).toInt().coerceIn(0, rowsA - 1)
    private fun voiceAt(y: Float): Int = ((y - grid.top) / rowH()).toInt().coerceIn(0, InstState.DV - 1)

    // ---------------------------------------------------------------- ACID: Noten aus Schritten

    private fun aHead(k: Int): Boolean {
        val st = InstState
        if (!aGate[k]) return false
        val prev = Math.floorMod(k - 1, len)
        return !(aTie[k] && aGate[prev] && k > 0)
    }

    private fun aLen(h: Int): Int {
        val st = InstState
        var n = 1
        while (h + n < len && aGate[h + n] && aTie[h + n]) n++
        return n
    }

    /** Kopf der Note, die Schritt k abdeckt (oder -1). */
    private fun aCover(k: Int): Int {
        val st = InstState
        if (k < 0 || k >= len || !aGate[k]) return -1
        var h = k
        while (h > 0 && aTie[h] && aGate[h - 1]) h--
        return h
    }

    private fun aDelete(h: Int) {
        val st = InstState
        val n = aLen(h)
        for (k in h until min(len, h + n)) {
            aGate[k] = false; aTie[k] = false; aAcc[k] = false; aSlide[k] = false
        }
    }

    private fun aSet(h: Int, semi: Int, n: Int, acc: Boolean, slide: Boolean, vel: Float = 1f) {
        val st = InstState
        if (h < 0 || h >= len) return
        val e = min(len, h + max(1, n))
        // überlappende Noten kürzen bzw. entfernen
        val c = aCover(h)
        if (c in 0 until h) {
            // vorige Note endet vor h
            for (k in h until min(len, c + aLen(c))) { aGate[k] = false; aTie[k] = false }
        }
        for (k in h until e) {
            if (aHead(k) && k != h) aDelete(k)
        }
        if (aHead(h)) aDelete(h)
        // eine Note, die nach e weiterliefe, beginnt dort neu
        if (e < len && aGate[e] && aTie[e]) aTie[e] = false
        for (k in h until e) {
            aGate[k] = true
            aTie[k] = k != h
            aSemi[k] = semi
            aAcc[k] = k == h && acc
            aSlide[k] = false
            aVel[k] = vel
        }
        aSlide[e - 1] = slide
    }

    // ---------------------------------------------------------------- Zeichnen

    fun draw(c: Canvas, area: RectF, cols: IntArray) {
        layout(area)
        val acc = cols[2]
        fill.shader = null
        fill.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), fill)
        fill.color = Color.rgb(17, 18, 23)
        c.drawRoundRect(box, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(2f)
        stroke.color = acc
        c.drawRoundRect(box, dp(14f), dp(14f), stroke)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(14f)
        text.color = Color.WHITE
        c.drawText(when (kind) { 1 -> "🔊 ACID 303"; 3 -> "🎹 SYNTH"; else -> "🥁 DRUM 909" }, box.left + dp(12f), bClose.centerY() - dp(1f), text)
        text.textSize = dp(9.5f)
        text.color = Color.argb(180, 255, 255, 255)
        c.drawText("${len / 16} Takt" + (if (len > 16) "e" else "") + " · ${len} Schritte", box.left + dp(12f), bClose.centerY() + dp(12f), text)
        text.textAlign = Paint.Align.CENTER
        btn(c, bRec, if (Engine.seqRecording) "● REC" else "●", Engine.seqRecording, Color.rgb(225, 40, 50))
        btn(c, bPlay, if (Engine.seqPlaying) "■" else "▶", Engine.seqPlaying, acc)
        btn(c, bMidi, "⇅ MIDI", false, acc)
        btn(c, bSound, if (mel) "⚙ SOUND" else "⚙ KITS", sound, acc)
        btn(c, bClose, "✕", false, acc)
        btn(c, bLen, "${len / 16} TAKT" + if (len > 16) "E" else "", false, acc)
        btn(c, bRaster, "Q " + when (qStep) { 1 -> "1/16"; 2 -> "1/8"; else -> "1/4" }, false, acc)
        btn(c, bMode, if (selMode) "⬚ AUSWAHL" else "✏ STIFT", selMode, acc)
        btn(c, bZoomOut, "🔍−", false, acc)
        btn(c, bZoomIn, "🔍+ ${zoom}×", zoom > 1, acc)
        btn(c, bClear, "LEEREN", false, acc)
        btn(c, bAll, "ALLE", false, acc)
        btn(c, bCopy, "KOPIE", false, acc)
        val clipN = if (mel) aClip.size else dClip.size
        btn(c, bPaste, if (clipN > 0) "EINF. $clipN" else "EINF.", false, acc)
        btn(c, bDel, if (sel.isNotEmpty()) "LÖSCH ${sel.size}" else "LÖSCH", false, acc)
        btn(c, bExtra, "▦ PRESETS", false, acc)
        btn(c, bQuant, if (sel.isNotEmpty()) "QUANT ${sel.size}" else "QUANT", false, acc)
        if (sound) {
            drawSound(c, acc)
            return
        }
        if (pendingFit && mel) { pendingFit = false; fitView() }
        clampTop()
        drawTools(c, acc)
        drawRuler(c, acc)
        if (mel) drawAcid(c, acc) else drawDrum(c, acc)
        if (mel) drawVLane(c, acc)
        drawVel(c, acc)
        // Bildlauf
        fill.color = Color.argb(50, 255, 255, 255)
        c.drawRoundRect(scroll, dp(3f), dp(3f), fill)
        if (zoom > 1) {
            val a0 = col0.toFloat() / len
            val a1 = (col0 + visCols).toFloat() / len
            fill.color = acc
            tmp.set(scroll.left + scroll.width() * a0, scroll.top, scroll.left + scroll.width() * a1, scroll.bottom)
            c.drawRoundRect(tmp, dp(3f), dp(3f), fill)
        }
    }

    private fun toolLabels(): Array<String> = if (mel) arrayOf("◀", "▶", "▲ HT", "▼ HT", "OKT ▲", "OKT ▼", "DUPL", "↶ UNDO")
        else arrayOf("◀", "▶", "▲", "▼", "DUPL", "↶ UNDO")

    private fun drawTools(c: Canvas, acc: Int) {
        val l = toolLabels()
        val dim = Color.rgb(120, 200, 255)
        for (i in l.indices) btn(c, tools[i], l[i], false, if (l[i] == "DUPL" && sel.isNotEmpty()) acc else dim)
    }

    /** Taktleiste: Taktnummern, Zählzeiten, Einfügemarke, markierter Zeitbereich. */
    private fun drawRuler(c: Canvas, acc: Int) {
        fill.color = Color.rgb(24, 25, 31)
        c.drawRoundRect(ruler, dp(4f), dp(4f), fill)
        c.save()
        c.clipRect(ruler)
        // markierter Bereich
        if (sel.isNotEmpty()) {
            val ks = if (mel) sel.filter { aHead(it) }.flatMap { listOf(it, it + aLen(it)) } else sel.flatMap { listOf(it % InstState.MAX, it % InstState.MAX + 1) }
            if (ks.isNotEmpty()) {
                fill.color = Color.argb(70, Color.red(acc), Color.green(acc), Color.blue(acc))
                c.drawRect(colX(ks.min()), ruler.top, colX(ks.max()), ruler.bottom, fill)
            }
        }
        for (k in col0..min(len, col0 + visCols)) {
            val x = colX(k)
            val hgt = when { k % 16 == 0 -> ruler.height(); k % 4 == 0 -> ruler.height() * 0.45f; else -> ruler.height() * 0.2f }
            stroke.strokeWidth = if (k % 16 == 0) dp(1.4f) else dp(0.8f)
            stroke.color = Color.argb(if (k % 4 == 0) 170 else 70, 255, 255, 255)
            c.drawLine(x, ruler.bottom - hgt, x, ruler.bottom, stroke)
            if (k % 16 == 0 && k < len) {
                text.textAlign = Paint.Align.LEFT; text.textSize = dp(10f); text.color = Color.WHITE
                c.drawText("${k / 16 + 1}", x + dp(3f), ruler.top + dp(11f), text)
                text.textAlign = Paint.Align.CENTER
            } else if (k % 4 == 0 && k < len && colW() * 4 > dp(26f)) {
                text.textAlign = Paint.Align.LEFT; text.textSize = dp(7.5f); text.color = Color.argb(120, 255, 255, 255)
                c.drawText("${k / 16 + 1}.${k % 16 / 4 + 1}", x + dp(2f), ruler.top + dp(9f), text)
                text.textAlign = Paint.Align.CENTER
            }
        }
        if (insert in col0..col0 + visCols) {
            val x = colX(insert)
            fill.color = Color.rgb(255, 210, 40)
            val path = android.graphics.Path()
            path.moveTo(x - dp(6f), ruler.top); path.lineTo(x + dp(6f), ruler.top); path.lineTo(x, ruler.bottom); path.close()
            c.drawPath(path, fill)
        }
        c.restore()
    }

    private fun laneY(s: Float): Float = vLane.top + (SEMI_MAX - s + 0.5f) / (SEMI_MAX - SEMI_MIN + 1) * vLane.height()

    /** Senkrechter Bildlauf: ganzer Tonumfang, sichtbarer Ausschnitt, Striche wo Noten liegen. */
    private fun drawVLane(c: Canvas, acc: Int) {
        fill.color = Color.rgb(24, 25, 31)
        c.drawRoundRect(vLane, dp(5f), dp(5f), fill)
        stroke.strokeWidth = dp(0.8f)
        for (s in SEMI_MIN..SEMI_MAX) if (Math.floorMod(s, 12) == 0) {
            stroke.color = Color.argb(70, 255, 255, 255)
            c.drawLine(vLane.left + dp(3f), laneY(s.toFloat()), vLane.right - dp(3f), laneY(s.toFloat()), stroke)
        }
        val th0 = laneY(topSemi + 0.5f)
        val th1 = laneY(topSemi - rowsA + 0.5f)
        fill.color = Color.argb(90, Color.red(acc), Color.green(acc), Color.blue(acc))
        tmp.set(vLane.left + dp(1f), th0, vLane.right - dp(1f), th1)
        c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
        stroke.color = acc; stroke.strokeWidth = dp(1.2f)
        c.drawRoundRect(tmp, dp(4f), dp(4f), stroke)
        stroke.strokeWidth = dp(2f)
        for (k in 0 until len) if (aHead(k)) {
            stroke.color = if (k in sel) Color.WHITE else Color.rgb(255, 200, 80)
            val y = laneY(aSemi[k].toFloat())
            c.drawLine(vLane.left + dp(4f), y, vLane.right - dp(4f), y, stroke)
        }
    }

    /** Beim Öffnen: alle vorhandenen Noten in den sichtbaren Bereich holen. */
    private fun fitView() {
        val ss = (0 until len).filter { aHead(it) }.map { aSemi[it] }
        if (ss.isEmpty()) { topSemi = 24; clampTop(); return }
        val lo = ss.min(); val hi = ss.max()
        topSemi = if (hi - lo + 3 <= rowsA) Math.round((lo + hi) / 2f + rowsA / 2f) else hi + 2
        clampTop()
    }

    /** Markierte Noten sichtbar halten (nach Transponieren). */
    private fun ensureVisible() {
        val ss = sel.filter { aHead(it) }.map { aSemi[it] }
        if (ss.isEmpty()) return
        if (ss.max() > topSemi) topSemi = ss.max() + 1
        if (ss.min() < topSemi - rowsA + 1) topSemi = ss.min() + rowsA - 2
        clampTop()
    }

    private fun vScrollTo(y: Float) {
        val center = SEMI_MAX + 0.5f - (y - vLane.top) / vLane.height() * (SEMI_MAX - SEMI_MIN + 1)
        topSemi = Math.round(center + rowsA / 2f)
        clampTop()
    }

    private fun btn(c: Canvas, r: RectF, label: String, on: Boolean, col: Int) {
        fill.color = if (on) col else Color.rgb(34, 36, 44)
        c.drawRoundRect(r, dp(8f), dp(8f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(if (on) 255 else 90, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRoundRect(r, dp(8f), dp(8f), stroke)
        text.color = Color.WHITE
        val lb = fitLabel(text, label, r.width() - dp(8f), dp(11.5f), dp(7f))
        c.drawText(lb, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
    private fun black(semi: Int): Boolean { val n = Math.floorMod(semi, 12); return n == 1 || n == 3 || n == 6 || n == 8 || n == 10 }

    private fun gridBack(c: Canvas, rows: Int, rowColor: (Int) -> Int) {
        fill.color = Color.rgb(28, 29, 35)
        c.drawRect(grid, fill)
        val rh = grid.height() / rows
        for (r in 0 until rows) {
            fill.color = rowColor(r)
            c.drawRect(grid.left, grid.top + r * rh, grid.right, grid.top + (r + 1) * rh, fill)
            stroke.strokeWidth = dp(0.5f)
            stroke.color = Color.argb(30, 255, 255, 255)
            c.drawLine(grid.left, grid.top + (r + 1) * rh, grid.right, grid.top + (r + 1) * rh, stroke)
        }
        val cw = colW()
        for (k in col0..min(len, col0 + visCols)) {
            val x = colX(k)
            val (w, a) = when {
                k % 16 == 0 -> dp(1.6f) to 160
                k % 4 == 0 -> dp(1f) to 80
                else -> dp(0.5f) to 30
            }
            stroke.strokeWidth = w
            stroke.color = Color.argb(a, 255, 255, 255)
            c.drawLine(x, grid.top, x, grid.bottom, stroke)
            if (k % 16 == 0 && k < len) {
                text.textAlign = Paint.Align.LEFT
                text.textSize = dp(8.5f)
                text.color = Color.argb(150, 255, 255, 255)
                c.drawText("${k / 16 + 1}", x + dp(2f), grid.top + dp(9f), text)
                text.textAlign = Paint.Align.CENTER
            }
        }
        // Lauflicht
        val cur = Engine.instCurrentStep(len)
        if (cur in col0 until col0 + visCols) {
            fill.color = Color.argb(45, 255, 255, 255)
            c.drawRect(colX(cur), grid.top, colX(cur) + cw, grid.bottom, fill)
        }
        // Einfügemarke
        if (insert in col0..col0 + visCols) {
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.rgb(255, 210, 40)
            val x = colX(insert)
            var y = grid.top
            while (y < grid.bottom) { c.drawLine(x, y, x, min(grid.bottom, y + dp(6f)), stroke); y += dp(10f) }
        }
    }

    private fun drawAcid(c: Canvas, acc: Int) {
        val st = InstState
        val rows = rowsA
        val rh = rowH()
        // Klaviatur links
        fill.color = Color.rgb(246, 244, 238)
        c.drawRect(keys, fill)
        for (r in 0 until rows) {
            val semi = topSemi - r
            val y0 = keys.top + r * rh
            val held = semi == liveSemi
            if (black(semi)) {
                fill.color = if (held) acc else Color.rgb(28, 28, 32)
                c.drawRect(keys.left, y0, keys.left + keys.width() * 0.62f, y0 + rh, fill)
            } else if (held) {
                fill.color = acc
                c.drawRect(keys.left, y0, keys.right, y0 + rh, fill)
            }
            val n = Math.floorMod(semi, 12)
            if (n == 0 || n == 5) {
                stroke.strokeWidth = dp(1f)
                stroke.color = Color.argb(110, 0, 0, 0)
                c.drawLine(keys.left, y0 + rh, keys.right, y0 + rh, stroke)
            }
            if (InstState.scaleType != 0 && InstState.inScale(semi)) {
                fill.color = if (InstState.isScaleRoot(semi)) acc else Color.argb(150, Color.red(acc), Color.green(acc), Color.blue(acc))
                c.drawRect(keys.right - dp(4f), y0 + dp(1f), keys.right, y0 + rh - dp(1f), fill)
            }
            if (n == 0) {
                text.textAlign = Paint.Align.RIGHT
                text.textSize = min(dp(9.5f), rh * 0.9f)
                text.color = Color.rgb(80, 80, 80)
                c.drawText("C${(if (kind == 3) 4 + st.synOct else 2 + st.acidOct) + Math.floorDiv(semi, 12)}", keys.right - dp(3f), y0 + rh * 0.82f, text)
                text.textAlign = Paint.Align.CENTER
            }
        }
        val scl = InstState.scaleType != 0
        gridBack(c, rows) { r ->
            val sm = topSemi - r
            when {
                scl && InstState.isScaleRoot(sm) -> blendC(Color.rgb(29, 30, 37), acc, 0.22f)
                scl && InstState.inScale(sm) -> Color.rgb(33, 35, 43)
                scl -> Color.rgb(17, 17, 21)
                black(sm) -> Color.rgb(22, 23, 28)
                else -> Color.rgb(29, 30, 37)
            }
        }
        c.save()
        c.clipRect(grid)
        val cw = colW()
        var k = 0
        while (k < len) {
            if (!aHead(k)) { k++; continue }
            val n = aLen(k)
            val semi = aSemi[k]
            val r = topSemi - semi
            if (k + n > col0 && k < col0 + visCols) {
                val x0 = colX(k)
                val x1 = colX(k + n)
                val s = k in sel
                if (r < 0 || r >= rows) {
                    text.textSize = dp(9f)
                    text.color = acc
                    c.drawText(if (r < 0) "▲" else "▼", x0 + cw / 2, if (r < 0) grid.top + dp(10f) else grid.bottom - dp(3f), text)
                } else {
                    val y0 = grid.top + r * rh
                    tmp.set(x0 + dp(1f), y0 + dp(1f), x1 - dp(1f), y0 + rh - dp(1f))
                    fill.color = if (s) blendC(acc, Color.WHITE, 0.55f) else if (aAcc[k]) blendC(acc, Color.rgb(255, 200, 60), 0.5f) else acc
                    c.drawRoundRect(tmp, dp(3f), dp(3f), fill)
                    stroke.strokeWidth = dp(if (s) 2f else 1f)
                    stroke.color = Color.WHITE
                    c.drawRoundRect(tmp, dp(3f), dp(3f), stroke)
                    if (aSlide[k + n - 1]) {
                        // Slide: Pfeil zum nächsten Ton
                        stroke.strokeWidth = dp(2f)
                        stroke.color = Color.rgb(80, 180, 255)
                        c.drawLine(tmp.right - dp(6f), tmp.centerY(), tmp.right + dp(4f), tmp.centerY(), stroke)
                    }
                    if (tmp.width() > dp(16f) && rh > dp(11f)) {
                        text.textSize = min(dp(8.5f), rh * 0.7f)
                        text.color = Color.BLACK
                        text.textAlign = Paint.Align.LEFT
                        c.drawText(names[Math.floorMod(semi, 12)] + (if (aAcc[k]) " A" else ""), tmp.left + dp(3f), tmp.centerY() + text.textSize * 0.36f, text)
                        text.textAlign = Paint.Align.CENTER
                    }
                }
            }
            k += n
        }
        if (drag == D_RUBBER && moved) {
            fill.color = Color.argb(50, Color.red(acc), Color.green(acc), Color.blue(acc))
            c.drawRect(rubber, fill)
            stroke.strokeWidth = dp(1.2f)
            stroke.color = acc
            c.drawRect(rubber, stroke)
        }
        c.restore()
        // Accent / Slide-Spuren
        for ((lane, isAcc) in listOf(accLane to true, sliLane to false)) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = dp(9f)
            text.color = Color.argb(200, 255, 255, 255)
            c.drawText(if (isAcc) "ACCENT" else "SLIDE", lane.left - dp(4f), lane.centerY() + dp(3f), text)
            text.textAlign = Paint.Align.CENTER
            for (kk in col0 until min(len, col0 + visCols)) {
                val on = if (isAcc) aAcc[kk] && aHead(kk) else aSlide[kk] && aGate[kk]
                tmp.set(colX(kk) + dp(1f), lane.top, colX(kk + 1) - dp(1f), lane.bottom)
                fill.color = if (on) (if (isAcc) Color.rgb(255, 170, 40) else Color.rgb(80, 180, 255)) else if (kk % 4 == 0) Color.rgb(38, 40, 48) else Color.rgb(28, 29, 36)
                c.drawRoundRect(tmp, dp(2f), dp(2f), fill)
            }
        }
    }

    /** Lautstärke-Spur: ein Balken je Note – mit dem Finger hoch/runter ziehen. */
    private fun drawVel(c: Canvas, acc: Int) {
        val st = InstState
        fill.color = Color.rgb(22, 23, 29)
        c.drawRoundRect(velLane, dp(4f), dp(4f), fill)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(9f)
        text.color = Color.argb(200, 255, 255, 255)
        c.drawText("VEL", velLane.left - dp(4f), velLane.top + dp(10f), text)
        if (kind == 2) {
            text.textSize = dp(8f)
            text.color = Color.argb(150, 255, 255, 255)
            c.drawText(st.drumNames[st.drumSel], velLane.left - dp(4f), velLane.top + dp(21f), text)
        }
        text.textAlign = Paint.Align.CENTER
        stroke.strokeWidth = dp(0.6f)
        stroke.color = Color.argb(40, 255, 255, 255)
        c.drawLine(velLane.left, velLane.centerY(), velLane.right, velLane.centerY(), stroke)
        for (k in col0 until min(len, col0 + visCols)) {
            val v: Float
            val selK: Boolean
            if (mel) {
                if (!aHead(k)) continue
                v = aVel[k]
                selK = k in sel
            } else {
                val vv = st.drumSel
                if (st.drumSteps[vv][k] == 0) continue
                v = st.drumVel[vv][k]
                selK = (vv * InstState.MAX + k) in sel
            }
            val x0 = colX(k) + colW() * 0.2f
            val x1 = colX(k) + colW() * 0.8f
            val top = velLane.bottom - dp(2f) - (velLane.height() - dp(4f)) * v.coerceIn(0f, 1f)
            fill.color = if (selK) blendC(acc, Color.WHITE, 0.55f) else acc
            c.drawRect(x0, top, max(x0 + dp(2f), x1), velLane.bottom - dp(2f), fill)
            fill.color = Color.WHITE
            c.drawCircle((x0 + x1) / 2, top, dp(2.5f), fill)
        }
    }

    private fun velAt(x: Float, y: Float) {
        val st = InstState
        val k = colAt(x)
        val v = ((velLane.bottom - dp(2f) - y) / (velLane.height() - dp(4f))).coerceIn(0.05f, 1f)
        if (mel) {
            val h = aCover(k)
            if (h < 0) return
            // markierte Noten gemeinsam, wenn die angefasste dazugehört
            if (h in sel && sel.size > 1) for (s2 in sel) aVel[s2] = v else aVel[h] = v
        } else {
            val vv = st.drumSel
            if (st.drumSteps[vv][k] == 0) return
            val key = vv * InstState.MAX + k
            if (key in sel && sel.size > 1) for (s2 in sel) st.drumVel[s2 / InstState.MAX][s2 % InstState.MAX] = v else st.drumVel[vv][k] = v
        }
    }

    private fun drawDrum(c: Canvas, acc: Int) {
        val st = InstState
        val rh = rowH()
        for (v in 0 until InstState.DV) {
            val y0 = keys.top + v * rh
            tmp.set(keys.left, y0 + dp(1f), keys.right, y0 + rh - dp(1f))
            val selV = v == st.drumSel
            fill.color = if (st.drumMute[v]) Color.rgb(60, 30, 34) else if (selV) blendC(Color.rgb(34, 36, 44), acc, 0.5f) else Color.rgb(34, 36, 44)
            c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = min(dp(11f), rh * 0.42f)
            text.color = if (st.drumMute[v]) Color.argb(130, 255, 255, 255) else Color.WHITE
            c.drawText(st.drumNames[v] + (if (st.drumMute[v]) " ✕" else ""), tmp.left + dp(5f), tmp.centerY() - dp(1f), text)
            text.textSize = min(dp(7.5f), rh * 0.3f)
            text.color = Color.argb(140, 255, 255, 255)
            c.drawText(st.drumLong[v], tmp.left + dp(5f), tmp.centerY() + text.textSize + dp(1f), text)
            text.textAlign = Paint.Align.CENTER
        }
        gridBack(c, InstState.DV) { v -> if (v % 2 == 0) Color.rgb(29, 30, 37) else Color.rgb(25, 26, 32) }
        c.save()
        c.clipRect(grid)
        for (v in 0 until InstState.DV) for (k in col0 until min(len, col0 + visCols)) {
            val x = st.drumSteps[v][k]
            if (x == 0) continue
            val y0 = grid.top + v * rh
            tmp.set(colX(k) + dp(1.5f), y0 + dp(1.5f), colX(k + 1) - dp(1.5f), y0 + rh - dp(1.5f))
            val s = (v * InstState.MAX + k) in sel
            fill.color = when {
                s -> blendC(acc, Color.WHITE, 0.55f)
                x == 2 -> blendC(acc, Color.rgb(255, 210, 80), 0.6f)
                else -> acc
            }
            if (st.drumMute[v]) fill.color = blendC(fill.color, Color.rgb(40, 40, 40), 0.6f)
            c.drawRoundRect(tmp, dp(3f), dp(3f), fill)
            if (s) {
                stroke.strokeWidth = dp(2f)
                stroke.color = Color.WHITE
                c.drawRoundRect(tmp, dp(3f), dp(3f), stroke)
            }
            if (x == 2 && tmp.width() > dp(10f)) {
                text.textSize = min(dp(9f), rh * 0.5f)
                text.color = Color.BLACK
                c.drawText("A", tmp.centerX(), tmp.centerY() + text.textSize * 0.36f, text)
            }
        }
        if (drag == D_RUBBER && moved) {
            fill.color = Color.argb(50, Color.red(acc), Color.green(acc), Color.blue(acc))
            c.drawRect(rubber, fill)
            stroke.strokeWidth = dp(1.2f)
            stroke.color = acc
            c.drawRect(rubber, stroke)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- Klang-Einstellungen

    private class Slider(val label: String, val get: () -> Float, val set: (Float) -> Unit, val show: (Float) -> String)
    private class Toggle(val label: () -> String, val on: () -> Boolean, val act: () -> Unit)
    private val sliderRects = ArrayList<RectF>()
    private val toggleRects = ArrayList<RectF>()

    private fun pct(v: Float) = "${(v * 100).toInt()} %"

    private fun sliders(): List<Slider> {
        val st = InstState
        if (kind == 3) return listOf(
            Slider("CUTOFF", { st.synCut }, { st.synCut = it }, ::pct),
            Slider("RESONANZ", { st.synRes }, { st.synRes = it }, ::pct),
            Slider("FILTER-HÜLLKURVE", { st.synEnv }, { st.synEnv = it }, ::pct),
            Slider("ATTACK", { st.synAtk }, { st.synAtk = it }, { v -> "${((0.002 + v * v * 2.5) * 1000).toInt()} ms" }),
            Slider("RELEASE", { st.synRel }, { st.synRel = it }, { v -> "${((0.02 + v * v * 3.0) * 1000).toInt()} ms" }),
            Slider("VERSTIMMUNG", { st.synDetune }, { st.synDetune = it }, ::pct),
            Slider("OKTAVE", { (st.synOct + 3) / 5f }, { st.synOct = Math.round(it * 5) - 3 }, { v -> val o = Math.round(v * 5) - 3; (if (o > 0) "+" else "") + "$o" }),
            Slider("SWING (alle)", { Engine.swing }, { Engine.swing = it }, { v -> "${50 + (v * 25).toInt()} %" }),
            Slider("LAUTSTÄRKE", { st.synVol }, { st.synVol = it }, ::pct)
        )
        return if (mel) listOf(
            Slider("CUTOFF", { st.acidCut }, { st.acidCut = it }, ::pct),
            Slider("RESONANZ", { st.acidRes }, { st.acidRes = it }, ::pct),
            Slider("ENV MOD", { st.acidEnv }, { st.acidEnv = it }, ::pct),
            Slider("DECAY", { st.acidDec }, { st.acidDec = it }, ::pct),
            Slider("ACCENT", { st.acidAccent }, { st.acidAccent = it }, ::pct),
            Slider("GLIDE-ZEIT", { st.acidGlide }, { st.acidGlide = it }, { v -> "${(10 + v * 240).toInt()} ms" }),
            Slider("DRIVE", { st.acidDrive }, { st.acidDrive = it }, ::pct),
            Slider("TUNE", { (st.acidTune + 12) / 24f }, { st.acidTune = Math.round(it * 24) - 12 }, { v -> val t = Math.round(v * 24) - 12; (if (t > 0) "+" else "") + "$t HT" }),
            Slider("OKTAVE", { (st.acidOct + 1) / 3f }, { st.acidOct = Math.round(it * 3) - 1 }, { v -> val o = Math.round(v * 3) - 1; (if (o > 0) "+" else "") + "$o" }),
            Slider("SWING (alle)", { Engine.swing }, { Engine.swing = it }, { v -> "${50 + (v * 25).toInt()} %" }),
            Slider("LAUTSTÄRKE", { st.acidVol }, { st.acidVol = it }, ::pct)
        ) else {
            val v = st.drumSel
            listOf(
                Slider("${st.drumNames[v]} TUNE", { st.drumTune[v] }, { st.drumTune[v] = it }, ::pct),
                Slider("${st.drumNames[v]} DECAY", { st.drumDecay[v] }, { st.drumDecay[v] = it }, ::pct),
                Slider("${st.drumNames[v]} LEVEL", { st.drumLevel[v] }, { st.drumLevel[v] = it }, ::pct),
                Slider("DRIVE (Kit)", { st.drumDrive }, { st.drumDrive = it }, ::pct),
                Slider("SWING (alle)", { Engine.swing }, { Engine.swing = it }, { x -> "${50 + (x * 25).toInt()} %" }),
                Slider("LAUTSTÄRKE (Kit)", { st.drumVol }, { st.drumVol = it }, ::pct)
            )
        }
    }

    private fun toggles(): List<Toggle> {
        val st = InstState
        if (kind == 3) return listOf(
            Toggle({ if (st.synOn) "● AN" else "○ AUS" }, { st.synOn }, { st.synOn = !st.synOn }),
            Toggle({ "KLANG: " + st.synWaveNames[st.synWave] }, { false }, { st.synWave = (st.synWave + 1) % st.synWaveNames.size }),
            Toggle({ "AKKORD: " + st.synChordNames[st.synChord] }, { st.synChord != 0 }, { st.synChord = (st.synChord + 1) % st.synChordNames.size }),
            Toggle({ "FX-BUS" }, { st.synFx }, { st.synFx = !st.synFx }),
            Toggle({ "SKALA: " + st.scaleLabel() }, { st.scaleType != 0 }, { onScale?.invoke() }),
            Toggle({ "🎲 ZUFALL" }, { false }, { Engine.pushPatternUndo("SYNTH Zufall"); st.synRandom() })
        )
        return if (mel) listOf(
            Toggle({ if (st.acidOn) "● AN" else "○ AUS" }, { st.acidOn }, { st.acidOn = !st.acidOn }),
            Toggle({ if (st.acidSquare) "⊓ SQUARE" else "⩘ SAW" }, { st.acidSquare }, { st.acidSquare = !st.acidSquare }),
            Toggle({ "FX-BUS" }, { st.acidFx }, { st.acidFx = !st.acidFx }),
            Toggle({ "SKALA: " + st.scaleLabel() }, { st.scaleType != 0 }, { onScale?.invoke() }),
            Toggle({ "🎲 ZUFALL" }, { false }, { Engine.pushPatternUndo("ACID Zufall"); st.acidRandom() })
        ) else listOf(
            Toggle({ if (st.drumOn) "● AN" else "○ AUS" }, { st.drumOn }, { st.drumOn = !st.drumOn }),
            Toggle({ "FX-BUS" }, { st.drumFx }, { st.drumFx = !st.drumFx }),
            Toggle({ if (st.drumMute[st.drumSel]) "${st.drumNames[st.drumSel]} STUMM" else "${st.drumNames[st.drumSel]} HÖRBAR" }, { st.drumMute[st.drumSel] }, { st.drumMute[st.drumSel] = !st.drumMute[st.drumSel] }),
            Toggle({ "▦ " + (if (st.drumPreset >= 0) st.presetNames[st.drumPreset] else "PRESET") }, { false }, { Engine.pushPatternUndo("DRUM Preset"); st.drumApplyPreset(st.drumPreset + 1) })
        )
    }

    private fun soundArea(): RectF = RectF(if (kind == 2) grid.left else keys.left, grid.top, grid.right, if (mel) scroll.bottom else grid.bottom)

    private fun drawSound(c: Canvas, acc: Int) {
        if (kind == 2) drawDrumNamesOnly(c, acc)
        val a = soundArea()
        fill.color = Color.rgb(24, 26, 32)
        c.drawRoundRect(a, dp(8f), dp(8f), fill)
        val tg = toggles()
        toggleRects.clear()
        val tw = (a.width() - dp(16f) - (tg.size - 1) * dp(6f)) / tg.size
        for (i in tg.indices) {
            val r = RectF(a.left + dp(8f) + i * (tw + dp(6f)), a.top + dp(8f), a.left + dp(8f) + i * (tw + dp(6f)) + tw, a.top + dp(40f))
            toggleRects.add(r)
            btn(c, r, tg[i].label(), tg[i].on(), acc)
        }
        val sl = sliders()
        sliderRects.clear()
        val top = a.top + dp(50f)
        val h = min(dp(44f), (a.bottom - dp(8f) - top) / sl.size)
        for (i in sl.indices) {
            val r = RectF(a.left + dp(10f), top + i * h, a.right - dp(10f), top + (i + 1) * h - dp(6f))
            sliderRects.add(r)
            val v = sl[i].get().coerceIn(0f, 1f)
            text.textAlign = Paint.Align.LEFT
            text.textSize = min(dp(10.5f), r.height() * 0.4f)
            text.color = Color.WHITE
            c.drawText(sl[i].label, r.left, r.top + text.textSize, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = acc
            c.drawText(sl[i].show(v), r.right, r.top + text.textSize, text)
            text.textAlign = Paint.Align.CENTER
            val bar = RectF(r.left, r.bottom - max(dp(8f), r.height() * 0.35f), r.right, r.bottom)
            fill.color = Color.rgb(40, 42, 52)
            c.drawRoundRect(bar, dp(4f), dp(4f), fill)
            fill.color = acc
            tmp.set(bar.left, bar.top, bar.left + bar.width() * v, bar.bottom)
            c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
            fill.color = Color.WHITE
            c.drawCircle(bar.left + bar.width() * v, bar.centerY(), bar.height() * 0.75f, fill)
        }
    }

    private fun drawDrumNamesOnly(c: Canvas, acc: Int) {
        val st = InstState
        val rh = keys.height() / InstState.DV
        for (v in 0 until InstState.DV) {
            tmp.set(keys.left, keys.top + v * rh + dp(1f), keys.right, keys.top + (v + 1) * rh - dp(1f))
            fill.color = if (v == st.drumSel) acc else Color.rgb(34, 36, 44)
            c.drawRoundRect(tmp, dp(4f), dp(4f), fill)
            text.textSize = min(dp(11f), rh * 0.45f)
            text.color = Color.WHITE
            c.drawText(st.drumNames[v], tmp.centerX(), tmp.centerY() + text.textSize * 0.36f, text)
        }
    }

    // ---------------------------------------------------------------- Bedienung

    private val SYN_TAG = 77
    private val D_NONE = 0
    private val D_MOVE = 1
    private val D_RESIZE = 2
    private val D_RUBBER = 3
    private val D_SLIDER = 4
    private val D_KEYS = 5
    private val D_SCROLL = 6
    private val D_CELL = 7
    private val D_VEL = 8
    private val D_VSCROLL = 9
    private val D_RULER = 10
    private val D_PAN = 11
    private var pressToken = 0
    private var longDone = false
    // zwei Finger: verschieben + zoomen
    private var panA = -1
    private var panB = -1
    private var panSx0 = 0f
    private var panSy0 = 0f
    private var panRow0 = 17f
    private var panZoom0 = 1
    private var panSemiAnchor = 0f
    private var panColAnchor = 0f
    private var drag = D_NONE
    private var ptr = -1
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var dHead = -1
    private var dLen0 = 1
    private var dApplied = 0
    private var dAppliedSemi = 0
    private var newNote = false
    private var sliderIdx = -1
    private var liveSemi = Int.MIN_VALUE
    private var keysTop0 = 0
    private var cellVoice = -1
    private var cellStep = -1
    private val rubber = RectF()

    private fun haptic() = host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    fun touch(e: MotionEvent) {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (ptr >= 0) { startPan(e); return }
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == D_PAN) { panMove(e); return }
                val pi = e.findPointerIndex(ptr)
                if (pi >= 0) move(e.getX(pi), e.getY(pi))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (drag == D_PAN) {
                    val id = e.getPointerId(i)
                    if (id == panA || id == panB) { drag = D_NONE; ptr = -1; panA = -1; panB = -1 }
                } else if (e.getPointerId(i) == ptr) up()
            }
            MotionEvent.ACTION_CANCEL -> {
                drag = D_NONE
                ptr = -1
                if (liveSemi != Int.MIN_VALUE) { liveOff(); liveSemi = Int.MIN_VALUE }
            }
        }
    }

    /** Zweiter Finger auf der Matrix: verschieben (beide Richtungen) und zoomen statt bearbeiten. */
    private fun startPan(e: MotionEvent) {
        if (sound || drag == D_PAN) return
        if (drag !in intArrayOf(D_NONE, D_MOVE, D_RESIZE, D_CELL, D_RUBBER)) return
        if (moved && (drag == D_MOVE || (drag == D_RESIZE && !newNote))) return
        val i1 = e.findPointerIndex(ptr)
        val i2 = e.actionIndex
        if (i1 < 0) return
        val x2 = e.getX(i2); val y2 = e.getY(i2)
        if (!grid.contains(x2, y2) && !keys.contains(x2, y2)) return
        // gerade gesetzte Note war nicht gemeint
        if (newNote) { Engine.undo(); newNote = false }
        if (liveSemi != Int.MIN_VALUE) { liveOff(); liveSemi = Int.MIN_VALUE }
        pressToken++
        drag = D_PAN
        panA = ptr
        panB = e.getPointerId(i2)
        val x1 = e.getX(i1); val y1 = e.getY(i1)
        val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
        panSx0 = abs(x1 - x2); panSy0 = abs(y1 - y2)
        panRow0 = rowPx; panZoom0 = zoom
        panSemiAnchor = topSemi + 0.5f - (cy - grid.top) / rowH()
        panColAnchor = col0 + (cx - grid.left) / colW()
    }

    private fun panMove(e: MotionEvent) {
        val a = e.findPointerIndex(panA)
        val b = e.findPointerIndex(panB)
        if (a < 0 || b < 0) return
        val cx = (e.getX(a) + e.getX(b)) / 2
        val cy = (e.getY(a) + e.getY(b)) / 2
        val sx = abs(e.getX(a) - e.getX(b))
        val sy = abs(e.getY(a) - e.getY(b))
        if (mel) {
            if (panSy0 > dp(50f) && sy > dp(30f)) {
                val r = sy / panSy0
                if (abs(r - 1f) > 0.12f) rowPx = (panRow0 * r).coerceIn(10f, 40f)
            }
            topSemi = Math.round(panSemiAnchor + (cy - grid.top) / rowH() - 0.5f)
            clampTop()
        }
        if (panSx0 > dp(50f)) {
            val r = sx / panSx0
            zoom = when {
                r > 2.6f -> panZoom0 * 4
                r > 1.5f -> panZoom0 * 2
                r < 0.4f -> panZoom0 / 4
                r < 0.67f -> panZoom0 / 2
                else -> panZoom0
            }.coerceIn(1, 4)
        }
        col0 = Math.round(panColAnchor - (cx - grid.left) / colW()).coerceIn(0, max(0, len - visCols))
    }

    /** Lange drücken: auf leerem Feld = Rahmen aufziehen (markieren), auf einer Note = markieren/abwählen. */
    private fun longPress(token: Int) {
        if (token != pressToken || ptr < 0 || moved || sound) return
        if (mel) {
            when {
                (drag == D_RESIZE && newNote) -> {
                    Engine.undo()
                    newNote = false
                    liveOff()
                    drag = D_RUBBER
                    rubber.set(downX, downY, downX, downY)
                    onHint?.invoke("⬚ Rahmen ziehen = markieren · loslassen ohne Ziehen = Einfügemarke")
                }
                drag == D_MOVE || drag == D_RESIZE -> {
                    if (dHead in sel && downWasSelected) sel.remove(dHead) else sel.add(dHead)
                    drag = D_MOVE
                    onHint?.invoke(if (sel.isEmpty()) "Auswahl leer" else "${sel.size} markiert – ziehen verschiebt alle · KOPIE / DUPL / ▲▼")
                }
                else -> return
            }
        } else {
            if (drag == D_CELL && newNote) {
                Engine.undo()
                newNote = false
                drag = D_RUBBER
                rubber.set(downX, downY, downX, downY)
                onHint?.invoke("⬚ Rahmen ziehen = markieren · loslassen ohne Ziehen = Einfügemarke")
            } else return
        }
        longDone = true
        haptic()
        host.invalidate()
    }

    private fun down(id: Int, x: Float, y: Float) {
        downX = x; downY = y; downT = SystemClock.uptimeMillis(); moved = false
        longDone = false
        pressToken++
        val st = InstState
        if (button(x, y)) { haptic(); return }
        if (sound) {
            toggleRects.forEachIndexed { k, r -> if (r.contains(x, y)) { toggles()[k].act(); haptic(); return } }
            sliderRects.forEachIndexed { k, r ->
                if (x >= r.left - dp(6f) && x <= r.right + dp(6f) && y >= r.top - dp(4f) && y <= r.bottom + dp(6f)) {
                    ptr = id; drag = D_SLIDER; sliderIdx = k
                    sliders()[k].set(((x - r.left) / r.width()).coerceIn(0f, 1f))
                    return
                }
            }
            if (kind == 2 && keys.contains(x, y)) {
                st.drumSel = ((y - keys.top) / (keys.height() / InstState.DV)).toInt().coerceIn(0, InstState.DV - 1)
                Engine.drum?.trigger(st.drumSel, 0, false)
            }
            return
        }
        if (scroll.contains(x, y) || (zoom > 1 && y > scroll.top - dp(6f) && y < scroll.bottom + dp(10f) && x >= scroll.left && x <= scroll.right)) {
            ptr = id; drag = D_SCROLL
            scrollTo(x)
            return
        }
        if (!sound) {
            val ti = toolRects().indexOfFirst { it.contains(x, y) }
            if (ti >= 0) { tool(ti); haptic(); return }
        }
        if (mel && x >= vLane.left - dp(4f) && x <= vLane.right + dp(4f) && y >= vLane.top && y <= vLane.bottom) {
            ptr = id; drag = D_VSCROLL
            vScrollTo(y)
            return
        }
        if (y >= ruler.top - dp(4f) && y <= ruler.bottom && x >= ruler.left && x <= ruler.right) {
            ptr = id; drag = D_RULER
            insert = colAt(x)
            return
        }
        if (velLane.contains(x, y)) {
            ptr = id; drag = D_VEL
            Engine.pushPatternUndo("Lautstärke")
            velAt(x, y)
            return
        }
        if (keys.contains(x, y)) {
            ptr = id; drag = D_KEYS
            if (mel) {
                keysTop0 = topSemi
                val s = semiAt(y)
                liveSemi = s
                liveOn(s, false)
                // alle Noten dieser Tonhöhe markieren – danach als Gruppe verschieben
                if (!Engine.seqRecording) {
                    sel.clear()
                    for (k in 0 until len) if (aHead(k) && aSemi[k] == s) sel.add(k)
                    if (sel.isNotEmpty()) onHint?.invoke("${sel.size} Noten markiert – eine davon ziehen verschiebt alle")
                }
            } else {
                val v = ((y - keys.top) / rowH()).toInt().coerceIn(0, InstState.DV - 1)
                st.drumSel = v
                cellVoice = v
                Engine.drumHit(v, false)
                if (!Engine.seqRecording) {
                    sel.clear()
                    for (k in 0 until len) if (st.drumSteps[v][k] > 0) sel.add(v * InstState.MAX + k)
                    if (sel.isNotEmpty()) onHint?.invoke("${sel.size} Schläge (${st.drumNames[v]}) markiert – einen ziehen verschiebt alle")
                }
            }
            haptic()
            return
        }
        if (mel && (accLane.contains(x, y) || sliLane.contains(x, y))) {
            val k = colAt(x)
            Engine.pushPatternUndo("ACID Accent/Slide")
            if (accLane.contains(x, y)) {
                val h = aCover(k)
                if (h >= 0) aAcc[h] = !aAcc[h]
            } else if (aGate[k]) {
                val h = aCover(k)
                val end = h + aLen(h) - 1
                aSlide[end] = !aSlide[end]
            }
            haptic()
            return
        }
        if (!grid.contains(x, y)) return
        ptr = id
        if (mel) acidDown(x, y) else drumDown(x, y)
        val tok = pressToken
        if (!selMode) host.postDelayed({ longPress(tok) }, 450L)
    }

    private fun acidDown(x: Float, y: Float) {
        val st = InstState
        val k = colAt(x)
        val semiRaw = semiAt(y)
        val h = aCover(k)
        val semi = if (h >= 0 && aSemi[h] == semiRaw) semiRaw else InstState.snapScale(semiRaw)
        newNote = false
        dApplied = 0
        dAppliedSemi = 0
        if (h >= 0 && aSemi[h] == semi) {
            dHead = h
            dLen0 = aLen(h)
            val endX = colX(h + dLen0)
            if (!selMode && x > endX - max(dp(12f), colW() * 0.4f)) {
                drag = D_RESIZE
                return
            }
            drag = D_MOVE
            downWasSelected = h in sel
            if (selMode) {
                if (h !in sel) sel.add(h)
            } else if (h !in sel) {
                sel.clear()
            }
            liveOn(semi, false)
            host.postDelayed({ liveOff() }, 150L)
            return
        }
        if (selMode) {
            drag = D_RUBBER
            rubber.set(x, y, x, y)
            return
        }
        Engine.pushPatternUndo(if (kind == 3) "SYNTH Note" else "ACID Note")
        aSet(k, semi, 1, false, false)
        sel.clear()
        dHead = k
        dLen0 = 1
        newNote = true
        drag = D_RESIZE
        liveOn(semi, false)
        host.postDelayed({ liveOff() }, 150L)
        haptic()
    }

    private fun drumDown(x: Float, y: Float) {
        val st = InstState
        val k = colAt(x)
        val v = voiceAt(y)
        cellVoice = v
        cellStep = k
        if (selMode) {
            val key = v * InstState.MAX + k
            if (st.drumSteps[v][k] > 0) {
                drag = D_MOVE
                downWasSelected = key in sel
                if (key !in sel) sel.add(key)
                dApplied = 0
                dAppliedSemi = 0
            } else {
                drag = D_RUBBER
                rubber.set(x, y, x, y)
            }
            return
        }
        val key0 = v * InstState.MAX + k
        if (st.drumSteps[v][k] > 0 && key0 in sel) {
            // markierte Gruppe ziehen
            drag = D_MOVE
            downWasSelected = false
            dApplied = 0
            dAppliedSemi = 0
            return
        }
        drag = D_CELL
        if (st.drumSteps[v][k] == 0) {
            // Malen: leere Felder beim Ziehen setzen
            Engine.pushPatternUndo("DRUM Schritt")
            st.drumSteps[v][k] = 1
            st.drumVel[v][k] = 1f
            newNote = true
            Engine.drum?.trigger(v, 0, false)
        } else newNote = false
        st.drumSel = v
        haptic()
    }

    private fun move(x: Float, y: Float) {
        if (drag == D_VEL) { velAt(x, y); return }
        if (drag == D_VSCROLL) { vScrollTo(y); return }
        if (!moved && abs(x - downX) < dp(6f) && abs(y - downY) < dp(6f)) return
        val first = !moved
        moved = true
        val st = InstState
        when (drag) {
            D_VEL -> velAt(x, y)
            D_SLIDER -> {
                val r = sliderRects.getOrNull(sliderIdx) ?: return
                sliders()[sliderIdx].set(((x - r.left) / r.width()).coerceIn(0f, 1f))
            }
            D_SCROLL -> scrollTo(x)
            D_VSCROLL -> vScrollTo(y)
            D_RULER -> {
                val c0 = colAt(min(downX, x))
                val c1 = colAt(max(downX, x))
                sel.clear()
                if (mel) { for (k in 0 until len) if (aHead(k) && k <= c1 && k + aLen(k) - 1 >= c0) sel.add(k) }
                else for (v in 0 until InstState.DV) for (k in c0..c1) if (InstState.drumSteps[v][k] > 0) sel.add(v * InstState.MAX + k)
                insert = c0
            }
            D_KEYS -> if (mel) {
                // senkrecht ziehen = Klaviatur verschieben, sonst Ton wechseln
                if (abs(y - downY) > dp(30f) && abs(x - downX) > dp(30f)) {
                    topSemi = keysTop0 + ((y - downY) / rowH()).toInt()
                    clampTop()
                } else {
                    val s = semiAt(y)
                    if (s != liveSemi) {
                        liveSemi = s
                        liveOn(s, true)
                    }
                }
            }
            D_RESIZE -> if (mel) {
                val end = max(dHead + 1, colAt(x) + 1)
                if (first && !newNote) Engine.pushPatternUndo("ACID Notenlänge")
                aSet(dHead, aSemi[dHead], end - dHead, aAcc[dHead], aSlide[dHead + aLen(dHead) - 1], aVel[dHead])
            }
            D_MOVE -> {
                if (first) Engine.pushPatternUndo("Noten verschieben")
                val dc = ((x - downX) / colW()).let { Math.round(it) }
                val dr = if (mel) -Math.round((y - downY) / rowH()) else Math.round((y - downY) / rowH())
                if (dc != dApplied || dr != dAppliedSemi) {
                    if (mel) moveAcid(dc - dApplied, dr - dAppliedSemi) else moveDrum(dc - dApplied, dr - dAppliedSemi)
                    dApplied = dc
                    dAppliedSemi = dr
                }
            }
            D_RUBBER -> {
                rubber.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                sel.clear()
                val c0 = colAt(rubber.left)
                val c1 = colAt(rubber.right)
                if (mel) {
                    val sTop = semiAt(rubber.top)
                    val sBot = semiAt(rubber.bottom)
                    for (k in 0 until len) if (aHead(k)) {
                        val n = aLen(k)
                        if (k <= c1 && k + n - 1 >= c0 && aSemi[k] in sBot..sTop) sel.add(k)
                    }
                } else {
                    val v0 = voiceAt(rubber.top)
                    val v1 = voiceAt(rubber.bottom)
                    for (v in v0..v1) for (k in c0..c1) if (st.drumSteps[v][k] > 0) sel.add(v * InstState.MAX + k)
                }
            }
            D_CELL -> {
                // Stift auf der Drum-Matrix: weitere Felder malen
                val k = colAt(x)
                val v = voiceAt(y)
                if (newNote && st.drumSteps[v][k] == 0) {
                    st.drumSteps[v][k] = 1
                    st.drumVel[v][k] = 1f
                    Engine.drum?.trigger(v, 0, false)
                }
            }
        }
    }

    private fun up() {
        val st = InstState
        val long = SystemClock.uptimeMillis() - downT > 450
        when (drag) {
            D_KEYS -> if (mel) { liveOff(); liveSemi = Int.MIN_VALUE }
            D_RULER -> onHint?.invoke(if (moved) (if (sel.isEmpty()) "Keine Noten im Bereich" else "${sel.size} markiert – KOPIE, DUPL, ◀ ▶ ▲ ▼ oder ziehen")
                else "Einfügemarke: Takt ${insert / 16 + 1}.${insert % 16 / 4 + 1} – EINF. setzt dort ein")
            D_MOVE -> if (longDone) {
                // lange gedrückt = markiert, nichts löschen
            } else if (!moved && mel) {
                if (selMode) {
                    // nochmal antippen = abwählen (wenn schon markiert)
                    if (downWasSelected) sel.remove(dHead)
                } else {
                    Engine.pushPatternUndo("ACID Note löschen")
                    aDelete(dHead)
                    sel.remove(dHead)
                }
            } else if (!moved && kind == 2 && selMode) {
                val key = cellVoice * InstState.MAX + cellStep
                if (downWasSelected) sel.remove(key)
            } else if (!moved && kind == 2) {
                // Stift: markierten Schlag antippen = löschen
                Engine.pushPatternUndo("DRUM Schritt")
                st.drumSteps[cellVoice][cellStep] = 0
                sel.remove(cellVoice * InstState.MAX + cellStep)
            }
            D_RESIZE -> if (!moved && !newNote && mel && !longDone) {
                Engine.pushPatternUndo("ACID Note löschen")
                aDelete(dHead)
            }
            D_RUBBER -> if (!moved) {
                sel.clear()
                insert = colAt(downX)
                if (longDone) onHint?.invoke("Einfügemarke: Takt ${insert / 16 + 1}.${insert % 16 / 4 + 1}")
            } else if (sel.isNotEmpty()) onHint?.invoke("${sel.size} markiert – KOPIE, DUPL, ◀ ▶ ▲ ▼ oder eine ziehen")
            D_CELL -> if (!moved && !newNote) {
                val v = cellVoice
                val k = cellStep
                Engine.pushPatternUndo("DRUM Schritt")
                if (long) {
                    st.drumSteps[v][k] = if (st.drumSteps[v][k] == 2) 1 else 2
                    onHint?.invoke(if (st.drumSteps[v][k] == 2) "Accent" else "Accent aus")
                } else st.drumSteps[v][k] = 0
            } else if (!moved && newNote && long) {
                st.drumSteps[cellVoice][cellStep] = 2
            }
        }
        drag = D_NONE
        ptr = -1
        downWasSelected = false
        longDone = false
        pressToken++
    }

    private var downWasSelected = false

    private fun scrollTo(x: Float) {
        val f = ((x - scroll.left) / scroll.width()).coerceIn(0f, 1f)
        col0 = (f * len - visCols / 2f).toInt().coerceIn(0, max(0, len - visCols))
    }

    private fun moveAcid(dc: Int, dr: Int) {
        val st = InstState
        val wasSel = dHead in sel
        val heads = if (sel.isNotEmpty() && wasSel) sel.toList() else listOf(dHead)
        val notes = heads.filter { aHead(it) }.map { h -> AClip(h, aSemi[h], aLen(h), aAcc[h], aSlide[h + aLen(h) - 1], aVel[h]) }
        for (n in notes) aDelete(n.rel)
        val newHeads = ArrayList<Int>()
        for (n in notes) {
            val nh = Math.floorMod(n.rel + dc, len)
            aSet(nh, InstState.snapScale(n.semi + dr, dr).coerceIn(SEMI_MIN, SEMI_MAX), min(n.len, len - nh), n.acc, n.slide, n.vel)
            newHeads.add(nh)
        }
        sel.clear()
        if (wasSel) sel.addAll(newHeads)
        dHead = Math.floorMod(dHead + dc, len)
        if (dr != 0) {
            liveOn(aSemi[dHead], false)
            host.postDelayed({ liveOff() }, 120L)
        }
    }

    private fun moveDrum(dc: Int, dv: Int) {
        val st = InstState
        val keysSel = if (sel.isNotEmpty()) sel.toList() else listOf(cellVoice * InstState.MAX + cellStep)
        val items = keysSel.map { Triple(it / InstState.MAX, it % InstState.MAX, st.drumSteps[it / InstState.MAX][it % InstState.MAX]) }
        val vels = items.map { st.drumVel[it.first][it.second] }
        for ((v, k, _) in items) st.drumSteps[v][k] = 0
        sel.clear()
        items.forEachIndexed { i, (v, k, x) ->
            val nv = (v + dv).coerceIn(0, InstState.DV - 1)
            val nk = Math.floorMod(k + dc, len)
            st.drumSteps[nv][nk] = max(st.drumSteps[nv][nk], max(1, x))
            st.drumVel[nv][nk] = vels[i]
            sel.add(nv * InstState.MAX + nk)
        }
        cellVoice = (cellVoice + dv).coerceIn(0, InstState.DV - 1)
        cellStep = Math.floorMod(cellStep + dc, len)
    }

    // ---------------------------------------------------------------- Tasten

    private fun button(x: Float, y: Float): Boolean {
        val st = InstState
        when {
            bClose.contains(x, y) -> close()
            bSound.contains(x, y) -> sound = !sound
            bMidi.contains(x, y) -> onMidi?.invoke(kind)
            bPlay.contains(x, y) -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
            bRec.contains(x, y) -> onRec?.invoke()
            bLen.contains(x, y) -> {
                val nl = if (len >= InstState.MAX) 16 else len + 16
                Engine.pushPatternUndo("Pattern-Länge")
                // neue Takte mit dem ersten Takt füllen, damit es weiterläuft
                if (nl > len) {
                    if (mel) for (k in len until nl) {
                        val s = k % 16
                        aGate[k] = aGate[s]; aSemi[k] = aSemi[s]; aAcc[k] = aAcc[s]
                        aSlide[k] = aSlide[s]; aTie[k] = aTie[s] && s != 0
                    } else for (v in 0 until InstState.DV) for (k in len until nl) st.drumSteps[v][k] = st.drumSteps[v][k % 16]
                }
                when (kind) { 1 -> st.acidLen = nl; 3 -> st.synLen = nl; else -> st.drumLen = nl }
                col0 = col0.coerceIn(0, max(0, nl - visCols))
                sel.clear()
                onHint?.invoke("${nl / 16} Takt" + (if (nl > 16) "e" else "") + if (nl > 16) " – neue Takte als Kopie des ersten" else "")
            }
            bRaster.contains(x, y) -> qStep = when (qStep) { 1 -> 2; 2 -> 4; else -> 1 }
            bMode.contains(x, y) -> {
                selMode = !selMode
                onHint?.invoke(if (selMode) "⬚ Auswahl: Noten antippen oder Rahmen ziehen, Markierte ziehen = verschieben, ins Leere tippen = Einfügemarke"
                               else if (mel) "✏ Stift: antippen setzt Note (ziehen = länger), Note antippen löscht, Note ziehen = verschieben · lange drücken: Note = markieren, leer = Rahmen · 2 Finger = scrollen/zoomen"
                               else "✏ Stift: Felder antippen/ziehen setzt Schläge, antippen löscht, Schlag lange drücken = Accent, leer lange drücken = Rahmen")
            }
            bZoomIn.contains(x, y) -> { zoom = min(4, zoom * 2); col0 = col0.coerceIn(0, max(0, len - visCols)) }
            bZoomOut.contains(x, y) -> { zoom = max(1, zoom / 2); col0 = col0.coerceIn(0, max(0, len - visCols)) }
            bClear.contains(x, y) -> {
                Engine.pushPatternUndo(when (kind) { 1 -> "ACID leeren"; 3 -> "SYNTH leeren"; else -> "DRUM leeren" })
                when (kind) { 1 -> st.acidClear(); 3 -> st.synClear(); else -> st.drumClear() }
                sel.clear()
            }
            bAll.contains(x, y) -> {
                val all = HashSet<Int>()
                if (mel) { for (k in 0 until len) if (aHead(k)) all.add(k) }
                else for (v in 0 until InstState.DV) for (k in 0 until len) if (st.drumSteps[v][k] > 0) all.add(v * InstState.MAX + k)
                if (all.isNotEmpty() && sel.containsAll(all)) sel.clear() else { sel.clear(); sel.addAll(all) }
            }
            bCopy.contains(x, y) -> copy()
            bPaste.contains(x, y) -> paste()
            bDel.contains(x, y) -> {
                if (sel.isEmpty()) { onHint?.invoke("Nichts markiert"); return true }
                Engine.pushPatternUndo("Löschen")
                if (mel) for (h in sel) { if (aHead(h)) aDelete(h) } else for (key in sel) st.drumSteps[key / InstState.MAX][key % InstState.MAX] = 0
                sel.clear()
            }
            bExtra.contains(x, y) -> {
                sel.clear()
                onPresets?.invoke(kind)
            }
            bQuant.contains(x, y) -> quantize()
            else -> return false
        }
        return true
    }

    private fun transpose() {
        // markierte (oder alle) Noten: +1 Halbton; lange drücken der Taste nicht nötig – Auswahl ziehen geht auch
        val st = InstState
        val heads = if (sel.isNotEmpty()) sel.toList() else (0 until len).filter { aHead(it) }
        if (heads.isEmpty()) return
        Engine.pushPatternUndo("Transponieren")
        val up = heads.all { aSemi[it] < 24 }
        for (h in heads) if (aHead(h)) {
            val n = aLen(h)
            for (k in h until h + n) aSemi[k] = (aSemi[k] + if (up) 1 else -12).coerceIn(-24, 36)
        }
        onHint?.invoke(if (up) "+1 Halbton" else "Zurück um eine Oktave")
    }

    private fun toolRects(): List<RectF> = tools.take(if (mel) 8 else 6)

    private fun selectAll() {
        sel.clear()
        if (mel) { for (k in 0 until len) if (aHead(k)) sel.add(k) }
        else for (v in 0 until InstState.DV) for (k in 0 until len) if (InstState.drumSteps[v][k] > 0) sel.add(v * InstState.MAX + k)
    }

    /** Auswahl (oder alles) um [dc] Schritte und [dr] Halbtöne bzw. Spuren verschieben. */
    private fun nudge(dc: Int, dr: Int) {
        if (mel) sel.retainAll { aHead(it) }
        if (sel.isEmpty()) {
            selectAll()
            if (sel.isEmpty()) { onHint?.invoke("Keine Noten da"); return }
            onHint?.invoke("Nichts markiert – ganzes Pattern")
        }
        Engine.pushPatternUndo(if (dr != 0 && mel) "Transponieren" else "Noten verschieben")
        if (mel) {
            dHead = sel.min()
            moveAcid(dc, dr)
            if (dr != 0) ensureVisible()
        } else {
            val k0 = sel.first()
            cellVoice = k0 / InstState.MAX; cellStep = k0 % InstState.MAX
            moveDrum(dc, dr)
        }
    }

    private fun duplicate() {
        if (mel) sel.retainAll { aHead(it) }
        if (sel.isEmpty()) { onHint?.invoke("Erst markieren: Note lange drücken, Rahmen ziehen oder in der Taktleiste ziehen"); return }
        copy(true)
        val at = insert
        paste()
        onHint?.invoke("Verdoppelt → ab Takt ${at / 16 + 1}.${at % 16 / 4 + 1} (bleibt markiert: weiter DUPL drücken reiht an)")
    }

    private fun tool(i: Int) {
        if (mel) when (i) {
            0 -> nudge(-1, 0); 1 -> nudge(1, 0); 2 -> nudge(0, 1); 3 -> nudge(0, -1)
            4 -> nudge(0, 12); 5 -> nudge(0, -12); 6 -> duplicate(); else -> undoNow()
        } else when (i) {
            0 -> nudge(-1, 0); 1 -> nudge(1, 0); 2 -> nudge(0, -1); 3 -> nudge(0, 1)
            4 -> duplicate(); else -> undoNow()
        }
    }

    private fun undoNow() {
        val l = Engine.undo()
        sel.clear()
        onHint?.invoke(if (l != null) "↶ $l" else "Nichts zum Rückgängigmachen")
    }

    private fun copy(quiet: Boolean = false) {
        val st = InstState
        if (sel.isEmpty()) { onHint?.invoke("Erst markieren: Note lange drücken, Rahmen ziehen oder in der Taktleiste ziehen"); return }
        if (mel) {
            val heads = sel.filter { aHead(it) }.sorted()
            if (heads.isEmpty()) return
            val s0 = heads.first()
            aClip = heads.map { h -> AClip(h - s0, aSemi[h], aLen(h), aAcc[h], aSlide[h + aLen(h) - 1], aVel[h]) }
            val end = heads.maxOf { it + aLen(it) }
            clipSpan = ((end - s0 + 3) / 4) * 4
            insert = (s0 + clipSpan).let { if (it >= len) 0 else it }
            if (!quiet) onHint?.invoke("${aClip.size} Noten kopiert · EINF. setzt sie an der gelben Marke ein (Marke: Taktleiste antippen)")
        } else {
            val items = sel.map { Triple(it / InstState.MAX, it % InstState.MAX, st.drumSteps[it / InstState.MAX][it % InstState.MAX]) }
            val s0 = items.minOf { it.second }
            dClip = items.map { DClip(it.first, it.second - s0, max(1, it.third), st.drumVel[it.first][it.second]) }
            val end = items.maxOf { it.second } + 1
            clipSpan = ((end - s0 + 3) / 4) * 4
            insert = (s0 + clipSpan).let { if (it >= len) 0 else it }
            if (!quiet) onHint?.invoke("${dClip.size} Schläge kopiert · EINF. setzt sie an der gelben Marke ein (Marke: Taktleiste antippen)")
        }
    }

    private fun paste() {
        val st = InstState
        val at = if (insert >= 0) insert else 0
        if (mel) {
            if (aClip.isEmpty()) { onHint?.invoke("Zwischenablage leer – erst KOPIE"); return }
            Engine.pushPatternUndo("Einfügen")
            sel.clear()
            for (n in aClip) {
                val h = at + n.rel
                if (h >= len) continue
                aSet(h, n.semi, min(n.len, len - h), n.acc, n.slide, n.vel)
                sel.add(h)
            }
        } else {
            if (dClip.isEmpty()) { onHint?.invoke("Zwischenablage leer – erst KOPIE"); return }
            Engine.pushPatternUndo("Einfügen")
            sel.clear()
            for (n in dClip) {
                val k = at + n.rel
                if (k >= len) continue
                st.drumSteps[n.voice][k] = n.v
                st.drumVel[n.voice][k] = n.vel
                sel.add(n.voice * InstState.MAX + k)
            }
        }
        insert = (at + clipSpan).let { if (it >= len) 0 else it }
    }

    private fun quantize() {
        val st = InstState
        val q = qStep
        if (q <= 1) {
            onHint?.invoke("Raster ist 1/16 – die Schritte liegen schon darauf. Q auf 1/8 oder 1/4 stellen.")
            return
        }
        Engine.pushPatternUndo("Quantisieren")
        if (mel) {
            val heads = (if (sel.isNotEmpty()) sel.toList() else (0 until len).filter { aHead(it) }).filter { aHead(it) }.sorted()
            val notes = heads.map { h -> AClip(h, aSemi[h], aLen(h), aAcc[h], aSlide[h + aLen(h) - 1], aVel[h]) }
            for (n in notes) aDelete(n.rel)
            sel.clear()
            for (n in notes) {
                val nh = (Math.round(n.rel / q.toFloat()) * q) % len
                aSet(nh, n.semi, max(1, min(n.len, len - nh)), n.acc, n.slide, n.vel)
                sel.add(nh)
            }
        } else {
            val keysSel = if (sel.isNotEmpty()) sel.toList() else {
                val all = ArrayList<Int>()
                for (v in 0 until InstState.DV) for (k in 0 until len) if (st.drumSteps[v][k] > 0) all.add(v * InstState.MAX + k)
                all
            }
            val items = keysSel.map { Triple(it / InstState.MAX, it % InstState.MAX, st.drumSteps[it / InstState.MAX][it % InstState.MAX]) }
            for ((v, k, _) in items) st.drumSteps[v][k] = 0
            sel.clear()
            for ((v, k, x) in items) {
                val nk = (Math.round(k / q.toFloat()) * q) % len
                st.drumSteps[v][nk] = max(st.drumSteps[v][nk], max(1, x))
                sel.add(v * InstState.MAX + nk)
            }
        }
        onHint?.invoke("Auf " + (if (q == 2) "1/8" else "1/4") + " quantisiert" + if (sel.isNotEmpty()) " (${sel.size})" else "")
    }

    private fun blendC(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    @Suppress("unused")
    private fun fmt(v: Float) = String.format(Locale.GERMANY, "%.2f", v)
}
