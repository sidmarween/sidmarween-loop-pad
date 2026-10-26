package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mikrofon-Werkzeuge (ohne Android, daher testbar):
 *  - [Yin]: Tonhöhe (Grundfrequenz) eines Ausschnitts
 *  - [hum]: Summen/Singen → Noten (Tonhöhe, Anfang, Länge)
 *  - [beatbox]: Beatbox → Kick / Snare / Hi-Hat
 *  - [chords]: Akkord je Abschnitt (Folge dem Song)
 *  - [AutoTune]: Echtzeit-Tonhöhenkorrektur fürs Live-Mikro
 */
object MicTools {

    fun midiOf(hz: Double) = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
    fun hzOf(midi: Double) = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0)

    /**
     * YIN (de Cheveigné & Kawahara 2002): Grundfrequenz in Hz von x[off until off+win] oder -1 (stimmlos).
     * [tmp] Arbeitsfeld ≥ maxLag+1.
     */
    class Yin(val sr: Int, val win: Int, fMin: Double = 70.0, fMax: Double = 1100.0, private val thresh: Float = 0.15f) {
        private val minLag = max(2, (sr / fMax).toInt())
        val maxLag = min(win - 2, (sr / fMin).toInt())
        private val d = FloatArray(maxLag + 2)
        /** Güte der letzten Schätzung (0..1, 1 = sehr periodisch). */
        var clarity = 0f
            private set

        fun pitch(x: FloatArray, off: Int, len: Int = win): Double {
            val w = min(len, win)
            if (off < 0 || off + w + maxLag + 1 > x.size) return -1.0
            // Differenzfunktion
            d[0] = 0f
            for (tau in 1..maxLag) {
                var s = 0f
                var i = off
                val e = off + w
                while (i < e) {
                    val q = x[i] - x[i + tau]
                    s += q * q
                    i++
                }
                d[tau] = s
            }
            // kumulativ normiert
            var run = 0f
            d[0] = 1f
            for (tau in 1..maxLag) {
                run += d[tau]
                d[tau] = if (run > 0f) d[tau] * tau / run else 1f
            }
            var tau = minLag
            var found = -1
            while (tau < maxLag) {
                if (d[tau] < thresh) {
                    while (tau + 1 < maxLag && d[tau + 1] < d[tau]) tau++
                    found = tau
                    break
                }
                tau++
            }
            if (found < 0) {
                // kein klarer Treffer: globales Minimum, nur wenn ordentlich
                var best = minLag
                for (t in minLag until maxLag) if (d[t] < d[best]) best = t
                if (d[best] > 0.35f) { clarity = 1f - d[best]; return -1.0 }
                found = best
            }
            clarity = 1f - d[found]
            // parabolische Verfeinerung
            val t0 = found
            val a = d[max(1, t0 - 1)]
            val b = d[t0]
            val c = d[min(maxLag, t0 + 1)]
            val den = a - 2 * b + c
            val sh = if (abs(den) > 1e-9f) 0.5f * (a - c) / den else 0f
            return sr / (t0 + sh.coerceIn(-1f, 1f)).toDouble()
        }
    }

    // ---------------------------------------------------------------- Summen → Noten

    /** Eine erkannte Note: Anfang/Ende in Samples, MIDI-Tonhöhe (gerundet), Lautstärke 0..1. */
    class Note(val start: Int, val end: Int, val midi: Int, val vel: Float)

    /**
     * Wandelt gesummtes/gesungenes Audio in Noten. Tonhöhe alle 10 ms (YIN), Noten = zusammenhängende
     * stimmhafte Abschnitte; ein Tonwechsel um mehr als ¾ Halbton (≥ 50 ms lang) beginnt eine neue Note,
     * ebenso ein neuer Anschlag (Lautstärke steigt deutlich). [snap] rundet auf erlaubte Töne (Tonleiter).
     */
    fun hum(x: FloatArray, sr: Int, snap: (Int) -> Int = { it }): List<Note> {
        val hop = sr / 100
        val win = (sr * 0.04).toInt()
        val yin = Yin(sr, win, 75.0, 1000.0, 0.2f)
        val frames = (x.size - win - yin.maxLag - 2) / hop
        if (frames < 5) return emptyList()
        val p = DoubleArray(frames) { Double.NaN }
        val rms = FloatArray(frames)
        var peakRms = 1e-6f
        for (f in 0 until frames) {
            val o = f * hop
            var s = 0f
            for (i in o until o + win) s += x[i] * x[i]
            rms[f] = sqrt(s / win)
            if (rms[f] > peakRms) peakRms = rms[f]
        }
        val gate = max(peakRms * 0.08f, 0.004f)
        for (f in 0 until frames) {
            if (rms[f] < gate) continue
            val hz = yin.pitch(x, f * hop)
            if (hz > 0 && yin.clarity > 0.6f) p[f] = midiOf(hz)
        }
        // Ausreißer (Oktavsprünge einzelner Rahmen) glätten: Median über 5
        val sm = DoubleArray(frames) { Double.NaN }
        val buf = DoubleArray(5)
        for (f in 0 until frames) {
            if (p[f].isNaN()) continue
            var c = 0
            for (k in max(0, f - 2)..min(frames - 1, f + 2)) if (!p[k].isNaN()) buf[c++] = p[k]
            java.util.Arrays.sort(buf, 0, c)
            sm[f] = buf[c / 2]
        }
        val notes = ArrayList<Note>()
        var f = 0
        while (f < frames) {
            if (sm[f].isNaN()) { f++; continue }
            val st = f
            var ref = sm[f]
            val vals = ArrayList<Double>()
            var g = f
            var peak = 0f
            while (g < frames && !sm[g].isNaN()) {
                // neuer Anschlag: Lautstärke springt nach einer Senke kräftig an
                if (g > st + 4 && rms[g] > rms[g - 3] * 2.2f && rms[g - 3] < peak * 0.5f) break
                if (abs(sm[g] - ref) > 0.75) {
                    // hält der neue Ton ≥ 5 Rahmen? dann neue Note
                    var k = g
                    var stable = 0
                    while (k < frames && !sm[k].isNaN() && abs(sm[k] - sm[g]) < 0.6 && stable < 5) { stable++; k++ }
                    if (stable >= 5 && g - st >= 5) break
                }
                vals.add(sm[g])
                if (g - st < 8) ref = vals.sorted()[vals.size / 2]
                if (rms[g] > peak) peak = rms[g]
                g++
            }
            if (g - st >= 6 && vals.isNotEmpty()) {
                val med = vals.sorted()[vals.size / 2]
                val vel = (0.45f + 0.55f * (20 * log10(peak / peakRms + 1e-6f) + 24f) / 24f).coerceIn(0.3f, 1f)
                notes.add(Note(st * hop, g * hop + win / 2, snap(med.roundToInt()), vel))
            }
            f = max(g, st + 1)
        }
        return notes
    }

    // ---------------------------------------------------------------- Beatbox → Drums

    /** Ein Schlag: Sample-Position, Art (0 = Kick, 1 = Snare, 2 = Hi-Hat), Stärke 0..1. */
    class Hit(val at: Int, val kind: Int, val vel: Float)

    /**
     * Anschläge per Spektralfluss finden und nach Klang einordnen:
     * Kick = tief (viel Energie < 200 Hz, niedriger Schwerpunkt), Hi-Hat = sehr hell (Schwerpunkt > ~5 kHz,
     * kaum Tiefen), Snare = dazwischen (breites Rauschen, „k“/„pf“/„psch“).
     */
    fun beatbox(x: FloatArray, sr: Int): List<Hit> {
        val n = 1024
        val hop = 128
        val frames = (x.size - n) / hop
        if (frames < 4) return emptyList()
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val fft = SpleeterCore.Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val bins = n / 2
        var prev = FloatArray(bins)
        val flux = FloatArray(frames)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            val o = f * hop
            for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0f }
            fft.forward(re, im)
            val mag = FloatArray(bins)
            var fl = 0f
            var e = 0f
            for (k in 1 until bins) {
                val m = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]))
                mag[k] = m
                val d = m - prev[k]
                if (d > 0) fl += d
                e += re[k] * re[k] + im[k] * im[k]
            }
            flux[f] = fl
            energy[f] = e
            prev = mag
        }
        // adaptiver Schwellwert: gleitender Median + Abstand
        var maxE = 1e-9f
        for (e in energy) if (e > maxE) maxE = e
        val hits = ArrayList<Hit>()
        val minGap = (0.07 * sr / hop).toInt()
        var last = -minGap
        val med = FloatArray(frames)
        val tmp = FloatArray(25)
        for (f in 0 until frames) {
            var c = 0
            for (k in max(0, f - 12)..min(frames - 1, f + 12)) tmp[c++] = flux[k]
            java.util.Arrays.sort(tmp, 0, c)
            med[f] = tmp[c / 2]
        }
        var maxF = 1e-9f
        for (v in flux) if (v > maxF) maxF = v
        for (f in 1 until frames - 1) {
            val th = med[f] * 1.5f + maxF * 0.12f
            if (flux[f] > th && flux[f] >= flux[f - 1] && flux[f] >= flux[f + 1] && f - last >= minGap && energy[min(frames - 1, f + 2)] > maxE * 0.003f) {
                last = f
                val at = f * hop + n / 2 - hop
                hits.add(Hit(max(0, at), 0, 0f))
            }
        }
        // Klang: Merkmale je Anschlag, dann Kick = tief; Rest nach Helligkeit in Snare/Hi-Hat teilen –
        // an der größten Lücke (passt sich an Mund und Mikro an), sonst feste Grenze
        val feats = hits.map { features(x, max(0, it.at - hop), sr) }
        val kind = IntArray(hits.size) { i -> val f = feats[i]; if (f[0] > 0.4 || f[2] < 500) 0 else 1 }
        val rest = hits.indices.filter { kind[it] != 0 }.sortedBy { feats[it][2] }
        if (rest.isNotEmpty()) {
            val lc = rest.map { ln(feats[it][2]) }
            var gapAt = -1
            var gap = 0.0
            for (i in 1 until lc.size) if (lc[i] - lc[i - 1] > gap) { gap = lc[i] - lc[i - 1]; gapAt = i }
            if (gap > ln(1.6) && feats[rest.last()][2] > 3500) {
                for (i in gapAt until rest.size) kind[rest[i]] = 2
            } else for (i in rest) if (feats[i][2] > 8000 || feats[i][1] > 0.6) kind[i] = 2
        }
        // Stärke aus dem Pegel kurz nach dem Anschlag
        val out = ArrayList<Hit>()
        var pk = 1e-9f
        val lv = FloatArray(hits.size)
        for ((i, h) in hits.withIndex()) {
            var m = 0f
            for (j in h.at until min(x.size, h.at + sr / 30)) m = max(m, abs(x[j]))
            lv[i] = m
            pk = max(pk, m)
        }
        for ((i, h) in hits.withIndex()) out.add(Hit(h.at, kind[i], (0.35f + 0.65f * sqrt(lv[i] / pk)).coerceIn(0.3f, 1f)))
        return out
    }

    /** Klang-Merkmale eines Anschlags (~60 ms ab [at]): Anteil < 200 Hz, Anteil > 5 kHz, Schwerpunkt (Hz). */
    fun features(x: FloatArray, at: Int, sr: Int): DoubleArray {
        val n = 2048
        val fft = SpleeterCore.Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val len = min(n, (sr * 0.06).toInt())
        for (i in 0 until n) {
            val j = at + i
            val w = if (i < len) (0.5 - 0.5 * cos(2 * PI * i / len)).toFloat() else 0f
            re[i] = if (j < x.size) x[j] * w else 0f
            im[i] = 0f
        }
        fft.forward(re, im)
        var tot = 0.0; var low = 0.0; var high = 0.0; var cen = 0.0
        for (k in 1 until n / 2) {
            val f = k.toDouble() * sr / n
            val p = (re[k] * re[k] + im[k] * im[k]).toDouble()
            tot += p
            cen += p * f
            if (f < 200) low += p
            if (f > 5000) high += p
        }
        if (tot <= 0) return doubleArrayOf(0.0, 0.0, 2000.0)
        return doubleArrayOf(low / tot, high / tot, cen / tot)
    }

    // ---------------------------------------------------------------- Folge dem Song: Akkorde

    /**
     * Akkord je Abschnitt: Chromagramm (wie die Tonart-Erkennung) gegen Dur-/Moll-Dreiklänge.
     * Liefert je Abschnitt 0..11 = Dur, 12..23 = Moll (wie [Keys]), -1 = unklar.
     * [key] (optional) bevorzugt leitereigene Akkorde.
     */
    fun chords(x: FloatArray, sr: Double, starts: IntArray, ends: IntArray, key: Int = -1): IntArray {
        val n = 4096
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val fft = SpleeterCore.Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val pcOf = IntArray(n / 2) { -1 }
        val wOf = FloatArray(n / 2)
        for (k in 1 until n / 2) {
            val f = k * sr / n
            if (f < 60 || f > 2000) continue
            val m = midiOf(f)
            val r = m.roundToInt()
            val dist = abs(m - r)
            if (dist > 0.4) continue
            pcOf[k] = Math.floorMod(r, 12)
            wOf[k] = ((1 - dist / 0.4) * (if (f < 250) 1.6 else 1.0)).toFloat()
        }
        val diat = if (key >= 0) diatonic(key) else null
        val out = IntArray(starts.size) { -1 }
        for (s in starts.indices) {
            val a = starts[s]
            val b = min(ends[s], x.size)
            if (b - a < n) continue
            val chroma = DoubleArray(12)
            var o = a
            while (o + n <= b) {
                for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0f }
                fft.forward(re, im)
                for (k in 1 until n / 2) {
                    val pc = pcOf[k]
                    if (pc < 0) continue
                    chroma[pc] += sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()) * wOf[k]
                }
                o += n / 2
            }
            val tot = chroma.sum()
            if (tot <= 1e-9) continue
            var best = -1
            var bs = -1e9
            for (c in 0 until 24) {
                val r = c % 12
                val third = if (c < 12) 4 else 3
                var sc = chroma[r] * 1.0 + chroma[(r + third) % 12] * 0.9 + chroma[(r + 7) % 12] * 0.8
                // Gegenspieler: falsche Terz, Tritonus
                sc -= chroma[(r + (if (c < 12) 3 else 4)) % 12] * 0.6 + chroma[(r + 6) % 12] * 0.4
                sc /= tot
                if (diat != null && c in diat) sc += 0.06
                if (sc > bs) { bs = sc; best = c }
            }
            out[s] = best
        }
        return out
    }

    /** Die 6 leitereigenen Dur-/Moll-Akkorde einer Tonart (ohne vermindert). */
    fun diatonic(key: Int): Set<Int> {
        val r = key % 12
        val maj = if (key < 12) r else (r + 3) % 12
        return setOf(maj, (maj + 5) % 12, (maj + 7) % 12, 12 + (maj + 9) % 12, 12 + (maj + 2) % 12, 12 + (maj + 4) % 12)
    }

    // ---------------------------------------------------------------- Live-Auto-Tune

    /**
     * Echtzeit-Tonhöhenkorrektur (Mischer-Thread, mono). Erkennung per YIN auf halber Abtastrate alle ~10 ms,
     * Verschiebung mit zwei überblendeten Lese-Köpfen auf einer Verzögerungslinie (Fenster ≈ 2,5 Perioden).
     * [allowed] liefert für eine Tonklasse 0..11, ob sie erlaubt ist (Tonleiter); [speed] 0 = sofort (Roboter) … 1 = weich.
     */
    class AutoTune(private val sr: Int) {
        private val dec = 2
        private val dsr = sr / dec
        private val hist = FloatArray(1024)
        private var hw = 0
        private var decAcc = 0f
        private var decN = 0
        private var sinceDetect = 0
        private val detectEvery = sr / 100
        private val yin = Yin(dsr, 420, 75.0, 900.0, 0.18f)
        private val ana = FloatArray(1024)
        private val buf = FloatArray(8192)
        private var w = 0
        private var phase = 0.0
        private var ratio = 1.0
        private var target = 1.0
        private var period = sr / 200.0
        private var voicedHold = 0
        /** Erkannte und Ziel-Tonhöhe (MIDI, NaN = nichts) – für die Anzeige. */
        @Volatile var heardMidi = Double.NaN
        @Volatile var targetMidi = Double.NaN

        fun process(x: FloatArray, n: Int, allowed: (Int) -> Boolean, speed: Float, mix: Float) {
            // Nachführ-Zeitkonstante: 0 = sofort (harter Roboter-Effekt) … 1 = ~200 ms (natürlich)
            val tau = 0.004 + speed * 0.2
            val k = if (speed <= 0.01f) 1.0 else 1.0 - exp(-0.01 / tau)
            for (i in 0 until n) {
                val v = x[i]
                // Analyse: halbe Rate
                decAcc += v; decN++
                if (decN == dec) {
                    hist[hw] = decAcc / dec
                    hw = (hw + 1) and 1023
                    decAcc = 0f; decN = 0
                }
                if (++sinceDetect >= detectEvery) {
                    sinceDetect = 0
                    detect(allowed)
                    ratio += (target - ratio) * k
                }
                // Verzögerungslinie
                buf[w] = v
                val win = grainWin(period, sr)
                phase += (1.0 - ratio) / win
                if (phase >= 1.0) phase -= 1.0
                if (phase < 0.0) phase += 1.0
                val d1 = phase * win + 2
                val p2 = (phase + 0.5) % 1.0
                val d2 = p2 * win + 2
                val g1 = sin(PI * phase).let { it * it }
                val g2 = sin(PI * p2).let { it * it }
                val y = (read(d1) * g1 + read(d2) * g2).toFloat()
                x[i] = v + (y - v) * mix
                w = (w + 1) and 8191
            }
        }

        private fun read(d: Double): Double {
            val p = w - d
            val i0 = Math.floor(p).toInt()
            val f = p - i0
            val a = buf[i0 and 8191]
            val b = buf[(i0 + 1) and 8191]
            return a + (b - a) * f
        }

        private fun detect(allowed: (Int) -> Boolean) {
            val len = 420 + yin.maxLag + 2
            for (k in 0 until len) ana[k] = hist[(hw - len + k) and 1023]
            var e = 0f
            for (k in 0 until len) e += ana[k] * ana[k]
            if (e / len < 1e-6f) { unvoiced(); return }
            val hz = yin.pitch(ana, 0, 420)
            if (hz <= 0 || yin.clarity < 0.75f) { unvoiced(); return }
            voicedHold = 8
            period = sr / hz
            val m = midiOf(hz)
            heardMidi = m
            // nächster erlaubter Ton
            val base = m.roundToInt()
            var best = base
            var bd = 99.0
            for (c in base - 6..base + 6) if (allowed(Math.floorMod(c, 12)) && abs(c - m) < bd) { bd = abs(c - m); best = c }
            targetMidi = best.toDouble()
            target = Math.pow(2.0, (best - m) / 12.0).coerceIn(0.7, 1.4)
        }

        private fun unvoiced() {
            if (voicedHold > 0) { voicedHold--; return }
            target = 1.0
            heardMidi = Double.NaN
            targetMidi = Double.NaN
        }
    }

    // ---------------------------------------------------------------- Bausteine für Echtzeit (Mischer-Thread)

    /** Laufende Tonhöhen-Erkennung: Samples hineinschieben, alle ~10 ms neue Schätzung ([midi], NaN = stimmlos). */
    class PitchTracker(private val sr: Int) {
        private val dec = 2
        private val hist = FloatArray(1024)
        private var hw = 0
        private var acc = 0f
        private var accN = 0
        private var since = 0
        private val every = sr / 100
        private val yin = Yin(sr / dec, 420, 75.0, 900.0, 0.18f)
        private val ana = FloatArray(1024)
        private var hold = 0
        /** Letzte Tonhöhe (MIDI mit Bruchteil) und Pegel (RMS). */
        var midi = Double.NaN
            private set
        var level = 0f
            private set
        /** true, wenn gerade eine neue Schätzung kam. */
        var fresh = false
            private set

        fun push(v: Float) {
            fresh = false
            acc += v; accN++
            if (accN == dec) { hist[hw] = acc / dec; hw = (hw + 1) and 1023; acc = 0f; accN = 0 }
            if (++since >= every) { since = 0; detect(); fresh = true }
        }

        private fun detect() {
            val len = 420 + yin.maxLag + 2
            var e = 0f
            for (k in 0 until len) { val q = hist[(hw - len + k) and 1023]; ana[k] = q; e += q * q }
            level = sqrt(e / len)
            if (level < 0.003f) { miss(); return }
            val hz = yin.pitch(ana, 0, 420)
            if (hz <= 0 || yin.clarity < 0.75f) { miss(); return }
            hold = 6
            midi = midiOf(hz)
        }

        private fun miss() { if (hold > 0) hold-- else midi = Double.NaN }
    }

    /** Tonhöhen-Verschieber (zwei überblendete Lese-Köpfe auf einer Verzögerungslinie). */
    class Shifter(private val sr: Int) {
        private val buf = FloatArray(8192)
        private var w = 0
        private var phase = 0.0
        var ratio = 1.0
        var period = sr / 200.0

        fun next(v: Float): Float {
            buf[w] = v
            val win = grainWin(period, sr)
            phase += (1.0 - ratio) / win
            if (phase >= 1.0) phase -= 1.0
            if (phase < 0.0) phase += 1.0
            val p2 = (phase + 0.5) % 1.0
            val g1 = sin(PI * phase).let { it * it }
            val g2 = sin(PI * p2).let { it * it }
            val y = read(phase * win + 2) * g1 + read(p2 * win + 2) * g2
            w = (w + 1) and 8191
            return y.toFloat()
        }

        private fun read(d: Double): Double {
            val p = w - d
            val i0 = Math.floor(p).toInt()
            val f = p - i0
            val a = buf[i0 and 8191]
            val b = buf[(i0 + 1) and 8191]
            return a + (b - a) * f
        }
    }

    /**
     * Fensterlänge der Lese-Köpfe: ein gerades Vielfaches der Periode, damit die beiden um ein halbes Fenster
     * versetzten Köpfe phasengleich lesen (sonst löschen sie sich teilweise aus). Etwa 8–40 ms.
     */
    fun grainWin(period: Double, sr: Int): Double {
        val p = period.coerceIn(sr / 1200.0, sr / 60.0)
        val m = max(1.0, Math.ceil(sr * 0.008 / (2 * p)))
        return min(2 * p * m, sr * 0.04).coerceAtLeast(sr * 0.008)
    }

    /** Nächster Ton der Tonleiter ([allowed]) zu [m]. */
    fun nearestAllowed(m: Double, allowed: (Int) -> Boolean): Int {
        val base = m.roundToInt()
        var best = base
        var bd = 99.0
        for (c in base - 6..base + 6) if (allowed(Math.floorMod(c, 12)) && abs(c - m) < bd) { bd = abs(c - m); best = c }
        return best
    }

    /** [steps] Tonleiter-Stufen von [note] aus (+ hoch, − runter); chromatisch = feste Intervalle. */
    fun scaleStep(note: Int, steps: Int, allowed: (Int) -> Boolean): Int {
        val chromatic = (0..11).all(allowed)
        if (chromatic) return note + when (steps) { 2 -> 4; -2 -> -3; 4 -> 7; -4 -> -5; 7 -> 12; -7 -> -12; else -> steps }
        var n = note
        var left = abs(steps)
        val dir = if (steps > 0) 1 else -1
        var guard = 0
        while (left > 0 && guard++ < 40) { n += dir; if (allowed(Math.floorMod(n, 12))) left-- }
        return n
    }

    /**
     * Harmonizer: bis zu 3 Begleitstimmen zur eigenen Stimme, leitereigen (Terz, Quinte, Sexte …).
     * [voices]: Tonleiter-Stufen je Stimme, z. B. [2] = Terz drüber, [2, 4] = Terz + Quinte, [-2, 2] = Terz drunter + drüber.
     */
    class Harmonizer(private val sr: Int) {
        private val tr = PitchTracker(sr)
        private val sh = Array(3) { Shifter(sr) }
        private val target = DoubleArray(3) { 1.0 }
        var voices: IntArray = intArrayOf(2, 4)

        fun process(x: FloatArray, n: Int, allowed: (Int) -> Boolean, level: Float) {
            val vs = voices
            val nv = min(3, vs.size)
            for (i in 0 until n) {
                val v = x[i]
                tr.push(v)
                if (tr.fresh) {
                    val m = tr.midi
                    if (m.isNaN()) for (k in 0 until 3) target[k] = 1.0 else {
                        val base = nearestAllowed(m, allowed)
                        for (k in 0 until nv) {
                            val t = scaleStep(base, vs[k], allowed)
                            target[k] = Math.pow(2.0, (t - m) / 12.0).coerceIn(0.4, 2.2)
                            sh[k].period = sr / hzOf(m)
                        }
                    }
                }
                var add = 0f
                for (k in 0 until nv) {
                    val s = sh[k]
                    s.ratio += (target[k] - s.ratio) * 0.02
                    add += s.next(v)
                }
                x[i] = v + add * level * (if (tr.midi.isNaN()) 0f else 1f)
            }
        }
    }

    /**
     * Stimme → Synth: verfolgt die Tonhöhe und meldet Noten-Wechsel ([onNote] mit MIDI-Ton und Stärke, -1 = aus).
     * Ein neuer Ton gilt erst, wenn er ~30 ms stabil ist (kein Flattern), Stille beendet die Note.
     */
    class VoiceToNotes(sr: Int) {
        private val tr = PitchTracker(sr)
        private var cur = -1
        private var cand = -1
        private var candN = 0
        private var silent = 0
        var heard = Double.NaN
            private set
        var playing = -1
            private set

        fun process(x: FloatArray, n: Int, allowed: (Int) -> Boolean, onNote: (Int, Float) -> Unit) {
            for (i in 0 until n) {
                tr.push(x[i])
                if (!tr.fresh) continue
                val m = tr.midi
                heard = m
                if (m.isNaN()) {
                    if (cur >= 0 && ++silent >= 4) { cur = -1; playing = -1; onNote(-1, 0f) }
                    candN = 0
                    continue
                }
                silent = 0
                val note = nearestAllowed(m, allowed)
                // Hysterese: am aktuellen Ton festhalten, solange man nicht deutlich danebenliegt
                if (cur >= 0 && abs(m - cur) < 0.7) { candN = 0; continue }
                if (note == cand) candN++ else { cand = note; candN = 1 }
                if (candN >= 3) {
                    cur = note
                    playing = note
                    candN = 0
                    onNote(note, (0.5f + tr.level * 6f).coerceIn(0.4f, 1f))
                }
            }
        }
    }

    // ---------------------------------------------------------------- Klang-Sammler

    /** Ein gesammelter Klang: Audio, Art (0 Kick, 1 Snare, 2 Hi-Hat, 3 Percussion), Lautheit. */
    class Found(val data: FloatArray, val kind: Int, val loud: Float)

    /**
     * Geräusche aus einer freien Aufnahme ausschneiden und sortieren: Anschläge finden (wie Beatbox),
     * jeden bis zum nächsten bzw. max. 1 s schneiden, Stille am Ende kürzen, weich ausblenden.
     * Je Art der lauteste/klarste, dazu bis zu [extra] weitere verschiedene als Percussion.
     */
    fun collect(x: FloatArray, sr: Int, extra: Int = 5): List<Found> {
        val hits = beatbox(x, sr)
        if (hits.isEmpty()) return emptyList()
        val cuts = ArrayList<Found>()
        for ((i, h) in hits.withIndex()) {
            val a = max(0, h.at - sr / 200)
            val next = if (i + 1 < hits.size) hits[i + 1].at - sr / 200 else x.size
            var b = min(min(next, x.size), a + sr)
            if (b - a < sr / 40) continue
            var pk = 0f
            for (j in a until b) pk = max(pk, abs(x[j]))
            if (pk < 0.01f) continue
            // Ende: wo es unter −40 dB fällt (über 10 ms gemittelt)
            val wl = sr / 100
            var j = a + sr / 50
            while (j + wl < b) {
                var e = 0f
                for (q in j until j + wl) e = max(e, abs(x[q]))
                if (e < pk * 0.01f) { b = j + wl; break }
                j += wl
            }
            val d = x.copyOfRange(a, b)
            // normalisieren und ausblenden
            val g = 0.89f / pk
            val fo = min(d.size / 3, sr / 50)
            for (q in d.indices) {
                var v = d[q] * g
                if (q < 32) v *= q / 32f
                if (q >= d.size - fo) v *= (d.size - q).toFloat() / fo
                d[q] = v
            }
            cuts.add(Found(d, h.kind, h.vel))
        }
        val out = ArrayList<Found>()
        for (k in 0..2) cuts.filter { it.kind == k }.maxByOrNull { it.loud }?.let { out.add(it) }
        // weitere: möglichst verschieden (Länge/Art), lauteste zuerst
        val rest = cuts.filter { c -> out.none { it === c } }.sortedByDescending { it.loud }
        for (c in rest) {
            if (out.size >= 3 + extra) break
            if (out.any { abs(it.data.size - c.data.size) < sr / 100 && it.kind == c.kind }) continue
            out.add(Found(c.data, 3, c.loud))
        }
        return out
    }

    /**
     * Gleichmäßiger Versatz zum Raster: Kreis-Mittel der Positionen (in 1/16-Schritten) modulo [period] Schritte.
     * Liefert den Versatz in Schritten (−period/2 … +period/2) oder 0, wenn die Schläge nicht einheitlich liegen.
     */
    fun gridOffset(steps: List<Double>, period: Double = 1.0, minConsistency: Double = 0.55): Double {
        if (steps.size < 3) return 0.0
        var c = 0.0; var s = 0.0
        for (x in steps) { val a = 2 * PI * x / period; c += cos(a); s += sin(a) }
        val r = sqrt(c * c + s * s) / steps.size
        if (r < minConsistency) return 0.0
        return kotlin.math.atan2(s, c) / (2 * PI) * period
    }

    @Suppress("unused")
    private fun db(v: Float) = 20 * log10(max(1e-9f, v))
    @Suppress("unused")
    private fun env(t: Double, k: Double) = exp(-t * k)
}
