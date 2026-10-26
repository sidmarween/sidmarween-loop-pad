package de.marween.looppad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * KI-Stem-Trennung (Spleeter 4 Stems von Deezer, als ONNX): reine Rechnung ohne Android,
 * damit sie auch auf dem Build-Server gegen die Python-Referenz geprüft werden kann.
 *
 * Ablauf je Abschnitt von [T] Rahmen (~12 s): STFT (4096/1024, periodisches Hann, 44,1 kHz, Stereo) →
 * Beträge der unteren [F] Bins → 4 U-Netze (vocals, drums, bass, other) → weiche Masken
 * m_s = (y_s² + ε/4) / (Σ y² + ε) → über L/R energiegewichtet zusammengefasst.
 * Ausgabe je Rahmen: [OUT_BINS] Werte je Stem – Bins paarweise gemittelt (21,5 Hz breit, bis 11 kHz),
 * der letzte Wert ist der Mittelwert des Rahmens (für alles darüber, wie Spleeter es auch macht).
 *
 * Rahmen t ist mittig bei Sample t·HOP − (N_FFT/2 − (N_FFT − HOP)) = t·HOP − 1024 (Vorlauf N_FFT − HOP Nullen).
 */
object SpleeterCore {
    const val SR = 44100
    const val N_FFT = 4096
    const val HOP = 1024
    const val F = 1024
    const val T = 512
    const val PAD = N_FFT - HOP
    /** Rahmen-Mitte liegt bei t·HOP − CENTER_SHIFT Samples. */
    const val CENTER_SHIFT = N_FFT / 2 - PAD
    const val OUT_BINS = F / 2 + 1
    val STEMS = arrayOf("vocals", "drums", "bass", "other")
    private const val EPS = 1e-10f

    /** Ein U-Netz: Eingabe [2, 1, T, F] → Ausgabe gleicher Form (geschätzter Betrag des Stems). */
    fun interface Runner {
        fun run(stem: Int, x: FloatArray): FloatArray
    }

    /** Anzahl Rahmen für [n] Samples. */
    fun frames(n: Int): Int = (n + PAD + HOP - 1) / HOP + 1

    /**
     * Rechnet alle Masken. [sample] (Kanal 0/1, Index bei 44,1 kHz) liefert das Signal (außerhalb 0..n-1 → 0).
     * [out] bekommt je Rahmen t: vocals, drums, bass als [OUT_BINS] Werte 0..1 (other = Rest).
     * [progress] 0..1, [cancel] bricht ab (liefert dann false).
     */
    fun separate(n: Int, sample: (Int, Int) -> Float, runner: Runner, out: (Int, FloatArray, FloatArray, FloatArray) -> Unit,
                 progress: (Float) -> Unit = {}, cancel: () -> Boolean = { false }): Boolean {
        val total = frames(n)
        val splits = (total + T - 1) / T
        val win = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
        val fft = Fft(N_FFT)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val x = FloatArray(2 * T * F)
        // volle Leistung je Kanal/Rahmen/Bin (für die L/R-Gewichtung)
        val pw = FloatArray(2 * T * F)
        val ys = arrayOfNulls<FloatArray>(4)
        val ov = FloatArray(OUT_BINS); val od = FloatArray(OUT_BINS); val ob = FloatArray(OUT_BINS)
        for (s in 0 until splits) {
            if (cancel()) return false
            val t0 = s * T
            for (t in 0 until T) {
                val start = (t0 + t) * HOP - PAD
                for (c in 0..1) {
                    for (i in 0 until N_FFT) {
                        val idx = start + i
                        re[i] = if (idx in 0 until n) sample(c, idx) * win[i] else 0f
                        im[i] = 0f
                    }
                    fft.forward(re, im)
                    val base = (c * T + t) * F
                    for (k in 0 until F) {
                        val p = re[k] * re[k] + im[k] * im[k]
                        pw[base + k] = p
                        x[base + k] = sqrt(p)
                    }
                }
            }
            for (st in 0 until 4) {
                if (cancel()) return false
                ys[st] = runner.run(st, x)
                progress((s + (st + 1) / 4f) / splits)
            }
            val y0 = ys[0]!!; val y1 = ys[1]!!; val y2 = ys[2]!!; val y3 = ys[3]!!
            for (t in 0 until T) {
                val tf = t0 + t
                if (tf >= total) break
                var sv = 0f; var sd = 0f; var sb = 0f; var cnt = 0
                for (j in 0 until OUT_BINS - 1) {
                    var nv = 0f; var nd = 0f; var nb = 0f; var den = 0f
                    for (kk in 0..1) {
                        val k = 2 * j + kk
                        for (c in 0..1) {
                            val i = (c * T + t) * F + k
                            val a0 = y0[i] * y0[i]; val a1 = y1[i] * y1[i]; val a2 = y2[i] * y2[i]; val a3 = y3[i] * y3[i]
                            val inv = 1f / (a0 + a1 + a2 + a3 + EPS)
                            val w = pw[i] + 1e-12f
                            nv += (a0 + EPS / 4) * inv * w
                            nd += (a1 + EPS / 4) * inv * w
                            nb += (a2 + EPS / 4) * inv * w
                            den += w
                        }
                    }
                    val v = nv / den; val d = nd / den; val b = nb / den
                    ov[j] = v; od[j] = d; ob[j] = b
                    sv += v; sd += d; sb += b; cnt++
                }
                ov[OUT_BINS - 1] = sv / cnt; od[OUT_BINS - 1] = sd / cnt; ob[OUT_BINS - 1] = sb / cnt
                out(tf, ov, od, ob)
            }
        }
        progress(1f)
        return true
    }

    /**
     * Fertige Audio-Stems (für Pads): Mono-Signal [x] bei 44,1 kHz → [vocals, drums, bass, other], gleich lang.
     * Masken in voller Auflösung (2049 Bins, oberhalb 11 kHz Rahmen-Mittelwert), Phase vom Original,
     * Rücktransformation mit Overlap-Add. Die vier Stems ergeben zusammen wieder das Original. null = abgebrochen.
     */
    fun separateMono(x: FloatArray, runner: Runner, progress: (Float) -> Unit = {}, cancel: () -> Boolean = { false }): Array<FloatArray>? {
        val n = x.size
        val total = frames(n)
        val splits = (total + T - 1) / T
        val bins = N_FFT / 2 + 1
        val win = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
        val norm = 1f / 1.5f / N_FFT // Σ hann² bei 75 % Überlappung = 1,5; inverse FFT / N
        val fft = Fft(N_FFT)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val inp = FloatArray(2 * T * F)
        val sRe = Array(T) { FloatArray(bins) }
        val sIm = Array(T) { FloatArray(bins) }
        val outs = Array(4) { FloatArray(n) }
        val m = Array(4) { FloatArray(bins) }
        for (s in 0 until splits) {
            if (cancel()) return null
            val t0 = s * T
            for (t in 0 until T) {
                val start = (t0 + t) * HOP - PAD
                for (i in 0 until N_FFT) {
                    val idx = start + i
                    re[i] = if (idx in 0 until n) x[idx] * win[i] else 0f
                    im[i] = 0f
                }
                fft.forward(re, im)
                System.arraycopy(re, 0, sRe[t], 0, bins)
                System.arraycopy(im, 0, sIm[t], 0, bins)
                for (k in 0 until F) {
                    val a = sqrt(re[k] * re[k] + im[k] * im[k])
                    inp[t * F + k] = a
                    inp[(T + t) * F + k] = a
                }
            }
            val ys = arrayOfNulls<FloatArray>(4)
            for (st in 0..3) {
                if (cancel()) return null
                ys[st] = runner.run(st, inp)
                progress((s + (st + 1) * 0.2f) / splits)
            }
            for (t in 0 until T) {
                val tf = t0 + t
                if (tf >= total) break
                val sums = FloatArray(4)
                for (k in 0 until F) {
                    // beide Kanäle bekamen dasselbe Signal → Schätzungen mitteln
                    val i0 = t * F + k
                    val i1 = (T + t) * F + k
                    var den = EPS
                    for (st in 0..3) {
                        val ya = ys[st]!!
                        val y = (ya[i0] + ya[i1]) * 0.5f
                        val a = y * y
                        m[st][k] = a
                        den += a
                    }
                    for (st in 0..3) { val v = (m[st][k] + EPS / 4) / den; m[st][k] = v; sums[st] += v }
                }
                for (st in 0..3) {
                    val mean = sums[st] / F
                    for (k in F until bins) m[st][k] = mean
                }
                val start = tf * HOP - PAD
                val rr = sRe[t]; val ii = sIm[t]
                for (st in 0..3) {
                    val mk = m[st]
                    // Spektrum × Maske, konjugiert-symmetrisch ergänzen, inverse FFT über konjugieren
                    for (k in 0 until bins) { re[k] = rr[k] * mk[k]; im[k] = -ii[k] * mk[k] }
                    for (k in 1 until N_FFT / 2) { re[N_FFT - k] = re[k]; im[N_FFT - k] = -im[k] }
                    fft.forward(re, im)
                    val o = outs[st]
                    for (i in 0 until N_FFT) {
                        val idx = start + i
                        if (idx in 0 until n) o[idx] += re[i] * win[i] * norm
                    }
                }
            }
            progress((s + 1f) / splits)
        }
        return outs
    }

    /** Radix-2-FFT mit Tabellen (vorwärts, an Ort und Stelle). */
    class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        private val sinT = FloatArray(n / 2) { (-sin(2 * PI * it / n)).toFloat() }
        private val rev = IntArray(n)

        init {
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun forward(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var i = 0
                while (i < n) {
                    var tw = 0
                    for (k in 0 until half) {
                        val wr = cosT[tw]; val wi = sinT[tw]
                        val a = i + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        tw += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    /** Lineare Abtastraten-Wandlung beim Lesen: Index bei 44,1 kHz → Quelle. */
    fun resampledReader(srcRate: Int, len: Int, get: (Int, Int) -> Float): Pair<Int, (Int, Int) -> Float> {
        if (srcRate == SR) return len to get
        val step = srcRate.toDouble() / SR
        val n = ((len - 1) / step).toInt()
        return n to { c, i ->
            val p = i * step
            val i0 = p.toInt()
            val fr = (p - i0).toFloat()
            val a = get(c, i0)
            val b = if (i0 + 1 < len) get(c, i0 + 1) else a
            a + (b - a) * fr
        }
    }

    @Suppress("unused")
    private fun clamp01(v: Float) = max(0f, min(1f, v))
}
