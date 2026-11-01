package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🚀 FX-GENERATOR: Übergangs-Effekte im Takt – Riser, Downlifter, Impact, Sub-Drop, Reverse-Crash, Sweep,
 * Snare-Build, Whoosh. Mit „AB NÄCHSTER EINS“ startet ein Effekt auf der nächsten Eins und endet genau nach
 * N Takten wieder auf einer Eins (z. B. Riser über 8 Takte → Drop).
 */
object FxGen {
    val TYPES = arrayOf("RISER", "DOWNLIFTER", "IMPACT", "SUB-DROP", "REVERSE-CRASH", "SWEEP", "SNARE-BUILD", "WHOOSH")
    val ICONS = arrayOf("🚀", "🪂", "💥", "🔻", "⏪", "🌊", "🥁", "💨")
    @Volatile var bars = 4
    @Volatile var intensity = 0.8f
    @Volatile var vol = 0.8f
    @Volatile var quant = true
    @Volatile var meter = 0f

    private class Fx(val type: Int, val start: Long, val len: Long) {
        var lp = 0f; var bp = 0f; var lp2 = 0f; var bp2 = 0f
        var ph = DoubleArray(6)
        var hit = -1L; var hitT = 0; var hitPh = 0.0
        var r = 31337
        fun nz(): Float { r = r * 1664525 + 1013904223; return ((r ushr 8) / 16777216f) * 2f - 1f }
    }
    private val fx = java.util.concurrent.CopyOnWriteArrayList<Fx>()
    private val trig = java.util.concurrent.ConcurrentLinkedQueue<IntArray>()

    /** Effekt [type] auslösen; [nBars] = Länge (0 = Grundeinstellung). */
    fun fire(type: Int, nBars: Int = 0) { trig.add(intArrayOf(type, if (nBars > 0) nBars else bars)) }
    /** 💥 Drop-Paket: Riser + Snare-Build über N Takte, auf der Eins danach Impact + Sub-Drop. */
    fun dropPack() { trig.add(intArrayOf(100, bars)) }
    fun stopAll() { fx.clear() }

    /** Fortschritt der laufenden Effekte (0..1) für die Anzeige, −1 = wartet auf die Eins. */
    val progress = FloatArray(8) { -2f }

    fun active(): Boolean = fx.isNotEmpty() || trig.isNotEmpty()

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w: Long, ms: Long, ml: Long) {
        val bar = if (ml > 0L) ml else sr * 2L
        while (true) {
            val t = trig.poll() ?: break
            val nextOne = if (quant) ms + (Math.floorDiv(w - ms, bar) + 1) * bar else w
            val nb = t[1].toLong()
            if (t[0] == 100) {
                fx.add(Fx(0, nextOne, nb * bar)); fx.add(Fx(6, nextOne, nb * bar))
                fx.add(Fx(2, nextOne + nb * bar, (sr * 3).toLong())); fx.add(Fx(3, nextOne + nb * bar, (sr * 2).toLong()))
            } else {
                val len = when (t[0]) { 2 -> sr * 3L; 3 -> sr * 2L; 7 -> bar; else -> nb * bar }
                fx.add(Fx(t[0], nextOne, len))
            }
            if (fx.size > 10) fx.removeAt(0)
        }
        java.util.Arrays.fill(progress, -2f)
        val k = intensity
        var pk = 0f
        for (f in fx) {
            if (w > f.start + f.len + sr * 4) { fx.remove(f); continue }
            val pr = ((w - f.start).toDouble() / f.len).toFloat()
            progress[f.type] = if (pr < 0) -1f else pr.coerceAtMost(1f)
            for (i in 0 until n) {
                val t = w + i - f.start
                if (t < 0) continue
                val p = (t.toDouble() / f.len).toFloat()
                val ts = t.toDouble() / sr
                var l = 0f; var r = 0f
                when (f.type) {
                    0, 1 -> {
                        // Riser / Downlifter: Rausch-Bandpass + steigende Säge, Breite wächst
                        if (p > 1f && f.type == 0) continue
                        val q = if (f.type == 0) p else 1f - min(1f, p)
                        val env = if (f.type == 0) q.pow(1.6f) else (if (p < 0.02f) p / 0.02f else 1f) * q.pow(1.4f)
                        if (f.type == 1 && p > 1f) continue
                        val fc = 200.0 * 2.0.pow(q * 6.0 * (0.6 + 0.4 * k))
                        val g = (2 * sin(PI * min(fc, sr / 6.0) / sr)).toFloat()
                        val qq = 1f - 0.85f * q
                        f.lp += g * f.bp; val h = f.nz() - f.lp - qq * f.bp; f.bp += g * h
                        f.lp2 += g * f.bp2; val h2 = f.nz() - f.lp2 - qq * f.bp2; f.bp2 += g * h2
                        val pf = 110.0 * 2.0.pow(q * 3.5 * k)
                        var tone = 0f
                        for (j in 0 until 3) { f.ph[j] += pf * (1 + (j - 1) * 0.01) / sr; if (f.ph[j] >= 1.0) f.ph[j] -= 1.0; tone += (2 * f.ph[j] - 1).toFloat() }
                        tone *= 0.12f * k
                        l = (f.bp * 0.9f + tone) * env; r = (f.bp2 * 0.9f + tone) * env
                    }
                    2 -> {
                        // Impact: Boom + Knall + Hallfahne
                        val fr = 30.0 + 60.0 * exp(-ts * 18)
                        f.ph[0] += fr / sr; if (f.ph[0] >= 1.0) f.ph[0] -= 1.0
                        val boom = (sin(2 * PI * f.ph[0]) * exp(-ts * 2.2)).toFloat()
                        val nzv = f.nz()
                        f.lp += 0.08f * (nzv - f.lp)
                        val crack = (f.lp * 3f * exp(-ts * 25)).toFloat()
                        val g = (2 * sin(PI * 900.0 / sr)).toFloat()
                        f.lp2 += g * f.bp2; val h = f.nz() - f.lp2 - 0.5f * f.bp2; f.bp2 += g * h
                        val tail = (f.bp2 * 0.5f * exp(-ts * 1.3) * k).toFloat()
                        l = boom * 0.9f + crack + tail; r = boom * 0.9f + crack - tail * 0.6f
                    }
                    3 -> {
                        val fr = 28.0 + 45.0 * exp(-ts * 2.5)
                        f.ph[0] += fr / sr; if (f.ph[0] >= 1.0) f.ph[0] -= 1.0
                        val s = tanh(sin(2 * PI * f.ph[0]) * 1.5) * exp(-ts * 1.4)
                        l = s.toFloat(); r = l
                    }
                    4 -> {
                        // Reverse-Crash: Rauschen schwillt an, endet hart
                        if (p > 1f) continue
                        val env = p * p * p
                        f.lp += 0.5f * (f.nz() - f.lp); val hpL = f.nz() - f.lp
                        f.lp2 += 0.5f * (f.nz() - f.lp2); val hpR = f.nz() - f.lp2
                        l = hpL * env * 0.8f; r = hpR * env * 0.8f
                    }
                    5 -> {
                        if (p > 1f) continue
                        val q = sin(PI * p).toFloat()
                        val fc = 150.0 * 2.0.pow(q * 6.5 * k)
                        val g = (2 * sin(PI * min(fc, sr / 6.0) / sr)).toFloat()
                        f.lp += g * f.bp; val h = f.nz() - f.lp - 0.35f * f.bp; f.bp += g * h
                        val env = 0.3f + 0.7f * q
                        val pan = sin(2 * PI * p).toFloat()
                        l = f.lp * env * (1f - pan * 0.5f) * 0.8f; r = f.lp * env * (1f + pan * 0.5f) * 0.8f
                    }
                    6 -> {
                        // Snare-Build: Viertel → Achtel → 16tel → 32tel, immer lauter
                        if (p > 1f) continue
                        val div = when { p < 0.25f -> 4; p < 0.5f -> 8; p < 0.75f -> 16; else -> 32 }
                        val barsN = f.len.toDouble() / bar
                        val stepLen = (bar / div).toLong().coerceAtLeast(1)
                        val idx = t / stepLen
                        if (idx != f.hit) { f.hit = idx; f.hitT = 0; f.hitPh = 0.0 }
                        val ht = f.hitT.toDouble() / sr
                        f.hitT++
                        val tonef = 180.0 * (1 + p * 0.6 * k)
                        f.hitPh += tonef / sr
                        val tone = (sin(2 * PI * f.hitPh) * exp(-ht * 40)).toFloat()
                        f.lp += 0.4f * (f.nz() - f.lp)
                        val noise = (f.nz() - f.lp) * exp(-ht * 28).toFloat()
                        val env = (0.25f + 0.75f * p) * (if (barsN > 0) 1f else 1f)
                        l = (tone * 0.5f + noise * 0.8f) * env; r = l
                    }
                    else -> {
                        if (p > 1f) continue
                        val fc = 4000.0 * 2.0.pow(-p * 4.0)
                        val g = (2 * sin(PI * min(fc, sr / 6.0) / sr)).toFloat()
                        f.lp += g * f.bp; val h = f.nz() - f.lp - 0.4f * f.bp; f.bp += g * h
                        val env = sin(PI * p).toFloat()
                        l = f.bp * env * (1f - p); r = f.bp * env * p
                        l *= 1.4f; r *= 1.4f
                    }
                }
                val gl = vol * (0.5f + 0.5f * k)
                val yl = tanh((l * gl * 0.55f).toDouble()).toFloat(); val yr = tanh((r * gl * 0.55f).toDouble()).toFloat()
                oL[i] += yl; oR[i] += yr
                val a = abs(yl)
                if (a > pk) pk = a
            }
        }
        meter = max(pk, meter * 0.9f)
    }

    private fun Float.pow(e: Float): Float = this.toDouble().pow(e.toDouble()).toFloat()
}
