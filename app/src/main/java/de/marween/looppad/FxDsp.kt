package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Bausteine für hochwertige Effekte:
 *  - [Plate]: Hallalgorithmus nach Dattorro (Griesinger-Plate) – dichter, weicher Raum ohne metallisches Klingeln
 *  - [Os4]: 4-fach-Überabtastung für Verzerrer (kein Aliasing-„Kratzen“ bei hohen Tönen)
 *  - [hermite]: Interpolation 3. Ordnung für modulierte Verzögerungen (Chorus, Flanger, Echo: keine dumpfen/rauen Höhen)
 *  - [PitchHead]: Tonhöhenverschiebung mit korrelationsgesteuerten Übergängen (kein Flattern/Phasing)
 */
object FxDsp {
    /** Hermite-Interpolation (4 Punkte) an Bruchteil [t] zwischen x0 und x1. */
    fun hermite(xm1: Float, x0: Float, x1: Float, x2: Float, t: Float): Float {
        val c1 = 0.5f * (x1 - xm1)
        val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
        val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
        return ((c3 * t + c2) * t + c1) * t + x0
    }

    /** Liest aus Ringpuffer [b] (Größe beliebig) an Position [pos] (darf negativ sein) mit Hermite-Interpolation. */
    fun readRing(b: FloatArray, pos: Float): Float {
        val size = b.size
        var p = pos
        while (p < 0f) p += size
        val i = p.toInt()
        val t = p - i
        val i0 = i % size
        val im1 = if (i0 == 0) size - 1 else i0 - 1
        val i1 = if (i0 + 1 == size) 0 else i0 + 1
        val i2 = if (i1 + 1 == size) 0 else i1 + 1
        return hermite(b[im1], b[i0], b[i1], b[i2], t)
    }

    /** Weiche Sättigung mit Antiderivat-Glättung: Mittelwert von tanh über [x0, x1] (weniger Aliasing). */
    fun tanhAA(x0: Float, x1: Float): Float {
        val d = x1 - x0
        if (abs(d) < 1e-4f) return tanh(0.5f * (x0 + x1))
        return ((lnCosh(x1) - lnCosh(x0)) / d).toFloat()
    }

    private fun lnCosh(x: Float): Double {
        val a = abs(x.toDouble())
        return a + ln(1.0 + exp(-2.0 * a)) - ln(2.0)
    }

    /** PolyBLEP-Korrektur für bandbegrenzte Sägezähne/Rechtecke. */
    fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }
}

/**
 * 4-fach-Überabtastung (linearphasiger FIR, 64 Abgriffe, Kaiser-Fenster, ~90 dB Sperrdämpfung).
 * [up] liefert 4 Werte, nach der Kennlinie gibt [down] einen Wert zurück. Verzögerung: [LATENCY] Samples.
 */
class Os4 {
    companion object {
        const val TAPS = 64
        /** Gesamtverzögerung (hoch + runter) in Samples der Grundrate. */
        const val LATENCY = 16
        private val H: FloatArray = run {
            val n = TAPS
            val h = DoubleArray(n)
            val fc = 0.5 / 4 * 0.92   // knapp unter der halben Grund-Abtastrate
            val beta = 9.0
            fun i0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (k < 50) { t *= (x / 2 / k) * (x / 2 / k); s += t; k++ }; return s }
            val m = (n - 1) / 2.0
            var sum = 0.0
            for (i in 0 until n) {
                val x = i - m
                val sinc = if (abs(x) < 1e-9) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val r = (i - m) / m
                val w = i0(beta * sqrt(max(0.0, 1 - r * r))) / i0(beta)
                h[i] = sinc * w
                sum += h[i]
            }
            FloatArray(n) { (h[it] / sum).toFloat() }
        }
        /** Polyphasen für das Hochtasten (×4 Verstärkung, da 3 von 4 Werten Nullen sind). */
        private val PH = Array(4) { p -> FloatArray(TAPS / 4) { k -> H[k * 4 + p] * 4f } }
    }

    private val xin = FloatArray(TAPS / 4 * 2)
    private var xi = 0
    private val yin = FloatArray(TAPS * 2)
    private var yi = 0

    fun reset() { java.util.Arrays.fill(xin, 0f); java.util.Arrays.fill(yin, 0f) }

    /** Ein Sample hinein, 4 überabgetastete Werte nach [out]. */
    fun up(x: Float, out: FloatArray) {
        val L = TAPS / 4
        xi = if (xi == 0) L - 1 else xi - 1
        xin[xi] = x; xin[xi + L] = x
        for (p in 0 until 4) {
            val c = PH[p]
            var s = 0f
            for (k in 0 until L) s += c[k] * xin[xi + k]
            out[p] = s
        }
    }

    /** 4 überabgetastete Werte hinein, ein Sample der Grundrate heraus. */
    fun down(v: FloatArray): Float {
        for (p in 0 until 4) {
            yi = if (yi == 0) TAPS - 1 else yi - 1
            yin[yi] = v[p]; yin[yi + TAPS] = v[p]
        }
        var s = 0f
        for (k in 0 until TAPS) s += H[k] * yin[yi + k]
        return s
    }
}

/**
 * Plate-Hall nach Jon Dattorro („Effect Design Part 1“, 1997): Eingangs-Diffusion, zwei gekreuzte Tank-Zweige
 * mit modulierten Allpässen und Dämpfung. [size] skaliert alle Längen (0.4 kleiner Raum … 1.0 große Platte).
 */
class Plate(private val sr: Int, size: Float) {
    private val s = sr / 29761.0 * size
    private fun len(n: Int) = max(4, (n * s).toInt())

    private class AP(n: Int) { val b = FloatArray(n); var i = 0 }
    private class DL(n: Int) { val b = FloatArray(n); var i = 0 }

    private val inAp = arrayOf(AP(len(142)), AP(len(107)), AP(len(379)), AP(len(277)))
    private val modA = FloatArray(len(672) + len(32) + 4); private var modAi = 0
    private val modB = FloatArray(len(908) + len(32) + 4); private var modBi = 0
    private val dA1 = DL(len(4453)); private val apA = AP(len(1800)); private val dA2 = DL(len(3720))
    private val dB1 = DL(len(4217)); private val apB = AP(len(2656)); private val dB2 = DL(len(3163))
    private val modLenA = len(672).toFloat(); private val modLenB = len(908).toFloat()
    private val exc = 16.0 * s
    private var lfo = 0.0
    private val lfoInc = 2 * PI * 0.9 / sr
    private var bw = 0f
    private var dampA = 0f
    private var dampB = 0f
    private var fbA = 0f
    private var fbB = 0f
    // Ausgangs-Abgriffe (aus dem Paper, links)
    private val t1 = len(266); private val t2 = len(2974); private val t3 = len(1913); private val t4 = len(1996)
    private val t5 = len(1990); private val t6 = len(187); private val t7 = len(1066)

    fun reset() {
        for (a in inAp) java.util.Arrays.fill(a.b, 0f)
        java.util.Arrays.fill(modA, 0f); java.util.Arrays.fill(modB, 0f)
        for (d in arrayOf(dA1, dA2, dB1, dB2)) java.util.Arrays.fill(d.b, 0f)
        java.util.Arrays.fill(apA.b, 0f); java.util.Arrays.fill(apB.b, 0f)
        bw = 0f; dampA = 0f; dampB = 0f; fbA = 0f; fbB = 0f
    }

    private fun ap(a: AP, x: Float, g: Float): Float {
        val d = a.b[a.i]
        val v = x - g * d
        a.b[a.i] = v
        a.i = if (a.i + 1 == a.b.size) 0 else a.i + 1
        return d + g * v
    }

    private fun tapAp(a: AP, back: Int): Float = a.b[Math.floorMod(a.i - back, a.b.size)]
    private fun tapDl(d: DL, back: Int): Float = d.b[Math.floorMod(d.i - back, d.b.size)]

    private fun dl(d: DL, x: Float): Float {
        val o = d.b[d.i]
        d.b[d.i] = x
        d.i = if (d.i + 1 == d.b.size) 0 else d.i + 1
        return o
    }

    /** Moduliertes Allpass (Länge [baseLen] ± Auslenkung) auf Ringpuffer [b]. */
    private fun modAp(b: FloatArray, wi: Int, x: Float, g: Float, baseLen: Float, mod: Float): Float {
        val d = FxDsp.readRing(b, wi - baseLen - mod)
        val v = x + g * d
        b[wi] = v
        return d - g * v
    }

    /**
     * Ein Sample. [decay] 0..0.98, [damping] 0 (hell) … 1 (dunkel), [bandwidth] Eingangs-Helligkeit 0..1.
     */
    fun tick(x: Float, decay: Float, damping: Float, bandwidth: Float = 0.9995f): Float {
        bw += (x - bw) * bandwidth
        var v = bw
        v = ap(inAp[0], v, 0.75f)
        v = ap(inAp[1], v, 0.75f)
        v = ap(inAp[2], v, 0.625f)
        v = ap(inAp[3], v, 0.625f)
        lfo += lfoInc
        if (lfo > 2 * PI) lfo -= 2 * PI
        val m1 = (exc * (1 + sin(lfo))).toFloat()
        val m2 = (exc * (1 + cos(lfo))).toFloat()
        val dd2 = (decay + 0.15f).coerceIn(0.25f, 0.5f)
        // Zweig A
        var a = v + fbB * decay
        a = modAp(modA, modAi, a, 0.7f, modLenA, m1); modAi = if (modAi + 1 == modA.size) 0 else modAi + 1
        a = dl(dA1, a)
        dampA += (a - dampA) * (1f - damping)
        a = dampA * decay
        a = ap(apA, a, dd2)
        val outA = dl(dA2, a)
        // Zweig B
        var b = v + fbA * decay
        b = modAp(modB, modBi, b, 0.7f, modLenB, m2); modBi = if (modBi + 1 == modB.size) 0 else modBi + 1
        b = dl(dB1, b)
        dampB += (b - dampB) * (1f - damping)
        b = dampB * decay
        b = ap(apB, b, dd2)
        val outB = dl(dB2, b)
        fbA = outA; fbB = outB
        // Ausgang (Abgriffe links + rechts gemittelt wäre kammartig – daher nur links wie im Paper)
        return 0.6f * (tapDl(dA1, t1) + tapDl(dA1, t2) - tapAp(apA, t3) + tapDl(dA2, t4) - tapDl(dB1, t5) - tapAp(apB, t6) - tapDl(dB2, t7))
    }
}

/**
 * Tonhöhenverschiebung mit zwei Leseköpfen; springt ein Kopf zurück, wird die neue Position per Kreuzkorrelation
 * so gewählt, dass sie zur aktuellen Wellenform passt – weniger Flattern und Kammfilter-Klang als feste Sprünge.
 */
class PitchHead(sr: Int, winMs: Float = 50f) {
    private val size = 1 shl 15
    private val mask = size - 1
    private val buf = FloatArray(size)
    private var w = 0
    private val win = max(256.0, sr * winMs / 1000.0)
    private val search = (sr * 0.010).toInt().coerceIn(32, 1024)
    private val cmpN = (sr * 0.006).toInt().coerceIn(32, 512)
    private var dA = 0.0
    private var dB = win * 0.5
    private var phase = 0.0     // 0..1: Überblendung A→B→A
    private var useA = true

    fun reset() { java.util.Arrays.fill(buf, 0f); dA = 0.0; dB = win * 0.5; phase = 0.0 }

    private fun rd(d: Double): Float {
        val p = w - 1 - d
        val i = floor(p).toInt()
        val t = (p - i).toFloat()
        return FxDsp.hermite(buf[(i - 1) and mask], buf[i and mask], buf[(i + 1) and mask], buf[(i + 2) and mask], t)
    }

    /** Beste neue Verzögerung um [target] herum, passend zur Wellenform am Kopf mit Verzögerung [ref]. */
    private fun align(target: Double, ref: Double): Double {
        var best = target
        var bestC = -1e30
        var k = -search
        while (k <= search) {
            val d = target + k
            if (d < 2 || d > size - 4 * cmpN) { k += 1; continue }
            var c = 0.0
            var e = 1e-9
            var j = 0
            while (j < cmpN) {
                val a = buf[(w - 1 - ref.toInt() - j) and mask]
                val b = buf[(w - 1 - d.toInt() - j) and mask]
                c += a * b; e += b * b
                j += 2
            }
            val score = c / sqrt(e)
            if (score > bestC) { bestC = score; best = d }
            k += 1
        }
        return best
    }

    fun tick(x: Float, ratio: Double): Float {
        buf[w and mask] = x
        w++
        val drift = 1.0 - ratio            // Verzögerung wächst bei tieferer, schrumpft bei höherer Tonhöhe
        dA += drift; dB += drift
        phase += 1.0 / (win * 0.5)
        if (phase >= 1.0) {
            phase -= 1.0
            // der leise gewordene Kopf springt – ausgerichtet am lauten
            val start = if (ratio > 1) 2.0 + search + (ratio - 1.0) * win else 2.0 + search
            if (useA) dA = align(start, dB) else dB = align(start, dA)
            useA = !useA
        }
        dA = dA.coerceIn(2.0, size - 8.0); dB = dB.coerceIn(2.0, size - 8.0)
        // Überblendung (Köpfe sind phasengleich ausgerichtet → gleiche Amplitude): useA = A wird gerade leiser
        val g = (0.5 - 0.5 * cos(PI * phase)).toFloat()
        val ga = if (useA) 1f - g else g
        return rd(dA) * ga + rd(dB) * (1f - ga)
    }
}
