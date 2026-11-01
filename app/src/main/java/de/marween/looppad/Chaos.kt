package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * ⚡ CHAOS-TRIGGER: hängt sich in den Master (DJ-Decks + alles andere) und zerlegt das laufende Audio live.
 *  1. Stutter-Pads 4×4: Reihen = Loop-Länge (1/8 … 1/64 Takt), Spalten = Gravitation (Glide hoch, gerade, Glide runter, Beschleunigen)
 *  2. Audio-Marrow: 8 gestimmte Resonatoren (Metall / Holz / Ton) – der Track klingt durch sie hindurch, Antippen spielt sie an
 *  3. Scratch-Ghost: Scratches und Crossfader-Cuts aus den letzten Schlägen auf die Offbeat-16tel
 *  4. Portal-Warp: halten = Delay-Strudel, Pitch sinkt, Rausch-Riser · loslassen = Drop auf der nächsten Eins (Sub + Filter auf)
 */
object Chaos {
    /** Panel einmal geöffnet → Audio wird mitgeschnitten (für die Stutter-Pads). */
    @Volatile var armed = false
    // Regler
    @Volatile var marrow = 0f
    @Volatile var material = 0.5f
    @Volatile var scratch = 0f
    @Volatile var wet = 1f

    // ---- Eingaben der Oberfläche
    /** gehaltenes Stutter-Pad (−1 = keins) */
    @Volatile var padHeld = -1
    @Volatile var portalHeld = false
    private val resTap = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    fun tapRes(k: Int) { resTap.add(k) }

    // ---- Anzeige
    @Volatile var stutterRate = 1f
    @Volatile var portalAmt = 0f
    @Volatile var dropFlash = 0f
    val resLevel = FloatArray(8)
    @Volatile var scratchFlash = 0f
    @Volatile var rootPc = 9

    class Preset(val name: String, val marrow: Float, val material: Float, val scratch: Float, val wet: Float)
    val PRESETS = listOf(
        Preset("Sauber – nur Stutter", 0f, 0.5f, 0f, 1f),
        Preset("Metall-Glocken aus dem Track", 0.45f, 0.9f, 0f, 1f),
        Preset("Holz-Percussion", 0.4f, 0.5f, 0.1f, 1f),
        Preset("Ton-Gefäße (Udu)", 0.35f, 0.15f, 0f, 1f),
        Preset("Turntablism", 0f, 0.5f, 0.75f, 1f),
        Preset("Voller Chaos-Modus", 0.35f, 0.75f, 0.55f, 0.85f)
    )
    @Volatile var presetIdx = -1
    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size); val p = PRESETS[k]
        presetIdx = k; marrow = p.marrow; material = p.material; scratch = p.scratch; wet = p.wet
    }

    val ROW_DIV = intArrayOf(8, 16, 32, 64)
    val COL_NAMES = arrayOf("↗ HOCH", "→ GERADE", "↘ RUNTER", "⇶ BESCHL.")

    // ---- Puffer (Stereo, 8 s)
    private var sr = 48000
    private var cap = 0
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)

    private fun ensure(rate: Int) {
        if (cap == 0 || sr != rate) { sr = rate; cap = rate * 8; bufL = FloatArray(cap); bufR = FloatArray(cap) }
    }

    private fun rd(b: FloatArray, pos: Double): Float {
        var p = pos % cap
        if (p < 0) p += cap
        val i = p.toInt()
        val f = (p - i).toFloat()
        val a = b[i]; val c = b[if (i + 1 >= cap) 0 else i + 1]
        return a + (c - a) * f
    }

    /** Raster vom laufenden Deck (wie bei EUKLID). */
    fun tick() {
        if (!armed || Ghost.on || Euclid.on) return
        var best: Dj.Deck? = null
        var bl = -1f
        for (d in Dj.decks) {
            if (!d.loaded() || !d.playing || d.bpm <= 0f) continue
            val g = d.fader * (if (d.idx == 0) 1f - Dj.xfade else Dj.xfade).coerceAtLeast(0.05f) + d.level
            if (g > bl) { bl = g; best = d }
        }
        val d = best ?: return
        val bpm = (d.bpm * d.ratio * (1.0 + d.bend)).toFloat()
        if (bpm !in 40f..240f) return
        val rate = Engine.sampleRate
        val w = Engine.outFrameNow()
        val outBeat = rate * 60.0 / bpm
        val heard = d.pos - DeckStems.LAT * (d.srcRate.toDouble() / rate) * d.ratio
        val bi = Dj.beatIndex(d, heard) - d.barOffset
        val inBar = bi - floor(bi / 4.0) * 4.0
        Engine.ghostLock(bpm, w - (inBar * outBeat).toLong())
        if (d.key >= 0) rootPc = Keys.root(d.effKey())
    }

    // ---- Stutter
    private var stPad = -1
    private var stStart = 0.0      // Start-Frame im Puffer (absolut)
    private var stLen = 0.0
    private var stT = 0.0          // Zeit seit Druck (Frames)
    private var stPos = 0.0        // Lese-Position innerhalb des Loops
    private var stMix = 0f
    private var stRel = 0          // Frames bis zur Ausblende

    // ---- Resonatoren (8 × 2 Moden)
    private val ra1 = DoubleArray(16); private val ra2 = DoubleArray(16); private val ry1 = DoubleArray(16); private val ry2 = DoubleArray(16)
    private val rg = DoubleArray(16)
    private val resBoost = FloatArray(8)
    private var resKey = -1; private var resMat = -1f
    private var hpL = 0f
    private val SCALE = intArrayOf(0, 3, 5, 7, 10, 12, 15, 19)

    private fun tuneRes() {
        val mat = material
        val ratio = if (mat < 0.33f) 2.0 else if (mat < 0.66f) 3.93 else 2.76
        val dec = if (mat < 0.33f) 0.35 else if (mat < 0.66f) 0.18 else 1.2
        for (k in 0 until 8) {
            val n = 57 + rootPc + SCALE[k]
            val f = 440.0 * 2.0.pow((n - 69) / 12.0)
            for (m in 0 until 2) {
                val fm = f * (if (m == 0) 1.0 else ratio)
                val i = k * 2 + m
                if (fm > sr * 0.45) { ra1[i] = 0.0; ra2[i] = 0.0; rg[i] = 0.0; continue }
                val w = 2 * PI * fm / sr
                val r = exp(-1.0 / ((if (m == 0) dec else dec * 0.5) * sr))
                ra1[i] = 2 * r * kotlin.math.cos(w); ra2[i] = -r * r
                rg[i] = sin(w) * (if (m == 0) 1.0 else 0.4)
            }
        }
        resKey = rootPc; resMat = mat
    }

    // ---- Scratch-Ghost
    private var lastHitAbs = -1L
    private var envFast = 0f; private var envSlow = 0f
    private var scT = -1
    private var scLen = 0
    private var scSrc = 0.0
    private var scPos = 0.0
    private var lastStep = Long.MIN_VALUE
    private val rnd = java.util.Random()

    // ---- Portal
    private var poAmt = 0f
    private var poRate = 1.0
    private var poRead = 0.0
    private var poNoiseBp = 0f; private var poNoiseLp = 0f
    private var dropPending = false
    private var dropT = -1
    private var subPh = 0.0
    private var dropLpL = 0f; private var dropLpR = 0f
    private var lastBeat = Long.MIN_VALUE

    fun active(): Boolean = armed

    /** Im Mischer-Thread: Master [l]/[r] (Stereo) an Ausgabe-Frame [w] bearbeiten. */
    fun process(l: FloatArray, r: FloatArray, n: Int, w: Long, rate: Int) {
        ensure(rate)
        if (resKey != rootPc || resMat != material) tuneRes()
        while (true) { val k = resTap.poll() ?: break; if (k in 0 until 8) resBoost[k] = 1f }
        val beat = Engine.seqBeatLen().coerceAtLeast(1000.0)
        val bar = beat * 4
        val bpos0 = Engine.beatPos(w)
        val resDec = exp(-1.0 / (0.6 * sr)).toFloat()
        val mx = marrow
        for (i in 0 until n) {
            val af = w + i
            val ip = (af % cap).toInt()
            val inL = l[i]; val inR = r[i]
            bufL[ip] = inL; bufR[ip] = inR
            var oL = inL; var oR = inR
            val bpos = bpos0 + i / beat
            // ---------- Stutter
            val ph = padHeld
            if (ph >= 0 && stPad != ph) {
                // neuer Druck: Loop am letzten Rasterpunkt seiner Länge festhalten
                val div = ROW_DIV[ph / 4]
                stLen = bar / div
                val stepIdx = floor(bpos * div / 4.0)
                stStart = af - (bpos * div / 4.0 - stepIdx) * stLen
                if (stStart + stLen > af) stStart -= stLen   // nur bereits aufgenommenes Audio
                stPad = ph; stT = 0.0; stPos = 0.0; stRel = 0
            }
            if (ph < 0 && stPad >= 0 && stRel == 0) stRel = (sr * 0.012).toInt()
            if (stPad >= 0) {
                val col = stPad % 4
                val prog = stT / beat      // in Schlägen seit Druck
                var rate1 = 1.0
                var len = stLen
                when (col) {
                    0 -> rate1 = 2.0.pow(min(2.0, prog * 0.5))         // Glide hoch (bis 2 Oktaven)
                    2 -> rate1 = 2.0.pow(-min(2.0, prog * 0.5))        // Glide runter
                    3 -> len = stLen / 2.0.pow(min(3.0, floor(prog)))  // Salve wird jeden Schlag doppelt so schnell
                }
                stPos += rate1
                if (stPos >= len) stPos -= len * floor(stPos / len)
                stutterRate = rate1.toFloat() * (stLen / len).toFloat()
                val sl = rd(bufL, stStart + stPos); val srr = rd(bufR, stStart + stPos)
                // weiche Kanten je Wiederholung
                val edge = min(1.0, min(stPos, len - stPos) / (sr * 0.0015)).toFloat()
                stMix = if (stRel > 0) max(0f, stMix - 1f / (sr * 0.012f)) else min(1f, stMix + 1f / (sr * 0.003f))
                val g = stMix * edge * wet
                oL = oL * (1f - stMix * wet) + sl * g
                oR = oR * (1f - stMix * wet) + srr * g
                stT++
                if (stRel > 0) { stRel--; if (stRel == 0 || stMix <= 0f) { stPad = -1; stRel = 0; stutterRate = 1f } }
            }
            // ---------- Transienten (für Scratch-Ghost)
            val mono = (inL + inR) * 0.5f
            val a = abs(mono)
            envFast += (a - envFast) * 0.02f
            envSlow += (a - envSlow) * 0.0008f
            if (envFast > envSlow * 2.2f && envFast > 0.05f && af - lastHitAbs > sr / 10) lastHitAbs = af
            // Offbeat-16tel → Scratch
            val step = floor(bpos * 4).toLong()
            if (step != lastStep) {
                lastStep = step
                if (scratch > 0.02f && Math.floorMod(step, 2L) == 1L && rnd.nextFloat() < scratch * 0.55f && lastHitAbs > 0 && af - lastHitAbs < sr * 6) {
                    scT = 0; scLen = (beat / 4 * (0.8 + 0.4 * rnd.nextFloat())).toInt().coerceAtLeast(400)
                    scSrc = lastHitAbs.toDouble(); scPos = 0.0
                    scratchFlash = 1f
                }
            }
            if (scT >= 0) {
                val p = scT.toDouble() / scLen
                // Baby-Scratch: vor, zurück, vor (Geschwindigkeit als Sinus) + Crossfader-Cuts
                val v = 1.8 * sin(2 * PI * p * 1.5)
                scPos += v
                val cut = if (((p * 6).toInt() % 2) == 0) 1f else 0.05f
                val env = (sin(PI * p)).toFloat()
                val s = rd(bufL, scSrc + scPos) * 0.5f + rd(bufR, scSrc + scPos) * 0.5f
                val gs = s * cut * env * scratch * 1.3f
                oL += gs; oR += gs
                scT++
                if (scT >= scLen) scT = -1
            }
            // ---------- Audio-Marrow (Resonatoren)
            hpL += 0.05f * (mono - hpL)
            val exc = (mono - hpL).toDouble()
            var rsum = 0.0
            for (k in 0 until 8) {
                val lvl = mx + resBoost[k] * 1.5f
                if (lvl < 0.002f && abs(ry1[k * 2]) < 1e-5) continue
                for (m in 0 until 2) {
                    val ii = k * 2 + m
                    val y = ra1[ii] * ry1[ii] + ra2[ii] * ry2[ii] + exc * rg[ii] * lvl
                    ry2[ii] = ry1[ii]; ry1[ii] = y
                    rsum += y
                }
            }
            for (k in 0 until 8) resBoost[k] *= resDec
            val rs = tanh(rsum * 2.0).toFloat() * 0.5f
            oL += rs; oR += rs
            // ---------- Portal-Warp
            val beatIdx = floor(bpos).toLong()
            if (portalHeld) { poAmt = min(1f, poAmt + 1f / (sr * 0.4f)); dropPending = true }
            else if (dropPending && beatIdx != lastBeat && Math.floorMod(beatIdx, 4L) == 0L) {
                dropPending = false; dropT = 0; dropFlash = 1f; poAmt = 0f; poRate = 1.0
            } else if (!dropPending) poAmt = max(0f, poAmt - 1f / (sr * 0.05f))
            lastBeat = beatIdx
            if (poAmt > 0.001f || dropPending) {
                // Puffer-Strudel: Lesekopf wird langsamer (Pitch sinkt), alte Takte kreisen
                poRate = max(0.25, poRate - 1.0 / (sr * 6.0))
                if (poRead == 0.0 || poRead > af || af - poRead > cap - sr) poRead = af - beat
                poRead += poRate
                if (af - poRead > bar * 2) poRead += bar
                val wl = rd(bufL, poRead); val wr = rd(bufR, poRead)
                // Rausch-Riser: Bandpass steigt
                val nz = rnd.nextFloat() * 2f - 1f
                val fc = 300.0 * 2.0.pow(poAmt * 5.5 + (1 - poRate) * 2)
                val gg = (2 * sin(PI * min(fc, sr / 6.0) / sr)).toFloat()
                poNoiseLp += gg * poNoiseBp; val h = nz - poNoiseLp - 0.4f * poNoiseBp; poNoiseBp += gg * h
                val riser = poNoiseBp * poAmt * poAmt * 0.5f
                val pa = if (dropPending) max(poAmt, 0.85f) else poAmt
                oL = oL * (1f - pa) + (wl * 0.9f + riser) * pa
                oR = oR * (1f - pa) + (wr * 0.9f + riser) * pa
            } else { poRead = 0.0; poRate = 1.0 }
            portalAmt = poAmt
            // ---------- Drop: Sub-Bass + Filter öffnet
            if (dropT >= 0) {
                val t = dropT.toDouble() / sr
                val f = 32.0 + 40.0 * exp(-t * 6)
                subPh += f / sr; if (subPh >= 1.0) subPh -= 1.0
                val sub = (sin(2 * PI * subPh) * exp(-t * 1.6) * 0.8).toFloat()
                val fcOpen = 150.0 * 2.0.pow(min(1.0, t / 0.6) * 7.0)
                val g2 = (1 - exp(-2 * PI * min(fcOpen, sr * 0.45) / sr)).toFloat()
                dropLpL += g2 * (oL - dropLpL); dropLpR += g2 * (oR - dropLpR)
                val open = min(1.0, t / 0.6).toFloat()
                oL = dropLpL * (1f - open) + oL * open + sub
                oR = dropLpR * (1f - open) + oR * open + sub
                dropT++
                if (t > 1.5) dropT = -1
            } else { dropLpL = oL; dropLpR = oR }
            l[i] = oL; r[i] = oR
        }
        for (k in 0 until 8) resLevel[k] = (mx + resBoost[k]).coerceIn(0f, 1f)
        scratchFlash *= 0.9f; dropFlash *= 0.95f
    }
}
