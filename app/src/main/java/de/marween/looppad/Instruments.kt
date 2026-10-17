package de.marween.looppad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Einstellungen und Pattern der virtuellen Instrumente (bleiben über Audio-Neustarts erhalten). */
object InstState {
    const val STEPS = 16

    // ---------------------------------------------------------------- ACID (303-artig)
    val acidSemi = IntArray(STEPS)
    val acidGate = BooleanArray(STEPS)
    val acidAcc = BooleanArray(STEPS)
    val acidSlide = BooleanArray(STEPS)
    @Volatile var acidOn = true
    @Volatile var acidFx = true
    @Volatile var acidSquare = false
    @Volatile var acidOct = 0
    @Volatile var acidCut = 0.35f
    @Volatile var acidRes = 0.7f
    @Volatile var acidEnv = 0.6f
    @Volatile var acidDec = 0.35f
    @Volatile var acidVol = 0.75f
    /** Live-Filter-Modulation vom Touchpad (−1 … +1). */
    @Volatile var acidCutMod = 0f

    fun acidRandom() {
        val scale = intArrayOf(0, 0, 3, 5, 7, 10, 12, 12, 15, 0, 7)
        val r = java.util.Random()
        for (k in 0 until STEPS) {
            acidGate[k] = r.nextFloat() < 0.72f
            acidSemi[k] = scale[r.nextInt(scale.size)] + if (r.nextFloat() < 0.15f) -12 else 0
            acidAcc[k] = r.nextFloat() < 0.25f
            acidSlide[k] = r.nextFloat() < 0.22f
        }
        acidGate[0] = true
    }

    fun acidClear() {
        java.util.Arrays.fill(acidGate, false)
        java.util.Arrays.fill(acidAcc, false)
        java.util.Arrays.fill(acidSlide, false)
        java.util.Arrays.fill(acidSemi, 0)
    }

    fun acidHasNotes() = acidGate.any { it }

    // ---------------------------------------------------------------- DRUM (909-artig)
    const val DV = 11
    val drumNames = arrayOf("BD", "SD", "LT", "MT", "HT", "RS", "CP", "CH", "OH", "CR", "RD")
    val drumLong = arrayOf("Bass Drum", "Snare", "Low Tom", "Mid Tom", "High Tom", "Rimshot", "Clap", "Closed Hat", "Open Hat", "Crash", "Ride")
    /** 0 = aus, 1 = an, 2 = Accent. */
    val drumSteps = Array(DV) { IntArray(STEPS) }
    val drumTune = FloatArray(DV) { 0.5f }
    val drumDecay = FloatArray(DV) { 0.5f }
    val drumLevel = FloatArray(DV) { 0.8f }
    @Volatile var drumOn = true
    @Volatile var drumFx = false
    @Volatile var drumDrive = 0.15f
    @Volatile var drumVol = 0.8f
    @Volatile var drumSel = 0
    var drumPreset = -1

    val presetNames = arrayOf("House", "Techno", "Breakbeat", "Electro")

    fun drumApplyPreset(i: Int) {
        for (v in 0 until DV) java.util.Arrays.fill(drumSteps[v], 0)
        fun set(v: Int, vararg st: Int) { for (s in st) drumSteps[v][s] = 1 }
        fun acc(v: Int, vararg st: Int) { for (s in st) drumSteps[v][s] = 2 }
        when (i % presetNames.size) {
            0 -> { // House
                acc(0, 0, 4, 8, 12); set(6, 4, 12); set(8, 2, 6, 10, 14); set(7, 0, 4, 8, 12); set(5, 7, 15)
            }
            1 -> { // Techno
                acc(0, 0, 4, 8, 12); set(7, 2, 6, 10, 14); set(7, 0, 1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 15); set(8, 2, 10)
                set(6, 4, 12); set(10, 0, 4, 8, 12); set(5, 3, 11, 14)
            }
            2 -> { // Breakbeat
                acc(0, 0); set(0, 10); set(0, 3); acc(1, 4, 12); set(1, 7, 9, 15)
                set(7, 0, 2, 4, 6, 8, 10, 12, 14); set(8, 14)
            }
            else -> { // Electro
                acc(0, 0); set(0, 6, 10); acc(1, 4, 12); set(6, 4, 12); set(7, 0, 2, 4, 6, 8, 10, 12, 14)
                set(2, 13); set(3, 14); set(4, 15)
            }
        }
        drumPreset = i % presetNames.size
    }

    fun drumClear() {
        for (v in 0 until DV) java.util.Arrays.fill(drumSteps[v], 0)
    }

    fun drumHasNotes() = drumSteps.any { row -> row.any { it > 0 } }

    // ---------------------------------------------------------------- Projekte

    fun toJson(): JSONObject {
        val j = JSONObject()
        fun ia(a: IntArray) = JSONArray().apply { a.forEach { put(it) } }
        fun ba(a: BooleanArray) = JSONArray().apply { a.forEach { put(it) } }
        fun fa(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
        j.put("acidSemi", ia(acidSemi)); j.put("acidGate", ba(acidGate)); j.put("acidAcc", ba(acidAcc)); j.put("acidSlide", ba(acidSlide))
        j.put("acidOn", acidOn); j.put("acidFx", acidFx); j.put("acidSquare", acidSquare); j.put("acidOct", acidOct)
        j.put("acidCut", acidCut.toDouble()); j.put("acidRes", acidRes.toDouble()); j.put("acidEnv", acidEnv.toDouble())
        j.put("acidDec", acidDec.toDouble()); j.put("acidVol", acidVol.toDouble())
        val ds = JSONArray()
        for (v in 0 until DV) ds.put(ia(drumSteps[v]))
        j.put("drumSteps", ds)
        j.put("drumTune", fa(drumTune)); j.put("drumDecay", fa(drumDecay)); j.put("drumLevel", fa(drumLevel))
        j.put("drumOn", drumOn); j.put("drumFx", drumFx); j.put("drumDrive", drumDrive.toDouble()); j.put("drumVol", drumVol.toDouble())
        return j
    }

    fun fromJson(j: JSONObject?) {
        if (j == null) {
            acidClear(); drumClear(); return
        }
        fun ri(n: String, a: IntArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optInt(k) } }
        fun rb(n: String, a: BooleanArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optBoolean(k) } }
        fun rf(n: String, a: FloatArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optDouble(k).toFloat() } }
        ri("acidSemi", acidSemi); rb("acidGate", acidGate); rb("acidAcc", acidAcc); rb("acidSlide", acidSlide)
        acidOn = j.optBoolean("acidOn", true); acidFx = j.optBoolean("acidFx", true); acidSquare = j.optBoolean("acidSquare", false)
        acidOct = j.optInt("acidOct", 0)
        acidCut = j.optDouble("acidCut", 0.35).toFloat(); acidRes = j.optDouble("acidRes", 0.7).toFloat()
        acidEnv = j.optDouble("acidEnv", 0.6).toFloat(); acidDec = j.optDouble("acidDec", 0.35).toFloat(); acidVol = j.optDouble("acidVol", 0.75).toFloat()
        j.optJSONArray("drumSteps")?.let { ds -> for (v in 0 until min(DV, ds.length())) ds.optJSONArray(v)?.let { r -> for (k in 0 until min(STEPS, r.length())) drumSteps[v][k] = r.optInt(k) } }
        rf("drumTune", drumTune); rf("drumDecay", drumDecay); rf("drumLevel", drumLevel)
        drumOn = j.optBoolean("drumOn", true); drumFx = j.optBoolean("drumFx", false)
        drumDrive = j.optDouble("drumDrive", 0.15).toFloat(); drumVol = j.optDouble("drumVol", 0.8).toFloat()
    }
}

/** 303-artiger Bass-Synth: Sägezahn/Rechteck, resonantes 4-Pol-Filter, Hüllkurve, Accent und Slide. */
class AcidSynth(private val sr: Int) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val acc: Boolean, val slide: Boolean)

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private var phase = 0.0
    private var freq = 55.0
    private var target = 55.0
    private var gate = false
    private var amp = 0f
    private var fenv = 0f
    private var accNow = false
    private var y1 = 0f; private var y2 = 0f; private var y3 = 0f; private var y4 = 0f
    private var accEnv = 0f

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((36 + 12 * InstState.acidOct + semi - 69) / 12.0)

    /** frame < 0 = sofort. */
    fun queue(frame: Long, on: Boolean, semi: Int, acc: Boolean, slide: Boolean) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, acc, slide)) }
    }

    private fun apply(e: Ev) {
        if (!e.on) {
            gate = false
            return
        }
        target = hz(e.semi)
        if (e.slide && gate) {
            // Slide: Tonhöhe gleitet, Hüllkurven laufen weiter
        } else {
            freq = target
            fenv = 1f
            accNow = e.acc
            if (e.acc) accEnv = 1f
        }
        gate = true
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    fun render(out: FloatArray, n: Int, w: Long) {
        val evs: List<Ev>
        synchronized(lock) {
            if (queue.isEmpty()) evs = emptyList() else {
                evs = queue.filter { it.frame < w + n }.sortedBy { it.frame }
                queue.removeAll(evs.toSet())
            }
        }
        var ei = 0
        val st = InstState
        val decT = (0.04 + st.acidDec * 1.6) * sr
        val fDec = exp(-1.0 / decT).toFloat()
        val accDec = exp(-1.0 / (0.12 * sr)).toFloat()
        val glide = 1.0 - exp(-1.0 / (0.05 * sr))
        val res = st.acidRes * 3.95f
        val vol = st.acidVol * 0.9f
        val square = st.acidSquare
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) {
                apply(evs[ei]); ei++
            }
            if (!gate && amp < 1e-5f && fenv < 1e-4f) continue
            freq += (target - freq) * glide
            val dt = freq / sr
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            var osc = if (!square) 2.0 * phase - 1.0 - blep(phase, dt)
            else {
                var sq = if (phase < 0.5) 1.0 else -1.0
                sq += blep(phase, dt)
                sq -= blep((phase + 0.5) % 1.0, dt)
                sq
            }
            osc *= 0.9
            // Hüllkurven
            val ampTarget = if (gate) 1f else 0f
            amp += (ampTarget - amp) * (if (gate) 0.02f else 0.006f)
            fenv *= fDec
            accEnv *= accDec
            val accBoost = if (accNow) accEnv * st.acidEnv * 2.5f else 0f
            val oct = st.acidCut * 7f + (st.acidEnv * 5f) * fenv + accBoost * 2f + st.acidCutMod * 3f
            val fc = min(0.45f * sr, 70f * 2f.pow(oct))
            val g = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()
            val x = tanh(osc.toFloat() - res * y4)
            y1 += g * (x - y1)
            y2 += g * (y1 - y2)
            y3 += g * (y2 - y3)
            y4 += g * (y3 - y4)
            val a = amp * (if (accNow) 1f + 0.6f * accEnv else 1f)
            out[i] += tanh(y4 * (1f + st.acidRes * 1.5f) * 1.4f) * a * vol
        }
        // spätere Ereignisse zurücklegen
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}

/** 909-artiger Drumcomputer: alle Klänge synthetisiert. */
class DrumMachine(private val sr: Int) {
    private class Voice {
        var active = false
        var delay = 0
        var t = 0
        var acc = 1f
        var ph = DoubleArray(6)
        var p1 = 0.0
        var lp = 0f
        var hp = 0f
        var bp1 = 0f
        var bp2 = 0f
    }

    private val voices = Array(InstState.DV) { Voice() }
    private val lock = Any()
    private var seed = 0x1234567
    private val metal = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)

    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return (seed and 0xFFFF) / 32768f - 1f
    }

    fun trigger(v: Int, delay: Int, accent: Boolean) {
        if (v !in 0 until InstState.DV) return
        synchronized(lock) {
            val vc = voices[v]
            vc.active = true
            vc.delay = max(0, delay)
            vc.t = 0
            vc.acc = if (accent) 1.45f else 1f
            vc.p1 = 0.0
            for (k in vc.ph.indices) vc.ph[k] = 0.0
            // Closed Hat würgt Open Hat ab
            if (v == 7) voices[8].active = false
        }
    }

    private fun metallic(vc: Voice, tuneMul: Double): Float {
        var s = 0f
        for (k in 0 until 6) {
            vc.ph[k] += metal[k] * tuneMul / sr
            if (vc.ph[k] >= 1.0) vc.ph[k] -= 1.0
            s += if (vc.ph[k] < 0.5) 1f else -1f
        }
        return s / 6f
    }

    fun render(out: FloatArray, n: Int) {
        val st = InstState
        val drive = 1f + st.drumDrive * 6f
        val norm = 1f / tanh(drive)
        synchronized(lock) {
            for (v in 0 until InstState.DV) {
                val vc = voices[v]
                if (!vc.active) continue
                val tune = st.drumTune[v]
                val dec = st.drumDecay[v]
                val lvl = st.drumLevel[v] * st.drumVol * vc.acc
                for (i in 0 until n) {
                    if (vc.delay > 0) { vc.delay--; continue }
                    val t = vc.t.toFloat() / sr
                    var s: Float
                    var env: Float
                    when (v) {
                        0 -> { // Bass Drum
                            val base = 42.0 * 2.0.pow((tune - 0.5) * 1.2)
                            val f = base * (1.0 + 3.2 * exp(-t / 0.028))
                            vc.p1 += f / sr
                            env = exp(-t / (0.12f + dec * 0.9f))
                            s = sin(2 * PI * vc.p1).toFloat() + noise() * exp(-t / 0.0015f) * 0.35f
                            s = tanh(s * 1.6f)
                        }
                        1 -> { // Snare
                            val f = 175.0 * 2.0.pow((tune - 0.5))
                            vc.p1 += f * (1 + 0.4 * exp(-t / 0.02)) / sr
                            val tone = (sin(2 * PI * vc.p1) + 0.5 * sin(2 * PI * vc.p1 * 1.87)).toFloat() * exp(-t / 0.06f)
                            val nz = noise()
                            vc.hp += (nz - vc.hp) * 0.25f
                            env = 1f
                            s = tone * 0.7f + (nz - vc.hp) * exp(-t / (0.07f + dec * 0.25f)) * 0.9f
                        }
                        2, 3, 4 -> { // Toms
                            val baseF = when (v) { 2 -> 90.0; 3 -> 130.0; else -> 185.0 } * 2.0.pow(tune - 0.5)
                            vc.p1 += baseF * (1 + 0.6 * exp(-t / 0.05)) / sr
                            env = exp(-t / (0.12f + dec * 0.6f))
                            s = sin(2 * PI * vc.p1).toFloat() + noise() * exp(-t / 0.004f) * 0.15f
                        }
                        5 -> { // Rimshot
                            vc.p1 += 1700.0 * 2.0.pow(tune - 0.5) / sr
                            env = exp(-t / (0.01f + dec * 0.02f))
                            s = (sin(2 * PI * vc.p1) + 0.6 * sin(2 * PI * vc.p1 * 0.3)).toFloat()
                        }
                        6 -> { // Clap
                            val nz = noise()
                            vc.bp1 += (nz - vc.bp1) * 0.35f
                            vc.hp += (vc.bp1 - vc.hp) * 0.06f
                            val band = vc.bp1 - vc.hp
                            var e = 0f
                            for (d in 0..3) { val td = t - d * 0.011f; if (td >= 0f) e += exp(-td / 0.006f) }
                            if (t > 0.034f) e += exp(-(t - 0.034f) / (0.1f + dec * 0.4f)) * 0.8f
                            env = 1f
                            s = band * e * 2.2f
                        }
                        7, 8 -> { // Hi-Hats
                            val m = metallic(vc, 1.6 * 2.0.pow(tune - 0.5)) + noise() * 0.25f
                            vc.hp += (m - vc.hp) * 0.55f
                            env = if (v == 7) exp(-t / (0.02f + dec * 0.08f)) else exp(-t / (0.12f + dec * 0.7f))
                            s = (m - vc.hp) * 1.8f
                        }
                        9 -> { // Crash
                            val m = metallic(vc, 2.4 * 2.0.pow(tune - 0.5)) * 0.6f + noise() * 0.6f
                            vc.hp += (m - vc.hp) * 0.45f
                            env = exp(-t / (0.6f + dec * 2.2f))
                            s = (m - vc.hp) * 1.4f
                        }
                        else -> { // Ride
                            val m = metallic(vc, 3.3 * 2.0.pow(tune - 0.5)) * 0.8f + noise() * 0.15f
                            vc.hp += (m - vc.hp) * 0.5f
                            vc.p1 += 3100.0 / sr
                            env = exp(-t / (0.8f + dec * 2.2f))
                            s = (m - vc.hp) * 1.1f + sin(2 * PI * vc.p1).toFloat() * 0.25f * exp(-t / 0.4f)
                        }
                    }
                    out[i] += tanh(s * env * lvl * drive) * norm * 0.8f
                    vc.t++
                    if (vc.t > sr * 4 || (env < 1e-4f && vc.t > sr / 20)) {
                        vc.active = false
                        break
                    }
                }
            }
        }
    }
}
