package de.marween.looppad

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 🐺 WOLF – Loop-Zerhacker: nimmt den Loop eines Pads, schneidet ihn in 16tel-Scheiben (im Raster des Loops)
 * und baut daraus Takt für Takt neue Varianten: Stotter-Wirbel, rückwärts, Scheiben tauschen, Tonhöhe,
 * Tape-Stop, halbes Tempo, Bitcrusher, Gate, Pause. Läuft im Takt des Loops (der ja im Master-Raster liegt).
 */
object Wolf {
    @Volatile var on = false
    /** Quell-Pad (0..11), −1 = keins. */
    @Volatile var srcPad = -1
    @Volatile var chaos = 0.5f
    @Volatile var density = 0.5f
    /** 0 = ein Takt wiederholt sich … 1 = jeder Takt neu. */
    @Volatile var variation = 0.45f
    @Volatile var level = 0.9f
    /** BEHALTEN: der gerade laufende Takt wiederholt sich. */
    @Volatile var hold = false
    @Volatile var seed = 4711
    /** Original-Pad stumm, solange WOLF läuft. */
    @Volatile var muteSrc = true
    @Volatile var presetIdx = -1

    const val N_ACT = 10
    val ACT_NAMES = arrayOf("NORMAL", "TAUSCH", "RÜCKWÄRTS", "STOTTERN", "TONHÖHE", "TAPE-STOP", "HALBTEMPO", "CRUSH", "GATE", "PAUSE")
    val ACT_SHORT = arrayOf("·", "⇄", "◀", "≡", "♯", "⏬", "½", "▦", "┆", "—")
    /** Gewicht je Aktion (Index 1..9). */
    val weights = floatArrayOf(0f, 1f, 0.8f, 1.2f, 0.6f, 0.4f, 0.4f, 0.5f, 0.6f, 0.3f)

    class Preset(val name: String, val chaos: Float, val density: Float, val variation: Float, val w: FloatArray)
    val PRESETS = listOf(
        Preset("Breakbeat-Mangle", 0.6f, 0.55f, 0.5f, floatArrayOf(0f, 1.6f, 0.9f, 1.0f, 0.3f, 0.2f, 0.4f, 0.2f, 0.3f, 0.3f)),
        Preset("Glitch-Hop", 0.7f, 0.6f, 0.6f, floatArrayOf(0f, 0.6f, 0.5f, 1.6f, 0.8f, 0.3f, 0.2f, 1.2f, 1.0f, 0.4f)),
        Preset("Trap-Rolls", 0.45f, 0.5f, 0.4f, floatArrayOf(0f, 0.3f, 0.2f, 2.2f, 0.6f, 0.1f, 0.2f, 0.1f, 0.5f, 0.2f)),
        Preset("Lo-Fi-Leier", 0.4f, 0.4f, 0.35f, floatArrayOf(0f, 0.4f, 0.4f, 0.4f, 1.0f, 0.8f, 1.2f, 1.4f, 0.2f, 0.2f)),
        Preset("Tape-Stops", 0.5f, 0.35f, 0.4f, floatArrayOf(0f, 0.3f, 0.3f, 0.4f, 0.3f, 2.0f, 0.8f, 0.2f, 0.1f, 0.3f)),
        Preset("Rückwärts-Welt", 0.55f, 0.55f, 0.5f, floatArrayOf(0f, 0.6f, 2.2f, 0.5f, 0.5f, 0.2f, 0.3f, 0.2f, 0.3f, 0.2f)),
        Preset("Stotter-Edit", 0.5f, 0.7f, 0.3f, floatArrayOf(0f, 0.3f, 0.2f, 1.8f, 0.2f, 0.1f, 0.1f, 0.3f, 1.8f, 0.5f)),
        Preset("Sanfte Variation", 0.25f, 0.3f, 0.25f, floatArrayOf(0f, 1.0f, 0.3f, 0.8f, 0.2f, 0.1f, 0.2f, 0.1f, 0.3f, 0.1f)),
        Preset("Totaler Wolf", 0.95f, 0.9f, 1f, floatArrayOf(0f, 1.2f, 1.2f, 1.2f, 1.2f, 0.8f, 0.8f, 1.0f, 1.0f, 0.6f))
    )

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        chaos = p.chaos; density = p.density; variation = p.variation
        for (a in 1 until N_ACT) weights[a] = p.w[a]
    }

    fun reroll() { seed = (seed * 1103515245 + 12345) and 0x7fffffff; replan() }
    /** Neue Einstellungen ab sofort hören (nicht erst im nächsten Takt). */
    fun replan() { for (p in plans) p.bar = Long.MIN_VALUE }

    /** Ein/aus inkl. Stummschalten des Original-Pads; false = keine Quelle. */
    fun switch(v: Boolean): Boolean {
        val sp = srcPad
        if (v && (sp < 0 || !Engine.hasLoop(sp))) return false
        on = v
        if (sp >= 0 && muteSrc && Engine.isMuted(sp) != v) Engine.toggleMute(sp)
        return true
    }

    fun phraseBars(): Int = when { variation < 0.15f -> 1; variation < 0.35f -> 2; variation < 0.6f -> 4; variation < 0.85f -> 8; else -> 0 }
    fun variationLabel(): String = when (phraseBars()) { 0 -> "jeder Takt neu"; 1 -> "1 Takt wiederholt"; else -> "alle ${phraseBars()} Takte" }

    // ---- Anzeige
    @Volatile var curStep = -1
    @Volatile var curAct = 0
    val planView = IntArray(16)
    @Volatile var meter = 0f

    // ---- Plan je Takt
    private class Plan {
        var bar = Long.MIN_VALUE
        val act = IntArray(16)
        val par = IntArray(16)
        val sp = IntArray(16)
        val sn = IntArray(16) { 1 }
    }
    private val plans = arrayOf(Plan(), Plan(), Plan())
    @Volatile private var heldKey = Long.MIN_VALUE

    private fun rawKey(bar: Long): Long {
        val pb = phraseBars()
        return if (pb == 0) bar else Math.floorMod(bar, pb.toLong())
    }
    private fun keyOf(bar: Long): Long = if (hold && heldKey != Long.MIN_VALUE) heldKey else rawKey(bar)
    @Volatile private var curBar = 0L

    /** BEHALTEN an/aus: der gerade laufende Takt wiederholt sich. */
    fun setHold(v: Boolean) {
        heldKey = if (v) rawKey(curBar) else Long.MIN_VALUE
        hold = v
        replan()
    }

    private fun plan(bar: Long, nSl: Int): Plan {
        for (p in plans) if (p.bar == bar) return p
        val p = plans.minByOrNull { if (it.bar == Long.MIN_VALUE) Long.MIN_VALUE else abs(it.bar - bar) * -1 }!!
        val key = keyOf(bar)
        val r = java.util.Random(seed * 7919L + key * 104729L + nSl)
        p.bar = bar
        val c = chaos
        // Wilde Aktionen wachsen mit CHAOS, sanfte (Stottern, Gate) sind immer dabei
        val ew = FloatArray(N_ACT)
        var sum = 0f
        for (a in 1 until N_ACT) {
            val wild = when (a) { 3, 8 -> 0.6f + 0.4f * c; 1 -> 0.3f + 0.9f * c; else -> 0.1f + 1.2f * c * c }
            ew[a] = weights[a] * wild
            sum += ew[a]
        }
        var k = 0
        val phraseEnd = Math.floorMod(bar, 4L) == 3L
        while (k < 16) {
            val wk = when { k >= 12 -> 1f; k % 4 == 3 -> 0.8f; k % 2 == 1 -> 0.6f; else -> 0.4f } * (if (phraseEnd) 1.3f else 1f)
            if (sum <= 0f || r.nextFloat() >= density * 0.75f * wk) { p.act[k] = 0; p.par[k] = 0; p.sp[k] = 0; p.sn[k] = 1; k++; continue }
            var x = r.nextFloat() * sum
            var a = 1
            while (a < N_ACT - 1 && x > ew[a]) { x -= ew[a]; a++ }
            var span = when (a) {
                3 -> if (r.nextFloat() < 0.35f + 0.3f * c) (if (r.nextFloat() < 0.5f) 2 else 4) else 1
                5 -> 2 + r.nextInt(3)
                6 -> if (r.nextFloat() < 0.5f) 2 else 4
                2 -> if (r.nextFloat() < 0.5f) 2 else 1
                1 -> if (r.nextFloat() < 0.4f) 2 else 1
                else -> 1 + (if (r.nextFloat() < 0.3f) 1 else 0)
            }
            // Tape-Stop gern am Taktende
            if (a == 5 && k < 12 && r.nextFloat() < 0.6f) { p.act[k] = 0; p.sp[k] = 0; p.sn[k] = 1; k++; continue }
            span = min(span, 16 - k)
            val par = when (a) {
                1 -> r.nextInt(nSl)
                3 -> intArrayOf(2, 4, 4, 8, 8, 16)[min(5, (r.nextFloat() * (3 + 3 * c)).toInt())] + (if (c > 0.5f && r.nextFloat() < 0.4f) 1000 else 0)
                4 -> intArrayOf(-12, -7, -5, 5, 7, 12)[r.nextInt(6)]
                7 -> 3 + r.nextInt(4)
                8 -> if (r.nextFloat() < 0.5f) 2 else 4
                else -> 0
            }
            for (j in 0 until span) { p.act[k + j] = a; p.par[k + j] = par; p.sp[k + j] = j; p.sn[k + j] = span }
            k += span
        }
        return p
    }

    fun active(): Boolean = on || gain > 0.001f
    private var gain = 0f
    private var crushHold = 0f
    private var crushCnt = 0

    private fun rd(d: FloatArray, idx: Double): Float {
        val len = d.size
        var x = idx % len
        if (x < 0) x += len
        val i0 = x.toInt()
        val fr = (x - i0).toFloat()
        val a = d[i0]
        val b = d[if (i0 + 1 >= len) 0 else i0 + 1]
        return a + (b - a) * fr
    }

    /**
     * [loop] = Quell-Loop, [mlen] = Taktlänge (Samples, 0 = unbekannt), [run] = Transport läuft.
     */
    fun render(oL: FloatArray, oR: FloatArray, n: Int, w: Long, loop: Loop?, mlen: Long, run: Boolean) {
        val d = loop?.data
        val tgt = if (on && run && d != null && d.size > 2000) level else 0f
        if (d == null || d.size <= 2000) { gain = 0f; meter *= 0.9f; return }
        val len = d.size
        val bars = if (mlen > 0L) max(1, Math.round(len.toDouble() / mlen).toInt()) else 1
        val nSl = 16 * bars
        val sl = len.toDouble() / nSl
        val fadeN = 64.0
        val gStep = 1f / 2400f
        var pk = 0f
        for (i in 0 until n) {
            gain = if (gain < tgt) min(tgt, gain + gStep) else max(tgt, gain - gStep)
            if (gain <= 0f) continue
            val rel = w + i - loop.start
            if (rel < 0) continue
            val g = floor(rel / sl).toLong()
            val bar = Math.floorDiv(g, 16L)
            val k = Math.floorMod(g, 16L).toInt()
            val pl = plan(bar, nSl)
            curBar = bar
            val o = rel - g * sl
            val s = Math.floorMod(g, nSl.toLong()).toInt()
            val a = pl.act[k]
            val sp = pl.sp[k]
            val sn = pl.sn[k]
            val t = sp * sl + o                  // Zeit seit Beginn der Aktion
            val tot = sn * sl                    // Länge der Aktion
            val st0 = Math.floorMod(s - sp, nSl)  // Scheibe am Anfang der Aktion
            var env = 1.0
            var v: Float
            when (a) {
                1 -> v = rd(d, ((pl.par[k] + sp) % nSl) * sl + o)
                2 -> v = rd(d, st0 * sl + (tot - 1 - t))
                3 -> {
                    val div = pl.par[k] % 1000
                    val ramp = pl.par[k] >= 1000
                    val sub = sl / div * (if (div >= 16) 2 else 1)
                    val ph = t % sub
                    val prog = t / tot
                    val rate = if (ramp) 2.0.pow(prog) else 1.0
                    v = rd(d, st0 * sl + ph * rate) * (0.55f + 0.45f * prog.toFloat())
                    env = min(1.0, min(ph / fadeN, (sub - ph) / fadeN))
                }
                4 -> v = rd(d, st0 * sl + (t * 2.0.pow(pl.par[k] / 12.0)) % tot)
                5 -> {
                    val p = t / tot
                    v = rd(d, st0 * sl + tot * (p - p * p / 2))
                    env = min(1.0, (1 - p) / 0.15)
                }
                6 -> v = rd(d, st0 * sl + t * 0.5)
                7 -> {
                    if (crushCnt <= 0) {
                        val bits = pl.par[k]
                        val q = (1 shl bits).toFloat()
                        crushHold = Math.round(rd(d, s * sl + o) * q) / q
                        crushCnt = 2 + 2 * (7 - bits)
                    }
                    crushCnt--
                    v = crushHold
                }
                8 -> {
                    v = rd(d, s * sl + o)
                    val cell = sl / pl.par[k]
                    val cp = o % cell
                    env = if (cp < cell / 2) min(1.0, min(cp / fadeN, (cell / 2 - cp) / fadeN)) else 0.0
                }
                9 -> { v = 0f; env = 0.0 }
                else -> v = rd(d, s * sl + o)
            }
            // Übergänge zwischen Aktionen weich (Anfang/Ende der Aktion, Nachbar-Schritte)
            if (a != 0 && a != 3 && a != 8) env = min(env, min(t / fadeN, (tot - t) / fadeN))
            if (a == 0) {
                val prevAct = if (k > 0) pl.act[k - 1] else plan(bar - 1, nSl).act[15]
                val nextAct = if (k < 15) pl.act[k + 1] else plan(bar + 1, nSl).act[0]
                if (prevAct != 0) env = min(env, o / fadeN)
                if (nextAct != 0) env = min(env, (sl - o) / fadeN)
            }
            val y = (v * env.coerceIn(0.0, 1.0) * gain).toFloat()
            oL[i] += y; oR[i] += y
            val ay = abs(y)
            if (ay > pk) pk = ay
            if (i == n - 1) {
                curStep = k; curAct = a
                if (bar == pl.bar) for (j in 0 until 16) planView[j] = pl.act[j]
            }
        }
        meter = max(pk, meter * 0.9f)
    }
}
