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
        val fit = best
        // sauberer Loop: Länge ergibt ganze Takte und passt (±1,5 %) zum gehörten Tempo, Schlag 1 am Anfang
        val first = clip?.second ?: 0
        val startsOnBeat = first < sr * 0.03 || (clip != null && first > x.size - sr * 0.03)
        if (fit != null && (auto == null || octErr(fit.first, auto) < 0.015) && startsOnBeat) {
            return Info(fit.first, fit.second, if (auto != null) "Länge + Rhythmus" else "Länge")
        }
        if (auto == null) {
            if (fit != null) return Info(fit.first, fit.second, "Länge")
            return Info(120f, max(1f, (dur * 120f / 240f).roundToInt().toFloat()), "Standard")
        }
        // Ausschnitt aus einem Song (z. B. MP3): exaktes Tempo, ab dem ersten Schlag ganze Takte schneiden
        var bpm = auto
        // Tempo auf die Oktave des Längen-Treffers bringen (falls vorhanden)
        if (fit != null) for (k in floatArrayOf(0.5f, 2f)) if (abs(ln((bpm * k / fit.first).toDouble())) < abs(ln((bpm / fit.first).toDouble()))) bpm *= k
        val usable = (x.size - first).toFloat() / sr
        var bars = kotlin.math.floor(usable * bpm / 240f)
        if (bars < 1f) bars = max(0.25f, kotlin.math.floor(usable * bpm / 60f) / 4f)
        // längste sinnvolle Loop-Länge: Zweierpotenz an Takten
        var pb = 1f
        while (pb * 2 <= bars && pb < 32f) pb *= 2
        if (bars >= 1f) bars = pb
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
