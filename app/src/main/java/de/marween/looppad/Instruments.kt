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
    /** Schritte der Kompakt-Ansicht (1 Takt). */
    const val STEPS = 16
    /** Längstes Pattern: 4 Takte à 16 Schritte. */
    const val MAX = 64

    // ---------------------------------------------------------------- ACID (303-artig)
    val acidSemi = IntArray(MAX)
    val acidGate = BooleanArray(MAX)
    val acidAcc = BooleanArray(MAX)
    val acidSlide = BooleanArray(MAX)
    /** Tie: der Schritt hält den Ton des vorigen Schritts (längere Noten, kein neuer Anschlag). */
    val acidTie = BooleanArray(MAX)
    /** Lautstärke je Note (Kopf-Schritt), 0..1. */
    val acidVel = FloatArray(MAX) { 1f }
    /** Pattern-Länge in Schritten (16, 32, 48, 64). */
    @Volatile var acidLen = 16
    /** Glide-Zeit 0..1 (≈ 10 … 250 ms), Accent-Stärke, Verzerrung, Feinstimmung in Halbtönen. */
    @Volatile var acidGlide = 0.25f
    @Volatile var acidAccent = 0.6f
    @Volatile var acidDrive = 0.2f
    @Volatile var acidTune = 0
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

    /** Zufalls-Linie (Algorithmus nach „Acid Banger“, David Whiting, CC BY 4.0). */
    fun acidRandom() = InstPresets.randomAcid()

    fun acidClear() {
        java.util.Arrays.fill(acidGate, false)
        java.util.Arrays.fill(acidAcc, false)
        java.util.Arrays.fill(acidSlide, false)
        java.util.Arrays.fill(acidTie, false)
        java.util.Arrays.fill(acidSemi, 0)
    }

    fun acidHasNotes() = (0 until acidLen).any { acidGate[it] }

    // ---------------------------------------------------------------- DRUM (909-artig)
    const val DV = 11
    val drumNames = arrayOf("BD", "SD", "LT", "MT", "HT", "RS", "CP", "CH", "OH", "CR", "RD")
    val drumLong = arrayOf("Bass Drum", "Snare", "Low Tom", "Mid Tom", "High Tom", "Rimshot", "Clap", "Closed Hat", "Open Hat", "Crash", "Ride")
    /** 0 = aus, 1 = an, 2 = Accent. */
    val drumSteps = Array(DV) { IntArray(MAX) }
    /** Lautstärke je Schlag, 0..1. */
    val drumVel = Array(DV) { FloatArray(MAX) { 1f } }
    @Volatile var drumLen = 16
    /** Stumm geschaltete Instrumente. */
    val drumMute = BooleanArray(DV)
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
        for (v in 0 until DV) for (k in STEPS until MAX) drumSteps[v][k] = drumSteps[v][k % STEPS]
        drumPreset = i % presetNames.size
    }

    fun drumClear() {
        for (v in 0 until DV) java.util.Arrays.fill(drumSteps[v], 0)
    }

    fun drumHasNotes() = drumSteps.any { row -> (0 until drumLen).any { row[it] > 0 } }

    // ---------------------------------------------------------------- SYNTH (polyfoner Lead-/Pad-Synth)
    val synSemi = IntArray(MAX)
    val synGate = BooleanArray(MAX)
    /** Halten: der Schritt verlängert die Note des vorigen Schritts. */
    val synTie = BooleanArray(MAX)
    val synVel = FloatArray(MAX) { 1f }
    @Volatile var synLen = 16
    @Volatile var synOn = true
    @Volatile var synFx = true
    @Volatile var synOct = 0
    /** 0 SAW, 1 SQUARE, 2 SUPERSAW, 3 PLUCK, 4 GLOCKE (FM) */
    @Volatile var synWave = 2
    /** 0 einzeln, 1 Dur, 2 Moll, 3 Sus4, 4 Dur7, 5 Moll7, 6 Oktave */
    @Volatile var synChord = 0
    @Volatile var synCut = 0.55f
    @Volatile var synRes = 0.25f
    @Volatile var synAtk = 0.05f
    @Volatile var synRel = 0.35f
    @Volatile var synVol = 0.6f
    @Volatile var synDetune = 0.35f
    @Volatile var synEnv = 0.4f
    @Volatile var synCutMod = 0f
    val synWaveNames = arrayOf("SAW", "SQR", "SUPER", "PLUCK", "GLOCKE")
    val synChordNames = arrayOf("EINZELN", "DUR", "MOLL", "SUS4", "DUR7", "MOLL7", "OKTAVE")
    val synChordIv = arrayOf(intArrayOf(0), intArrayOf(0, 4, 7), intArrayOf(0, 3, 7), intArrayOf(0, 5, 7), intArrayOf(0, 4, 7, 10), intArrayOf(0, 3, 7, 10), intArrayOf(0, 12))

    fun synClear() {
        java.util.Arrays.fill(synGate, false)
        java.util.Arrays.fill(synTie, false)
        java.util.Arrays.fill(synSemi, 0)
        java.util.Arrays.fill(synVel, 1f)
    }

    fun synHasNotes() = (0 until synLen).any { synGate[it] }

    /** Zufällige Melodie in Moll-Pentatonik mit gehaltenen Tönen. */
    fun synRandom() {
        synClear()
        val scale = intArrayOf(0, 3, 5, 7, 10, 12, 15)
        val r = java.util.Random()
        var k = 0
        while (k < STEPS) {
            if (r.nextFloat() < 0.6f) {
                synGate[k] = true
                synSemi[k] = scale[r.nextInt(scale.size)]
                synVel[k] = 0.7f + r.nextFloat() * 0.3f
                val hold = if (r.nextFloat() < 0.4f) 1 + r.nextInt(3) else 0
                for (h in 1..hold) if (k + h < STEPS) { synGate[k + h] = true; synTie[k + h] = true; synSemi[k + h] = synSemi[k] }
                k += 1 + hold
            } else k++
        }
        for (i in STEPS until MAX) { synGate[i] = synGate[i % STEPS]; synTie[i] = synTie[i % STEPS]; synSemi[i] = synSemi[i % STEPS]; synVel[i] = synVel[i % STEPS] }
    }

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
        j.put("acidTie", ba(acidTie)); j.put("acidLen", acidLen); j.put("drumLen", drumLen)
        j.put("acidGlide", acidGlide.toDouble()); j.put("acidAccent", acidAccent.toDouble()); j.put("acidDrive", acidDrive.toDouble()); j.put("acidTune", acidTune)
        j.put("drumMute", ba(drumMute))
        j.put("acidVel", fa(acidVel))
        j.put("drumVel", JSONArray().apply { for (v in 0 until DV) put(fa(drumVel[v])) })
        j.put("synSemi", ia(synSemi)); j.put("synGate", ba(synGate)); j.put("synTie", ba(synTie)); j.put("synVel", fa(synVel))
        j.put("synLen", synLen); j.put("synOn", synOn); j.put("synFx", synFx); j.put("synOct", synOct); j.put("synWave", synWave); j.put("synChord", synChord)
        j.put("synCut", synCut.toDouble()); j.put("synRes", synRes.toDouble()); j.put("synAtk", synAtk.toDouble()); j.put("synRel", synRel.toDouble())
        j.put("synVol", synVol.toDouble()); j.put("synDetune", synDetune.toDouble()); j.put("synEnv", synEnv.toDouble())
        return j
    }

    fun fromJson(j: JSONObject?) {
        if (j == null) {
            acidClear(); drumClear(); synClear(); return
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
        java.util.Arrays.fill(acidTie, false)
        java.util.Arrays.fill(drumMute, false)
        rb("acidTie", acidTie); rb("drumMute", drumMute)
        java.util.Arrays.fill(acidVel, 1f)
        for (r in drumVel) java.util.Arrays.fill(r, 1f)
        rf("acidVel", acidVel)
        j.optJSONArray("drumVel")?.let { dv -> for (v in 0 until min(DV, dv.length())) dv.optJSONArray(v)?.let { r -> for (k in 0 until min(MAX, r.length())) drumVel[v][k] = r.optDouble(k, 1.0).toFloat() } }
        acidLen = j.optInt("acidLen", 16).coerceIn(16, MAX); drumLen = j.optInt("drumLen", 16).coerceIn(16, MAX)
        acidGlide = j.optDouble("acidGlide", 0.25).toFloat(); acidAccent = j.optDouble("acidAccent", 0.6).toFloat()
        acidDrive = j.optDouble("acidDrive", 0.2).toFloat(); acidTune = j.optInt("acidTune", 0)
        // alte Projekte (16 Schritte): Rest leeren
        if (!j.has("acidLen")) for (k in STEPS until MAX) { acidGate[k] = false; acidTie[k] = false }
        if (!j.has("drumLen")) for (v in 0 until DV) for (k in STEPS until MAX) drumSteps[v][k] = 0
        synClear()
        ri("synSemi", synSemi); rb("synGate", synGate); rb("synTie", synTie); rf("synVel", synVel)
        synLen = j.optInt("synLen", 16).coerceIn(16, MAX); synOn = j.optBoolean("synOn", true); synFx = j.optBoolean("synFx", true)
        synOct = j.optInt("synOct", 0); synWave = j.optInt("synWave", 2); synChord = j.optInt("synChord", 0)
        synCut = j.optDouble("synCut", 0.55).toFloat(); synRes = j.optDouble("synRes", 0.25).toFloat()
        synAtk = j.optDouble("synAtk", 0.05).toFloat(); synRel = j.optDouble("synRel", 0.35).toFloat()
        synVol = j.optDouble("synVol", 0.6).toFloat(); synDetune = j.optDouble("synDetune", 0.35).toFloat(); synEnv = j.optDouble("synEnv", 0.4).toFloat()
    }
}

/** 303-artiger Bass-Synth: Sägezahn/Rechteck, resonantes 4-Pol-Filter, Hüllkurve, Accent und Slide. */
class AcidSynth(private val sr: Int) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val acc: Boolean, val slide: Boolean, val vel: Float = 1f)

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

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((36 + 12 * InstState.acidOct + InstState.acidTune + semi - 69) / 12.0)

    /** frame < 0 = sofort. */
    fun queue(frame: Long, on: Boolean, semi: Int, acc: Boolean, slide: Boolean, vel: Float = 1f) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, acc, slide, vel)) }
    }

    private var velNow = 1f

    private fun apply(e: Ev) {
        if (!e.on) {
            gate = false
            return
        }
        target = hz(e.semi)
        if (!(e.slide && gate)) velNow = e.vel
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
        val glide = 1.0 - exp(-1.0 / ((0.01 + st.acidGlide * 0.24) * sr))
        val accAmt = st.acidAccent * 1.7f
        val drv = 1f + st.acidDrive * 5f
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
            val accBoost = if (accNow) accEnv * (0.3f + st.acidEnv) * 2.5f * accAmt / 1.02f else 0f
            val oct = st.acidCut * 7f + (st.acidEnv * 5f) * fenv + accBoost * 2f + st.acidCutMod * 3f
            val fc = min(0.45f * sr, 70f * 2f.pow(oct))
            val g = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()
            val x = tanh(osc.toFloat() - res * y4)
            y1 += g * (x - y1)
            y2 += g * (y1 - y2)
            y3 += g * (y2 - y3)
            y4 += g * (y3 - y4)
            val a = amp * velNow * (if (accNow) 1f + 0.6f * accAmt * accEnv else 1f)
            out[i] += tanh(y4 * (1f + st.acidRes * 1.5f) * 1.4f * drv) / (0.75f + 0.25f * drv) * a * vol
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

    fun trigger(v: Int, delay: Int, accent: Boolean, vel: Float = 1f) {
        if (v !in 0 until InstState.DV) return
        synchronized(lock) {
            val vc = voices[v]
            vc.active = true
            vc.delay = max(0, delay)
            vc.t = 0
            vc.acc = (if (accent) 1.45f else 1f) * vel.coerceIn(0.02f, 1f)
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
                if (st.drumMute[v]) { vc.active = false; continue }
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

/**
 * Polyfoner Synth (bis 10 Stimmen): Sägezahn, Rechteck, Supersaw (5 verstimmte Sägezähne),
 * Pluck und FM-Glocke; je Stimme ADSR und resonantes 2-Pol-Filter mit Hüllkurve.
 * Spielt Einzeltöne oder Akkorde.
 */
class PolySynth(private val sr: Int) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val vel: Float, val tag: Int)

    private class Voice {
        var active = false
        var tag = 0
        var semi = 0
        var freq = 220.0
        var vel = 1f
        var gate = false
        var env = 0f
        var stage = 0      // 0 Attack, 1 Decay/Sustain, 2 Release
        var fenv = 0f
        val ph = DoubleArray(5)
        var mph = 0.0
        var lp = 0f
        var bp = 0f
        var age = 0L
    }

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private val voices = Array(10) { Voice() }
    private var ageCtr = 0L
    private val det = doubleArrayOf(0.0, -0.11, 0.11, -0.05, 0.05)

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((60 + 12 * InstState.synOct + semi - 69) / 12.0)

    /** frame < 0 = sofort. [tag] verbindet An und Aus (z. B. Schritt oder Finger). */
    fun queue(frame: Long, on: Boolean, semi: Int, vel: Float, tag: Int) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, vel, tag)) }
    }

    fun allOff() {
        synchronized(lock) { queue.clear() }
        for (v in voices) if (v.active) { v.gate = false; v.stage = 2 }
    }

    private fun apply(e: Ev) {
        val iv = InstState.synChordIv[InstState.synChord.coerceIn(0, InstState.synChordIv.size - 1)]
        if (!e.on) {
            for (v in voices) if (v.active && v.tag == e.tag && v.gate) { v.gate = false; v.stage = 2 }
            return
        }
        for (o in iv) {
            var v = voices.firstOrNull { !it.active }
            if (v == null) v = voices.minByOrNull { if (it.gate) it.age + 1_000_000_000L else it.age }!!
            v.active = true
            v.tag = e.tag
            v.semi = e.semi + o
            v.freq = hz(v.semi)
            v.vel = e.vel
            v.gate = true
            v.stage = 0
            v.fenv = 1f
            v.age = ++ageCtr
            if (v.env < 0.001f) {
                for (k in v.ph.indices) v.ph[k] = Math.random()
                v.mph = 0.0
                v.lp = 0f; v.bp = 0f
            }
        }
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
        val st = InstState
        val wave = st.synWave
        val atk = (1.0 / ((0.002 + st.synAtk * st.synAtk * 2.5) * sr)).toFloat()
        val relC = exp(-1.0 / ((0.02 + st.synRel * st.synRel * 3.0) * sr)).toFloat()
        val decC = exp(-1.0 / ((if (wave == 3) 0.25 else 0.6) * sr)).toFloat()
        val sus = if (wave == 3) 0f else if (wave == 4) 0.0f else 0.8f
        val fDec = exp(-1.0 / ((if (wave == 3) 0.12 else 0.35) * sr)).toFloat()
        val q = 1f - st.synRes * 0.92f
        val vol = st.synVol * 0.32f
        val spread = st.synDetune
        var ei = 0
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { apply(evs[ei]); ei++ }
            var mix = 0f
            for (v in voices) {
                if (!v.active) continue
                when (v.stage) {
                    0 -> { v.env += atk; if (v.env >= 1f) { v.env = 1f; v.stage = 1 } }
                    1 -> v.env = sus + (v.env - sus) * decC
                    else -> { v.env *= relC; if (v.env < 1e-4f) { v.active = false; continue } }
                }
                v.fenv *= fDec
                val f = v.freq
                var o: Double
                when (wave) {
                    1 -> {
                        val dt = f / sr
                        v.ph[0] += dt; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        var sq = if (v.ph[0] < 0.5) 1.0 else -1.0
                        sq += blep(v.ph[0], dt); sq -= blep((v.ph[0] + 0.5) % 1.0, dt)
                        o = sq * 0.8
                    }
                    2 -> {
                        o = 0.0
                        for (k in 0 until 5) {
                            val fk = f * (1.0 + det[k] * spread * 0.12)
                            val dt = fk / sr
                            v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
                            o += 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
                        }
                        o *= 0.32
                    }
                    4 -> {
                        // FM-Glocke: Modulator 3,5 : 1, Index fällt mit der Hüllkurve
                        v.mph += f * 3.5 / sr; if (v.mph >= 1.0) v.mph -= 1.0
                        val idx = 0.4 + 3.2 * v.fenv
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        o = kotlin.math.sin(2 * PI * v.ph[0] + idx * kotlin.math.sin(2 * PI * v.mph))
                    }
                    else -> {
                        // SAW / PLUCK: zwei leicht verstimmte Sägezähne
                        o = 0.0
                        for (k in 0 until 2) {
                            val fk = f * (1.0 + (if (k == 0) -1 else 1) * spread * 0.006)
                            val dt = fk / sr
                            v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
                            o += 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
                        }
                        o *= 0.5
                    }
                }
                var s = o.toFloat()
                if (wave != 4) {
                    val oct = st.synCut * 7.5f + st.synEnv * 4.5f * v.fenv + st.synCutMod * 2.5f
                    val fc = min(0.42f * sr, 60f * 2f.pow(oct))
                    val g = (2.0 * kotlin.math.sin(PI * fc / sr)).toFloat().coerceAtMost(1.1f)
                    // Chamberlin-SVF (Tiefpass)
                    v.lp += g * v.bp
                    val hp = s - v.lp - q * v.bp
                    v.bp += g * hp
                    s = v.lp
                }
                mix += s * v.env * v.vel
            }
            out[i] += tanh(mix * vol * 1.2f)
        }
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}
