package de.marween.looppad

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bester-Loop-Finder: sucht in einem Song (im Takt, Takt-Länge [bar] Samples ab Sample 0) die Stelle,
 * die sich als [bars]-Takt-Loop am saubersten wiederholt.
 *
 * Bewertung je Kandidat (Start auf einer Takt-Eins):
 *  - Wiederholung: der Abschnitt danach klingt fast gleich (Klangverlauf je 1/16: Lautheit, Bass, Höhen) →
 *    dann ist der Ausschnitt ein in sich geschlossenes Muster
 *  - Naht: das Ende passt zum Anfang (Klang kurz vor dem Start ≈ Klang am Ende) → kein Sprung beim Wiederholen
 *  - Fülle: nicht zu leise und mit Bass (Intro-Stille und Breaks werden seltener gewählt)
 */
object LoopFinder {
    class Cand(val startBar: Int, val score: Float, val rep: Float, val seam: Float, val fill: Float)

    fun find(x: FloatArray, sr: Int, bar: Int, bars: Int, top: Int = 5): List<Cand> {
        if (bar <= 0) return emptyList()
        val totalBars = x.size / bar
        if (totalBars < bars + 1) return emptyList()
        val step = bar / 16
        val n = x.size / step
        // Merkmale je 1/16: log-Lautheit, Bass-Anteil, Höhen-Anteil
        val loud = FloatArray(n)
        val low = FloatArray(n)
        val high = FloatArray(n)
        val aL = (1.0 - exp(-2 * Math.PI * 150.0 / sr)).toFloat()
        val aH = (1.0 - exp(-2 * Math.PI * 3000.0 / sr)).toFloat()
        var l = 0f
        var h = 0f
        for (i in 0 until n) {
            var se = 0.0; var sl = 0.0; var sh = 0.0
            val o = i * step
            for (j in o until min(x.size, o + step)) {
                val v = x[j]
                l += aL * (v - l)
                h += aH * (v - h)
                val hv = v - h
                se += v * v; sl += l * l; sh += hv * hv
            }
            loud[i] = ln(1e-9 + se / step).toFloat()
            low[i] = (sl / (se + 1e-12)).toFloat()
            high[i] = (sh / (se + 1e-12)).toFloat()
        }
        // auf typische Spannweite normieren
        val lmax = loud.maxOrNull() ?: 0f
        for (i in 0 until n) loud[i] = ((loud[i] - lmax + 12f) / 12f).coerceIn(0f, 1f)
        fun dist(a: Int, b: Int): Float = abs(loud[a] - loud[b]) + 0.8f * abs(low[a] - low[b]) + 0.8f * abs(high[a] - high[b])
        val len = bars * 16
        val out = ArrayList<Cand>()
        for (s in 0..(totalBars - bars)) {
            val a = s * 16
            if (a + len > n) break
            // Wiederholung: mit dem nächsten Abschnitt vergleichen (oder dem davor, am Song-Ende)
            val b = if (a + 2 * len <= n) a + len else a - len
            if (b < 0) continue
            var d = 0f
            for (k in 0 until len) d += dist(a + k, b + k)
            val rep = (1f - d / len / 0.6f).coerceIn(0f, 1f)
            // Naht: die letzten 2/16 gegen die 2/16 vor dem Start
            var sd = 0f
            var sc = 0
            for (k in 1..2) { val e = a + len - k; val p = a - k; if (p >= 0) { sd += dist(e, p); sc++ } }
            val seam = if (sc > 0) (1f - sd / sc / 0.6f).coerceIn(0f, 1f) else 0.7f
            // Fülle
            var fl = 0f; var lowSum = 0f
            for (k in 0 until len) { fl += loud[a + k]; lowSum += low[a + k] }
            val fill = (fl / len * 0.7f + min(1f, lowSum / len * 3f) * 0.3f).coerceIn(0f, 1f)
            // Phrasenraster: Songs beginnen auf der Eins – Loops, die auf Vielfachen ihrer Länge (bzw. 4 Takten) starten, klingen „richtig herum“
            val grid = (if (s % bars == 0) 0.04f else 0f) + (if (s % 4 == 0) 0.03f else 0f)
            val score = 0.45f * rep + 0.25f * seam + 0.3f * fill + grid
            out.add(Cand(s, score, rep, seam, fill))
        }
        // die besten, nicht überlappend
        val sorted = out.sortedByDescending { it.score }
        val pick = ArrayList<Cand>()
        for (c in sorted) {
            if (pick.size >= top) break
            if (pick.any { abs(it.startBar - c.startBar) < bars }) continue
            pick.add(c)
        }
        return pick
    }

    @Suppress("unused")
    private fun rms(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() } / max(1, a.size))
}
