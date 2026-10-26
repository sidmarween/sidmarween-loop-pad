package de.marween.looppad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stems live im DJ-Deck: VOCALS, MELODIE, BASS, DRUMS – je Deck stufenlos regelbar, während der Track läuft.
 *
 * Zwei Teile:
 *  1. [analyze] rechnet beim Laden (im Hintergrund) eine kompakte „Stem-Karte“: für jeden Zeitpunkt (~21 ms) und
 *     jedes von [BANDS] Frequenzbändern, welcher Anteil Drums, Bass bzw. Gesang ist. Drums = kurze breitbandige
 *     Ereignisse (HPSS), Bass = gehaltene Töne unter ~150 Hz, Gesang = gehaltene Töne im Stimmbereich, die in der
 *     Stereo-Mitte stehen; der Rest ist Melodie. Ein 5-Minuten-Song braucht so nur ~5 MB statt 4 Audiospuren.
 *  2. [Live] filtert den laufenden Deck-Ton (nach Tempo/Pitch/Scratch) im Spektrum mit den Reglern – deshalb klappt
 *     es auch mit Keylock, Loops und Scratchen. Feste Verzögerung [LAT] Samples (~21 ms), immer gleich, damit
 *     beide Decks im Takt bleiben.
 *
 * Ehrlich: das ist keine KI-Trennung wie bei Serato/rekordbox (die brauchen große neuronale Modelle). Drums und Bass
 * trennen gut; Gesang vs. Melodie hängt davon ab, wie mittig die Stimme gemischt ist.
 */
object DeckStems {
    val NAMES = arrayOf("VOCALS", "MELODIE", "BASS", "DRUMS")
    /** Indizes der Regler (Deck.stemGain): */
    const val VOC = 0
    const val MEL = 1
    const val BASS = 2
    const val DRUMS = 3

    const val BANDS = 128
    /** Kartenauflösung in Quell-Frames. */
    const val HOP = 1024
    private const val N = 2048
    private const val LH = 4       // ± Rahmen waagerecht (≈ ±85 ms)
    private const val LH_LOW = 10  // im Bass länger (≈ ±210 ms): Kick kurz, Basston lang
    private const val LV = 8       // ± Bins senkrecht
    private const val CHUNK = 128

    /** Stem-Karte: [frames] × [BANDS] × 3 Bytes (Drums, Bass, Vocals; 0..255 = Anteil). Melodie = Rest. */
    class Mask(val frames: Int, val hop: Double, val data: ByteArray) {
        /** Nach dem Warpen (Track gedehnt um [r]). */
        fun scaled(r: Double) = Mask(frames, hop * r, data)
    }

    /** Frequenz → Band (log. 40 Hz … 18 kHz, darunter Band 0). */
    fun bandOf(f: Double): Int {
        if (f <= 40.0) return 0
        val b = 1 + ((ln(f / 40.0) / ln(18000.0 / 40.0)) * (BANDS - 2)).toInt()
        return min(BANDS - 1, b)
    }

    /**
     * Rechnet die Stem-Karte für ein Deck-Audio (16 Bit, [ch] Kanäle verschachtelt). [progress] 0..1.
     * Läuft auf bis zu 3 Kernen mit niedriger Priorität, damit der Ton nicht stottert.
     */
    fun analyze(data: ShortArray, ch: Int, len: Int, sr: Int, cancel: () -> Boolean, progress: (Float) -> Unit): Mask? {
        val bins = N / 2 + 1
        val frames = len / HOP + 1
        val out = ByteArray(frames * BANDS * 3)
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val bandOfBin = IntArray(bins) { k -> bandOf(k.toDouble() * sr / N) }
        val bassW = FloatArray(bins) { k ->
            val f = k.toDouble() * sr / N
            when { f <= 110.0 -> 1f; f >= 190.0 -> 0f; else -> (1.0 - (f - 110.0) / 80.0).toFloat() }
        }
        // Stimmbereich mit weichen Kanten (Grundton ~180 Hz bis Präsenz ~6 kHz)
        val vocW = FloatArray(bins) { k ->
            val f = k.toDouble() * sr / N
            when {
                f < 150.0 -> 0f
                f < 260.0 -> ((f - 150.0) / 110.0).toFloat()
                f <= 5000.0 -> 1f
                f < 9000.0 -> (1.0 - (f - 5000.0) / 4000.0).toFloat()
                else -> 0f
            }
        }
        val low = BooleanArray(bins) { k -> k.toDouble() * sr / N < 250.0 }
        val chunks = (frames + CHUNK - 1) / CHUNK
        val done = java.util.concurrent.atomic.AtomicInteger()
        val threads = min(3, max(1, Runtime.getRuntime().availableProcessors() - 2))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }
        }
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        for (ci in 0 until chunks) pool.execute {
            if (cancel() || err.get() != null) return@execute
            try {
                chunk(data, ch, len, ci * CHUNK, min(frames, (ci + 1) * CHUNK), bins, win, bassW, vocW, low, bandOfBin, out)
            } catch (t: Throwable) { err.set(t) }
            progress(done.incrementAndGet().toFloat() / chunks)
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
        err.get()?.let { throw it }
        if (cancel()) return null
        return Mask(frames, HOP.toDouble(), out)
    }

    private fun chunk(data: ShortArray, ch: Int, len: Int, c0: Int, c1: Int, bins: Int, win: FloatArray,
                      bassW: FloatArray, vocW: FloatArray, low: BooleanArray, bandOfBin: IntArray, out: ByteArray) {
        val re = FloatArray(N)
        val im = FloatArray(N)
        val tmp = FloatArray(2 * max(LH_LOW, LV) + 1)
        val f0 = c0 - LH_LOW
        val f1 = c1 + LH_LOW
        val nf = f1 - f0
        val magM = Array(nf) { FloatArray(bins) }
        val cen = Array(c1 - c0) { FloatArray(bins) }
        for (fi in 0 until nf) {
            val f = f0 + fi
            val start = f * HOP - N / 2
            for (i in 0 until N) {
                val idx = start + i
                if (idx in 0 until len) {
                    val l = data[idx * ch] / 32768f
                    val r = if (ch == 2) data[idx * 2 + 1] / 32768f else l
                    re[i] = (l + r) * 0.5f * win[i]
                    im[i] = (l - r) * 0.5f * win[i]
                } else { re[i] = 0f; im[i] = 0f }
            }
            Keys.fft(re, im)
            // Mitte (M) und Seite (S) aus einer komplexen FFT trennen
            val m = magM[fi]
            val inside = f in c0 until c1
            for (k in 0 until bins) {
                val nk = (N - k) and (N - 1)
                val mr = (re[k] + re[nk]) * 0.5f
                val mi = (im[k] - im[nk]) * 0.5f
                val sr = (im[k] + im[nk]) * 0.5f
                val si = (re[nk] - re[k]) * 0.5f
                val mm = mr * mr + mi * mi
                val ss = sr * sr + si * si
                m[k] = sqrt(mm + ss)
                if (inside) {
                    // Stereo-Mitte: 1 = nur Mitte, 0 = breit/seitlich
                    val c = mm / (mm + 3f * ss + 1e-12f)
                    cen[f - c0][k] = c * c
                }
            }
        }
        val eTot = FloatArray(BANDS)
        val eD = FloatArray(BANDS)
        val eB = FloatArray(BANDS)
        val eV = FloatArray(BANDS)
        for (f in c0 until c1) {
            val fi = f - f0
            val row = magM[fi]
            java.util.Arrays.fill(eTot, 0f); java.util.Arrays.fill(eD, 0f); java.util.Arrays.fill(eB, 0f); java.util.Arrays.fill(eV, 0f)
            val cf = cen[f - c0]
            for (k in 1 until bins) {
                val lo = low[k]
                val lh = if (lo) LH_LOW else LH
                var cnt = 0
                for (t in fi - lh..fi + lh) tmp[cnt++] = magM[t][k]
                val h = median(tmp, cnt)
                val p = if (lo) max(0f, row[k] - h) else {
                    cnt = 0
                    for (q in max(1, k - LV)..min(bins - 1, k + LV)) tmp[cnt++] = row[q]
                    median(tmp, cnt)
                }
                val h2 = if (lo) h * h * h * h else h * h
                val p2 = if (lo) p * p * p * p else p * p
                val mp = p2 / (h2 + p2 + 1e-24f)
                val mh = 1f - mp
                val mb = mh * bassW[k]
                val mv = (mh - mb) * vocW[k] * cf[k]
                val e = row[k] * row[k] + 1e-14f
                val b = bandOfBin[k]
                eTot[b] += e
                eD[b] += mp * e
                eB[b] += mb * e
                eV[b] += mv * e
            }
            val o = f * BANDS * 3
            var lastD = 0; var lastB = 0; var lastV = 0
            for (b in 0 until BANDS) {
                if (eTot[b] > 0f) {
                    lastD = (eD[b] / eTot[b] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    lastB = (eB[b] / eTot[b] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    lastV = (eV[b] / eTot[b] * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
                // Bänder ohne eigenen Bin (ganz unten) erben vom Nachbarn
                out[o + b * 3] = lastD.toByte()
                out[o + b * 3 + 1] = lastB.toByte()
                out[o + b * 3 + 2] = lastV.toByte()
            }
            // Band 0 (< 40 Hz) leer? → von Band 1 nehmen
            if (eTot[0] <= 0f) for (s in 0..2) out[o + s] = out[o + 3 + s]
        }
    }

    private fun median(a: FloatArray, n: Int): Float {
        for (i in 1 until n) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
            a[j + 1] = v
        }
        return a[n / 2]
    }

    // ================================================================ Live-Filter (Mischer-Thread)

    private const val LN = 1024
    private const val LHOP = LN / 4
    private const val FIFO = LN - LHOP
    /** Feste Verzögerung in Ausgabe-Samples (gemessen: genau ein Fenster). */
    const val LAT = LN

    class Live {
        private val win = FloatArray(LN) { sqrt(0.5 - 0.5 * cos(2 * PI * it / LN)).toFloat() }
        private val inL = FloatArray(LN)
        private val inR = FloatArray(LN)
        private val inPos = DoubleArray(LN)
        private val outL = FloatArray(LHOP)
        private val outR = FloatArray(LHOP)
        private val accL = FloatArray(2 * LN)
        private val accR = FloatArray(2 * LN)
        private var rover = FIFO
        private val re = FloatArray(LN)
        private val im = FloatArray(LN)
        private val gainBin = FloatArray(LN / 2 + 1)
        /** Geglättete Regler (wirken pro Rahmen, ~5 ms). */
        private val g = floatArrayOf(1f, 1f, 1f, 1f)
        private val cosT = FloatArray(LN / 2)
        private val sinT = FloatArray(LN / 2)
        private val rev = IntArray(LN)

        init {
            for (i in 0 until LN / 2) { cosT[i] = cos(2 * PI * i / LN).toFloat(); sinT[i] = -sin(2 * PI * i / LN).toFloat() }
            var bits = 0
            while ((1 shl bits) < LN) bits++
            for (i in 0 until LN) rev[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun reset() {
            java.util.Arrays.fill(inL, 0f); java.util.Arrays.fill(inR, 0f)
            java.util.Arrays.fill(accL, 0f); java.util.Arrays.fill(accR, 0f)
            java.util.Arrays.fill(outL, 0f); java.util.Arrays.fill(outR, 0f)
            rover = FIFO
        }

        /**
         * Filtert [n] Samples an Ort und Stelle (Ausgabe um [LAT] verzögert).
         * [p0]/[p1]: Quellposition am Anfang/Ende des Blocks, [pf]: Tonhöhen-Faktor (Ausgabe/Quelle),
         * [target]: Reglerstände (VOC, MEL, BASS, DRUMS) 0..1.
         */
        fun process(l: FloatArray, r: FloatArray, n: Int, p0: Double, p1: Double, pf: Double, sr: Int, mask: Mask?, target: FloatArray) {
            for (i in 0 until n) {
                inL[rover] = l[i]
                inR[rover] = r[i]
                inPos[rover] = p0 + (p1 - p0) * i / n
                val oi = rover - FIFO
                l[i] = outL[oi]
                r[i] = outR[oi]
                rover++
                if (rover >= LN) {
                    rover = FIFO
                    frame(pf, sr, mask, target)
                }
            }
        }

        private fun frame(pf: Double, sr: Int, mask: Mask?, target: FloatArray) {
            var unity = true
            for (s in 0..3) {
                g[s] += (target[s] - g[s]) * 0.35f
                if (abs1(target[s] - g[s]) < 0.002f) g[s] = target[s]
                if (g[s] < 0.999f) unity = false
            }
            if (unity || mask == null) {
                // durchlassen (gleiche Verzögerung)
                for (i in 0 until LN) {
                    val w = win[i] * win[i] * 0.5f
                    accL[i] += inL[i] * w
                    accR[i] += inR[i] * w
                }
            } else {
                // Karte an der Quellposition der Rahmen-Mitte
                val pc = inPos[LN / 2]
                val fi = (pc / mask.hop + 0.5).toInt().coerceIn(0, mask.frames - 1)
                val base = fi * BANDS * 3
                val md = mask.data
                val binHz = sr.toDouble() / LN / max(0.25, pf)
                var lastBand = -1
                var gb = 1f
                for (k in 0..LN / 2) {
                    val b = bandOf(k * binHz)
                    if (b != lastBand) {
                        lastBand = b
                        val o = base + b * 3
                        val d = (md[o].toInt() and 255) / 255f
                        val bs = (md[o + 1].toInt() and 255) / 255f
                        val v = (md[o + 2].toInt() and 255) / 255f
                        val m = max(0f, 1f - d - bs - v)
                        gb = g[DRUMS] * d + g[BASS] * bs + g[VOC] * v + g[MEL] * m
                    }
                    gainBin[k] = gb
                }
                for (i in 0 until LN) { re[rev[i]] = inL[i] * win[i]; im[rev[i]] = inR[i] * win[i] }
                fftCore(re, im)
                // reelle, symmetrische Verstärkung → L (Real) und R (Imaginär) bleiben getrennt
                for (k in 0 until LN) {
                    val gk = gainBin[if (k <= LN / 2) k else LN - k]
                    re[k] *= gk
                    im[k] = -im[k] * gk   // konjugieren für die Rück-FFT
                }
                // Bit-Umkehr für die Rück-FFT
                for (i in 0 until LN) {
                    val j = rev[i]
                    if (i < j) {
                        var t = re[i]; re[i] = re[j]; re[j] = t
                        t = im[i]; im[i] = im[j]; im[j] = t
                    }
                }
                fftCore(re, im)
                val sc = 0.5f / LN
                for (i in 0 until LN) {
                    val w = win[i] * sc
                    accL[i] += re[i] * w
                    accR[i] -= im[i] * w
                }
            }
            System.arraycopy(accL, 0, outL, 0, LHOP)
            System.arraycopy(accR, 0, outR, 0, LHOP)
            System.arraycopy(accL, LHOP, accL, 0, LN)
            System.arraycopy(accR, LHOP, accR, 0, LN)
            java.util.Arrays.fill(accL, LN, 2 * LN, 0f)
            java.util.Arrays.fill(accR, LN, 2 * LN, 0f)
            System.arraycopy(inL, LHOP, inL, 0, FIFO)
            System.arraycopy(inR, LHOP, inR, 0, FIFO)
            System.arraycopy(inPos, LHOP, inPos, 0, FIFO)
        }

        private fun abs1(x: Float) = if (x < 0) -x else x

        /** FFT-Schmetterlinge (Eingang schon in Bit-Umkehr-Reihenfolge). */
        private fun fftCore(re: FloatArray, im: FloatArray) {
            var len = 2
            while (len <= LN) {
                val half = len / 2
                val step = LN / len
                var i = 0
                while (i < LN) {
                    var t = 0
                    for (k in 0 until half) {
                        val wr = cosT[t]; val wi = sinT[t]
                        val a = i + k
                        val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        t += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
