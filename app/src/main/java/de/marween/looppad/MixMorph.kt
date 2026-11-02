package de.marween.looppad

import android.os.Handler
import android.os.Looper

/**
 * 🎛 PERFORMANCE-SZENEN: zwei Mix-Zustände A und B (Lautstärken, Filter, Pads und Instrumente an/aus)
 * und ein Morph-Regler dazwischen. AUTO-MORPH fährt im Takt von A nach B (oder zurück) über N Takte –
 * gestartet auf der nächsten Eins. Schalter (an/aus, stumm) springen in der Mitte des Wegs.
 */
object MixMorph {
    class Snap(val f: FloatArray, val b: BooleanArray)

    private class FP(val name: String, val get: () -> Float, val set: (Float) -> Unit, val avail: () -> Boolean = { true })
    private class BP(val name: String, val get: () -> Boolean, val set: (Boolean) -> Unit, val avail: () -> Boolean = { true })

    private val FPS: List<FP> = buildList {
        for (p in 0 until 12) add(FP("PAD${p + 1}", { Engine.padGain(p) }, { Engine.setPadGain(p, it) }, { Engine.hasLoop(p) }))
        add(FP("ACID", { InstState.acidVol }, { InstState.acidVol = it }))
        add(FP("ACID-CUT", { InstState.acidCut }, { InstState.acidCut = it }))
        add(FP("DRUM", { InstState.drumVol }, { InstState.drumVol = it }))
        for (t in 0 until InstState.SYN_TRACKS) {
            add(FP(InstState.synName(t), { InstState.synTracks[t].vol }, { InstState.synTracks[t].vol = it }))
            add(FP(InstState.synName(t) + "-CUT", { InstState.synTracks[t].cut }, { InstState.synTracks[t].cut = it }))
        }
        add(FP("EUKLID", { Euclid.level }, { Euclid.level = it }))
        add(FP("ATMOS", { Atmos.level }, { Atmos.level = it }))
        add(FP("HARMONIZER", { Harmonizer.level }, { Harmonizer.level = it }))
        add(FP("WOLF", { Wolf.level }, { Wolf.level = it }))
        add(FP("ARCHITECT", { Architect.vol }, { Architect.vol = it }))
        add(FP("ORBITS", { Orbits.vol }, { Orbits.vol = it }))
        add(FP("TOUCH-LAB", { TouchLab.vol }, { TouchLab.vol = it }))
        add(FP("FX-GEN", { FxGen.vol }, { FxGen.vol = it }))
        add(FP("VOCODER", { Vocoder.level }, { Vocoder.level = it }))
        add(FP("VOCAL-CHOP", { VocalChop.vol }, { VocalChop.vol = it }))
    }

    /** Instrument-Codes wie bei den Instrument-Pads (0 ACID, 1 DRUM, 2.. Synth-Spuren, 10 EUKLID …). */
    private val CODES = (0 until InstState.INSTS).toList() + listOf(10, 11, 13, 14, 15, 19)

    private val BPS: List<BP> = buildList {
        for (p in 0 until 12) add(BP("PAD${p + 1}", { !Engine.isMuted(p) }, { v -> if (Engine.isMuted(p) == v) Engine.toggleMute(p) }, { Engine.hasLoop(p) }))
        for (c in CODES) add(BP("I$c", { Engine.instCodeOn(c) }, { v -> if (Engine.instCodeOn(c) != v) Engine.setInstCodeOn(c, v) }))
    }

    @Volatile var a: Snap? = null
    @Volatile var b: Snap? = null
    /** 0 = A, 1 = B */
    @Volatile var pos = 0f
        private set
    @Volatile var autoBars = 4
    /** Auto-Morph läuft (Ziel 0 oder 1), sonst NaN. */
    @Volatile var autoTo = Float.NaN
        private set
    private var autoFrom = 0f
    private var autoStartBeat = 0.0
    @Volatile var presetIdx = -1

    fun capture(): Snap = Snap(FloatArray(FPS.size) { FPS[it].get() }, BooleanArray(BPS.size) { BPS[it].get() })

    fun storeA() { a = capture(); pos = 0f; lastSide = 0 }
    fun storeB() { b = capture(); pos = 1f; lastSide = 1 }
    fun ready(): Boolean = a != null && b != null

    private var lastSide = -1

    /** Morph-Stand setzen und den Mix dorthin bringen. */
    fun setPos(m: Float) {
        val sa = a ?: return
        val sb = b ?: return
        val x = m.coerceIn(0f, 1f)
        pos = x
        for (i in FPS.indices) {
            val fp = FPS[i]
            if (!fp.avail()) continue
            val va = sa.f[i]; val vb = sb.f[i]
            if (va == vb) continue
            // weiche S-Kurve: Lautstärken fahren musikalisch, nicht linear
            val e = x * x * (3 - 2 * x)
            fp.set(va + (vb - va) * e)
        }
        val side = if (x >= 0.5f) 1 else 0
        if (side != lastSide) {
            lastSide = side
            val s = if (side == 1) sb else sa
            for (i in BPS.indices) { val bp = BPS[i]; if (bp.avail() && sa.b[i] != sb.b[i]) bp.set(s.b[i]) }
        }
    }

    // ---------------------------------------------------------------- Auto-Morph im Takt
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val to = autoTo
            if (to.isNaN()) return
            val beats = (autoBars * 4).toDouble()
            val now = Engine.beatPos(Engine.heardFrame())
            val pr = ((now - autoStartBeat) / beats).toFloat()
            if (pr >= 0f) setPos(autoFrom + (to - autoFrom) * pr.coerceIn(0f, 1f))
            if (pr >= 1f) { autoTo = Float.NaN; return }
            ui.postDelayed(this, 25L)
        }
    }

    /** Auto-Morph zu [to] (0 = A, 1 = B) über [autoBars] Takte, Start auf der nächsten Eins. */
    fun startAuto(to: Float) {
        if (!ready()) return
        if (!Engine.seqPlaying) Engine.transportPlay(false)
        autoFrom = pos
        val now = Engine.beatPos(Engine.heardFrame())
        autoStartBeat = (Math.floor(now / 4.0) + 1) * 4.0
        autoTo = to
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    fun stopAuto() { autoTo = Float.NaN; ui.removeCallbacks(tick) }

    /** Fortschritt des Auto-Morphs (−1 = wartet auf die Eins). */
    fun autoProgress(): Float {
        if (autoTo.isNaN()) return 0f
        val p = ((Engine.beatPos(Engine.heardFrame()) - autoStartBeat) / (autoBars * 4.0)).toFloat()
        return if (p < 0f) -1f else p.coerceIn(0f, 1f)
    }

    fun reset() { stopAuto(); a = null; b = null; pos = 0f; lastSide = -1; presetIdx = -1 }

    // ---------------------------------------------------------------- Vorlagen: B aus dem jetzigen Mix ableiten
    class Preset(val name: String, val desc: String, val make: (Snap) -> Snap)

    private fun idx(name: String) = FPS.indexOfFirst { it.name == name }
    private fun bIdx(name: String) = BPS.indexOfFirst { it.name == name }
    private fun Snap.copy() = Snap(f.copyOf(), b.copyOf())
    private fun Snap.mulF(name: String, k: Float) { val i = idx(name); if (i >= 0) f[i] = (f[i] * k).coerceIn(0f, 1.5f) }
    private fun Snap.setF(name: String, v: Float) { val i = idx(name); if (i >= 0) f[i] = v }
    private fun Snap.setB(name: String, v: Boolean) { val i = bIdx(name); if (i >= 0) b[i] = v }
    /** Filter aller Synths/ACID Richtung [v] – nur zu (oder nur auf, wenn [open]), nie in die Gegenrichtung. */
    private fun Snap.allSynthCut(v: Float, open: Boolean = false) {
        fun one(n: String, x: Float) { val i = idx(n); if (i >= 0) f[i] = if (open) maxOf(f[i], x) else minOf(f[i], x) }
        for (t in 0 until InstState.SYN_TRACKS) one(InstState.synName(t) + "-CUT", v)
        one("ACID-CUT", v * 0.8f)
    }
    private val BASS_NAME get() = InstState.synName(InstState.BASS_TRACK)

    val PRESETS = listOf(
        Preset("Breakdown", "Drums und Bass raus, Flächen bleiben, Filter halb zu") { s -> s.copy().apply {
            setB("I1", false); setB("I0", false); setB("I${2 + InstState.BASS_TRACK}", false)
            allSynthCut(0.35f); mulF("ATMOS", 1.4f)
        } },
        Preset("Filter-Sweep zu", "alle Synths und ACID dumpf – zum Hochfahren in den Drop") { s -> s.copy().apply { allSynthCut(0.12f) } },
        Preset("Nur Drums & Bass", "Akkorde, Leads und Extras stumm") { s -> s.copy().apply {
            for (t in 0 until InstState.SYN_TRACKS) if (t != InstState.BASS_TRACK) setB("I${2 + t}", false)
            for (c in listOf(10, 11, 13, 14, 15, 19)) setB("I$c", false)
        } },
        Preset("Drop: alles voll", "alle Spuren an, Filter offen, Lautstärke +20 %") { s -> s.copy().apply {
            for (p in 0 until 12) setB("PAD${p + 1}", true)
            for (c in 0 until InstState.INSTS) if (InstState.instHasNotes(c)) setB("I$c", true)
            allSynthCut(0.85f, open = true)
            for (n in listOf("ACID", "DRUM", BASS_NAME)) mulF(n, 1.2f)
        } },
        Preset("Intro: nur Flächen", "Drums aus, Pads leise, Atmos lauter") { s -> s.copy().apply {
            setB("I1", false); setB("I0", false)
            for (p in 0 until 12) mulF("PAD${p + 1}", 0.5f)
            mulF("ATMOS", 1.5f); allSynthCut(0.45f)
        } },
        Preset("Pads leiser (Platz für Vocals)", "alle Pads −40 %, Synths etwas zu") { s -> s.copy().apply {
            for (p in 0 until 12) mulF("PAD${p + 1}", 0.6f)
            allSynthCut(0.5f)
        } },
    )

    /** Vorlage anwenden: A = jetziger Mix, B = Vorlage daraus. */
    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        stopAuto()
        val now = capture()
        a = now
        b = PRESETS[k].make(now)
        pos = 0f; lastSide = 0
        presetIdx = k
    }

    /** Wie viele Werte sich zwischen A und B unterscheiden (für die Anzeige). */
    fun diffCount(): Int {
        val sa = a ?: return 0
        val sb = b ?: return 0
        var n = 0
        for (i in sa.f.indices) if (sa.f[i] != sb.f[i] && FPS[i].avail()) n++
        for (i in sa.b.indices) if (sa.b[i] != sb.b[i] && BPS[i].avail()) n++
        return n
    }

    /** Kurze Liste der Unterschiede (Namen), für die Anzeige. */
    fun diffNames(max: Int = 8): List<String> {
        val sa = a ?: return emptyList()
        val sb = b ?: return emptyList()
        val out = ArrayList<String>()
        for (i in sa.b.indices) if (sa.b[i] != sb.b[i] && BPS[i].avail()) out.add(bName(BPS[i].name) + (if (sb.b[i]) " an" else " aus"))
        for (i in sa.f.indices) if (sa.f[i] != sb.f[i] && FPS[i].avail()) {
            val d = sb.f[i] - sa.f[i]
            out.add(FPS[i].name.replace("-CUT", " Filter") + (if (d > 0) " ↑" else " ↓"))
        }
        return out.take(max)
    }

    private fun bName(n: String): String = if (n.startsWith("I")) {
        when (val c = n.drop(1).toInt()) { 0 -> "ACID"; 1 -> "DRUM"; in 2 until 2 + InstState.SYN_TRACKS -> InstState.synName(c - 2); else -> Engine.INST_NAMES.getOrElse(c - 7) { "?" } }
    } else n
}
