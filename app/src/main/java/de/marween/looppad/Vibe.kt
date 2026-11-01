package de.marween.looppad

/**
 * 🏠 VIBE-SYNTH (Klang 27): Hybrid-Analog-House-Synth.
 *  - One-Finger Club Chords (Moll7, Moll9, Sus4, Dur7, Dur9, Moll11) wie von Juno/M1 gestimmt
 *  - Zwei Drift-Oszillatoren (gealterte Bauteile: langsames Verstimmen)
 *  - Pump-Filter: tempo-synchrones Ducking (1/4, 1/8) in Filter und Lautstärke – ohne Sidechain-Routing
 *  - Modus STAB ↔ ACID: ab der Mitte monofon, Diode-Ladder mit Squelch, Sub bleibt unangetastet
 *  - Class-A-Konsole + Band-Sättigung am Ausgang
 */
object Vibe {
    const val WAVE = 27
    val CHORD_NAMES = arrayOf("EINZELN", "MOLL 7", "MOLL 9", "SUS 4", "DUR 7", "DUR 9 (M1)", "MOLL 11")
    val CHORDS = arrayOf(
        intArrayOf(0),
        intArrayOf(0, 3, 7, 10),
        intArrayOf(0, 3, 7, 10, 14),
        intArrayOf(0, 5, 7, 12),
        intArrayOf(0, 4, 7, 11),
        intArrayOf(0, 4, 7, 11, 14),
        intArrayOf(0, 3, 10, 14, 17)
    )
    @Volatile var chord = 1
    @Volatile var drift = 0.35f
    /** 0 = aus, 1 = 1/4, 2 = 1/8 */
    @Volatile var pumpRate = 1
    @Volatile var pump = 0.5f
    /** 0 = STAB/Fläche … 1 = ACID (ab 0,5 monofon) */
    @Volatile var mode = 0f
    @Volatile var drive = 0.35f
    @Volatile var sub = 0.3f
    /** Spur, auf der der Vibe-Synth spielt (Standard: SYNTH 2). */
    @Volatile var track = 1
    @Volatile var presetIdx = -1

    fun acid() = mode >= 0.5f

    class Preset(
        val name: String, val chord: Int, val drift: Float, val pumpRate: Int, val pump: Float, val mode: Float, val drive: Float, val sub: Float,
        val cut: Float, val res: Float, val env: Float, val atk: Float, val rel: Float, val oct: Int, val bpm: Int, val pattern: Int
    )
    /** pattern: 0 Offbeat-Stabs, 1 Stabs (1 + 2+ + 4), 2 gehaltene Flächen, 3 Acid-Linie, 4 rollender Sub, 5 Plucks Achtel */
    val PRESETS = listOf(
        Preset("Deep-House-Stab (Moll 7)", 1, 0.35f, 1, 0.55f, 0f, 0.35f, 0f, 0.45f, 0.25f, 0.55f, 0f, 0.18f, 0, 122, 0),
        Preset("M1-Orgel-Akkorde (Dur 9)", 5, 0.25f, 1, 0.4f, 0f, 0.45f, 0f, 0.7f, 0.1f, 0.3f, 0f, 0.15f, 0, 124, 1),
        Preset("Jupiter-Fläche (Moll 9)", 2, 0.6f, 2, 0.45f, 0f, 0.3f, 0f, 0.5f, 0.15f, 0.25f, 0.4f, 0.6f, 0, 120, 2),
        Preset("Acid-Squelch (303-Hybrid)", 0, 0.2f, 0, 0f, 0.85f, 0.6f, 0.35f, 0.3f, 0.85f, 0.75f, 0f, 0.12f, -1, 126, 3),
        Preset("Rollender Sub", 0, 0.15f, 2, 0.35f, 0.65f, 0.4f, 0.8f, 0.22f, 0.3f, 0.4f, 0f, 0.1f, -1, 124, 4),
        Preset("Nordic Pluck (Sus 4)", 3, 0.4f, 2, 0.5f, 0f, 0.3f, 0f, 0.4f, 0.3f, 0.7f, 0f, 0.1f, 0, 120, 5),
        Preset("Garage-Stab (Moll 11)", 6, 0.3f, 1, 0.6f, 0f, 0.4f, 0f, 0.5f, 0.2f, 0.5f, 0f, 0.2f, 0, 130, 1)
    )

    /** Vibe-Synth auf seine Spur legen (Klang + Grundeinstellung), ohne Noten zu ändern. */
    fun attach() {
        val t = InstState.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)]
        if (t.wave != WAVE) {
            t.wave = WAVE
            t.cut = 0.45f; t.res = 0.25f; t.env = 0.55f; t.atk = 0f; t.rel = 0.2f; t.detune = 0.3f
        }
        t.chord = 0
        t.mono = acid()
        t.on = true
    }

    /** Vorlage: Klang + Noten (mit Akkordfolge oder Linie in der Tonart). */
    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        chord = p.chord; drift = p.drift; pumpRate = p.pumpRate; pump = p.pump; mode = p.mode; drive = p.drive; sub = p.sub
        val st = InstState
        val t = st.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)]
        t.wave = WAVE; t.cut = p.cut; t.res = p.res; t.env = p.env; t.atk = p.atk; t.rel = p.rel; t.oct = p.oct; t.chord = 0; t.arp = 0
        t.mono = acid(); t.glide = if (acid()) 0.15f else 0f
        t.on = true
        val old = st.synCur
        st.synCur = track
        val root = if (st.scaleType != 0) st.scaleRoot else if (GrooveGen.made) GrooveGen.root else 9
        val minorProg = st.progressions.indexOfFirst { it.name.startsWith("Deep House") }.coerceAtLeast(0)
        when (p.pattern) {
            0 -> st.writeProgression(st.progressions[minorProg], root, 16, 3)
            1 -> st.writeProgression(st.progressions[minorProg], root, 16, 4)
            2 -> st.writeProgression(st.progressions[minorProg], root, 16, 0)
            5 -> st.writeProgression(st.progressions[minorProg], root, 8, 2)
            else -> {
                // Linie: Acid (16tel mit Oktaven, Akzenten, Slides) oder rollender Sub (Offbeat-16tel)
                t.clear(); t.len = 32
                val r = java.util.Random()
                val rt = Math.floorMod(root, 12).let { if (it > 6) it - 12 else it }
                val notes = intArrayOf(0, 0, 0, 12, 3, 7, 10, -2, 5)
                for (s in 0 until 32) {
                    val hit = if (p.pattern == 3) r.nextFloat() < 0.7f else s % 4 != 0
                    if (!hit) continue
                    t.gate[s] = true
                    t.semi[s] = rt + if (p.pattern == 3) notes[r.nextInt(notes.size)] else (if (r.nextFloat() < 0.1f) 12 else 0)
                    t.vel[s] = if (p.pattern == 3 && r.nextFloat() < 0.3f) 1f else 0.72f
                    if (p.pattern == 3 && s > 0 && t.gate[s - 1] && r.nextFloat() < 0.2f) { t.tie[s] = true; t.semi[s] = t.semi[s - 1] }
                }
                for (s in 32 until InstState.MAX) { t.gate[s] = t.gate[s % 32]; t.tie[s] = t.tie[s % 32]; t.semi[s] = t.semi[s % 32]; t.vel[s] = t.vel[s % 32] }
                st.scaleRoot = Math.floorMod(root, 12); if (st.scaleType == 0) st.scaleType = 2
            }
        }
        st.synCur = old
    }
}
