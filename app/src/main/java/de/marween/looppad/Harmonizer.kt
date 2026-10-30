package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * VOCAL HARMONIZER & FORMANT SHIFTER.
 *
 * Quelle: die Gesangsspur (Stems) eines DJ-Decks oder das Mikrofon. Eine Tonhöhen-Erkennung (YIN) misst laufend,
 * welchen Ton die Stimme singt. Jede Chorstimme wird per PSOLA (Körner im Takt der Stimmperiode) auf ihren Zielton
 * gelegt – Tonhöhe und Formant (Stimmcharakter) getrennt:
 *  - Zieltöne: gehaltene Noten/Akkorde (Pads im Feld, Tastatur, MIDI) – ohne Noten automatisch Terz + Quinte in der Tonart.
 *  - XY: X = Formant (tief/männlich … hoch/hell/Hyperpop), Y = Tuning: unten natürlich (Vibrato bleibt),
 *        oben hart auf die Töne, ganz oben Roboter (feste Tonhöhe, gleichförmige Körner).
 *  - LEAD-TUNE: auch die Hauptstimme läuft getunt mit (Original-Gesang im Deck wird dafür stumm).
 */
object Harmonizer {
    // ---------------------------------------------------------------- Bedienung
    @Volatile var on = false
    /** 0 = Deck A, 1 = Deck B, 2 = Auto (lauteres laufendes Deck), 3 = Mikrofon. */
    @Volatile var source = 2
    @Volatile var x = 0.5f
    @Volatile var y = 0.25f
    @Volatile var level = 0.7f
    @Volatile var space = 0.35f
    @Volatile var width = 0.6f
    /** Oktave der Chorstimmen −1..+1. */
    @Volatile var octave = 0
    @Volatile var leadTune = false
    /** Original-Gesang im Deck stumm, solange der Harmonizer läuft. */
    @Volatile var muteOrig = false
    /** Gehaltene Noten (MIDI-Nummern, Bitfeld über 128) – UI- und MIDI-Thread schreiben. */
    private val held = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** Stimmen ohne gehaltene Noten: siehe [AUTO_NAMES]. */
    @Volatile var autoMode = 0
    val AUTO_NAMES = arrayOf("TERZ + QUINTE", "OKTAVEN", "QUINTEN (POWER)", "GOSPEL (4-stimmig)", "DOUBLER (breit)", "NUR TERZ", "TERZ+QUINTE DARUNTER", "ENGEL (hoch)")

    /** Vorlagen: Name, Formant X, Tuning Y, Oktave, Auto-Stimmen, Lead-Tune, Original stumm, Hall, Breite */
    class Preset(val name: String, val x: Float, val y: Float, val oct: Int, val auto: Int, val lead: Boolean, val mute: Boolean, val space: Float, val width: Float)
    val PRESETS = listOf(
        Preset("Natürlicher Chor", 0.5f, 0.2f, 0, 0, false, false, 0.35f, 0.6f),
        Preset("Gospel-Chor", 0.52f, 0.35f, 0, 3, false, false, 0.45f, 0.75f),
        Preset("Engelschor", 0.68f, 0.3f, 0, 7, false, false, 0.85f, 0.9f),
        Preset("Tiefer Männerchor", 0.18f, 0.3f, -1, 0, false, false, 0.5f, 0.7f),
        Preset("Mönche (Oktave tief, Quinten)", 0.25f, 0.55f, -1, 2, false, false, 0.75f, 0.5f),
        Preset("Doubler (breiter Lead)", 0.5f, 0.15f, 0, 4, false, false, 0.2f, 1f),
        Preset("Hyperpop-Lead", 0.85f, 0.82f, 0, 5, true, true, 0.3f, 0.6f),
        Preset("Chipmunk", 1f, 0.6f, 1, 5, true, true, 0.2f, 0.4f),
        Preset("Roboter-Chor", 0.45f, 0.95f, 0, 0, false, false, 0.35f, 0.7f),
        Preset("Daft-Vocoder (Lead)", 0.38f, 0.97f, 0, 4, true, true, 0.25f, 0.5f),
        Preset("Monster (tief, hart)", 0f, 0.8f, -1, 1, true, true, 0.4f, 0.4f),
        Preset("EDM-Quinten-Stack", 0.55f, 0.7f, 0, 2, true, false, 0.5f, 0.8f),
        Preset("Harmonie darunter", 0.45f, 0.3f, 0, 6, false, false, 0.35f, 0.6f),
        Preset("Hall-Geister (nur Effekt)", 0.6f, 0.4f, 1, 7, false, true, 1f, 1f)
    )
    @Volatile var presetIdx = -1
        private set

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        x = p.x; y = p.y; octave = p.oct; autoMode = p.auto; leadTune = p.lead; muteOrig = p.mute; space = p.space; width = p.width
    }

    /** MIDI-Noten an den Chor statt an die Instrumente. */
    @Volatile var midiIn = true

    /** Anzeige */
    @Volatile var pitchHz = 0f
        private set
    @Volatile var voiced = false
        private set
    @Volatile var meter = 0f
        private set
    @Volatile var curKey = 21
        private set
    @Volatile var srcDeck = -1
        private set
    val shownTargets = IntArray(5) { -1 }

    fun noteOn(m: Int, tag: Int = 0) { held[m] = tag }
    fun noteOff(m: Int) { held.remove(m) }
    fun allOff() { held.clear() }
    fun heldNotes(): List<Int> = held.keys.sorted()
    fun takesMidi(): Boolean = on && midiIn

    // ---------------------------------------------------------------- Eingang
    private const val RN = 1 shl 16
    private const val RM = RN - 1
    private val inR = FloatArray(RN)
    private var wPos = 0L
    private var srN = 48000
    private var deckIn = Array(2) { FloatArray(0) }
    private var deckN = IntArray(2)
    private var lpIn = 0f

    /** Vom DJ-Renderer: Gesangsanteil des Decks [idx] in diesem Block (Mono). */
    fun feedDeck(idx: Int, l: FloatArray, r: FloatArray, n: Int) {
        if (deckIn[idx].size < n) deckIn[idx] = FloatArray(n * 2)
        val b = deckIn[idx]
        for (i in 0 until n) b[i] = (l[i] + r[i]) * 0.5f
        deckN[idx] = n
    }

    /** Braucht dieses Deck den Gesangs-Anteil? */
    fun wantsDeck(idx: Int): Boolean = on && (source == idx || (source == 2 && autoDeck() == idx))

    private fun autoDeck(): Int {
        var best = -1
        var bl = -1f
        for (d in Dj.decks) {
            if (!d.loaded() || !d.playing) continue
            val g = d.fader * (if (d.idx == 0) 1f - Dj.xfade else Dj.xfade) + 0.01f
            if (g > bl) { bl = g; best = d.idx }
        }
        return if (best < 0) 0 else best
    }

    // ---------------------------------------------------------------- Tonhöhe (YIN auf 1/4 Rate)
    private const val DS = 4
    private val dsBuf = FloatArray(2048)
    private var dsW = 0
    private var dsAcc = 0f
    private var dsCnt = 0
    private var hopCtr = 0
    private var dLp1 = 0f; private var dLp2 = 0f
    private val yinD = FloatArray(400)
    private var f0 = 0.0
    private val f0Hist = DoubleArray(3)
    private var rmsE = 0f

    private fun yin() {
        val sr4 = srN.toDouble() / DS
        val w = (sr4 * 0.021).toInt()
        val tMin = (sr4 / 900).toInt().coerceAtLeast(2)
        val tMax = min(yinD.size - 1, (sr4 / 70).toInt())
        val n = dsBuf.size
        val start = dsW - w - tMax
        fun at(i: Int) = dsBuf[Math.floorMod(start + i, n)]
        var e = 0.0
        for (i in 0 until w) { val v = at(i); e += v * v }
        if (e / w < 1e-6) { pushF0(0.0); return }
        yinD[0] = 1f
        var run = 0.0
        var found = -1
        for (tau in 1..tMax) {
            var s = 0.0
            for (i in 0 until w) { val d = at(i) - at(i + tau); s += d * d }
            run += s
            yinD[tau] = if (run > 0) (s * tau / run).toFloat() else 1f
        }
        for (tau in tMin..tMax - 1) {
            if (yinD[tau] < 0.18f) {
                var t = tau
                while (t + 1 < tMax && yinD[t + 1] < yinD[t]) t++
                found = t
                break
            }
        }
        if (found < 0) { pushF0(0.0); return }
        val a = yinD[found - 1]; val b = yinD[found]; val c = yinD[found + 1]
        val den = a + c - 2 * b
        val off = if (abs(den) > 1e-9f) (0.5f * (a - c) / den).coerceIn(-1f, 1f) else 0f
        pushF0(sr4 / (found + off))
    }

    private fun pushF0(v: Double) {
        f0Hist[0] = f0Hist[1]; f0Hist[1] = f0Hist[2]; f0Hist[2] = v
        val s = f0Hist.sorted()
        f0 = if (f0Hist.count { it > 0 } >= 2) s[1].let { if (it > 0) it else s[2] } else 0.0
    }

    // ---------------------------------------------------------------- PSOLA-Stimmen
    private class Grain(var center: Double, var half: Double, var rate: Double, var age: Double, var on: Boolean = false)
    private class Voice {
        val g = Array(10) { Grain(0.0, 0.0, 1.0, 0.0) }
        var next = 0.0          // Ausgabe-Proben bis zum nächsten Korn
        var ratio = 1.0
        var target = -1         // MIDI-Zielton
        var gain = 0f
        var pan = 0f
        var drift = 0.0
        var dph = Math.random()
        var lastP = 400.0
    }
    private const val NV = 5
    /** Hann-Fenster über −1..1 (1024 Werte). */
    private val HANN = FloatArray(1024) { (0.5 + 0.5 * kotlin.math.cos(PI * (it / 511.5 - 1.0))).toFloat() }
    private val voices = Array(NV) { Voice() }
    private var lat = 0
    private var plL: Plate? = null
    private var plR: Plate? = null
    private var hp1 = 0f; private var hp2 = 0f
    private var ready = false
    private var fade = 0f
    private var savedVoc = Array(2) { -1f }
    @Volatile var outL = FloatArray(0)
        private set
    @Volatile var outR = FloatArray(0)
        private set

    private fun init(sr: Int) {
        srN = sr
        lat = (sr * 0.03).toInt()
        plL = Plate(sr, 0.8f); plR = Plate(sr, 0.93f)
        val pans = floatArrayOf(-0.75f, 0.75f, -0.35f, 0.35f, 0f)
        for (k in 0 until NV) voices[k].pan = pans[k]
        ready = true
    }

    private fun rd(p: Double): Float {
        val i = floor(p).toLong()
        val t = (p - i).toFloat()
        val a = inR[((i - 1) and RM.toLong()).toInt()]; val b = inR[(i and RM.toLong()).toInt()]
        val c = inR[((i + 1) and RM.toLong()).toInt()]; val d = inR[((i + 2) and RM.toLong()).toInt()]
        return FxDsp.hermite(a, b, c, d, t)
    }

    /** Tonleiter-Töne (Halbtöne 0..11) der Tonart. */
    private fun scale(key: Int): IntArray {
        val r = Keys.root(key)
        val iv = if (Keys.isMinor(key)) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return IntArray(7) { (r + iv[it]) % 12 }
    }

    private fun nearestScale(m: Double, sc: IntArray): Int {
        var best = m.roundToInt()
        var bd = 99.0
        for (o in -1..1) for (pc in sc) {
            val c = (floor(m / 12.0).toInt() + o) * 12 + pc
            val d = abs(c - m)
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    /** Diatonischer Schritt [steps] Stufen über dem Ton [m] (m liegt in der Tonleiter). */
    private fun diatonic(m: Int, steps: Int, sc: IntArray): Int {
        val root = sc[0]
        val iv = IntArray(7) { Math.floorMod(sc[it] - root, 12) }
        val idx = sc.indexOf(Math.floorMod(m, 12)).let { if (it < 0) 0 else it }
        val mRoot = m - iv[idx]
        val ti = idx + steps
        return mRoot + iv[Math.floorMod(ti, 7)] + 12 * Math.floorDiv(ti, 7)
    }

    private fun key(): Int {
        for (k in intArrayOf(srcDeck, 0, 1)) {
            if (k < 0) continue
            val d = Dj.decks[k]
            if (d.loaded() && d.key >= 0) return d.effKey()
        }
        if (Ghost.curKey >= 0) return Ghost.curKey
        return 21
    }

    /**
     * Ein Block: Eingang holen (Deck-Gesang oder [mic]), Chor rendern und in [oL]/[oR] mischen.
     */
    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, mic: FloatArray?, vol: Float) {
        if (!ready || srN != sr) init(sr)
        // Original-Gesang stumm schalten / zurück
        for (k in 0..1) {
            val d = Dj.decks[k]
            val want = on && muteOrig && source != 3 && (source == k || (source == 2 && srcDeck == k))
            if (want && savedVoc[k] < 0f) { savedVoc[k] = d.stemGain[DeckStems.VOC]; d.stemGain[DeckStems.VOC] = 0f }
            else if (!want && savedVoc[k] >= 0f) { d.stemGain[DeckStems.VOC] = savedVoc[k]; savedVoc[k] = -1f }
        }
        if (!on && fade <= 0f) { meter *= 0.9f; deckN[0] = 0; deckN[1] = 0; return }
        srcDeck = if (source == 3) -1 else if (source == 2) autoDeck() else source
        val inp: FloatArray? = when {
            source == 3 -> mic
            srcDeck >= 0 && deckN[srcDeck] >= n -> deckIn[srcDeck]
            else -> null
        }
        deckN[0] = 0; deckN[1] = 0
        val k0 = key()
        curKey = k0
        val sc = scale(k0)
        val pl = plL!!; val pr = plR!!
        // Formant: X 0..1 → 0,6 … 1,75 (Mitte = unverändert)
        val fmt = if (x < 0.5f) 0.6 + 0.4 * (x / 0.5) else 1.0 + 0.75 * ((x - 0.5) / 0.5)
        val robot = y > 0.85f
        val hard = (y / 0.85f).coerceIn(0f, 1f).toDouble()
        val oct = octave * 12
        val aHp = (1.0 - kotlin.math.exp(-2 * PI * 120.0 / sr)).toFloat()
        val fadeStep = 1f / (0.08f * sr)
        var pk = 0f
        if (outL.size < n) { outL = FloatArray(n * 2); outR = FloatArray(n * 2) }
        for (i in 0 until n) {
            // Eingang (Deck-Gesang 120-Hz-Hochpass gegen Bass-Reste)
            var v = inp?.get(i) ?: 0f
            hp1 += aHp * (v - hp1); v -= hp1
            inR[(wPos and RM.toLong()).toInt()] = v
            wPos++
            rmsE += (v * v - rmsE) * 0.0005f
            // Dezimiert für YIN
            dLp1 += 0.35f * (v - dLp1); dLp2 += 0.35f * (dLp1 - dLp2)
            dsAcc += dLp2; dsCnt++
            if (dsCnt == DS) {
                dsBuf[dsW and (dsBuf.size - 1)] = dsAcc / DS
                dsW++; dsAcc = 0f; dsCnt = 0
                if (++hopCtr >= 160) { hopCtr = 0; yin(); updateTargets(sc, oct) }
            }
            fade = if (on) min(1f, fade + fadeStep) else max(0f, fade - fadeStep)
            val vo = f0 > 0 && rmsE > 1e-6f
            val pIn = if (vo) sr / f0 else sr * 0.008
            var l = 0f; var r = 0f
            for (vc in voices) {
                if (vc.target < 0 && vc.gain <= 0.0005f) continue
                val tg = if (vc.target >= 0) 1f else 0f
                vc.gain += (tg - vc.gain) * 0.0015f
                vc.next -= 1.0
                if (vc.next <= 0.0) {
                    // Ausgabe-Periode (nur beim Korn-Start berechnet)
                    val pOut = if (vc.target >= 0 && (vo || robot)) {
                        vc.dph += vc.next.coerceAtLeast(0.0) / sr + pIn / sr
                        val det = if (robot) 0.0 else 0.08 * width * sin(2 * PI * 0.23 * vc.dph + vc.pan * 3)
                        sr / (440.0 * 2.0.pow((vc.target - 69 + det) / 12.0))
                    } else pIn
                    // natürliches Tuning: Abweichung vom Tonleiter-Ton bleibt teilweise (Vibrato, Gleiten)
                    val pUse = if (vo && vc.target >= 0 && !robot && hard < 1.0) {
                        val mIn = 69 + 12 * ln(f0 / 440.0) / ln(2.0)
                        val dev = mIn - nearestScale(mIn, sc)
                        pOut / 2.0.pow(dev * (1.0 - hard) / 12.0)
                    } else pOut
                    vc.next += pUse
                    vc.lastP = pUse
                    val gr = vc.g.firstOrNull { !it.on } ?: vc.g.minByOrNull { it.half - it.age }!!
                    val pAn = if (robot) min(pIn, sr * 0.012) else pIn
                    val half = pAn / fmt
                    // Mitte des Korns: Eingang vor [lat] Proben, auf die nächste Spitze (Stimmpuls) ausgerichtet
                    var c = (wPos - lat).toDouble()
                    if (vo) {
                        val ci = c.toLong()
                        var bi = ci; var bv = 0f
                        val hw = (pIn * 0.5).toLong()
                        var s = -hw
                        var first = true
                        while (s <= hw) { val a = inR[((ci + s) and RM.toLong()).toInt()]; if (first || a > bv) { bv = a; bi = ci + s; first = false }; s += 1 }
                        c = bi.toDouble()
                    }
                    gr.center = c; gr.half = half; gr.rate = fmt; gr.age = -half; gr.on = true
                }
                var s = 0f
                for (gr in vc.g) {
                    if (!gr.on) continue
                    val t = gr.age
                    val wv = HANN[((t / gr.half + 1.0) * 511.5).toInt().coerceIn(0, 1023)]
                    s += rd(gr.center + t * gr.rate) * wv
                    gr.age += 1.0
                    if (gr.age >= gr.half) gr.on = false
                }
                // Überlappung normieren (Körner liegen im Abstand pOut, Länge 2·half)
                val ov = ((pIn / fmt) / max(1.0, vc.lastP)).coerceIn(0.4, 6.0)
                s *= (1.0 / ov).toFloat() * vc.gain
                val pn = vc.pan * width
                l += s * (0.5f - 0.5f * pn) * 1.4f
                r += s * (0.5f + 0.5f * pn) * 1.4f
            }
            val vl = pl.tick((l + r) * 0.5f + l * 0.2f, 0.72f, 0.4f, 0.8f)
            val vr = pr.tick((l + r) * 0.5f + r * 0.2f, 0.72f, 0.4f, 0.8f)
            val g = fade * level * vol * 1.6f
            val ol = tanh((l + vl * space * 1.3f) * g)
            val orr = tanh((r + vr * space * 1.3f) * g)
            oL[i] += ol; oR[i] += orr
            outL[i] = ol; outR[i] = orr
            pk = max(pk, max(abs(ol), abs(orr)))
        }
        meter = max(pk, meter * 0.9f)
        pitchHz = f0.toFloat()
        voiced = f0 > 0
        if (!on && fade <= 0f) { for (vc in voices) { vc.target = -1; vc.gain = 0f; for (g in vc.g) g.on = false }; pl.reset(); pr.reset() }
    }

    /** Zieltöne der Stimmen festlegen (alle ~8 ms). */
    private fun updateTargets(sc: IntArray, oct: Int) {
        val notes = held.keys.sorted()
        val tg = IntArray(NV) { -1 }
        if (f0 > 0 || y > 0.85f) {
            val mIn = if (f0 > 0) 69 + 12 * ln(f0 / 440.0) / ln(2.0) else 60.0
            val mq = nearestScale(mIn, sc)
            if (notes.isNotEmpty()) {
                // gehaltene Töne: jede Note in die Oktave um die Stimme legen (so klingt es wie ein Chor um den Sänger)
                var k = 0
                for (nt in notes) {
                    if (k >= 4) break
                    var m = nt
                    while (m - mIn > 6.5) m -= 12
                    while (mIn - m > 5.5) m += 12
                    if (abs(m - mIn) < 0.6 && notes.size > 1) m += 12   // nicht exakt auf der Hauptstimme
                    // Hysterese: gleicher Ton in anderer Oktave bleibt, solange er nah genug liegt (kein Oktav-Springen)
                    val prev = voices[k].target - oct
                    if (prev >= 0 && Math.floorMod(prev - m, 12) == 0 && abs(prev - mIn) <= 9.5) m = prev
                    tg[k++] = m + oct
                }
            } else {
                // Auto-Stimmen (ohne gehaltene Noten), je nach [autoMode] – alles in der Tonart
                when (autoMode) {
                    1 -> { tg[0] = mq - 12 + oct; tg[1] = mq + 12 + oct }                                        // Oktaven
                    2 -> { tg[0] = mq + 7 + oct; tg[1] = mq - 5 + oct; tg[2] = mq - 12 + oct }                    // Quinten (Power)
                    3 -> { tg[0] = diatonic(mq, -2, sc) + oct; tg[1] = diatonic(mq, 2, sc) + oct; tg[2] = diatonic(mq, 4, sc) + oct; tg[3] = diatonic(mq, 5, sc) + oct } // Gospel
                    4 -> { tg[0] = mq + oct; tg[1] = mq + oct }                                                   // Doubler (Unisono, breit)
                    5 -> { tg[0] = diatonic(mq, 2, sc) + oct }                                                    // nur Terz darüber
                    6 -> { tg[0] = diatonic(mq, -2, sc) + oct; tg[1] = diatonic(mq, -4, sc) + oct }               // Terz + Quinte darunter
                    7 -> { tg[0] = mq + 12 + oct; tg[1] = diatonic(mq, 4, sc) + 12 + oct; tg[2] = mq + 24 + oct } // Engel (hoch)
                    else -> {
                        tg[0] = diatonic(mq, 2, sc) + oct
                        tg[1] = diatonic(mq, 4, sc) + oct
                        tg[2] = diatonic(mq, -3, sc) + oct
                    }
                }
            }
            if (leadTune) tg[4] = mq + oct
        }
        for (k in 0 until NV) {
            // in Pausen (kein Ton erkannt) die Stimmen weiterklingen lassen statt hart abzuschalten
            if (tg[k] >= 0 || f0 > 0 || held.isEmpty()) voices[k].target = tg[k]
            shownTargets[k] = voices[k].target
        }
    }
}
