package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tonart-Erkennung und harmonisches Mixen (Camelot-Rad).
 * Tonart als Zahl: 0..11 = Dur (C, C#, D … B), 12..23 = Moll (Cm … Bm), -1 = unbekannt.
 * Erkennung: Chromagramm (Spektrum auf die 12 Halbtöne gefaltet, über den ganzen Track gemittelt),
 * dann Korrelation mit den Krumhansl-Kessler-Tonartprofilen für alle 24 Tonarten.
 */
object Keys {
    private val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    // Krumhansl & Kessler (1982): Wahrnehmungs-Stabilität der Töne in Dur und Moll
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun isMinor(k: Int) = k >= 12
    fun root(k: Int) = k % 12

    /** Name wie „Am“ oder „F#“. */
    fun name(k: Int): String = if (k < 0) "–" else NAMES[root(k)] + if (isMinor(k)) "m" else ""

    /** Camelot-Nummer 1..12. */
    fun camelotNum(k: Int): Int {
        if (k < 0) return 0
        val majorRoot = if (isMinor(k)) (root(k) + 3) % 12 else root(k)
        return ((majorRoot * 7) % 12 + 7) % 12 + 1
    }

    /** Camelot wie „8A“ (Moll = A, Dur = B). */
    fun camelot(k: Int): String = if (k < 0) "–" else "${camelotNum(k)}${if (isMinor(k)) "A" else "B"}"

    /** Tonart um [semis] Halbtöne verschoben. */
    fun shift(k: Int, semis: Int): Int {
        if (k < 0) return -1
        val r = Math.floorMod(root(k) + semis, 12)
        return if (isMinor(k)) 12 + r else r
    }

    /**
     * Passt [b] harmonisch zu [a]? 0 = gleiche Tonart, 1 = harmonisch (±1 auf dem Rad oder Dur/Moll-Paar),
     * 2 = Energie-Wechsel (±2 bzw. ±7 Halbtöne), 3 = passt nicht, -1 = unbekannt.
     */
    fun compat(a: Int, b: Int): Int {
        if (a < 0 || b < 0) return -1
        if (a == b) return 0
        val na = camelotNum(a)
        val nb = camelotNum(b)
        val d = Math.floorMod(nb - na, 12).let { min(it, 12 - it) }
        val same = isMinor(a) == isMinor(b)
        return when {
            same && d == 1 -> 1
            !same && d == 0 -> 1
            same && d == 2 -> 2
            !same && d == 1 -> 2
            else -> 3
        }
    }

    /** Kleinste Verschiebung (−2..+2 Halbtöne), mit der [b] harmonisch zu [a] passt – oder null. */
    fun bestShift(a: Int, b: Int): Int? {
        if (a < 0 || b < 0) return null
        var best: Int? = null
        var bs = 99
        for (s in intArrayOf(0, 1, -1, 2, -2)) {
            val c = compat(a, shift(b, s))
            if (c in 0..1) {
                val score = c * 10 + abs(s)
                if (score < bs) { bs = score; best = s }
            }
        }
        return best
    }

    /** Halbtöne, um die ein Tempo-Verhältnis (ohne Keylock) die Tonhöhe verschiebt. */
    fun semisOfRatio(ratio: Double): Double = 12.0 * ln(max(1e-6, ratio)) / ln(2.0)

    // ---------------------------------------------------------------- Erkennung

    /** Erkennt die Tonart eines Tracks (16 Bit, [ch] Kanäle). Liefert Tonart und Sicherheit 0..1. */
    fun detect(data: ShortArray, ch: Int, len: Int, rate: Int): Pair<Int, Float> {
        // auf ~11–12 kHz herunterrechnen (Mittelwert über [f] Samples)
        val f = max(1, (rate / 11025.0).roundToInt())
        val sr = rate.toDouble() / f
        val m = len / f
        if (m < 4096 * 2) return -1 to 0f
        val x = FloatArray(m)
        for (i in 0 until m) {
            var s = 0f
            for (j in 0 until f) {
                val fr = i * f + j
                s += if (ch == 2) (data[2 * fr] + data[2 * fr + 1]) * 0.5f else data[fr].toFloat()
            }
            x[i] = s / (f * 32768f)
        }
        return detectMono(x, sr)
    }

    /** Erkennung auf Mono-Daten mit Abtastrate [sr] (am besten ~11 kHz). */
    fun detectMono(x: FloatArray, sr: Double): Pair<Int, Float> {
        val n = 4096
        val m = x.size
        if (m < n * 2) return -1 to 0f
        val frames = min(700, (m - n) / (n / 4))
        val hop = (m - n) / max(1, frames)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val re = FloatArray(n)
        val im = FloatArray(n)
        // Halbtöne MIDI 36 (C2, 65 Hz) … 95 (B6, ~1976 Hz)
        val lo = 36
        val hi = 95
        val semi = DoubleArray(hi - lo + 1)
        val chroma = DoubleArray(12)
        // Stimmung schätzen (nicht jeder Track ist auf 440 Hz): mittlere Lage der Spektralspitzen
        // zwischen den Halbtönen (Kreismittel der Nachkommastellen)
        var tc = 0.0
        var ts = 0.0
        run {
            val probe = min(frames, 120)
            for (pi in 0 until probe) {
                val o = (pi.toLong() * (m - n) / max(1, probe)).toInt()
                for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0f }
                fft(re, im)
                val mg = FloatArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
                // nur die 6 stärksten Spitzen je Rahmen (Grundtöne), Rauschen bleibt außen vor
                val cand = ArrayList<Int>()
                for (k in 2 until n / 2 - 1) if (mg[k] > mg[k - 1] && mg[k] >= mg[k + 1]) {
                    val fr = k * sr / n
                    if (fr in 100.0..1000.0) cand.add(k)
                }
                cand.sortByDescending { mg[it] }
                for (k in cand.take(6)) {
                    val a0 = ln(1e-9 + mg[k - 1]); val a1 = ln(1e-9 + mg[k]); val a2 = ln(1e-9 + mg[k + 1])
                    val den = a0 - 2 * a1 + a2
                    val dd = if (abs(den) > 1e-12) 0.5 * (a0 - a2) / den else 0.0
                    val fr = (k + dd) * sr / n
                    val midi = 69.0 + 12.0 * ln(fr / 440.0) / ln(2.0)
                    val ang = 2 * PI * (midi - Math.floor(midi))
                    val wgt = mg[k].toDouble()
                    tc += wgt * cos(ang); ts += wgt * sin(ang)
                }
            }
        }
        var tuning = kotlin.math.atan2(ts, tc) / (2 * PI)
        if (tuning > 0.5) tuning -= 1.0
        if (tuning < -0.5) tuning += 1.0
        // Bin → Halbton und Gewicht (Dreieck um die Halbtonmitte)
        val binSemi = IntArray(n / 2) { -1 }
        val binW = FloatArray(n / 2)
        for (k in 1 until n / 2) {
            val fr = k * sr / n
            val midi = 69.0 + 12.0 * ln(fr / 440.0) / ln(2.0) - tuning
            val r = midi.roundToInt()
            if (r < lo || r > hi) continue
            val dist = abs(midi - r)
            if (dist > 0.4) continue
            binSemi[k] = r - lo
            binW[k] = (1.0 - dist / 0.4).toFloat()
        }
        var used = 0
        for (fi in 0 until frames) {
            val o = fi * hop
            var en = 0.0
            for (i in 0 until n) {
                val v = x[o + i] * win[i]
                re[i] = v
                im[i] = 0f
                en += v * v
            }
            if (en < 1e-7) continue
            fft(re, im)
            java.util.Arrays.fill(semi, 0.0)
            for (k in 1 until n / 2) {
                val s = binSemi[k]
                if (s < 0) continue
                val mag = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
                semi[s] += mag * binW[k]
            }
            // Obertöne und Rauschen dämpfen: nur, was über dem Umfeld (±1 Oktave Mittel) liegt
            var tot = 0.0
            val loc = DoubleArray(semi.size)
            for (s in semi.indices) {
                var a = 0.0
                var c = 0
                for (t in max(0, s - 6)..min(semi.size - 1, s + 6)) { a += semi[t]; c++ }
                loc[s] = max(0.0, semi[s] - 0.8 * a / c)
                tot += loc[s]
            }
            if (tot <= 1e-9) continue
            for (s in semi.indices) chroma[(s + lo) % 12] += loc[s] / tot
            used++
        }
        if (used < 4) return -1 to 0f
        // Korrelation mit allen 24 Profilen
        var best = -1
        var bs = -2.0
        var second = -2.0
        for (key in 0 until 24) {
            val prof = if (key < 12) MAJOR else MINOR
            val r = key % 12
            val c = pearson(chroma) { pc -> prof[Math.floorMod(pc - r, 12)] }
            if (c > bs) { second = bs; bs = c; best = key } else if (c > second) second = c
        }
        val conf = ((bs - second) * 4 + (bs - 0.3)).toFloat().coerceIn(0f, 1f)
        return best to conf
    }

    private fun pearson(a: DoubleArray, b: (Int) -> Double): Double {
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until 12) { ma += a[i]; mb += b(i) }
        ma /= 12; mb /= 12
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until 12) {
            val da = a[i] - ma
            val db = b(i) - mb
            sab += da * db; saa += da * da; sbb += db * db
        }
        return sab / sqrt(max(1e-18, saa * sbb))
    }

    /** FFT (Radix 2, an Ort und Stelle). */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Halbtöne → Tonhöhen-Faktor. */
    fun factor(semis: Double): Double = 2.0.pow(semis / 12.0)
}
