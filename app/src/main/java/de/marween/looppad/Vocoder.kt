package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🤖 VOCODER: Mikrofon (Modulator) steuert einen Synth-Akkord (Träger) über 16 Bänder – Roboter-/Daft-Punk-Stimme.
 * Träger-Töne: eigene Akkord-Tasten, oder er folgt dem CHORD-PAD. ROBOTER = immer ein fester Ton.
 */
object Vocoder {
    @Volatile var on = false
    @Volatile var carrier = 0          // 0 SÄGE-AKKORD, 1 PULS, 2 SUPERSAW, 3 ORGEL
    val CARRIERS = arrayOf("SÄGE", "PULS", "SUPERSAW", "ORGEL")
    @Volatile var formant = 0.5f       // 0..1 → 0,6×..1,7× (Stimme tiefer/höher)
    @Volatile var sibilance = 0.35f    // Rauschen für S/T/K
    @Volatile var dry = 0f
    @Volatile var level = 0.8f
    @Volatile var release = 0.35f
    @Volatile var robot = false
    @Volatile var followChords = true
    @Volatile var meter = 0f
    /** gehaltene Träger-Töne (MIDI), max. 6 */
    val notes = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val bandLevel = FloatArray(16)

    private const val N = 16
    private var sr0 = 0
    private val fc = DoubleArray(N)
    private val mLp = FloatArray(N); private val mBp = FloatArray(N)
    private val cLp = FloatArray(N); private val cBp = FloatArray(N)
    private val env = FloatArray(N)
    private val gM = FloatArray(N); private val gC = FloatArray(N)
    private var lastFormant = -1f
    private val ph = DoubleArray(24)
    private var rnd = 4711

    private fun setup(sr: Int) {
        sr0 = sr
        for (k in 0 until N) fc[k] = 110.0 * (7500.0 / 110.0).pow(k / (N - 1.0))
        for (k in 0 until N) gM[k] = (2 * sin(PI * min(fc[k], sr / 6.0) / sr)).toFloat()
        lastFormant = -1f
    }

    fun active(): Boolean = on || meter > 0.001f

    /** Träger-Töne der Stimme (MIDI): Robot → fester Ton, sonst Chord-Pad oder eigene Tasten. */
    private fun carrierNotes(): IntArray {
        if (robot) return intArrayOf(48 + (if (InstState.scaleType != 0) InstState.scaleRoot else 9))
        if (followChords && ChordPad.cur.isNotEmpty()) return ChordPad.cur.toIntArray()
        return notes.toIntArray()
    }

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, mic: FloatArray?) {
        if (sr0 != sr) setup(sr)
        if (lastFormant != formant) {
            val sh = 0.6 + formant * 1.1
            for (k in 0 until N) gC[k] = (2 * sin(PI * min(fc[k] * sh, sr / 6.0) / sr)).toFloat()
            lastFormant = formant
        }
        val nts = if (on) carrierNotes() else IntArray(0)
        val rel = exp(-1.0 / ((0.01 + release * 0.25) * sr)).toFloat()
        val att = exp(-1.0 / (0.002 * sr)).toFloat()
        val q = 0.12f
        var pk = 0f
        for (i in 0 until n) {
            val m = if (on && mic != null) mic[i] * 3f else 0f
            // Träger
            var c = 0f
            for ((j, nn) in nts.withIndex()) {
                if (j >= 6) break
                val f = 440.0 * 2.0.pow((nn - 69) / 12.0)
                when (carrier) {
                    1 -> { ph[j] += f / sr; if (ph[j] >= 1.0) ph[j] -= 1.0; c += if (ph[j] < 0.4) 1f else -1f }
                    2 -> for (d in 0 until 3) { val idx = 6 + j * 3 + d; if (idx >= ph.size) break; ph[idx] += f * (1 + (d - 1) * 0.008) / sr; if (ph[idx] >= 1.0) ph[idx] -= 1.0; c += ((2 * ph[idx] - 1) * 0.6).toFloat() }
                    3 -> { ph[j] += f / sr; if (ph[j] >= 1.0) ph[j] -= 1.0; c += (sin(2 * PI * ph[j]) + 0.6 * sin(4 * PI * ph[j]) + 0.4 * sin(6 * PI * ph[j])).toFloat() * 0.7f }
                    else -> { ph[j] += f / sr; if (ph[j] >= 1.0) ph[j] -= 1.0; c += (2 * ph[j] - 1).toFloat() }
                }
            }
            if (nts.isNotEmpty()) c /= kotlin.math.sqrt(nts.size.toFloat())
            rnd = rnd * 1664525 + 1013904223
            val nz = ((rnd ushr 8) / 16777216f) * 2f - 1f
            var outL = 0f; var outR = 0f
            for (k in 0 until N) {
                // Analyse
                mLp[k] += gM[k] * mBp[k]; val hm = m - mLp[k] - q * mBp[k]; mBp[k] += gM[k] * hm
                val a = abs(mBp[k])
                env[k] = if (a > env[k]) a + (env[k] - a) * att else env[k] * rel
                // Synthese: obere Bänder bekommen Rauschen (S, T, K)
                val src = if (k >= N - 4) c * (1f - sibilance) + nz * sibilance * 2f else c
                cLp[k] += gC[k] * cBp[k]; val hc = src - cLp[k] - q * cBp[k]; cBp[k] += gC[k] * hc
                val y = cBp[k] * env[k] * 0.15f
                if (k % 2 == 0) { outL += y; outR += y * 0.7f } else { outL += y * 0.7f; outR += y }
            }
            val g = level
            val yl = tanh((outL * g + m * dry * 0.3f).toDouble()).toFloat()
            val yr = tanh((outR * g + m * dry * 0.3f).toDouble()).toFloat()
            oL[i] += yl; oR[i] += yr
            val aa = abs(yl)
            if (aa > pk) pk = aa
        }
        for (k in 0 until N) bandLevel[k] = (env[k] * 8f).coerceIn(0f, 1f)
        meter = max(pk, meter * 0.9f)
    }
}
