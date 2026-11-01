package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wavetables für den SYNTH (Klänge 20–24): je Bank 16 Wellenformen, zwischen denen stufenlos überblendet wird.
 * Jede Form liegt bandbegrenzt in 9 Stufen vor (512 … 2 Obertöne) – so klingt sie auch hoch gespielt ohne Aliasing.
 * Banken: ANALOG (Säge → Rechteck → Puls), VOCAL (Vokale A-E-I-O-U), GROWL (FM-Knurren), DIGITAL (PPG-artig),
 * SAMPLE (aus einem eigenen Sample, Schlüssel „wt:0“ in [UserSamples]).
 */
object Wavetables {
    const val N = 2048
    const val FRAMES = 16
    const val LEVELS = 9
    val NAMES = arrayOf("ANALOG", "VOCAL", "GROWL", "DIGITAL", "SAMPLE")

    /** [bank][frame][level] → Tabelle (N+1 Werte, letzter = erster). */
    private val tables = arrayOfNulls<Array<Array<FloatArray>>>(NAMES.size)
    private val building = BooleanArray(NAMES.size)

    fun ready(bank: Int): Array<Array<FloatArray>>? {
        val t = tables[bank]
        if (t == null) ensure(bank)
        return t
    }

    @Synchronized fun ensure(bank: Int) {
        if (bank !in NAMES.indices || tables[bank] != null || building[bank]) return
        building[bank] = true
        Thread {
            try { tables[bank] = build(bank) } catch (ignored: Throwable) {}
            synchronized(this) { building[bank] = false }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Eigenes Sample wurde geändert: Bank SAMPLE neu bauen. */
    fun sampleChanged() { tables[4] = null; ensure(4) }

    /** Obertöne (Amplitude, Phase) einer Form – bis 512. */
    private fun spectrum(bank: Int, f: Int): Pair<FloatArray, FloatArray> {
        val a = FloatArray(513)
        val ph = FloatArray(513)
        val x = f / (FRAMES - 1f)
        when (bank) {
            0 -> {
                // Säge → Rechteck → schmaler Puls
                for (k in 1..512) {
                    val saw = 1f / k
                    val sq = if (k % 2 == 1) 1f / k else 0f
                    val pw = 0.5f - 0.45f * ((x - 0.5f) * 2f).coerceIn(0f, 1f)
                    val pulse = (2f / (PI.toFloat() * k)) * abs(sin(PI * k * pw).toFloat())
                    a[k] = when {
                        x < 0.5f -> saw + (sq - saw) * (x * 2f)
                        else -> pulse * 1.2f
                    }
                }
            }
            1 -> {
                // Vokale: Formanten (Hz) A, E, I, O, U bei einer Grundfrequenz von 130 Hz
                val fm = arrayOf(floatArrayOf(800f, 1150f, 2900f), floatArrayOf(400f, 1600f, 2700f), floatArrayOf(300f, 2200f, 3000f), floatArrayOf(450f, 800f, 2830f), floatArrayOf(325f, 700f, 2530f))
                val pos = x * 4f
                val i0 = pos.toInt().coerceIn(0, 3)
                val fr = pos - i0
                val amp = floatArrayOf(1f, 0.6f, 0.35f)
                for (k in 1..512) {
                    val hz = 130f * k
                    var s = 0f
                    for (j in 0 until 3) {
                        val fc = fm[i0][j] + (fm[i0 + 1][j] - fm[i0][j]) * fr
                        val bw = 80f + fc * 0.06f
                        val dd = (hz - fc) / bw
                        s += amp[j] * exp(-dd * dd)
                    }
                    a[k] = (s + 0.02f / k) / sqrt(k.toFloat())
                }
            }
            2, 4 -> {
                // aus einer Wellenform im Zeitbereich (Growl: FM mit wachsendem Index und Faltung; Sample: Ausschnitt)
                val w = FloatArray(N)
                if (bank == 2) {
                    val idx = 0.3 + x * 6.0
                    val fold = 1.0 + x * 2.5
                    for (n in 0 until N) {
                        val t = 2 * PI * n / N
                        var v = sin(t + idx * sin(2 * t) + 0.5 * x * sin(3 * t))
                        v = sin(v * fold * PI / 2)
                        w[n] = v.toFloat()
                    }
                } else {
                    val smp = UserSamples.get("wt:0")
                    if (smp == null || smp.size < N) return spectrum(0, f)
                    // 16 gleichmäßig verteilte Ausschnitte, weich gefenstert und als ein Zyklus gelesen
                    val start = ((smp.size - N) * x).toInt()
                    for (n in 0 until N) w[n] = smp[start + n] * (0.5f - 0.5f * cos(2 * PI * n / N).toFloat())
                }
                for (k in 1..256) {
                    var re = 0.0; var im = 0.0
                    for (n in 0 until N step 2) {
                        val t = 2 * PI * k * n / N
                        re += w[n] * cos(t); im += w[n] * sin(t)
                    }
                    a[k] = (sqrt(re * re + im * im) * 4.0 / N).toFloat()
                    ph[k] = atan2(re, im).toFloat()
                }
            }
            else -> {
                // DIGITAL: Obertongruppen wandern, harte Sprünge wie bei PPG-Wellen
                val r = java.util.Random(91L + f * 7L)
                val center = 2 + (x * 40).toInt()
                for (k in 1..512) {
                    val near = abs(k - center)
                    var v = if (near < 3 + f / 3) 1f / (1 + near) else 0f
                    if (k == 1) v += 0.7f
                    if (k % (2 + f % 5) == 0) v += 0.35f / sqrt(k.toFloat())
                    if (k < 64 && r.nextFloat() < 0.15f) v += 0.2f * r.nextFloat()
                    a[k] = v / (1f + k / 64f)
                }
            }
        }
        return a to ph
    }

    private fun build(bank: Int): Array<Array<FloatArray>> {
        val sinT = FloatArray(N) { sin(2 * PI * it / N).toFloat() }
        return Array(FRAMES) { f ->
            val (a, ph) = spectrum(bank, f)
            Array(LEVELS) { l ->
                val hMax = 512 shr l
                val t = FloatArray(N + 1)
                for (k in 1..hMax) {
                    val ak = a[k]
                    if (ak < 1e-5f) continue
                    val p0 = ((ph[k] / (2 * PI) * N).toInt() % N + N) % N
                    var idx = p0
                    for (n in 0 until N) {
                        t[n] += ak * sinT[idx]
                        idx += k; if (idx >= N) idx -= N * (idx / N)
                    }
                }
                t
            }.also { lv ->
                // gemeinsame Normierung je Form (alle Stufen gleich laut)
                var pk = 1e-6f
                for (v in lv[0]) pk = maxOf(pk, abs(v))
                val g = 0.9f / pk
                for (t in lv) { for (n in 0 until N) t[n] *= g; t[N] = t[0] }
            }
        }
    }

    /** Stufe passend zur Frequenz (höchster Oberton unter ~0,45 · Abtastrate). */
    fun level(freq: Double, sr: Int): Int {
        val maxH = 0.45 * sr / freq
        var l = 0
        while (l < LEVELS - 1 && (512 shr l) > maxH) l++
        return l
    }
}
