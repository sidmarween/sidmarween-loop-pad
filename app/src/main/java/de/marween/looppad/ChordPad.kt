package de.marween.looppad

import kotlin.math.abs

/**
 * 🎼 CHORD-PAD: 8 Akkord-Tasten der Tonart (I–VII + I'), schöne Stimmführung, Anschlag wie eine Gitarre (STRUM),
 * Bass läuft automatisch mit (BASSLINE), gespielte Folge lässt sich in die Spuren schreiben.
 */
object ChordPad {
    @Volatile var root = -1           // −1 = aus Tonleiter/Groove/A
    @Volatile var minor = true
    @Volatile var voicing = 1         // 0 Dreiklang, 1 Septakkord, 2 add9, 3 sus, 4 Power
    val VOICINGS = arrayOf("DREIKLANG", "SEPT (7)", "ADD 9", "SUS", "POWER")
    @Volatile var strum = 0.3f        // 0..1 → 0..60 ms
    @Volatile var autoBass = true
    @Volatile var track = InstState.KEYS_TRACK
    /** gerade klingende Akkord-Töne (MIDI) – für den Vocoder */
    val cur = java.util.concurrent.CopyOnWriteArrayList<Int>()
    /** aufgenommene Folge: Stufe je Akkord */
    val recorded = java.util.concurrent.CopyOnWriteArrayList<Int>()
    @Volatile var recording = false

    private val MAJ = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MIN = intArrayOf(0, 2, 3, 5, 7, 8, 10)

    fun rootPc(): Int = if (root >= 0) root else if (InstState.scaleType != 0) InstState.scaleRoot else if (GrooveGen.made) GrooveGen.root else 9
    fun isMinor(): Boolean = if (root >= 0) minor else if (InstState.scaleType == 1 || InstState.scaleType == 6) false else if (InstState.scaleType != 0) true else if (GrooveGen.made) GrooveGen.minor else minor

    /** Akkord-Name der Stufe [d] (0..7). */
    fun name(d: Int): String {
        val sc = if (isMinor()) MIN else MAJ
        val pc = Math.floorMod(rootPc() + sc[d % 7], 12)
        val third = Math.floorMod(sc[(d + 2) % 7] - sc[d % 7], 12)
        val fifth = Math.floorMod(sc[(d + 4) % 7] - sc[d % 7], 12)
        val suf = when { fifth == 6 -> "°"; third == 3 -> "m"; else -> "" } + when (voicing) { 1 -> "7"; 2 -> "add9"; 3 -> "sus"; 4 -> "5"; else -> "" }
        return InstState.scaleNoteNames[pc] + suf
    }

    /** Akkordtöne (MIDI) der Stufe mit Stimmführung nahe am letzten Akkord. */
    fun notesOf(d: Int): IntArray {
        val sc = if (isMinor()) MIN else MAJ
        val base = 48 + rootPc()
        fun deg(k: Int): Int { val o = Math.floorDiv(k, 7); return base + 12 * o + sc[Math.floorMod(k, 7)] }
        val dd = d % 7 + (if (d >= 7) 7 else 0)
        val tones = when (voicing) {
            0 -> intArrayOf(deg(dd), deg(dd + 2), deg(dd + 4))
            1 -> intArrayOf(deg(dd), deg(dd + 2), deg(dd + 4), deg(dd + 6))
            2 -> intArrayOf(deg(dd), deg(dd + 2), deg(dd + 4), deg(dd + 8))
            3 -> intArrayOf(deg(dd), deg(dd + 3), deg(dd + 4))
            else -> intArrayOf(deg(dd), deg(dd + 4), deg(dd) + 12)
        }
        // Stimmführung: ganze Oktaven so verschieben, dass die Mitte nah an der letzten liegt
        val target = lastCenter
        var best = tones; var bd = Int.MAX_VALUE
        for (shift in -12..12 step 12) for (inv in 0 until tones.size) {
            val t = tones.copyOf()
            for (k in 0 until inv) t[k] += 12
            for (k in t.indices) t[k] += shift
            val c = t.sum() / t.size
            val dist = abs(c - target)
            if (dist < bd && t.minOrNull()!! >= 45 && t.maxOrNull()!! <= 84) { bd = dist; best = t }
        }
        lastCenter = best.sum() / best.size
        return best.sortedArray()
    }
    private var lastCenter = 62

    private fun toSemi(midi: Int, t: InstState.SynTrack) = midi - 60 - 12 * t.oct - t.transpose

    /** Akkord anschlagen. */
    fun play(d: Int, vel: Float, tagBase: Int) {
        val t = InstState.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)]
        t.on = true
        val ns = notesOf(d)
        cur.clear(); for (x in ns) cur.add(x)
        val strumFr = (Engine.sampleRate * 0.06 * strum).toLong()
        for ((k, x) in ns.withIndex()) Engine.synTrackLiveAt(track, true, toSemi(x, t), vel * (if (k == 0) 1f else 0.85f), tagBase + k, k * strumFr)
        if (autoBass) {
            val b = InstState.synTracks[InstState.BASS_TRACK]
            b.on = true
            val sc = if (isMinor()) MIN else MAJ
            val root = 36 + rootPc() + sc[d % 7]
            Engine.synTrackLive(InstState.BASS_TRACK, true, toSemi(root, b), vel, tagBase + 50)
        }
        if (recording && recorded.size < 16) recorded.add(d)
    }

    fun release(tagBase: Int) {
        for (k in 0 until 6) Engine.synTrackLive(track, false, 0, 0f, tagBase + k)
        Engine.synTrackLive(InstState.BASS_TRACK, false, 0, 0f, tagBase + 50)
        cur.clear()
    }

    /** Aufgenommene Folge in die Spuren schreiben: je Akkord 1 Takt (KEYS als Akkord, BASSLINE Grundton im Offbeat). */
    fun writeRecorded(): Boolean {
        val seq = recorded.toList()
        if (seq.isEmpty()) return false
        val st = InstState
        val t = st.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)]
        val b = st.synTracks[InstState.BASS_TRACK]
        val bars = seq.size.coerceAtMost(4)
        t.clear(); t.len = bars * 16; t.chord = 0
        b.clear(); b.len = bars * 16; b.mono = true
        val sc = if (isMinor()) MIN else MAJ
        for (i in 0 until bars) {
            val d = seq[i]
            val ns = notesOf(d)
            val rootMidi = ns.first()
            val third = Math.floorMod(sc[(d + 2) % 7] - sc[d % 7], 12)
            val fifth = Math.floorMod(sc[(d + 4) % 7] - sc[d % 7], 12)
            // Akkord-Typ für die Spur: Index in synChordIv + 1
            val chd = when {
                voicing == 4 -> 7
                voicing == 3 -> 4
                fifth == 6 -> 8
                voicing == 1 && third == 3 -> 6
                voicing == 1 -> 5
                third == 3 -> 3
                else -> 2
            }
            val s0 = i * 16
            for (k in s0 until s0 + 16) { t.gate[k] = true; t.tie[k] = k != s0; t.semi[k] = toSemi(rootMidi, t); t.chd[k] = chd; t.vel[k] = 0.85f }
            val bassMidi = 36 + rootPc() + sc[d % 7]
            for (k in intArrayOf(2, 6, 10, 14)) { b.gate[s0 + k] = true; b.semi[s0 + k] = toSemi(bassMidi, b); b.vel[s0 + k] = 0.85f; b.gate[s0 + k + 1] = true; b.tie[s0 + k + 1] = true; b.semi[s0 + k + 1] = b.semi[s0 + k] }
        }
        t.on = true; b.on = true
        st.scaleRoot = rootPc(); st.scaleType = if (isMinor()) 2 else 1
        return true
    }
}
