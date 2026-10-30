package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * ATMOS – Evolving Pad & Ambient Texture Generator.
 *
 * Zwei Klangquellen, frei mischbar:
 *  - FM-Fläche: Akkordtöne in der Tonart des laufenden Decks, jede Stimme ein FM-Paar, dessen Index, Lautstärke
 *    und Verstimmung von eigenen, sehr langsamen Zufalls-LFOs gezogen werden – der Teppich verändert sich ständig.
 *    Optional wandert der Akkord alle 8 Takte weiter (i – VI – III – VII bzw. I – vi – IV – V).
 *  - Körner-Wolke: aus einem eingefangenen Schnipsel (die letzten Sekunden des Masters) werden laufend
 *    weiche Körner (80–450 ms) gelesen, verteilt im Stereobild, teils eine Oktave höher/tiefer oder als Quinte.
 * Danach: Tiefpass (XY: X), großer Hall (XY: Y), langes Ein- und Ausblenden.
 * Läuft im Pad- und im DJ-Modus (wird hinter den Decks in den Master gemischt).
 */
object Atmos {
    // ---------------------------------------------------------------- Bedienung (UI)
    @Volatile var on = false
    /** Filter 0 (dunkel) … 1 (offen). */
    @Volatile var x = 0.55f
    /** Raum 0 (nah) … 1 (endlos). */
    @Volatile var y = 0.65f
    @Volatile var level = 0.6f
    /** 0 = nur FM-Fläche, 1 = nur Körner-Wolke. */
    @Volatile var blend = 0.35f
    /** Wie schnell sich alles bewegt 0..1. */
    @Volatile var evolve = 0.45f
    /** Körnerdichte 0..1. */
    @Volatile var density = 0.5f
    /** Shimmer: Anteil Oktave hoch in Körnern und Hall 0..1. */
    @Volatile var shimmer = 0.3f
    /** Akkord wandert alle 8 Takte weiter. */
    @Volatile var wander = true
    /** Tonart automatisch vom Deck/Ghost; sonst [keyManual]. */
    @Volatile var keyAuto = true
    @Volatile var keyManual = 21          // A-Moll (Keys: 12..23 = Moll)
    /** Ein- und Ausblendzeit in Sekunden. */
    @Volatile var fadeSec = 4f
    /** Klangfarbe der Fläche: siehe [TIMBRES]. */
    @Volatile var timbre = 0
    /** Pegelausgleich der Klangfarben (gleich laut beim Durchschalten). */
    private val TG = floatArrayOf(1f, 1.1f, 2.1f, 1.15f, 1.6f, 2.1f, 1.15f, 1.45f)
    val TIMBRES = arrayOf("FM-GLAS", "STREICHER", "CHOR „AAH“", "DUNKLE DRONE", "GLOCKEN-WOLKE", "WIND", "ORGEL", "SAMT-PAD")
    /** Akkord-Art: siehe [CHORDSETS]. */
    @Volatile var chordSet = 0
    val CHORDSETS = arrayOf("TONART-FOLGE", "OFFEN (SUS)", "QUINTEN-DRONE", "LUSH (9er)", "DÜSTER (Moll add9)")

    /** Fertige Klangvorlagen: Name, Klang, Akkord, Filter X, Raum Y, Mix, Bewegung, Dichte, Shimmer, Blende, wandert */
    class Preset(val name: String, val timbre: Int, val chord: Int, val x: Float, val y: Float, val blend: Float, val evolve: Float,
                 val density: Float, val shimmer: Float, val fade: Float, val wander: Boolean)
    val PRESETS = listOf(
        Preset("Gläserner Himmel", 0, 0, 0.62f, 0.75f, 0.3f, 0.45f, 0.5f, 0.45f, 4f, true),
        Preset("Sonnenaufgang (Intro)", 1, 3, 0.35f, 0.6f, 0.15f, 0.35f, 0.4f, 0.3f, 10f, true),
        Preset("Engelschor", 2, 1, 0.55f, 0.85f, 0.1f, 0.4f, 0.4f, 0.6f, 6f, true),
        Preset("Deep-Space-Bridge", 3, 2, 0.3f, 0.95f, 0.4f, 0.55f, 0.6f, 0.4f, 8f, false),
        Preset("Techno-Dunkeldrone", 3, 4, 0.22f, 0.5f, 0.25f, 0.7f, 0.5f, 0.1f, 3f, false),
        Preset("Glocken im Nebel", 4, 3, 0.7f, 0.8f, 0.2f, 0.5f, 0.5f, 0.5f, 5f, true),
        Preset("Wind über dem Feld", 5, 1, 0.45f, 0.7f, 0.2f, 0.8f, 0.4f, 0.2f, 6f, false),
        Preset("Kathedrale (Orgel)", 6, 0, 0.5f, 0.9f, 0.0f, 0.25f, 0.3f, 0.2f, 5f, true),
        Preset("Samt-Pad (Deep House)", 7, 3, 0.4f, 0.55f, 0.1f, 0.35f, 0.4f, 0.15f, 3f, true),
        Preset("Lo-Fi-Wolke (Körner)", 7, 0, 0.35f, 0.6f, 0.8f, 0.5f, 0.6f, 0.2f, 3f, true),
        Preset("Unterwasser", 0, 2, 0.15f, 0.8f, 0.5f, 0.6f, 0.7f, 0.1f, 6f, false),
        Preset("Breakdown-Aufbau", 1, 0, 0.15f, 0.5f, 0.2f, 0.9f, 0.6f, 0.5f, 12f, true),
        Preset("Trance-Schimmer", 0, 3, 0.8f, 0.8f, 0.45f, 0.6f, 0.75f, 0.9f, 4f, true),
        Preset("Meditation (Quinten)", 6, 2, 0.4f, 0.7f, 0.0f, 0.2f, 0.3f, 0.3f, 8f, false),
        Preset("Film-Spannung", 2, 4, 0.3f, 0.75f, 0.3f, 0.75f, 0.5f, 0.3f, 6f, false),
        Preset("Körnersturm", 5, 1, 0.65f, 0.65f, 0.9f, 0.95f, 1f, 0.6f, 2f, false)
    )
    @Volatile var presetIdx = -1
        private set

    fun applyPreset(i: Int) {
        val p = PRESETS[Math.floorMod(i, PRESETS.size)]
        presetIdx = Math.floorMod(i, PRESETS.size)
        timbre = p.timbre; chordSet = p.chord; x = p.x; y = p.y
        blend = if (hasSnip()) p.blend else 0f
        evolve = p.evolve; density = p.density; shimmer = p.shimmer; fadeSec = p.fade; wander = p.wander
    }

    /** Aktuelle Tonart (für die Anzeige). */
    @Volatile var curKey = 21
        private set
    /** Pegel (Anzeige). */
    @Volatile var meter = 0f
        private set
    /** Für die Anzeige: Positionen der aktiven Körner 0..1 (Quellposition) und Pan. */
    val grainView = FloatArray(32)
    val grainPan = FloatArray(32)
    @Volatile var grainCount = 0
        private set
    @Volatile var chordStep = 0
        private set

    fun hasSnip(): Boolean = snipLen > 0
    fun snipSeconds(): Float = snipLen.toFloat() / max(1, srN)

    // ---------------------------------------------------------------- Mitschnitt fürs Einfangen
    private var srN = 48000
    private var ringL = FloatArray(0)
    private var ringR = FloatArray(0)
    private var ringW = 0
    private var ringFill = 0
    @Volatile private var snipL = FloatArray(0)
    @Volatile private var snipR = FloatArray(0)
    @Volatile private var snipLen = 0
    @Volatile private var grabReq = 0f

    /** Die letzten [sec] Sekunden des Masters als Körner-Quelle übernehmen. */
    fun grab(sec: Float = 4f) { grabReq = sec.coerceIn(1f, 8f) }

    /** Eigenes Audio als Quelle (Mono oder Stereo, beliebige Länge – es wird auf 12 s begrenzt). */
    fun setSnip(l: FloatArray, r: FloatArray?, sr: Int) {
        val n = min(l.size, sr * 12)
        val a = l.copyOf(n)
        val b = (r ?: l).copyOf(n)
        normalize(a, b, n)
        snipL = a; snipR = b; snipLen = n
    }

    private fun normalize(a: FloatArray, b: FloatArray, n: Int) {
        var pk = 1e-6f
        for (i in 0 until n) pk = max(pk, max(abs(a[i]), abs(b[i])))
        val g = (0.7f / pk).coerceAtMost(8f)
        for (i in 0 until n) { a[i] *= g; b[i] *= g }
        // weiche Ränder
        val f = min(n / 4, srN / 50)
        for (i in 0 until f) { val t = i.toFloat() / f; a[i] *= t; b[i] *= t; a[n - 1 - i] *= t; b[n - 1 - i] *= t }
    }

    // ---------------------------------------------------------------- DSP-Zustand
    private var ready = false
    private var fade = 0f
    private var t = 0L
    private val SIN = FloatArray(4096) { sin(2 * PI * it / 4096).toFloat() }
    private fun sn(ph: Double): Float {
        val p = (ph - floor(ph)) * 4096.0
        val i = p.toInt()
        val f = (p - i).toFloat()
        val a = SIN[i and 4095]
        return a + (SIN[(i + 1) and 4095] - a) * f
    }

    /** Langsamer, glatter Zufall (Interpolation zwischen Zufallswerten). */
    private class Drift(var rate: Double) {
        var a = Math.random(); var b = Math.random(); var ph = Math.random()
        fun tick(dt: Double): Double {
            ph += rate * dt
            if (ph >= 1.0) { ph -= 1.0; a = b; b = Math.random() }
            val s = ph * ph * (3 - 2 * ph)
            return a + (b - a) * s
        }
    }

    private const val NV = 8
    private class Voice {
        var semi = 0.0          // Ziel-Halbton relativ zu C4
        var cur = 0.0           // gleitet zum Ziel
        var ph = Math.random()
        var mph = Math.random()
        var ph2 = Math.random()
        val dIdx = Drift(0.05)
        val dAmp = Drift(0.04)
        val dDet = Drift(0.03)
        var pan = 0f
        var amp = 0f
        var inc = 0.0; var inc2 = 0.0; var minc = 0.0; var idx = 0.0; var tgt = 0f
        var ph3 = Math.random()
        var bLp = 0f; var bBp = 0f
        var bell = 0f
        var bellNext = Math.random() * 3.0
    }
    private val voices = Array(NV) { Voice() }

    private const val NG = 32
    private class Grain {
        var on = false
        var pos = 0.0
        var rate = 1.0
        var len = 1
        var age = 0
        var gl = 1f; var gr = 1f
        var amp = 1f
    }
    private val grains = Array(NG) { Grain() }
    private var nextGrain = 0.0
    private var scan = 0.0
    private val dScan = Drift(0.02)
    private val dCut = Drift(0.03)

    private var plL: Plate? = null
    private var plR: Plate? = null
    private var lpL = 0f; private var bpL = 0f; private var lpR = 0f; private var bpR = 0f
    private var lp2L = 0f; private var lp2R = 0f
    private var hpL = 0f; private var hpR = 0f
    private var sx = 0.55f; private var sy = 0.65f
    private var shPh = 0.0
    private var vowel = 0.0
    private val dVow = Drift(0.05)
    // Formant-Filter (je Kanal 3 Bandpässe)
    private val fLp = Array(2) { FloatArray(3) }
    private val fBp = Array(2) { FloatArray(3) }
    private val FA = doubleArrayOf(730.0, 1090.0, 2440.0)   // „aah“
    private val FO = doubleArrayOf(570.0, 840.0, 2410.0)    // „ooh/oh“
    private val fG = FloatArray(3)
    private fun formant(c: Int, x: Float, vw: Double): Float {
        var out = 0f
        for (k in 0..2) {
            val hp = x - fLp[c][k] - 0.12f * fBp[c][k]
            fBp[c][k] += fG[k] * hp
            fLp[c][k] += fG[k] * fBp[c][k]
            out += fBp[c][k] * (if (k == 0) 1f else if (k == 1) 0.7f else 0.35f)
        }
        return out * 0.6f
    }
    private var nzS = 22222
    private fun nz(): Float { nzS = nzS * 1103515245 + 12345; return ((nzS ushr 9) and 0x7FFF) / 16384f - 1f }
    private var lastChordBar = -1L

    // Tonart: Akkordfolgen (Halbtöne relativ zum Grundton, Akkordtöne)
    private val MINOR_PROG = intArrayOf(0, 8, 3, 10)     // i – VI – III – VII
    private val MAJOR_PROG = intArrayOf(0, 9, 5, 7)      // I – vi – IV – V
    private fun chordTones(minorKey: Boolean, step: Int): IntArray {
        val prog = if (minorKey) MINOR_PROG else MAJOR_PROG
        val r = prog[Math.floorMod(step, prog.size)]
        when (chordSet) {
            1 -> return intArrayOf(r - 12, r - 5, r, r + 5, r + 7, r + 10, r + 14, r + 17)          // Sus2/Sus4, offen
            2 -> { val q = prog[0]; return intArrayOf(q - 24, q - 12, q - 5, q, q + 7, q + 12, q + 19, q + 24) } // nur Grundton/Quinte
            3 -> {
                val mc = if (minorKey) (r == 0) else (r == 9)
                val th = if (mc) 3 else 4
                return intArrayOf(r - 12, r - 5, r + th, r + 7, r + (if (mc) 10 else 11), r + 14, r + th + 12, r + 21)   // 7/9/13
            }
            4 -> return intArrayOf(r - 12, r - 5, r, r + 3, r + 7, r + 14, r + 15, r + 8)          // Moll add9 + kleine Sexte
        }
        // Qualität des Akkords auf der Stufe
        val minorChord = if (minorKey) (r == 0) else (r == 9)
        val third = if (minorChord) 3 else 4
        // Voicing: Grundton tief, Quinte, Terz oben, None/Sexte als Farbe, Oktave
        return intArrayOf(r - 12, r - 5, r, r + third, r + 7, r + 14, r + third + 12, r + 19)
    }

    private fun pickKey(): Int {
        if (!keyAuto) return keyManual
        var best: Dj.Deck? = null
        var bl = -1f
        for (d in Dj.decks) {
            if (!d.loaded() || !d.playing || d.key < 0) continue
            val g = d.fader * (if (d.idx == 0) 1f - Dj.xfade else Dj.xfade).coerceAtLeast(0.05f) + d.level
            if (g > bl) { bl = g; best = d }
        }
        best?.let { return it.effKey() }
        if (Ghost.curKey >= 0) return Ghost.curKey
        return keyManual
    }

    private fun init(sr: Int) {
        srN = sr
        ringL = FloatArray(sr * 8); ringR = FloatArray(sr * 8)
        plL = Plate(sr, 1.6f); plR = Plate(sr, 1.83f)
        for ((i, v) in voices.withIndex()) v.pan = (if (i % 2 == 0) -1f else 1f) * (0.2f + 0.6f * (i / NV.toFloat()))
        ready = true
    }

    /** Mitschnitt füttern (Master vor ATMOS), immer – auch wenn ATMOS aus ist (billig). */
    fun feed(l: FloatArray, r: FloatArray, n: Int, sr: Int) {
        if (!ready || srN != sr) init(sr)
        val sz = ringL.size
        for (i in 0 until n) {
            ringL[ringW] = l[i]; ringR[ringW] = r[i]
            ringW++; if (ringW >= sz) ringW = 0
        }
        ringFill = min(sz, ringFill + n)
        val g = grabReq
        if (g > 0f) {
            grabReq = 0f
            val len = min(ringFill, (g * sr).toInt())
            if (len > sr / 2) {
                val a = FloatArray(len); val b = FloatArray(len)
                var p = ringW - len; if (p < 0) p += sz
                for (i in 0 until len) { a[i] = ringL[p]; b[i] = ringR[p]; p++; if (p >= sz) p = 0 }
                normalize(a, b, len)
                snipL = a; snipR = b; snipLen = len
            }
        }
    }

    /** Hörbar? (für den Audio-Thread: sonst nichts rechnen) */
    fun active(): Boolean = on || fade > 0f

    /**
     * In den Master mischen (Stereo, vor Lautstärke/Master-Kette). [bpm] Master-Tempo (0 = frei), [bar0] Takt-Position
     * am Blockanfang (für den Akkordwechsel), [barStep] Takte je Probe.
     */
    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, bar0: Double, barStep: Double, vol: Float = 1f) {
        if (!ready || srN != sr) init(sr)
        if (!on && fade <= 0f) { meter *= 0.9f; return }
        val pl = plL!!; val pr = plR!!
        // Tonart / Akkord
        val key = pickKey().let { if (it < 0) 21 else it }
        curKey = key
        val minorKey = Keys.isMinor(key)
        val root = Keys.root(key)
        if (wander) {
            // ohne Master-Tempo: ein „Takt“ = 2 s
            val bp = if (bar0 >= 0) bar0 else t.toDouble() / sr / 2.0
            val cb = floor(bp / 8.0).toLong()
            if (cb != lastChordBar) { if (lastChordBar >= 0) chordStep = Math.floorMod(chordStep + 1, 4); lastChordBar = cb }
        } else if (!wander) chordStep = 0
        val tones = chordTones(minorKey, chordStep)
        // Grundton in eine angenehme Lage (A2…G#3 für den tiefsten Ton)
        val base = (root + 12 * (if (root >= 9) -1 else 0)).toDouble() - 12.0
        for (k in 0 until NV) voices[k].semi = base + tones[k]

        val dt = 1.0 / sr
        val ev = 0.25 + evolve * 2.5                // Bewegungsfaktor
        val fadeStep = 1f / (max(0.2f, fadeSec) * sr)
        val kxy = 1f / (0.08f * sr)
        val snLen = snipLen
        val sL = snipL; val sR = snipR
        val useG = blend > 0.01f && snLen > sr / 4
        val gBlend = if (snLen > sr / 4) blend else 0f
        val fmG = (1f - gBlend) * 0.16f
        val grG = gBlend * 1.1f
        val grainRate = (2.0 + density * 22.0)      // Körner je Sekunde
        val q = 0.55f
        var pk = 0f
        var gc = 0
        // je Block: Stimmen-Parameter (Gleiten, Zufalls-LFOs) und Filter
        val bdt = dt * n
        val gl = 1.0 - exp(-bdt / 1.5)
        for ((k, v) in voices.withIndex()) {
            v.cur += (v.semi - v.cur) * gl
            val idxD = v.dIdx.tick(bdt * ev)
            val ampD = v.dAmp.tick(bdt * ev)
            val detD = v.dDet.tick(bdt * ev) - 0.5
            val f = 261.6256 * 2.0.pow(v.cur / 12.0)
            v.inc = f * dt
            v.inc2 = f * (1.0 + detD * 0.008) * dt
            v.minc = f * (if (k % 3 == 2) 3.0 else 2.0) * dt
            v.idx = (0.15 + 2.2 * idxD * idxD * (0.4 + 0.6 * sx)) / (2 * PI)
            v.tgt = ((if (k == 0) 0.55 else 0.35) + (if (k >= 5) 0.65 else 0.45) * ampD * ampD).toFloat()
        }
        val wob = (dCut.tick(bdt * ev) - 0.5) * 0.12
        val oct = (sx + wob).coerceIn(0.0, 1.0)
        val fc = 140.0 * (14000.0 / 140.0).pow(oct)
        val gF = (2.0 * sin(PI * min(fc, sr * 0.2) / sr)).toFloat()
        val ampK = 1f - exp(-n / (0.04f * sr))
        if (timbre == 2) {
            vowel = dVow.tick(bdt * ev)
            for (k in 0..2) { val f = FA[k] + (FO[k] - FA[k]) * vowel; fG[k] = (2.0 * sin(PI * f / sr)).toFloat() }
        }
        for (v in voices) v.amp += (v.tgt - v.amp) * ampK
        for (i in 0 until n) {
            fade = if (on) min(1f, fade + fadeStep) else max(0f, fade - fadeStep)
            sx += (x - sx) * kxy
            sy += (y - sy) * kxy
            var l = 0f
            var r = 0f
            // ---- Fläche (Klangfarbe [timbre])
            if (fmG > 0.0005f) {
                val tb = timbre
                for (v in voices) {
                    v.mph += v.minc
                    v.ph += v.inc
                    v.ph2 += v.inc2
                    if (v.ph > 1e4) { v.ph -= floor(v.ph); v.ph2 -= floor(v.ph2); v.mph -= floor(v.mph); v.ph3 -= floor(v.ph3) }
                    val s: Float = when (tb) {
                        1, 7 -> {
                            // Streicher / Samt: drei verstimmte Sägezähne (Samt: weicher, mit Sinus-Grundton)
                            v.ph3 += v.inc * (1.0 - (v.inc2 / v.inc - 1.0))
                            val a1 = (2 * (v.ph - floor(v.ph)) - 1).toFloat()
                            val a2 = (2 * (v.ph2 - floor(v.ph2)) - 1).toFloat()
                            val a3 = (2 * (v.ph3 - floor(v.ph3)) - 1).toFloat()
                            val saw = (a1 + a2 + a3) * 0.28f
                            v.bLp += (if (tb == 7) 0.06f else 0.16f) * (saw - v.bLp)
                            if (tb == 7) v.bLp * 1.2f + sn(v.ph) * 0.35f else v.bLp * 1.6f
                        }
                        2 -> {
                            // Chor: Puls-artige Quelle, später global durch Formanten
                            val a1 = (2 * (v.ph - floor(v.ph)) - 1).toFloat()
                            val a2 = (2 * (v.ph2 - floor(v.ph2)) - 1).toFloat()
                            (a1 + a2) * 0.35f
                        }
                        3 -> {
                            // dunkle Drone: Sinus + Sub-Oktave + etwas Atem
                            (sn(v.ph) * 0.6f + sn(v.ph * 0.5) * 0.45f + sn(v.ph2 + sn(v.mph) * 0.08) * 0.25f + nz() * 0.015f)
                        }
                        4 -> {
                            // Glocken-Wolke: FM 3,5:1, jede Stimme schlägt ab und zu neu an
                            v.bellNext -= dt * ev
                            if (v.bellNext <= 0) { v.bell = 1f; v.bellNext = 1.5 + Math.random() * 5.0 }
                            v.bell *= 0.99996f
                            val m = sn(v.ph * 3.5) * (0.3 + 2.5 * v.bell) / (2 * PI)
                            (sn(v.ph + m) * (0.25f + v.bell)).toFloat()
                        }
                        5 -> {
                            // Wind: Rauschen durch einen engen Bandpass auf dem Akkordton
                            val f = (2.0 * sin(PI * min(0.45, v.inc))).toFloat()
                            val hp = nz() - v.bLp - 0.04f * v.bBp
                            v.bBp += f * hp; v.bLp += f * v.bBp
                            v.bBp * 0.9f
                        }
                        6 -> {
                            // Orgel: Zugriegel 16' 8' 4' 2 2/3'
                            sn(v.ph * 0.5) * 0.35f + sn(v.ph) * 0.5f + sn(v.ph * 2) * 0.3f + sn(v.ph * 3) * 0.18f + sn(v.ph2 * 4) * 0.08f
                        }
                        else -> {
                            val m = sn(v.mph) * v.idx
                            (sn(v.ph + m) + sn(v.ph2 + m * 0.7)) * 0.5f
                        }
                    }
                    val a = s * v.amp * TG[tb]
                    l += a * (0.5f - 0.5f * v.pan)
                    r += a * (0.5f + 0.5f * v.pan)
                }
                if (tb == 2) {
                    // Formanten „aah“ ↔ „ooh“ (wandert mit der Bewegung)
                    val vw = vowel
                    l = formant(0, l, vw); r = formant(1, r, vw)
                }
                l *= fmG; r *= fmG
            }
            // ---- Körner-Wolke
            if (useG) {
                nextGrain -= grainRate * dt
                if (nextGrain <= 0.0) {
                    nextGrain += 0.6 + Math.random() * 0.8
                    val g = grains.firstOrNull { !it.on }
                    if (g != null) {
                        // Lesekopf wandert langsam durch den Schnipsel, Körner streuen um ihn herum
                        scan = dScan.tick(0.0)
                        val spread = 0.08 + 0.3 * evolve
                        val c = (scan + (Math.random() - 0.5) * spread).coerceIn(0.0, 1.0)
                        val lenS = (0.08 + 0.37 * (1.0 - density * 0.5) * Math.random() + 0.05) * sr
                        val pick = Math.random()
                        g.rate = when {
                            pick < shimmer * 0.5 -> 2.0
                            pick < shimmer * 0.5 + 0.12 -> 0.5
                            pick < shimmer * 0.5 + 0.22 -> 1.5
                            else -> 1.0
                        } * (1.0 + (Math.random() - 0.5) * 0.004)
                        g.len = lenS.toInt().coerceAtLeast(64)
                        g.pos = c * max(1, snLen - (g.len * g.rate).toInt() - 4)
                        g.age = 0
                        val p = ((Math.random() * 2 - 1) * (0.3 + 0.6 * sy)).toFloat()
                        g.gl = sqrt(0.5f - 0.5f * p); g.gr = sqrt(0.5f + 0.5f * p)
                        g.amp = (0.6 + 0.4 * Math.random()).toFloat() / (if (g.rate > 1.9) 1.4f else 1f)
                        g.on = true
                    }
                }
                dScan.rate = 0.01 + 0.06 * evolve
                for (g in grains) {
                    if (!g.on) continue
                    val tt = g.age.toDouble() / g.len
                    val w = 0.5f - 0.5f * sn(tt + 0.25)   // Hann
                    val p = g.pos + g.age * g.rate
                    val ip = p.toInt()
                    if (ip + 1 >= snLen) { g.on = false; continue }
                    val fr = (p - ip).toFloat()
                    val a = sL[ip] + (sL[ip + 1] - sL[ip]) * fr
                    val b = sR[ip] + (sR[ip + 1] - sR[ip]) * fr
                    val m = (a + b) * 0.5f * w * g.amp
                    l += m * g.gl * grG
                    r += m * g.gr * grG
                    g.age++
                    if (g.age >= g.len) g.on = false
                    if (i == 0 && gc < NG) { grainView[gc] = (p / snLen).toFloat(); grainPan[gc] = g.gr - g.gl; gc++ }
                }
            }
            // ---- Tiefpass (X) mit leichter Eigenbewegung
            lpL += gF * bpL; val hl = l - lpL - q * bpL; bpL += gF * hl
            lpR += gF * bpR; val hr = r - lpR - q * bpR; bpR += gF * hr
            lp2L += gF * 0.7f * (lpL - lp2L); lp2R += gF * 0.7f * (lpR - lp2R)
            var fl = lp2L; var fr = lp2R
            // Tiefbass weg (sonst matscht es mit dem Track)
            hpL += 0.004f * (fl - hpL); hpR += 0.004f * (fr - hpR)
            fl -= hpL; fr -= hpR
            // ---- Hall (Y)
            val decay = 0.55f + 0.42f * sy
            val wet = 0.25f + 0.7f * sy
            val inV = (fl + fr) * 0.5f
            shPh += 0.0
            val vl = pl.tick(inV + fl * 0.3f, decay, 0.35f - 0.25f * shimmer, 0.75f)
            val vr = pr.tick(inV + fr * 0.3f, decay, 0.35f - 0.25f * shimmer, 0.75f)
            var outL = fl * (1f - 0.6f * wet) + vl * wet * 1.1f
            var outR = fr * (1f - 0.6f * wet) + vr * wet * 1.1f
            val g = fade * fade * level * 0.9f
            outL = tanh(outL * g * 1.2f) / 1.2f
            outR = tanh(outR * g * 1.2f) / 1.2f
            oL[i] += outL * vol
            oR[i] += outR * vol
            val a = max(abs(outL), abs(outR))
            if (a > pk) pk = a
        }
        grainCount = gc
        meter = max(pk, meter * 0.9f)
        if (!on && fade <= 0f) {
            pl.reset(); pr.reset()
            lpL = 0f; bpL = 0f; lpR = 0f; bpR = 0f; lp2L = 0f; lp2R = 0f
            for (gg in grains) gg.on = false
        }
        t += n
    }
}
