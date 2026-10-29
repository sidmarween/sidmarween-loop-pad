package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Neue Kaoss-Effekte: Tape-Echo und Space-Echo (Zeit + Pitch per Wischen), Looper, Slicer, Reverse
 * sowie Vocal-Effekte: Harmonizer, Autotune, Roboter.
 */
class Fx3(private val sr: Int) {
    private var engage = 0f
    private var wasTouching = false

    // Verlauf (für Looper/Reverse), absolute Schreibposition
    private val hSize = 1 shl 19
    private val hMask = hSize - 1
    private val hist = FloatArray(hSize)
    private var hW = 0L

    // Tape/Space-Echo
    private val dSize = 1 shl 18
    private val dMask = dSize - 1
    private val dl = FloatArray(dSize)
    private var dW = 0
    private var dT = sr * 0.3
    private var wowPh = 0.0
    private var flPh = 0.0
    private var lp = 0f
    private var hp = 0f
    private val shA = PitchHead(sr, 50f)
    private val shB = PitchHead(sr, 50f)
    private val shC = PitchHead(sr, 40f)
    /** Federhall-artiger kleiner Plate-Hall für das Space-Echo. */
    private val verb = Plate(sr, 0.4f)

    // Beat Mutation
    private val mut = BeatMutation(sr)
    private var mutDry = FloatArray(0)

    // Looper
    private var loop = FloatArray(0)
    private var loopLen = 0
    private var loopPos = 0.0

    // Slicer
    private var gate = 0f

    // Autotune
    private val dec = FloatArray(1024)
    private var decW = 0
    private var decAcc = 0f
    private var decN = 0
    private var hop = 0
    private var detHz = 0f
    private var ratio = 1.0
    private var ratioT = 1.0

    // Roboter
    private val cbSize = sr / 30 + 8
    private val cb = FloatArray(cbSize)
    private var cbW = 0
    private var rmPh = 0.0
    private var crHold = 0f
    private var crCnt = 0f

    fun reset() {
        engage = 0f
        java.util.Arrays.fill(dl, 0f)
        lp = 0f; hp = 0f
        shA.reset(); shB.reset(); shC.reset()
        verb.reset()
        loopLen = 0
        gate = 0f
        java.util.Arrays.fill(cb, 0f)
        ratio = 1.0; ratioT = 1.0; detHz = 0f
        mut.reset()
    }

    private fun stepEngage(target: Float, rate: Float) {
        if (engage < target) engage = min(target, engage + rate) else if (engage > target) engage = max(target, engage - rate)
    }

    private fun hRead(pos: Double): Float {
        val i0 = floor(pos).toLong()
        val fr = (pos - i0).toFloat()
        val a = hist[(i0 and hMask.toLong()).toInt()]
        val b = hist[((i0 + 1) and hMask.toLong()).toInt()]
        return a + (b - a) * fr
    }

    fun process(
        buf: FloatArray, n: Int, frame0: Long, type: Int, sx: Float, sy: Float, touching: Boolean,
        mix: Float, p1: Float, p2: Float, bar: Long, ms: Long
    ) {
        val start = touching && !wasTouching
        wasTouching = touching
        val target = if (touching) 1f else 0f
        val eRate = 1f / (0.008f * sr)
        val tails = type == Fx.TAPEECHO || type == Fx.SPACEECHO || type == Fx.HARMONIZER
        if (!tails && !touching && engage < 1e-4f) {
            engage = 0f
            for (i in 0 until n) { hist[(hW and hMask.toLong()).toInt()] = buf[i]; hW++ }
            // Beat Mutation hört auch inaktiv mit (Takt-Analyse), damit es beim Antippen sofort losgeht
            if (type == Fx.MUTATION) mut.feed(buf, n, frame0, bar, ms)
            return
        }
        when (type) {
            Fx.TAPEECHO -> tapeEcho(buf, n, target, eRate, mix, p1, p2, sx, sy, bar, false)
            Fx.SPACEECHO -> tapeEcho(buf, n, target, eRate, mix, p1, p2, sx, sy, bar, true)
            Fx.LOOPER -> looper(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms, start)
            Fx.SLICER -> slicer(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms)
            Fx.REVERSE -> reverse(buf, n, frame0, target, eRate, mix, p1, p2, sx, sy, bar, ms)
            Fx.HARMONIZER -> harmonizer(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.AUTOTUNE -> autotune(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.ROBOT -> robot(buf, n, target, eRate, mix, p1, p2, sx, sy)
            Fx.MUTATION -> {
                if (mutDry.size < n) mutDry = FloatArray(n)
                System.arraycopy(buf, 0, mutDry, 0, n)
                mut.process(buf, n, frame0, bar, ms, sx, sy, p1, p2)
                for (i in 0 until n) {
                    stepEngage(target, eRate)
                    val d = mutDry[i]
                    buf[i] = d + (buf[i] - d) * engage * mix
                }
            }
        }
    }

    // ------------------------------------------------------------------ TAPE ECHO / SPACE ECHO

    /** Zeit (X) mit Band-Trägheit: Wischen lässt die Wiederholungen hörbar hoch/runter gleiten. Y = Tonhöhe der Wiederholungen. */
    private fun tapeEcho(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, fb: Float, p2: Float, sx: Float, sy: Float, bar: Long, space: Boolean) {
        val tT = if (space) (0.06 + sx * 0.34) * sr else {
            // im Takt einrastende Zeiten, wenn P2 > 0.5 (SYNC), sonst stufenlos 30 ms … 1,2 s
            if (p2 > 0.5f) {
                val opts = doubleArrayOf(1 / 32.0, 1 / 16.0, 3 / 32.0, 1 / 8.0, 3 / 16.0, 1 / 4.0, 3 / 8.0, 1 / 2.0)
                opts[min(opts.size - 1, (sx * opts.size).toInt())] * bar
            } else (0.03 + sx * sx * 1.17) * sr
        }
        val inertia = 1.0 - exp(-1.0 / (0.18 * sr))
        val semi = if (space) 0.0 else round((sy - 0.5) * 24.0)
        val pr = 2.0.pow(semi / 12.0)
        val feedback = if (space) 0.25f + sy * 0.75f else min(1.08f, fb * 1.1f)
        val wowDepth = if (space) 0.0015 else if (p2 <= 0.5f) p2 * 0.012 else 0.002
        val toneC = (1.0 - exp(-2.0 * PI * (if (space) 1500.0 + p2 * 5000.0 else 3200.0) / sr)).toFloat()
        val hpC = (1.0 - exp(-2.0 * PI * 90.0 / sr)).toFloat()
        val verbAmt = if (space) fb else 0f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            hist[(hW and hMask.toLong()).toInt()] = x
            hW++
            dT += (tT - dT) * inertia
            wowPh += 0.6 / sr
            flPh += 7.3 / sr
            val mod = 1.0 + wowDepth * sin(2 * PI * wowPh) + wowDepth * 0.3 * sin(2 * PI * flPh)
            var wet: Float
            if (space) {
                // drei Tonköpfe (1×, 2×, 3× Grundzeit) wie beim Space-Echo
                val d1 = dT * mod
                wet = readD(d1) * 0.55f + readD(d1 * 2) * 0.35f + readD(d1 * 3) * 0.25f
            } else {
                wet = readD(dT * mod)
                if (abs(semi) > 0.1) wet = shA.tick(wet, pr)
            }
            lp += toneC * (wet - lp)
            hp += hpC * (lp - hp)
            val fbSig = tanh((lp - hp) * feedback * 1.2f) / 1.2f
            dl[dW and dMask] = x * engage + fbSig
            dW++
            var out = lp - hp
            if (space && verbAmt > 0.01f) out += verb.tick(out * 0.5f + x * engage * 0.2f, 0.72f, 0.45f, 0.6f) * verbAmt * 0.9f
            buf[i] = x + out * mix
        }
    }

    private fun readD(d: Double): Float {
        val p = dW - 1 - d
        val i0 = floor(p).toInt()
        val fr = (p - i0).toFloat()
        return FxDsp.hermite(dl[(i0 - 1) and dMask], dl[i0 and dMask], dl[(i0 + 1) and dMask], dl[(i0 + 2) and dMask], fr)
    }

    // ------------------------------------------------------------------ LOOPER

    private fun loopLenFor(sx: Float, bar: Long): Int {
        val opts = doubleArrayOf(1 / 16.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1.0, 2.0)
        return max(64L, (opts[min(opts.size - 1, (sx * opts.size).toInt())] * bar).toLong()).toInt()
    }

    /** Fängt beim Antippen die letzten Takte ein und spielt sie als Loop: X = Länge, Y = Tempo/Rückwärts. */
    private fun looper(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, decay: Float, overdub: Float, sx: Float, sy: Float, bar: Long, ms: Long, start: Boolean) {
        val maxLen = min(hSize / 2, (2 * bar).toInt().coerceAtLeast(sr))
        if (start || loop.size < maxLen) {
            if (loop.size < maxLen) loop = FloatArray(maxLen)
            // letzte maxLen Frames einfangen
            val from = hW - maxLen
            for (k in 0 until maxLen) loop[k] = hist[((from + k) and hMask.toLong()).toInt()]
            loopLen = maxLen
            loopPos = 0.0
        }
        val l = min(loopLenFor(sx, bar), loopLen)
        val off = loopLen - l
        // Y: unten rückwärts, Mitte normal, oben doppelt
        val speed = when {
            sy < 0.2f -> -1.0
            sy < 0.35f -> -0.5
            sy < 0.65f -> 1.0
            sy < 0.85f -> 1.5
            else -> 2.0
        }
        val fade = min(96, l / 4)
        val keep = 1f - decay * 0.002f
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            hist[(hW and hMask.toLong()).toInt()] = x
            hW++
            if (engage <= 0f || loopLen == 0) continue
            loopPos += speed
            if (loopPos >= l) loopPos -= l
            if (loopPos < 0) loopPos += l
            val p = loopPos
            val i0 = p.toInt().coerceIn(0, l - 1)
            var v = loop[off + i0]
            // weiche Nahtstelle
            val edge = min(i0, l - 1 - i0)
            if (edge < fade) v *= edge / fade.toFloat()
            if (overdub > 0.01f) loop[off + i0] = loop[off + i0] * keep + x * overdub * 0.5f
            else if (decay > 0.01f) loop[off + i0] *= keep
            buf[i] = x * (1f - engage * mix) + v * engage * mix
        }
    }

    // ------------------------------------------------------------------ SLICER

    private val PATTERNS = arrayOf(
        intArrayOf(1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0),
        intArrayOf(1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0),
        intArrayOf(1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0),
        intArrayOf(1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0),
        intArrayOf(1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 1),
        intArrayOf(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0, 0, 1, 0, 1, 1),
        intArrayOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0)
    )

    /** Rhythmisches Zerschneiden im Takt: X = Muster, Y = Rate (1/8 … 1/32). */
    private fun slicer(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, depth: Float, smooth: Float, sx: Float, sy: Float, bar: Long, ms: Long) {
        val pat = PATTERNS[min(PATTERNS.size - 1, (sx * PATTERNS.size).toInt())]
        val div = when { sy < 0.33f -> 8; sy < 0.66f -> 16; else -> 32 }
        val step = max(1L, bar / div)
        val sm = (1.0 - exp(-1.0 / ((0.001 + smooth * 0.012) * sr))).toFloat()
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            hist[(hW and hMask.toLong()).toInt()] = x
            hW++
            val f = frame0 + i - ms
            val k = Math.floorMod(Math.floorDiv(f, step), 16L).toInt()
            val inStep = Math.floorMod(f, step).toFloat() / step
            val on = pat[k] == 1 && inStep < 0.85f
            gate += ((if (on) 1f else 0f) - gate) * sm
            val g = 1f - depth.coerceAtLeast(0.2f) * (1f - gate)
            val v = x * g
            buf[i] = x + (v - x) * engage * mix
        }
    }

    // ------------------------------------------------------------------ REVERSE

    /** Spielt im Takt jeweils das vorige Stück rückwärts: X = Länge (1/16 … 1 Takt), Y = Echo. */
    private fun reverse(buf: FloatArray, n: Int, frame0: Long, target: Float, eRate: Float, mix: Float, smooth: Float, tone: Float, sx: Float, sy: Float, bar: Long, ms: Long) {
        val opts = doubleArrayOf(1 / 16.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1.0)
        val l = max(256L, (opts[min(opts.size - 1, (sx * opts.size).toInt())] * bar).toLong())
        val fade = max(32L, (l * (0.02 + smooth * 0.2)).toLong())
        val fb = sy * 0.7f
        val toneC = (1.0 - exp(-2.0 * PI * (800.0 + tone * 15000.0) / sr)).toFloat()
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            val f = frame0 + i - ms
            val o = Math.floorMod(f, l)
            // Ende des vorigen Stücks liegt o+1 Frames zurück; rückwärts lesen
            val back = o + 1 + o
            val p = hW - back
            var v = hRead(p.toDouble())
            val edge = min(o, l - 1 - o)
            if (edge < fade) v *= edge / fade.toFloat()
            lp += toneC * (v - lp)
            v = lp
            hist[(hW and hMask.toLong()).toInt()] = x + v * fb * engage
            hW++
            buf[i] = x * (1f - engage * mix) + v * engage * mix
        }
    }

    // ------------------------------------------------------------------ HARMONIZER

    private val CHORDS = arrayOf(
        intArrayOf(4, 7), intArrayOf(3, 7), intArrayOf(5, 12), intArrayOf(7, 12),
        intArrayOf(-12, 12), intArrayOf(4, 9), intArrayOf(-5, 7), intArrayOf(12, 19)
    )
    val CHORD_NAMES = arrayOf("DUR", "MOLL", "QUARTE+OKT", "QUINTE+OKT", "OKT ±", "SEXTE", "QUINTE ±", "OKT+DUODEZ")

    /** Zwei zusätzliche Stimmen im Intervall (X), Lautstärke der Stimmen (Y). */
    private fun harmonizer(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, detune: Float, octave: Float, sx: Float, sy: Float) {
        val ch = CHORDS[min(CHORDS.size - 1, (sx * CHORDS.size).toInt())]
        val oct = when { octave < 0.33f -> -12; octave < 0.66f -> 0; else -> 12 }
        val det = detune * 0.25
        val r1 = 2.0.pow((ch[0] + oct + det) / 12.0)
        val r2 = 2.0.pow((ch[1] + oct - det) / 12.0)
        val lvl = sy
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            val a = shA.tick(x, r1)
            val b = shB.tick(x, r2)
            buf[i] = x + (a + b) * 0.6f * lvl * engage * mix
        }
    }

    // ------------------------------------------------------------------ AUTOTUNE

    private val SCALES = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        intArrayOf(0, 2, 4, 5, 7, 9, 11),
        intArrayOf(0, 2, 3, 5, 7, 8, 10),
        intArrayOf(0, 3, 5, 7, 10)
    )
    val SCALE_NAMES = arrayOf("CHROM.", "DUR", "MOLL", "PENTA")
    val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")

    /** Erkennt die gesungene Tonhöhe und zieht sie auf die nächste Note der Tonart (X). Y = Geschwindigkeit (oben = Roboter-Effekt). */
    private fun autotune(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, scaleV: Float, transp: Float, sx: Float, sy: Float) {
        val key = min(11, (sx * 12f).toInt())
        val scale = SCALES[min(SCALES.size - 1, (scaleV * SCALES.size).toInt())]
        val tr = round((transp - 0.5f) * 24f).toInt()
        // Y oben = sofort (hart), unten = weich
        val speed = 1.0 - exp(-1.0 / ((0.002 + (1 - sy) * (1 - sy) * 0.15) * sr))
        val dsr = sr / 4
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            // Analyse auf 1/4 der Abtastrate
            decAcc += x
            if (++decN == 4) {
                dec[decW] = decAcc / 4f
                decW = (decW + 1) and 1023
                decAcc = 0f
                decN = 0
                if (++hop >= 64) {
                    hop = 0
                    detHz = detect(dsr)
                    if (detHz > 0f) {
                        val midi = 69.0 + 12.0 * ln(detHz / 440.0) / ln(2.0)
                        var best = scale[0]
                        var bd = 99.0
                        for (s in scale) for (o in -1..1) {
                            val cand = s + o * 12
                            val d = abs((midi - key - 12 * floor((midi - key) / 12.0)) - cand)
                            if (d < bd) { bd = d; best = cand }
                        }
                        val base = key + 12 * floor((midi - key) / 12.0) + best
                        ratioT = 2.0.pow((base - midi + tr) / 12.0)
                    } else ratioT = 2.0.pow(tr / 12.0)
                }
            }
            ratio += (ratioT - ratio) * speed
            val v = shC.tick(x, ratio)
            buf[i] = x * (1f - engage * mix) + v * engage * mix
        }
    }

    private val yin = FloatArray(700)

    /** Grundton (YIN) aus dem dezimierten Puffer, 0 = keiner erkannt. */
    private fun detect(dsr: Int): Float {
        val w = 256
        val minLag = max(2, dsr / 900)
        val maxLag = min(yin.size - 2, dsr / 70)
        var energy = 0f
        for (k in 0 until w) { val v = dec[(decW - 1 - k) and 1023]; energy += v * v }
        if (energy < 1e-4f) return 0f
        var run = 0f
        for (lag in 1..maxLag) {
            var d = 0f
            for (k in 0 until w) {
                val df = dec[(decW - 1 - k) and 1023] - dec[(decW - 1 - k - lag) and 1023]
                d += df * df
            }
            run += d
            yin[lag] = if (run > 0f) d * lag / run else 1f
        }
        var lag = minLag
        while (lag < maxLag) {
            if (yin[lag] < 0.15f) {
                while (lag + 1 < maxLag && yin[lag + 1] < yin[lag]) lag++
                // parabolische Verfeinerung
                val y0 = yin[lag - 1]; val y1 = yin[lag]; val y2 = yin[lag + 1]
                val den = y0 - 2 * y1 + y2
                val l = if (abs(den) > 1e-9f) lag + 0.5f * (y0 - y2) / den else lag.toFloat()
                return dsr / l
            }
            lag++
        }
        return 0f
    }

    // ------------------------------------------------------------------ ROBOTER

    /** Metallische Roboterstimme: Kammfilter + Ringmodulation auf Tonhöhe X, Y = Metall-Anteil. */
    private fun robot(buf: FloatArray, n: Int, target: Float, eRate: Float, mix: Float, bits: Float, tone: Float, sx: Float, sy: Float) {
        val f = 40.0 * 10.0.pow(sx * 1.0)          // 40 … 400 Hz
        val d = (sr / f).coerceIn(2.0, cbSize - 2.0)
        val fb = 0.55f + sy * 0.42f
        val rmInc = f / sr
        val holdN = 1f + bits * 12f
        val toneC = (1.0 - exp(-2.0 * PI * (1200.0 + tone * 8000.0) / sr)).toFloat()
        for (i in 0 until n) {
            stepEngage(target, eRate)
            val x = buf[i]
            val p = cbW - d
            val i0 = floor(p).toInt()
            val fr = (p - i0).toFloat()
            val a = cb[Math.floorMod(i0, cbSize)]
            val b = cb[Math.floorMod(i0 + 1, cbSize)]
            val del = a + (b - a) * fr
            val c = tanh(x + del * fb)
            cb[Math.floorMod(cbW, cbSize)] = c
            cbW = (cbW + 1) % cbSize
            rmPh += rmInc
            if (rmPh >= 1.0) rmPh -= 1.0
            var v = c * (1f - sy * 0.6f) + c * sin(2 * PI * rmPh).toFloat() * sy * 0.9f
            // leichte Bit-/Rate-Reduktion für den Digitalklang
            crCnt += 1f
            if (crCnt >= holdN) { crCnt -= holdN; crHold = v }
            v = crHold
            lp += toneC * (v - lp)
            buf[i] = x * (1f - engage * mix) + lp * engage * mix
        }
    }
}
