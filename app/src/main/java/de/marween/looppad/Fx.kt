package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** Freeverb (mono) – für REVERB (Raum) und HALL (große Halle). */
private class Verb(sr: Int, scale: Float) {
    private val combTun = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTun = intArrayOf(556, 441, 341, 225)
    private val combs = Array(8) { FloatArray(max(1, (combTun[it] * scale * sr / 44100f).toInt())) }
    private val combIdx = IntArray(8)
    private val combStore = FloatArray(8)
    private val aps = Array(4) { FloatArray(max(1, (apTun[it] * sr / 44100f).toInt())) }
    private val apIdx = IntArray(4)

    fun reset() {
        for (c in combs) java.util.Arrays.fill(c, 0f)
        java.util.Arrays.fill(combStore, 0f)
        for (a in aps) java.util.Arrays.fill(a, 0f)
    }

    fun tick(input: Float, room: Float, damp: Float): Float {
        var acc = 0f
        for (c in 0 until 8) {
            val cb = combs[c]
            val ci = combIdx[c]
            val yv = cb[ci]
            combStore[c] = yv * (1f - damp) + combStore[c] * damp
            cb[ci] = input + combStore[c] * room
            combIdx[c] = if (ci + 1 == cb.size) 0 else ci + 1
            acc += yv
        }
        for (a in 0 until 4) {
            val ab = aps[a]
            val ai = apIdx[a]
            val bo = ab[ai]
            val o = -acc + bo
            ab[ai] = acc + bo * 0.5f
            apIdx[a] = if (ai + 1 == ab.size) 0 else ai + 1
            acc = o
        }
        return acc
    }
}

/** Bandpass (TPT-SVF) für den Vocoder. */
private class Band {
    var a1 = 0f
    var a2 = 0f
    var a3 = 0f
    var k = 0f
    private var ic1 = 0f
    private var ic2 = 0f

    fun set(freq: Float, q: Float, sr: Int) {
        val g = tan(PI * min(freq, 0.45f * sr) / sr).toFloat()
        k = 1f / q
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun bp(v0: Float): Float {
        val v3 = v0 - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        return v1
    }

    fun reset() {
        ic1 = 0f
        ic2 = 0f
    }
}

/**
 * Master-Effekte im Kaoss-Pad-Stil. X/Y kommen von der Touch-Fläche,
 * MIX, P1, P2 von den Drehreglern. Der Effekt greift, solange der Finger liegt;
 * Delay, Reverb und Hall klingen danach natürlich aus.
 */
class Fx(private val sr: Int) {

    companion object {
        const val FILTER = 0
        const val DELAY = 1
        const val STUTTER = 2
        const val FLANGER = 3
        const val CHORUS = 4
        const val REVERB = 5
        const val HALL = 6
        const val VOCODER = 7
        const val DISTORTION = 8
        const val CRUSH = 9
        const val COUNT = 10

        val NAMES = arrayOf("FILTER", "DELAY", "STUTTER", "FLANGER", "CHORUS", "REVERB", "HALL", "VOCODER", "DISTORT", "CRUSH")
        val X_LABELS = arrayOf("CUTOFF", "ZEIT", "LÄNGE", "SPEED", "SPEED", "GRÖSSE", "LÄNGE", "TONHÖHE", "DRIVE", "RATE")
        val Y_LABELS = arrayOf("RESONANZ", "FEEDBACK", "GATE", "TIEFE", "TIEFE", "DÄMPFUNG", "HELLIGKEIT", "FORMANT", "TON", "BITS")
        val P1_LABELS = arrayOf("MODUS", "TON", "PITCH", "FEEDBK", "STIMMEN", "PRE", "PRE", "AKKORD", "TYP", "DRIVE")
        val P2_LABELS = arrayOf("DRIVE", "WOBBLE", "TON", "BASIS", "BASIS", "LOWCUT", "LOWCUT", "MIC", "LEVEL", "TON")
        val P1_DEFAULT = floatArrayOf(0f, 0.6f, 0.5f, 0.5f, 0.6f, 0.15f, 0.4f, 0.3f, 0.2f, 0.2f)
        val P2_DEFAULT = floatArrayOf(0f, 0.2f, 1f, 0.3f, 0.4f, 0.2f, 0.3f, 0f, 0.6f, 1f)
        val HUES = floatArrayOf(190f, 280f, 50f, 130f, 160f, 230f, 250f, 310f, 15f, 0f)
    }

    private var engage = 0f
    private var sx = 0.5f
    private var sy = 0.5f
    private var lastType = -1
    private var wasTouching = false

    // Verlauf des trockenen Signals (für STUTTER), indiziert über den Ausgabe-Frame
    private val histSize = sr * 8
    private val hist = FloatArray(histSize)

    // FILTER
    private var ic1 = 0f
    private var ic2 = 0f

    // DELAY
    private val dlSize = sr * 4
    private val dl = FloatArray(dlSize)
    private var dlW = 0
    private var dlTime = sr * 0.25f
    private var dlLp = 0f
    private var lfo = 0.0

    // STUTTER
    private var anchor = 0L
    private var gateEnv = 0f
    private var rpLp = 0f

    // CRUSH
    private var holdCount = 0f
    private var held = 0f
    private var crLp = 0f

    // FLANGER / CHORUS (gemeinsame Modulations-Verzögerung)
    private val mdSize = sr / 10
    private val md = FloatArray(mdSize)
    private var mdW = 0
    private var mdLfo = 0.0

    // REVERB / HALL
    private val room = Verb(sr, 1f)
    private val hall = Verb(sr, 1.7f)
    private val preSize = sr / 2 + 1
    private val pre = FloatArray(preSize)
    private var preW = 0
    private var hpState = 0f
    private var verbLp = 0f

    // VOCODER
    private val bandCount = 16
    private val modBands = Array(bandCount) { Band() }
    private val carBands = Array(bandCount) { Band() }
    private val env = FloatArray(bandCount)
    private val oscPh = DoubleArray(3)
    private var vocFormant = -1f

    // DISTORTION
    private var dsLp = 0f
    private var dsHp = 0f

    private fun reset() {
        ic1 = 0f; ic2 = 0f
        java.util.Arrays.fill(dl, 0f); dlLp = 0f
        gateEnv = 0f; rpLp = 0f
        holdCount = 0f; held = 0f; crLp = 0f
        java.util.Arrays.fill(md, 0f)
        room.reset(); hall.reset()
        java.util.Arrays.fill(pre, 0f)
        hpState = 0f; verbLp = 0f
        for (b in modBands) b.reset()
        for (b in carBands) b.reset()
        java.util.Arrays.fill(env, 0f)
        vocFormant = -1f
        dsLp = 0f; dsHp = 0f
    }

    private fun onePole(cutoff: Float): Float = (1.0 - exp(-2.0 * PI * cutoff / sr)).toFloat()

    fun process(
        buf: FloatArray, n: Int, frame0: Long,
        type: Int, x: Float, y: Float, touching: Boolean,
        mix: Float, p1: Float, p2: Float,
        masterLen: Long, masterStart: Long,
        mic: FloatArray
    ) {
        if (type != lastType) {
            reset()
            lastType = type
        }
        for (i in 0 until n) hist[Math.floorMod(frame0 + i, histSize.toLong()).toInt()] = buf[i]

        val sm = 1f - exp(-n / (0.03f * sr))
        sx += (x - sx) * sm
        sy += (y - sy) * sm
        val bar = if (masterLen > 0) masterLen else sr * 2L
        val target = if (touching) 1f else 0f
        val eRate = 1f / (0.008f * sr)

        if (touching && !wasTouching && type == STUTTER) {
            val l = stutterLen(bar)
            anchor = if (masterLen > 0) masterStart + Math.floorDiv(frame0 - masterStart, l) * l else frame0
            if (frame0 - anchor >= histSize / 2) anchor = frame0
        }
        wasTouching = touching

        val tails = type == DELAY || type == REVERB || type == HALL
        if (!tails && !touching && engage < 1e-4f) {
            engage = 0f
            return
        }

        when (type) {
            FILTER -> filter(buf, n, target, eRate, mix, p1, p2)
            DELAY -> delay(buf, n, target, eRate, mix, p1, p2, bar)
            STUTTER -> stutter(buf, n, frame0, target, eRate, mix, p1, p2, bar)
            FLANGER -> flanger(buf, n, target, eRate, mix, p1, p2)
            CHORUS -> chorus(buf, n, target, eRate, mix, p1, p2)
            REVERB -> reverb(buf, n, target, eRate, mix, p1, p2, room, 0.7f + sx * 0.28f, sy * 0.7f)
            HALL -> reverb(buf, n, target, eRate, mix, p1, p2, hall, 0.88f + sx * 0.115f, 0.6f - sy * 0.55f)
            VOCODER -> vocoder(buf, n, target, eRate, mix, p1, p2, mic)
            DISTORTION -> distortion(buf, n, target, eRate, mix, p1, p2)
            CRUSH -> crush(buf, n, target, eRate, mix, p1, p2)
        }
    }

    private fun stepEngage(target: Float, rate: Float) {
        if (engage < target) engage = min(target, engage + rate) else if (engage > target) engage = max(target, engage - rate)
    }

    // ------------------------------------------------------------------ FILTER

    private fun filter(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, mode: Float, drive: Float) {
        val fc = min(40f * (18000f / 40f).pow(sx), 0.45f * sr)
        val g = tan(PI * fc / sr).toFloat()
        val k = 2f - 1.93f * sy
        val a1 = 1f / (1f + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2
        val gain = 1f + drive * 7f
        val norm = 1f / tanh(gain)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val dry = buf[i]
            val v0 = if (drive < 0.01f) dry else tanh(dry * gain) * norm
            val v3 = v0 - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            val lp = v2
            val bp = v1
            val hp = v0 - k * v1 - v2
            val wet = if (mode < 0.5f) lp + (bp - lp) * mode * 2f else bp + (hp - bp) * (mode - 0.5f) * 2f
            buf[i] = dry + (wet - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ DELAY (taktsynchron)

    private val delayFractions = floatArrayOf(1f / 32, 1f / 16, 3f / 32, 1f / 8, 3f / 16, 1f / 4, 3f / 8, 1f / 2)

    private fun delay(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, tone: Float, wobble: Float, bar: Long) {
        val idx = min(delayFractions.size - 1, (sx * delayFractions.size).toInt())
        var t = bar * delayFractions[idx]
        while (t > dlSize - 4) t /= 2f
        t = max(t, sr * 0.01f)
        val fb = sy * 0.95f
        val lpA = onePole(600f * (16000f / 600f).pow(tone))
        val lfoInc = 2.0 * PI * 0.6 / sr
        val wobDepth = wobble * 0.004f * sr
        for (i in 0 until n) {
            stepEngage(target, eRate)
            dlTime += (t - dlTime) * 0.0006f
            lfo += lfoInc
            if (lfo > 2 * PI) lfo -= 2 * PI
            val d = dlTime + wobDepth * sin(lfo).toFloat()
            val out = readInterp(dl, dlSize, dlW - d)
            dlLp += (out - dlLp) * lpA
            val dry = buf[i]
            dl[dlW] = dry * engage + dlLp * fb
            dlW++
            if (dlW == dlSize) dlW = 0
            buf[i] = dry + out * mix
        }
    }

    private fun readInterp(b: FloatArray, size: Int, pos: Float): Float {
        var rp = pos
        while (rp < 0f) rp += size
        val i0 = rp.toInt() % size
        val i1 = (i0 + 1) % size
        val fr = rp - floor(rp)
        return b[i0] + (b[i1] - b[i0]) * fr
    }

    // ------------------------------------------------------------------ STUTTER (taktsynchron)

    private val stutterFractions = floatArrayOf(1f / 4, 1f / 8, 1f / 16, 1f / 32, 1f / 64)

    private fun stutterLen(bar: Long): Long {
        val idx = min(stutterFractions.size - 1, (sx * stutterFractions.size).toInt())
        var l = (bar * stutterFractions[idx]).toLong()
        while (l > histSize / 2) l /= 2
        return max(l, (sr / 200).toLong())
    }

    private fun stutter(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, pitch: Float, tone: Float, bar: Long) {
        val l = stutterLen(bar)
        val speed = 2.0.pow(((pitch - 0.5f) * 2f).toDouble())
        val duty = 1f - 0.85f * sy
        val lpA = onePole(400f * (20000f / 400f).pow(tone))
        val gA = onePole(400f)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val t = frame0 + i
            val rel = t - anchor
            val dry = buf[i]
            var wet = dry
            if (rel >= 0) {
                val inCycle = ((rel * speed) % l).toLong()
                val src = anchor + inCycle
                if (src < t && t - src < histSize) wet = hist[Math.floorMod(src, histSize.toLong()).toInt()]
                val phase = (rel % l).toFloat() / l
                val gate = if (phase < duty) 1f else 0f
                gateEnv += (gate - gateEnv) * gA
                wet *= gateEnv
            }
            rpLp += (wet - rpLp) * lpA
            buf[i] = dry + (rpLp - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ FLANGER

    private fun flanger(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, feedback: Float, base: Float) {
        val rate = 0.05 * (100.0).pow(sx.toDouble())
        val inc = 2.0 * PI * rate / sr
        val baseS = (0.0005f + base * 0.008f) * sr
        val depthS = sy * 0.005f * sr
        val fb = feedback * 0.9f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            mdLfo += inc
            if (mdLfo > 2 * PI) mdLfo -= 2 * PI
            val d = min(mdSize - 3f, baseS + depthS * (0.5f + 0.5f * sin(mdLfo).toFloat()))
            val delayed = readInterp(md, mdSize, mdW - d)
            val dry = buf[i]
            md[mdW] = dry + delayed * fb
            mdW++
            if (mdW == mdSize) mdW = 0
            val wet = 0.7f * (dry + delayed)
            buf[i] = dry + (wet - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ CHORUS

    private fun chorus(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, voices: Float, base: Float) {
        val rate = 0.1 * (40.0).pow(sx.toDouble())
        val inc = 2.0 * PI * rate / sr
        val baseS = (0.008f + base * 0.022f) * sr
        val depthS = (0.0005f + sy * 0.006f) * sr
        val v3 = voices
        for (i in 0 until n) {
            stepEngage(target, eRate)
            mdLfo += inc
            if (mdLfo > 2 * PI) mdLfo -= 2 * PI
            val dry = buf[i]
            md[mdW] = dry
            val d1 = baseS + depthS * sin(mdLfo).toFloat()
            val d2 = baseS * 1.3f + depthS * sin(mdLfo + 2.094).toFloat()
            val d3 = baseS * 0.7f + depthS * sin(mdLfo + 4.189).toFloat()
            val w1 = readInterp(md, mdSize, mdW - d1)
            val w2 = readInterp(md, mdSize, mdW - min(mdSize - 3f, d2))
            val w3 = readInterp(md, mdSize, mdW - d3)
            mdW++
            if (mdW == mdSize) mdW = 0
            val wet = (dry + w1 + (w2 + w3) * v3) / (2f + 2f * v3) * 1.6f
            buf[i] = dry + (wet - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ REVERB / HALL

    private fun reverb(
        buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float,
        preDelay: Float, lowCut: Float, verb: Verb, roomFb: Float, damp: Float
    ) {
        val preS = min(preSize - 1, (preDelay * 0.5f * sr).toInt())
        val hpA = onePole(20f * 40f.pow(lowCut))
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val dry = buf[i]
            pre[preW] = dry * engage
            var r = preW - preS
            if (r < 0) r += preSize
            val delayedIn = pre[r]
            preW++
            if (preW == preSize) preW = 0
            hpState += (delayedIn - hpState) * hpA
            val out = verb.tick((delayedIn - hpState) * 0.015f, roomFb, damp)
            buf[i] = dry + out * mix * 2.5f
        }
    }

    // ------------------------------------------------------------------ VOCODER

    private fun vocoder(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, chord: Float, micAmt: Float, mic: FloatArray) {
        // Trägerton: Sägezahn, Tonhöhe auf Halbtöne gerastet (A1 .. A5)
        val semis = round(sx * 48f)
        val f0 = 55.0 * 2.0.pow(semis / 12.0)
        val incs = doubleArrayOf(f0 / sr, f0 * 1.4983 / sr, f0 * 2.0 / sr)
        val v2 = (chord * 2f).coerceIn(0f, 1f)
        val v3 = (chord * 2f - 1f).coerceIn(0f, 1f)
        val formant = 0.5f * 4f.pow(sy)
        if (abs(formant - vocFormant) > 0.01f) {
            vocFormant = formant
            for (b in 0 until bandCount) {
                val f = 120f * (8000f / 120f).pow(b / (bandCount - 1f))
                modBands[b].set(f, 7f, sr)
                carBands[b].set(f * formant, 7f, sr)
            }
        }
        val att = onePole(200f)
        val rel = onePole(25f)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val dry = buf[i]
            var car = 0f
            for (o in 0 until 3) {
                oscPh[o] += incs[o]
                if (oscPh[o] >= 1.0) oscPh[o] -= 1.0
            }
            car += (2.0 * oscPh[0] - 1.0).toFloat()
            car += (2.0 * oscPh[1] - 1.0).toFloat() * v2
            car += (2.0 * oscPh[2] - 1.0).toFloat() * v3
            val mod = dry + (if (i < mic.size) mic[i] else 0f) * micAmt * 2f
            var wet = 0f
            for (b in 0 until bandCount) {
                val m = abs(modBands[b].bp(mod))
                val e = env[b]
                env[b] = e + (m - e) * (if (m > e) att else rel)
                wet += carBands[b].bp(car) * env[b]
            }
            wet *= 6f
            buf[i] = dry + (wet - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ DISTORTION

    private fun distortion(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, kind: Float, level: Float) {
        val gain = 1f + sx * sx * 60f
        val lpA = onePole(700f * (14000f / 700f).pow(sy))
        val hpA = onePole(60f)
        val outGain = 0.3f + level * 0.9f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val dry = buf[i]
            dsHp += (dry - dsHp) * hpA
            val v = (dry - dsHp) * gain
            val soft = tanh(v)
            val hard = v.coerceIn(-1f, 1f)
            val fold = sin(v * 1.5f)
            val shaped = if (kind < 0.5f) soft + (hard - soft) * kind * 2f else hard + (fold - hard) * (kind - 0.5f) * 2f
            dsLp += (shaped - dsLp) * lpA
            buf[i] = dry + (dsLp * outGain - dry) * engage * mix
        }
    }

    // ------------------------------------------------------------------ CRUSH

    private fun crush(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, drive: Float, tone: Float) {
        val hold = 1f + sx * sx * 40f
        val bits = 16f - sy * 14f
        val steps = 2f.pow(bits - 1f)
        val gain = 1f + drive * 10f
        val norm = 1f / tanh(gain)
        val lpA = onePole(500f * (20000f / 500f).pow(tone))
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val dry = buf[i]
            holdCount += 1f
            if (holdCount >= hold) {
                holdCount -= hold
                held = tanh(dry * gain) * norm
            }
            val q = round(held * steps) / steps
            crLp += (q - crLp) * lpA
            buf[i] = dry + (crLp - dry) * engage * mix
        }
    }
}
