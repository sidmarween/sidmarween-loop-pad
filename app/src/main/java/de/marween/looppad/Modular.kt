package de.marween.looppad

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🔌 TOUCH MODULAR: 8 Module, frei verkabelt (Ausgang → Eingang). Jeder Eingang summiert seine Kabel;
 * gelesen wird der Wert des vorigen Samples – so sind auch Rückkopplungen erlaubt.
 */
object Modular {
    val NAMES = arrayOf("OSC 1", "OSC 2", "RAUSCHEN", "LFO", "FILTER", "DRIVE", "DELAY", "AUSGANG")
    val KNOB = arrayOf("SÄGE ↔ RECHTECK", "VERHÄLTNIS", "FARBE", "TEMPO", "CUTOFF", "MENGE", "ZEIT", "LAUTSTÄRKE")
    /** Mitte der Module (0..1) auf der Fläche. */
    val pos = arrayOf(
        floatArrayOf(0.2f, 0.18f), floatArrayOf(0.2f, 0.45f), floatArrayOf(0.2f, 0.72f), floatArrayOf(0.5f, 0.12f),
        floatArrayOf(0.5f, 0.45f), floatArrayOf(0.8f, 0.3f), floatArrayOf(0.8f, 0.6f), floatArrayOf(0.5f, 0.82f)
    )
    val knob = floatArrayOf(0f, 0.5f, 0.5f, 0.3f, 0.45f, 0.3f, 0.5f, 0.8f)
    /** Module mit Ausgang (AUSGANG hat keinen). */
    fun hasOut(m: Int) = m != 7
    class In(val module: Int, val name: String)
    val INPUTS = listOf(In(0, "FM"), In(0, "PITCH"), In(1, "PITCH"), In(4, "IN"), In(4, "CUT"), In(5, "IN"), In(5, "AMT"), In(6, "IN"), In(7, "IN"))
    /** Kabel: (Ausgangs-Modul, Eingangs-Index in [INPUTS]) */
    val cables = java.util.concurrent.CopyOnWriteArrayList<IntArray>()

    @Volatile var drone = false
    @Volatile var gate = false
    @Volatile var note = 45.0
    @Volatile var level = 0f

    fun reset() {
        cables.clear()
        cables.add(intArrayOf(0, 3)); cables.add(intArrayOf(4, 5)); cables.add(intArrayOf(5, 8)); cables.add(intArrayOf(3, 4))
    }
    init { reset() }

    /** Patch-Vorlagen: Name, Kabel (Ausgang, Eingang), Regler. */
    class Patch(val name: String, val cables: List<IntArray>, val knob: FloatArray)
    val PATCHES = listOf(
        Patch("Grund-Patch (Subtraktiv)", listOf(intArrayOf(0, 3), intArrayOf(4, 5), intArrayOf(5, 8), intArrayOf(3, 4)), floatArrayOf(0f, 0.5f, 0.5f, 0.3f, 0.45f, 0.3f, 0.5f, 0.8f)),
        Patch("FM-Glocke", listOf(intArrayOf(1, 0), intArrayOf(0, 7), intArrayOf(6, 8)), floatArrayOf(0f, 0.75f, 0.5f, 0.2f, 0.6f, 0.2f, 0.45f, 0.75f)),
        Patch("Acid-Linie", listOf(intArrayOf(0, 3), intArrayOf(3, 4), intArrayOf(4, 5), intArrayOf(5, 8)), floatArrayOf(0f, 0.5f, 0.5f, 0.55f, 0.25f, 0.6f, 0.4f, 0.8f)),
        Patch("Rausch-Sturm", listOf(intArrayOf(2, 3), intArrayOf(3, 4), intArrayOf(4, 7), intArrayOf(6, 8)), floatArrayOf(0f, 0.5f, 0.8f, 0.15f, 0.5f, 0.2f, 0.7f, 0.7f)),
        Patch("Dub-Delay-Stab", listOf(intArrayOf(0, 3), intArrayOf(1, 3), intArrayOf(4, 7), intArrayOf(6, 8), intArrayOf(3, 6)), floatArrayOf(0.6f, 0.4f, 0.5f, 0.05f, 0.35f, 0.4f, 0.55f, 0.75f)),
        Patch("Rückkopplungs-Monster", listOf(intArrayOf(0, 5), intArrayOf(5, 3), intArrayOf(4, 0), intArrayOf(4, 8), intArrayOf(3, 1)), floatArrayOf(1f, 0.3f, 0.5f, 0.6f, 0.55f, 0.8f, 0.5f, 0.6f))
    )
    @Volatile var patchIdx = -1
    fun applyPatch(i: Int) {
        val k = Math.floorMod(i, PATCHES.size); val p = PATCHES[k]
        patchIdx = k
        cables.clear(); for (c in p.cables) cables.add(c.copyOf())
        for (j in 0 until 8) knob[j] = p.knob[j]
    }

    fun connect(out: Int, inp: Int) {
        if (cables.any { it[0] == out && it[1] == inp }) return
        cables.add(intArrayOf(out, inp))
    }
    fun unplug(inp: Int) { cables.removeAll { it[1] == inp } }

    private val prev = FloatArray(8)
    private val cur = FloatArray(8)
    private val inSum = FloatArray(INPUTS.size)
    private var ph0 = 0.0; private var ph1 = 0.0; private var lfoPh = 0.0
    private var nzLp = 0f; private var rnd = 99
    private var lp = 0f; private var bp = 0f
    private var env = 0f
    private var dbuf = FloatArray(0); private var dw = 0
    @Volatile var flash = FloatArray(8)

    fun running(): Boolean = drone || gate || env > 1e-4f

    fun sample(sr: Int, beat: Double): Float {
        if (dbuf.isEmpty()) dbuf = FloatArray(sr * 2)
        java.util.Arrays.fill(inSum, 0f)
        for (c in cables) { val o = c[0]; val i = c[1]; if (o in 0..6 && i in inSum.indices) inSum[i] += prev[o] }
        // LFO
        lfoPh += (0.1 * 120.0.pow(knob[3].toDouble())) / sr; if (lfoPh >= 1.0) lfoPh -= 1.0
        cur[3] = sin(2 * PI * lfoPh).toFloat()
        // Rauschen
        rnd = rnd * 1664525 + 1013904223
        val n = ((rnd ushr 8) / 16777216f) * 2f - 1f
        nzLp += (0.02f + knob[2] * 0.98f) * (n - nzLp)
        cur[2] = nzLp
        val f = 440.0 * 2.0.pow((note - 69) / 12.0)
        // OSC 2
        val ratio = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)[(knob[1] * 5.99f).toInt()]
        val f1 = f * ratio * 2.0.pow(inSum[2] * 1.0)
        ph1 += f1 / sr; if (ph1 >= 1.0) ph1 -= 1.0
        cur[1] = (2 * ph1 - 1).toFloat()
        // OSC 1 (Säge ↔ Rechteck, FM, Pitch)
        val f0 = f * 2.0.pow(inSum[1] * 1.0)
        ph0 += f0 / sr; if (ph0 >= 1.0) ph0 -= 1.0
        val p = (ph0 + inSum[0] * 0.3).let { it - kotlin.math.floor(it) }
        val sawV = (2 * p - 1).toFloat()
        val sq = if (p < 0.5) 1f else -1f
        cur[0] = sawV + (sq - sawV) * knob[0]
        // FILTER
        val oct = knob[4] * 7.5f + inSum[4] * 2f
        val fc = min(sr / 6.0, 40.0 * 2.0.pow(oct.toDouble()))
        val g = (2 * sin(PI * fc / sr)).toFloat()
        lp += g * bp; val hp = inSum[3] - lp - 0.45f * bp; bp += g * hp
        cur[4] = lp
        // DRIVE
        val amt = (knob[5] + inSum[6] * 0.5f).coerceIn(0f, 1.5f)
        cur[5] = tanh(inSum[5] * (1f + amt * 8f)) * (1f / (1f + amt))
        // DELAY
        val dt = (beat * (0.25 + knob[6] * 1.25)).toInt().coerceIn(10, dbuf.size - 1)
        val rd = dbuf[(dw - dt + dbuf.size) % dbuf.size]
        dbuf[dw] = inSum[7] + rd * 0.55f
        dw = (dw + 1) % dbuf.size
        cur[6] = inSum[7] + rd * 0.7f
        // AUSGANG mit Hüllkurve
        val target = if (drone || gate) 1f else 0f
        env = if (target > env) min(1f, env + 1f / (0.005f * sr)) else env * exp(-1.0 / (0.3 * sr)).toFloat()
        val out = inSum[8] * env * knob[7]
        for (k in 0 until 8) { prev[k] = cur[k]; if (k < 7) flash[k] = flash[k] * 0.999f + kotlin.math.abs(cur[k]) * 0.001f }
        level = level * 0.999f + kotlin.math.abs(out) * 0.001f
        return out * 0.5f
    }
}
