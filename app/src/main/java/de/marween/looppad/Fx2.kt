package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/** Ringpuffer mit absoluter Schreibposition und interpoliertem Lesen. */
private class Ring(val size: Int) {
    val a = FloatArray(size)
    var w = 0L

    fun write(v: Float) {
        a[(w % size).toInt()] = v
        w++
    }

    fun read(pos: Double): Float {
        var p = pos
        val minP = (w - size + 2).toDouble()
        val maxP = (w - 2).toDouble()
        if (p < 0.0 || p < minP) return 0f
        if (p > maxP) p = maxP
        val i0 = floor(p).toLong()
        val fr = (p - i0).toFloat()
        val x0 = a[(i0 % size).toInt()]
        val x1 = a[((i0 + 1) % size).toInt()]
        return x0 + (x1 - x0) * fr
    }

    /** Wert von vor d Samples (0 = zuletzt geschrieben). */
    fun back(d: Double): Float = read(w - 1 - d)

    fun clear() {
        java.util.Arrays.fill(a, 0f)
    }
}

private class Grain {
    var src: Ring? = null
    var pos = 0.0
    var rate = 1.0
    var len = 1
    var age = 0
    var gain = 1f
    var active = false
}

private class Biquad {
    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun tick(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun lowShelf(f: Double, db: Double, sr: Int) {
        val a = 10.0.pow(db / 40.0); val w0 = 2 * PI * f / sr; val c = cos(w0); val al = sin(w0) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        set(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
    }

    fun highShelf(f: Double, db: Double, sr: Int) {
        val a = 10.0.pow(db / 40.0); val w0 = 2 * PI * f / sr; val c = cos(w0); val al = sin(w0) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        set(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
    }

    fun peak(f: Double, db: Double, q: Double, sr: Int) {
        val a = 10.0.pow(db / 40.0); val w0 = 2 * PI * f / sr; val c = cos(w0); val al = sin(w0) / (2 * q)
        set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }
}

/**
 * Erweiterte Effekte: Modulation, EQ/Filter, Sättigung, Granular, Spektral, Glitch und Morph-Ketten.
 * Typnummern siehe Fx.PHASER … Fx.KRISTALL.
 */
class Fx2(private val sr: Int) {
    private var engage = 0f
    private var wasTouching = false
    private val rnd = Random(42)
    private val dry = FloatArray(8192)

    private val hist = Ring(sr * 6)
    private val dly = Ring(sr * 4)
    private val grains = Array(40) { Grain() }

    // Modulation
    private val apS = FloatArray(12)
    private var phLfo = 0.0
    private var phFb = 0f
    private var lfoPh = 0.0
    private val vb = Ring(sr / 10)
    // EQ
    private val eqL = Biquad(); private val eqM = Biquad(); private val eqH = Biquad()
    // Filter
    private var ic1 = 0f; private var ic2 = 0f
    // Sättigung
    private var lp1 = 0f; private var hp1 = 0f
    // Granular
    private var gCount = 0
    private var ptPh = 0.0
    private var ptG = sr * 0.06
    private var stRp = 0.0
    // Spektral
    private val fftN = 1024
    private val hop = 256
    private val win = FloatArray(fftN) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftN)).toFloat() }
    private val inFifo = FloatArray(fftN)
    private var inPos = 0
    private val outAcc = FloatArray(fftN)
    private var outPos = 0
    private var hopCount = 0
    private val re = FloatArray(fftN)
    private val im = FloatArray(fftN)
    private val mag = FloatArray(fftN / 2 + 1)
    private val held = FloatArray(fftN / 2 + 1)
    private val phs = FloatArray(fftN / 2 + 1)
    private var frozen = false
    private val twC = FloatArray(fftN / 2) { cos(2 * PI * it / fftN).toFloat() }
    private val twS = FloatArray(fftN / 2) { sin(2 * PI * it / fftN).toFloat() }
    // Ringmod / FM / Shifter
    private var rmPh = 0.0
    private var rmLfo = 0.0
    private val fmBuf = Ring(sr / 20)
    private var fmFb = 0f
    private val hA = FloatArray(16)
    private val hB = FloatArray(16)
    private var hDelay = 0f
    private var fsPh = 0.0
    private var fsFb = 0f
    // Resonator
    private val rs1 = FloatArray(6); private val rs2 = FloatArray(6)
    // Glitch
    private var shSlice = Long.MIN_VALUE
    private var shBack = 0
    private var shRev = false
    private var shRate = 1.0
    private var sqAction = 0
    private var sqHold = 0f
    private var sqHoldN = 0
    private var mfxLp = 0f

    fun reset() {
        hist.clear(); dly.clear(); vb.clear(); fmBuf.clear()
        for (g in grains) g.active = false
        java.util.Arrays.fill(apS, 0f); phFb = 0f
        eqL.reset(); eqM.reset(); eqH.reset()
        ic1 = 0f; ic2 = 0f; lp1 = 0f; hp1 = 0f
        java.util.Arrays.fill(inFifo, 0f); java.util.Arrays.fill(outAcc, 0f)
        java.util.Arrays.fill(held, 0f); frozen = false
        java.util.Arrays.fill(hA, 0f); java.util.Arrays.fill(hB, 0f); fsFb = 0f; fmFb = 0f
        java.util.Arrays.fill(rs1, 0f); java.util.Arrays.fill(rs2, 0f)
        shSlice = Long.MIN_VALUE; mfxLp = 0f
    }

    private fun onePole(fc: Float): Float = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()

    private fun stepEngage(target: Float, rate: Float) {
        if (engage < target) engage = min(target, engage + rate) else if (engage > target) engage = max(target, engage - rate)
    }

    private fun shape(ph: Double, form: Float): Float {
        val s = sin(2 * PI * ph).toFloat()
        val k = 1f + form * 20f
        return tanh(s * k) / tanh(k)
    }

    private fun semis(v: Float): Int = round((v - 0.5f) * 24f).toInt()

    private fun spawn(src: Ring, pos: Double, rate: Double, len: Int, gain: Float) {
        for (g in grains) {
            if (!g.active) {
                g.src = src; g.pos = pos; g.rate = rate; g.len = max(8, len); g.age = 0; g.gain = gain; g.active = true
                return
            }
        }
    }

    private fun grainSum(): Float {
        var s = 0f
        for (g in grains) {
            if (!g.active) continue
            val w = 0.5f - 0.5f * cos(2 * PI * g.age / g.len).toFloat()
            s += (g.src?.read(g.pos) ?: 0f) * w * g.gain
            g.pos += g.rate
            g.age++
            if (g.age >= g.len) g.active = false
        }
        return s
    }

    fun process(
        buf: FloatArray, n: Int, frame0: Long, type: Int, sx: Float, sy: Float, touching: Boolean,
        mix: Float, p1: Float, p2: Float, bar: Long, ms: Long
    ) {
        val start = touching && !wasTouching
        wasTouching = touching
        val target = if (touching) 1f else 0f
        val eRate = 1f / (0.008f * sr)
        val tails = type == Fx.GRAINDLY || type == Fx.RESONATOR || type == Fx.MULTIFX || type == Fx.KRISTALL
        val selfHist = type == Fx.PITCH || type == Fx.STRETCH || type == Fx.GRANULAR || type == Fx.SHUFFLE || type == Fx.SEQFX
        if (!tails && !touching && engage < 1e-4f && type != Fx.FREEZE) {
            engage = 0f
            for (i in 0 until n) hist.write(buf[i])
            return
        }
        System.arraycopy(buf, 0, dry, 0, min(n, dry.size))
        if (start) {
            stRp = (hist.w - sr * 0.05)
            frozen = false
            shSlice = Long.MIN_VALUE
        }
        when (type) {
            Fx.PHASER -> phaser(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.TREMOLO -> tremolo(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms)
            Fx.VIBRATO -> vibrato(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.EQ -> eq(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.FILTER -> filter(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.SATURATE -> saturate(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.OVERDRIVE -> overdrive(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.GRAINDLY -> grainDelay(buf, n, target, eRate, mix, p1, p2, sx, sy, bar)
            Fx.PITCH -> pitch(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.STRETCH -> stretch(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.SMEAR, Fx.FREEZE -> spectral(buf, n, target, eRate, mix, p1, p2, sx, sy, type == Fx.FREEZE, touching, start)
            Fx.RINGMOD -> ringmod(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.FM -> fm(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.SHUFFLE -> shuffle(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms)
            Fx.FREQSHIFT -> freqShift(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.RESONATOR -> resonator(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.GRANULAR -> granular(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.SEQFX -> seqFx(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms)
            Fx.MULTIFX -> multiFx(buf, n, target, eRate, mix, p1, p2, sx, sy, bar)
            Fx.KRISTALL -> kristall(buf, n, target, eRate, mix, p1, p2, sx, sy)
        }
        if (!selfHist) for (i in 0 until n) hist.write(dry[i])
    }

    // ------------------------------------------------------------------ Modulation

    private fun phaser(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, fb: Float, stagesK: Float, sx: Float, sy: Float) {
        val rate = 0.05 * 100.0.pow(sx.toDouble())
        val stages = 2 + 2 * round(stagesK * 5f).toInt()
        var coef = 0f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            if (i % 16 == 0) {
                phLfo += rate * 16 / sr
                if (phLfo > 1.0) phLfo -= 1.0
                val l = 0.5f + 0.5f * sin(2 * PI * phLfo).toFloat()
                val fc = min(0.45f * sr, 200f * 20f.pow(0.3f + (l - 0.3f) * sy))
                val t = tan(PI * fc / sr).toFloat()
                coef = (t - 1f) / (t + 1f)
            }
            val d = buf[i]
            var x = d + phFb * fb * 0.9f
            for (s in 0 until stages) {
                val y = coef * x + apS[s]
                apS[s] = x - coef * y
                x = y
            }
            phFb = x
            val wet = 0.5f * (d + x)
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    private fun tremolo(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, form: Float, sync: Float, sx: Float, sy: Float, bar: Long, ms: Long) {
        val divs = doubleArrayOf(1.0 / 2, 1.0 / 4, 1.0 / 8, 1.0 / 16, 1.0 / 32)
        val synced = sync >= 0.5f
        val period = bar * divs[min(4, (sx * 5).toInt())]
        val rate = 0.5 * 30.0.pow(sx.toDouble())
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val ph = if (synced) {
                val x = (frame0 + i - ms).toDouble()
                ((x % period) + period) % period / period
            } else {
                lfoPh += rate / sr
                if (lfoPh > 1.0) lfoPh -= 1.0
                lfoPh
            }
            val l = shape(ph, form)
            val g = 1f - sy * (0.5f - 0.5f * l)
            val d = buf[i]
            buf[i] = d + (d * g - d) * engage * mix
        }
    }

    private fun vibrato(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, form: Float, base: Float, sx: Float, sy: Float) {
        val rate = 0.3 * 40.0.pow(sx.toDouble())
        val depth = sy * 0.006f * sr
        val b = (0.002f + base * 0.01f) * sr
        for (i in 0 until n) {
            stepEngage(target, eRate)
            lfoPh += rate / sr
            if (lfoPh > 1.0) lfoPh -= 1.0
            val d = buf[i]
            vb.write(d)
            val wet = vb.back((b + depth * (0.5f + 0.5f * shape(lfoPh, form))).toDouble())
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    // ------------------------------------------------------------------ EQ / Filter

    private fun eq(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, mid: Float, midF: Float, sx: Float, sy: Float) {
        eqL.lowShelf(120.0, ((sx - 0.5f) * 30f).toDouble(), sr)
        eqH.highShelf(6000.0, ((sy - 0.5f) * 30f).toDouble(), sr)
        eqM.peak(200.0 * 25.0.pow(midF.toDouble()), ((mid - 0.5f) * 30f).toDouble(), 0.8, sr)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            val wet = eqH.tick(eqM.tick(eqL.tick(d)))
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    private fun filter(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, mode: Float, drive: Float, sx: Float, sy: Float) {
        val fc = min(40f * (18000f / 40f).pow(sx), 0.45f * sr)
        val g = tan(PI * fc / sr).toFloat()
        val k = 2f - 1.93f * sy
        val a1 = 1f / (1f + g * (g + k)); val a2 = g * a1; val a3 = g * a2
        val gain = 1f + drive * 7f
        val norm = 1f / tanh(gain)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            val v0 = if (drive < 0.01f) d else tanh(d * gain) * norm
            val v3 = v0 - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            val hp = v0 - k * v1 - v2
            val wet = if (mode < 0.5f) v2 + (v1 - v2) * mode * 2f else v1 + (hp - v1) * (mode - 0.5f) * 2f
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    // ------------------------------------------------------------------ Sättigung

    private fun saturate(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, biasK: Float, level: Float, sx: Float, sy: Float) {
        val drive = 1f + sx * 15f
        val bias = (biasK - 0.5f) * 0.6f
        val off = tanh(drive * bias)
        val norm = 1f / max(0.2f, tanh(drive))
        val a = onePole(18000f * 0.12f.pow(sy))
        val out = 0.4f + level * 0.9f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            val y = (tanh(drive * (d + bias)) - off) * norm
            lp1 += (y - lp1) * a
            buf[i] = d + (lp1 * out - d) * engage * mix
        }
    }

    private fun overdrive(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, bass: Float, level: Float, sx: Float, sy: Float) {
        val drive = 1f + sx * 40f
        val hpA = onePole(400f * 0.1f.pow(bass))
        val lpA = onePole(800f * 20f.pow(sy))
        val out = 0.3f + level * 0.8f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            hp1 += (d - hp1) * hpA
            val v = (d - hp1) * drive
            val y = if (v >= 0f) 1f - exp(-v) else -0.8f * (1f - exp(1.25f * v))
            lp1 += (y - lp1) * lpA
            buf[i] = d + (lp1 * out - d) * engage * mix
        }
    }

    // ------------------------------------------------------------------ Granular

    private fun grainDelay(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, spray: Float, pitchK: Float, sx: Float, sy: Float, bar: Long) {
        val fr = doubleArrayOf(1.0 / 16, 1.0 / 8, 3.0 / 16, 1.0 / 4, 3.0 / 8, 1.0 / 2)
        val gl = (0.09 * sr).toInt()
        var d = bar * fr[min(fr.size - 1, (sx * fr.size).toInt())]
        while (d > dly.size - gl * 3) d /= 2
        val rate = 2.0.pow(semis(pitchK) / 12.0)
        val interval = (0.02 * sr).toInt()
        for (i in 0 until n) {
            stepEngage(target, eRate)
            if (++gCount >= interval) {
                gCount = 0
                val jitter = (rnd.nextDouble() * 2 - 1) * spray * d * 0.3
                spawn(dly, dly.w - d - gl * rate + jitter, rate, gl, 0.45f)
            }
            val g = grainSum()
            val dr = buf[i]
            dly.write(dr * engage + g * sy * 0.9f)
            buf[i] = dr + g * mix
        }
    }

    private fun pitch(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, fb: Float, spray: Float, sx: Float, sy: Float) {
        val r = 2.0.pow(semis(sx) / 12.0)
        val base = (0.03 + sy * 0.12) * sr
        for (i in 0 until n) {
            stepEngage(target, eRate)
            ptPh += (1.0 - r) / ptG
            if (ptPh >= 1.0 || ptPh < 0.0) {
                ptPh = ((ptPh % 1.0) + 1.0) % 1.0
                ptG = base * (1.0 + spray * (rnd.nextDouble() - 0.5))
            }
            val ph2 = (ptPh + 0.5) % 1.0
            val w1 = (1.0 - abs(2 * ptPh - 1)).toFloat()
            val w2 = (1.0 - abs(2 * ph2 - 1)).toFloat()
            val o = hist.back(2 + ptPh * ptG) * w1 + hist.back(2 + ph2 * ptG) * w2
            val d = buf[i]
            hist.write(d + o * fb * 0.8f)
            buf[i] = d + (o - d) * engage * mix
        }
    }

    private fun stretch(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, pitchK: Float, spray: Float, sx: Float, sy: Float) {
        val speed = 0.1 + 0.9 * sx
        val g = ((0.04 + sy * 0.25) * sr).toInt()
        val hopN = max(16, g / 4)
        val rate = 2.0.pow(semis(pitchK) / 12.0)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            hist.write(d)
            stRp += speed
            if (hist.w - stRp > hist.size * 0.8) stRp = hist.w - sr * 0.05
            if (stRp > hist.w - 2 - g * rate) stRp = hist.w - 2 - g * rate
            if (++gCount >= hopN) {
                gCount = 0
                spawn(hist, stRp + (rnd.nextDouble() - 0.5) * spray * g, rate, g, 0.5f)
            }
            val o = grainSum()
            buf[i] = d + (o - d) * engage * mix
        }
    }

    private fun granular(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, spread: Float, sizeK: Float, sx: Float, sy: Float) {
        val look = (0.05 + sx * 1.95) * sr
        val density = 4f + sy * 76f
        val size = ((0.02 + sizeK * 0.28) * sr).toInt()
        val overlap = density * size / sr
        val gain = 0.7f / sqrt(max(1f, overlap))
        val prob = density / sr
        val steps = intArrayOf(-12, -5, 0, 0, 7, 12)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            hist.write(d)
            if (engage > 0.01f && rnd.nextFloat() < prob) {
                val semi = if (rnd.nextFloat() < spread) steps[rnd.nextInt(steps.size)] else 0
                val rate = 2.0.pow(semi / 12.0)
                val pos = hist.w - look - rnd.nextDouble() * look * 0.5 - size * rate
                spawn(hist, pos, rate, size, gain)
            }
            val o = grainSum()
            buf[i] = d + (o - d) * engage * mix
        }
    }

    private fun kristall(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, pitchK: Float, revK: Float, sx: Float, sy: Float) {
        val l = ((0.04 + sx * 0.46) * sr).toInt()
        val r = 2.0.pow(semis(pitchK) / 12.0)
        val interval = max(32, l / 2)
        for (i in 0 until n) {
            stepEngage(target, eRate)
            if (++gCount >= interval) {
                gCount = 0
                if (rnd.nextFloat() < revK) spawn(dly, dly.w - 3.0, -r, l, 0.6f)
                else spawn(dly, dly.w - l * r - 3.0, r, l, 0.6f)
            }
            val g = grainSum()
            val d = buf[i]
            dly.write(d * engage + g * sy * 0.85f)
            buf[i] = d + g * mix
        }
    }

    // ------------------------------------------------------------------ Spektral

    private fun fft(inverse: Boolean) {
        val n = fftN
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = twC[k * step]
                    val wi = if (inverse) twS[k * step] else -twS[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun spectralFrame(freeze: Boolean, touching: Boolean, start: Boolean, sx: Float, sy: Float, p1: Float, p2: Float) {
        for (k in 0 until fftN) {
            re[k] = inFifo[(inPos + k) % fftN] * win[k]
            im[k] = 0f
        }
        fft(false)
        val half = fftN / 2
        val tilt = (sy - 0.5f) * 2f
        for (k in 0..half) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            mag[k] = m
            if (!freeze) phs[k] = kotlin.math.atan2(im[k], re[k])
        }
        if (freeze) {
            if (start || !frozen) {
                System.arraycopy(mag, 0, held, 0, mag.size)
                frozen = true
            } else if (touching) {
                val a = sx * 0.15f
                for (k in 0..half) held[k] = held[k] * (1f - a) + mag[k] * a
            }
        } else {
            val a = 0.5f + 0.49f * sx
            for (k in 0..half) held[k] = held[k] * a + mag[k] * (1f - a)
        }
        for (k in 0..half) {
            var m = held[k] * ((k + 1f) / (half / 8f)).pow(tilt * 0.6f)
            if (freeze && p1 > 0.01f && k >= 2) m += held[k / 2] * p1 * 0.7f
            val ph = if (freeze) (rnd.nextFloat() * 2f * PI.toFloat()) * (0.3f + 0.7f * p2) + phs[k]
            else phs[k] + p1 * PI.toFloat() * (rnd.nextFloat() * 2f - 1f)
            if (freeze) phs[k] = ph
            re[k] = m * cos(ph)
            im[k] = m * sin(ph)
            if (k in 1 until half) {
                re[fftN - k] = re[k]
                im[fftN - k] = -im[k]
            }
        }
        fft(true)
        val scale = 0.5f / fftN
        for (k in 0 until fftN) outAcc[(outPos + k) % fftN] += re[k] * win[k] * scale
    }

    private fun spectral(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, p1: Float, p2: Float, sx: Float, sy: Float, freeze: Boolean, touching: Boolean, start: Boolean) {
        var first = start
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            inFifo[inPos] = d
            inPos = (inPos + 1) % fftN
            val y = outAcc[outPos]
            outAcc[outPos] = 0f
            outPos = (outPos + 1) % fftN
            if (++hopCount >= hop) {
                hopCount = 0
                spectralFrame(freeze, touching, first, sx, sy, p1, p2)
                first = false
            }
            buf[i] = d + (y - d) * engage * mix
        }
        if (!touching && engage < 1e-3f) frozen = false
    }

    // ------------------------------------------------------------------ Ringmod / FM / Shifter

    private fun ringmod(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, lfoK: Float, lfoDepth: Float, sx: Float, sy: Float) {
        val fc = 20.0 * 100.0.pow(sx.toDouble())
        val lr = 0.1 * 100.0.pow(lfoK.toDouble())
        for (i in 0 until n) {
            stepEngage(target, eRate)
            rmLfo += lr / sr
            if (rmLfo > 1.0) rmLfo -= 1.0
            val f = fc * 2.0.pow(lfoDepth * sin(2 * PI * rmLfo))
            rmPh += f / sr
            if (rmPh > 1.0) rmPh -= 1.0
            val s = sin(2 * PI * rmPh).toFloat()
            val car = s + ((if (s >= 0f) 1f else -1f) - s) * sy
            val d = buf[i]
            buf[i] = d + (d * car - d) * engage * mix
        }
    }

    private fun fm(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, fb: Float, tone: Float, sx: Float, sy: Float) {
        val f = 20.0 * 100.0.pow(sx.toDouble())
        val depth = sy * 0.002 * sr
        val a = onePole(600f * 30f.pow(tone))
        for (i in 0 until n) {
            stepEngage(target, eRate)
            rmPh += f / sr
            if (rmPh > 1.0) rmPh -= 1.0
            val d = buf[i]
            fmBuf.write(d + fmFb * fb * 0.7f)
            val o = fmBuf.back(2 + depth * (1 + sin(2 * PI * rmPh)))
            fmFb = o
            lp1 += (o - lp1) * a
            buf[i] = d + (lp1 - d) * engage * mix
        }
    }

    private val hCoefA = floatArrayOf(0.6923878f, 0.9360654f, 0.9882295f, 0.9987488f)
    private val hCoefB = floatArrayOf(0.4021921f, 0.8561711f, 0.9722910f, 0.9952885f)

    /** Allpass-Kette (zweite Ordnung in z^-2) für die Hilbert-Transformation. */
    private fun apChain(x: Float, c: FloatArray, st: FloatArray): Float {
        var v = x
        for (s in 0 until 4) {
            val o = s * 4
            // st: x1, x2, y1, y2
            val y = c[s] * c[s] * (v + st[o + 3]) - st[o + 1]
            st[o + 1] = st[o]; st[o] = v
            st[o + 3] = st[o + 2]; st[o + 2] = y
            v = y
        }
        return v
    }

    private fun freqShift(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, range: Float, tone: Float, sx: Float, sy: Float) {
        val rng = 50.0 * 40.0.pow(range.toDouble())
        val shift = (sx - 0.5f) * 2.0 * rng
        val a = onePole(800f * 25f.pow(tone))
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            val x = d + fsFb * sy * 0.8f
            val iA = apChain(x, hCoefA, hA)
            val q = apChain(x, hCoefB, hB)
            val iD = hDelay
            hDelay = iA
            fsPh += shift / sr
            if (fsPh > 1.0) fsPh -= 1.0
            if (fsPh < 0.0) fsPh += 1.0
            val c = cos(2 * PI * fsPh).toFloat()
            val s = sin(2 * PI * fsPh).toFloat()
            val y = iD * c - q * s
            fsFb = y
            lp1 += (y - lp1) * a
            buf[i] = d + (lp1 - d) * engage * mix
        }
    }

    // ------------------------------------------------------------------ Resonator-Cluster

    private val chords = arrayOf(
        intArrayOf(0, 12, 19, 24, 28, 31), intArrayOf(0, 7, 12, 19, 24, 31), intArrayOf(0, 4, 7, 12, 16, 19),
        intArrayOf(0, 3, 7, 12, 15, 19), intArrayOf(0, 5, 7, 12, 17, 19), intArrayOf(0, 4, 7, 10, 12, 16)
    )

    private fun resonator(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, chordK: Float, bright: Float, sx: Float, sy: Float) {
        val root = 36 + round(sx * 36f).toInt()
        val ch = chords[min(chords.size - 1, (chordK * chords.size).toInt())]
        val q = 5f + sy * 300f
        val k = 1f / q
        val a1 = FloatArray(6); val a2 = FloatArray(6); val a3 = FloatArray(6); val gn = FloatArray(6)
        for (j in 0 until 6) {
            val f = min(0.45 * sr, 440.0 * 2.0.pow((root + ch[j] - 69) / 12.0))
            val g = tan(PI * f / sr).toFloat()
            a1[j] = 1f / (1f + g * (g + k)); a2[j] = g * a1[j]; a3[j] = g * a2[j]
            gn[j] = if (j < 3) 1f else bright * 1.5f
        }
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val d = buf[i]
            val x = d * engage
            var o = 0f
            for (j in 0 until 6) {
                val v3 = x - rs2[j]
                val v1 = a1[j] * rs1[j] + a2[j] * v3
                val v2 = rs2[j] + a2[j] * rs1[j] + a3[j] * v3
                rs1[j] = 2f * v1 - rs1[j]
                rs2[j] = 2f * v2 - rs2[j]
                o += v1 * k * gn[j] * 3f
            }
            buf[i] = d + tanh(o) * mix
        }
    }

    // ------------------------------------------------------------------ Glitch

    private fun shuffle(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, revK: Float, pitchK: Float, sx: Float, sy: Float, bar: Long, ms: Long) {
        val divs = doubleArrayOf(1.0 / 4, 1.0 / 8, 1.0 / 16, 1.0 / 32)
        val slice = max(256L, (bar * divs[min(3, (sx * 4).toInt())]).toLong())
        val maxBack = max(1L, min(8L, (bar * 2) / slice)).toInt()
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val f = frame0 + i
            val d = buf[i]
            hist.write(d)
            val rel = f - ms
            val k = Math.floorDiv(rel, slice)
            val off = rel - k * slice
            if (k != shSlice) {
                shSlice = k
                if (rnd.nextFloat() < sy) {
                    shBack = 1 + rnd.nextInt(maxBack)
                    shRev = rnd.nextFloat() < revK
                    shRate = if (rnd.nextFloat() < pitchK) (if (rnd.nextBoolean()) 0.5 else 2.0) else 1.0
                } else shBack = 0
            }
            var wet = d
            if (shBack > 0) {
                val srcStart = (k - shBack) * slice
                val srcRel = if (shRev) srcStart + slice - 1 - off * shRate else srcStart + off * shRate
                val back = rel - srcRel
                if (back >= 1) {
                    val env = min(1f, min(off / 96f, (slice - off) / 96f))
                    wet = d + (hist.back(back) - d) * env
                }
            }
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    private val patterns = arrayOf(
        intArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0),
        intArrayOf(0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 0, 3, 3, 3),
        intArrayOf(0, 0, 2, 0, 0, 0, 2, 2, 0, 0, 2, 0, 0, 2, 0, 2),
        intArrayOf(4, 4, 4, 4, 0, 0, 0, 0, 4, 4, 4, 4, 0, 0, 0, 0),
        intArrayOf(0, 0, 5, 5, 0, 0, 5, 5, 0, 0, 1, 1, 0, 0, 5, 5),
        intArrayOf(6, 0, 6, 0, 6, 6, 0, 0, 6, 0, 6, 0, 6, 6, 6, 6),
        intArrayOf(1, 1, 1, 1, 2, 2, 2, 2, 3, 0, 3, 0, 5, 5, 5, 5),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 0, 0, 6, 5, 4, 3, 2, 1, 0)
    )

    private fun seqFx(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, rateK: Float, variation: Float, sx: Float, sy: Float, bar: Long, ms: Long) {
        val div = when { rateK < 0.33f -> 8L; rateK < 0.66f -> 16L; else -> 32L }
        val step = max(128L, bar / div)
        val pat = patterns[min(patterns.size - 1, (sx * patterns.size).toInt())]
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val f = frame0 + i
            val d = buf[i]
            hist.write(d)
            val rel = f - ms
            val k = Math.floorDiv(rel, step)
            val off = rel - k * step
            if (k != shSlice) {
                shSlice = k
                sqAction = pat[Math.floorMod(k, 16L).toInt()]
                if (rnd.nextFloat() < variation) sqAction = rnd.nextInt(7)
            }
            val env = min(1f, min(off / 64f, (step - off) / 64f))
            val wetRaw = when (sqAction) {
                1 -> hist.back((step).toDouble())
                2 -> { val b = 2 * off + 1; hist.back(b.toDouble()) }
                3 -> 0f
                4 -> {
                    val c = 8000f * (300f / 8000f).pow(off.toFloat() / step)
                    lp1 += (d - lp1) * onePole(c)
                    lp1
                }
                5 -> hist.back(step + off * 0.5)
                6 -> {
                    if (++sqHoldN >= 6) { sqHoldN = 0; sqHold = round(d * 16f) / 16f }
                    sqHold
                }
                else -> d
            }
            val wet = d + (wetRaw - d) * env * sy.coerceAtLeast(0.15f)
            buf[i] = d + (wet - d) * engage * mix
        }
    }

    private fun multiFx(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, color: Float, room: Float, sx: Float, sy: Float, bar: Long) {
        // Filter-Morph: links Tiefpass, rechts Hochpass, dazu Sättigung zur Seite hin
        val lpMode = sx < 0.5f
        val amt = abs(sx - 0.5f) * 2f
        val fc = if (lpMode) 18000f * 0.02f.pow(amt) else 30f * 200f.pow(amt)
        val g = tan(PI * min(fc, 0.45f * sr) / sr).toFloat()
        val kq = 2f - 1.8f * color
        val a1 = 1f / (1f + g * (g + kq)); val a2 = g * a1; val a3 = g * a2
        val drive = 1f + amt * 6f
        var d = (bar * 3 / 16).toDouble()
        while (d > dly.size - 64) d /= 2
        val fb = sy * 0.7f
        val toneA = onePole(1500f + 12000f * (1f - room))
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            val f = if (lpMode) v2 else x - kq * v1 - v2
            val sat = tanh(f * drive) / tanh(drive)
            lfoPh += 0.4 / sr
            if (lfoPh > 1.0) lfoPh -= 1.0
            val e = dly.back(d + sy * 0.004 * sr * sin(2 * PI * lfoPh))
            mfxLp += (e - mfxLp) * toneA
            dly.write(sat * engage + mfxLp * (fb + room * 0.2f))
            val wet = sat + mfxLp * sy
            buf[i] = x + (wet - x) * engage * mix + if (engage < 0.01f) mfxLp * sy * mix else 0f
        }
    }
}
