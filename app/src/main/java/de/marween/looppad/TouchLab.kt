package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🖐 TOUCH-LAB: gemeinsamer Klang-Kanal der Touch-Instrumente
 *  - MORPH: Timbre Morph Matrix (4 Klänge in den Ecken, der Finger mischt Lautstärke UND Charakter)
 *  - TERRAIN: Wave Terrain (eine Umlaufbahn tastet eine Klanglandschaft ab: X = Klangfarbe, Y = Tonhöhe, Höhe = Intensität)
 *  - SPECTRAL: Spectral Painter (Klang ins Spektrum malen, läuft im Takt)
 *  - GESTURE: Gesten-Instrument (Kreisen = Modulation, Wischen = Pitch-Bend, mehrere Finger = Akkord, Fläche = Dynamik)
 *  - MODULAR: Touch Modular (Module + Kabel, siehe [Modular])
 * Gemeinsames Stereo-Delay (SPACE) für alle.
 */
object TouchLab {
    const val MORPH = 0
    const val TERRAIN = 1
    const val SPECTRAL = 2
    const val GESTURE = 3
    const val MODULAR = 4
    val MODE_NAMES = arrayOf("MORPH-MATRIX", "WAVE TERRAIN", "SPECTRAL PAINTER", "GESTEN", "TOUCH MODULAR")

    @Volatile var mode = MORPH
    @Volatile var vol = 0.8f
    @Volatile var space = 0.25f
    @Volatile var meter = 0f

    // ---------------------------------------------------------------- Stimmen
    class V {
        @Volatile var gate = false
        var active = false
        var tag = 0
        var semi = 60.0
        var tgt = 60.0
        var vel = 1f
        var env = 0f
        var fenv = 0f
        var t = 0L
        val ph = DoubleArray(16)
        val s = FloatArray(32)
        var lp = 0f; var bp = 0f
        // Steuer-Rate (alle 8 Proben neu): Frequenz und Filter-Koeffizient
        var cf = 261.6; var cg = 0.1f
        var r = 4242
        fun nz(): Float { r = r * 1664525 + 1013904223; return ((r ushr 8) / 16777216f) * 2f - 1f }
    }
    val voices = Array(6) { V() }

    private class Ev(val on: Boolean, val tag: Int, val semi: Double, val vel: Float)
    private val q = java.util.concurrent.ConcurrentLinkedQueue<Ev>()
    /** [semi] = MIDI-Notennummer (kann gebrochen sein). */
    fun noteOn(tag: Int, semi: Double, vel: Float) { q.add(Ev(true, tag, semi, vel)) }
    fun noteOff(tag: Int) { q.add(Ev(false, tag, 0.0, 0f)) }
    /** Tonhöhe einer klingenden Note gleiten lassen (Wischen, Terrain). */
    fun bend(tag: Int, semi: Double) { for (v in voices) if (v.active && v.tag == tag) v.tgt = semi }
    fun allOff() { for (v in voices) v.gate = false }

    private fun applyEv(e: Ev) {
        if (!e.on) { for (v in voices) if (v.active && v.tag == e.tag) v.gate = false; return }
        var v = voices.firstOrNull { !it.active } ?: voices.minByOrNull { if (it.gate) 1_000_000_000L - it.t else -it.t }!!
        val fresh = !v.active
        v.active = true; v.gate = true; v.tag = e.tag; v.vel = e.vel
        v.tgt = e.semi; if (fresh || mode != GESTURE) v.semi = e.semi
        v.fenv = 1f; v.t = 0
        if (fresh) { java.util.Arrays.fill(v.s, 0f); for (k in v.ph.indices) v.ph[k] = Math.random(); v.lp = 0f; v.bp = 0f; v.env = 0f }
    }

    // ---------------------------------------------------------------- Hilfen
    private const val TN = 4096
    private val SIN = FloatArray(TN + 1) { sin(2 * PI * it / TN).toFloat() }
    fun sinT(p: Double): Float { val x = (p - floor(p)) * TN; val i = min(x.toInt(), TN - 1); val f = (x - i).toFloat(); return SIN[i] + (SIN[i + 1] - SIN[i]) * f }
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }
    private fun saw(v: V, k: Int, f: Double, sr: Int): Double {
        val dt = f / sr
        v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
        return 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
    }
    private fun pulse(v: V, k: Int, f: Double, sr: Int, pw: Double): Double {
        val dt = f / sr
        v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
        var p = if (v.ph[k] < pw) 1.0 else -1.0
        p += blep(v.ph[k], dt); p -= blep((v.ph[k] - pw + 1.0) % 1.0, dt)
        return p - (2 * pw - 1)
    }
    fun hz(semi: Double) = 440.0 * 2.0.pow((semi - 69) / 12.0)

    // ---------------------------------------------------------------- MORPH-MATRIX
    val MODELS = arrayOf("ACID", "ANALOG", "METALL", "VOCAL", "SUB", "SUPERSAW", "ORGEL", "RAUSCHEN", "CHIP")
    /** Charakter je Modell: Cutoff, Resonanz, Drive, Filter-Hüllkurve, Decay (s), Sustain */
    private val CHAR = arrayOf(
        floatArrayOf(0.32f, 0.85f, 0.5f, 0.8f, 0.25f, 0.25f),
        floatArrayOf(0.55f, 0.2f, 0.2f, 0.3f, 0.8f, 0.85f),
        floatArrayOf(0.9f, 0.1f, 0.1f, 0.1f, 1.2f, 0.3f),
        floatArrayOf(0.8f, 0.1f, 0.2f, 0.2f, 0.6f, 0.9f),
        floatArrayOf(0.35f, 0f, 0.35f, 0f, 1f, 1f),
        floatArrayOf(0.75f, 0.15f, 0.2f, 0.25f, 0.8f, 0.9f),
        floatArrayOf(0.85f, 0f, 0.25f, 0f, 1f, 1f),
        floatArrayOf(0.7f, 0.35f, 0f, 0.4f, 0.4f, 0.6f),
        floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0.8f)
    )
    /** Ecken: oben links, oben rechts, unten links, unten rechts. */
    val corners = intArrayOf(0, 3, 1, 2)
    // Formant-Koeffizienten des VOCAL-Modells (einmal je Block)
    private var vg1 = 0.1f; private var vg2 = 0.1f
    @Volatile var mx = 0.5f
    @Volatile var my = 0.5f
    val w = FloatArray(4) { 0.25f }

    private fun model(v: V, m: Int, slot: Int, f: Double, sr: Int): Double {
        val b = slot * 3      // eigene Phasen je Ecke (ph[b..b+2])
        return when (m) {
            0 -> saw(v, b, f, sr)
            1 -> 0.55 * saw(v, b, f * 0.997, sr) + 0.45 * pulse(v, b + 1, f * 1.003, sr, 0.5)
            2 -> {
                v.ph[b + 1] += f * 3.51 / sr; if (v.ph[b + 1] >= 1.0) v.ph[b + 1] -= 1.0
                v.ph[b] += f / sr; if (v.ph[b] >= 1.0) v.ph[b] -= 1.0
                sinT(v.ph[b] + (0.3 + 2.5 * v.fenv) * sinT(v.ph[b + 1]) / (2 * PI)).toDouble()
            }
            3 -> {
                // Puls durch zwei Formanten („Ah“), Grund-Vokal wandert leicht mit der Y-Position
                val x = pulse(v, b, f, sr, 0.3)
                val i0 = slot * 4
                val g1 = vg1; val g2 = vg2
                v.s[i0] += g1 * v.s[i0 + 1]; val h1 = x.toFloat() - v.s[i0] - 0.12f * v.s[i0 + 1]; v.s[i0 + 1] += g1 * h1
                v.s[i0 + 2] += g2 * v.s[i0 + 3]; val h2 = x.toFloat() - v.s[i0 + 2] - 0.15f * v.s[i0 + 3]; v.s[i0 + 3] += g2 * h2
                (v.s[i0 + 1] * 0.8f + v.s[i0 + 3] * 0.5f).toDouble()
            }
            4 -> { v.ph[b] += f / sr; if (v.ph[b] >= 1.0) v.ph[b] -= 1.0; val s = sinT(v.ph[b]).toDouble(); tanh(s * 1.6) }
            5 -> (saw(v, b, f * 0.991, sr) + saw(v, b + 1, f, sr) + saw(v, b + 2, f * 1.009, sr)) * 0.4
            6 -> {
                v.ph[b] += f / sr; if (v.ph[b] >= 1.0) v.ph[b] -= 1.0
                (sinT(v.ph[b]) + 0.6f * sinT(v.ph[b] * 2) + 0.35f * sinT(v.ph[b] * 3) + 0.25f * sinT(v.ph[b] * 4)).toDouble() * 0.5
            }
            7 -> {
                val i0 = 16 + slot * 2
                val g = (2 * sin(PI * min(f * 4, sr / 6.0) / sr)).toFloat()
                v.s[i0] += g * v.s[i0 + 1]; val h = v.nz() - v.s[i0] - 0.3f * v.s[i0 + 1]; v.s[i0 + 1] += g * h
                v.s[i0 + 1].toDouble() * 1.4
            }
            else -> { val p = pulse(v, b, f, sr, 0.25); floor(p * 4) / 4 }
        }
    }

    // ---------------------------------------------------------------- WAVE TERRAIN
    val TERRAINS = arrayOf("HÜGEL", "KRISTALL", "DÜNEN", "CHAOS")
    @Volatile var terrain = 0
    @Volatile var tx = 0.5f
    @Volatile var ty = 0.5f
    @Volatile var orbit = 0.35f
    fun terrainAt(t: Int, x: Double, y: Double): Double = when (t) {
        0 -> sin(2 * PI * 1.5 * x) * cos(2 * PI * y)
        1 -> sin(2 * PI * (3 * x + y * y)) * cos(2 * PI * (2 * y - x))
        2 -> tanh(3 * sin(2 * PI * (x + 0.3 * sin(2 * PI * y))))
        else -> (sin(2 * PI * (5 * x * y + x)) + 0.5 * cos(2 * PI * (7 * y - 3 * x))) / 1.5
    }

    // ---------------------------------------------------------------- GESTEN
    /** Modulationstiefe aus Kreisbewegung (0..1), Helligkeit aus Y, Klang 0 Lead · 1 Fläche · 2 Bass */
    @Volatile var gMod = 0f
    @Volatile var gBright = 0.5f
    @Volatile var gSound = 0
    val G_SOUNDS = arrayOf("LEAD", "FLÄCHE", "BASS")

    // ---------------------------------------------------------------- SPECTRAL PAINTER
    const val NB = 40
    const val NS = 32
    /** [Schritt][Band] 0..1 */
    val spec = Array(NS) { FloatArray(NB) }
    @Volatile var specPlay = false
    @Volatile var specBars = 1
    @Volatile var specScale = true
    @Volatile var specCol = -1
    private val specAmp = FloatArray(NB)
    private val specPh = DoubleArray(NB)
    fun bandHz(b: Int): Double {
        val root = if (InstState.scaleType != 0) InstState.scaleRoot else if (GrooveGen.made) GrooveGen.root else 9
        return if (specScale) {
            // Moll-Pentatonik ab A1 aufwärts (immer harmonisch)
            val pent = intArrayOf(0, 3, 5, 7, 10)
            val n = 33 + root + 12 * (b / 5) + pent[b % 5]
            hz(n.toDouble())
        } else 55.0 * 2.0.pow(b * 7.0 / NB)
    }

    // ---------------------------------------------------------------- Delay (SPACE)
    private var dl = FloatArray(0); private var dr = FloatArray(0); private var dw = 0

    // auch wenn nur Noten warten: sie werden erst im Mischer-Thread eingeschaltet (sonst blieb Touch-Lab stumm)
    fun active(): Boolean = q.isNotEmpty() || voices.any { it.active } || (mode == SPECTRAL && specPlay) || (mode == MODULAR && Modular.running()) || space > 0.01f && dl.isNotEmpty() && delayTail > 0

    private var delayTail = 0
    private val twBuf = FloatArray(4)
    private val chBuf = FloatArray(6)

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w0: Long, ms: Long, ml: Long) {
        while (true) { val e = q.poll() ?: break; applyEv(e) }
        if (dl.isEmpty()) { dl = FloatArray(sr * 2); dr = FloatArray(sr * 2) }
        val beat = if (ml > 0L) ml / 4.0 else sr * 0.5
        // Morph-Gewichte (bilinear) weich nachführen
        val tw = twBuf
        tw[0] = (1 - mx) * my; tw[1] = mx * my; tw[2] = (1 - mx) * (1 - my); tw[3] = mx * (1 - my)
        for (k in 0 until 4) w[k] += (tw[k] - w[k]) * 0.2f
        val ch = chBuf; java.util.Arrays.fill(ch, 0f)
        if (mode == MORPH) for (k in 0 until 4) { val c = CHAR[corners[k]]; for (j in 0 until 6) ch[j] += c[j] * w[k] }
        val atk = 1f / (0.004f * sr)
        val rel = exp(-1.0 / (0.25 * sr)).toFloat()
        // pro Block konstant (früher je Probe und Stimme gerechnet)
        val decK = exp(-1.0 / ((if (mode == MORPH) ch[4] else 0.8f).coerceAtLeast(0.01f) * sr)).toFloat()
        val fenvK = exp(-1.0 / (0.2 * sr)).toFloat()
        vg1 = (2 * sin(PI * (700.0 + 200 * my) / sr)).toFloat(); vg2 = (2 * sin(PI * (1100.0 + 600 * (1 - my)) / sr)).toFloat()
        val tHeight = if (mode == TERRAIN) terrainAt(terrain, tx.toDouble(), ty.toDouble()).toFloat() * 0.5f + 0.5f else 0f
        var pk = 0f
        val dTime = (beat * 0.75).toInt().coerceIn(100, dl.size - 1)
        for (i in 0 until n) {
            var mix = 0f
            when (mode) {
                MORPH, TERRAIN, GESTURE -> for (v in voices) {
                    if (!v.active) continue
                    v.semi += (v.tgt - v.semi) * (if (mode == GESTURE) 0.0015 else 0.004)
                    val ctl = (v.t and 7L) == 0L
                    if (ctl) v.cf = hz(v.semi)
                    val f = v.cf
                    val sus = if (mode == MORPH) ch[5] else 0.85f
                    if (v.gate) { if (v.env < 1f && v.t < sr / 50) v.env = min(1f, v.env + atk) else v.env = sus + (v.env - sus) * decK }
                    else { v.env *= rel; if (v.env < 1e-4f) { v.active = false; continue } }
                    v.fenv *= fenvK
                    v.t++
                    var x: Float
                    var cut: Float; var res: Float; var drv: Float
                    when (mode) {
                        MORPH -> {
                            var s = 0.0
                            for (k in 0 until 4) if (w[k] > 0.02f) s += model(v, corners[k], k, f, sr) * w[k]
                            x = s.toFloat()
                            cut = ch[0] * 7.5f + ch[3] * 4.5f * v.fenv; res = ch[1]; drv = ch[2]
                        }
                        TERRAIN -> {
                            // Bahn um den Fingerpunkt durch die Landschaft
                            v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                            val th = 2 * PI * v.ph[0]
                            val r = 0.05 + orbit * 0.45
                            val px = tx + r * cos(th)
                            val py = 0.5 + r * 0.7 * sin(th) + (ty - 0.5) * 0.3
                            x = terrainAt(terrain, px, py).toFloat()
                            // DC weg
                            v.s[0] += 0.002f * (x - v.s[0]); x -= v.s[0]
                            val height = tHeight
                            cut = 3.5f + height * 4.5f; res = 0.2f + height * 0.3f; drv = height * 0.4f
                        }
                        else -> {
                            // Gesten: drei Sägen + Sub, Vibrato aus Kreisbewegung
                            v.ph[8] += (4.5 + 3 * gMod) / sr; if (v.ph[8] >= 1.0) v.ph[8] -= 1.0
                            val vib = 1.0 + 0.012 * gMod * sinT(v.ph[8])
                            val ff = f * vib
                            x = when (gSound) {
                                1 -> ((saw(v, 0, ff * 0.993, sr) + saw(v, 1, ff, sr) + saw(v, 2, ff * 1.007, sr)) * 0.35).toFloat()
                                2 -> (pulse(v, 0, ff, sr, 0.4) * 0.6 + sinT(v.ph[3].also { v.ph[3] = (v.ph[3] + ff * 0.5 / sr) % 1.0 }) * 0.6).toFloat()
                                else -> (saw(v, 0, ff, sr) * 0.6 + pulse(v, 1, ff * 1.004, sr, 0.3) * 0.4).toFloat()
                            }
                            cut = 3f + gBright * 5f + v.vel * 1.5f + v.fenv * 2f; res = 0.25f + gMod * 0.4f; drv = 0.2f + v.vel * 0.3f
                        }
                    }
                    // Filter (SVF Tiefpass) mit gemischtem Charakter
                    if (ctl) v.cg = (2 * sin(PI * min(sr / 6.0, 50.0 * 2.0.pow(cut.toDouble())) / sr)).toFloat()
                    val g = v.cg
                    v.lp += g * v.bp
                    val hp = x - v.lp - (1f - res * 0.9f) * v.bp
                    v.bp += g * hp
                    var y = v.lp
                    if (drv > 0.02f) y = tanh(y * (1f + drv * 4f)) / (1f + drv)
                    mix += y * v.env * v.vel
                }
                SPECTRAL -> if (specPlay) {
                    val bpos = Engine.beatPos(w0 + i)
                    val len = specBars * 4.0
                    val p = ((bpos % len) + len) % len / len * NS
                    val c0 = p.toInt().coerceIn(0, NS - 1)
                    val c1 = (c0 + 1) % NS
                    val fr = (p - c0).toFloat()
                    specCol = c0
                    var s = 0f
                    for (b in 0 until NB) {
                        val target = spec[c0][b] + (spec[c1][b] - spec[c0][b]) * fr * fr
                        specAmp[b] += (target - specAmp[b]) * 0.003f
                        if (specAmp[b] < 0.001f) continue
                        specPh[b] += bandHz(b) / sr; if (specPh[b] >= 1.0) specPh[b] -= 1.0
                        s += sinT(specPh[b]) * specAmp[b] / (1f + b * 0.04f)
                    }
                    mix += s * 0.35f
                } else specCol = -1
                MODULAR -> mix += Modular.sample(sr, beat)
            }
            // SPACE: Ping-Pong-Delay (punktierte Achtel)
            val idx = dw
            val rdI = (dw - dTime + dl.size) % dl.size
            val outL = dl[rdI]; val outR = dr[rdI]
            val sp = space
            dl[idx] = mix * 0.6f + outR * 0.45f * sp
            dr[idx] = outL * 0.55f
            dw = (dw + 1) % dl.size
            val yl = tanh(((mix + outL * sp) * vol * 0.9f).toDouble()).toFloat()
            val yr = tanh(((mix + outR * sp) * vol * 0.9f).toDouble()).toFloat()
            oL[i] += yl; oR[i] += yr
            val a = abs(yl)
            if (a > pk) pk = a
            if (a > 1e-4f) delayTail = sr * 3 else if (delayTail > 0) delayTail--
        }
        meter = max(pk, meter * 0.9f)
    }
}
