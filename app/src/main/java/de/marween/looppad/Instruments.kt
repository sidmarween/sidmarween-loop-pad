package de.marween.looppad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** Einstellungen und Pattern der virtuellen Instrumente (bleiben über Audio-Neustarts erhalten). */
object InstState {
    /** Schritte der Kompakt-Ansicht (1 Takt). */
    const val STEPS = 16
    /** Längstes Pattern: 4 Takte à 16 Schritte. */
    const val MAX = 64

    // ---------------------------------------------------------------- ACID (303-artig)
    val acidSemi = IntArray(MAX)
    val acidGate = BooleanArray(MAX)
    val acidAcc = BooleanArray(MAX)
    val acidSlide = BooleanArray(MAX)
    /** Tie: der Schritt hält den Ton des vorigen Schritts (längere Noten, kein neuer Anschlag). */
    val acidTie = BooleanArray(MAX)
    /** Lautstärke je Note (Kopf-Schritt), 0..1. */
    val acidVel = FloatArray(MAX) { 1f }
    /** Pattern-Länge in Schritten (16, 32, 48, 64). */
    @Volatile var acidLen = 16
    /** Glide-Zeit 0..1 (≈ 10 … 250 ms), Accent-Stärke, Verzerrung, Feinstimmung in Halbtönen. */
    @Volatile var acidGlide = 0.25f
    @Volatile var acidAccent = 0.6f
    @Volatile var acidDrive = 0.2f
    @Volatile var acidTune = 0
    @Volatile var acidOn = true
    @Volatile var acidFx = true
    @Volatile var acidSquare = false
    @Volatile var acidOct = 0
    @Volatile var acidCut = 0.35f
    @Volatile var acidRes = 0.7f
    @Volatile var acidEnv = 0.6f
    @Volatile var acidDec = 0.35f
    @Volatile var acidVol = 0.75f
    /** Live-Filter-Modulation vom Touchpad (−1 … +1). */
    @Volatile var acidCutMod = 0f
    // Song-Automation: NaN = Regler gilt
    @Volatile var acidCutAuto = Float.NaN
    @Volatile var acidVolAuto = Float.NaN
    @Volatile var drumVolAuto = Float.NaN
    fun acidCutNow() = acidCutAuto.let { if (it.isNaN()) acidCut else it }
    fun acidVolNow() = acidVolAuto.let { if (it.isNaN()) acidVol else it }
    fun drumVolNow() = drumVolAuto.let { if (it.isNaN()) drumVol else it }

    /** Zufalls-Linie (Algorithmus nach „Acid Banger“, David Whiting, CC BY 4.0). */
    fun acidRandom() = InstPresets.randomAcid()

    fun acidClear() {
        java.util.Arrays.fill(acidGate, false)
        java.util.Arrays.fill(acidAcc, false)
        java.util.Arrays.fill(acidSlide, false)
        java.util.Arrays.fill(acidTie, false)
        java.util.Arrays.fill(acidSemi, 0)
    }

    fun acidHasNotes() = (0 until acidLen).any { acidGate[it] }

    // ---------------------------------------------------------------- Lebendige Patterns
    /** 0 = aus … 1 = wild: kleine Variationen beim Abspielen (Ghost-Notes, Lautstärke, Töne), Pattern bleibt unverändert. */
    @Volatile var liveAmt = 0f
    /** Fill im letzten Schlag vor jeder 8-Takt-Phrase und Crash auf der Eins danach. */
    @Volatile var liveFills = true
    /** Phrasenlänge in Takten für Fills. */
    @Volatile var livePhrase = 8

    // ---------------------------------------------------------------- DRUM (909-artig)
    const val DV = 11
    val drumNames = arrayOf("BD", "SD", "LT", "MT", "HT", "RS", "CP", "CH", "OH", "CR", "RD")
    val drumLong = arrayOf("Bass Drum", "Snare", "Low Tom", "Mid Tom", "High Tom", "Rimshot", "Clap", "Closed Hat", "Open Hat", "Crash", "Ride")
    /** 0 = aus, 1 = an, 2 = Accent. */
    val drumSteps = Array(DV) { IntArray(MAX) }
    /** Lautstärke je Schlag, 0..1. */
    val drumVel = Array(DV) { FloatArray(MAX) { 1f } }
    @Volatile var drumLen = 16
    /** Stumm geschaltete Instrumente. */
    val drumMute = BooleanArray(DV)
    val drumTune = FloatArray(DV) { 0.5f }
    /** Klang-Modell je Stimme (0 = 909; siehe [drumModelNames]). */
    val drumModel = IntArray(DV)
    val drumModelNames: Array<Array<String>> = arrayOf(
        arrayOf("909", "808 Boom", "Techno Rumble", "Hardstyle", "Deep", "Minimal Click", "Lo-Fi", "Punchy House", "808 verzerrt", "Akustik", "Psytrance"),
        arrayOf("909", "808", "Trap", "Lo-Fi", "Akustik", "Electro Rim", "Besen"),
        arrayOf("909 Tom", "Conga", "Bongo", "Syn-Tom", "Akustik-Tom", "Timbale", "Log Drum"),
        arrayOf("909 Tom", "Conga", "Bongo", "Syn-Tom", "Akustik-Tom", "Timbale", "Log Drum"),
        arrayOf("909 Tom", "Conga", "Bongo", "Syn-Tom", "Akustik-Tom", "Timbale", "Log Drum"),
        arrayOf("909 Rim", "Clave", "Cowbell", "Woodblock", "Triangel", "Guiro"),
        arrayOf("909 Clap", "808 Clap", "Snap", "Big Clap", "Gated Clap", "Hände (Gruppe)"),
        arrayOf("909", "808", "Shaker", "Tambourin", "Akustik", "Cabasa"),
        arrayOf("909", "808", "Shaker", "Tambourin", "Akustik", "Cabasa"),
        arrayOf("Crash", "Splash", "China", "Reverse"),
        arrayOf("Ride", "Agogo", "Ride-Glocke", "FM-Glocke")
    )
    fun drumModelName(v: Int): String = drumModelNames[v][drumModel[v].coerceIn(0, drumModelNames[v].size - 1)]
    val drumDecay = FloatArray(DV) { 0.5f }
    val drumLevel = FloatArray(DV) { 0.8f }
    @Volatile var drumOn = true
    @Volatile var drumFx = false
    @Volatile var drumDrive = 0.15f
    @Volatile var drumVol = 0.8f
    @Volatile var drumSel = 0
    var drumPreset = -1

    val presetNames = arrayOf("House", "Techno", "Breakbeat", "Electro")

    fun drumApplyPreset(i: Int) {
        for (v in 0 until DV) java.util.Arrays.fill(drumSteps[v], 0)
        fun set(v: Int, vararg st: Int) { for (s in st) drumSteps[v][s] = 1 }
        fun acc(v: Int, vararg st: Int) { for (s in st) drumSteps[v][s] = 2 }
        when (i % presetNames.size) {
            0 -> { // House
                acc(0, 0, 4, 8, 12); set(6, 4, 12); set(8, 2, 6, 10, 14); set(7, 0, 4, 8, 12); set(5, 7, 15)
            }
            1 -> { // Techno
                acc(0, 0, 4, 8, 12); set(7, 2, 6, 10, 14); set(7, 0, 1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 15); set(8, 2, 10)
                set(6, 4, 12); set(10, 0, 4, 8, 12); set(5, 3, 11, 14)
            }
            2 -> { // Breakbeat
                acc(0, 0); set(0, 10); set(0, 3); acc(1, 4, 12); set(1, 7, 9, 15)
                set(7, 0, 2, 4, 6, 8, 10, 12, 14); set(8, 14)
            }
            else -> { // Electro
                acc(0, 0); set(0, 6, 10); acc(1, 4, 12); set(6, 4, 12); set(7, 0, 2, 4, 6, 8, 10, 12, 14)
                set(2, 13); set(3, 14); set(4, 15)
            }
        }
        for (v in 0 until DV) for (k in STEPS until MAX) drumSteps[v][k] = drumSteps[v][k % STEPS]
        drumPreset = i % presetNames.size
    }

    fun drumClear() {
        for (v in 0 until DV) java.util.Arrays.fill(drumSteps[v], 0)
    }

    fun drumHasNotes() = drumSteps.any { row -> (0 until drumLen).any { row[it] > 0 } }

    // ---------------------------------------------------------------- SYNTH (polyfoner Lead-/Pad-Synth) – mehrere Spuren

    /** Eine Synth-Spur: Noten + Klang. */
    class SynTrack {
        val semi = IntArray(MAX)
        val gate = BooleanArray(MAX)
        /** Halten: der Schritt verlängert die Note des vorigen Schritts. */
        val tie = BooleanArray(MAX)
        val vel = FloatArray(MAX) { 1f }
        /** Akkord je Note: 0 = Akkord-Einstellung der Spur, k > 0 = synChordIv[k − 1]. */
        val chd = IntArray(MAX)
        /** Arpeggiator: 0 aus, 1 auf, 2 ab, 3 auf-ab, 4 zufällig · Rate 0 = 1/8, 1 = 1/16, 2 = 1/32 · Oktaven 1–3. */
        @Volatile var arp = 0
        @Volatile var arpRate = 1
        @Volatile var arpOct = 1
        @Volatile var len = 16
        @Volatile var on = true
        @Volatile var fx = true
        @Volatile var oct = 0
        /** 0 SAW, 1 SQUARE, 2 SUPERSAW, 3 PLUCK, 4 GLOCKE (FM), 5 NES-Puls, 6 NES-Dreieck, 7 Noise, 8 SID-PWM */
        @Volatile var wave = 2
        /** 0 einzeln, 1 Dur, 2 Moll, 3 Sus4, 4 Dur7, 5 Moll7, 6 Oktave */
        @Volatile var chord = 0
        @Volatile var cut = 0.55f
        @Volatile var res = 0.25f
        @Volatile var atk = 0.05f
        @Volatile var rel = 0.35f
        @Volatile var vol = 0.6f
        @Volatile var detune = 0.35f
        @Volatile var env = 0.4f
        @Volatile var cutMod = 0f
        /** Transponierung in Halbtönen (Ghost-Producer: an die Tonart des Tracks angepasst). */
        @Volatile var transpose = 0
        @Volatile var cutAuto = Float.NaN
        @Volatile var volAuto = Float.NaN
        fun cutNow() = cutAuto.let { if (it.isNaN()) cut else it }
        fun volNow() = volAuto.let { if (it.isNaN()) vol else it }
        fun clear() {
            java.util.Arrays.fill(gate, false); java.util.Arrays.fill(tie, false)
            java.util.Arrays.fill(semi, 0); java.util.Arrays.fill(vel, 1f); java.util.Arrays.fill(chd, 0)
        }
        fun hasNotes() = (0 until len).any { gate[it] }
        fun soundDefaults(kind: Int) {
            when (kind) {
                1 -> { wave = 2; oct = 0; cut = 0.45f; res = 0.15f; atk = 0.45f; rel = 0.6f; vol = 0.45f; detune = 0.5f; env = 0.15f; chord = 0 } // Fläche
                2 -> { wave = 0; oct = -1; cut = 0.32f; res = 0.35f; atk = 0f; rel = 0.12f; vol = 0.65f; detune = 0.08f; env = 0.55f; chord = 0 } // Bass
                else -> { wave = 2; oct = 0; cut = 0.55f; res = 0.25f; atk = 0.05f; rel = 0.35f; vol = 0.6f; detune = 0.35f; env = 0.4f; chord = 0 }
            }
        }
        fun toJson(): JSONObject {
            val j = JSONObject()
            j.put("semi", JSONArray().apply { semi.forEach { put(it) } })
            j.put("gate", String(CharArray(MAX) { if (gate[it]) '1' else '0' }))
            j.put("tie", String(CharArray(MAX) { if (tie[it]) '1' else '0' }))
            j.put("vel", JSONArray().apply { vel.forEach { put(Math.round(it * 1000) / 1000.0) } })
            j.put("len", len); j.put("on", on); j.put("fx", fx); j.put("oct", oct); j.put("wave", wave); j.put("chord", chord)
            j.put("cut", cut.toDouble()); j.put("res", res.toDouble()); j.put("atk", atk.toDouble()); j.put("rel", rel.toDouble())
            j.put("vol", vol.toDouble()); j.put("detune", detune.toDouble()); j.put("env", env.toDouble())
            j.put("chd", JSONArray().apply { chd.forEach { put(it) } })
            j.put("arp", arp); j.put("arpRate", arpRate); j.put("arpOct", arpOct)
            return j
        }
        fun fromJson(j: JSONObject) {
            clear()
            j.optJSONArray("semi")?.let { a -> for (k in 0 until min(MAX, a.length())) semi[k] = a.optInt(k) }
            j.optString("gate").let { str -> for (k in 0 until min(MAX, str.length)) gate[k] = str[k] == '1' }
            j.optString("tie").let { str -> for (k in 0 until min(MAX, str.length)) tie[k] = str[k] == '1' }
            j.optJSONArray("vel")?.let { a -> for (k in 0 until min(MAX, a.length())) vel[k] = a.optDouble(k, 1.0).toFloat() }
            len = j.optInt("len", 16).coerceIn(16, MAX); on = j.optBoolean("on", true); fx = j.optBoolean("fx", true)
            oct = j.optInt("oct", 0); wave = j.optInt("wave", 2); chord = j.optInt("chord", 0)
            cut = j.optDouble("cut", 0.55).toFloat(); res = j.optDouble("res", 0.25).toFloat(); atk = j.optDouble("atk", 0.05).toFloat()
            rel = j.optDouble("rel", 0.35).toFloat(); vol = j.optDouble("vol", 0.6).toFloat(); detune = j.optDouble("detune", 0.35).toFloat(); env = j.optDouble("env", 0.4).toFloat()
            j.optJSONArray("chd")?.let { a -> for (k in 0 until min(MAX, a.length())) chd[k] = a.optInt(k) }
            arp = j.optInt("arp", 0); arpRate = j.optInt("arpRate", 1); arpOct = j.optInt("arpOct", 1)
        }
    }

    const val SYN_TRACKS = 3
    val synTracks = Array(SYN_TRACKS) { SynTrack().apply { soundDefaults(it) } }
    /** Die Synth-Spur, die Editor, Touchpad und Presets gerade bearbeiten. */
    @Volatile var synCur = 0
    val syn: SynTrack get() = synTracks[synCur.coerceIn(0, SYN_TRACKS - 1)]
    val synSoundNames = arrayOf("Lead", "Fläche", "Bass")

    val synSemi: IntArray get() = syn.semi
    val synGate: BooleanArray get() = syn.gate
    val synTie: BooleanArray get() = syn.tie
    val synVel: FloatArray get() = syn.vel
    val synChd: IntArray get() = syn.chd
    var synArp: Int get() = syn.arp; set(v) { syn.arp = v }
    var synArpRate: Int get() = syn.arpRate; set(v) { syn.arpRate = v }
    var synArpOct: Int get() = syn.arpOct; set(v) { syn.arpOct = v }
    val arpNames = arrayOf("AUS", "AUF", "AB", "AUF-AB", "ZUFALL")
    val arpRates = arrayOf("1/8", "1/16", "1/32")
    var synLen: Int get() = syn.len; set(v) { syn.len = v }
    var synOn: Boolean get() = syn.on; set(v) { syn.on = v }
    var synFx: Boolean get() = syn.fx; set(v) { syn.fx = v }
    var synOct: Int get() = syn.oct; set(v) { syn.oct = v }
    var synWave: Int get() = syn.wave; set(v) { syn.wave = v }
    var synChord: Int get() = syn.chord; set(v) { syn.chord = v }
    var synCut: Float get() = syn.cut; set(v) { syn.cut = v }
    var synRes: Float get() = syn.res; set(v) { syn.res = v }
    var synAtk: Float get() = syn.atk; set(v) { syn.atk = v }
    var synRel: Float get() = syn.rel; set(v) { syn.rel = v }
    var synVol: Float get() = syn.vol; set(v) { syn.vol = v }
    var synDetune: Float get() = syn.detune; set(v) { syn.detune = v }
    var synEnv: Float get() = syn.env; set(v) { syn.env = v }
    var synCutMod: Float get() = syn.cutMod; set(v) { syn.cutMod = v }
    val synWaveNames = arrayOf("SAW", "SQR", "SUPER", "PLUCK", "GLOCKE", "NES ▢", "NES △", "NOISE", "SID", "GB-WAVE", "FM-CHIP")
    /** Chiptune-Klänge (Klang-Nr. ≥ 5): Akkorde laufen als schnelles Chip-Arpeggio, Lautstärke in 16 Stufen. */
    fun synIsChip(w: Int) = w >= 5
    val synChordNames = arrayOf("EINZELN", "DUR", "MOLL", "SUS4", "DUR7", "MOLL7", "OKTAVE", "VERM.")
    /** Kurzname hinter dem Grundton (C, Cm, C7 …). */
    val chordSuffix = arrayOf("", "", "m", "sus4", "7", "m7", "8", "°")
    // ---------------------------------------------------------------- Tonleiter (Piano-Roll: Hervorheben + Einrasten)
    @Volatile var scaleType = 0
    @Volatile var scaleRoot = 0
    @Volatile var scaleSnap = false
    val scaleNames = arrayOf("AUS", "DUR", "MOLL", "MOLL HARM.", "DORISCH", "PHRYGISCH", "PENTA DUR", "PENTA MOLL", "BLUES")
    private val scaleIv = arrayOf(intArrayOf(), intArrayOf(0, 2, 4, 5, 7, 9, 11), intArrayOf(0, 2, 3, 5, 7, 8, 10), intArrayOf(0, 2, 3, 5, 7, 8, 11),
        intArrayOf(0, 2, 3, 5, 7, 9, 10), intArrayOf(0, 1, 3, 5, 7, 8, 10), intArrayOf(0, 2, 4, 7, 9), intArrayOf(0, 3, 5, 7, 10), intArrayOf(0, 3, 5, 6, 7, 10))
    val scaleNoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
    fun inScale(s: Int): Boolean = scaleType == 0 || scaleIv[scaleType].contains(Math.floorMod(s - scaleRoot, 12))
    fun isScaleRoot(s: Int): Boolean = scaleType != 0 && Math.floorMod(s - scaleRoot, 12) == 0
    fun scaleLabel(): String = if (scaleType == 0) "AUS" else scaleNoteNames[scaleRoot] + " " + scaleNames[scaleType]
    /** Ton auf die Tonleiter ziehen (nur wenn Einrasten an). [dir] = Richtung (+1/−1), 0 = nächster. */
    fun snapScale(s: Int, dir: Int = 0): Int {
        if (!scaleSnap || scaleType == 0 || inScale(s)) return s
        if (dir != 0) {
            var t = s
            repeat(12) { t += if (dir > 0) 1 else -1; if (inScale(t)) return t }
            return s
        }
        for (d in 1..6) { if (inScale(s - d)) return s - d; if (inScale(s + d)) return s + d }
        return s
    }

    val synChordIv = arrayOf(intArrayOf(0), intArrayOf(0, 4, 7), intArrayOf(0, 3, 7), intArrayOf(0, 5, 7), intArrayOf(0, 4, 7, 10), intArrayOf(0, 3, 7, 10), intArrayOf(0, 12), intArrayOf(0, 3, 6))

    /** Akkord-Intervalle einer Note (eigener Akkord der Note oder der Spur). */
    fun chordOf(t: SynTrack, k: Int): IntArray {
        val c = t.chd[k]
        return if (c > 0) synChordIv[(c - 1).coerceIn(0, synChordIv.size - 1)] else synChordIv[t.chord.coerceIn(0, synChordIv.size - 1)]
    }

    // ---------------------------------------------------------------- Akkordfolgen

    class Progression(val name: String, val minor: Boolean, val degrees: IntArray, val quality: IntArray? = null)
    /** quality: Index in synChordIv (überschreibt den leitereigenen Akkord). */
    val progressions = listOf(
        Progression("Pop  I – V – vi – IV", false, intArrayOf(0, 4, 5, 3)),
        Progression("Gefühlvoll  vi – IV – I – V", false, intArrayOf(5, 3, 0, 4)),
        Progression("50er  I – vi – IV – V", false, intArrayOf(0, 5, 3, 4)),
        Progression("Rock  I – IV – V – IV", false, intArrayOf(0, 3, 4, 3)),
        Progression("Jazz  ii7 – V7 – I – I", false, intArrayOf(1, 4, 0, 0), intArrayOf(5, 4, 1, 1)),
        Progression("Moll-Pop  i – VI – III – VII", true, intArrayOf(0, 5, 2, 6)),
        Progression("Deep House  i – VII – VI – VII", true, intArrayOf(0, 6, 5, 6)),
        Progression("Dunkel  i – iv – v – i", true, intArrayOf(0, 3, 4, 0)),
        Progression("Andalusisch  i – VII – VI – V", true, intArrayOf(0, 6, 5, 4), intArrayOf(2, 1, 1, 1)),
        Progression("Trance  i – VI – VII – v", true, intArrayOf(0, 5, 6, 4))
    )
    private val MAJ = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MIN = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJ_Q = intArrayOf(1, 2, 2, 1, 1, 2, 7)
    private val MIN_Q = intArrayOf(2, 7, 1, 2, 2, 1, 1)
    val rhythmNames = arrayOf("Gehalten (Flächen)", "Viertel", "Achtel", "Offbeat (House-Pumpen)", "Stabs (1 + 2+ + 4)")

    /** Name eines Akkords der Folge in Tonart [root] (z. B. „Am“). */
    fun progChordName(pr: Progression, i: Int, root: Int): String {
        val d = pr.degrees[i]
        val sc = if (pr.minor) MIN else MAJ
        val q = pr.quality?.get(i) ?: (if (pr.minor) MIN_Q else MAJ_Q)[d]
        return scaleNoteNames[Math.floorMod(root + sc[d], 12)] + chordSuffix[q]
    }

    /**
     * Akkordfolge in die aktuelle Synth-Spur schreiben: [root] = Grundton der Tonart, [steps] Schritte je Akkord
     * (16 = 1 Takt, 8 = ½ Takt), [rhythm] siehe [rhythmNames]. Setzt Länge und Tonleiter passend.
     */
    fun writeProgression(pr: Progression, root: Int, steps: Int, rhythm: Int) {
        val t = syn
        t.clear()
        val sc = if (pr.minor) MIN else MAJ
        val n = pr.degrees.size
        val total = (n * steps).coerceIn(16, MAX)
        t.len = total
        var prevRoot = Int.MIN_VALUE
        for (i in 0 until n) {
            val s0 = i * steps
            if (s0 >= MAX) break
            val d = pr.degrees[i]
            val q = pr.quality?.get(i) ?: (if (pr.minor) MIN_Q else MAJ_Q)[d]
            // Grundton nahe am vorigen (keine großen Sprünge), Bereich etwa G3 … F#4
            var r = Math.floorMod(root + sc[d], 12)
            if (r > 6) r -= 12
            if (prevRoot != Int.MIN_VALUE && abs(r - prevRoot) > 7) r += if (r < prevRoot) 12 else -12
            prevRoot = r
            fun note(at: Int, len: Int, v: Float) {
                if (at >= MAX) return
                for (k in at until min(MAX, at + max(1, len))) {
                    t.gate[k] = true; t.tie[k] = k != at; t.semi[k] = r; t.chd[k] = q + 1; t.vel[k] = v
                }
            }
            when (rhythm) {
                0 -> note(s0, steps, 0.9f)
                1 -> { var k = 0; while (k < steps) { note(s0 + k, 3, if (k % 16 == 0) 1f else 0.8f); k += 4 } }
                2 -> { var k = 0; while (k < steps) { note(s0 + k, 1, if (k % 4 == 0) 0.95f else 0.7f); k += 2 } }
                3 -> { var k = 2; while (k < steps) { note(s0 + k, 2, 0.9f); k += 4 } }
                else -> { for (k in intArrayOf(0, 6, 12)) if (k < steps) note(s0 + k, 2, if (k == 0) 1f else 0.85f) }
            }
        }
        scaleRoot = Math.floorMod(root, 12)
        if (scaleType == 0) scaleType = if (pr.minor) 2 else 1
    }

    /**
     * Erkannte Akkorde (je Takt, [Keys]-Zahlen: 0..11 Dur, 12..23 Moll, -1 = Takt auslassen) in Spur [track] schreiben.
     * [asBass]: nur Grundtöne eine Oktave tiefer im Offbeat-Rhythmus, sonst gehaltene Akkorde (Fläche).
     */
    fun writeChords(track: Int, chords: IntArray, asBass: Boolean) {
        val t = synTracks[track.coerceIn(0, SYN_TRACKS - 1)]
        t.clear()
        val bars = chords.size.coerceIn(1, MAX / STEPS)
        t.len = bars * STEPS
        var prevRoot = Int.MIN_VALUE
        for (b in 0 until bars) {
            val c = chords[b]
            if (c < 0) continue
            var r = c % 12
            if (r > 6) r -= 12
            if (prevRoot != Int.MIN_VALUE && abs(r - prevRoot) > 7) r += if (r < prevRoot) 12 else -12
            prevRoot = r
            val q = if (c < 12) 1 else 2
            val s0 = b * STEPS
            fun note(at: Int, len: Int, semi: Int, chd: Int, v: Float) {
                for (k in at until min(MAX, at + max(1, len))) { t.gate[k] = true; t.tie[k] = k != at; t.semi[k] = semi; t.chd[k] = chd; t.vel[k] = v }
            }
            if (asBass) {
                var k = 2
                while (k < STEPS) { note(s0 + k, 2, r, 1, 0.9f); k += 4 }
            } else note(s0, STEPS, r, q + 1, 0.85f)
        }
    }

    fun synClear() = syn.clear()

    fun synHasNotes() = syn.hasNotes()

    /** Zufällige Melodie in Moll-Pentatonik mit gehaltenen Tönen. */
    fun synRandom() {
        synClear()
        val scale = intArrayOf(0, 3, 5, 7, 10, 12, 15)
        val r = java.util.Random()
        var k = 0
        while (k < STEPS) {
            if (r.nextFloat() < 0.6f) {
                synGate[k] = true
                synSemi[k] = scale[r.nextInt(scale.size)]
                synVel[k] = 0.7f + r.nextFloat() * 0.3f
                val hold = if (r.nextFloat() < 0.4f) 1 + r.nextInt(3) else 0
                for (h in 1..hold) if (k + h < STEPS) { synGate[k + h] = true; synTie[k + h] = true; synSemi[k + h] = synSemi[k] }
                k += 1 + hold
            } else k++
        }
        for (i in STEPS until MAX) { synGate[i] = synGate[i % STEPS]; synTie[i] = synTie[i % STEPS]; synSemi[i] = synSemi[i % STEPS]; synVel[i] = synVel[i % STEPS] }
    }

    // ---------------------------------------------------------------- Projekte

    // ---------------------------------------------------------------- Patterns A–D je Instrument

    /** Instrumente mit Pattern-Speicher: 0 = ACID, 1 = DRUM, 2.. = SYNTH-Spuren. */
    const val INSTS = 2 + SYN_TRACKS
    const val PATS = 4
    val instNames = Array(INSTS) { when (it) { 0 -> "ACID"; 1 -> "DRUM"; else -> "SYNTH ${it - 1}" } }
    private fun trk(inst: Int) = synTracks[(inst - 2).coerceIn(0, SYN_TRACKS - 1)]
    fun patLetter(p: Int) = "ABCD"[p.coerceIn(0, PATS - 1)].toString()

    /** Gespeichertes Pattern (Kopien der Schritt-Arrays + Länge). */
    class Pat(val len: Int, val ints: Array<IntArray>, val bools: Array<BooleanArray>, val floats: Array<FloatArray>) {
        fun copy() = Pat(len, Array(ints.size) { ints[it].copyOf() }, Array(bools.size) { bools[it].copyOf() }, Array(floats.size) { floats[it].copyOf() })
        fun hasNotes(inst: Int): Boolean = when (inst) {
            1 -> ints.any { r -> (0 until len).any { r[it] > 0 } }
            else -> (0 until len).any { bools[0][it] }
        }
        fun toJson(): JSONObject = JSONObject().apply {
            put("len", len)
            put("i", JSONArray().apply { ints.forEach { a -> put(JSONArray().apply { a.forEach { put(it) } }) } })
            put("b", JSONArray().apply { bools.forEach { a -> put(String(CharArray(a.size) { k -> if (a[k]) '1' else '0' })) } })
            put("f", JSONArray().apply { floats.forEach { a -> put(JSONArray().apply { a.forEach { put(Math.round(it * 1000) / 1000.0) } }) } })
        }
    }

    /** Die gerade gespielten/bearbeiteten Arrays je Instrument. */
    private fun liveInts(inst: Int): Array<IntArray> = when (inst) { 0 -> arrayOf(acidSemi); 1 -> drumSteps; else -> arrayOf(trk(inst).semi, trk(inst).chd) }
    private fun liveBools(inst: Int): Array<BooleanArray> = when (inst) { 0 -> arrayOf(acidGate, acidAcc, acidSlide, acidTie); 1 -> emptyArray(); else -> arrayOf(trk(inst).gate, trk(inst).tie) }
    private fun liveFloats(inst: Int): Array<FloatArray> = when (inst) { 0 -> arrayOf(acidVel); 1 -> drumVel; else -> arrayOf(trk(inst).vel) }
    fun instLen(inst: Int): Int = when (inst) { 0 -> acidLen; 1 -> drumLen; else -> trk(inst).len }
    private fun setInstLen(inst: Int, n: Int) { val v = n.coerceIn(16, MAX); when (inst) { 0 -> acidLen = v; 1 -> drumLen = v; else -> trk(inst).len = v } }
    fun instOn(inst: Int): Boolean = when (inst) { 0 -> acidOn; 1 -> drumOn; else -> trk(inst).on }
    fun setInstOn(inst: Int, on: Boolean) { when (inst) { 0 -> acidOn = on; 1 -> drumOn = on; else -> trk(inst).on = on } }
    fun instHasNotes(inst: Int): Boolean = when (inst) { 0 -> acidHasNotes(); 1 -> drumHasNotes(); else -> trk(inst).hasNotes() }
    /** Pattern-Index des gerade bearbeiteten Synth. */
    val synInst: Int get() = 2 + synCur

    val bank = Array(INSTS) { arrayOfNulls<Pat>(PATS) }

    // ---------------------------------------------------------------- Groove je Spur (0 ACID, 1 DRUM, 2.. SYNTH)
    /** Swing der Spur 0..1, < 0 = globaler Swing. */
    val grooveSwing = FloatArray(INSTS) { -1f }
    /** Humanize: zufällige Verzögerung (bis ~20 ms) und Lautstärke-Schwankung (bis ±30 %). */
    val humTime = FloatArray(INSTS)
    val humVel = FloatArray(INSTS)
    /** Gerade aktives Pattern je Instrument (0..3 = A..D). */
    val curPat = IntArray(INSTS)

    fun capturePat(inst: Int): Pat = Pat(instLen(inst), Array(liveInts(inst).size) { liveInts(inst)[it].copyOf() },
        Array(liveBools(inst).size) { liveBools(inst)[it].copyOf() }, Array(liveFloats(inst).size) { liveFloats(inst)[it].copyOf() })

    private fun loadPat(inst: Int, p: Pat?) {
        val li = liveInts(inst); val lb = liveBools(inst); val lf = liveFloats(inst)
        for (k in li.indices) { java.util.Arrays.fill(li[k], 0); p?.ints?.getOrNull(k)?.let { System.arraycopy(it, 0, li[k], 0, min(it.size, MAX)) } }
        for (k in lb.indices) { java.util.Arrays.fill(lb[k], false); p?.bools?.getOrNull(k)?.let { System.arraycopy(it, 0, lb[k], 0, min(it.size, MAX)) } }
        for (k in lf.indices) { java.util.Arrays.fill(lf[k], 1f); p?.floats?.getOrNull(k)?.let { System.arraycopy(it, 0, lf[k], 0, min(it.size, MAX)) } }
        setInstLen(inst, p?.len ?: 16)
    }

    /** Pattern wechseln: aktuelles sichern, anderes laden. (Auch aus dem Audio-Thread an der Eins.) */
    @Synchronized fun selectPat(inst: Int, p: Int) {
        if (inst !in 0 until INSTS || p !in 0 until PATS || p == curPat[inst]) return
        bank[inst][curPat[inst]] = capturePat(inst)
        loadPat(inst, bank[inst][p])
        curPat[inst] = p
    }

    /** Aktuelles Pattern in Platz [to] kopieren. */
    @Synchronized fun copyPatTo(inst: Int, to: Int) {
        if (inst !in 0 until INSTS || to !in 0 until PATS || to == curPat[inst]) return
        bank[inst][to] = capturePat(inst)
    }

    /** Pattern [p] von [inst] (das aktive aus den Live-Daten), null = leer. */
    fun patFor(inst: Int, p: Int): Pat? = if (p == curPat[inst]) capturePat(inst) else bank[inst].getOrNull(p)

    fun patHasNotes(inst: Int, p: Int): Boolean = if (p == curPat[inst]) instHasNotes(inst) else bank[inst][p]?.hasNotes(inst) == true

    fun resetBanks() {
        for (b in bank) java.util.Arrays.fill(b, null)
        java.util.Arrays.fill(curPat, 0)
    }

    private fun banksToJson(): JSONArray {
        val a = JSONArray()
        for (inst in 0 until INSTS) {
            val o = JSONObject()
            o.put("cur", curPat[inst])
            val ps = JSONArray()
            for (p in 0 until PATS) ps.put(if (p == curPat[inst]) JSONObject.NULL else bank[inst][p]?.toJson() ?: JSONObject.NULL)
            o.put("pats", ps)
            a.put(o)
        }
        return a
    }

    private fun banksFromJson(a: JSONArray?) {
        resetBanks()
        if (a == null) return
        for (inst in 0 until min(INSTS, a.length())) {
            val o = a.optJSONObject(inst) ?: continue
            curPat[inst] = o.optInt("cur", 0).coerceIn(0, PATS - 1)
            val ps = o.optJSONArray("pats") ?: continue
            for (p in 0 until min(PATS, ps.length())) {
                val q = ps.optJSONObject(p) ?: continue
                try {
                    val ia = q.optJSONArray("i"); val ba = q.optJSONArray("b"); val fa = q.optJSONArray("f")
                    val ints = Array(ia?.length() ?: 0) { r -> val x = ia!!.getJSONArray(r); IntArray(MAX) { k -> if (k < x.length()) x.optInt(k) else 0 } }
                    val bools = Array(ba?.length() ?: 0) { r -> val str = ba!!.optString(r); BooleanArray(MAX) { k -> k < str.length && str[k] == '1' } }
                    val floats = Array(fa?.length() ?: 0) { r -> val x = fa!!.getJSONArray(r); FloatArray(MAX) { k -> if (k < x.length()) x.optDouble(k, 1.0).toFloat() else 1f } }
                    bank[inst][p] = Pat(q.optInt("len", 16).coerceIn(16, MAX), ints, bools, floats)
                } catch (e: Exception) {}
            }
        }
    }

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("banks", banksToJson())
        j.put("groove", JSONArray().apply { for (i in 0 until INSTS) put(JSONArray().put(grooveSwing[i].toDouble()).put(humTime[i].toDouble()).put(humVel[i].toDouble())) })
        fun ia(a: IntArray) = JSONArray().apply { a.forEach { put(it) } }
        fun ba(a: BooleanArray) = JSONArray().apply { a.forEach { put(it) } }
        fun fa(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
        j.put("acidSemi", ia(acidSemi)); j.put("acidGate", ba(acidGate)); j.put("acidAcc", ba(acidAcc)); j.put("acidSlide", ba(acidSlide))
        j.put("acidOn", acidOn); j.put("acidFx", acidFx); j.put("acidSquare", acidSquare); j.put("acidOct", acidOct)
        j.put("acidCut", acidCut.toDouble()); j.put("acidRes", acidRes.toDouble()); j.put("acidEnv", acidEnv.toDouble())
        j.put("acidDec", acidDec.toDouble()); j.put("acidVol", acidVol.toDouble())
        val ds = JSONArray()
        for (v in 0 until DV) ds.put(ia(drumSteps[v]))
        j.put("drumSteps", ds)
        j.put("drumTune", fa(drumTune)); j.put("drumDecay", fa(drumDecay)); j.put("drumLevel", fa(drumLevel))
        j.put("drumModel", ia(drumModel))
        j.put("drumOn", drumOn); j.put("drumFx", drumFx); j.put("drumDrive", drumDrive.toDouble()); j.put("drumVol", drumVol.toDouble())
        j.put("acidTie", ba(acidTie)); j.put("acidLen", acidLen); j.put("drumLen", drumLen)
        j.put("acidGlide", acidGlide.toDouble()); j.put("acidAccent", acidAccent.toDouble()); j.put("acidDrive", acidDrive.toDouble()); j.put("acidTune", acidTune)
        j.put("drumMute", ba(drumMute))
        j.put("acidVel", fa(acidVel))
        j.put("drumVel", JSONArray().apply { for (v in 0 until DV) put(fa(drumVel[v])) })
        j.put("synTracks", JSONArray().apply { synTracks.forEach { put(it.toJson()) } })
        j.put("scaleType", scaleType); j.put("scaleRoot", scaleRoot); j.put("scaleSnap", scaleSnap)
        return j
    }

    fun fromJson(j: JSONObject?) {
        if (j == null) {
            acidClear(); drumClear(); resetBanks(); acidLen = 16; drumLen = 16
            synCur = 0
            for ((k, t) in synTracks.withIndex()) { t.clear(); t.len = 16; t.on = true; t.soundDefaults(k) }
            return
        }
        banksFromJson(j.optJSONArray("banks"))
        java.util.Arrays.fill(grooveSwing, -1f); java.util.Arrays.fill(humTime, 0f); java.util.Arrays.fill(humVel, 0f)
        j.optJSONArray("groove")?.let { a -> for (i in 0 until min(INSTS, a.length())) a.optJSONArray(i)?.let { g ->
            grooveSwing[i] = g.optDouble(0, -1.0).toFloat(); humTime[i] = g.optDouble(1, 0.0).toFloat(); humVel[i] = g.optDouble(2, 0.0).toFloat() } }
        fun ri(n: String, a: IntArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optInt(k) } }
        fun rb(n: String, a: BooleanArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optBoolean(k) } }
        fun rf(n: String, a: FloatArray) { j.optJSONArray(n)?.let { for (k in 0 until min(a.size, it.length())) a[k] = it.optDouble(k).toFloat() } }
        ri("acidSemi", acidSemi); rb("acidGate", acidGate); rb("acidAcc", acidAcc); rb("acidSlide", acidSlide)
        acidOn = j.optBoolean("acidOn", true); acidFx = j.optBoolean("acidFx", true); acidSquare = j.optBoolean("acidSquare", false)
        acidOct = j.optInt("acidOct", 0)
        scaleType = j.optInt("scaleType", 0).coerceIn(0, scaleNames.size - 1); scaleRoot = Math.floorMod(j.optInt("scaleRoot", 0), 12); scaleSnap = j.optBoolean("scaleSnap", false)
        acidCut = j.optDouble("acidCut", 0.35).toFloat(); acidRes = j.optDouble("acidRes", 0.7).toFloat()
        acidEnv = j.optDouble("acidEnv", 0.6).toFloat(); acidDec = j.optDouble("acidDec", 0.35).toFloat(); acidVol = j.optDouble("acidVol", 0.75).toFloat()
        j.optJSONArray("drumSteps")?.let { ds -> for (v in 0 until min(DV, ds.length())) ds.optJSONArray(v)?.let { r -> for (k in 0 until min(MAX, r.length())) drumSteps[v][k] = r.optInt(k) } }
        rf("drumTune", drumTune); rf("drumDecay", drumDecay); rf("drumLevel", drumLevel)
        java.util.Arrays.fill(drumModel, 0); ri("drumModel", drumModel)
        for (v in 0 until DV) drumModel[v] = drumModel[v].coerceIn(0, drumModelNames[v].size - 1)
        drumOn = j.optBoolean("drumOn", true); drumFx = j.optBoolean("drumFx", false)
        drumDrive = j.optDouble("drumDrive", 0.15).toFloat(); drumVol = j.optDouble("drumVol", 0.8).toFloat()
        java.util.Arrays.fill(acidTie, false)
        java.util.Arrays.fill(drumMute, false)
        rb("acidTie", acidTie); rb("drumMute", drumMute)
        java.util.Arrays.fill(acidVel, 1f)
        for (r in drumVel) java.util.Arrays.fill(r, 1f)
        rf("acidVel", acidVel)
        j.optJSONArray("drumVel")?.let { dv -> for (v in 0 until min(DV, dv.length())) dv.optJSONArray(v)?.let { r -> for (k in 0 until min(MAX, r.length())) drumVel[v][k] = r.optDouble(k, 1.0).toFloat() } }
        acidLen = j.optInt("acidLen", 16).coerceIn(16, MAX); drumLen = j.optInt("drumLen", 16).coerceIn(16, MAX)
        acidGlide = j.optDouble("acidGlide", 0.25).toFloat(); acidAccent = j.optDouble("acidAccent", 0.6).toFloat()
        acidDrive = j.optDouble("acidDrive", 0.2).toFloat(); acidTune = j.optInt("acidTune", 0)
        // alte Projekte (16 Schritte): Rest leeren
        if (!j.has("acidLen")) for (k in STEPS until MAX) { acidGate[k] = false; acidTie[k] = false }
        if (!j.has("drumLen")) for (v in 0 until DV) for (k in STEPS until MAX) drumSteps[v][k] = 0
        synCur = 0
        for ((k, t) in synTracks.withIndex()) { t.clear(); t.len = 16; t.on = true; t.fx = true; t.soundDefaults(k) }
        ri("synSemi", synSemi); rb("synGate", synGate); rb("synTie", synTie); rf("synVel", synVel)
        synLen = j.optInt("synLen", 16).coerceIn(16, MAX); synOn = j.optBoolean("synOn", true); synFx = j.optBoolean("synFx", true)
        synOct = j.optInt("synOct", 0); synWave = j.optInt("synWave", 2); synChord = j.optInt("synChord", 0)
        synCut = j.optDouble("synCut", 0.55).toFloat(); synRes = j.optDouble("synRes", 0.25).toFloat()
        synAtk = j.optDouble("synAtk", 0.05).toFloat(); synRel = j.optDouble("synRel", 0.35).toFloat()
        synVol = j.optDouble("synVol", 0.6).toFloat(); synDetune = j.optDouble("synDetune", 0.35).toFloat(); synEnv = j.optDouble("synEnv", 0.4).toFloat()
        j.optJSONArray("synTracks")?.let { a -> for (k in 0 until min(SYN_TRACKS, a.length())) a.optJSONObject(k)?.let { synTracks[k].fromJson(it) } }
    }
}

/**
 * TB-303-Modell („Acid“): Klangerzeugung nach dem Vorbild des Originals und von Open303 (Robin Schmidt, MIT):
 *  - Sägezahn (bandbegrenzt) bzw. Rechteck, davor ein leichter Hochpass wie im Original
 *  - Diodenleiter-Filter (gekoppelte Stufen statt idealer Moog-Leiter) mit Hochpass in der Rückkopplung –
 *    das typische „quakende“ 303-Resonanzverhalten, auch bei hoher Resonanz noch stabil
 *  - Filter-Hüllkurve (Decay einstellbar, bei Accent fest kurz), Accent-Kreis: lauter + „Wow“ im Filter,
 *    Slide mit fester Gleitzeit wie im Original, Amp-Hüllkurve mit kurzem Release
 */
class AcidSynth(private val sr: Int) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val acc: Boolean, val slide: Boolean, val vel: Float = 1f)

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private var phase = 0.0
    private var pitch = 33.0   // MIDI-Ton (gleitet)
    private var pitchTarget = 33.0
    private var gate = false
    private var amp = 0f
    private var ampEnv = 0f
    private var meg = 0f          // Filter-Hüllkurve
    private var megDecay = 0.999f
    private var accNow = false
    private var accPulse = 0f     // Accent-Hüllkurve (geglättet → „Wow“)
    private var accRc = 0f
    private var velNow = 1f
    // Filter
    private var y1 = 0.0; private var y2 = 0.0; private var y3 = 0.0; private var y4 = 0.0
    private var fbHp = 0.0
    private var preHp = 0.0; private var preX = 0.0
    private var postHp = 0.0; private var postX = 0.0
    private var cutSm = 0.0

    private fun midi(semi: Int): Double = 36.0 + 12 * InstState.acidOct + InstState.acidTune + semi

    /** frame < 0 = sofort. */
    fun queue(frame: Long, on: Boolean, semi: Int, acc: Boolean, slide: Boolean, vel: Float = 1f) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, acc, slide, vel)) }
    }

    private fun apply(e: Ev) {
        if (!e.on) { gate = false; return }
        pitchTarget = midi(e.semi)
        if (e.slide && gate) {
            // Slide: nur die Tonhöhe gleitet, Hüllkurven laufen weiter (wie beim Original)
        } else {
            pitch = pitchTarget
            velNow = e.vel
            meg = 1f
            accNow = e.acc
            if (e.acc) accPulse = 1f
            ampEnv = 1f
        }
        gate = true
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    fun render(out: FloatArray, n: Int, w: Long) {
        val evs: List<Ev>
        synchronized(lock) {
            if (queue.isEmpty()) evs = emptyList() else {
                evs = queue.filter { it.frame < w + n }.sortedBy { it.frame }
                queue.removeAll(evs.toSet())
            }
        }
        var ei = 0
        val st = InstState
        // Filter-Hüllkurve: normal 200 ms … 2 s, Accent-Noten fest kurz (~200 ms) – typisch 303
        val decNorm = exp(-1.0 / ((0.2 + st.acidDec * 1.8) * sr)).toFloat()
        val decAcc = exp(-1.0 / (0.2 * sr)).toFloat()
        val ampDec = exp(-1.0 / (1.2 * sr)).toFloat()
        val accDec = exp(-1.0 / (0.15 * sr)).toFloat()
        val accRcA = (1.0 - exp(-1.0 / (0.012 * sr))).toFloat()
        // Slide: feste Gleitzeit (~60 ms), Regler verlängert bis ~250 ms
        val glide = 1.0 - exp(-1.0 / ((0.02 + st.acidGlide * 0.22) * sr))
        val accAmt = st.acidAccent
        val envMod = st.acidEnv
        val r = st.acidRes.toDouble().coerceIn(0.0, 1.0)
        val rs = (1.0 - exp(-3.0 * r)) / (1.0 - exp(-3.0))   // Resonanz wie beim Original „schief“
        val drv = 1f + st.acidDrive * 4f
        val vol = st.acidVolNow() * 1.1f
        val square = st.acidSquare
        // Grund-Cutoff 314 Hz … 2,4 kHz (Original-Bereich), Touchpad dazu
        val baseCut = 314.0 * (2394.0 / 314.0).pow(st.acidCutNow().toDouble().coerceIn(0.0, 1.0)) * 2.0.pow(st.acidCutMod * 2.0)
        val aPre = exp(-2 * PI * 44.0 / sr)
        val aPost = exp(-2 * PI * 24.0 / sr)
        val aFb = exp(-2 * PI * 150.0 / sr)
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { apply(evs[ei]); ei++ }
            if (!gate && amp < 1e-5f && abs(y4) < 1e-7) continue
            pitch += (pitchTarget - pitch) * glide
            val f = 440.0 * 2.0.pow((pitch - 69.0) / 12.0)
            val dt = f / sr
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            var osc = if (!square) 2.0 * phase - 1.0 - blep(phase, dt) else {
                var sq = if (phase < 0.5) 1.0 else -1.0
                sq += blep(phase, dt)
                sq -= blep((phase + 0.5) % 1.0, dt)
                sq * 0.8
            }
            // leichter Hochpass vor dem Filter
            val hp = aPre * (preHp + osc - preX); preX = osc; preHp = hp; osc = hp
            // Hüllkurven
            meg *= if (accNow) decAcc else decNorm
            accPulse *= accDec
            accRc += (accPulse - accRc) * accRcA          // Accent-„Wow“: geglätteter Buckel
            ampEnv *= ampDec
            val ampTarget = if (gate) ampEnv else 0f
            amp += (ampTarget - amp) * (if (gate) 0.25f else 0.004f)   // schneller Anstieg, ~5 ms Release
            // Cutoff: Grund · Hüllkurve (bis +4 Oktaven) · Accent (bis +2 Oktaven)
            val oct = envMod * 4.0 * meg + (if (accNow) accAmt * 2.0 * accRc else 0.0)
            val fc = (baseCut * 2.0.pow(oct)).coerceIn(30.0, sr * 0.42)
            cutSm += (fc - cutSm) * 0.2
            // Diodenleiter (TB-303-Näherung): Koeffizienten aus dem Cutoff, Resonanz als Rückkopplung
            val wc = 2 * PI * cutSm / sr
            val fx = wc * 0.7071067811865476 / (2 * PI)
            val b0 = (0.00045522346 + 6.1922189 * fx) / (1.0 + 12.358354 * fx + 4.4156345 * fx * fx)
            var k = fx * (fx * (fx * (fx * (fx * (fx + 7198.6997) - 5837.7917) - 476.47308) + 614.95611) + 213.87126) + 16.998792
            var g = k / 17.0
            g = (g - 1.0) * rs + 1.0
            g *= (1.0 + rs)
            k *= rs
            // Hochpass in der Rückkopplung (das Original hält so den Bass bei hoher Resonanz)
            val fbIn = k * y4
            fbHp = aFb * fbHp + (1 - aFb) * fbIn
            val y0 = tanh(osc - (fbIn - fbHp))
            y1 += 2 * b0 * (y0 - y1 + y2)
            y2 += b0 * (y1 - 2 * y2 + y3)
            y3 += b0 * (y2 - 2 * y3 + y4)
            y4 += b0 * (y3 - 2 * y4)
            var s = 2 * g * y4
            if (s.isNaN() || abs(s) > 50.0) { y1 = 0.0; y2 = 0.0; y3 = 0.0; y4 = 0.0; s = 0.0 }
            // Nach-Hochpass, Lautstärke, Accent
            val ph = aPost * (postHp + s - postX); postX = s; postHp = ph
            val a = amp * velNow * (if (accNow) 1f + 0.7f * accAmt * accRc else 1f)
            out[i] += (tanh(ph * 1.6 * drv) / (0.8 + 0.2 * drv)).toFloat() * a * vol
        }
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}

/**
 * Drumcomputer im Stil von TR-909/808, analog nachgebildet:
 * Kick mit Tonhöhen-Hüllkurve und Klick, Snare aus zwei Oszillatoren + gefiltertem Rauschen („Snappy“),
 * Hi-Hats/Becken aus sechs Rechteck-Oszillatoren durch Bandpässe (wie beim 808/909), Clap mit Mehrfach-Anschlag,
 * Toms mit Tonhöhen-Abfall, Rimshot aus Resonatoren. Gefiltert mit Zustandsvariablen-Filtern (kein Rauschbrei).
 */
class DrumMachine(private val sr: Int) {
    /** Zustandsvariablen-Filter (TPT), liefert Tief-, Band- und Hochpass. */
    private class Svf {
        var ic1 = 0.0; var ic2 = 0.0
        var lp = 0.0; var bp = 0.0; var hp = 0.0
        fun run(x: Double, g: Double, k: Double) {
            val a1 = 1.0 / (1.0 + g * (g + k))
            val v3 = x - ic2
            val v1 = a1 * ic1 + g * a1 * v3
            val v2 = ic2 + g * v1
            ic1 = 2 * v1 - ic1
            ic2 = 2 * v2 - ic2
            lp = v2; bp = v1; hp = x - k * v1 - v2
        }
        fun reset() { ic1 = 0.0; ic2 = 0.0 }
    }

    private class Voice {
        var active = false
        var delay = 0
        var t = 0
        var acc = 1f
        val ph = DoubleArray(6)
        var p1 = 0.0
        var p2 = 0.0
        val f1 = Svf(); val f2 = Svf(); val f3 = Svf()
        var fade = 1f   // weiches Abwürgen bei Neuanschlag
        var last = 0f
    }

    private val voices = Array(InstState.DV) { Voice() }
    private val lock = Any()
    private var seed = 0x1234567
    private val metal = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
    private fun g(fc: Double) = tan(PI * min(fc, sr * 0.45) / sr)
    // feste Filter-Koeffizienten (tan() nicht pro Sample rechnen)
    private val g3500 = g(3500.0); private val g1800 = g(1800.0); private val g9000 = g(9000.0)
    private val g2500 = g(2500.0); private val g1150 = g(1150.0); private val g700 = g(700.0)
    private val g10k = g(10000.0); private val g7000 = g(7000.0); private val g4500 = g(4500.0)
    private val g8500 = g(8500.0); private val g5000 = g(5000.0)

    private fun noise(): Double {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return ((seed and 0xFFFF) / 32768.0) - 1.0
    }

    fun trigger(v: Int, delay: Int, accent: Boolean, vel: Float = 1f) {
        if (v !in 0 until InstState.DV) return
        synchronized(lock) {
            val vc = voices[v]
            restart(vc, v, (if (accent) 1.35f else 1f) * vel.coerceIn(0.02f, 1f))
            vc.delay = max(0, delay)
        }
    }

    private fun restart(vc: Voice, v: Int, acc: Float) {
        vc.active = true
        vc.delay = 0
        vc.t = 0
        vc.fade = 1f
        vc.acc = acc
        vc.p1 = 0.0; vc.p2 = 0.0
        for (k in vc.ph.indices) vc.ph[k] = (noise() + 1.0) * 0.5
        vc.f1.reset(); vc.f2.reset(); vc.f3.reset()
        // Closed Hat würgt Open Hat ab (weich, kein Knacks)
        if (v == 7) voices[8].fade = 0.999f
    }

    // Zwischenschläge (32tel, Vorschläge): erneuter Anschlag derselben Stimme mitten im Block
    private val pendV = IntArray(32)
    private val pendAt = IntArray(32)
    private val pendVel = FloatArray(32)
    private var pendN = 0

    /** Zusätzlicher Anschlag von [v] nach [delay] Frames – ohne den gerade geplanten Schlag zu ersetzen. */
    fun triggerExtra(v: Int, delay: Int, vel: Float) {
        if (v !in 0 until InstState.DV) return
        synchronized(lock) {
            if (pendN >= pendV.size) return
            pendV[pendN] = v; pendAt[pendN] = max(0, delay); pendVel[pendN] = vel.coerceIn(0.02f, 1f); pendN++
        }
    }

    /** Frühester ausstehender Zusatzschlag für Stimme [v] innerhalb von [n] Frames (Index) oder −1. */
    private fun nextPend(v: Int, n: Int, after: Int): Int {
        var best = -1
        for (k in 0 until pendN) if (pendV[k] == v && pendAt[k] < n && pendAt[k] >= after && (best < 0 || pendAt[k] < pendAt[best])) best = k
        return best
    }

    // ---------------------------------------------------------------- weitere Klang-Modelle (808, Percussion …)
    private var altS = 0.0
    private var altEnv = 0.0
    private val gF = DoubleArray(22)
    private val gHz = doubleArrayOf(250.0, 800.0, 1000.0, 1200.0, 1400.0, 2000.0, 2500.0, 3000.0, 5000.0, 6000.0, 7000.0, 8000.0, 9000.0, 4000.0, 600.0, 11000.0,
        150.0, 350.0, 1500.0, 2200.0, 3500.0, 4500.0)
    private fun gx(i: Int): Double { if (gF[i] == 0.0) gF[i] = g(gHz[i]); return gF[i] }
    private val G250 = 0; private val G800 = 1; private val G1000 = 2; private val G1200 = 3; private val G1400 = 4; private val G2000 = 5
    private val G2500 = 6; private val G3000 = 7; private val G5000 = 8; private val G6000 = 9; private val G7000 = 10; private val G8000 = 11
    private val G9000 = 12; private val G4000 = 13; private val G600 = 14; private val G11000 = 15
    private val G150 = 16; private val G350 = 17; private val G1500 = 18; private val G2200 = 19; private val G3500 = 20; private val G4500 = 21

    /** Hüllkurve eines Hand-Claps: drei schnelle Anschläge ab [t0], Abstand [gap]. */
    private fun clapEnv(t: Double, t0: Double, gap: Double): Double {
        var e = 0.0
        for (d in 0..2) { val td = t - t0 - d * gap; if (td >= 0) e += exp(-td / 0.0045) }
        return e
    }

    /** Klang-Modelle 1… je Stimme (0 = 909, im Hauptteil). Ergebnis in [altS] und [altEnv]. */
    private fun altVoice(vc: Voice, v: Int, m: Int, t: Double, tune: Double, dec: Double) {
        val ft = 2.0.pow(tune - 0.5)
        var s: Double
        var env: Double
        when (v) {
            0 -> { // Bass Drums
                when (m) {
                    1 -> { // 808 Boom: langer Sinus, sanfter Tonhöhen-Abfall
                        val f = 48.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 1.3 * exp(-t / 0.03))
                        vc.p1 += f / sr
                        env = exp(-t / (0.35 + dec * 1.8))
                        vc.f1.run(noise(), gx(G2000), 1.4)
                        s = tanh(sin(2 * PI * vc.p1) * 1.15) + vc.f1.bp * exp(-t / 0.0012) * 0.25
                    }
                    2 -> { // Techno Rumble: verzerrt, druckvoll
                        val f = 46.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 2.6 * exp(-t / 0.014) + 0.3 * exp(-t / 0.08))
                        vc.p1 += f / sr
                        env = exp(-t / (0.25 + dec * 0.9))
                        val body = sin(2 * PI * vc.p1)
                        vc.f1.run(tanh(body * (2.5 + 4.0 * exp(-t / 0.12))), gx(G600), 0.9)
                        vc.f2.run(noise(), gx(G4000), 1.2)
                        s = vc.f1.lp * 1.1 + vc.f2.bp * exp(-t / 0.002) * 0.6
                    }
                    3 -> { // Hardstyle: starker Pitch-Sweep, harte Sättigung
                        val f = 52.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 6.0 * exp(-t / 0.018) + 1.4 * exp(-t / 0.11))
                        vc.p1 += f / sr
                        env = exp(-t / (0.3 + dec * 0.6))
                        vc.f1.run(tanh(sin(2 * PI * vc.p1) * 7.0), gx(G3000), 0.8)
                        s = vc.f1.lp * 0.95
                    }
                    4 -> { // Deep: rund, ohne Klick
                        val f = 44.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 1.8 * exp(-t / 0.025))
                        vc.p1 += f / sr
                        env = exp(-t / (0.2 + dec * 0.7)) * (1 - exp(-t / 0.0015))
                        s = sin(2 * PI * vc.p1) * 1.1
                    }
                    5 -> { // Minimal Click: kurz und knackig
                        val f = 58.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 4.0 * exp(-t / 0.006))
                        vc.p1 += f / sr
                        env = exp(-t / (0.08 + dec * 0.25))
                        vc.f1.run(noise(), gx(G5000), 0.8)
                        s = sin(2 * PI * vc.p1) + vc.f1.hp * exp(-t / 0.0015) * 0.9
                    }
                    6 -> { // Lo-Fi: Wumms mit Rauschen, leicht zerbröselt
                        val f = 52.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 2.0 * exp(-t / 0.02))
                        vc.p1 += f / sr
                        env = exp(-t / (0.15 + dec * 0.5))
                        vc.f1.run(noise(), gx(G250), 0.9)
                        val x = sin(2 * PI * vc.p1) + vc.f1.lp * exp(-t / 0.03) * 1.2
                        s = Math.round(x * 20.0) / 20.0
                    }
                    7 -> { // Punchy House: kurzer Halt, dann Abklingen, knackiger Anschlag
                        val f = 50.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 3.2 * exp(-t / 0.01) + 0.4 * exp(-t / 0.05))
                        vc.p1 += f / sr
                        env = if (t < 0.04) 1.0 else exp(-(t - 0.04) / (0.12 + dec * 0.6))
                        vc.f1.run(noise(), gx(G3000), 1.1)
                        s = tanh(sin(2 * PI * vc.p1) * 1.7) + vc.f1.bp * exp(-t / 0.0018) * 0.7
                    }
                    8 -> { // 808 verzerrt: lang, gesättigt, oben weich abgerundet
                        val f = 46.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 1.6 * exp(-t / 0.025))
                        vc.p1 += f / sr
                        env = exp(-t / (0.4 + dec * 1.6))
                        vc.f1.run(tanh(sin(2 * PI * vc.p1) * 4.0), gx(G2000), 0.75)
                        s = vc.f1.lp * 0.85
                    }
                    9 -> { // Akustik: Fell-Grundton, zweite Mode, Schlägel + Luftstoß
                        val f = 58.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 0.3 * exp(-t / 0.02))
                        vc.p1 += f / sr
                        env = exp(-t / (0.12 + dec * 0.35))
                        vc.f1.run(noise(), gx(G3000), 1.3)
                        vc.f2.run(noise(), gx(G250), 0.8)
                        s = sin(2 * PI * vc.p1) + 0.35 * sin(2 * PI * vc.p1 * 1.59) * exp(-t / 0.05) +
                            vc.f1.bp * exp(-t / 0.0015) * 0.9 + vc.f2.lp * exp(-t / 0.02) * 0.8
                    }
                    else -> { // Psytrance: sehr kurz, steiler Pitch-Sweep
                        val f = 55.0 * 2.0.pow((tune - 0.5) * 1.2) * (1.0 + 5.0 * exp(-t / 0.008))
                        vc.p1 += f / sr
                        env = exp(-t / (0.07 + dec * 0.18))
                        vc.f1.run(noise(), gx(G5000), 0.8)
                        s = tanh(sin(2 * PI * vc.p1) * 1.4) + vc.f1.hp * exp(-t / 0.001) * 0.6
                    }
                }
            }
            1 -> { // Snares
                when (m) {
                    1 -> { // 808: mehr Ton, weniger Teppich
                        vc.p1 += 238.0 * ft / sr; vc.p2 += 476.0 * ft / sr
                        val tone = (sin(2 * PI * vc.p1) * 0.75 + sin(2 * PI * vc.p2) * 0.35) * exp(-t / 0.07)
                        vc.f1.run(noise(), gx(G2000), 1.0)
                        val sn = vc.f1.hp * exp(-t / (0.06 + dec * 0.18)) * 0.9
                        env = 1.0; s = tone + sn
                        if (t > 0.08 + dec * 0.6) env = 0.0
                    }
                    2 -> { // Trap: kurzer Ton, heller langer Teppich, doppelter Anschlag
                        vc.p1 += 200.0 * ft * (1 + 0.6 * exp(-t / 0.01)) / sr
                        val tone = sin(2 * PI * vc.p1) * exp(-t / 0.03) * 0.6
                        vc.f1.run(noise(), gx(G3000), 0.9)
                        vc.f2.run(vc.f1.hp, gx(G7000), 0.7)
                        var e = exp(-t / (0.1 + dec * 0.25))
                        if (t > 0.012) e += exp(-(t - 0.012) / 0.006) * 0.6
                        env = 1.0; s = tone + vc.f2.bp * e * 1.6
                        if (t > 0.1 + dec * 0.8) env = 0.0
                    }
                    3 -> { // Lo-Fi: dumpf, körnig
                        vc.p1 += 180.0 * ft / sr
                        val tone = sin(2 * PI * vc.p1) * exp(-t / 0.05) * 0.7
                        vc.f1.run(noise(), gx(G5000), 0.8)
                        val x = tone + vc.f1.lp * exp(-t / (0.08 + dec * 0.2))
                        env = 1.0; s = Math.round(x * 12.0) / 12.0
                        if (t > 0.08 + dec * 0.6) env = 0.0
                    }
                    4 -> { // Akustik: zwei Fell-Moden, Snare-Teppich, Stock-Anschlag
                        vc.p1 += 190.0 * ft / sr; vc.p2 += 330.0 * ft / sr
                        val tone = (sin(2 * PI * vc.p1) * 0.6 + sin(2 * PI * vc.p2) * 0.35) * exp(-t / 0.06)
                        vc.f1.run(noise(), gx(G4500), 0.8)
                        vc.f2.run(vc.f1.bp, gx(G3000), 1.0)
                        val wires = vc.f2.hp * exp(-t / (0.12 + dec * 0.3)) * 1.6
                        env = 1.0; s = tone + wires + noise() * exp(-t / 0.0015) * 0.4
                        if (t > 0.12 + dec * 0.9) env = 0.0
                    }
                    5 -> { // Electro Rim: harter Ton, kurzer Teppich, Rand-Klick
                        vc.p1 += 230.0 * ft / sr; vc.p2 += 1700.0 * ft / sr
                        vc.f1.run(noise(), gx(G4000), 0.9)
                        env = 1.0
                        s = tanh(sin(2 * PI * vc.p1) * 3.0) * exp(-t / 0.04) * 0.6 + vc.f1.hp * exp(-t / (0.05 + dec * 0.1)) * 0.9 +
                            sin(2 * PI * vc.p2) * exp(-t / 0.006) * 0.4
                        if (t > 0.06 + dec * 0.4) env = 0.0
                    }
                    else -> { // Besen: weiches Wischen
                        vc.f1.run(noise(), gx(G4000), 2.0)
                        env = (1 - exp(-t / 0.012)) * exp(-t / (0.12 + dec * 0.3))
                        s = vc.f1.bp * 2.4
                    }
                }
            }
            2, 3, 4 -> { // Toms / Percussion
                val k = v - 2
                when (m) {
                    1 -> { // Conga (808)
                        val f = doubleArrayOf(180.0, 250.0, 330.0)[k] * ft
                        vc.p1 += f * (1 + 0.12 * exp(-t / 0.01)) / sr
                        env = exp(-t / (0.12 + dec * 0.3))
                        vc.f1.run(noise(), gx(G2000), 1.2)
                        s = sin(2 * PI * vc.p1) + vc.f1.bp * exp(-t / 0.003) * 0.5
                    }
                    2 -> { // Bongo: höher, kürzer
                        val f = doubleArrayOf(400.0, 520.0, 680.0)[k] * ft
                        vc.p1 += f * (1 + 0.15 * exp(-t / 0.006)) / sr
                        env = exp(-t / (0.06 + dec * 0.15))
                        vc.f1.run(noise(), gx(G3000), 1.0)
                        s = sin(2 * PI * vc.p1) + vc.f1.bp * exp(-t / 0.002) * 0.6
                    }
                    3 -> { // Syn-Tom (Simmons): „Piuu“
                        val f = doubleArrayOf(110.0, 160.0, 230.0)[k] * ft
                        vc.p1 += f * (1 + 1.5 * exp(-t / 0.12)) / sr
                        env = exp(-t / (0.2 + dec * 0.6))
                        val ph = vc.p1 % 1.0
                        val tri = 4 * abs(ph - 0.5) - 1
                        vc.f1.run(noise(), gx(G1200), 1.5)
                        s = tri * 0.9 + vc.f1.bp * exp(-t / 0.01) * 0.4
                    }
                    4 -> { // Akustik-Tom: Grundton + Fell-Mode, Stock
                        val f = doubleArrayOf(100.0, 140.0, 190.0)[k] * ft
                        vc.p1 += f * (1 + 0.08 * exp(-t / 0.05)) / sr
                        env = exp(-t / (0.25 + dec * 0.6))
                        vc.f1.run(noise(), gx(G2000), 1.2)
                        s = sin(2 * PI * vc.p1) + 0.3 * sin(2 * PI * vc.p1 * 1.59) * exp(-t / 0.08) + vc.f1.bp * exp(-t / 0.002) * 0.4
                    }
                    5 -> { // Timbale: hell, metallisch klingend
                        val f = doubleArrayOf(300.0, 400.0, 520.0)[k] * ft
                        vc.p1 += f / sr
                        env = exp(-t / (0.12 + dec * 0.4))
                        vc.f1.run(noise(), gx(G3000), 1.0)
                        s = sin(2 * PI * vc.p1) + 0.45 * sin(2 * PI * vc.p1 * 2.31) * exp(-t / 0.1) + 0.25 * sin(2 * PI * vc.p1 * 3.7) * exp(-t / 0.05) +
                            vc.f1.bp * exp(-t / 0.0025) * 0.5
                    }
                    else -> { // Log Drum (Amapiano): tonaler Holz-Bass
                        val f = doubleArrayOf(65.0, 82.0, 110.0)[k] * ft
                        vc.p1 += f * (1 + 0.25 * exp(-t / 0.015)) / sr
                        env = exp(-t / (0.3 + dec * 0.8))
                        s = sin(2 * PI * vc.p1) + 0.4 * sin(4 * PI * vc.p1) * exp(-t / 0.04) + 0.25 * sin(6 * PI * vc.p1) * exp(-t / 0.02)
                        s = tanh(s * 1.2)
                    }
                }
            }
            5 -> { // Rimshot-Platz: Clave, Cowbell, Woodblock
                when (m) {
                    1 -> { // Clave (808)
                        vc.p1 += 2500.0 * ft / sr
                        env = exp(-t / (0.02 + dec * 0.04))
                        s = sin(2 * PI * vc.p1)
                    }
                    2 -> { // Cowbell (808): zwei Rechtecke durch Bandpass
                        vc.p1 += 540.0 * ft / sr; vc.p2 += 800.0 * ft / sr
                        val a = if (vc.p1 % 1.0 < 0.5) 1.0 else -1.0
                        val b = if (vc.p2 % 1.0 < 0.5) 1.0 else -1.0
                        vc.f1.run((a + b) * 0.5, gx(G1000), 1.4)
                        env = 0.6 * exp(-t / 0.01) + 0.4 * exp(-t / (0.06 + dec * 0.3))
                        s = vc.f1.bp * 1.8
                    }
                    3 -> { // Woodblock
                        vc.p1 += 1100.0 * ft * (1 + 0.05 * exp(-t / 0.004)) / sr
                        vc.p2 += 1100.0 * 2.7 * ft / sr
                        env = exp(-t / (0.03 + dec * 0.05))
                        s = sin(2 * PI * vc.p1) * 0.8 + sin(2 * PI * vc.p2) * 0.25 * exp(-t / 0.008)
                    }
                    4 -> { // Triangel: unharmonische Teiltöne, langer Ausklang
                        env = exp(-t / (0.6 + dec * 1.2))
                        val a = 2 * PI * t * ft
                        s = sin(a * 4200.0) * 0.5 + sin(a * 5900.0) * 0.3 * exp(-t / 0.4) + sin(a * 7800.0) * 0.2 * exp(-t / 0.2)
                    }
                    else -> { // Guiro: geriffeltes Kratzen
                        vc.f1.run(noise(), gx(G3000), 1.0)
                        val len = 0.15 + dec * 0.2
                        val rasp = if ((t * 38.0 * ft) % 1.0 < 0.5) 1.0 else 0.25
                        env = if (t < len) (1 - exp(-t / 0.01)) * rasp else 0.0
                        s = vc.f1.bp * 1.8
                    }
                }
            }
            6 -> { // Claps
                when (m) {
                    1 -> { // 808 Clap: vier Anschläge, längere Fahne
                        vc.f1.run(noise(), gx(G1000), 0.6)
                        vc.f2.run(vc.f1.bp, gx(G600), 1.2)
                        var e = 0.0
                        for (d in 0..3) { val td = t - d * 0.008; if (td >= 0) e += exp(-td / 0.004) }
                        if (t > 0.03) e += exp(-(t - 0.03) / (0.15 + dec * 0.4)) * 0.8
                        env = 1.0; s = vc.f2.hp * e * 2.4
                        if (t > 0.2 + dec * 1.6) env = 0.0
                    }
                    2 -> { // Snap (Fingerschnipsen)
                        vc.f1.run(noise(), gx(G2500), 0.7)
                        vc.p1 += 1800.0 * ft / sr
                        env = exp(-t / (0.012 + dec * 0.02))
                        s = vc.f1.bp * 2.2 + sin(2 * PI * vc.p1) * 0.25
                    }
                    3 -> { // Big Clap: breiter, mit Raum
                        vc.f1.run(noise(), gx(G1400), 0.5)
                        vc.f2.run(noise(), gx(G3000), 0.8)
                        val band = vc.f1.bp + vc.f2.bp * 0.6
                        var e = 0.0
                        for (d in 0..3) { val td = t - d * 0.011; if (td >= 0) e += exp(-td / 0.005) }
                        if (t > 0.035) e += exp(-(t - 0.035) / (0.3 + dec * 0.8)) * 0.7
                        env = 1.0; s = band * e * 2.0
                        if (t > 0.4 + dec * 2.5) env = 0.0
                    }
                    4 -> { // Gated Clap: dichter Raum, der abrupt (aber weich) endet – 80er
                        vc.f1.run(noise(), gx(G1500), 0.6)
                        val gate = 0.12 + dec * 0.25
                        var e = clapEnv(t, 0.0, 0.009)
                        if (t > 0.02) e += 0.55 * (if (t < gate) 1.0 else exp(-(t - gate) / 0.008))
                        env = 1.0; s = vc.f1.bp * e * 2.2
                        if (t > gate + 0.08) env = 0.0
                    }
                    else -> { // Hände (Gruppe): drei Klatscher, leicht versetzt und verschieden hell
                        vc.f1.run(noise(), gx(G1000), 0.7)
                        vc.f2.run(noise(), gx(G1500), 0.7)
                        vc.f3.run(noise(), gx(G2200), 0.7)
                        var e1 = clapEnv(t, 0.0, 0.008); var e2 = clapEnv(t, 0.006, 0.009); var e3 = clapEnv(t, 0.014, 0.007)
                        val tail = if (t > 0.03) exp(-(t - 0.03) / (0.1 + dec * 0.3)) * 0.5 else 0.0
                        e1 += tail; e2 += tail; e3 += tail
                        env = 1.0; s = (vc.f1.bp * e1 + vc.f2.bp * e2 + vc.f3.bp * e3) * 1.4
                        if (t > 0.15 + dec * 1.2) env = 0.0
                    }
                }
            }
            7, 8 -> { // Hi-Hats / Shaker / Tambourin
                val open = v == 8
                when (m) {
                    1 -> { // 808: dunkler, weicher
                        val mm = metallic(vc, 0.9 * 2.0.pow((tune - 0.5) * 0.8)) * 0.9 + noise() * 0.15
                        vc.f1.run(mm, gx(G8000), 1.0)
                        vc.f2.run(vc.f1.bp, gx(G6000), 1.0)
                        env = if (!open) exp(-t / (0.03 + dec * 0.06)) else exp(-t / (0.25 + dec * 0.6))
                        s = vc.f2.hp * 3.0
                    }
                    2 -> { // Shaker
                        vc.f1.run(noise(), gx(G7000), 1.2)
                        val att = 1 - exp(-t / 0.006)
                        env = att * (if (!open) exp(-t / (0.05 + dec * 0.08)) else exp(-t / (0.12 + dec * 0.25)))
                        s = vc.f1.bp * 1.6
                    }
                    3 -> { // Tambourin: Schellen (Metall + flatternde Rausch-Stöße)
                        val mm = metallic(vc, 2.3 * ft) * 0.6 + noise() * 0.5
                        vc.f1.run(mm, gx(G9000), 0.9)
                        val jingle = 0.6 + 0.4 * sin(2 * PI * 38.0 * t)
                        env = if (!open) exp(-t / (0.08 + dec * 0.1)) else exp(-t / (0.3 + dec * 0.5))
                        s = vc.f1.bp * 2.4 * jingle
                    }
                    4 -> { // Akustik-Becken: dunkler, mit „Tschick“-Anschlag
                        val mm = metallic(vc, 1.2 * ft) * 0.6 + noise() * 0.5
                        vc.f1.run(mm, gx(G9000), 0.7)
                        vc.f2.run(vc.f1.bp, gx(G5000), 1.0)
                        env = if (!open) exp(-t / (0.04 + dec * 0.06)) else exp(-t / (0.4 + dec * 0.8))
                        s = vc.f2.hp * (if (open) 1.8 else 2.4) + noise() * exp(-t / 0.001) * 0.3
                    }
                    else -> { // Cabasa: raues, hohes Rasseln
                        vc.f1.run(noise(), gx(G8000), 0.9)
                        val rasp = 0.7 + 0.3 * sin(2 * PI * 60.0 * t)
                        env = (1 - exp(-t / 0.004)) * (if (!open) exp(-t / (0.04 + dec * 0.06)) else exp(-t / (0.1 + dec * 0.2))) * rasp
                        s = vc.f1.hp * 1.6
                    }
                }
            }
            9 -> when (m) {
                1 -> { // Splash: höher, kürzer
                    val mm = metallic(vc, 1.9 * ft) * 0.6 + noise() * 0.5
                    vc.f1.run(mm, gx(G8000), 0.7)
                    vc.f2.run(vc.f1.bp, gx(G5000), 1.0)
                    env = exp(-t / (0.25 + dec * 0.6)) * (1 - exp(-t / 0.0015))
                    s = vc.f2.hp * 1.8
                }
                2 -> { // China: rau, „trashig“
                    val mm = metallic(vc, 1.3 * ft) * 0.7 + noise() * 0.4
                    vc.f1.run(sin(mm * 2.2), gx(G5000), 0.6)
                    vc.f2.run(vc.f1.bp, gx(G3500), 1.0)
                    env = exp(-t / (0.5 + dec * 1.5)) * (1 - exp(-t / 0.001))
                    s = vc.f2.hp * 1.6
                }
                else -> { // Reverse: anschwellendes Becken, endet hart (weich geblendet)
                    val mm = metallic(vc, 1.45 * ft) * 0.6 + noise() * 0.5
                    vc.f1.run(mm, gx(G7000), 0.7)
                    vc.f2.run(vc.f1.bp, gx(G4500), 1.0)
                    val len = 0.6 + dec * 1.4
                    env = if (t < len) (t / len) * (t / len) else exp(-(t - len) / 0.006)
                    s = vc.f2.hp * 1.8
                }
            }
            else -> when (m) {
                1 -> { // Agogo: zweiteilige Glocke
                    vc.p1 += 1300.0 * ft / sr; vc.p2 += 1300.0 * 2.76 * ft / sr
                    env = exp(-t / (0.15 + dec * 0.35))
                    s = sin(2 * PI * vc.p1) * 0.7 + sin(2 * PI * vc.p2) * 0.3 * exp(-t / 0.05)
                }
                2 -> { // Ride-Glocke: heller Glockenton auf dem Becken
                    val mm = metallic(vc, 2.2 * ft) * 0.5
                    vc.f1.run(mm, gx(G8000), 0.9)
                    val a = 2 * PI * t * ft
                    env = exp(-t / (0.6 + dec * 1.5))
                    s = vc.f1.bp * 0.9 + (sin(a * 2600.0) * 0.4 + sin(a * 3520.0) * 0.25) * exp(-t / 0.5)
                }
                else -> { // FM-Glocke: klar, glasig
                    val fc = 880.0 * ft
                    vc.p1 += fc / sr; vc.p2 += fc * 3.5 / sr
                    env = exp(-t / (0.8 + dec * 2.0))
                    s = sin(2 * PI * vc.p1 + 3.0 * exp(-t / 0.3) * sin(2 * PI * vc.p2)) * 0.5
                }
            }
        }
        altS = s; altEnv = env
    }

    /** Sechs Rechtecke (808/909-Metall) – leicht geglättet gegen Aliasing. */
    private fun metallic(vc: Voice, mul: Double): Double {
        var s = 0.0
        for (k in 0 until 6) {
            val dt = metal[k] * mul / sr
            vc.ph[k] += dt
            if (vc.ph[k] >= 1.0) vc.ph[k] -= 1.0
            val p = vc.ph[k]
            var sq = if (p < 0.5) 1.0 else -1.0
            // PolyBLEP an beiden Flanken
            sq += blep(p, dt); sq -= blep((p + 0.5) % 1.0, dt)
            s += sq
        }
        return s / 6.0
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    fun render(out: FloatArray, n: Int) {
        val st = InstState
        val drive = 1f + st.drumDrive * 3f
        val norm = 1.0 / tanh(drive.toDouble())
        synchronized(lock) {
            for (v in 0 until InstState.DV) {
                val vc = voices[v]
                var pi = if (pendN > 0) nextPend(v, n, 0) else -1
                if (!vc.active && pi < 0) continue
                if (st.drumMute[v]) { vc.active = false; continue }
                val tune = st.drumTune[v].toDouble()
                val dec = st.drumDecay[v].toDouble()
                var lvl = st.drumLevel[v] * st.drumVolNow() * vc.acc
                val gTom = if (v in 2..4) g(when (v) { 2 -> 95.0; 3 -> 140.0; else -> 200.0 } * 2.0.pow(tune - 0.5) * 6) else 0.0
                for (i in 0 until n) {
                    if (pi >= 0 && pendAt[pi] == i) {
                        restart(vc, v, pendVel[pi])
                        lvl = st.drumLevel[v] * st.drumVolNow() * vc.acc
                        pendV[pi] = -1
                        pi = nextPend(v, n, i + 1)
                    }
                    if (!vc.active) continue
                    if (vc.delay > 0) { vc.delay--; continue }
                    val t = vc.t.toDouble() / sr
                    var s: Double
                    var env: Double
                    val model = st.drumModel[v]
                    if (model > 0) {
                        altVoice(vc, v, model, t, tune, dec)
                        s = altS; env = altEnv
                    } else {
                        when (v) {
                            0 -> { // Bass Drum (909): schneller Tonhöhen-Abfall + Klick
                                val base = 50.0 * 2.0.pow((tune - 0.5) * 1.2)
                                val f = base * (1.0 + 3.6 * exp(-t / 0.012) + 0.6 * exp(-t / 0.06))
                                vc.p1 += f / sr
                                env = exp(-t / (0.18 + dec * 0.8))
                                val body = sin(2 * PI * vc.p1)
                                vc.f1.run(noise(), g3500, 1.2)
                                val click = vc.f1.bp * exp(-t / 0.0025) * 0.9 + (if (vc.t < sr / 1000) 0.6 else 0.0)
                                s = tanh(body * 1.25) + click
                            }
                            1 -> { // Snare (909): zwei Oszillatoren + Rauschen durch Hoch- und Tiefpass
                                val ft = 2.0.pow(tune - 0.5)
                                vc.p1 += 180.0 * ft * (1 + 0.5 * exp(-t / 0.015)) / sr
                                vc.p2 += 330.0 * ft * (1 + 0.5 * exp(-t / 0.015)) / sr
                                val tone = (sin(2 * PI * vc.p1) * 0.65 + sin(2 * PI * vc.p2) * 0.4) * exp(-t / 0.045)
                                vc.f1.run(noise(), g1800, 1.0)
                                vc.f2.run(vc.f1.hp, g9000, 0.9)
                                val snappy = vc.f2.lp * exp(-t / (0.06 + dec * 0.22)) * 1.3
                                env = 1.0
                                s = tone + snappy
                                if (t > 0.05 + dec * 0.4 && abs(snappy) < 1e-4) env = 0.0
                            }
                            2, 3, 4 -> { // Toms: Sinus mit Tonhöhen-Abfall + gefilterter Anschlag
                                val baseF = when (v) { 2 -> 95.0; 3 -> 140.0; else -> 200.0 } * 2.0.pow(tune - 0.5)
                                vc.p1 += baseF * (1 + 0.45 * exp(-t / 0.04)) / sr
                                env = exp(-t / (0.15 + dec * 0.6))
                                vc.f1.run(noise(), gTom, 1.5)
                                s = sin(2 * PI * vc.p1) + vc.f1.bp * exp(-t / 0.006) * 0.4
                            }
                            5 -> { // Rimshot: zwei Resonatoren, sehr kurz
                                vc.p1 += 1700.0 * 2.0.pow(tune - 0.5) / sr
                                vc.p2 += 480.0 * 2.0.pow(tune - 0.5) / sr
                                env = exp(-t / (0.008 + dec * 0.02))
                                vc.f1.run(noise() * exp(-t / 0.001), g2500, 0.6)
                                s = sin(2 * PI * vc.p1) * 0.6 + sin(2 * PI * vc.p2) * 0.5 + vc.f1.bp * 0.8
                            }
                            6 -> { // Clap: Rauschen durch Bandpass, drei schnelle Anschläge + Hall-Fahne
                                vc.f1.run(noise(), g1150, 0.55)
                                vc.f2.run(vc.f1.bp, g700, 1.2)
                                val band = vc.f2.hp
                                var e = 0.0
                                for (d in 0..2) { val td = t - d * 0.0105; if (td >= 0) e += exp(-td / 0.0045) }
                                if (t > 0.03) e += exp(-(t - 0.03) / (0.09 + dec * 0.35)) * 0.75
                                env = 1.0
                                s = band * e * 2.4
                                if (t > 0.12 + dec * 1.2) env = 0.0
                            }
                            7, 8 -> { // Hi-Hats: Metall-Rechtecke → Bandpass ~10 kHz → Hochpass
                                val m = metallic(vc, 1.0 * 2.0.pow((tune - 0.5) * 0.8)) * 0.9 + noise() * 0.12
                                vc.f1.run(m, g10k, 0.9)
                                vc.f2.run(vc.f1.bp, g7000, 1.0)
                                env = if (v == 7) exp(-t / (0.025 + dec * 0.07)) else exp(-t / (0.16 + dec * 0.6))
                                s = vc.f2.hp * 3.4
                            }
                            9 -> { // Crash: Metall + Rauschen, breiter Bandpass, lang
                                val m = metallic(vc, 1.45 * 2.0.pow(tune - 0.5)) * 0.6 + noise() * 0.5
                                vc.f1.run(m, g7000, 0.7)
                                vc.f2.run(vc.f1.bp, g4500, 1.0)
                                env = exp(-t / (0.7 + dec * 2.0)) * (1 - exp(-t / 0.002))
                                s = vc.f2.hp * 1.8
                            }
                            else -> { // Ride: Metall mit Glocke
                                val m = metallic(vc, 1.9 * 2.0.pow(tune - 0.5)) * 0.85 + noise() * 0.1
                                vc.f1.run(m, g8500, 0.8)
                                vc.f2.run(vc.f1.bp, g5000, 1.0)
                                vc.p1 += 3200.0 / sr
                                env = exp(-t / (0.9 + dec * 2.0))
                                s = vc.f2.hp * 1.8 + sin(2 * PI * vc.p1) * 0.18 * exp(-t / 0.35)
                            }
                        }
                    }
                    if (vc.fade < 1f) { vc.fade *= 0.997f; if (vc.fade < 0.001f) { vc.active = false; if (pi < 0) break else continue } }
                    out[i] += (tanh(s * env * lvl * drive) * norm * 0.8 * vc.fade).toFloat()
                    vc.t++
                    if (vc.t > sr * 4 || ((env < 1e-4) && vc.t > sr / 20)) { vc.active = false; vc.fade = 1f; if (pi < 0) break }
                }
                if (!vc.active) vc.fade = 1f
            }
            // Zusatzschläge: verbrauchte/abgelaufene entfernen, übrige in den nächsten Block verschieben
            var k = 0
            for (j in 0 until pendN) {
                if (pendV[j] < 0 || pendAt[j] < n) continue
                pendV[k] = pendV[j]; pendAt[k] = pendAt[j] - n; pendVel[k] = pendVel[j]; k++
            }
            pendN = k
        }
    }
}

/**
 * Polyfoner Synth (bis 10 Stimmen): Sägezahn, Rechteck, Supersaw (5 verstimmte Sägezähne),
 * Pluck und FM-Glocke; je Stimme ADSR und resonantes 2-Pol-Filter mit Hüllkurve.
 * Spielt Einzeltöne oder Akkorde.
 */
class PolySynth(private val sr: Int, private val t: InstState.SynTrack) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val vel: Float, val tag: Int, val iv: IntArray? = null)

    private class Voice {
        var active = false
        var tag = 0
        var semi = 0
        var freq = 220.0
        var vel = 1f
        var gate = false
        var env = 0f
        var stage = 0      // 0 Attack, 1 Decay/Sustain, 2 Release
        var fenv = 0f
        val ph = DoubleArray(5)
        var mph = 0.0
        var lp = 0f
        var bp = 0f
        var age = 0L
        // Chiptune
        var arpIv: IntArray? = null
        var arpBase = 0
        var arpIdx = -1
        var n = 0L
        var lfsr = 1
        var nz = 0f
        var vph = 0.0
    }

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private val voices = Array(10) { Voice() }
    private var ageCtr = 0L
    private val det = doubleArrayOf(0.0, -0.11, 0.11, -0.05, 0.05)

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((60 + 12 * t.oct + t.transpose + semi - 69) / 12.0)

    /** frame < 0 = sofort. [tag] verbindet An und Aus (z. B. Schritt oder Finger). */
    fun queue(frame: Long, on: Boolean, semi: Int, vel: Float, tag: Int, iv: IntArray? = null) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, vel, tag, iv)) }
    }

    fun allOff() {
        synchronized(lock) { queue.clear() }
        for (v in voices) if (v.active) { v.gate = false; v.stage = 2 }
    }

    private fun apply(e: Ev) {
        val iv = e.iv ?: InstState.synChordIv[t.chord.coerceIn(0, InstState.synChordIv.size - 1)]
        if (!e.on) {
            for (v in voices) if (v.active && v.tag == e.tag && v.gate) { v.gate = false; v.stage = 2 }
            return
        }
        val chip = t.wave >= 5
        // Chiptune: Akkord als EINE Stimme, die die Töne schnell durchläuft (wie auf NES/Game Boy/C64)
        val list = if (chip && iv.size > 1) intArrayOf(0) else iv
        for (o in list) {
            var v = voices.firstOrNull { !it.active }
            if (v == null) v = voices.minByOrNull { if (it.gate) it.age + 1_000_000_000L else it.age }!!
            v.active = true
            v.tag = e.tag
            v.semi = e.semi + o
            v.freq = hz(v.semi)
            v.vel = e.vel
            v.gate = true
            v.stage = 0
            v.fenv = 1f
            v.age = ++ageCtr
            v.arpIv = if (chip && iv.size > 1) iv.copyOf() else null
            v.arpBase = v.semi
            v.arpIdx = -1
            v.n = 0L
            v.vph = 0.0
            if (v.env < 0.001f) {
                for (k in v.ph.indices) v.ph[k] = Math.random()
                v.mph = 0.0
                v.lp = 0f; v.bp = 0f
            }
        }
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    fun render(out: FloatArray, n: Int, w: Long) {
        val evs: List<Ev>
        synchronized(lock) {
            if (queue.isEmpty()) evs = emptyList() else {
                evs = queue.filter { it.frame < w + n }.sortedBy { it.frame }
                queue.removeAll(evs.toSet())
            }
        }
        val st = t
        val wave = st.wave
        val atk = (1.0 / ((0.002 + st.atk * st.atk * 2.5) * sr)).toFloat()
        val relC = exp(-1.0 / ((0.02 + st.rel * st.rel * 3.0) * sr)).toFloat()
        val decC = exp(-1.0 / ((if (wave == 3) 0.25 else 0.6) * sr)).toFloat()
        val sus = if (wave == 3) 0f else if (wave == 4) 0.0f else 0.8f
        val fDec = exp(-1.0 / ((if (wave == 3) 0.12 else 0.35) * sr)).toFloat()
        val q = 1f - st.res * 0.92f
        val vol = st.volNow() * 0.32f
        val spread = st.detune
        val chip = wave >= 5
        // Chip-Arpeggio: 1/32 im Master-Tempo (ohne Tempo: 120 BPM)
        val bpmNow = Engine.currentBpm().let { if (it in 40f..300f) it else 120f }
        val arpTick = max(1L, (sr * 60.0 / bpmNow / 8.0).toLong())
        // NES-Puls: Tastgrad 12,5 / 25 / 50 % · ab 75 % langsame PWM-Fahrt
        val duty = when { spread < 0.25f -> 0.125; spread < 0.5f -> 0.25; spread < 0.75f -> 0.5; else -> -1.0 }
        val pwmRate = 0.3 + spread * 3.0
        var ei = 0
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { apply(evs[ei]); ei++ }
            var mix = 0f
            for (v in voices) {
                if (!v.active) continue
                when (v.stage) {
                    0 -> { v.env += atk; if (v.env >= 1f) { v.env = 1f; v.stage = 1 } }
                    1 -> v.env = sus + (v.env - sus) * decC
                    else -> { v.env *= relC; if (v.env < 1e-4f) { v.active = false; continue } }
                }
                v.fenv *= fDec
                val ai = v.arpIv
                if (ai != null) {
                    val k = ((v.n / arpTick) % ai.size).toInt()
                    if (k != v.arpIdx) { v.arpIdx = k; v.freq = hz(v.arpBase + ai[k]) }
                }
                v.n++
                var f = v.freq
                if (chip) {
                    // verzögertes Vibrato (setzt nach ~0,3 s ein, wie in Tracker-Musik)
                    val vd = ((v.n - sr * 0.3) / (sr * 0.4)).coerceIn(0.0, 1.0)
                    if (vd > 0.0) {
                        v.vph += 5.5 / sr
                        f *= 1.0 + 0.006 * vd * kotlin.math.sin(2 * PI * v.vph)
                    }
                }
                var o: Double
                when (wave) {
                    5, 8 -> {
                        val dt = f / sr
                        v.ph[0] += dt; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        val dc = if (wave == 8 || duty < 0) {
                            v.mph += pwmRate / sr; if (v.mph >= 1.0) v.mph -= 1.0
                            0.5 + 0.42 * kotlin.math.sin(2 * PI * v.mph)
                        } else duty
                        var p = if (v.ph[0] < dc) 1.0 else -1.0
                        p += blep(v.ph[0], dt); p -= blep((v.ph[0] - dc + 1.0) % 1.0, dt)
                        o = (p - (2 * dc - 1)) * 0.7
                    }
                    9 -> {
                        // Game-Boy-Wave-Kanal: 32 Schritte à 4 Bit, Form über DETUNE (Säge, Rechteck-Mix, Sinus-artig, „Buzz“)
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        val k = (v.ph[0] * 32).toInt().coerceIn(0, 31)
                        val tbl = (spread * 3.999f).toInt()
                        val raw = when (tbl) {
                            0 -> k / 31.0 * 2 - 1
                            1 -> (if (k < 16) 0.7 else -0.7) + (k % 16) / 15.0 * 0.6 - 0.3
                            2 -> kotlin.math.sin(2 * PI * k / 32.0)
                            else -> if (k % 8 < 3) 1.0 else -0.6
                        }
                        o = kotlin.math.round((raw + 1) * 7.5) / 7.5 - 1.0
                        o *= 0.8
                    }
                    10 -> {
                        // FM-Chip (2 Operatoren mit Rückkopplung, wie OPL/Mega Drive): Index folgt der Filter-Hüllkurve
                        val ratio = if (spread < 0.5f) 1.0 else 2.0
                        v.mph += f * ratio / sr; if (v.mph >= 1.0) v.mph -= 1.0
                        val fbk = v.nz.toDouble() * (0.2 + st.res * 0.9)
                        val mod = kotlin.math.sin(2 * PI * v.mph + fbk)
                        v.nz = mod.toFloat()
                        val idx = 0.4 + (st.env * 4.0) * v.fenv + st.cutNow() * 1.2
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        o = kotlin.math.sin(2 * PI * v.ph[0] + idx * mod) * 0.75
                    }
                    6 -> {
                        // NES-Dreieck: 32 Stufen (4 Bit)
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        val tri = if (v.ph[0] < 0.5) v.ph[0] * 4 - 1 else 3 - v.ph[0] * 4
                        o = kotlin.math.round(tri * 7.5) / 7.5 * 0.9
                    }
                    7 -> {
                        // Rauschen aus einem 15-Bit-Schieberegister, Takt folgt der Tonhöhe; RES hoch = „kurzer“ metallischer Modus
                        v.ph[0] += f * 8 / sr
                        while (v.ph[0] >= 1.0) {
                            v.ph[0] -= 1.0
                            val tap = if (st.res > 0.5f) 6 else 1
                            val bit = (v.lfsr xor (v.lfsr shr tap)) and 1
                            v.lfsr = (v.lfsr shr 1) or (bit shl 14)
                            v.nz = if (v.lfsr and 1 == 0) 0.6f else -0.6f
                        }
                        o = v.nz.toDouble()
                    }
                    1 -> {
                        val dt = f / sr
                        v.ph[0] += dt; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        var sq = if (v.ph[0] < 0.5) 1.0 else -1.0
                        sq += blep(v.ph[0], dt); sq -= blep((v.ph[0] + 0.5) % 1.0, dt)
                        o = sq * 0.8
                    }
                    2 -> {
                        o = 0.0
                        for (k in 0 until 5) {
                            val fk = f * (1.0 + det[k] * spread * 0.12)
                            val dt = fk / sr
                            v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
                            o += 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
                        }
                        o *= 0.32
                    }
                    4 -> {
                        // FM-Glocke: Modulator 3,5 : 1, Index fällt mit der Hüllkurve
                        v.mph += f * 3.5 / sr; if (v.mph >= 1.0) v.mph -= 1.0
                        val idx = 0.4 + 3.2 * v.fenv
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        o = kotlin.math.sin(2 * PI * v.ph[0] + idx * kotlin.math.sin(2 * PI * v.mph))
                    }
                    else -> {
                        // SAW / PLUCK: zwei leicht verstimmte Sägezähne
                        o = 0.0
                        for (k in 0 until 2) {
                            val fk = f * (1.0 + (if (k == 0) -1 else 1) * spread * 0.006)
                            val dt = fk / sr
                            v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
                            o += 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
                        }
                        o *= 0.5
                    }
                }
                var s = o.toFloat()
                if (wave != 4 && wave != 10 && !(chip && wave != 8 && st.cutNow() > 0.97f)) {
                    val oct = st.cutNow() * 7.5f + st.env * 4.5f * v.fenv + st.cutMod * 2.5f
                    val fc = min(0.42f * sr, 60f * 2f.pow(oct))
                    val g = (2.0 * kotlin.math.sin(PI * fc / sr)).toFloat().coerceAtMost(1.1f)
                    // Chamberlin-SVF (Tiefpass)
                    v.lp += g * v.bp
                    val hp = s - v.lp - q * v.bp
                    v.bp += g * hp
                    s = v.lp
                }
                // Chip: Lautstärke in 16 Stufen wie auf dem Soundchip
                val e = if (chip) kotlin.math.ceil(v.env * 15f) / 15f else v.env
                mix += s * e * v.vel
            }
            out[i] += tanh(mix * vol * 1.2f)
        }
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}
