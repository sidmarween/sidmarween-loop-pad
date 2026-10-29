package de.marween.looppad

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * BEAT MUTATION – der Beat entwickelt neue rhythmische Muster.
 *
 * Erkennt je 1/16 im vorigen Takt Kick (Bass-Anschlag), Snare/Clap (Mitten) und Hi-Hats (Höhen) und baut daraus
 * für jeden Takt einen „Mutationsplan“: einzelne Schläge werden verschoben, weggelassen, verdoppelt (Ratchets),
 * umgedreht, halbiert oder getauscht. Die Klänge stammen aus dem Takt davor (gleiche Stelle im Raster),
 * deshalb bleibt alles im Takt. Über [RESET] Takte wächst die Mutation an – auf der Eins danach läuft wieder
 * exakt das Original.
 *
 * Regler: X = MUTATION (wie viel), Y = KOMPLEXITÄT (welche Eingriffe), P1 = RESET (2/4/8/16 Takte), P2 = VARIANTE.
 */
class BeatMutation(private val sr: Int) {
    private val size = 1 shl 21                     // ~43 s bei 48 kHz – reicht für sehr langsame Takte
    private val mask = size - 1
    private val hist = FloatArray(size)

    // Analyse (Takt, der gerade läuft) und Referenz (Takt davor)
    private val curLow = FloatArray(16); private val curMid = FloatArray(16); private val curHigh = FloatArray(16); private val curAll = FloatArray(16)
    private val refLow = FloatArray(16); private val refMid = FloatArray(16); private val refHigh = FloatArray(16); private val refAll = FloatArray(16)
    private var refValid = false
    private var lpLow = 0f; private var lpMid1 = 0f; private var lpMid2 = 0f; private var lpHigh = 0f
    private var curBar = Long.MIN_VALUE

    // Plan des aktuellen Takts: Quelle (Schritt im Vortakt, −1 = trocken) und Art je Schritt
    private val src = IntArray(16) { -1 }
    private val mode = IntArray(16)
    private val gain = FloatArray(16) { 1f }
    private var wetMix = 0f                          // Überblendung trocken ↔ mutiert (gleitet an Schrittgrenzen)

    /** Für die Anzeige: Anzahl veränderter Schritte im aktuellen Takt und Takt im Zyklus. */
    @Volatile var shownMutations = 0
    @Volatile var shownBar = 0

    companion object {
        const val NORMAL = 0; const val REVERSE = 1; const val RATCHET2 = 2; const val RATCHET3 = 3; const val RATCHET4 = 4
        const val HALF = 5; const val DOUBLE = 6
        fun resetBars(p1: Float): Int = when { p1 < 0.25f -> 2; p1 < 0.5f -> 4; p1 < 0.75f -> 8; else -> 16 }
    }

    fun reset() {
        java.util.Arrays.fill(hist, 0f)
        refValid = false; curBar = Long.MIN_VALUE
        java.util.Arrays.fill(src, -1)
        wetMix = 0f
    }

    /** Nur mitschreiben und analysieren (Effekt nicht aktiv) – damit beim Antippen schon ein Takt bereitliegt. */
    fun feed(buf: FloatArray, n: Int, frame0: Long, bar: Long, ms: Long) {
        for (i in 0 until n) { val f = frame0 + i; hist[(f and mask.toLong()).toInt()] = buf[i]; analyze(f, buf[i], bar, ms) }
    }

    private val aLow = (1.0 - exp(-2.0 * Math.PI * 150.0 / sr)).toFloat()
    private val aMid1 = (1.0 - exp(-2.0 * Math.PI * 2500.0 / sr)).toFloat()
    private val aMid2 = (1.0 - exp(-2.0 * Math.PI * 250.0 / sr)).toFloat()
    private val aHigh = (1.0 - exp(-2.0 * Math.PI * 6000.0 / sr)).toFloat()

    /** Energie je Band in den ersten ~25 ms jedes 1/16 sammeln (Anschlag); am Taktende wird daraus die Referenz. */
    private fun analyze(f: Long, x: Float, bar: Long, ms: Long) {
        lpLow += (x - lpLow) * aLow
        lpMid1 += (x - lpMid1) * aMid1
        lpMid2 += (x - lpMid2) * aMid2
        lpHigh += (x - lpHigh) * aHigh
        val rel = f - ms
        val b = Math.floorDiv(rel, bar)
        if (b != curBar) {
            if (curBar != Long.MIN_VALUE && b == curBar + 1) {
                System.arraycopy(curLow, 0, refLow, 0, 16); System.arraycopy(curMid, 0, refMid, 0, 16)
                System.arraycopy(curHigh, 0, refHigh, 0, 16); System.arraycopy(curAll, 0, refAll, 0, 16)
                refValid = true
            } else refValid = false
            java.util.Arrays.fill(curLow, 0f); java.util.Arrays.fill(curMid, 0f); java.util.Arrays.fill(curHigh, 0f); java.util.Arrays.fill(curAll, 0f)
            curBar = b
        }
        val inBar = rel - b * bar
        val stepLen = bar / 16.0
        val k = (inBar / stepLen).toInt().coerceIn(0, 15)
        val o = inBar - k * stepLen
        if (o < min(stepLen * 0.5, sr * 0.025)) {
            val mid = lpMid1 - lpMid2
            val hi = x - lpHigh
            curLow[k] += lpLow * lpLow; curMid[k] += mid * mid; curHigh[k] += hi * hi; curAll[k] += x * x
        }
    }

    private class Rng(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        fun next(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        fun int(n: Int): Int = (next() * n).toInt().coerceIn(0, n - 1)
    }

    /** Mutationsplan für Takt [b] im Zyklus der Länge [cycle]. */
    private fun plan(barIdx: Long, mutation: Float, complexity: Float, cycle: Int, variant: Int) {
        java.util.Arrays.fill(src, -1); java.util.Arrays.fill(mode, NORMAL); java.util.Arrays.fill(gain, 1f)
        shownMutations = 0
        val b = Math.floorMod(barIdx, cycle.toLong()).toInt()
        shownBar = b
        if (!refValid || b == 0 || mutation < 0.02f) return       // erster Takt des Zyklus: exakt das Original
        // Klassen aus dem Vortakt
        fun norm(a: FloatArray): FloatArray { val m = a.maxOrNull() ?: 0f; return FloatArray(16) { if (m > 1e-9f) a[it] / m else 0f } }
        val lo = norm(refLow); val mi = norm(refMid); val hi = norm(refHigh); val all = norm(refAll)
        val kick = BooleanArray(16) { lo[it] > 0.45f }
        val snare = BooleanArray(16) { mi[it] > 0.5f && !kick[it] || (mi[it] > 0.7f && lo[it] < 0.8f) }
        val hat = BooleanArray(16) { hi[it] > 0.35f && !snare[it] }
        var rest = 0
        for (k in 0 until 16) if (all[k] < all[rest]) rest = k
        val rng = Rng(barIdx / cycle * 7919L + b * 104729L + variant * 31L + 17)
        // Stärke wächst über den Zyklus an
        val ramp = (b.toFloat() / max(1, cycle - 1)).pow(0.7f)
        val intensity = mutation * (0.35f + 0.65f * ramp)
        val count = (intensity * (2f + complexity * 7f)).roundToInt().coerceIn(1, 10)
        val c2 = complexity > 0.33f
        val c3 = complexity > 0.66f
        val used = BooleanArray(16)
        fun free(k: Int) = k in 0..15 && !used[k]
        var made = 0
        var tries = 0
        while (made < count && tries < 60) {
            tries++
            // Ziel bevorzugt auf Schlägen, bei hoher Komplexität auch auf leeren Schritten
            val k = rng.int(16)
            if (!free(k)) continue
            val r = rng.next()
            when {
                kick[k] && k != 0 -> {
                    if (!c2 || r < 0.5f) {
                        // Kick verschieben: hier Pause, 1–3 Schritte später die Kick
                        val to = k + 1 + rng.int(3)
                        if (free(to) && to < 16 && !kick[to]) { src[k] = rest; src[to] = k; used[k] = true; used[to] = true; made++ }
                    } else { src[k] = k; mode[k] = RATCHET2; used[k] = true; made++ }
                }
                snare[k] -> {
                    when {
                        c3 && r < 0.3f -> { src[k] = k; mode[k] = if (rng.next() < 0.5f) RATCHET3 else RATCHET4 }
                        c2 && r < 0.5f -> { src[k] = k; mode[k] = if (rng.next() < 0.6f) RATCHET2 else REVERSE }
                        r < 0.75f -> {
                            // Snare eine 16tel zu spät (Synkope), die Stelle selbst wird zur Pause
                            val to = k + 1
                            if (free(to) && to < 16) { src[k] = rest; src[to] = k; used[to] = true } else { src[k] = rest }
                        }
                        else -> src[k] = rest                                   // weglassen
                    }
                    used[k] = true; made++
                }
                hat[k] -> {
                    when {
                        c3 && r < 0.35f -> { src[k] = k; mode[k] = if (rng.next() < 0.5f) RATCHET4 else DOUBLE }
                        c2 && r < 0.6f -> { src[k] = k; mode[k] = RATCHET2 }
                        r < 0.8f -> src[k] = rest
                        else -> { src[k] = k; mode[k] = REVERSE }
                    }
                    used[k] = true; made++
                }
                else -> if (c2) {
                    // leerer Schritt: Ghost-Schlag aus einem Snare- oder Hat-Schritt
                    val from = (0 until 16).filter { snare[it] || hat[it] }
                    if (from.isNotEmpty()) {
                        src[k] = from[rng.int(from.size)]
                        gain[k] = 0.45f + 0.3f * rng.next()
                        if (c3 && rng.next() < 0.4f) mode[k] = HALF
                        used[k] = true; made++
                    }
                }
            }
            // ganz komplex: einzelne Schritte wild vertauschen (Glitch)
            if (c3 && rng.next() < intensity * 0.25f) {
                val a = rng.int(16)
                if (free(a) && a != 0) { src[a] = rng.int(16); mode[a] = if (rng.next() < 0.5f) HALF else NORMAL; used[a] = true; made++ }
            }
        }
        // letzter Takt des Zyklus: Wirbel auf die Rückkehr zum Original
        if (b == cycle - 1 && complexity > 0.45f) {
            val sn = (0 until 16).firstOrNull { snare[it] } ?: (0 until 16).firstOrNull { hat[it] }
            if (sn != null) {
                val from = if (complexity > 0.75f) 12 else 14
                for (k in from until 16) { src[k] = sn; mode[k] = if (k < 14) RATCHET2 else if (k == 14) RATCHET3 else RATCHET4; gain[k] = 0.6f + 0.1f * (k - from) }
            }
        }
        shownMutations = (0 until 16).count { src[it] >= 0 }
    }

    /** Verarbeitet einen Block (schreibt den mutierten Beat in [buf]); Ein-/Ausblenden und MIX macht der Aufrufer. */
    fun process(buf: FloatArray, n: Int, frame0: Long, bar: Long, ms: Long, mutation: Float, complexity: Float, p1: Float, p2: Float) {
        val cycle = resetBars(p1)
        val variant = (p2 * 7.99f).toInt()
        val stepLen = bar / 16.0
        val fadeN = max(16.0, sr * 0.0015)
        val wRate = 1f / (sr * 0.003f)
        for (i in 0 until n) {
            val f = frame0 + i
            val x = buf[i]
            hist[(f and mask.toLong()).toInt()] = x
            val rel = f - ms
            val bIdx = Math.floorDiv(rel, bar)
            if (bIdx != curBar) {
                analyze(f, x, bar, ms)                     // schließt den Vortakt ab
                plan(bIdx, mutation, complexity, cycle, variant)
            } else analyze(f, x, bar, ms)
            val inBar = (rel - bIdx * bar).toDouble()
            val k = (inBar / stepLen).toInt().coerceIn(0, 15)
            val o = inBar - k * stepLen
            val s = src[k]
            var wet = x
            val target: Float
            if (s >= 0) {
                val m = mode[k]
                val subN = when (m) { RATCHET2, DOUBLE -> 2; RATCHET3 -> 3; RATCHET4 -> 4; else -> 1 }
                val sub = stepLen / subN
                val os = if (subN > 1) o % sub else o
                val so = when (m) {
                    REVERSE -> stepLen - 1 - o
                    HALF -> o * 0.5
                    DOUBLE -> os * 2.0
                    else -> os
                }
                val pos = (bIdx - 1) * bar + ms + s * stepLen + so
                val p0 = floor(pos)
                val fr = (pos - p0).toFloat()
                val a = hist[(p0.toLong() and mask.toLong()).toInt()]
                val c = hist[((p0.toLong() + 1) and mask.toLong()).toInt()]
                // weiche Kanten an jeder Teilstück-Grenze (kein Knacksen)
                val segLen = if (subN > 1) sub else stepLen
                val e = min(1.0, min(os / fadeN, (segLen - os) / fadeN)).toFloat().coerceAtLeast(0f)
                wet = (a + (c - a) * fr) * gain[k] * e
                target = 1f
            } else target = 0f
            // trocken ↔ mutiert in 3 ms überblenden
            if (wetMix < target) wetMix = min(target, wetMix + wRate) else if (wetMix > target) wetMix = max(target, wetMix - wRate)
            buf[i] = x + (wet - x) * wetMix
        }
    }
}
