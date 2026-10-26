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
        /** 0 SAW, 1 SQUARE, 2 SUPERSAW, 3 PLUCK, 4 GLOCKE (FM) */
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
    val synWaveNames = arrayOf("SAW", "SQR", "SUPER", "PLUCK", "GLOCKE")
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

/** 303-artiger Bass-Synth: Sägezahn/Rechteck, resonantes 4-Pol-Filter, Hüllkurve, Accent und Slide. */
class AcidSynth(private val sr: Int) {
    private class Ev(val frame: Long, val on: Boolean, val semi: Int, val acc: Boolean, val slide: Boolean, val vel: Float = 1f)

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private var phase = 0.0
    private var freq = 55.0
    private var target = 55.0
    private var gate = false
    private var amp = 0f
    private var fenv = 0f
    private var accNow = false
    private var y1 = 0f; private var y2 = 0f; private var y3 = 0f; private var y4 = 0f
    private var accEnv = 0f

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((36 + 12 * InstState.acidOct + InstState.acidTune + semi - 69) / 12.0)

    /** frame < 0 = sofort. */
    fun queue(frame: Long, on: Boolean, semi: Int, acc: Boolean, slide: Boolean, vel: Float = 1f) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, acc, slide, vel)) }
    }

    private var velNow = 1f

    private fun apply(e: Ev) {
        if (!e.on) {
            gate = false
            return
        }
        target = hz(e.semi)
        if (!(e.slide && gate)) velNow = e.vel
        if (e.slide && gate) {
            // Slide: Tonhöhe gleitet, Hüllkurven laufen weiter
        } else {
            freq = target
            fenv = 1f
            accNow = e.acc
            if (e.acc) accEnv = 1f
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
        val decT = (0.04 + st.acidDec * 1.6) * sr
        val fDec = exp(-1.0 / decT).toFloat()
        val accDec = exp(-1.0 / (0.12 * sr)).toFloat()
        val glide = 1.0 - exp(-1.0 / ((0.01 + st.acidGlide * 0.24) * sr))
        val accAmt = st.acidAccent * 1.7f
        val drv = 1f + st.acidDrive * 5f
        val res = st.acidRes * 3.95f
        val vol = st.acidVolNow() * 0.9f
        val square = st.acidSquare
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) {
                apply(evs[ei]); ei++
            }
            if (!gate && amp < 1e-5f && fenv < 1e-4f) continue
            freq += (target - freq) * glide
            val dt = freq / sr
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            var osc = if (!square) 2.0 * phase - 1.0 - blep(phase, dt)
            else {
                var sq = if (phase < 0.5) 1.0 else -1.0
                sq += blep(phase, dt)
                sq -= blep((phase + 0.5) % 1.0, dt)
                sq
            }
            osc *= 0.9
            // Hüllkurven
            val ampTarget = if (gate) 1f else 0f
            amp += (ampTarget - amp) * (if (gate) 0.02f else 0.006f)
            fenv *= fDec
            accEnv *= accDec
            val accBoost = if (accNow) accEnv * (0.3f + st.acidEnv) * 2.5f * accAmt / 1.02f else 0f
            val oct = st.acidCutNow() * 7f + (st.acidEnv * 5f) * fenv + accBoost * 2f + st.acidCutMod * 3f
            val fc = min(0.45f * sr, 70f * 2f.pow(oct))
            val g = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()
            val x = tanh(osc.toFloat() - res * y4)
            y1 += g * (x - y1)
            y2 += g * (y1 - y2)
            y3 += g * (y2 - y3)
            y4 += g * (y3 - y4)
            val a = amp * velNow * (if (accNow) 1f + 0.6f * accAmt * accEnv else 1f)
            out[i] += tanh(y4 * (1f + st.acidRes * 1.5f) * 1.4f * drv) / (0.75f + 0.25f * drv) * a * vol
        }
        // spätere Ereignisse zurücklegen
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}

/** 909-artiger Drumcomputer: alle Klänge synthetisiert. */
class DrumMachine(private val sr: Int) {
    private class Voice {
        var active = false
        var delay = 0
        var t = 0
        var acc = 1f
        var ph = DoubleArray(6)
        var p1 = 0.0
        var lp = 0f
        var hp = 0f
        var bp1 = 0f
        var bp2 = 0f
    }

    private val voices = Array(InstState.DV) { Voice() }
    private val lock = Any()
    private var seed = 0x1234567
    private val metal = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)

    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return (seed and 0xFFFF) / 32768f - 1f
    }

    fun trigger(v: Int, delay: Int, accent: Boolean, vel: Float = 1f) {
        if (v !in 0 until InstState.DV) return
        synchronized(lock) {
            val vc = voices[v]
            vc.active = true
            vc.delay = max(0, delay)
            vc.t = 0
            vc.acc = (if (accent) 1.45f else 1f) * vel.coerceIn(0.02f, 1f)
            vc.p1 = 0.0
            for (k in vc.ph.indices) vc.ph[k] = 0.0
            // Closed Hat würgt Open Hat ab
            if (v == 7) voices[8].active = false
        }
    }

    private fun metallic(vc: Voice, tuneMul: Double): Float {
        var s = 0f
        for (k in 0 until 6) {
            vc.ph[k] += metal[k] * tuneMul / sr
            if (vc.ph[k] >= 1.0) vc.ph[k] -= 1.0
            s += if (vc.ph[k] < 0.5) 1f else -1f
        }
        return s / 6f
    }

    fun render(out: FloatArray, n: Int) {
        val st = InstState
        val drive = 1f + st.drumDrive * 6f
        val norm = 1f / tanh(drive)
        synchronized(lock) {
            for (v in 0 until InstState.DV) {
                val vc = voices[v]
                if (!vc.active) continue
                if (st.drumMute[v]) { vc.active = false; continue }
                val tune = st.drumTune[v]
                val dec = st.drumDecay[v]
                val lvl = st.drumLevel[v] * st.drumVolNow() * vc.acc
                for (i in 0 until n) {
                    if (vc.delay > 0) { vc.delay--; continue }
                    val t = vc.t.toFloat() / sr
                    var s: Float
                    var env: Float
                    when (v) {
                        0 -> { // Bass Drum
                            val base = 42.0 * 2.0.pow((tune - 0.5) * 1.2)
                            val f = base * (1.0 + 3.2 * exp(-t / 0.028))
                            vc.p1 += f / sr
                            env = exp(-t / (0.12f + dec * 0.9f))
                            s = sin(2 * PI * vc.p1).toFloat() + noise() * exp(-t / 0.0015f) * 0.35f
                            s = tanh(s * 1.6f)
                        }
                        1 -> { // Snare
                            val f = 175.0 * 2.0.pow((tune - 0.5))
                            vc.p1 += f * (1 + 0.4 * exp(-t / 0.02)) / sr
                            val tone = (sin(2 * PI * vc.p1) + 0.5 * sin(2 * PI * vc.p1 * 1.87)).toFloat() * exp(-t / 0.06f)
                            val nz = noise()
                            vc.hp += (nz - vc.hp) * 0.25f
                            env = 1f
                            s = tone * 0.7f + (nz - vc.hp) * exp(-t / (0.07f + dec * 0.25f)) * 0.9f
                        }
                        2, 3, 4 -> { // Toms
                            val baseF = when (v) { 2 -> 90.0; 3 -> 130.0; else -> 185.0 } * 2.0.pow(tune - 0.5)
                            vc.p1 += baseF * (1 + 0.6 * exp(-t / 0.05)) / sr
                            env = exp(-t / (0.12f + dec * 0.6f))
                            s = sin(2 * PI * vc.p1).toFloat() + noise() * exp(-t / 0.004f) * 0.15f
                        }
                        5 -> { // Rimshot
                            vc.p1 += 1700.0 * 2.0.pow(tune - 0.5) / sr
                            env = exp(-t / (0.01f + dec * 0.02f))
                            s = (sin(2 * PI * vc.p1) + 0.6 * sin(2 * PI * vc.p1 * 0.3)).toFloat()
                        }
                        6 -> { // Clap
                            val nz = noise()
                            vc.bp1 += (nz - vc.bp1) * 0.35f
                            vc.hp += (vc.bp1 - vc.hp) * 0.06f
                            val band = vc.bp1 - vc.hp
                            var e = 0f
                            for (d in 0..3) { val td = t - d * 0.011f; if (td >= 0f) e += exp(-td / 0.006f) }
                            if (t > 0.034f) e += exp(-(t - 0.034f) / (0.1f + dec * 0.4f)) * 0.8f
                            env = 1f
                            s = band * e * 2.2f
                        }
                        7, 8 -> { // Hi-Hats
                            val m = metallic(vc, 1.6 * 2.0.pow(tune - 0.5)) + noise() * 0.25f
                            vc.hp += (m - vc.hp) * 0.55f
                            env = if (v == 7) exp(-t / (0.02f + dec * 0.08f)) else exp(-t / (0.12f + dec * 0.7f))
                            s = (m - vc.hp) * 1.8f
                        }
                        9 -> { // Crash
                            val m = metallic(vc, 2.4 * 2.0.pow(tune - 0.5)) * 0.6f + noise() * 0.6f
                            vc.hp += (m - vc.hp) * 0.45f
                            env = exp(-t / (0.6f + dec * 2.2f))
                            s = (m - vc.hp) * 1.4f
                        }
                        else -> { // Ride
                            val m = metallic(vc, 3.3 * 2.0.pow(tune - 0.5)) * 0.8f + noise() * 0.15f
                            vc.hp += (m - vc.hp) * 0.5f
                            vc.p1 += 3100.0 / sr
                            env = exp(-t / (0.8f + dec * 2.2f))
                            s = (m - vc.hp) * 1.1f + sin(2 * PI * vc.p1).toFloat() * 0.25f * exp(-t / 0.4f)
                        }
                    }
                    out[i] += tanh(s * env * lvl * drive) * norm * 0.8f
                    vc.t++
                    if (vc.t > sr * 4 || (env < 1e-4f && vc.t > sr / 20)) {
                        vc.active = false
                        break
                    }
                }
            }
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
    }

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private val voices = Array(10) { Voice() }
    private var ageCtr = 0L
    private val det = doubleArrayOf(0.0, -0.11, 0.11, -0.05, 0.05)

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((60 + 12 * t.oct + semi - 69) / 12.0)

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
        for (o in iv) {
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
                val f = v.freq
                var o: Double
                when (wave) {
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
                if (wave != 4) {
                    val oct = st.cutNow() * 7.5f + st.env * 4.5f * v.fenv + st.cutMod * 2.5f
                    val fc = min(0.42f * sr, 60f * 2f.pow(oct))
                    val g = (2.0 * kotlin.math.sin(PI * fc / sr)).toFloat().coerceAtMost(1.1f)
                    // Chamberlin-SVF (Tiefpass)
                    v.lp += g * v.bp
                    val hp = s - v.lp - q * v.bp
                    v.bp += g * hp
                    s = v.lp
                }
                mix += s * v.env * v.vel
            }
            out[i] += tanh(mix * vol * 1.2f)
        }
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}
