package de.marween.looppad

import kotlin.math.abs
import kotlin.random.Random

/**
 * 🎲 GROOVE-GENERATOR: ein kompletter Beat auf Knopfdruck – 909-Drums, BASSLINE, Akkorde (KEYS) und auf Wunsch eine
 * Melodie (SYNTH 1), alles in derselben Tonart und im Stil des Genres. Teile lassen sich einzeln neu würfeln,
 * die Akkordfolge bleibt dabei stehen, damit Bass und Melodie weiter passen.
 */
object GrooveGen {
    class Genre(
        val name: String, val bpm: IntRange, val swing: Float, val minorProb: Float,
        val progsMaj: IntArray, val progsMin: IntArray, val kits: Array<String>, val drums: String,
        val keysWave: Int, val keysRhythm: IntArray, val keysOct: Int,
        val bassWave: Int, val bass: String, val bassOct: Int, val bassGlide: Float,
        val leadWave: Int
    )

    val GENRES = listOf(
        Genre("House", 122..126, 0.1f, 0.4f, intArrayOf(0, 1, 2), intArrayOf(5, 6), arrayOf("909 Klassik", "Disco Live"), "four", 11, intArrayOf(4, 3), 0, 17, "offbeat", -1, 0.1f, 14),
        Genre("Deep House", 118..122, 0.15f, 0.85f, intArrayOf(1, 4), intArrayOf(6, 5, 7), arrayOf("Deep House"), "deep", 12, intArrayOf(4, 0), 0, 16, "offbeat", -1, 0.15f, 12),
        Genre("Techno", 128..134, 0f, 1f, intArrayOf(3), intArrayOf(7, 6), arrayOf("Techno Rumble", "Industrial", "Minimal"), "techno", 23, intArrayOf(4), 0, 0, "rolling", -1, 0f, 23),
        Genre("Trance", 136..140, 0f, 1f, intArrayOf(0), intArrayOf(9, 5), arrayOf("909 Klassik", "Psytrance"), "trance", 2, intArrayOf(0, 2), 0, 0, "trance", -1, 0f, 2),
        Genre("Drum & Bass", 172..176, 0f, 1f, intArrayOf(1), intArrayOf(5, 7, 8), arrayOf("Studio-Drumset", "909 Klassik"), "dnb", 21, intArrayOf(0), 0, 15, "reese", -2, 0.35f, 14),
        Genre("Trap", 138..146, 0f, 1f, intArrayOf(1), intArrayOf(8, 7, 5), arrayOf("Trap 808"), "trap", 4, intArrayOf(0), 1, 16, "808", -2, 0.45f, 4),
        Genre("Boom Bap", 88..94, 0.35f, 0.7f, intArrayOf(4), intArrayOf(5, 7), arrayOf("Boom Bap"), "boombap", 12, intArrayOf(0), 0, 16, "funk", -2, 0.1f, 11),
        Genre("Lo-Fi", 72..80, 0.4f, 0.5f, intArrayOf(4, 1), intArrayOf(6, 5), arrayOf("Lo-Fi Hip-Hop"), "lofi", 12, intArrayOf(0), 0, 16, "long", -2, 0.2f, 11),
        Genre("Afro House", 120..124, 0.1f, 0.8f, intArrayOf(1), intArrayOf(6, 5), arrayOf("Afro House", "Latin Percussion"), "afro", 13, intArrayOf(4, 3), 0, 17, "offbeat", -1, 0.1f, 19),
        Genre("Amapiano", 110..114, 0.15f, 0.6f, intArrayOf(4, 1), intArrayOf(6), arrayOf("Amapiano"), "amapiano", 11, intArrayOf(4), 0, 16, "long", -2, 0.2f, 12),
        Genre("UK Garage", 130..134, 0.3f, 0.7f, intArrayOf(4), intArrayOf(6, 5), arrayOf("UK Garage"), "garage", 13, intArrayOf(4), 0, 17, "funk", -1, 0.1f, 12),
        Genre("Dubstep", 140..140, 0f, 1f, intArrayOf(3), intArrayOf(7, 8), arrayOf("Industrial", "Trap 808"), "half", 21, intArrayOf(0), 0, 22, "wobble", -2, 0.15f, 14),
        Genre("Synthwave", 96..106, 0f, 0.6f, intArrayOf(0, 2), intArrayOf(5, 9), arrayOf("80er Syn-Drums"), "rock", 20, intArrayOf(2, 0), 0, 0, "eighths", -1, 0f, 20),
        Genre("Electro", 124..130, 0.05f, 0.9f, intArrayOf(3), intArrayOf(7, 6), arrayOf("Electro 808"), "electro", 14, intArrayOf(4), 0, 17, "eighths", -1, 0.1f, 14)
    )

    const val P_DRUMS = 1
    const val P_BASS = 2
    const val P_CHORDS = 4
    const val P_MELODY = 8

    // Stand des letzten Grooves (damit „nur Bass neu“ zu den Akkorden passt)
    @Volatile var genre = 0
    @Volatile var root = -1
    @Volatile var minor = true
    @Volatile var prog = 0
    /** Grundton je Takt (Halbtöne um C, −6..+5). */
    val barRoots = IntArray(4)
    @Volatile var made = false
    /** Offene Hi-Hat in den erzeugten Beats (aus = nur geschlossene Hats). */
    @Volatile var openHat = true

    private val MAJ = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MIN = intArrayOf(0, 2, 3, 5, 7, 8, 10)

    /** Beschreibung des aktuellen Grooves für die Anzeige. */
    fun describe(): String {
        if (!made) return "noch nichts erzeugt"
        val g = GENRES[genre]
        val pr = InstState.progressions[prog]
        val chords = (0 until pr.degrees.size).joinToString(" – ") { InstState.progChordName(pr, it, root) }
        return "${g.name} · ${InstState.scaleNoteNames[root]}-${if (minor) "Moll" else "Dur"} · $chords"
    }

    /**
     * Groove erzeugen. [parts] = Bits P_DRUMS/P_BASS/P_CHORDS/P_MELODY; Akkorde neu würfeln heißt auch: neue Tonart/Folge
     * (außer [keepKey]). Liefert das Tempo-Vorschlag-BPM.
     */
    fun generate(g: Int, parts: Int, keepKey: Boolean = false, rnd: Random = Random.Default): Int {
        val ge = GENRES[g.coerceIn(0, GENRES.size - 1)]
        val newHarmony = !made || genre != g || ((parts and P_CHORDS) != 0 && !keepKey)
        genre = g
        if (newHarmony) {
            minor = rnd.nextFloat() < ge.minorProb
            val list = if (minor) ge.progsMin else ge.progsMaj
            prog = list[rnd.nextInt(list.size)]
            minor = InstState.progressions[prog].minor
            if (!keepKey || root < 0) root = intArrayOf(9, 2, 4, 0, 5, 7, 11, 1, 6)[rnd.nextInt(9)] // A D E C F G H C# F#
            computeRoots()
        }
        made = true
        val bpm = ge.bpm.first + rnd.nextInt(ge.bpm.last - ge.bpm.first + 1)
        if (parts and P_DRUMS != 0 || newHarmony) drums(ge, rnd)
        if (parts and P_CHORDS != 0 || newHarmony) chords(ge, rnd)
        if (parts and P_BASS != 0 || newHarmony) bass(ge, rnd)
        if (parts and P_MELODY != 0) melody(ge, rnd)
        Engine.swing = ge.swing
        InstState.scaleRoot = root
        InstState.scaleType = if (minor) 2 else 1
        return bpm
    }

    private fun computeRoots() {
        val pr = InstState.progressions[prog]
        val sc = if (pr.minor) MIN else MAJ
        var prev = Int.MIN_VALUE
        for (i in 0 until 4) {
            val d = pr.degrees[i % pr.degrees.size]
            var r = Math.floorMod(root + sc[d], 12)
            if (r > 6) r -= 12
            if (prev != Int.MIN_VALUE && abs(r - prev) > 7) r += if (r < prev) 12 else -12
            prev = r
            barRoots[i] = r
        }
    }

    // ---------------------------------------------------------------- Drums
    private fun drums(ge: Genre, r: Random) {
        val st = InstState
        st.drumClear()
        for (v in st.drumVel) java.util.Arrays.fill(v, 1f)
        st.drumLen = 32
        val kit = InstPresets.KITS.firstOrNull { it.name == ge.kits[r.nextInt(ge.kits.size)] }
        if (kit != null) InstPresets.applyKit(kit)
        for (v in 0 until InstState.DV) st.drumMute[v] = false
        fun hit(v: Int, k: Int, acc: Boolean = false, vel: Float = 1f) {
            if (k !in 0 until 32) return
            st.drumSteps[v][k] = if (acc) 2 else 1
            st.drumVel[v][k] = vel
        }
        fun both(v: Int, vararg ks: Int, acc: Boolean = false, vel: Float = 1f) { for (k in ks) { hit(v, k, acc, vel); hit(v, k + 16, acc, vel) } }
        fun hats16(v: Int, dens: Float, accOff: Boolean) {
            // Achtel-Offbeats immer, die übrigen 16tel nach Dichte – leiser, damit die Hats nicht alles zudecken
            for (k in 0 until 32) if (k % 4 == 2 || r.nextFloat() < dens) hit(v, k, accOff && k % 4 == 2, if (k % 4 == 2) 0.6f else 0.35f + r.nextFloat() * 0.25f)
        }
        val BD = 0; val SD = 1; val LT = 2; val MT = 3; val HT = 4; val RS = 5; val CP = 6; val CH = 7; val OH = 8; val CR = 9; val RD = 10
        when (ge.drums) {
            "four", "deep", "trance", "afro", "amapiano", "rock" -> {
                if (ge.drums == "rock") { both(BD, 0, 8); if (r.nextBoolean()) both(BD, 10); both(SD, 4, 12, acc = true) }
                else { both(BD, 0, 4, 8, 12); both(CP, 4, 12) }
                when (ge.drums) {
                    "four" -> { both(OH, 2, 6, 10, 14, vel = 0.5f); hats16(CH, 0.25f, false); if (r.nextBoolean()) both(SD, 4, 12, vel = 0.6f) }
                    "deep" -> { both(CH, 2, 6, 10, 14, vel = 0.7f); both(RS, 3, 11, vel = 0.6f); if (r.nextBoolean()) both(RS, 7, vel = 0.5f); hits(r, CH, 0.2f) }
                    "trance" -> { both(OH, 2, 6, 10, 14, vel = 0.6f); hats16(CH, 0.6f, false); both(RD, 2, 6, 10, 14, vel = 0.5f) }
                    "afro" -> {
                        for (k in intArrayOf(0, 3, 6, 10, 12)) both(RS, k, vel = 0.75f)
                        for (k in intArrayOf(2, 7, 11, 14)) both(LT, k, vel = 0.7f)
                        for (k in intArrayOf(5, 13)) both(MT, k, vel = 0.7f)
                        hats16(CH, 0.9f, true)
                    }
                    "amapiano" -> {
                        for (v in listOf(LT, MT)) java.util.Arrays.fill(st.drumSteps[v], 0)
                        for (k in intArrayOf(3, 6, 11, 14, 19, 22, 24, 27, 30)) hit(LT, k, vel = 0.9f)
                        for (k in intArrayOf(7, 23, 29)) hit(MT, k, vel = 0.8f)
                        hats16(CH, 0.8f, false)
                        both(RS, 3, 10, vel = 0.6f)
                    }
                    "rock" -> { both(CH, 0, 2, 4, 6, 8, 10, 12, 14, vel = 0.7f); hit(CR, 0) }
                }
            }
            "techno" -> {
                both(BD, 0, 4, 8, 12, acc = true)
                if (r.nextBoolean()) both(BD, 14, vel = 0.55f)
                both(CP, 4, 12, vel = 0.85f)
                hats16(CH, 0.9f, true)
                both(OH, 2, 6, 10, 14, vel = 0.45f)
                if (r.nextBoolean()) both(RD, 0, 2, 4, 6, 8, 10, 12, 14, vel = 0.45f)
                if (r.nextBoolean()) both(RS, 3, 11, 15, vel = 0.6f)
            }
            "dnb" -> {
                hit(BD, 0, true); hit(BD, 10); hit(BD, 16, true); if (r.nextBoolean()) hit(BD, 26) else { hit(BD, 24); hit(BD, 27) }
                both(SD, 4, 12, acc = true)
                for (k in 0 until 32) if (k % 2 == 0) hit(CH, k, vel = if (k % 4 == 0) 0.8f else 0.6f)
                for (k in intArrayOf(7, 9, 15, 23, 25, 31)) if (r.nextFloat() < 0.5f) hit(SD, k, vel = 0.4f)
                both(RD, 2, 10, vel = 0.5f)
            }
            "trap" -> {
                hit(BD, 0, true); hit(BD, 7); hit(BD, 10); hit(BD, 16, true); hit(BD, 22); hit(BD, 27)
                both(CP, 8, acc = true); both(SD, 8, vel = 0.8f)
                for (k in 0 until 32) {
                    val roll = (k in 12..15 || k in 28..31) && r.nextFloat() < 0.7f
                    if (k % 2 == 0 || roll) hit(CH, k, vel = if (roll) 0.55f + 0.1f * (k % 4) else 0.75f)
                }
                if (r.nextBoolean()) hit(OH, 14, vel = 0.6f)
            }
            "boombap", "lofi" -> {
                hit(BD, 0, true); hit(BD, 7); hit(BD, 10); hit(BD, 16, true); hit(BD, 23); if (r.nextBoolean()) hit(BD, 25)
                both(SD, 4, 12, acc = true)
                for (k in 0 until 32) if (k % 2 == 0) hit(CH, k, vel = if (k % 4 == 0) 0.8f else 0.55f)
                if (ge.drums == "lofi") { for (v in st.drumVel) for (k in 0 until 32) v[k] *= 0.8f; both(RS, 6, vel = 0.5f) }
                else hit(OH, 30, vel = 0.6f)
            }
            "garage" -> {
                hit(BD, 0, true); hit(BD, 10); hit(BD, 16, true); hit(BD, 25)
                both(SD, 4, 12, acc = true)
                for (k in intArrayOf(2, 3, 6, 7, 10, 11, 14, 15)) both(CH, k, vel = if (k % 2 == 0) 0.75f else 0.5f)
                both(RS, 9, vel = 0.6f)
            }
            "half" -> {
                hit(BD, 0, true); hit(BD, 3); hit(BD, 16, true); if (r.nextBoolean()) hit(BD, 19)
                both(SD, 8, acc = true); both(CP, 8)
                for (k in 0 until 32) if (k % 2 == 0) hit(CH, k, vel = 0.65f)
                hit(CR, 0)
            }
            else -> { // electro
                hit(BD, 0, true); hit(BD, 6); hit(BD, 10); hit(BD, 16, true); hit(BD, 22); hit(BD, 26); hit(BD, 28)
                both(SD, 4, 12, acc = true); both(CP, 12)
                hats16(CH, 1f, true)
                both(RS, 7, vel = 0.6f)
            }
        }
        // Fill am Ende von Takt 2
        val fill = r.nextInt(4)
        when (fill) {
            1 -> for (k in intArrayOf(28, 29, 30, 31)) hit(SD, k, vel = 0.5f + 0.12f * (k - 28))
            2 -> { hit(HT, 28, vel = 0.8f); hit(MT, 29, vel = 0.8f); hit(LT, 30, vel = 0.85f); hit(LT, 31, vel = 0.9f) }
            3 -> { hit(OH, 30, vel = 0.7f); hit(SD, 31, vel = 0.7f) }
        }
        if (!openHat) java.util.Arrays.fill(st.drumSteps[OH], 0)
        st.drumOn = true
    }

    private fun hits(r: Random, v: Int, p: Float) {
        for (k in 0 until 32) if (k % 2 == 1 && r.nextFloat() < p) { InstState.drumSteps[v][k] = 1; InstState.drumVel[v][k] = 0.5f }
    }

    // ---------------------------------------------------------------- Akkorde (KEYS)
    private fun chords(ge: Genre, r: Random) {
        val st = InstState
        val old = st.synCur
        st.synCur = InstState.KEYS_TRACK
        val t = st.syn
        t.mono = false
        if (ge.keysWave >= 11) st.waveDefaults(t, ge.keysWave) else t.wave = ge.keysWave
        if (ge.keysWave == 2) { t.cut = 0.6f; t.res = 0.2f; t.env = 0.2f; t.atk = 0.15f; t.rel = 0.5f; t.detune = 0.5f }
        if (ge.keysWave == 4) { t.cut = 0.5f; t.env = 0.3f; t.atk = 0f; t.rel = 0.6f }
        t.oct = ge.keysOct
        t.chord = 0
        t.vol = if (ge.keysRhythm[0] == 0) 0.45f else 0.55f
        val rh = ge.keysRhythm[r.nextInt(ge.keysRhythm.size)]
        st.writeProgression(st.progressions[prog], root, 16, rh)
        t.on = true
        st.synCur = old
    }

    // ---------------------------------------------------------------- Bass (BASSLINE)
    private fun bass(ge: Genre, r: Random) {
        val st = InstState
        val t = st.synTracks[InstState.BASS_TRACK]
        t.clear()
        sound(t, ge.bassWave, true)
        t.oct = ge.bassOct
        t.mono = true
        t.glide = ge.bassGlide
        t.on = true
        t.len = 64
        fun note(at: Int, len: Int, semi: Int, vel: Float = 0.9f) {
            for (k in at until minOf(64, at + maxOf(1, len))) { t.gate[k] = true; t.tie[k] = k != at; t.semi[k] = semi; t.vel[k] = vel }
        }
        val third = if (minor) 3 else 4
        for (b in 0 until 4) {
            val rt = barRoots[b]
            val s0 = b * 16
            when (ge.bass) {
                "offbeat" -> { for (k in intArrayOf(2, 6, 10, 14)) note(s0 + k, 2, if (r.nextFloat() < 0.15f) rt + 12 else rt); if (r.nextFloat() < 0.3f) note(s0 + 15, 1, rt + 7, 0.7f) }
                "rolling" -> for (k in 0 until 16) if (k % 4 != 0) note(s0 + k, 1, if (r.nextFloat() < 0.12f) rt + 12 else rt, if (k % 4 == 2) 0.95f else 0.75f)
                "trance" -> for (k in 0 until 16) if (k % 4 != 0) note(s0 + k, 1, rt, 0.85f)
                "reese" -> { note(s0, 10, rt); note(s0 + 10, 6, if (r.nextBoolean()) rt + 7 else rt - 2) }
                "808" -> { note(s0, 6, rt); note(s0 + 7, 3, rt); note(s0 + 10, 6, if (r.nextFloat() < 0.4f) rt + 12 else rt + (if (r.nextBoolean()) 7 else -2)) }
                "funk" -> { note(s0, 2, rt); note(s0 + 3, 1, rt + 12, 0.7f); note(s0 + 6, 2, rt + 7); note(s0 + 10, 1, rt); note(s0 + 11, 2, rt + third, 0.8f); note(s0 + 14, 2, rt + 7, 0.75f) }
                "long" -> { note(s0, 7, rt); note(s0 + 8, 6, if (r.nextBoolean()) rt else rt + 7) }
                "wobble" -> { note(s0, 8, rt); note(s0 + 8, 4, rt + (if (r.nextBoolean()) 3 else 0)); note(s0 + 12, 4, rt + (if (r.nextBoolean()) -2 else 5)) }
                else -> for (k in 0 until 16 step 2) note(s0 + k, 1, if (k == 14 && r.nextBoolean()) rt + 12 else rt, if (k % 4 == 0) 0.95f else 0.75f) // eighths
            }
        }
    }

    /** Klang setzen: neue Modelle mit ihrer Grundeinstellung, klassische Wellen passend für Bass oder Lead. */
    private fun sound(t: InstState.SynTrack, w: Int, bass: Boolean) {
        if (w >= 11) { InstState.waveDefaults(t, w); return }
        t.wave = w
        if (bass) { t.cut = 0.33f; t.res = 0.4f; t.env = 0.55f; t.atk = 0f; t.rel = 0.12f; t.detune = 0.12f; t.vol = 0.65f }
        else when (w) {
            4 -> { t.cut = 0.5f; t.res = 0.1f; t.env = 0.3f; t.atk = 0f; t.rel = 0.6f; t.detune = 0.2f; t.vol = 0.5f }
            else -> { t.cut = 0.6f; t.res = 0.25f; t.env = 0.4f; t.atk = 0.01f; t.rel = 0.3f; t.detune = 0.4f; t.vol = 0.5f }
        }
    }

    // ---------------------------------------------------------------- Melodie (SYNTH 1)
    private fun melody(ge: Genre, r: Random) {
        val st = InstState
        val t = st.synTracks[0]
        t.clear()
        sound(t, ge.leadWave, false)
        t.oct = 0
        t.mono = ge.leadWave != 4 && ge.leadWave != 11 && ge.leadWave != 12
        t.glide = if (t.mono) 0.08f else 0f
        t.chord = 0
        t.arp = 0
        t.on = true
        t.len = 64
        val sc = if (minor) MIN else MAJ
        // 2-taktiges Motiv, im 2. Durchgang leicht verändert
        val rhythm = BooleanArray(32) { k -> if (k % 4 == 0) r.nextFloat() < 0.6f else if (k % 2 == 0) r.nextFloat() < 0.45f else r.nextFloat() < 0.15f }
        var deg = 7 + r.nextInt(3)
        val motif = IntArray(32)
        for (k in 0 until 32) {
            deg += intArrayOf(-2, -1, -1, 0, 1, 1, 2, 3)[r.nextInt(8)]
            deg = deg.coerceIn(4, 13)
            motif[k] = deg
        }
        for (b in 0 until 4) {
            val rt = barRoots[b]
            for (k in 0 until 16) {
                val mk = (b % 2) * 16 + k
                if (!rhythm[mk] || (b == 3 && k > 11 && r.nextFloat() < 0.5f)) continue
                var d = motif[mk]
                // auf schweren Zählzeiten: Akkordton (Grundton, Terz, Quinte des Taktes)
                var semi = Math.floorDiv(d, 7) * 12 + sc[Math.floorMod(d, 7)] + root
                if (k % 4 == 0) {
                    val tones = intArrayOf(0, if (minor) 3 else 4, 7).map { Math.floorMod(rt + it, 12) }
                    val pc = Math.floorMod(semi, 12)
                    val best = tones.minByOrNull { val dd = Math.floorMod(it - pc, 12); minOf(dd, 12 - dd) }!!
                    var dd = Math.floorMod(best - pc, 12); if (dd > 6) dd -= 12
                    semi += dd
                }
                var len = 1
                while (k + len < 16 && !rhythm[(mk + len) % 32] && len < 4) len++
                val at = b * 16 + k
                for (j in 0 until len) { t.gate[at + j] = true; t.tie[at + j] = j > 0; t.semi[at + j] = semi - 12; t.vel[at + j] = if (k % 4 == 0) 0.95f else 0.75f }
            }
        }
    }
}
