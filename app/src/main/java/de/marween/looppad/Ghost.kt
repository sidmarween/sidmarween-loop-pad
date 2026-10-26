package de.marween.looppad

import kotlin.math.floor

/**
 * Ghost-Producer: Genre-Patterns (Drums, Bass, Synth) laufen als Live-Remix über den aktuellen Track.
 * Tempo und Takt-Eins folgen dem laufenden DJ-Deck, Bass und Synth werden in dessen Tonart transponiert.
 * Die Instrumente sind Klangerzeuger – sie spielen in jedem Tempo, ohne Dehnen.
 */
object Ghost {
    @Volatile var on = false
    /** -1 = automatisch (lautestes laufendes Deck), 0/1 = Deck A/B. */
    @Volatile var follow = -1
    /** Tonart: automatisch vom Deck, sonst [keyManual] (Keys-Zahl, -1 = nicht transponieren). */
    @Volatile var keyAuto = true
    @Volatile var keyManual = -1
    /** Drums-Filter: 0 alles, 1 nur Hi-Hats & Becken, 2 nur Percussion, 3 ohne Bass Drum. */
    @Volatile var drumFilter = 1
    val FILTERS = arrayOf("ALLES", "NUR HATS", "NUR PERC", "OHNE KICK")
    val laneName = arrayOf("–", "–", "–")
    /** Grundton/Moll der geladenen Bass-/Synth-Patterns (für die Transponierung). */
    @Volatile private var acidTonic = 0
    @Volatile private var acidMinor = true
    @Volatile private var synTonic = 0
    @Volatile private var synMinor = true
    /** Zuletzt gefolgtes Deck (für die Anzeige), -1 = keins. */
    @Volatile var followed = -1
        private set
    @Volatile var shiftNow = 0
        private set

    private val HATS = intArrayOf(7, 8, 9, 10)
    private val PERC = intArrayOf(1, 2, 3, 4, 5, 6)

    // ---------------------------------------------------------------- Patterns laden (Ziehen/Antippen)

    fun applyDrum(p: InstPresets.Drum) {
        InstPresets.applyDrum(p)
        val st = InstState
        val keep = BooleanArray(InstState.DV) { v ->
            when (drumFilter) {
                1 -> v in HATS
                2 -> v in PERC
                3 -> v != 0
                else -> true
            }
        }
        for (v in 0 until InstState.DV) if (!keep[v]) java.util.Arrays.fill(st.drumSteps[v], 0)
        st.drumOn = true
        laneName[0] = p.genre + " · " + p.name + if (drumFilter != 0) " (${FILTERS[drumFilter].lowercase()})" else ""
    }

    fun applyAcid(p: InstPresets.Acid) {
        InstPresets.applyAcid(p)
        acidTonic = 0; acidMinor = true
        InstState.acidOn = true
        laneName[1] = p.genre + " · " + p.name
    }

    fun applySynth(p: InstPresets.Synth) {
        InstState.synCur = 0
        InstPresets.applySynth(p)
        val first = p.notes.minByOrNull { it.step }
        synTonic = Math.floorMod(first?.semi ?: 0, 12)
        synMinor = p.chord == 2 || p.name.contains("Moll") || Regex("\\b[A-H][#b]?m\\b").containsMatchIn(p.name)
        laneName[2] = p.genre + " · " + p.name
    }

    // ---------------------------------------------------------------- Groove-Generator (Variation)

    fun varyDrums(rnd: java.util.Random = java.util.Random()) {
        val st = InstState
        val len = st.drumLen
        for (v in 0 until InstState.DV) {
            if ((0 until len).none { st.drumSteps[v][it] > 0 }) continue
            if (v == 0) continue // Kick bleibt stehen
            for (k in 0 until len) {
                val on = st.drumSteps[v][k] > 0
                val off = k % 2 == 1
                if (!on && off && rnd.nextFloat() < (if (v in HATS) 0.18f else 0.08f)) { st.drumSteps[v][k] = 1; st.drumVel[v][k] = 0.45f + rnd.nextFloat() * 0.3f }
                else if (on && k % 4 != 0 && rnd.nextFloat() < 0.12f) st.drumSteps[v][k] = 0
                else if (on) st.drumVel[v][k] = (0.7f + rnd.nextFloat() * 0.3f) * (if (k % 4 == 0) 1f else 0.85f)
            }
        }
        laneName[0] = laneName[0].substringBefore(" · Var") + " · Variation"
    }

    fun varyBass() {
        InstPresets.randomAcid()
        acidTonic = 0; acidMinor = true
        laneName[1] = "🎲 Zufalls-Acid"
    }

    fun varySynth() {
        InstState.synCur = 0
        InstState.synRandom()
        synTonic = 0; synMinor = true
        laneName[2] = "🎲 Zufalls-Melodie (Moll-Pentatonik)"
    }

    // ---------------------------------------------------------------- Mitlaufen (Mischer-Thread, pro Block)

    private fun pickDeck(): Dj.Deck? {
        val f = follow
        if (f in 0..1) return Dj.decks[f].takeIf { it.loaded() && it.bpm > 0f }
        var best: Dj.Deck? = null
        var bl = -1f
        for (d in Dj.decks) {
            if (!d.loaded() || !d.playing || d.bpm <= 0f) continue
            val g = d.fader * (if (d.idx == 0) 1f - Dj.xfade else Dj.xfade).coerceAtLeast(0.05f) + d.level
            if (g > bl) { bl = g; best = d }
        }
        return best
    }

    /** Tempo, Takt-Eins und Tonart vom Deck übernehmen. */
    fun tick(w: Long) {
        if (!on) { followed = -1; return }
        val d = pickDeck()
        var key = if (keyAuto) -1 else keyManual
        if (d != null && d.playing) {
            followed = d.idx
            val bpm = (d.bpm * d.ratio * (1.0 + d.bend)).toFloat()
            if (bpm in 40f..240f) {
                val outBeat = Engine.sampleRate * 60.0 / bpm
                val bi = Dj.beatIndex(d, d.pos) - d.barOffset
                val inBar = bi - floor(bi / 4.0) * 4.0
                Engine.ghostLock(bpm, w - (inBar * outBeat).toLong())
            }
            if (keyAuto) key = d.effKey()
        } else followed = -1
        curKey = key
        // Tonart: Bass/Synth so verschieben, dass ihr Grundton zum Track passt (Moll ↔ Dur über die Parallele)
        if (key >= 0) {
            val sa = shiftTo(acidTonic, acidMinor) ?: 0
            InstState.acidTune = sa
            InstState.synTracks[0].transpose = shiftTo(synTonic, synMinor) ?: 0
            shiftNow = sa
        }
    }

    /** Tonart, nach der gerade transponiert wird (-1 = keine). */
    @Volatile var curKey = -1
        private set

    /** Halbtöne, um die ein Pattern/Loop mit Grundton [tonic] (Moll/Dur) in die aktuelle Tonart muss – null = keine Tonart. */
    fun shiftTo(tonic: Int, pMinor: Boolean): Int? {
        val key = curKey
        if (key < 0) return null
        val root = Keys.root(key)
        val minor = Keys.isMinor(key)
        val target = when {
            minor == pMinor -> root
            minor -> root + 3       // Track Moll, Pattern Dur → Paralleltonart
            else -> root + 9        // Track Dur, Pattern Moll
        }
        var s = Math.floorMod(target - tonic, 12)
        if (s > 6) s -= 12
        return s
    }

    fun stop() {
        on = false
        followed = -1
        InstState.synTracks[0].transpose = 0
        InstState.acidTune = 0
    }

    /** Für die Anzeige: Tonart, nach der gerade transponiert wird. */
    fun keyName(): String {
        val k = if (keyAuto) Dj.decks.getOrNull(followed)?.effKey() ?: -1 else keyManual
        return if (k < 0) "–" else Keys.name(k) + " (" + Keys.camelot(k) + ")"
    }
}
