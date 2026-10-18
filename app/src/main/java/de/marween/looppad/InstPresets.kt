package de.marween.looppad

import kotlin.random.Random

/**
 * Preset-Bibliothek für DRUM 909 und ACID 303.
 *
 * - Drum-Beats: genretypische Grundmuster (House, Techno, Breaks, Hip-Hop, DnB …) als Lauflicht-Schritte
 *   (allgemein bekannte Rhythmus-Konventionen, für Marv PAD neu notiert).
 * - Acid-Linien: eigene Basslinien im 303-Stil mit passenden Klangeinstellungen.
 * - Zufalls-Generator nach „Acid Banger“ von David Whiting (https://github.com/vitling/acid-banger),
 *   lizenziert unter Creative Commons Attribution 4.0 (CC BY 4.0) – Algorithmus nach Kotlin übertragen.
 */
object InstPresets {
    // Reihenfolge der Drum-Stimmen: BD SD LT MT HT RS CP CH OH CR RD
    private val V = mapOf("BD" to 0, "SD" to 1, "LT" to 2, "MT" to 3, "HT" to 4, "RS" to 5, "CP" to 6, "CH" to 7, "OH" to 8, "CR" to 9, "RD" to 10)

    class Drum(val genre: String, val name: String, val bpm: Int, val swing: Float, val rows: Map<String, String>)
    class Acid(
        val genre: String, val name: String, val bpm: Int, val line: String,
        val cut: Float, val res: Float, val env: Float, val dec: Float, val acc: Float, val glide: Float, val square: Boolean = false
    )

    // ---------------------------------------------------------------- Drum-Beats ('.' aus, x an, X Accent)
    val DRUMS = listOf(
        Drum("House", "Classic House", 124, 0f, mapOf("BD" to "X...X...X...X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "CH" to "x...x...x...x...")),
        Drum("House", "Deep House", 120, 0.15f, mapOf("BD" to "X...X...X...X...", "RS" to "...x..x....x..x.", "CP" to "....x.......x...", "OH" to "..x...x...x...x.", "CH" to ".x.x.x.x.x.x.x.x")),
        Drum("House", "Acid House", 125, 0f, mapOf("BD" to "X...X...X...X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "CH" to "xx.xxx.xxx.xxx.x")),
        Drum("House", "Disco House", 118, 0.1f, mapOf("BD" to "X...X...X...X...", "SD" to "....X.......X...", "OH" to "..X...X...X...X.", "CH" to "x...x...x...x...", "CP" to "............x...")),
        Drum("House", "Afro House", 122, 0.12f, mapOf("BD" to "X...X...X...X...", "RS" to "..x..x..x...x.x.", "LT" to "......x.......x.", "HT" to "...x.......x....", "CH" to "x.xx.x.xx.x.xx.x", "CP" to "....x.......x...")),
        Drum("House", "Tech House", 126, 0.08f, mapOf("BD" to "X...X...X...X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "CH" to ".x.x...x.x.x...x", "RS" to "..........x..x..")),
        Drum("Techno", "Peak Time", 132, 0f, mapOf("BD" to "X...X...X...X...", "CP" to "....x.......x...", "CH" to "..x...x...x...x.", "RD" to "x.x.x.x.x.x.x.x.", "RS" to "...x......x..x..")),
        Drum("Techno", "Minimal", 126, 0.05f, mapOf("BD" to "X...X...X...X...", "RS" to "......x.......x.", "CH" to "..x...x...x...x.", "CP" to "............x...")),
        Drum("Techno", "Rolling", 132, 0f, mapOf("BD" to "X..xX...X..xX...", "CH" to ".xx..xx..xx..xx.", "CP" to "....x.......x...", "RD" to "x...x...x...x...")),
        Drum("Techno", "Hard Techno", 145, 0f, mapOf("BD" to "X.x.X...X.x.X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "RD" to "x.x.x.x.x.x.x.x.")),
        Drum("Techno", "Industrial / EBM", 128, 0f, mapOf("BD" to "X.x.X.x.X.x.X.x.", "SD" to "....X.......X...", "CH" to ".x.x.x.x.x.x.x.x")),
        Drum("Techno", "Detroit", 128, 0.06f, mapOf("BD" to "X...X...X...X...", "CP" to "....x.......x...", "CH" to "xxxxxxxxxxxxxxxx", "OH" to "......x.......x.", "RS" to "..x.....x.x.....")),
        Drum("Trance & Hard", "Trance", 138, 0f, mapOf("BD" to "X...X...X...X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "CH" to ".x.x.x.x.x.x.x.x")),
        Drum("Trance & Hard", "Hardstyle", 150, 0f, mapOf("BD" to "X...X...X...X...", "CP" to "....X.......X...", "OH" to "..x...x...x...x.", "CR" to "X...............")),
        Drum("Trance & Hard", "Gabber", 180, 0f, mapOf("BD" to "X...X...X...X...", "CH" to "..x...x...x...x.", "SD" to "............x...")),
        Drum("Electro & Bass", "Electro", 125, 0f, mapOf("BD" to "X......x..x.....", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.", "CP" to "....X.......X...")),
        Drum("Electro & Bass", "Miami Bass", 130, 0.05f, mapOf("BD" to "X......x..X.....", "SD" to "....X.......X...", "CH" to "xxxxxxxxxxxxxxxx", "CP" to "....X.......X...")),
        Drum("Electro & Bass", "UK Garage 2-Step", 132, 0.35f, mapOf("BD" to "X......x..X.....", "SD" to "....X.......X...", "CH" to "..x...x...x...x.", "RS" to ".......x.......x", "OH" to "..............x.")),
        Drum("Electro & Bass", "Jersey Club", 140, 0f, mapOf("BD" to "X..X..X...X.X...", "CP" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.")),
        Drum("Electro & Bass", "Footwork", 160, 0f, mapOf("BD" to "X..X..X..X..X...", "CP" to "....x.......x...", "CH" to "..x...x...x...x.", "RS" to ".x.....x.x.....x")),
        Drum("Breaks & DnB", "Big Beat", 128, 0.05f, mapOf("BD" to "X.....X...X.....", "SD" to "....X..x.x..X...", "CH" to "x.x.x.x.x.x.x.x.", "OH" to "..............x.")),
        Drum("Breaks & DnB", "Jungle Break", 165, 0f, mapOf("BD" to "X.X.......XX....", "SD" to "....X..x.x..X..x", "RD" to "x.x.x.x.x.x.x.x.", "CR" to "X...............")),
        Drum("Breaks & DnB", "DnB Two-Step", 174, 0f, mapOf("BD" to "X.........X.....", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.")),
        Drum("Breaks & DnB", "Liquid DnB", 172, 0f, mapOf("BD" to "X.........x.....", "SD" to "....X.......X...", "CH" to "xxXxxxXxxxXxxxXx")),
        Drum("Breaks & DnB", "Halftime / Dubstep", 140, 0f, mapOf("BD" to "X.........x.....", "SD" to "........X.......", "CH" to "x.x.x.x.x.x.x.x.", "OH" to "......x.......x.")),
        Drum("Hip-Hop & Trap", "Boom Bap", 90, 0.4f, mapOf("BD" to "X.....x..X......", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.", "OH" to "..............x.")),
        Drum("Hip-Hop & Trap", "Lazy Hip-Hop", 85, 0.5f, mapOf("BD" to "X..x......X.....", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.")),
        Drum("Hip-Hop & Trap", "Trap", 140, 0f, mapOf("BD" to "X......x...X....", "CP" to "........X.......", "CH" to "x.x.x.x.xxxxx.x.", "OH" to "..............x.")),
        Drum("Hip-Hop & Trap", "Lo-Fi", 80, 0.45f, mapOf("BD" to "X......x..x.....", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.", "RS" to ".......x........")),
        Drum("Hip-Hop & Trap", "Reggaeton / Dembow", 95, 0f, mapOf("BD" to "X...X...X...X...", "SD" to "...x..x....x..x.", "CH" to "x.x.x.x.x.x.x.x.")),
        Drum("Band & World", "Funk", 100, 0.2f, mapOf("BD" to "X.x.......X..x..", "SD" to "....X..x.x..X...", "CH" to "x.x.x.x.x.x.x.x.", "OH" to "..............x.")),
        Drum("Band & World", "Disco", 118, 0f, mapOf("BD" to "X...X...X...X...", "SD" to "....X.......X...", "OH" to "..x...x...x...x.", "CH" to "x...x...x...x...")),
        Drum("Band & World", "Rock", 110, 0f, mapOf("BD" to "X.......X.X.....", "SD" to "....X.......X...", "CH" to "x.x.x.x.x.x.x.x.", "CR" to "X...............")),
        Drum("Band & World", "Synthwave", 110, 0f, mapOf("BD" to "X...X...X...X...", "SD" to "....X.......X...", "CH" to ".x.x.x.x.x.x.x.x", "OH" to "..............x.")),
        Drum("Band & World", "Bossa Nova", 130, 0f, mapOf("RS" to "x..x..x...x..x..", "BD" to "X..xX..xX..xX..x", "CH" to "x.x.x.x.x.x.x.x.")),
        Drum("Fills", "Fill · Toms", 0, 0f, mapOf("BD" to "X...............", "HT" to "........xx......", "MT" to "..........xx....", "LT" to "............xxXX", "CR" to "X...............")),
        Drum("Fills", "Fill · Snare-Roll", 0, 0f, mapOf("BD" to "X.......X.......", "SD" to "........xxxxXXXX", "CR" to "X..............."))
    )

    // ---------------------------------------------------------------- Acid-Linien
    // Notation je Schritt: "-" Pause, Ton (C, C#, D …) mit "+" / "-" für Oktave hoch/runter,
    // "*" = Accent, "~" = Slide zum nächsten Ton, "_" = gehalten (Tie vom vorigen Schritt).
    val ACIDS = listOf(
        Acid("Klassisch", "Squelch", 124, "C* C C+ C - C~ D# C C - G-~ A#- C* C+ C F", 0.3f, 0.82f, 0.7f, 0.35f, 0.7f, 0.25f),
        Acid("Klassisch", "Rubber", 122, "C - C C+* - C C~ C+ - D# - C* F~ G - C", 0.25f, 0.75f, 0.6f, 0.3f, 0.6f, 0.3f),
        Acid("Klassisch", "Night Drive", 126, "C* _ D# C - G- C~ C+ C* - A#- C - D# F~ G", 0.35f, 0.8f, 0.55f, 0.4f, 0.65f, 0.22f),
        Acid("Klassisch", "Rolling Root", 128, "C C C+ C C C D# C C C C+* C C~ D# C C", 0.28f, 0.7f, 0.65f, 0.25f, 0.55f, 0.2f),
        Acid("Klassisch", "Sixteenths", 130, "C* C C C+ C C D# C F* C C C+ C G C A#", 0.32f, 0.85f, 0.75f, 0.22f, 0.7f, 0.2f),
        Acid("Dunkel", "Underground", 128, "C - - C* - C - D#~ D - C - - G-* - C", 0.2f, 0.88f, 0.8f, 0.45f, 0.75f, 0.3f),
        Acid("Dunkel", "Minor Pulse", 126, "C C+* C D# C F C G~ G# G F D# C* - C~ C+", 0.3f, 0.78f, 0.6f, 0.35f, 0.6f, 0.28f),
        Acid("Dunkel", "Phrygian", 125, "C C# C - C* C# C+ C - C# C G- C* - C# C", 0.27f, 0.84f, 0.7f, 0.33f, 0.7f, 0.25f),
        Acid("Dunkel", "Sub Stab", 120, "C-* - - C- - - C* - C-* - - C- - D#~ C -", 0.18f, 0.65f, 0.5f, 0.55f, 0.5f, 0.35f, square = true),
        Acid("Hypnotisch", "Spiral", 132, "C~ D# G C+ G D# C~ D# G C+* G D#~ F G# C+ G#", 0.34f, 0.8f, 0.6f, 0.3f, 0.6f, 0.4f),
        Acid("Hypnotisch", "Octave Jumper", 130, "C C+ C C+* C C+ C C+ C C+ C C+* D# D#+ F F+", 0.3f, 0.86f, 0.7f, 0.2f, 0.7f, 0.15f),
        Acid("Hypnotisch", "Long Glide", 124, "C~ _ D#~ _ G~ _ A#~ _ C+* _ A#~ _ G~ _ D#~ _", 0.36f, 0.75f, 0.5f, 0.6f, 0.5f, 0.6f),
        Acid("Hypnotisch", "Call & Response", 126, "C* C - - D#~ F - - C* C - - G-~ A#- - -", 0.3f, 0.8f, 0.65f, 0.35f, 0.65f, 0.3f),
        Acid("Funky", "Funk Acid", 118, "C - C+ - A#- C - D#* - F - D# C - G-~ C", 0.33f, 0.7f, 0.55f, 0.4f, 0.6f, 0.25f),
        Acid("Funky", "Bounce", 122, "C* - C C+ - C D#~ F - C* - C A#- - C+ -", 0.3f, 0.76f, 0.6f, 0.3f, 0.65f, 0.25f),
        Acid("Funky", "Syncopated", 124, "- C* - C - C+ - D# - C* - G- - A#- - C", 0.29f, 0.8f, 0.68f, 0.28f, 0.7f, 0.2f),
        Acid("Hart", "Screamer", 140, "C+* C C+ C C+* C D#+ C C+* C G C F+* C D#+ C", 0.45f, 0.92f, 0.85f, 0.2f, 0.85f, 0.15f),
        Acid("Hart", "Gabber Line", 170, "C* C C C D#* C C C C* C C C G-* C C C", 0.4f, 0.9f, 0.8f, 0.18f, 0.8f, 0.1f),
        Acid("Hart", "Warehouse", 136, "C* C - C C+ - C D#* C - C F - C G~ C+", 0.38f, 0.88f, 0.75f, 0.25f, 0.75f, 0.2f),
        Acid("2 Takte", "Journey (32)", 126, "C* C C+ C - C~ D# C C - G-~ A#- C* C+ C F C* C C+ C - C~ D# C D#* D# C+ D# F~ G - C", 0.3f, 0.82f, 0.68f, 0.35f, 0.7f, 0.25f),
        Acid("2 Takte", "Question & Answer (32)", 124, "C* - C C+ - C D#~ F - C - C A#- - C+ - C* - C C+ - C G~ G# - G - F D#~ C -", 0.3f, 0.78f, 0.6f, 0.32f, 0.65f, 0.3f)
    )

    private val NOTE = mapOf("C" to 0, "C#" to 1, "D" to 2, "D#" to 3, "E" to 4, "F" to 5, "F#" to 6, "G" to 7, "G#" to 8, "A" to 9, "A#" to 10, "H" to 11, "B" to 11)

    fun applyDrum(p: Drum) {
        val st = InstState
        st.drumClear()
        for (r in st.drumVel) java.util.Arrays.fill(r, 1f)
        var len = 16
        for ((k, row) in p.rows) {
            val v = V[k] ?: continue
            len = maxOf(len, ((row.length + 15) / 16) * 16)
            for (i in row.indices) {
                if (i >= InstState.MAX) break
                when (row[i]) {
                    'x' -> st.drumSteps[v][i] = 1
                    'X' -> st.drumSteps[v][i] = 2
                }
            }
        }
        st.drumLen = len.coerceIn(16, InstState.MAX)
        st.drumOn = true
    }

    fun applyAcid(p: Acid) {
        val st = InstState
        st.acidClear()
        java.util.Arrays.fill(st.acidVel, 1f)
        val tokens = p.line.trim().split(Regex("\\s+"))
        var prevSemi = 0
        for ((i, t0) in tokens.withIndex()) {
            if (i >= InstState.MAX) break
            var t = t0
            if (t == "-") continue
            if (t == "_") {
                if (i > 0 && st.acidGate[i - 1]) {
                    st.acidGate[i] = true
                    st.acidTie[i] = true
                    st.acidSemi[i] = prevSemi
                    // Slide gehört ans Ende der gehaltenen Note
                    if (st.acidSlide[i - 1]) { st.acidSlide[i - 1] = false; st.acidSlide[i] = true }
                }
                continue
            }
            val acc = t.contains('*')
            val slide = t.contains('~')
            t = t.replace("*", "").replace("~", "")
            var oct = 0
            while (t.endsWith("+")) { oct += 12; t = t.dropLast(1) }
            while (t.endsWith("-")) { oct -= 12; t = t.dropLast(1) }
            val n = NOTE[t] ?: continue
            val semi = n + oct
            st.acidGate[i] = true
            st.acidSemi[i] = semi
            st.acidAcc[i] = acc
            st.acidSlide[i] = slide
            prevSemi = semi
        }
        st.acidLen = ((tokens.size + 15) / 16 * 16).coerceIn(16, InstState.MAX)
        st.acidCut = p.cut; st.acidRes = p.res; st.acidEnv = p.env; st.acidDec = p.dec
        st.acidAccent = p.acc; st.acidGlide = p.glide; st.acidSquare = p.square
        st.acidOn = true
    }

    // ---------------------------------------------------------------- Zufall nach „Acid Banger“ (David Whiting, CC BY 4.0)

    private val OFFSETS = arrayOf(
        intArrayOf(0, 0, 12, 24, 27),
        intArrayOf(0, 0, 0, 12, 10, 19, 26, 27),
        intArrayOf(0, 1, 7, 10, 12, 13),
        intArrayOf(0),
        intArrayOf(0, 0, 0, 12),
        intArrayOf(0, 0, 12, 14, 15, 19),
        intArrayOf(0, 0, 0, 0, 12, 13, 16, 19, 22, 24, 25),
        intArrayOf(0, 0, 0, 7, 12, 15, 17, 20, 24)
    )

    /** Acid-Linie: Tonvorrat aus Grundton + Intervallsatz, Wahrscheinlichkeit je Zählzeit, 30 % Accent, 10 % Slide. */
    fun randomAcid(rnd: Random = Random) {
        val st = InstState
        st.acidClear()
        java.util.Arrays.fill(st.acidVel, 1f)
        val root = rnd.nextInt(15) - 9  // relativ zu C2 (Original: MIDI 16…30)
        val offs = OFFSETS[rnd.nextInt(OFFSETS.size)]
        for (i in 0 until 16) {
            val chance = if (i % 4 == 0) 0.6 else if (i % 3 == 0) 0.5 else if (i % 2 == 0) 0.3 else 0.1
            if (rnd.nextDouble() < chance) {
                var s = root + offs[rnd.nextInt(offs.size)]
                while (s > 24) s -= 12
                while (s < -12) s += 12
                st.acidGate[i] = true
                st.acidSemi[i] = s
                st.acidAcc[i] = rnd.nextDouble() < 0.3
                st.acidSlide[i] = rnd.nextDouble() < 0.1
            }
        }
        st.acidLen = 16
        st.acidOn = true
    }

    /** Drum-Pattern: Kick (Four-on-the-floor/Electro), Snare (Backbeat/Skip), Hats (Offbeat/Closed) mit Lautstärken. */
    fun randomDrums(rnd: Random = Random) {
        val st = InstState
        st.drumClear()
        for (r in st.drumVel) java.util.Arrays.fill(r, 1f)
        fun hit(v: Int, i: Int, vel: Double) {
            if (vel <= 0.02) return
            st.drumSteps[v][i] = if (vel >= 0.85) 2 else 1
            st.drumVel[v][i] = vel.toFloat().coerceIn(0.1f, 1f)
        }
        val kick = if (rnd.nextBoolean()) "electro" else "fourfloor"
        val hats = listOf("offbeats", "closed", "offbeats")[rnd.nextInt(3)]
        val snare = listOf("backbeat", "skip", "backbeat")[rnd.nextInt(3)]
        for (i in 0 until 16) {
            if (kick == "fourfloor") {
                if (i % 4 == 0) hit(0, i, 0.9) else if (i % 2 == 0 && rnd.nextDouble() < 0.1) hit(0, i, 0.6)
            } else {
                if (i == 0) hit(0, i, 1.0)
                else if (i % 2 == 0 && i % 8 != 4 && rnd.nextDouble() < 0.5) hit(0, i, 0.3 + rnd.nextDouble() * 0.6)
                else if (rnd.nextDouble() < 0.05) hit(0, i, 0.3 + rnd.nextDouble() * 0.6)
            }
            if (snare == "backbeat") {
                if (i % 8 == 4) hit(6, i, 1.0)
            } else {
                if (i % 8 == 3 || i % 8 == 6) hit(1, i, 0.6 + rnd.nextDouble() * 0.4)
                else if (i % 2 == 0 && rnd.nextDouble() < 0.2) hit(1, i, 0.4 + rnd.nextDouble() * 0.2)
                else if (rnd.nextDouble() < 0.1) hit(1, i, 0.2 + rnd.nextDouble() * 0.2)
            }
            if (hats == "offbeats") {
                if (i % 4 == 2) hit(8, i, 0.6)
                else if (rnd.nextDouble() < 0.3) { if (rnd.nextBoolean()) hit(7, i, 0.3 + rnd.nextDouble() * 0.3) else hit(8, i, 0.3 + rnd.nextDouble() * 0.2) }
            } else {
                if (i % 2 == 0) hit(7, i, 0.6) else if (rnd.nextDouble() < 0.5) hit(7, i, 0.25 + rnd.nextDouble() * 0.3)
            }
        }
        st.drumLen = 16
        st.drumOn = true
    }

    // ---------------------------------------------------------------- SYNTH (eigene Presets für Marv PAD)

    /** Note: Schritt, Halbton (0 = C4), Länge in Schritten. */
    class SynNote(val step: Int, val semi: Int, val len: Int, val vel: Float = 1f)
    class Synth(
        val genre: String, val name: String, val bpm: Int, val wave: Int, val chord: Int, val oct: Int,
        val cut: Float, val res: Float, val env: Float, val atk: Float, val rel: Float, val det: Float,
        val bars: Int, val notes: List<SynNote>
    )

    private fun n(vararg t: Int): List<SynNote> = (t.indices step 3).map { SynNote(t[it], t[it + 1], t[it + 2]) }

    val SYNTHS = listOf(
        Synth("Flächen", "Moll-Flächen (Am · Dm · Em · Am)", 120, 2, 2, 0, 0.55f, 0.2f, 0.25f, 0.45f, 0.6f, 0.45f, 4,
            n(0, -3, 16, 16, 2, 16, 32, 4, 16, 48, -3, 16)),
        Synth("Flächen", "Ambient Dur (C · F · Am · G)", 90, 2, 1, 0, 0.45f, 0.15f, 0.2f, 0.7f, 0.85f, 0.6f, 4,
            n(0, 0, 16, 16, 5, 16, 32, -3, 16, 48, -5, 16)),
        Synth("Stabs", "House-Stabs Moll7", 124, 0, 5, 0, 0.42f, 0.35f, 0.55f, 0.0f, 0.18f, 0.3f, 1,
            n(2, 0, 1, 6, 0, 1, 10, 0, 1, 13, -2, 1)),
        Synth("Stabs", "Dub-Chords (Moll)", 118, 0, 2, 0, 0.35f, 0.55f, 0.5f, 0.0f, 0.45f, 0.25f, 1,
            n(2, 0, 1, 7, 0, 1, 10, 0, 2)),
        Synth("Arpeggio", "Pluck-Arp Moll", 128, 3, 0, 0, 0.5f, 0.3f, 0.7f, 0.0f, 0.2f, 0.3f, 1,
            n(0, 0, 1, 1, 3, 1, 2, 7, 1, 3, 12, 1, 4, 7, 1, 5, 3, 1, 6, 0, 1, 7, 3, 1, 8, -2, 1, 9, 3, 1, 10, 7, 1, 11, 10, 1, 12, 7, 1, 13, 3, 1, 14, -2, 1, 15, 3, 1)),
        Synth("Arpeggio", "Trance-Gate Supersaw", 138, 2, 0, 0, 0.6f, 0.25f, 0.4f, 0.0f, 0.12f, 0.6f, 1,
            n(0, 9, 1, 2, 9, 1, 3, 9, 1, 5, 9, 1, 6, 12, 1, 8, 9, 1, 10, 7, 1, 11, 9, 1, 13, 4, 1, 14, 5, 1)),
        Synth("Lead", "Synthwave-Lead", 100, 0, 0, 0, 0.6f, 0.2f, 0.3f, 0.05f, 0.4f, 0.55f, 2,
            n(0, 9, 3, 4, 7, 2, 6, 4, 2, 8, 2, 4, 12, 4, 4, 16, 9, 3, 20, 12, 2, 22, 11, 2, 24, 7, 8)),
        Synth("Lead", "Oktav-Lead (Rechteck)", 126, 1, 6, 0, 0.55f, 0.3f, 0.35f, 0.0f, 0.2f, 0.2f, 1,
            n(0, 0, 2, 3, 3, 1, 6, 5, 2, 10, 7, 1, 12, 10, 2, 14, 7, 2)),
        Synth("Glocken", "FM-Glockenmelodie", 110, 4, 0, 1, 0.5f, 0.1f, 0.3f, 0.0f, 0.6f, 0.2f, 2,
            n(0, 0, 2, 2, 7, 2, 4, 12, 2, 6, 10, 2, 8, 7, 4, 16, 5, 2, 18, 3, 2, 20, 0, 2, 22, -2, 2, 24, 0, 8)),
        Synth("Bass", "Sub-Bass Achtel", 124, 1, 0, -2, 0.3f, 0.15f, 0.3f, 0.0f, 0.1f, 0.1f, 1,
            n(0, 0, 2, 4, 0, 2, 7, 0, 1, 8, 3, 2, 12, 5, 2, 14, -2, 2)),
        Synth("Bass", "Saw-Bass mit Filter", 122, 0, 0, -1, 0.25f, 0.6f, 0.75f, 0.0f, 0.12f, 0.2f, 1,
            n(0, 0, 1, 3, 0, 1, 6, 12, 1, 8, 0, 1, 10, 3, 1, 11, 0, 1, 14, 7, 1))
    )

    fun applySynth(p: Synth) {
        val st = InstState
        st.synClear()
        st.synWave = p.wave; st.synChord = p.chord; st.synOct = p.oct
        st.synCut = p.cut; st.synRes = p.res; st.synEnv = p.env; st.synAtk = p.atk; st.synRel = p.rel; st.synDetune = p.det
        st.synLen = (p.bars * 16).coerceIn(16, InstState.MAX)
        st.synOn = true
        for (nt in p.notes) for (j in 0 until nt.len) {
            val k = nt.step + j
            if (k !in 0 until st.synLen) break
            st.synGate[k] = true
            st.synTie[k] = j > 0
            st.synSemi[k] = nt.semi
            st.synVel[k] = nt.vel
        }
        // kurze Patterns auf 4 Takte spiegeln (falls später verlängert wird)
        for (k in st.synLen until InstState.MAX) {
            val s0 = k % st.synLen
            st.synGate[k] = st.synGate[s0]; st.synTie[k] = st.synTie[s0] && s0 != 0; st.synSemi[k] = st.synSemi[s0]; st.synVel[k] = st.synVel[s0]
        }
    }
}
