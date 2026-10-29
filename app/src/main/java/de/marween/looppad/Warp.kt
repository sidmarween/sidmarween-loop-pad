package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Clip-Warping wie in Ableton Live:
 * - erkennt Tempo und Länge (Takte) einer Datei – aus dem Dateinamen, der Länge und einer Rhythmus-Analyse
 * - dehnt/staucht Audio per WSOLA auf eine neue Länge, ohne die Tonhöhe zu ändern (nahtlos als Loop)
 */
object Warp {

    /** [offset] = Frames bis zum ersten Schlag (werden abgeschnitten), [cut] = nur bars Takte ab dort verwenden. */
    class Info(val bpm: Float, val bars: Float, val source: String, val offset: Int = 0, val cut: Boolean = false)

    private val POW2 = floatArrayOf(1f, 2f, 4f, 8f, 16f, 32f)
    private val OTHER = floatArrayOf(0.25f, 0.5f, 3f, 6f, 12f, 24f)

    /** Tempo aus dem Dateinamen, z. B. „Beat_120bpm.wav“, „loop 128 BPM“, „house_124_Am.wav“. */
    fun bpmFromName(name: String?): Float? {
        if (name.isNullOrBlank()) return null
        val n = name.substringBeforeLast('.')
        val pats = listOf(
            Regex("(?i)(\\d{2,3}(?:[.,]\\d+)?)\\s*-?\\s*bpm"),
            Regex("(?i)bpm\\s*-?\\s*(\\d{2,3}(?:[.,]\\d+)?)"),
            Regex("(?:^|[_\\- ])(\\d{2,3})(?=$|[_\\- ])")
        )
        for (p in pats) {
            for (m in p.findAll(n)) {
                val v = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: continue
                if (v in 60f..200f) return v
            }
        }
        return null
    }

    /** Rhythmus-Analyse: Onset-Hüllkurve + (zirkuläre) Autokorrelation. Liefert grobes Tempo oder null. */
    private fun analyzeBpm(x: FloatArray, sr: Int): Float? {
        val hop = max(1, sr / 100) // 10 ms
        val nf = x.size / hop
        if (nf < 120) return null
        val e = FloatArray(nf)
        for (k in 0 until nf) {
            var s = 0.0
            val o = k * hop
            for (i in 0 until hop) {
                val v = x[o + i]
                s += v * v
            }
            e[k] = ln(1.0 + 100.0 * s / hop).toFloat()
        }
        val on = FloatArray(nf)
        var mean = 0f
        for (k in 1 until nf) {
            on[k] = max(0f, e[k] - e[k - 1])
            mean += on[k]
        }
        mean /= nf
        if (mean < 1e-5f) return null
        for (k in 0 until nf) on[k] -= mean
        fun r(lag: Int): Double {
            if (lag >= nf) return 0.0
            var s = 0.0
            for (k in 0 until nf) s += on[k] * on[(k + lag) % nf]
            return s
        }
        var best = -1
        var bs = Double.NEGATIVE_INFINITY
        for (lag in 30..100) { // 200 … 60 BPM
            val s = r(lag) + 0.5 * r(2 * lag)
            if (s > bs) {
                bs = s
                best = lag
            }
        }
        if (best < 0) return null
        var bpm = 6000f / best
        while (bpm < 80f) bpm *= 2f
        while (bpm >= 170f) bpm /= 2f
        return bpm
    }

    /** Fehler zwischen zwei Tempi, Oktaven (halb/doppelt) gelten als gleichwertig. */
    private fun octErr(a: Float, b: Float): Double {
        var best = Double.MAX_VALUE
        for (k in floatArrayOf(0.5f, 1f, 2f)) best = min(best, abs(ln((a / (b * k)).toDouble())))
        return best
    }

    /** Tempo und Länge eines Clips bestimmen. */
    fun analyze(x: FloatArray, sr: Int, name: String?): Info {
        val dur = x.size.toFloat() / sr
        val nameBpm = bpmFromName(name)
        if (nameBpm != null) {
            val raw = dur * nameBpm / 240f
            var bars = (raw * 4f).roundToInt() / 4f
            for (c in POW2 + OTHER) if (abs(raw / c - 1f) < 0.03f) bars = c
            return Info(nameBpm, max(0.25f, bars), "Dateiname")
        }
        val clip = try { Dj.analyzeClip(x, sr) } catch (e: Throwable) { null }
        val auto = clip?.first ?: analyzeBpm(x, sr)
        // Loop-Länge: ganze Takte ergeben ein plausibles Tempo
        var best: Pair<Float, Float>? = null
        var bestErr = Double.MAX_VALUE
        for ((set, lo, hi, pen) in listOf(Quad(POW2, 70f, 185f, 0.0), Quad(OTHER, 60f, 200f, 0.06))) {
            for (bars in set) {
                val b = 240f * bars / dur
                if (b < lo || b > hi) continue
                val err = (if (auto != null) octErr(b, auto) else 0.0) + pen + 0.15 * abs(ln(b / 120.0))
                if (err < bestErr) {
                    bestErr = err
                    best = b to bars
                }
            }
        }
        val fit = if (dur > 45f) null else best
        // sauberer Loop: Länge ergibt ganze Takte und passt (±1,5 %) zum gehörten Tempo, Schlag 1 am Anfang
        val first = clip?.second ?: 0
        val startsOnBeat = first < sr * 0.03 || (clip != null && first > x.size - sr * 0.03)
        if (fit != null && (auto == null || octErr(fit.first, auto) < 0.015) && startsOnBeat) {
            return Info(fit.first, fit.second, if (auto != null) "Länge + Rhythmus" else "Länge")
        }
        if (auto == null) {
            if (fit != null) return Info(fit.first, fit.second, "Länge")
            if (dur > 45f) return Info(120f, max(1f, kotlin.math.floor(dur * 120f / 240f)), "ganzer Song (Tempo unbekannt)")
            return Info(120f, max(1f, (dur * 120f / 240f).roundToInt().toFloat()), "Standard")
        }
        // Ausschnitt aus einem Song (z. B. MP3): exaktes Tempo, ab dem ersten Schlag ganze Takte schneiden
        var bpm = auto
        // Tempo auf die Oktave des Längen-Treffers bringen (falls vorhanden)
        if (fit != null) for (k in floatArrayOf(0.5f, 2f)) if (abs(ln((bpm * k / fit.first).toDouble())) < abs(ln((bpm / fit.first).toDouble()))) bpm *= k
        val usable = (x.size - first).toFloat() / sr
        var bars = kotlin.math.floor(usable * bpm / 240f)
        if (bars < 1f) bars = max(0.25f, kotlin.math.floor(usable * bpm / 60f) / 4f)
        // kurzer Ausschnitt: längste Zweierpotenz an Takten · ganzer Song (> 45 s): alle vollen Takte
        if (dur <= 45f) {
            var pb = 1f
            while (pb * 2 <= bars && pb < 32f) pb *= 2
            if (bars >= 1f) bars = pb
        } else bars = bars.coerceIn(1f, 1024f)
        return Info(bpm, bars, "Schlag-Analyse ab ${String.format(java.util.Locale.GERMANY, "%.2f", first.toFloat() / sr)} s", first, true)
    }

    private data class Quad(val set: FloatArray, val lo: Float, val hi: Float, val pen: Double)

    /** Länge exakt anpassen (abschneiden/auffüllen) – für winzige Abweichungen. */
    fun fitLen(x: FloatArray, len: Int): FloatArray = x.copyOf(max(1, len))

    /**
     * Time-Stretch ohne Tonhöhenänderung (WSOLA), zirkulär: das Ergebnis ist wieder ein nahtloser Loop
     * der Länge [outLen].
     */
    fun stretch(x: FloatArray, outLen: Int, sr: Int): FloatArray {
        val n = x.size
        if (outLen <= 1 || n < 2) return FloatArray(max(1, outLen))
        if (abs(n.toDouble() / outLen - 1.0) < 0.002) return fitLen(x, outLen)
        val winNom = (sr * 0.04).toInt().coerceAtLeast(64)
        if (n < winNom * 3 || outLen < winNom * 3) return resample(x, outLen)
        // Rahmen gleichmäßig über die Ausgabe verteilen, damit der Loop sauber schließt
        val frames = max(4, (outLen / (winNom / 2.0)).roundToInt())
        val hopOut = outLen.toDouble() / frames
        val half = hopOut.toInt()
        val win = 2 * half
        val ratio = n.toDouble() / outLen
        val tol = (sr * 0.010).toInt()
        val xx = FloatArray(2 * n + win + 2 * tol)
        for (i in xx.indices) xx[i] = x[i % n]
        val wnd = FloatArray(win) { (0.5 - 0.5 * cos(2.0 * PI * it / win)).toFloat() }
        val out = FloatArray(outLen)
        val norm = FloatArray(outLen)
        var prevIn = 0
        for (k in 0 until frames) {
            val outPos = (k * hopOut).roundToInt()
            val nominal = Math.floorMod((outPos * ratio).roundToInt(), n)
            var best = nominal
            if (k > 0) {
                val natural = Math.floorMod(prevIn + (hopOut * 1.0).roundToInt(), n)
                var bs = Float.NEGATIVE_INFINITY
                var d = -tol
                while (d <= tol) {
                    val c = Math.floorMod(nominal + d, n)
                    var s = 0f
                    var j = 0
                    while (j < half) {
                        s += xx[c + j] * xx[natural + j]
                        j += 4
                    }
                    if (s > bs) {
                        bs = s
                        best = c
                    }
                    d += 2
                }
            }
            for (j in 0 until win) {
                val o = (outPos + j) % outLen
                out[o] += xx[best + j] * wnd[j]
                norm[o] += wnd[j]
            }
            prevIn = best
        }
        for (i in 0 until outLen) if (norm[i] > 1e-3f) out[i] /= norm[i]
        return out
    }

    // ---------------------------------------------------------------- BEATS-Warp (rhythmisch exakt)

    /**
     * Rhythmus-treues Dehnen (wie Ableton „Beats“): der Loop wird in 1/16-Stücke zerlegt (Grenzen auf
     * den nächsten Anschlag gerastet), jedes Stück einzeln gedehnt und genau auf seinen 1/16-Platz im
     * neuen Tempo gelegt. So landet jeder Schlag exakt im Raster – kein Holpern, kein Verschmieren.
     * [pre] = Audio direkt VOR x[0] im Original (bei Song-Ausschnitten): das Loop-Ende blendet hinein,
     * damit der Übergang Ende → Anfang ohne Knacks ist. Ohne [pre] gilt x als nahtloser Loop.
     */
    /**
     * Wie [stretchBeats], aber für ganze Songs speichersparend: in Stücken zu 8 Takten gedehnt und direkt
     * in das Ergebnis geschrieben. Passt das Tempo schon, wird gar nicht kopiert.
     */
    fun stretchBeatsLong(x: FloatArray, outLen: Int, sr: Int, bars: Float, pre: FloatArray? = null): FloatArray =
        stretchBeatsLong(ArraySource(x), outLen, sr, bars, pre)

    /**
     * Wie oben, aber die Quelle wird nur stückweise gelesen (8 Takte) – sie kann ein Ausschnitt sein oder
     * auf dem Handy-Speicher liegen. So braucht ein Song beim Dehnen nur den Platz für das Ergebnis.
     */
    /** Abbruch eines veralteten Dehn-Auftrags (neuer Auftrag läuft schon). */
    class Cancelled : RuntimeException()

    fun stretchBeatsLong(x: PcmSource, outLen: Int, sr: Int, bars: Float, pre: FloatArray? = null, cancel: (() -> Boolean)? = null): FloatArray {
        val n = x.size
        fun whole(): FloatArray = if (x is ArraySource && x.whole) x.a else x.copy(0, n)
        if (n < sr * 60 || bars < 16f || outLen <= 1) return stretchBeats(whole(), outLen, sr, bars, pre)
        if (abs(n.toDouble() / outLen - 1.0) < 0.0015) return seamEnd(if (n == outLen && x is ArraySource && x.whole) x.a else x.copy(0, outLen), pre, sr)
        val out = FloatArray(outLen)
        val chunk = 8f
        var b0 = 0f
        val ramp = max(16, sr / 1000)
        while (b0 < bars - 1e-3f) {
            if (cancel?.invoke() == true) throw Cancelled()
            val b1 = min(bars, b0 + chunk)
            val s0 = (b0 / bars * n).toInt()
            val s1 = min(n, (b1 / bars * n).toInt())
            val o0 = (b0.toDouble() / bars * outLen).toInt()
            val o1 = min(outLen, (b1.toDouble() / bars * outLen).toInt())
            if (s1 - s0 > 1 && o1 - o0 > 1) {
                val seg = x.copy(s0, s1)
                val segPre = if (s0 > 0) x.copy(max(0, s0 - sr / 20), s0) else pre
                val r = stretchBeats(seg, o1 - o0, sr, b1 - b0, segPre, seam = false)
                val len = min(r.size, o1 - o0)
                for (j in 0 until len) {
                    var g = 1f
                    if (o0 > 0 && j < ramp) g = (j + 1f) / (ramp + 1f)
                    if (o1 < outLen && j >= len - ramp) g *= (len - j).toFloat() / (ramp + 1f)
                    out[o0 + j] = r[j] * g
                }
            }
            b0 = b1
            LoadProgress.report(b0 / bars)
        }
        return seamEnd(out, pre, sr)
    }

    fun stretchBeats(x: FloatArray, outLen: Int, sr: Int, bars: Float, pre: FloatArray? = null, seam: Boolean = true): FloatArray {
        val n = x.size
        if (outLen <= 1 || n < 2) return FloatArray(max(1, outLen))
        if (abs(n.toDouble() / outLen - 1.0) < 0.0015) return fitLen(x, outLen).let { if (seam) seamEnd(it, pre, sr) else it }
        var slices = max(1, (bars * 16f).roundToInt()).coerceAtMost(4096)
        // Stücke nicht kürzer als ~45 ms (dann 1/8, 1/4 …)
        while (slices > 1 && n / slices < sr * 0.045) slices /= 2
        val hit = BooleanArray(slices + 1)
        val bounds = sliceBounds(x, sr, slices, hit)
        // Zwei Bänder: Höhen werden an JEDEM Anschlag geschnitten (Hi-Hats, Snare, Klick der Kick sitzen
        // exakt), der Bass (< ~180 Hz) nur dort, wo im Bass selbst etwas neu einsetzt (Kick, Basston).
        // So läuft der Bauch der Kick und der Bass ungestückelt durch – nichts fehlt, nichts wummert.
        val low = lowBand(x, sr)
        val high = FloatArray(n) { x[it] - low[it] }
        val cutsHi = ArrayList<Int>()
        val cutsLo = ArrayList<Int>()
        val win = (sr * 0.03).toInt()
        fun lowEnergy(from: Int, len: Int): Double { var s2 = 1e-12; for (i in 0 until len) { val v = low[Math.floorMod(from + i, n)]; s2 += v * v }; return s2 / len }
        for (k in 0..slices) {
            if (k == slices) { cutsHi.add(k); cutsLo.add(k); continue }
            if (k == 0 || hit[k]) cutsHi.add(k)
            if (k == 0 || (hit[k] && lowEnergy(bounds[k], win) > 2.5 * lowEnergy(bounds[k] - win, win))) cutsLo.add(k)
        }
        val hit0Lo = hit[0] && lowEnergy(0, win) > 2.5 * lowEnergy(-win, win)
        val out = FloatArray(outLen)
        assemble(high, bounds, cutsHi, slices, outLen, sr, pre != null, hit[0], out)
        assemble(low, bounds, cutsLo, slices, outLen, sr, pre != null, hit0Lo, out)
        return if (seam) seamEnd(out, pre, sr) else out
    }

    /** Stücke zwischen den Schnitten dehnen und genau auf ihre Rasterplätze legen (addiert in [out]). */
    private fun assemble(x: FloatArray, bounds: IntArray, cuts: List<Int>, slices: Int, outLen: Int, sr: Int, hasPre: Boolean, hit0: Boolean, out: FloatArray) {
        val xf = max(16, (sr * 0.002).toInt())
        for (ci in 0 until cuts.size - 1) {
            val k = cuts[ci]
            val k2 = cuts[ci + 1]
            val a = bounds[k]
            val ls = bounds[k2] - a
            val o0 = (k.toLong() * outLen / slices).toInt()
            val lo = (k2.toLong() * outLen / slices).toInt() - o0
            if (lo <= 0) continue
            val last = k2 == slices
            // An einem Anschlag: Stück endet mit 1 ms Ausblende, das nächste setzt (fast) hart ein –
            // der Anschlag verdeckt den Schnitt und sitzt exakt. Der Loop-Übergang ohne Vorlauf
            // (nahtloser Loop) und ein Anfang ohne Anschlag werden weich überblendet.
            val softStart = k == 0 && !hasPre && !hit0
            val tail = if (last && !hasPre) xf else 0
            val seg = stretchSeg(x, a, max(1, ls), lo, tail, sr)
            val fo = max(8, sr / 1000)
            val fi = if (softStart) xf else max(4, sr / 4000)
            for (j in seg.indices) {
                var g = 1f
                if (j < fi && !(k == 0 && hasPre)) g = (j + 1).toFloat() / (fi + 1)
                if (tail == 0 && !last && j >= lo - fo) g *= (lo - j).toFloat() / (fo + 1)
                if (j >= lo) g *= 1f - (j - lo + 1).toFloat() / (tail + 1)
                out[(o0 + j) % outLen] += seg[j] * g
            }
        }
    }

    /** Bass-Anteil (< ~180 Hz) ohne Phasenverschiebung (vor- und rückwärts gefiltert, zirkulär wie ein Loop). */
    private fun lowBand(x: FloatArray, sr: Int): FloatArray {
        val n = x.size
        val pad = min(n, (sr * 0.3).toInt())
        val m = n + 2 * pad
        val y = FloatArray(m) { x[Math.floorMod(it - pad, n)] }
        val a = (1.0 - kotlin.math.exp(-2.0 * PI * 180.0 / sr)).toFloat()
        repeat(2) {
            var s1 = 0f
            for (i in 0 until m) { s1 += (y[i] - s1) * a; y[i] = s1 }
            s1 = 0f
            for (i in m - 1 downTo 0) { s1 += (y[i] - s1) * a; y[i] = s1 }
        }
        return FloatArray(n) { y[it + pad] }
    }

    /** Ende in das Audio vor dem Anfang blenden (gleiche Leistung) – nahtloser Übergang Ende → Anfang. */
    private fun seamEnd(out: FloatArray, pre: FloatArray?, sr: Int): FloatArray {
        if (pre == null || pre.isEmpty()) return out
        val xf = min(min(pre.size, out.size / 8), (sr * 0.012).toInt())
        for (i in 0 until xf) {
            val t = (i + 1).toFloat() / (xf + 1)
            val k = out.size - xf + i
            out[k] = out[k] * cos(t * PI.toFloat() / 2f) + pre[pre.size - xf + i] * kotlin.math.sin(t * PI.toFloat() / 2f)
        }
        return out
    }

    /** 1/16-Grenzen, jeweils auf einen Anschlag in der Nähe (±12 ms) gerastet. */
    fun sliceBounds(x: FloatArray, sr: Int, slices: Int, hit: BooleanArray? = null): IntArray {
        val n = x.size
        val b = IntArray(slices + 1)
        b[slices] = n
        if (hit != null) {
            // Anschlag ganz am Anfang? (Loop: davor liegt das Ende)
            val q = max(1, sr / 1000)
            fun en(from: Int, len: Int): Double { var s = 1e-12; for (i in 0 until len) { val k = Math.floorMod(from + i, n); val d = x[k] - x[Math.floorMod(k - 1, n)]; s += (d * d + 0.25f * x[k] * x[k]).toDouble() }; return s / len }
            var pm = 0.0
            for (t in 3..12) pm = max(pm, en(-t * q, q))
            hit[0] = ln(en(0, 2 * q) / pm) > 1.4
        }
        if (slices == 1) return b
        val hop = max(1, sr / 1000)
        val nf = n / hop
        // Anschlagsstärke: Energie-Anstieg des Hochpass-Signals (1 ms Raster)
        val e = FloatArray(nf)
        var prev = 0f
        for (f in 0 until nf) {
            var s = 0.0
            val o = f * hop
            for (i in 0 until hop) {
                val v = x[o + i]
                val h = v - prev
                prev = v
                s += (h * h + 0.25f * v * v).toDouble()
            }
            e[f] = ln(1e-9 + s / hop).toFloat()
        }
        // Anstieg gegenüber dem lautesten Wert der letzten ~12 ms – ausklingendes Rauschen (Hi-Hat-Fahne,
        // Hall) zählt so nicht als Anschlag, nur ein echter neuer Einsatz
        val e3 = FloatArray(nf)
        for (f in 0 until nf) e3[f] = max(e[f], if (f + 1 < nf) e[f + 1] else e[f])
        val flux = FloatArray(nf)
        for (f in 12 until nf) {
            var pm = e3[f - 4]
            for (q in f - 12 until f - 3) pm = max(pm, e3[q])
            flux[f] = max(0f, e3[f] - pm)
        }
        val sorted = flux.copyOf().also { it.sort() }
        val med = if (sorted.isEmpty()) 0f else sorted[sorted.size / 2]
        val p90 = if (sorted.isEmpty()) 0f else sorted[(sorted.size * 9) / 10]
        // echter Anschlag: Energie springt um mind. ~6 dB (ln 4 ≈ 1,4) und hebt sich deutlich ab
        val thr = max(max(med * 4f, p90), 1.4f)
        val win = (0.012 * sr / hop).toInt()
        val minGap = (sr * 0.02).toInt()
        for (k in 1 until slices) {
            val g = (k.toLong() * n / slices).toInt()
            var pos = g
            val c = g / hop
            var bi = -1
            var bv = thr
            var bs = 0f
            for (f in max(3, c - win)..min(nf - 1, c + win)) {
                if (flux[f] <= thr) continue
                // stärker = besser, näher am Raster = besser
                val sc = flux[f] * (1f - 0.5f * abs(f - c).toFloat() / (win + 1))
                if (sc > bs) { bs = sc; bv = flux[f]; bi = f }
            }
            if (bi >= 0) {
                // samplegenau: erster deutlicher Sprung im Bereich des Anschlags, Grenze 0,3 ms davor
                val lo = max(1, (bi - 3) * hop)
                val hi = min(n - 1, (bi + 1) * hop)
                var mx = 0f
                for (i in lo until hi) mx = max(mx, abs(x[i] - x[i - 1]))
                var on = lo
                for (i in lo until hi) if (abs(x[i] - x[i - 1]) > 0.3f * mx) { on = i; break }
                pos = max(0, onsetStart(x, on, sr) - (sr * 0.0003).toInt())
                hit?.set(k, true)
            }
            pos = pos.coerceIn(b[k - 1] + minGap, n - minGap * (slices - k))
            b[k] = pos
        }
        return b
    }

    /**
     * Wo beginnt der Anschlag wirklich? Vom gefundenen Sprung [near] aus rückwärts, solange die
     * Hüllkurve (alle Frequenzen, auch der Bass einer Kick) noch fällt – bis zum Tal davor bzw. bis
     * sie unter ~12 % des Anschlags liegt. So wird keine Kick vorne angeschnitten.
     */
    fun onsetStart(x: FloatArray, near: Int, sr: Int): Int {
        val n = x.size
        val q = max(4, sr / 4000)
        val back = (sr * 0.015).toInt() / q
        val fwd = (sr * 0.012).toInt() / q
        val c0 = near / q
        fun env(b: Int): Float {
            var m = 0f
            val s0 = b * q
            for (i in s0 until min(n, s0 + q)) if (i >= 0) m = max(m, abs(x[i]))
            return m
        }
        var peak = 0f
        for (b in c0 until c0 + fwd) peak = max(peak, env(b))
        if (peak <= 0f) return near
        var b = c0
        while (b > 0 && b > c0 - back) {
            val e = env(b - 1)
            if (e < 0.12f * peak) break
            if (e > env(b) * 1.05f && env(b) < 0.5f * peak) break // Tal erreicht – davor klingt etwas anderes
            b--
        }
        return max(0, min(near, b * q))
    }

    /**
     * Ein Stück [a, a+ls) auf [lo] Samples dehnen (WSOLA, erster Rahmen exakt am Anfang), plus [extra]
     * Samples Nachlauf zum Überblenden. Liest zirkulär (Loop).
     */
    private fun stretchSeg(x: FloatArray, a: Int, ls: Int, lo: Int, extra: Int, sr: Int): FloatArray {
        val n = x.size
        val total = lo + extra
        val out = FloatArray(total)
        fun at(i: Int) = x[Math.floorMod(i, n)]
        if (abs(ls.toDouble() / lo - 1.0) < 0.002) {
            for (j in 0 until total) out[j] = at(a + j)
            return out
        }
        // Anschlag (erste ~20 ms) unverändert übernehmen – sonst verdoppelt das Dehnen ihn (Flam)
        val att = min((sr * 0.020).toInt(), min(ls, lo) / 3)
        for (j in 0 until min(att, total)) out[j] = at(a + j)
        val a2 = a + att
        val ls2 = max(1, ls - att)
        val lo2 = max(1, lo - att)
        val total2 = total - att
        if (total2 <= 0) return out
        val r = ls2.toDouble() / lo2
        val w = (min(sr * 0.040, lo2 / 2.0).toInt() / 2 * 2).coerceAtLeast(32)
        val h = w / 2
        // Suchweite bis 12 ms: auch tiefe Bässe (40–50 Hz) finden eine passende Stelle (keine Auslöschung)
        val tol = min((sr * 0.012).toInt(), (h * 0.8).toInt())
        val wnd = FloatArray(w) { (0.5 - 0.5 * cos(2.0 * PI * it / w)).toFloat() }
        val acc = FloatArray(total2 + w)
        val norm = FloatArray(total2 + w)
        var prevIn = a2
        var f = 0
        while (f * h < total2) {
            val op = f * h
            val nominal = a2 + (op * r).roundToInt()
            var best = nominal
            if (f > 0) {
                val natural = prevIn + h
                fun score(c: Int): Double {
                    var sum = 0.0
                    var e1 = 1e-9
                    var j = 0
                    while (j < h) {
                        val u = at(c + j)
                        sum += u * at(natural + j)
                        e1 += u * u
                        j += 4
                    }
                    return sum / kotlin.math.sqrt(e1)
                }
                // grob (Schritt 2), dann fein um den besten Wert
                var bs = Double.NEGATIVE_INFINITY
                var d = -tol
                while (d <= tol) {
                    val sc = score(nominal + d)
                    if (sc > bs) { bs = sc; best = nominal + d }
                    d += 2
                }
                val c0 = best
                for (c in intArrayOf(c0 - 1, c0 + 1)) { val sc = score(c); if (sc > bs) { bs = sc; best = c } }
            }
            for (j in 0 until w) {
                val o = op + j
                if (o >= acc.size) break
                acc[o] += at(best + j) * wnd[j]
                norm[o] += wnd[j]
            }
            prevIn = best
            f++
        }
        for (j in 0 until total2) {
            out[att + j] = if (norm[j] > 0.05f) acc[j] / norm[j] else at(a2 + (j * r).roundToInt())
        }
        // Übergang Anschlag → gedehnter Teil: erste Hälfte des Rahmens ist exakt
        val keep = min(h, lo2)
        for (j in 0 until keep) {
            val t = j.toFloat() / keep
            out[att + j] = at(a2 + j) * (1f - t) + out[att + j] * t
        }
        return out
    }

    /** Umrechnen auf eine neue Länge (ändert Tonhöhe und Tempo, wie ein Plattenspieler). */
    fun resampleLen(x: FloatArray, len: Int): FloatArray = resample(x, max(1, len))

    /** Einfaches Umrechnen (ändert die Tonhöhe) – nur für sehr kurze Schnipsel. */
    private fun resample(x: FloatArray, len: Int): FloatArray {
        val out = FloatArray(len)
        val step = x.size.toDouble() / len
        for (i in 0 until len) {
            val p = i * step
            val i0 = p.toInt().coerceIn(0, x.size - 1)
            val i1 = min(i0 + 1, x.size - 1)
            out[i] = x[i0] + (x[i1] - x[i0]) * (p - i0).toFloat()
        }
        return out
    }
}
