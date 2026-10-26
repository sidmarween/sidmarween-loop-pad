package de.marween.looppad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stems ohne KI: Harmonisch/Perkussiv-Trennung (HPSS, Fitzgerald 2010) im Spektrum.
 * Perkussiv = kurze, breitbandige Ereignisse (Drums), harmonisch = gehaltene Töne; der harmonische Teil
 * wird in Bass (< ~150 Hz) und Melodie/Gesang geteilt. Die drei Spuren ergeben zusammen wieder das Original.
 */
object Stems {
    val NAMES = arrayOf("DRUMS", "BASS", "MELODIE & GESANG")
    private const val N = 2048
    private const val HOP = N / 4
    private const val LH = 8   // ± Rahmen für den waagerechten Median (gehaltene Töne)
    /** Im Bass länger hinschauen (± ~220 ms): eine Kick ist kurz, ein Basston lang – so landet die Kick bei den Drums. */
    private const val LH_LOW = 20
    private const val LV = 8   // ± Bins für den senkrechten Median (Anschläge)
    private const val CHUNK = 192

    /** Liefert [drums, bass, melodie]. [progress] 0..1. Rechnet auf mehreren Kernen. */
    fun split(x: FloatArray, sr: Int, progress: (Float) -> Unit = {}): Array<FloatArray> {
        val n = x.size
        val bins = N / 2 + 1
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val frames = (n + N) / HOP + 1
        val outs = arrayOf(FloatArray(n), FloatArray(n), FloatArray(n))
        // Bass-Grenze: voll bis 120 Hz, weich bis 200 Hz
        val bassW = FloatArray(bins) { k ->
            val f = k.toDouble() * sr / N
            when { f <= 120.0 -> 1f; f >= 200.0 -> 0f; else -> (1.0 - (f - 120.0) / 80.0).toFloat() }
        }
        val lhOf = IntArray(bins) { k -> val f = k.toDouble() * sr / N; if (f < 250.0) LH_LOW else LH }
        val chunks = (frames + CHUNK - 1) / CHUNK
        val done = java.util.concurrent.atomic.AtomicInteger()
        val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() - 1))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        for (ci in 0 until chunks) pool.execute {
            try {
                chunk(x, n, ci * CHUNK, min(frames, (ci + 1) * CHUNK), bins, win, bassW, lhOf, outs)
            } catch (t: Throwable) { errors.set(t) }
            progress(done.incrementAndGet().toFloat() / chunks)
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
        errors.get()?.let { throw it }
        return outs
    }

    private fun chunk(x: FloatArray, n: Int, c0: Int, c1: Int, bins: Int, win: FloatArray, bassW: FloatArray, lhOf: IntArray, outs: Array<FloatArray>) {
        val re = FloatArray(N)
        val im = FloatArray(N)
        val tmp = FloatArray(2 * max(LH_LOW, LV) + 1)
        val masks = Array(3) { FloatArray(bins) }
        val norm = 1.5f // Summe von hann² bei 75 % Überlappung
        val f0 = c0 - LH_LOW
        val f1 = c1 + LH_LOW
        val nf = f1 - f0
        val mag = Array(nf) { FloatArray(bins) }
        val specRe = Array(c1 - c0) { FloatArray(bins) }
        val specIm = Array(c1 - c0) { FloatArray(bins) }
        for (fi in 0 until nf) {
            val f = f0 + fi
            val start = f * HOP - N / 2
            for (i in 0 until N) {
                val idx = start + i
                re[i] = if (idx in 0 until n) x[idx] * win[i] else 0f
                im[i] = 0f
            }
            Keys.fft(re, im)
            val m = mag[fi]
            for (k in 0 until bins) m[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            if (f in c0 until c1) {
                System.arraycopy(re, 0, specRe[f - c0], 0, bins)
                System.arraycopy(im, 0, specIm[f - c0], 0, bins)
            }
        }
        // eigener Ausgabe-Bereich, am Ende auf einmal addiert (Überlappung mit Nachbar-Stücken)
        val regStart = c0 * HOP - N / 2
        val regLen = (c1 - c0 - 1) * HOP + N
        val loc = Array(3) { FloatArray(regLen) }
        for (f in c0 until c1) {
            val fi = f - f0
            val sr0 = specRe[f - c0]
            val si0 = specIm[f - c0]
            val row = mag[fi]
            for (k in 0 until bins) {
                // waagerecht (Zeit): gehaltene Töne
                val lh = lhOf[k]
                var cnt = 0
                for (t in fi - lh..fi + lh) tmp[cnt++] = mag[t][k]
                val h = median(tmp, cnt)
                val low = lh == LH_LOW
                // senkrecht (Frequenz): Anschläge – im Bass: was über dem Dauerpegel liegt (Kick ist schmalbandig)
                val p = if (low) max(0f, row[k] - h) else {
                    cnt = 0
                    for (q in max(0, k - LV)..min(bins - 1, k + LV)) tmp[cnt++] = row[q]
                    median(tmp, cnt)
                }
                // im Bass schärfer trennen (Kick und Basston liegen in denselben Bins)
                val h2 = if (low) h * h * h * h else h * h
                val p2 = if (low) p * p * p * p else p * p
                val den = h2 + p2 + 1e-20f
                val mp = p2 / den
                val mh = 1f - mp
                val mb = mh * bassW[k]
                masks[0][k] = mp; masks[1][k] = mb; masks[2][k] = mh - mb
            }
            val off = f * HOP - N / 2 - regStart
            for (which in 0 until 3) {
                val mask = masks[which]
                for (k in 0 until bins) { re[k] = sr0[k] * mask[k]; im[k] = si0[k] * mask[k] }
                for (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
                // inverse FFT: konjugieren → FFT → Realteil
                for (i in 0 until N) im[i] = -im[i]
                Keys.fft(re, im)
                val out = loc[which]
                for (i in 0 until N) out[off + i] += re[i] / N * win[i] / norm
            }
        }
        synchronized(outs) {
            for (which in 0 until 3) {
                val g = outs[which]
                val l = loc[which]
                for (i in 0 until regLen) {
                    val idx = regStart + i
                    if (idx in 0 until n) g[idx] += l[i]
                }
            }
        }
    }

    /** Median der ersten [n] Werte (Einfügesortierung – n ≤ 41). */
    private fun median(a: FloatArray, n: Int): Float {
        for (i in 1 until n) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
            a[j + 1] = v
        }
        return a[n / 2]
    }
}
