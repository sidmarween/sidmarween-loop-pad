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
    /** Verzerrer-Pedal hinter der 303 (wie in den 88er-Rave-Acid-Tracks): 0 = aus. */
    @Volatile var acidDist = 0f
    /** Band-Echo (punktierte Achtel, im Takt): 0 = aus. */
    @Volatile var acidEcho = 0f
    /** Filter-Fahrt: Cutoff wandert langsam über 8 Takte auf und zu (wie von Hand gedreht): 0 = aus. */
    @Volatile var acidSweep = 0f
    /** Art der Filter-Fahrt: 0 = Welle (8 Takte auf und zu), 1 = Aufbau über 16 Takte, 2 = Aufbau über 32 Takte
     *  (Cutoff steigt immer weiter, Resonanz und Verzerrung wachsen mit bis zum Kreischen, dann fällt alles zurück). */
    @Volatile var acidSweepMode = 0
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
        /** MONO: nur eine Stimme, Tonhöhe gleitet ([glide] = Portamento-Zeit) – für Basslines. */
        @Volatile var mono = false
        @Volatile var glide = 0f
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
            mono = false; glide = 0f
            when (kind) {
                1 -> { wave = 2; oct = 0; cut = 0.45f; res = 0.15f; atk = 0.45f; rel = 0.6f; vol = 0.45f; detune = 0.5f; env = 0.15f; chord = 0 } // Fläche
                2 -> { wave = 0; oct = -1; cut = 0.32f; res = 0.35f; atk = 0f; rel = 0.12f; vol = 0.65f; detune = 0.08f; env = 0.55f; chord = 0 } // Bass
                3 -> { wave = 11; oct = 0; cut = 0.6f; res = 0f; atk = 0f; rel = 0.3f; vol = 0.6f; detune = 0.3f; env = 0f; chord = 0 } // Keys (Piano)
                4 -> { wave = 17; oct = -1; cut = 0.4f; res = 0.3f; atk = 0f; rel = 0.12f; vol = 0.65f; detune = 0.2f; env = 0.5f; chord = 0; mono = true; glide = 0.2f } // Bassline
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
            j.put("mono", mono); j.put("glide", glide.toDouble())
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
            mono = j.optBoolean("mono", false); glide = j.optDouble("glide", 0.0).toFloat()
        }
    }

    const val SYN_TRACKS = 5
    /** Spur 4 = KEYS (Piano, E-Piano, Orgel, Leads), Spur 5 = BASSLINE (zweiter Bass-Sequenzer neben der 303). */
    const val KEYS_TRACK = 3
    const val BASS_TRACK = 4
    fun synName(t: Int): String = when (t) { KEYS_TRACK -> "KEYS"; BASS_TRACK -> "BASSLINE"; else -> "SYNTH ${t + 1}" }
    fun synShort(t: Int): String = when (t) { KEYS_TRACK -> "K"; BASS_TRACK -> "B"; else -> "${t + 1}" }
    val synTracks = Array(SYN_TRACKS) { SynTrack().apply { soundDefaults(it) } }
    /** Die Synth-Spur, die Editor, Touchpad und Presets gerade bearbeiten. */
    @Volatile var synCur = 0
    val syn: SynTrack get() = synTracks[synCur.coerceIn(0, SYN_TRACKS - 1)]
    val synSoundNames = arrayOf("Lead", "Fläche", "Bass", "Piano", "Bass-Sequenzer")

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
    val synWaveNames = arrayOf("SAW", "SQR", "SUPER", "PLUCK", "GLOCKE", "NES ▢", "NES △", "NOISE", "SID", "GB-WAVE", "FM-CHIP",
        "PIANO", "E-PIANO", "ORGEL", "SYNC-LEAD", "REESE", "SUB-BASS", "FM-BASS", "WOBBLE", "SAITE",
        "WT ANALOG", "WT VOCAL", "WT GROWL", "WT DIGITAL", "WT SAMPLE", "MAGMA", "FUNK-SLAP", "VIBE", "ORGAN-BASS", "ORCH-HIT", "BRASS", "STRINGS", "HOUSE-PIANO", "LORE-PAD", "CHOIR", "M1-BELL", "LADDER (Moog-Art)")
    fun synIsWt(w: Int) = w in 20..24
    /** Chiptune-Klänge (Klang-Nr. 5–10): Akkorde laufen als schnelles Chip-Arpeggio, Lautstärke in 16 Stufen. */
    fun synIsChip(w: Int) = w in 5..10
    /** Auswahl-Liste der Klänge (Gruppe, Klang-Nr., Beschreibung). */
    val synWaveGroups = listOf(
        Triple("Tasten", 11, "Konzertflügel – Anschlag, Saiten, Hammer"),
        Triple("Tasten", 12, "E-Piano (Rhodes-Art, FM) – VERSTIMMUNG = Tremolo"),
        Triple("Tasten", 13, "Orgel (Zugriegel) – CUTOFF = Register, VERST. = Leslie"),
        Triple("Tasten", 4, "FM-Glocke"),
        Triple("Tasten", 27, "🏠 VIBE – House-Stabs, Drift, Pump, Acid-Modus (Vibe-Synth-Feld)"),
        Triple("Bass", 36, "🎛 LADDER – Moog-artiger Analog-Synth: 3 Oszillatoren, 24-dB-Filter, eigenes Bedienfeld (Menü › Instrumente)"),
        Triple("M1 (90er)", 32, "🎹 HOUSE-PIANO – das helle 90er-House-Klavier: harter Anschlag, kurzer Ton, leichter Chorus"),
        Triple("M1 (90er)", 33, "🌌 LORE-PAD – schwebende Fläche mit Glocken-Anschlag · ATTACK = Einblenden"),
        Triple("M1 (90er)", 34, "🎤 CHOIR – „Aah“-Chor mit Chorus · VERST. = Breite, CUTOFF = Vokal (Uuh … Aah)"),
        Triple("M1 (90er)", 35, "🔔 M1-BELL – FM-Glocke/Kalimba, kurz und gläsern"),
        Triple("Orchester", 29, "🎻 ORCH-HIT – 90er-Orchester-Schlag: Streicher + Blech + Pauke · VERST. = Breite"),
        Triple("Orchester", 30, "🎺 BRASS – Bläsersatz mit Anblas-Schmettern · FILTER-HÜLLK. = Stab, ATTACK = Swell"),
        Triple("Orchester", 31, "🎻 STRINGS – Streicher-Ensemble · ATTACK 0 = Stab/Pizz, hoch = Fläche"),
        Triple("Lead", 14, "Sync-Lead – schneidend, FILTER-HÜLLK. = Sync-Fahrt"),
        Triple("Lead", 2, "Supersaw (Trance)"),
        Triple("Lead", 0, "Saw (analog)"),
        Triple("Lead", 1, "Square"),
        Triple("Lead", 3, "Pluck"),
        Triple("Lead", 19, "Saite (gezupft: Gitarre, Harfe) – VERST. = Nachklang"),
        Triple("Bass", 25, "🌋 MAGMA – Viskosität, Krater-Filter, Spucke (Bass-Labor)"),
        Triple("Bass", 26, "🎸 FUNK-SLAP – Slap/Pop/Ghost, Auto-Wah, sprechender Formant (Bass-Labor)"),
        Triple("Bass", 28, "🎹 ORGAN-BASS – perkussiver Garage-/Deep-House-Orgelbass (Bass-Labor)"),
        Triple("Bass", 17, "FM-Bass (DX-Art) – VERST. = Obertöne"),
        Triple("Bass", 15, "Reese-Bass (DnB) – VERST. = Schwebung"),
        Triple("Bass", 16, "Sub-Bass (Sinus, 808) – VERST. = Wärme/Drive"),
        Triple("Bass", 18, "Wobble (Dubstep) – VERST. = Tempo, FILTER-HÜLLK. = Tiefe"),
        Triple("Wavetable", 20, "Analog: Säge → Rechteck → Puls · VERST. = Position, FILTER-HÜLLK. = Fahrt"),
        Triple("Wavetable", 21, "Vocal: Vokale A-E-I-O-U · VERST. = Position"),
        Triple("Wavetable", 22, "Growl: knurrender FM-Bass (EDM) · VERST. = Position"),
        Triple("Wavetable", 23, "Digital: harte PPG-Wellen · VERST. = Position"),
        Triple("Wavetable", 24, "Sample: Wellen aus deinem eigenen Sample (Presets → 📂)"),
        Triple("Chiptune", 5, "NES-Puls"), Triple("Chiptune", 6, "NES-Dreieck"), Triple("Chiptune", 7, "Noise"),
        Triple("Chiptune", 8, "SID (C64)"), Triple("Chiptune", 9, "Game-Boy-Wave"), Triple("Chiptune", 10, "FM-Chip")
    )

    /** Passende Grundeinstellung beim Wählen eines neuen Klangs (nur für die Klänge ab 11). */
    fun waveDefaults(t: SynTrack, w: Int) {
        t.wave = w
        when (w) {
            11 -> { t.cut = 0.6f; t.res = 0f; t.env = 0f; t.atk = 0f; t.rel = 0.3f; t.detune = 0.3f }
            12 -> { t.cut = 0.55f; t.res = 0f; t.env = 0f; t.atk = 0f; t.rel = 0.35f; t.detune = 0.25f }
            13 -> { t.cut = 0.45f; t.res = 0f; t.env = 0.3f; t.atk = 0.02f; t.rel = 0.12f; t.detune = 0.2f }
            14 -> { t.cut = 0.6f; t.res = 0.2f; t.env = 0.5f; t.atk = 0.02f; t.rel = 0.25f; t.detune = 0.3f }
            15 -> { t.cut = 0.42f; t.res = 0.2f; t.env = 0.25f; t.atk = 0f; t.rel = 0.15f; t.detune = 0.45f }
            16 -> { t.cut = 0.5f; t.res = 0f; t.env = 0f; t.atk = 0.02f; t.rel = 0.2f; t.detune = 0.25f }
            17 -> { t.cut = 0.4f; t.res = 0.3f; t.env = 0.5f; t.atk = 0f; t.rel = 0.12f; t.detune = 0.2f }
            18 -> { t.cut = 0.25f; t.res = 0.55f; t.env = 0.7f; t.atk = 0f; t.rel = 0.15f; t.detune = 0.3f }
            19 -> { t.cut = 0.6f; t.res = 0f; t.env = 0f; t.atk = 0f; t.rel = 0.4f; t.detune = 0.6f }
            in 20..24 -> { t.cut = 0.75f; t.res = 0.15f; t.env = 0.35f; t.atk = 0.01f; t.rel = 0.3f; t.detune = 0.2f }
            36 -> { t.mono = true; t.glide = Ladder.glide; t.atk = 0f }
            32 -> { t.cut = 0.8f; t.res = 0f; t.env = 0f; t.atk = 0f; t.rel = 0.2f; t.detune = 0.35f }
            33 -> { t.cut = 0.55f; t.res = 0.1f; t.env = 0.2f; t.atk = 0.35f; t.rel = 0.6f; t.detune = 0.4f }
            34 -> { t.cut = 0.6f; t.res = 0f; t.env = 0f; t.atk = 0.3f; t.rel = 0.5f; t.detune = 0.45f }
            35 -> { t.cut = 0.7f; t.res = 0f; t.env = 0f; t.atk = 0f; t.rel = 0.4f; t.detune = 0.3f }
            29 -> { t.cut = 0.75f; t.res = 0.1f; t.env = 0.4f; t.atk = 0f; t.rel = 0.25f; t.detune = 0.35f }
            30 -> { t.cut = 0.32f; t.res = 0.15f; t.env = 0.65f; t.atk = 0.01f; t.rel = 0.12f; t.detune = 0.35f }
            31 -> { t.cut = 0.55f; t.res = 0.05f; t.env = 0.15f; t.atk = 0f; t.rel = 0.3f; t.detune = 0.45f }
        }
        if (synIsWt(w)) Wavetables.ensure(w - 20)
    }
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

    /** Zufalls-Bassline (BASSLINE-Spur): drei Stile – Offbeat (House), rollende 16tel (Techno/Psy), Funk mit Oktavsprüngen. */
    fun bassRandom() {
        synClear()
        val r = java.util.Random()
        val style = r.nextInt(3)
        val minor = intArrayOf(0, 0, 0, 12, 7, 3, 10, -2, 5)
        var k = 0
        while (k < STEPS) {
            val hit = when (style) {
                0 -> k % 4 == 2 || (k % 2 == 1 && r.nextFloat() < 0.18f)
                1 -> k % 4 != 0
                else -> k == 0 || r.nextFloat() < 0.5f
            }
            if (!hit) { k++; continue }
            synGate[k] = true
            synSemi[k] = when (style) {
                1 -> if (r.nextFloat() < 0.82f) 0 else intArrayOf(12, 7, 3)[r.nextInt(3)]
                0 -> if (r.nextFloat() < 0.6f) 0 else minor[r.nextInt(minor.size)]
                else -> minor[r.nextInt(minor.size)]
            }
            synVel[k] = if (r.nextFloat() < 0.3f) 1f else 0.72f + r.nextFloat() * 0.15f
            val hold = when (style) { 0 -> if (r.nextFloat() < 0.5f) 1 else 0; 1 -> 0; else -> if (r.nextFloat() < 0.3f) 1 + r.nextInt(2) else 0 }
            for (h in 1..hold) if (k + h < STEPS) { synGate[k + h] = true; synTie[k + h] = true; synSemi[k + h] = synSemi[k] }
            k += 1 + hold
        }
        for (i in STEPS until MAX) { synGate[i] = synGate[i % STEPS]; synTie[i] = synTie[i % STEPS]; synSemi[i] = synSemi[i % STEPS]; synVel[i] = synVel[i % STEPS] }
    }

    // ---------------------------------------------------------------- Projekte

    // ---------------------------------------------------------------- Patterns A–D je Instrument

    /** Instrumente mit Pattern-Speicher: 0 = ACID, 1 = DRUM, 2.. = SYNTH-Spuren. */
    const val INSTS = 2 + SYN_TRACKS
    const val PATS = 4
    val instNames = Array(INSTS) { when (it) { 0 -> "ACID"; 1 -> "DRUM"; else -> synName(it - 2) } }
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
        j.put("acidDist", acidDist.toDouble()); j.put("acidEcho", acidEcho.toDouble()); j.put("acidSweep", acidSweep.toDouble()); j.put("acidSweepMode", acidSweepMode)
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
        acidDist = j.optDouble("acidDist", 0.0).toFloat(); acidEcho = j.optDouble("acidEcho", 0.0).toFloat(); acidSweep = j.optDouble("acidSweep", 0.0).toFloat(); acidSweepMode = j.optInt("acidSweepMode", 0).coerceIn(0, 2)
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
    // Verzerrer-Pedal und Band-Echo
    private var dTone = 0f; private var dLp1 = 0f; private var dLp2 = 0f; private var dHp = 0f; private var dHpX = 0f
    private val echo = FloatArray(sr * 2)
    private var echoW = 0
    private var echoLp = 0f

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
            // nur wenn ein Ereignis in diesem Block fällig ist, Listen bauen (sonst jeden Block Müll für den GC)
            var due = false
            for (qi in 0 until queue.size) if (queue[qi].frame < w + n) { due = true; break }
            if (!due) evs = emptyList() else {
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
        val drv = 1f + st.acidDrive * 4f
        val vol = st.acidVolNow() * 1.1f
        val square = st.acidSquare
        // Grund-Cutoff 314 Hz … 2,4 kHz (Original-Bereich), Touchpad dazu
        // Filter-Fahrt: über 8 Takte auf und zu (im Takt-Raster), bis ±1,5 Oktaven
        val sweep = st.acidSweep
        // Aufbau: Rampe von zu nach offen über 16/32 Takte (immer schneller steigend), Resonanz + Verzerrung wachsen mit
        var build = 0.0
        val sweepOct = if (sweep <= 0.01f) 0.0 else if (st.acidSweepMode == 0) kotlin.math.sin(2 * PI * Engine.beatPos(w) / 32.0) * sweep * 1.5 else {
            val beats = if (st.acidSweepMode == 1) 64.0 else 128.0
            val ph = Engine.beatPos(w) / beats
            build = (ph - kotlin.math.floor(ph)).pow(1.6)
            (build * 2.0 - 1.0) * sweep * 2.6
        }
        val baseCut = 314.0 * (2394.0 / 314.0).pow(st.acidCutNow().toDouble().coerceIn(0.0, 1.0)) * 2.0.pow(st.acidCutMod * 2.0 + sweepOct)
        val r = (st.acidRes + build * sweep * 0.12).coerceIn(0.0, 0.995)
        val rs = (1.0 - exp(-3.0 * r)) / (1.0 - exp(-3.0))   // Resonanz wie beim Original „schief“
        // Pedal: Mitten-Anhebung vor dem Clipping, Tiefpass danach (gegen Kratzen), Ausgang angeglichen
        val dist = (st.acidDist + (build * sweep * 0.45).toFloat()).coerceAtMost(1f)
        val dGain = 1f + 55f * dist * dist
        val aTone = (1.0 - exp(-2 * PI * 420.0 / sr)).toFloat()
        val aDlp = (1.0 - exp(-2 * PI * (6500.0 - 2500.0 * dist) / sr)).toFloat()
        val aDhp = exp(-2 * PI * 60.0 / sr).toFloat()
        val dOut = 0.4f / (1f + dist * 0.35f)
        // Echo: punktierte Achtel im Song-Tempo
        val ech = st.acidEcho
        val eLen = (Engine.seqBeatLen() * 0.75).toInt().coerceIn(sr / 20, echo.size - 1)
        val eFb = 0.3f + 0.35f * ech
        val eLpA = 0.35f
        val aPre = exp(-2 * PI * 44.0 / sr)
        val aPost = exp(-2 * PI * 24.0 / sr)
        val aFb = exp(-2 * PI * 150.0 / sr)
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { apply(evs[ei]); ei++ }
            if (!gate && amp < 1e-5f && abs(y4) < 1e-7 && (st.acidEcho < 0.01f || abs(echoLp) < 1e-6f)) {
                // Echo klingt nach, auch wenn die 303 schweigt
                if (ech > 0.01f) { echo[echoW] = 0f; echoW++; if (echoW >= echo.size) echoW = 0 }
                continue
            }
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
            var y = (tanh(ph * 1.6 * drv) / (0.8 + 0.2 * drv)).toFloat() * a
            if (dist > 0.01f) {
                // Verzerrer hinter dem VCA (wie ein Pedal am Ausgang): auch ausklingende Töne bleiben rau
                dTone += aTone * (y - dTone)
                val pre = (y + (y - dTone) * 1.4f) * dGain + 0.12f
                val c = pre / (1f + abs(pre)) * 1.6f - 0.12f / (1f + 0.12f)   // asymmetrisch → geradzahlige Obertöne
                val cl = c.coerceIn(-1f, 1f)
                dLp1 += aDlp * (cl - dLp1); dLp2 += aDlp * (dLp1 - dLp2)
                val hp = aDhp * (dHp + dLp2 - dHpX); dHpX = dLp2; dHp = hp
                y = y * (1f - dist) + hp * dOut * dist * 1.8f
            }
            if (ech > 0.01f) {
                var rp = echoW - eLen; if (rp < 0) rp += echo.size
                val d = echo[rp]
                echoLp += eLpA * (d - echoLp)
                echo[echoW] = y + echoLp * eFb
                echoW++; if (echoW >= echo.size) echoW = 0
                y += echoLp * ech * 0.7f
            }
            out[i] += y * vol
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
                    val smp = UserSamples.drum(v)
                    if (smp != null) {
                        // eigenes Sample: TUNE = Tonhöhe (±1 Oktave), DECAY = Länge (kurz abgeschnitten … ganz)
                        val pos = vc.p1
                        vc.p1 += 2.0.pow((tune - 0.5) * 2.0)
                        val i0 = pos.toInt()
                        if (i0 + 1 >= smp.size) { s = 0.0; env = 0.0 }
                        else {
                            val fr = pos - i0
                            s = (smp[i0] + (smp[i0 + 1] - smp[i0]) * fr).toDouble()
                            val maxT = 0.04 + dec * dec * 4.0
                            env = if (t < maxT) 1.0 else exp(-(t - maxT) / 0.025)
                        }
                    } else if (model > 0) {
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
        /** Zielton beim Gleiten (MONO + GLIDE). */
        var tgt = 220.0
        var vel = 1f
        var gate = false
        var env = 0f
        var stage = 0      // 0 Attack, 1 Decay/Sustain, 2 Release
        var fenv = 0f
        val ph = DoubleArray(16)
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
        // Teiltöne (Piano) und Hüllkurven der Modelle
        val inc = DoubleArray(16)
        /** Sinus-Resonatoren der Teiltöne (y[n] = k·y[n−1] − y[n−2]): ein Multiplizieren statt Tabellen-Sinus. */
        val rs1 = DoubleArray(16); val rs2 = DoubleArray(16); val rk = DoubleArray(16)
        val pa = FloatArray(16)
        val pd = FloatArray(16)
        var np = 0
        /** Hammer-/Klick-Rauschen (Piano, Orgel). */
        var hn = 0f
        var nlp = 0f
        var rnd = 22222
        // Saite (Karplus-Strong)
        var ks: FloatArray? = null
        var kw = 0
        /** Zustände der Bass-Motoren (MAGMA, FUNK). */
        val m = FloatArray(24)
        /** Filter-Koeffizient (Steuer-Rate). */
        var fg = 0f
        /** Steuer-Raten-Speicher der Modelle (MAGMA, FUNK, VIBE). */
        val c = FloatArray(8)
    }

    private companion object {
        const val TN = 4096
        val SIN = FloatArray(TN + 1) { kotlin.math.sin(2 * PI * it / TN).toFloat() }
        /** Sinus aus der Tabelle, Phase in Umdrehungen. */
        fun sinT(p: Double): Float {
            val x = (p - kotlin.math.floor(p)) * TN
            val i = min(x.toInt(), TN - 1)
            val fr = (x - i).toFloat()
            return SIN[i] + (SIN[i + 1] - SIN[i]) * fr
        }
        const val TWO_PI_INV = 1.0 / (2 * PI)
        /** Schnelle tanh-Näherung (Padé), weich begrenzt – weicht < 2 % von tanh ab. */
        fun fastTanh(x: Float): Float = if (x > 3f) 1f else if (x < -3f) -1f else { val x2 = x * x; x * (27f + x2) / (27f + 9f * x2) }
        // Orgel-Zugriegel: 16' 8' 5⅓' 4' 2⅔' 2' 1⅗' 1⅓' 1'
        val ORG_R = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0)
        /** dieselben Fußlagen als Vielfache des 16'-Tons */
        val ORG_M = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16)
        val ORG_JAZZ = floatArrayOf(0.8f, 1f, 0.6f, 0f, 0f, 0f, 0f, 0f, 0f)
        val ORG_GOSPEL = floatArrayOf(1f, 1f, 1f, 0.6f, 0.3f, 0.4f, 0f, 0f, 0f)
        val ORG_FULL = floatArrayOf(1f, 1f, 1f, 1f, 0.8f, 0.8f, 0.6f, 0.6f, 0.7f)
        val WOB_MULT = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    }

    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private val voices = Array(10) { Voice() }
    private var ageCtr = 0L
    private val det = doubleArrayOf(0.0, -0.11, 0.11, -0.05, 0.05)
    private val orgW = FloatArray(9)
    private val orgH = FloatArray(17)
    /** Leslie-Rotor (gemeinsam für alle Orgel-Stimmen). */
    private var leslie = 0.0

    private fun hz(semi: Int): Double = 440.0 * 2.0.pow((60 + 12 * t.oct + t.transpose + semi - 69) / 12.0)

    /** frame < 0 = sofort. [tag] verbindet An und Aus (z. B. Schritt oder Finger). */
    fun queue(frame: Long, on: Boolean, semi: Int, vel: Float, tag: Int, iv: IntArray? = null) {
        synchronized(lock) { queue.add(Ev(frame, on, semi, vel, tag, iv)) }
    }

    fun allOff() {
        synchronized(lock) { queue.clear() }
        for (v in voices) if (v.active) { v.gate = false; v.stage = 2 }
    }

    private fun rnd(v: Voice): Float {
        v.rnd = v.rnd * 1664525 + 1013904223
        return ((v.rnd ushr 8) / 16777216f) * 2f - 1f
    }

    /** Anschlag: Modell-Zustand je Klang neu aufsetzen (Teiltöne, Hüllkurven, Saite). */
    private fun noteInit(v: Voice, vel: Float) {
        val f = v.tgt
        when (t.wave) {
            11 -> {
                // Flügel: 10 leicht unharmonische Teiltöne (Saitensteife), hohe klingen schneller ab,
                // Anschlag macht heller; die drei tiefsten doppelt (zweite Saite, leichte Schwebung) + Hammer-Geräusch
                val bright = (t.cutNow() * (0.45f + 0.55f * vel)).coerceIn(0f, 1f)
                val t0 = (3.5 * (261.6 / f).pow(0.5)).coerceIn(0.6, 10.0)
                val bInh = 0.00035 * (f / 261.6).pow(0.5).coerceIn(0.3, 3.0)
                var n = 0
                for (k in 1..10) {
                    val fk = f * k * kotlin.math.sqrt(1.0 + bInh * k * k)
                    if (fk > sr * 0.45) break
                    v.inc[n] = fk / sr
                    v.pa[n] = ((1.0 / k.toDouble().pow(2.4 - 1.6 * bright)) * (0.4 + 0.6 * abs(kotlin.math.sin(PI * k / 7.3)))).toFloat()
                    v.pd[n] = exp(-1.0 / (t0 / (1.0 + 0.45 * (k - 1)) * sr)).toFloat()
                    n++
                }
                val base = n
                val dt = 1.0 + 0.0004 + t.detune * 0.0012
                for (k in 0 until min(3, base)) { v.inc[n] = v.inc[k] * dt; v.pa[n] = v.pa[k] * 0.7f; v.pd[n] = v.pd[k]; n++ }
                v.np = n
                for (k in 0 until n) { val wk = 2 * PI * v.inc[k]; v.rk[k] = 2 * kotlin.math.cos(wk); v.rs1[k] = 0.0; v.rs2[k] = -kotlin.math.sin(wk) }
                v.hn = 0.22f * vel * (0.5f + bright)
            }
            12 -> {
                // E-Piano: Lautstärke, FM-Index und „Tine“-Anschlag klingen getrennt ab
                v.pa[0] = 1f; v.pd[0] = exp(-1.0 / ((2.8 * (261.6 / f).pow(0.4)).coerceIn(0.6, 7.0) * sr)).toFloat()
                v.pa[1] = 1f; v.pd[1] = exp(-1.0 / (0.5 * sr)).toFloat()
                v.pa[2] = 1f; v.pd[2] = exp(-1.0 / (0.06 * sr)).toFloat()
                for (k in 0 until 3) v.ph[k] = 0.0
            }
            13 -> {
                // Orgel: Perkussion (2⅔'-Ton, kurz) über FILTER-HÜLLKURVE, kleiner Tasten-Klick
                v.pa[0] = 1f; v.pd[0] = exp(-1.0 / (0.22 * sr)).toFloat()
                v.hn = 0.1f * vel
            }
            26 -> {
                // FUNK: Slap (Daumen), Pop (Finger, laut/hoch) oder Ghost (leise = gedämpfter Plopp)
                val mode = if (vel < 0.45f) 2 else if (vel >= 0.88f || v.semi >= 10) 1 else 0
                java.util.Arrays.fill(v.m, 0f)
                v.m[0] = mode.toFloat(); v.m[1] = 1f; v.m[2] = 1f; v.m[5] = 1f
                val len = sr / 12 + 4
                if (v.ks == null || v.ks!!.size < len) v.ks = FloatArray(len)
                val buf = v.ks!!
                java.util.Arrays.fill(buf, 0f)
                val d = (sr / f).coerceIn(2.0, (buf.size - 3).toDouble())
                val cnt = d.toInt() + 2
                var lp = 0f
                val a = when (mode) { 1 -> 0.9f; 2 -> 0.12f; else -> 0.45f }
                val amp = when (mode) { 2 -> 0.5f; 1 -> 1.3f; else -> 1.1f }
                for (i in 0 until cnt) { lp += a * (rnd(v) - lp); buf[i] = lp * amp * 2.2f }
                v.kw = cnt % buf.size
                v.nz = 0f
                v.lp = 0f; v.bp = 0f; v.fg = 0f
            }
            29 -> {
                // ORCH-HIT: Pauken-Tonhöhe, Pauken-Lautstärke, Anschlag-Rauschen neu
                v.m[0] = 1f; v.m[1] = 1f; v.m[2] = 1f
                for (k in 0 until 6) v.ph[k] = 0.0
            }
            30 -> v.m[0] = 1f   // BRASS: Anblas-Tonhöhe
            32 -> {
                // HOUSE-PIANO (M1-Art): helle, kurz klingende Teiltöne – starke 2.–6. Harmonische, oben schnell weg, Chorus-Paar
                val bright = (t.cutNow() * (0.5f + 0.5f * vel)).coerceIn(0f, 1f)
                val t0 = (1.4 * (261.6 / f).pow(0.45)).coerceIn(0.35, 4.0)
                val amps = doubleArrayOf(1.0, 0.95, 0.7, 0.55, 0.45, 0.32, 0.22, 0.15, 0.1, 0.07)
                var n = 0
                for (k in 1..10) {
                    val fk = f * k * (1.0 + 0.0002 * k * k)
                    if (fk > sr * 0.45) break
                    v.inc[n] = fk / sr
                    v.pa[n] = (amps[k - 1] * (0.35 + 0.65 * bright).pow(if (k > 3) 1.0 else 0.0) * 0.55).toFloat()
                    v.pd[n] = exp(-1.0 / (t0 / (1.0 + 0.8 * (k - 1)) * sr)).toFloat()
                    n++
                }
                val base = n
                val dt = 1.0 + 0.0015 + t.detune * 0.004
                for (k in 0 until min(4, base)) { v.inc[n] = v.inc[k] * dt; v.pa[n] = v.pa[k] * 0.6f; v.pd[n] = v.pd[k]; n++ }
                v.np = n
                for (k in 0 until n) { val wk = 2 * PI * v.inc[k]; v.rk[k] = 2 * kotlin.math.cos(wk); v.rs1[k] = 0.0; v.rs2[k] = -kotlin.math.sin(wk) }
                v.hn = 0.35f * vel * (0.4f + bright)
            }
            33 -> { v.m[0] = 1f; for (k in 0 until 4) v.ph[k] = Math.random() }
            36 -> { v.m[0] = 0f; v.m[1] = 0f; v.m[2] = 0f; v.m[3] = 0f; v.c[0] = 0f }   // LADDER: Hüllkurven neu (Filter-Zustand bleibt – klingt wie das Original)   // LORE: Glocken-Anschlag, freie Phasen
            34 -> { java.util.Arrays.fill(v.m, 0f); for (k in 0 until 3) v.ph[k] = Math.random() }
            35 -> { v.m[0] = 1f; v.m[1] = 1f; v.ph[0] = 0.0; v.ph[1] = 0.0; v.ph[2] = 0.0 }
            28 -> {
                // ORGAN-BASS: Holz-FM und Klick neu anschlagen
                v.m[0] = 1f; v.m[1] = 1f
            }
            25 -> {
                // MAGMA: Blubber-LFOs neu, Glitch aus
                java.util.Arrays.fill(v.m, 0f)
            }
            19 -> {
                // Saite: Verzögerungsleitung mit gefiltertem Rauschen füllen (CUTOFF = Härte des Anschlags)
                val len = sr / 12 + 4
                if (v.ks == null || v.ks!!.size < len) v.ks = FloatArray(len)
                val buf = v.ks!!
                java.util.Arrays.fill(buf, 0f)
                val d = (sr / f).coerceIn(2.0, (buf.size - 3).toDouble())
                val cnt = d.toInt() + 2
                var lp = 0f
                val a = 0.12f + 0.88f * t.cutNow() * (0.5f + 0.5f * vel)
                for (i in 0 until cnt) { lp += a * (rnd(v) - lp); buf[i] = lp * (0.6f + 0.4f * vel) * 2.6f }
                v.kw = cnt % buf.size
                v.nz = 0f
            }
        }
    }

    private fun apply(e: Ev) {
        var iv = e.iv ?: InstState.synChordIv[t.chord.coerceIn(0, InstState.synChordIv.size - 1)]
        if (t.wave == Vibe.WAVE && Vibe.chord > 0 && !Vibe.acid()) iv = vibeChord(iv)
        if (!e.on) {
            for (v in voices) if (v.active && v.tag == e.tag && v.gate) { v.gate = false; v.stage = 2 }
            return
        }
        val chip = InstState.synIsChip(t.wave)
        if ((t.mono || (t.wave == Vibe.WAVE && Vibe.acid())) && !chip) {
            // MONO: eine Stimme, neue Note gleitet vom alten Ton (GLIDE), Hüllkurve setzt ohne Knacken neu an
            val v = voices[0]
            for (k in 1 until voices.size) if (voices[k].active) { voices[k].gate = false; voices[k].stage = 2 }
            val sounding = v.active && v.env > 0.001f
            val legato = v.active && v.gate
            v.active = true
            v.tag = e.tag
            v.semi = e.semi
            v.tgt = hz(e.semi)
            if (!(t.glide > 0.01f && sounding)) v.freq = v.tgt
            v.vel = e.vel
            v.gate = true
            v.age = ++ageCtr
            v.arpIv = null
            v.n = 0L
            if (!sounding) {
                for (k in v.ph.indices) v.ph[k] = Math.random()
                v.mph = 0.0
                v.lp = 0f; v.bp = 0f; v.fg = 0f
            }
            if (!legato) { v.stage = 0; v.fenv = 1f; noteInit(v, e.vel) }
            return
        }
        // Chiptune: Akkord als EINE Stimme, die die Töne schnell durchläuft (wie auf NES/Game Boy/C64)
        val list = if (chip && iv.size > 1) intArrayOf(0) else iv
        for (o in list) {
            var v = voices.firstOrNull { !it.active }
            if (v == null) v = voices.minByOrNull { if (it.gate) it.age + 1_000_000_000L else it.age }!!
            v.active = true
            v.tag = e.tag
            v.semi = e.semi + o
            v.freq = hz(v.semi)
            v.tgt = v.freq
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
                v.lp = 0f; v.bp = 0f; v.fg = 0f
            }
            noteInit(v, e.vel)
        }
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    /** 🌋 MAGMA: Sub-Anker (sauberer Sinus ≤ 62 Hz) + Magma-Oberteil (Falten, Blubbern, Crush) durch Röhre und Krater-Ladder. */
    private fun magma(v: Voice, f: Double, kick: Float, amtMid: Float, amtHigh: Float, ladK: Float): Double {
        val bl = BassLab
        val m = v.m
        // Spucke: Pitch-Glitches und Noise-Bursts
        val ctl = (v.n and 7L) == 1L
        if (m[10] <= 0f && bl.spit > 0.01f && ctl && rnd(v) * 0.5f + 0.5f < bl.spit * bl.spit * 0.0048f) {
            m[10] = (sr * (0.02 + 0.05 * Math.random())).toFloat()
            m[11] = 2.0.pow((Math.random() * 24 - 12) / 12).toFloat()
            m[12] = if (Math.random() < 0.4) 1f else 0f
        }
        val glitch = m[10] > 0f
        if (glitch) m[10]--
        val ff = f * (1.0 + 1.5 * kick) * (if (glitch) m[11].toDouble() else 1.0)
        // Anker: phasenstabil, bleibt unberührt
        var fa = f
        while (fa > 62.0) fa *= 0.5
        v.ph[0] += fa / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
        val anchor = sinT(v.ph[0])
        // Blubber-LFOs (3 zufällige, weiche Bewegungen)
        for (k in 0 until 3) {
            if (m[6 + k] <= 0f) { m[3 + k] = (Math.random() * 2 - 1).toFloat(); m[6 + k] = (sr / (1.5 + 4.0 * k + 6 * Math.random())).toFloat() }
            m[6 + k]--
            m[k] += (m[3 + k] - m[k]) * (0.0006f * (k + 1))
        }
        val bub = m[0] * 0.5f + m[1] * 0.3f + m[2] * 0.2f
        // Oberteil mit FM-Feedback (Spucke)
        v.ph[1] += ff / sr; if (v.ph[1] >= 1.0) v.ph[1] -= 1.0
        val x = sinT(v.ph[1] + m[13] * bl.spit * 0.35)
        val drv = 1f + amtMid * (1.5f + 1.2f * bub) + amtHigh * 6f
        var y = sinT((x * drv * 0.25).toDouble())
        y += (1f - amtMid) * 0.25f * x * x
        if (amtHigh > 0.01f) {
            v.ph[2] += ff * 2.41 / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
            y = y * (1f - amtHigh * 0.5f) + amtHigh * 0.6f * y * sinT(v.ph[2])
            if (m[15] <= 0f) {
                val q = 2f.pow(8f - 6f * amtHigh)
                m[14] = kotlin.math.round(y * q) / q
                m[15] = 1f + amtHigh * 6f
            }
            m[15]--
            y = m[14]
        }
        if (glitch && m[12] > 0f) y += rnd(v) * 0.6f
        // Hochpass: unten bleibt nur der Anker
        val hpc = (2 * PI * 90.0 / sr).toFloat()
        m[16] += hpc * (y - m[16])
        var top = y - m[16]
        // Röhre
        val dr = 1.5f + bl.drive * 6f
        if (ctl) { v.c[1] = 0.6f / tanh(dr * 0.6f).coerceAtLeast(0.3f) }
        top = (fastTanh(top * dr + 0.2f) - 0.19738f) * v.c[1]
        // Krater: 4-Pol-Ladder mit aggressiver Rückkopplung
        if (ctl) {
            val oct = bl.craterCut * 8.2f + kick * 3f + amtMid * bub * 1.5f
            val fc = min(0.45 * sr, 40.0 * 2.0.pow(oct.toDouble()))
            v.c[0] = (1 - exp(-2 * PI * fc / sr)).toFloat()
        }
        val g = v.c[0]
        val u = fastTanh(top - ladK * fastTanh(m[20] * 1.3f))
        val t17 = fastTanh(m[17]); val t18 = fastTanh(m[18]); val t19 = fastTanh(m[19])
        m[17] += g * (u - t17)
        m[18] += g * (t17 - t18)
        m[19] += g * (t18 - t19)
        m[20] += g * (t19 - fastTanh(m[20]))
        top = m[20] * (1f + ladK * 0.35f)
        m[13] = top
        return (anchor * bl.sub * 0.9f + top * 0.75f).toDouble()
    }

    /** 🎹 Flügel: Teiltöne als Sinus-Resonatoren + Hammer-Geräusch. */
    private fun piano(v: Voice, pianoHd: Float): Double {
        // Flügel
        var sum = 0f
        for (k in 0 until v.np) {
            if (v.pa[k] < 1e-5f) continue   // verklungener Teilton: nicht mehr rechnen
            val y = v.rk[k] * v.rs1[k] - v.rs2[k]
            v.rs2[k] = v.rs1[k]; v.rs1[k] = y
            sum += v.pa[k] * y.toFloat()
            v.pa[k] *= v.pd[k]
        }
        if (v.hn > 1e-4f) {
            v.nlp += 0.35f * (rnd(v) - v.nlp)
            sum += v.nlp * v.hn
            v.hn *= pianoHd
        }
        return sum * 0.5
    }

    /** E-Piano (FM). */
    private fun ePiano(v: Voice, f: Double, cutN: Float, spread: Float): Double {
        // E-Piano (FM): Körper 1:1 mit abklingendem Index + metallischer Anschlag (14:1), Tremolo über VERSTIMMUNG
        val vel = v.vel
        val bright = cutN
        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
        v.ph[2] += f * 14.0 / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
        val i1 = (0.25 + 1.8 * vel * bright) * (0.3 + 0.7 * v.pa[1])
        val body = sinT(v.ph[0] + i1 * sinT(v.ph[0]) * TWO_PI_INV)   // Modulator 1:1 läuft phasengleich mit dem Träger
        val tine = sinT(v.ph[0] + 2.2 * sinT(v.ph[2]) * TWO_PI_INV) * v.pa[2] * 0.35f * vel
        v.mph += 4.8 / sr; if (v.mph >= 1.0) v.mph -= 1.0
        val trem = 1f - spread * 0.55f * (0.5f + 0.5f * sinT(v.mph))
        val o = fastTanh((body * v.pa[0] + tine) * trem * (1f + vel * 0.8f)) * 0.85
        v.pa[0] *= v.pd[0]; v.pa[1] *= v.pd[1]; v.pa[2] *= v.pd[2]
        return o
    }

    /** Orgel (Zugriegel + Leslie). */
    private fun organ(v: Voice, f: Double, spread: Float, lesV: Float, lesA: Float, orgNorm: Float, envA: Float, clickHd: Float): Double {
        // Orgel (Zugriegel + Leslie)
        val fv = f * (1.0 + 0.0035 * spread * lesV)
        // alle Zugriegel sind ganzzahlige Vielfache des 16'-Tons: ein Tabellen-Sinus + Tschebyschow-Reihe
        v.ph[0] += fv * 0.5 / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
        val h = orgH
        val s1 = sinT(v.ph[0]); val c2 = 2f * sinT(v.ph[0] + 0.25)
        h[0] = 0f; h[1] = s1
        val top = min(16, (sr * 0.45 / (fv * 0.5)).toInt())
        for (m in 2..top) h[m] = c2 * h[m - 1] - h[m - 2]
        var sum = 0f
        for (k in 0 until 9) {
            val wk = orgW[k]
            if (wk < 0.01f) continue
            val mk = ORG_M[k]
            if (mk > top) continue
            sum += wk * h[mk]
        }
        sum *= orgNorm
        if (envA > 0.03f && v.pa[0] > 1e-4f && top >= 6) {
            sum += v.pa[0] * envA * 0.8f * h[6]
            v.pa[0] *= v.pd[0]
        }
        if (v.hn > 1e-4f) { sum += rnd(v) * v.hn; v.hn *= clickHd }
        return (sum * lesA).toDouble() * 0.8
    }

    /** 🎸 FUNK: Saite (Karplus-Strong) + Slap-Daumen/Pop-Klick/Ghost-Plopp, Auto-Wah, Formant, Phaser, Band-Sättigung, Thumb-Sub. */
    private fun funk(v: Voice, f: Double, juice: Float, tightT: Double): Double {
        val bl = BassLab
        val m = v.m
        val mode = m[0].toInt()
        val buf = v.ks ?: return 0.0
        val sz = buf.size
        val tSec = v.n.toFloat() / sr
        val d = (sr / f - 0.5).coerceIn(2.0, (sz - 3).toDouble())
        var rp = v.kw - d
        if (rp < 0) rp += sz
        val i0 = rp.toInt() % sz
        val i1 = (i0 + 1) % sz
        val fr = (rp - kotlin.math.floor(rp)).toFloat()
        val yv = buf[i0] + (buf[i1] - buf[i0]) * fr
        val ctl = (v.n and 7L) == 1L
        if (ctl) {
            val tt = if (mode == 2) 0.03 else tightT
            v.c[0] = exp(-1.0 / (f * tt)).toFloat()
        }
        val loss = v.c[0]
        val nv = (yv + v.nz) * 0.5f * loss
        v.nz = yv
        buf[v.kw] = nv
        v.kw = (v.kw + 1) % sz
        var x = nv
        // Daumen: holziger Pitch-Impuls / Ghost: dumpfer Plopp
        if (m[2] > 1e-3f) {
            val pf = f * (1.0 + (if (mode == 2) 1.5 else 4.0) * m[2])
            v.ph[3] += pf / sr; if (v.ph[3] >= 1.0) v.ph[3] -= 1.0
            x += sinT(v.ph[3]) * m[2] * (if (mode == 2) 0.6f else 0.7f)
            m[2] *= if (mode == 2) kGhost else kThumb
        }
        // Pop: metallischer Klick
        if (mode == 1 && m[1] > 1e-3f) {
            v.ph[4] += 3200.0 / sr; if (v.ph[4] >= 1.0) v.ph[4] -= 1.0
            val nz = rnd(v)
            m[6] += 0.6f * (nz - m[6])
            x += ((nz - m[6]) * 0.6f + sinT(v.ph[4]) * 0.4f) * m[1]
            m[1] *= kPop
        }
        // Auto-Wah: schnell auf (Quack), dann unter den Grundwert fallen (Ow)
        if (ctl) {
            val up = 1f - exp(-tSec / 0.004f)
            val down = exp(-tSec / (0.12f + 0.3f * (1f - juice)))
            val wah = up * down
            v.c[2] = wah
            val cut = 220.0 * 2.0.pow((0.9 + juice * 4.2 * wah - juice * 1.1 * (1 - down) + (if (mode == 1) 0.8 else 0.0)).coerceIn(-1.0, 6.5))
            v.c[1] = (2 * sin(PI * min(cut, sr * 0.4) / sr)).toFloat()
            v.c[3] = (2 * sin(PI * (325.0 + (800.0 - 325.0) * wah) / sr)).toFloat()
            v.c[4] = (2 * sin(PI * (700.0 + (1150.0 - 700.0) * wah) / sr)).toFloat()
        }
        val g = v.c[1]
        v.lp += g * v.bp
        val hp = x - v.lp - 0.3f * v.bp
        v.bp += g * hp
        var y = v.lp * 0.65f + v.bp * (0.4f + 0.6f * juice)
        // Formant: U → A beim Anschlag, dann zurück („Yow-Waw“)
        if (bl.talk > 0.01f) {
            val g1 = v.c[3]; val g2 = v.c[4]
            m[8] += g1 * m[9]; val h1 = x - m[8] - 0.15f * m[9]; m[9] += g1 * h1
            m[10] += g2 * m[11]; val h2 = x - m[10] - 0.15f * m[11]; m[11] += g2 * h2
            val form = m[9] * 0.9f + m[11] * 0.55f
            y = y * (1f - bl.talk) + form * bl.talk * 1.6f
        }
        // 6-stufiger Phaser (langsam), Stärke mit dem Juice
        if (juice > 0.05f) {
            v.mph += 0.25 / sr; if (v.mph >= 1.0) v.mph -= 1.0
            if (ctl) {
                val lf = 0.5f + 0.5f * sinT(v.mph)
                val tw = (PI * 300.0 * 2.0.pow(lf * 3.0) / sr).toFloat()
                v.c[5] = (1f - tw) / (1f + tw)
            }
            val a = v.c[5]
            var ap = y
            for (k in 0 until 6) {
                val zi = 12 + k
                val o2 = -a * ap + m[zi]
                m[zi] = ap + a * o2
                ap = o2
            }
            y += (ap - y) * 0.5f * juice
        }
        // Band-Sättigung (Disco-Dirt)
        val sat = 1f + juice * 2.5f
        y = fastTanh(y * sat) / fastTanh(sat).coerceAtLeast(0.5f)
        // Thumb-Sub (808-Sinus), klingt mit der Saite aus
        v.ph[5] += f / sr; if (v.ph[5] >= 1.0) v.ph[5] -= 1.0
        if (ctl) v.c[6] = exp(-1.0 / ((if (mode == 2) 0.04 else tightT * 1.5 + 0.1) * sr)).toFloat()
        m[5] *= v.c[6]
        y += sinT(v.ph[5]) * m[5] * bl.thumbSub * 0.8f
        return y.toDouble()
    }

    private var tapeLp = 0f
    // feste Abkling-Faktoren (früher je Probe neu berechnet)
    private val kGhost = exp(-1.0 / (0.02 * sr)).toFloat()
    private val kThumb = exp(-1.0 / (0.012 * sr)).toFloat()
    private val kPop = exp(-1.0 / (0.006 * sr)).toFloat()
    private val kWood = exp(-1.0 / (0.04 * sr)).toFloat()
    private val kClick = exp(-1.0 / (0.003 * sr)).toFloat()

    private val kBoomP = exp(-1.0 / (0.05 * sr)).toFloat()
    private val kBoomA = exp(-1.0 / (0.3 * sr)).toFloat()
    private val kHitN = exp(-1.0 / (0.02 * sr)).toFloat()
    private val kBlip = exp(-1.0 / (0.04 * sr)).toFloat()

    /** 🎻 ORCH-HIT: Streicher (2 verstimmte Sägen + Oktave), tiefes Blech, Pauke mit Tonhöhen-Fall, Anschlag-Rauschen. */
    private fun orchHit(v: Voice, f: Double, spread: Float): Double {
        val m = v.m
        val d = 0.004 + spread * 0.012
        var y = saw(v, 0, f * (1 - d)) + saw(v, 1, f * (1 + d)) + 0.7 * saw(v, 2, f * 2 * (1 + d * 0.5)) + 0.6 * saw(v, 3, f * 0.5)
        var fb = f * 0.25
        while (fb > 110.0) fb *= 0.5
        while (fb < 40.0) fb *= 2.0
        v.ph[5] += fb * (1 + 1.5 * m[0]) / sr; if (v.ph[5] >= 1.0) v.ph[5] -= 1.0
        y += sinT(v.ph[5]) * m[1] * 2.2
        m[0] *= kBoomP; m[1] *= kBoomA
        if (m[2] > 1e-4f) { y += rnd(v) * m[2] * 1.2; m[2] *= kHitN }
        return y * 0.3
    }

    /** 🎺 BRASS: 3 Sägen, kurz zu tief angeblasen, Schmettern (Sättigung) mit dem Anschlag, spätes Vibrato. */
    private fun brass(v: Voice, f: Double, spread: Float): Double {
        val m = v.m
        val fb = f * (1 - 0.018 * m[0]); m[0] *= kBlip
        val vd = ((v.n - sr * 0.35) / (sr * 0.5)).coerceIn(0.0, 1.0)
        var vib = 1.0
        if (vd > 0.0) { v.vph += 5.2 / sr; if (v.vph >= 1.0) v.vph -= 1.0; vib = 1.0 + 0.004 * vd * sinT(v.vph) }
        val d = 0.002 + spread * 0.008
        val y = saw(v, 0, fb * vib * (1 - d)) + saw(v, 1, fb * vib * (1 + d)) + 0.8 * saw(v, 2, fb * vib)
        return fastTanh((y * (0.8 + 1.6 * v.vel * v.fenv)).toFloat()) * 0.55
    }

    // LADDER: Hüllkurven-Koeffizienten je Block
    private var ladFa = 0.01f; private var ladFd = 0.999f; private var ladFs = 0.3f
    private var ladAa = 0.01f; private var ladAd = 0.999f; private var ladAs = 0.8f; private var ladRel = 0.999f

    private fun pulse(v: Voice, k: Int, f: Double, pw: Double): Double {
        val dt = f / sr
        v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
        val p = v.ph[k]
        var y = if (p < pw) 1.0 else -1.0
        y += blep(p, dt); y -= blep((p - pw + 1.0) % 1.0, dt)
        return y - (2 * pw - 1)
    }

    /** 🎛 LADDER (Moog-Art): 3 Oszillatoren → Mixer mit Übersteuerung → 24-dB-Ladder (sättigende Stufen) → VCA. */
    private fun ladder(v: Voice, f: Double): Double {
        val lp = Ladder
        val m = v.m
        // Hüllkurven (Filter m0/m1, Lautstärke m2/m3); Release für beide
        if (v.stage == 2) {
            m[0] *= ladRel; m[2] *= ladRel
            if (m[2] < 1e-4f) { v.env = 0f; return 0.0 }
        } else {
            if (m[1] == 0f) { m[0] += ladFa; if (m[0] >= 1f) { m[0] = 1f; m[1] = 1f } } else m[0] = ladFs + (m[0] - ladFs) * ladFd
            if (m[3] == 0f) { m[2] += ladAa; if (m[2] >= 1f) { m[2] = 1f; m[3] = 1f } } else m[2] = ladAs + (m[2] - ladAs) * ladAd
        }
        // Oszillatoren
        var mix = 0.0
        for (k in 0 until 3) {
            val lv = lp.oscLevel[k]
            if (lv < 0.01f) { continue }
            var fk = f * (1 shl lp.oscRange[k].coerceIn(0, 3)) * 0.5
            if (k == 1) fk *= 1.0 + lp.detune2 * 0.0293
            if (k == 2) fk *= 1.0 - lp.detune3 * 0.0293
            val o = when (lp.oscWave[k]) {
                0 -> { val dt = fk / sr; v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0; val p = v.ph[k]; if (p < 0.5) 4 * p - 1 else 3 - 4 * p }
                1 -> saw(v, k, fk)
                2 -> pulse(v, k, fk, 0.5)
                3 -> pulse(v, k, fk, 0.28)
                else -> pulse(v, k, fk, 0.1)
            }
            mix += o * lv
        }
        if (lp.noise > 0.01f) mix += rnd(v) * lp.noise
        // Mixer-Übersteuerung (der „fette“ Trick)
        val x = fastTanh((mix * (0.35 + lp.drive * 1.6)).toFloat())
        // Cutoff in Steuer-Rate: 20 Hz … 20 kHz, Kontur bis +5 Oktaven, Tastatur-Verfolgung
        if ((v.n and 7L) == 1L || v.c[0] == 0f) {
            val oct = lp.cutoff * 9.5 + lp.contour * 5.0 * m[0]
            val kt = Math.pow(f / 261.6, lp.keyTrack.toDouble())
            val fc = (30.0 * Math.pow(2.0, oct) * kt).coerceIn(20.0, sr * 0.45)
            v.c[0] = (1 - kotlin.math.exp(-2 * PI * fc / sr)).toFloat()
        }
        val g = v.c[0]
        val k = lp.emphasis * 4.15f
        val u = fastTanh(x - k * m[7])
        val t4 = fastTanh(m[4]); val t5 = fastTanh(m[5]); val t6 = fastTanh(m[6])
        m[4] += g * (u - t4)
        m[5] += g * (t4 - t5)
        m[6] += g * (t5 - t6)
        m[7] += g * (t6 - fastTanh(m[7]))
        val y = m[7] * (1f + lp.emphasis * 0.7f)
        return (y * m[2] * 4.2f).toDouble()
    }

    private val kLoreBell = exp(-1.0 / (0.6 * sr)).toFloat()
    private val kBellIdx = exp(-1.0 / (0.25 * sr)).toFloat()
    private val kBellHi = exp(-1.0 / (0.08 * sr)).toFloat()

    /** 🌌 LORE-PAD: zwei schwebende Sägen + Oktav-Sinus, dazu ein gläserner FM-Anschlag, der langsam verklingt. */
    private fun lore(v: Voice, f: Double, spread: Float): Double {
        val m = v.m
        val d = 0.003 + spread * 0.008
        var y = 0.45 * (saw(v, 0, f * (1 - d)) + saw(v, 1, f * (1 + d)))
        v.ph[2] += f * 2.0 / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
        y += 0.35 * sinT(v.ph[2])
        if (m[0] > 1e-4f) {
            v.ph[3] += f * 3.5 / sr; if (v.ph[3] >= 1.0) v.ph[3] -= 1.0
            v.ph[4] += f * 2.0 / sr; if (v.ph[4] >= 1.0) v.ph[4] -= 1.0
            y += 0.4 * m[0] * sinT(v.ph[4] + 0.25 * m[0] * sinT(v.ph[3]))
            m[0] *= kLoreBell
        }
        return y * 0.75
    }

    /** 🎤 CHOIR: Säge-Stimmen (Chorus) durch drei Formanten; CUTOFF schiebt den Vokal von „Uuh“ nach „Aah“. */
    private fun choir(v: Voice, f: Double, spread: Float, cut: Float): Double {
        val m = v.m
        val d = 0.004 + spread * 0.01
        var src = 0.0
        for (k in 0 until 3) src += saw(v, k, f * (1 + (k - 1) * d))
        src /= 3.0
        // Formant-Koeffizienten in Steuer-Rate (alle 16 Proben)
        if ((v.n and 15L) == 1L || v.c[0] == 0f) {
            val a = cut.coerceIn(0f, 1f)
            val f1 = 320.0 + 420.0 * a; val f2 = 800.0 + 300.0 * a; val f3 = 2400.0 + 100.0 * a
            v.c[0] = (2 * sin(PI * f1 / sr)).toFloat(); v.c[1] = (2 * sin(PI * f2 / sr)).toFloat(); v.c[2] = (2 * sin(PI * f3 / sr)).toFloat()
        }
        val x = src.toFloat()
        var out = 0f
        for (k in 0 until 3) {
            val g = v.c[k]; val q = if (k == 0) 0.12f else 0.1f
            m[2 * k] += g * m[2 * k + 1]
            val hp = x - m[2 * k] - q * m[2 * k + 1]
            m[2 * k + 1] += g * hp
            out += m[2 * k + 1] * (if (k == 0) 1f else if (k == 1) 0.75f else 0.35f)
        }
        // Atem-Rauschen dazu
        out += rnd(v) * 0.02f
        return (out * 0.45f).toDouble()
    }

    /** 🔔 M1-BELL: FM 1 : 3,5 mit schnell fallendem Index + heller 2,76-Teilton beim Anschlag. */
    private fun m1Bell(v: Voice, f: Double): Double {
        val m = v.m
        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
        v.ph[1] += f * 3.5 / sr; if (v.ph[1] >= 1.0) v.ph[1] -= 1.0
        v.ph[2] += f * 2.76 / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
        val y = sinT(v.ph[0] + (0.15 + 0.9 * m[0]) * sinT(v.ph[1]) * TWO_PI_INV * 6) + 0.35f * m[1] * sinT(v.ph[2])
        m[0] *= kBellIdx; m[1] *= kBellHi
        return y * 0.85
    }

    /** 🎻 STRINGS: Ensemble aus 5 verstimmten Sägen + Bass-Oktave, kurzes Bogen-Rauschen beim Anstrich. */
    private fun strings(v: Voice, f: Double, spread: Float): Double {
        val d = 0.003 + spread * 0.01
        var y = 0.0
        for (k in 0 until 5) y += saw(v, k, f * (1 + det[k] * 9 * d))
        y += 0.5 * saw(v, 5, f * 0.5 * (1 + d * 0.3))
        val bow = sr / 8
        if (v.n < bow) y += rnd(v) * 0.15 * (1f - v.n.toFloat() / bow)
        return y * 0.25
    }

    /** 🎹 ORGAN-BASS: Zugriegel (1', 2', 3', 4' Obertöne), holziger 1:1-FM-Anschlag, Klick, Sub, aufgeräumter Tiefmitten-Bereich. */
    private fun organBass(v: Voice, f: Double): Double {
        val ob = OrganBass
        val m = v.m
        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
        val p = v.ph[0]
        val b = ob.bars
        var y = sinT(p) + sinT(p * 2) * b * 0.7f + sinT(p * 3) * b * b * 0.45f + sinT(p * 4) * b * b * b * 0.3f
        y *= 0.55f
        // Holz: 1:1-FM, Index fällt in ~40 ms
        y += sinT(p + ob.wood * 2.5 * m[0] * sinT(p) / (2 * PI)) * ob.wood * 0.45f
        m[0] *= kWood
        // Klick (Anschlag)
        if (m[1] > 1e-3f) {
            v.ph[4] += 2600.0 / sr; if (v.ph[4] >= 1.0) v.ph[4] -= 1.0
            y += (sinT(v.ph[4]) * 0.6f + rnd(v) * 0.4f) * m[1] * ob.click
            m[1] *= kClick
        }
        y += sinT(p) * ob.sub * 0.5f
        // aufräumen: Tiefpass (Ton) + Hochpass 35 Hz
        if ((v.n and 7L) == 1L) v.c[0] = (1 - exp(-2 * PI * (600.0 + ob.tone * 4000.0) / sr)).toFloat()
        m[2] += v.c[0] * (y - m[2])
        val hpA = (2 * PI * 35.0 / sr).toFloat()
        m[3] += hpA * (m[2] - m[3])
        return ((m[2] - m[3]) * 1.6f).toDouble()
    }

    /** One-Finger-Akkord des Vibe-Synths: Einzelnote → gewählter Club-Akkord; Akkord der Folge → mit passender Erweiterung. */
    private fun vibeChord(iv: IntArray): IntArray {
        if (iv.size <= 1) return Vibe.CHORDS[Vibe.chord.coerceIn(0, Vibe.CHORDS.size - 1)]
        val minor = iv.contains(3)
        val sev = if (minor) 10 else 11
        return when (Vibe.chord) {
            1, 4 -> intArrayOf(0, if (minor) 3 else 4, 7, sev)
            2, 5 -> intArrayOf(0, if (minor) 3 else 4, 7, sev, 14)
            3 -> intArrayOf(0, 5, 7, 12)
            6 -> intArrayOf(0, if (minor) 3 else 4, sev, 14, 17)
            else -> iv
        }
    }

    /** 🏠 VIBE: zwei Drift-Sägen → Diode-Ladder (Squelch im Acid-Modus), Sub unberührt dazu. */
    private fun vibe(v: Voice, f: Double, acid: Float): Double {
        val m = v.m
        val dr = Vibe.drift
        for (k in 0 until 2) {
            if (m[2 + k] <= 0f) { m[4 + k] = rnd(v); m[2 + k] = sr * (0.5f + 0.3f * rnd(v)) }
            m[2 + k]--
            m[k] += (m[4 + k] - m[k]) * 0.00008f
        }
        val d1 = 1.0 + dr * 0.009 * m[0]
        val d2 = (1.0 + 0.0025 + dr * 0.004) * (1.0 + dr * 0.009 * m[1])
        val s1 = saw(v, 0, f * d1)
        val s2 = saw(v, 1, f * d2)
        val osc = ((s1 + s2 * (1f - acid * 0.8f)) * 0.55).toFloat()
        val st = t
        if ((v.n and 7L) == 1L) {
            val oct = st.cutNow() * 7.2f + st.env * (4f + acid * 3.5f) * v.fenv * (0.6f + 0.4f * v.vel) + st.cutMod * 2.5f
            val fc = min(0.45 * sr, 40.0 * 2.0.pow(oct.toDouble()))
            v.c[0] = (1 - exp(-2 * PI * fc / sr)).toFloat()
        }
        val g = v.c[0]
        val k = st.res * (3.4f + acid * 0.9f)
        // Diode-Ladder: leicht asymmetrische Stufen, Rückkopplung vom Ausgang
        val u = fastTanh(osc * 1.4f - k * m[9])
        val t6 = fastTanh(m[6]); val t7 = fastTanh(m[7]); val t8 = fastTanh(m[8])
        m[6] += g * (fastTanh(u + 0.08f * u * u) - t6)
        m[7] += g * (t6 - t7)
        m[8] += g * (t7 - t8)
        m[9] += g * (t8 - fastTanh(m[9]))
        var y = m[9] * (1f + k * 0.28f)
        if (Vibe.sub > 0.01f) {
            v.ph[2] += f / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
            y += sinT(v.ph[2]) * Vibe.sub * 0.6f
        }
        return y.toDouble()
    }

    private fun saw(v: Voice, k: Int, f: Double): Double {
        val dt = f / sr
        v.ph[k] += dt; if (v.ph[k] >= 1.0) v.ph[k] -= 1.0
        return 2.0 * v.ph[k] - 1.0 - blep(v.ph[k], dt)
    }

    fun render(out: FloatArray, n: Int, w: Long) {
        val evs: List<Ev>
        synchronized(lock) {
            // nur wenn ein Ereignis in diesem Block fällig ist, Listen bauen (sonst jeden Block Müll für den GC)
            var due = false
            for (qi in 0 until queue.size) if (queue[qi].frame < w + n) { due = true; break }
            if (!due) evs = emptyList() else {
                evs = queue.filter { it.frame < w + n }.sortedBy { it.frame }
                queue.removeAll(evs.toSet())
            }
        }
        // Leerlauf: keine Stimme klingt, nichts kommt an → nichts zu rechnen
        if (evs.isEmpty() && voices.none { it.active }) { tapeLp = 0f; if (t.wave == 25) BassLab.kick = 0f; return }
        val st = t
        val wave = st.wave
        // Regler einmal je Block lesen (statt je Probe und Stimme)
        val cutN = st.cutNow(); val envA = st.env; val cutM = st.cutMod; val resA = st.res
        val atk = (1.0 / ((0.002 + st.atk * st.atk * 2.5) * sr)).toFloat()
        val relC = if (wave == Ladder.WAVE) 1f else exp(-1.0 / ((0.02 + st.rel * st.rel * 3.0) * sr)).toFloat()
        if (wave == Ladder.WAVE) {
            val lp = Ladder
            ladFa = (1.0 / (lp.secs(lp.fAtk) * sr)).toFloat(); ladFd = exp(-1.0 / (lp.secs(lp.fDec) * sr)).toFloat(); ladFs = lp.fSus
            ladAa = (1.0 / (lp.secs(lp.aAtk) * sr)).toFloat(); ladAd = exp(-1.0 / (lp.secs(lp.aDec) * sr)).toFloat(); ladAs = lp.aSus
            ladRel = exp(-1.0 / (lp.secs(lp.release) * sr)).toFloat()
        }
        val decC = exp(-1.0 / ((if (wave == 3) 0.25 else if (wave == 29) 0.3 else if (wave == 35) 0.9 else if (wave == OrganBass.WAVE) 0.03 + OrganBass.decay * OrganBass.decay * 0.9 else 0.6) * sr)).toFloat()
        val sus = when (wave) { 3, 4, 29, 35 -> 0f; 32, 36 -> 1f; 33, 34 -> 0.95f; 30 -> 0.85f; 31 -> 0.9f; 11, 12, 13, 16, 19, 26 -> 1f; 17 -> 0.75f; 25 -> 0.92f; 28 -> if (OrganBass.legato) 0.45f else 0f; else -> 0.8f }
        val fDec = exp(-1.0 / ((if (wave == 3) 0.12 else 0.35) * sr)).toFloat()
        val q = 1f - resA * 0.92f
        val vol = st.volNow() * 0.32f
        val spread = st.detune
        val chip = InstState.synIsChip(wave)
        val noFilter = wave == 4 || wave == 10 || wave in 11..13 || wave == 16 || wave == 17 || wave == 19 || wave == 25 || wave == 26 || wave == 27 || wave == 28 || wave == 32 || wave == 34 || wave == 35 || wave == 36
        // Bass-Labor
        val bl = BassLab
        val kick0 = if (wave == 25) bl.kick else 0f
        if (wave == 25 && bl.kick > 0.001f) bl.kick *= exp(-n / (0.25 * sr)).toFloat() else if (wave == 25) bl.kick = 0f
        val mVisc = bl.visc
        val amtMid = ((mVisc - 0.25f) / 0.35f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        val amtHigh = ((mVisc - 0.62f) / 0.33f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        val ladK = bl.craterRes * 4.3f
        val juice = bl.juice
        val ksTight = 0.1 + bl.tight * bl.tight * 1.6
        // Vibe: Pump im Schlag-Raster, Modus
        val vAcid = Vibe.mode
        val pumpOn = wave == Vibe.WAVE && Vibe.pumpRate > 0 && Vibe.pump > 0.01f
        var pPh = 0.0; var pInc = 0.0
        if (pumpOn) {
            val div = if (Vibe.pumpRate == 2) 2.0 else 1.0
            pPh = Engine.beatPos(w) * div
            pInc = div / Engine.seqBeatLen().coerceAtLeast(1.0)
        }
        val vDrive = Vibe.drive
        // Chip-Arpeggio: 1/32 im Master-Tempo (ohne Tempo: 120 BPM)
        val bpmNow = Engine.currentBpm().let { if (it in 40f..300f) it else 120f }
        val arpTick = max(1L, (sr * 60.0 / bpmNow / 8.0).toLong())
        // NES-Puls: Tastgrad 12,5 / 25 / 50 % · ab 75 % langsame PWM-Fahrt
        val duty = when { spread < 0.25f -> 0.125; spread < 0.5f -> 0.25; spread < 0.75f -> 0.5; else -> -1.0 }
        val pwmRate = 0.3 + spread * 3.0
        // Gleiten (MONO)
        val glc = if (st.mono && st.glide > 0.01f) (1.0 - exp(-1.0 / ((0.004 + st.glide * st.glide * 0.4) * sr))) else 1.0
        // Orgel-Register über CUTOFF: Jazz → Gospel → volles Werk
        var orgNorm = 1f
        if (wave == 13) {
            val r = cutN.coerceIn(0f, 1f)
            var sum = 0f
            for (k in 0 until 9) {
                orgW[k] = if (r < 0.5f) ORG_JAZZ[k] + (ORG_GOSPEL[k] - ORG_JAZZ[k]) * r * 2f else ORG_GOSPEL[k] + (ORG_FULL[k] - ORG_GOSPEL[k]) * (r - 0.5f) * 2f
                sum += orgW[k]
            }
            orgNorm = 1f / (sum * 0.55f + 0.4f)
        }
        val lesRate = 0.7 + spread * 6.0
        val pianoHd = exp(-1.0 / (0.006 * sr)).toFloat()
        val clickHd = exp(-1.0 / (0.002 * sr)).toFloat()
        val ksT = 0.3 + spread * spread * 7.0
        val wobRate = bpmNow / 60.0 * WOB_MULT[(spread * 5.999f).toInt().coerceIn(0, 5)]
        val fmRatio = if (spread < 0.33f) 1.0 else if (spread < 0.66f) 2.0 else 3.0
        val wt = if (InstState.synIsWt(wave)) Wavetables.ready(wave - 20) else null
        val chipOpen = chip && wave != 8 && cutN > 0.97f
        var ei = 0
        var kickI = kick0
        val kickK = exp(-1.0 / (0.25 * sr)).toFloat()
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { apply(evs[ei]); ei++ }
            val kickNow = kickI; kickI *= kickK
            var lesV = 0f
            var lesA = 1f
            if (wave == 13) {
                leslie += lesRate / sr; if (leslie >= 1.0) leslie -= 1.0
                lesV = sinT(leslie)
                lesA = 1f - 0.22f * spread * (0.5f + 0.5f * sinT(leslie + 0.25))
            }
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
                    if (k != v.arpIdx) { v.arpIdx = k; v.freq = hz(v.arpBase + ai[k]); v.tgt = v.freq }
                }
                if (v.freq != v.tgt) {
                    v.freq += (v.tgt - v.freq) * glc
                    if (abs(v.tgt - v.freq) < 0.005) v.freq = v.tgt
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
                var wobLfo = 0f
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
                        val fbk = v.nz.toDouble() * (0.2 + resA * 0.9)
                        val mod = kotlin.math.sin(2 * PI * v.mph + fbk)
                        v.nz = mod.toFloat()
                        val idx = 0.4 + (envA * 4.0) * v.fenv + cutN * 1.2
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
                            val tap = if (resA > 0.5f) 6 else 1
                            val bit = (v.lfsr xor (v.lfsr shr tap)) and 1
                            v.lfsr = (v.lfsr shr 1) or (bit shl 14)
                            v.nz = if (v.lfsr and 1 == 0) 0.6f else -0.6f
                        }
                        o = v.nz.toDouble()
                    }
                    11, 32 -> o = piano(v, pianoHd)
                    33 -> o = lore(v, f, spread)
                    34 -> o = choir(v, f, spread, cutN)
                    35 -> o = m1Bell(v, f)
                    36 -> o = ladder(v, f)
                    12 -> o = ePiano(v, f, cutN, spread)
                    13 -> o = organ(v, f, spread, lesV, lesA, orgNorm, envA, clickHd)
                    14 -> {
                        // Sync-Lead: Slave-Säge an der Master-Phase neu gestartet, Verhältnis über VERSTIMMUNG + Filter-Hüllkurve
                        val dt0 = f / sr
                        val ratio = 1.5 + spread * 2.5 + envA * 4.0 * v.fenv
                        val dt1 = dt0 * ratio
                        v.ph[0] += dt0
                        if (v.ph[0] >= 1.0) { v.ph[0] -= 1.0; v.ph[1] = v.ph[0] * ratio }
                        else { v.ph[1] += dt1; if (v.ph[1] >= 1.0) v.ph[1] -= kotlin.math.floor(v.ph[1]) }
                        o = (2.0 * v.ph[1] - 1.0 - blep(v.ph[1], dt1)) * 0.6 + (2.0 * v.ph[0] - 1.0 - blep(v.ph[0], dt0)) * 0.25
                    }
                    15 -> {
                        // Reese: zwei gegeneinander verstimmte Sägen + Sub eine Oktave tiefer
                        val d = 0.0015 + spread * 0.008
                        o = 0.42 * (saw(v, 0, f * (1 - d)) + saw(v, 1, f * (1 + d)))
                        v.ph[2] += f * 0.5 / sr; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
                        o += 0.4 * sinT(v.ph[2])
                    }
                    16 -> {
                        // Sub-Bass: Sinus, VERSTIMMUNG = Sättigung (Obertöne für kleine Lautsprecher)
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        val dr = 1.0 + spread * 3.0
                        o = tanh(sinT(v.ph[0]) * dr) / tanh(dr) * 0.95
                    }
                    17 -> {
                        // FM-Bass: Modulator (1:1, 2:1 oder 3:1 über VERSTIMMUNG) mit Rückkopplung (RESONANZ), Index folgt der Filter-Hüllkurve
                        v.mph += f * fmRatio / sr; if (v.mph >= 1.0) v.mph -= 1.0
                        val mod = sinT(v.mph + v.nz * (0.05 + resA * 0.2))
                        v.nz = mod
                        val idx = 0.3 + envA * 6.0 * v.fenv + cutN * 1.5
                        v.ph[0] += f / sr; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        v.ph[1] += f * 0.5 / sr; if (v.ph[1] >= 1.0) v.ph[1] -= 1.0
                        o = sinT(v.ph[0] + idx * mod * TWO_PI_INV) * 0.72 + sinT(v.ph[1]) * 0.28
                    }
                    18 -> {
                        // Wobble: Säge + Rechteck, Filter-LFO im Songtempo (VERSTIMMUNG = 1/2 … 4 pro Schlag)
                        o = 0.4 * (saw(v, 0, f * 0.997) + saw(v, 1, f * 1.003))
                        val dt = f / sr
                        v.ph[2] += dt; if (v.ph[2] >= 1.0) v.ph[2] -= 1.0
                        var sq = if (v.ph[2] < 0.5) 1.0 else -1.0
                        sq += blep(v.ph[2], dt); sq -= blep((v.ph[2] + 0.5) % 1.0, dt)
                        o += 0.35 * sq
                        v.mph += wobRate / sr; if (v.mph >= 1.0) v.mph -= 1.0
                        wobLfo = 0.5f - 0.5f * sinT(v.mph + 0.25)
                    }
                    19 -> {
                        // Saite (Karplus-Strong): Nachklang über VERSTIMMUNG, Tonhöhe auch beim Gleiten sauber
                        val buf = v.ks
                        if (buf == null) o = 0.0 else {
                            val sz = buf.size
                            val d = (sr / f - 0.5).coerceIn(2.0, (sz - 3).toDouble())
                            var rp = v.kw - d
                            if (rp < 0) rp += sz
                            val i0 = rp.toInt() % sz
                            val i1 = (i0 + 1) % sz
                            val fr = (rp - kotlin.math.floor(rp)).toFloat()
                            val y = buf[i0] + (buf[i1] - buf[i0]) * fr
                            val loss = exp(-1.0 / (f * ksT)).toFloat()
                            val nv = (y + v.nz) * 0.5f * loss
                            v.nz = y
                            buf[v.kw] = nv
                            v.kw = (v.kw + 1) % sz
                            o = nv.toDouble()
                        }
                    }
                    in 20..24 -> {
                        // Wavetable: zwei leicht verstimmte Oszillatoren, Position aus VERSTIMMUNG + Filter-Hüllkurve
                        if (wt == null) o = 0.0 else {
                            val pos = (spread + envA * v.fenv * (1f - spread)).coerceIn(0f, 1f) * (Wavetables.FRAMES - 1)
                            val f0 = pos.toInt().coerceIn(0, Wavetables.FRAMES - 2)
                            val fr = pos - f0
                            val lvl = Wavetables.level(f * 1.003, sr)
                            val ta = wt[f0][lvl]
                            val tb = wt[f0 + 1][lvl]
                            var sum = 0f
                            for (u in 0 until 2) {
                                v.ph[u] += f * (if (u == 0) 0.9975 else 1.0025) / sr
                                if (v.ph[u] >= 1.0) v.ph[u] -= 1.0
                                val x = v.ph[u] * Wavetables.N
                                val ix = x.toInt().coerceIn(0, Wavetables.N - 1)
                                val q = (x - ix).toFloat()
                                val a0 = ta[ix] + (ta[ix + 1] - ta[ix]) * q
                                val b0 = tb[ix] + (tb[ix + 1] - tb[ix]) * q
                                sum += a0 + (b0 - a0) * fr
                            }
                            o = sum * 0.8
                        }
                    }
                    25 -> o = magma(v, f, kickNow, amtMid, amtHigh, ladK)
                    26 -> o = funk(v, f, juice, ksTight)
                    27 -> o = vibe(v, f, vAcid)
                    28 -> o = organBass(v, f)
                    29 -> o = orchHit(v, f, spread)
                    30 -> o = brass(v, f, spread)
                    31 -> o = strings(v, f, spread)
                    1 -> {
                        val dt = f / sr
                        v.ph[0] += dt; if (v.ph[0] >= 1.0) v.ph[0] -= 1.0
                        var sq = if (v.ph[0] < 0.5) 1.0 else -1.0
                        sq += blep(v.ph[0], dt); sq -= blep((v.ph[0] + 0.5) % 1.0, dt)
                        o = sq * 0.8
                    }
                    2 -> {
                        o = 0.0
                        for (k in 0 until 5) o += saw(v, k, f * (1.0 + det[k] * spread * 0.12))
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
                        o = 0.5 * (saw(v, 0, f * (1.0 - spread * 0.006)) + saw(v, 1, f * (1.0 + spread * 0.006)))
                    }
                }
                var s = o.toFloat()
                if (!noFilter && !chipOpen) {
                    // Filter-Koeffizient in Steuer-Rate (alle 8 Proben) – pow/sin je Probe kosteten am meisten
                    if ((v.n and 7L) == 1L || v.fg == 0f) {
                        val oct = if (wave == 18) cutN * 7.5f + envA * 6f * wobLfo + cutM * 2.5f
                                  else cutN * 7.5f + envA * 4.5f * v.fenv + cutM * 2.5f
                        val fc = min(0.42f * sr, 60f * 2f.pow(oct))
                        v.fg = (2.0 * kotlin.math.sin(PI * fc / sr)).toFloat().coerceAtMost(1.1f)
                    }
                    val g = v.fg
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
            if (wave == Vibe.WAVE) {
                if (pumpOn) {
                    val ph = (pPh + pInc * i).let { it - kotlin.math.floor(it) }.toFloat()
                    val rec = (ph / 0.55f).coerceIn(0f, 1f)
                    mix *= 1f - Vibe.pump * 0.9f * (1f - rec) * (1f - rec)
                }
                // Class-A-Konsole + Band: dichte Mitten, seidige Höhen
                val x = mix * vol * (1.2f + vDrive * 3f)
                val y = tanh(x + 0.12f * vDrive * x * x)
                tapeLp += (0.55f - 0.35f * vDrive) * (y - tapeLp)
                out[i] += tapeLp
            } else out[i] += fastTanh(mix * vol * 1.2f)
        }
        if (ei < evs.size) synchronized(lock) { queue.addAll(evs.subList(ei, evs.size)) }
    }
}
