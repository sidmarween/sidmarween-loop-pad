package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * EUKLID – generativer Polyrhythmus-Synth.
 *
 * Vier Spuren (Perkussion, Hi-Hat, Rim/Shaker, Pluck), jede ein euklidisches Muster: k Schläge möglichst gleichmäßig
 * auf n Schritte verteilt, gedreht um r. Die Spuren haben verschiedene Längen (z. B. 16, 12, 7, 5) – dadurch laufen
 * sie gegeneinander (Polymeter) und ergeben erst nach vielen Takten wieder dasselbe Bild. Mit POLY werden die n Schritte
 * einer Spur über einen ganzen Takt gestreckt (echter Polyrhythmus, z. B. 5 gegen 4).
 * MORPHING verändert nach jedem Durchlauf Schläge, Drehung, Länge, Betonungen und Pluck-Töne ein wenig – so wiederholt
 * sich nichts exakt. Alles läuft im Master-Raster (im DJ-Modus folgt es dem laufenden Deck), Pluck-Töne in dessen Tonart.
 */
object Euclid {
    // ---------------------------------------------------------------- Bedienung
    @Volatile var on = false
    /** Schläge pro Takt (Dichte) 0..1 */
    @Volatile var density = 0.45f
    /** Taktlänge / Komplexität 0..1: gerade Längen (16, 8, 12) … ungerade (5, 7, 11, 13) */
    @Volatile var complexity = 0.5f
    /** Morphing 0..1 */
    @Volatile var morph = 0.4f
    @Volatile var level = 0.7f
    /** Schritte über den Takt strecken (Polyrhythmus) statt 1/16-Raster (Polymeter). */
    @Volatile var poly = false
    val laneOn = booleanArrayOf(true, true, true, true)
    val laneVol = floatArrayOf(0.9f, 0.6f, 0.65f, 0.7f)
    val NAMES = arrayOf("PERC", "HATS", "RIM", "PLUCK")

    // Anzeige (UI liest)
    val laneN = IntArray(4) { 16 }
    val laneK = IntArray(4) { 4 }
    val laneRot = IntArray(4)
    val laneStep = IntArray(4) { -1 }
    val laneHit = Array(4) { BooleanArray(32) }
    val laneFlash = FloatArray(4)
    @Volatile var curKey = 21
        private set

    // ---------------------------------------------------------------- Muster
    private val rnd = java.util.Random()
    private val acc = Array(4) { FloatArray(32) { 1f } }
    private val baseLens = arrayOf(intArrayOf(16, 16, 12, 16), intArrayOf(16, 12, 8, 6), intArrayOf(16, 12, 7, 10), intArrayOf(13, 11, 7, 5))
    private val baseDens = floatArrayOf(0.30f, 0.65f, 0.35f, 0.32f)
    private var lastStep = LongArray(4) { Long.MIN_VALUE }
    private var cycles = IntArray(4)
    private var pluckDeg = 0
    private var ready = false
    private var srN = 48000

    private fun lensFor(c: Float, lane: Int): Int {
        val t = c * 3f
        val i = t.toInt().coerceIn(0, 2)
        val f = t - i
        // zwischen zwei Sätzen weich wählen (zufällig gewichtet)
        return if (rnd.nextFloat() < f) baseLens[i + 1][lane] else baseLens[i][lane]
    }

    private fun build(l: Int) {
        val n = laneN[l]
        val k = laneK[l].coerceIn(1, n - 1)
        laneK[l] = k
        val r = Math.floorMod(laneRot[l], n)
        for (i in 0 until 32) laneHit[l][i] = false
        for (i in 0 until n) {
            // Bresenham-Verteilung (= Björklund bis auf Drehung)
            val hit = (i * k) % n < k
            laneHit[l][Math.floorMod(i + r, n)] = hit
        }
        for (i in 0 until n) acc[l][i] = (0.6f + 0.4f * rnd.nextFloat()).let { if (i == r) 1f else it }
    }

    /** Neu würfeln (Knopf und beim Ändern von Dichte/Komplexität). */
    fun regenerate() {
        for (l in 0..3) {
            laneN[l] = lensFor(complexity, l)
            laneK[l] = max(1, (laneN[l] * (baseDens[l] * (0.35f + density * 1.3f))).roundToInt()).coerceAtMost(laneN[l] - 1)
            laneRot[l] = rnd.nextInt(laneN[l])
            if (l == 0) laneRot[l] = 0
            build(l)
        }
    }

    /** Nach jedem Durchlauf einer Spur: ein wenig verändern. */
    private fun mutate(l: Int) {
        val m = morph
        if (m < 0.02f) return
        val n = laneN[l]
        if (rnd.nextFloat() < m * 0.5f) laneRot[l] += if (rnd.nextBoolean()) 1 else -1
        if (rnd.nextFloat() < m * 0.4f) {
            val target = max(1, (n * (baseDens[l] * (0.35f + density * 1.3f))).roundToInt())
            laneK[l] = (laneK[l] + if (laneK[l] < target) 1 else if (laneK[l] > target) -1 else if (rnd.nextBoolean()) 1 else -1).coerceIn(1, n - 1)
        }
        if (rnd.nextFloat() < m * 0.12f && l != 0) {
            laneN[l] = lensFor(complexity, l)
            laneK[l] = laneK[l].coerceIn(1, laneN[l] - 1)
        }
        build(l)
        if (l == 3) pluckDeg += rnd.nextInt(5) - 2
    }

    // ---------------------------------------------------------------- Klang
    private class Hit(var on: Boolean = false, var t: Int = 0, var amp: Float = 0f, var f: Double = 0.0, var ph: Double = 0.0, var open: Boolean = false, var alt: Boolean = false, var mode: Int = 0)
    private val perc = Array(4) { Hit() }
    private val hats = Array(4) { Hit() }
    private val rims = Array(4) { Hit() }
    private class Str(sr: Int) { val b = FloatArray(sr / 30); var len = 100; var w = 0; var on = false; var amp = 0f; var last = 0f; var t = 0; var mode = 0; var f = 220.0; var ph = 0.0 }
    private var hph = 0.0
    private var crN = 0; private var crL = 0f; private var crR = 0f; private var crP = 0f

    /** Klang-Sätze: Perc-Art, Hat-Farbe/-Länge, Rim-Art, Pluck-Art. */
    class Kit(val name: String, val perc: Int, val percOct: Int, val hatHp: Float, val hatClosed: Double, val hatOpen: Double,
              val hatMetal: Boolean, val rim: Int, val pluck: Int, val crush: Boolean = false)
    val KITS = listOf(
        Kit("AFRO-PERCS", 0, 0, 0.86f, 70.0, 14.0, false, 0, 0),
        Kit("MINIMAL-KLICKS", 3, 12, 0.95f, 110.0, 30.0, false, 2, 3),
        Kit("BERLIN-METALL", 1, 0, 0.93f, 60.0, 9.0, true, 1, 1),
        Kit("MARIMBA", 2, 12, 0.8f, 80.0, 20.0, false, 0, 2),
        Kit("TRIBAL-TOMS", 4, -5, 0.84f, 75.0, 16.0, false, 0, 1),
        Kit("GLITCH-IDM", 1, 7, 0.95f, 140.0, 25.0, true, 2, 3, true),
        Kit("DEEP-HOUSE-SHAKER", 0, 0, 0.82f, 55.0, 12.0, false, 0, 2),
        Kit("DISCO-KUHGLOCKE", 0, 5, 0.9f, 65.0, 8.0, false, 3, 0),
        Kit("HOLZ & KALIMBA", 3, 0, 0.84f, 70.0, 16.0, false, 0, 2),
        Kit("CHIPTUNE-RHYTHMUS", 3, 7, 0.97f, 90.0, 20.0, true, 2, 3, true)
    )
    @Volatile var kit = 0

    /** Vorlagen: Name, Kit, Dichte, Taktlänge, Morphing, Poly, Spuren an (Bits: PERC=1, HATS=2, RIM=4, PLUCK=8) */
    class Preset(val name: String, val kit: Int, val dens: Float, val comp: Float, val morph: Float, val poly: Boolean, val lanes: Int)
    val PRESETS = listOf(
        Preset("Afro-House-Percs", 0, 0.5f, 0.45f, 0.35f, false, 15),
        Preset("Minimal-Techno-Klicks", 1, 0.55f, 0.6f, 0.5f, false, 7),
        Preset("Berlin-Metall 13/11", 2, 0.45f, 0.95f, 0.45f, false, 7),
        Preset("Marimba 3 gegen 4", 3, 0.4f, 0.4f, 0.3f, true, 13),
        Preset("Tribal-Toms", 4, 0.6f, 0.55f, 0.4f, false, 7),
        Preset("Glitch-IDM", 5, 0.65f, 1f, 0.85f, true, 15),
        Preset("Deep-House-Shaker", 6, 0.4f, 0.2f, 0.25f, false, 14),
        Preset("Disco-Kuhglocke", 7, 0.45f, 0.15f, 0.2f, false, 7),
        Preset("Kalimba-Regen 5 gegen 4", 8, 0.35f, 0.9f, 0.5f, true, 12),
        Preset("Steppers-Hats (nur Hats)", 0, 0.7f, 0.5f, 0.4f, false, 2),
        Preset("Ambient-Pluck (nur Pluck)", 3, 0.3f, 0.8f, 0.6f, true, 8),
        Preset("8-Bit-Groove", 9, 0.55f, 0.5f, 0.4f, false, 15)
    )
    @Volatile var presetIdx = -1
        private set

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        kit = p.kit; density = p.dens; complexity = p.comp; morph = p.morph; poly = p.poly
        for (l in 0..3) laneOn[l] = (p.lanes shr l) and 1 != 0
        regenerate()
    }
    private var strs: Array<Str> = emptyArray()
    private var noise = 12345
    private fun nz(): Float { noise = noise * 1103515245 + 12345; return ((noise ushr 9) and 0x7FFF) / 16384f - 1f }
    private var hp1 = 0f; private var hp2 = 0f; private var hpx = 0f
    private var bp1 = 0f; private var bp2 = 0f
    private var pl: Plate? = null
    private var dlL = FloatArray(0); private var dlR = FloatArray(0); private var dlW = 0

    private fun init(sr: Int) {
        srN = sr
        strs = Array(4) { Str(sr) }
        pl = Plate(sr, 0.6f)
        dlL = FloatArray(sr * 2); dlR = FloatArray(sr * 2)
        regenerate()
        ready = true
    }

    private fun scaleNote(key: Int, deg: Int): Int {
        val r = Keys.root(key)
        val iv = if (Keys.isMinor(key)) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)   // Pentatonik: klingt immer
        val o = Math.floorDiv(deg, 5).coerceIn(-1, 1)
        return 57 + r + iv[Math.floorMod(deg, 5)] + 12 * o + (if (r > 6) -12 else 0)
    }

    private fun key(): Int {
        for (d in Dj.decks) if (d.loaded() && d.playing && d.key >= 0) return d.effKey()
        if (Ghost.curKey >= 0) return Ghost.curKey
        return 21
    }

    // eigene Samples je Spur (UserSamples "euk:l")
    private class SV(var on: Boolean = false, var pos: Double = 0.0, var rate: Double = 1.0, var amp: Float = 0f, var lane: Int = 0, var data: FloatArray? = null)
    private val sv = Array(12) { SV() }
    private val SV_PAN = floatArrayOf(0.4f, 0.6f, 0.7f, 0.5f)

    private fun trigger(l: Int, a: Float, alt: Boolean) {
        laneFlash[l] = 1f
        val smp = UserSamples.euk(l)
        if (smp != null) {
            val v = sv.firstOrNull { !it.on } ?: sv.minByOrNull { it.amp }!!
            // Pluck-Spur: Sample folgt den Tönen (als C4 aufgenommen gedacht), sonst Originaltonhöhe
            v.rate = if (l == 3) {
                val note = scaleNote(curKey, pluckDeg + (if (alt) 2 else 0) + (if (rnd.nextFloat() < morph * 0.5f) rnd.nextInt(3) - 1 else 0))
                pluckDeg = pluckDeg.coerceIn(-4, 9)
                2.0.pow((note - 60) / 12.0)
            } else if (l == 0 && alt) 1.12 else 1.0
            v.pos = 0.0; v.amp = a * (if (alt) 1f else 0.85f); v.lane = l; v.data = smp; v.on = true
            return
        }
        when (l) {
            0 -> {
                val h = perc.firstOrNull { !it.on } ?: perc[0]
                // Conga/Tom: zwei Tonhöhen aus der Tonart (Grundton tief, Quinte)
                val kt = KITS[kit.coerceIn(0, KITS.size - 1)]
                val base = 440.0 * 2.0.pow((scaleNote(curKey, 0) - 24 + kt.percOct - 69) / 12.0)
                h.f = if (alt) base * 1.5 else base
                h.on = true; h.t = 0; h.amp = a; h.ph = 0.0; h.mode = kt.perc
            }
            1 -> {
                val h = hats.firstOrNull { !it.on } ?: hats[0]
                h.on = true; h.t = 0; h.amp = a * (if (alt) 0.9f else 0.65f); h.open = alt && rnd.nextFloat() < 0.35f + morph * 0.3f
            }
            2 -> {
                val h = rims.firstOrNull { !it.on } ?: rims[0]
                val kt = KITS[kit.coerceIn(0, KITS.size - 1)]
                h.on = true; h.t = 0; h.amp = a; h.ph = 0.0; h.mode = kt.rim
                h.alt = if (kt.name == "DEEP-HOUSE-SHAKER") true else alt || rnd.nextFloat() < morph * 0.3f
            }
            else -> {
                val s = strs.firstOrNull { !it.on } ?: strs.minByOrNull { it.amp }!!
                val note = scaleNote(curKey, pluckDeg + (if (alt) 2 else 0) + (if (rnd.nextFloat() < morph * 0.5f) rnd.nextInt(3) - 1 else 0))
                pluckDeg = pluckDeg.coerceIn(-4, 9)
                val f = 440.0 * 2.0.pow((note - 69) / 12.0)
                s.len = (srN / f).toInt().coerceIn(8, s.b.size - 1)
                for (i in 0 until s.len) s.b[i] = nz() * 0.6f
                // Anschlag weicher: einmal tiefpass
                for (i in 1 until s.len) s.b[i] = 0.5f * (s.b[i] + s.b[i - 1])
                s.w = 0; s.on = true; s.amp = a; s.last = 0f; s.t = 0
                s.mode = KITS[kit.coerceIn(0, KITS.size - 1)].pluck; s.f = f; s.ph = 0.0
            }
        }
    }

    /** Raster vom laufenden Deck übernehmen (wenn der Ghost-Producer es nicht schon tut). */
    fun tick() {
        if (!on || Ghost.on) return
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
        val sr = Engine.sampleRate
        val w = Engine.outFrameNow()
        val outBeat = sr * 60.0 / bpm
        // hörbare Position (die Stems-Filter verzögern das Deck um LAT)
        val heard = d.pos - DeckStems.LAT * (d.srcRate.toDouble() / sr) * d.ratio
        val bi = Dj.beatIndex(d, heard) - d.barOffset
        val inBar = bi - floor(bi / 4.0) * 4.0
        Engine.ghostLock(bpm, w - (inBar * outBeat).toLong())
    }

    fun active(): Boolean = on || perc.any { it.on } || strs.any { it.on } || sv.any { it.on }

    /** In den Master mischen. [ms]/[ml] = Master-Start und -Taktlänge (Ausgabe-Proben), [w] = Ausgabe-Frame des Blocks. */
    private val pendT = IntArray(4)
    private val pendA = FloatArray(4)
    private val pendAlt = BooleanArray(4)
    private var swingFrames = 0

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w: Long, ms: Long, ml: Long, vol: Float, swing: Long = 0L) {
        swingFrames = swing.toInt().coerceAtLeast(0)
        if (!ready || srN != sr) init(sr)
        curKey = key()
        val bar = if (ml > 0L) ml.toDouble() else sr * 2.0
        val start = ms
        val plate = pl!!
        val dt = 1.0 / sr
        val hpA = 0.86f
        for (i in 0 until n) {
            val f = w + i
            // Schritte
            if (on) for (l in 0..3) {
                val nn = laneN[l]
                val stepLen = if (poly && l >= 2) bar / nn else bar / 16.0
                val rel = (f - start).toDouble()
                val si = floor(rel / stepLen).toLong()
                if (si != lastStep[l]) {
                    val first = lastStep[l] == Long.MIN_VALUE
                    lastStep[l] = si
                    if (first) continue
                    // im Polymeter-Raster läuft der Schritt-Zähler frei (Spurlänge ≠ 16 → Verschiebung gegen den Takt)
                    val st = Math.floorMod(si, nn.toLong()).toInt()
                    if (st == 0) { cycles[l]++; mutate(l) }
                    laneStep[l] = st
                    if (laneOn[l] && laneHit[l][st]) {
                        // Morph: gelegentlich ein Geister-Schlag weglassen bzw. leiser
                        if (rnd.nextFloat() < morph * 0.12f && st != laneRot[l] % nn) continue
                        val a = acc[l][st] * laneVol[l]
                        val alt = st == Math.floorMod(laneRot[l], nn) || acc[l][st] > 0.92f
                        // Swing wie beim Sequenzer: ungerade 1/16 (im Takt-Raster) später
                        val swingIt = swingFrames > 0 && !(poly && l >= 2) && Math.floorMod(si, 2L) == 1L
                        if (swingIt) { pendT[l] = swingFrames; pendA[l] = a; pendAlt[l] = alt } else trigger(l, a, alt)
                    }
                }
            }
            for (q in 0..3) if (pendT[q] > 0) { pendT[q]--; if (pendT[q] == 0) trigger(q, pendA[q], pendAlt[q]) }
            var l = 0f; var r = 0f
            for (v in sv) {
                if (!v.on) continue
                val d = v.data
                if (d == null) { v.on = false; continue }
                val i0 = v.pos.toInt()
                if (i0 + 1 >= d.size) { v.on = false; continue }
                val fr = (v.pos - i0).toFloat()
                val x = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * v.amp * 0.6f
                v.pos += v.rate
                val pan = SV_PAN[v.lane]
                l += x * (1f - pan); r += x * pan
            }
            val kt = KITS[kit.coerceIn(0, KITS.size - 1)]
            // Perc: Conga / Metall / Marimba / Holzblock / tiefe Tom
            for (h in perc) {
                if (!h.on) continue
                val t = h.t * dt
                var e: Float
                val v: Float
                when (h.mode) {
                    1 -> { // Metall: FM mit unharmonischem Verhältnis
                        h.ph += h.f * 2.3 * dt
                        e = exp(-t * 22).toFloat()
                        v = sin(2 * PI * h.ph + 2.2 * e * sin(2 * PI * h.ph * 1.41)).toFloat() * e * h.amp * 0.4f
                    }
                    2 -> { // Marimba: Grundton + 4. Teilton, schnell verklingend
                        h.ph += h.f * 2 * dt
                        e = exp(-t * 9).toFloat()
                        v = (sin(2 * PI * h.ph).toFloat() + 0.35f * exp(-t * 40).toFloat() * sin(2 * PI * h.ph * 3.93).toFloat()) * e * h.amp * 0.45f
                    }
                    3 -> { // Holzblock: kurzer, hoher Ton
                        h.ph += h.f * 6 * dt
                        e = exp(-t * 55).toFloat()
                        v = (sin(2 * PI * h.ph).toFloat() * 0.8f + nz() * 0.15f * exp(-t * 300).toFloat()) * e * h.amp * 0.45f
                    }
                    4 -> { // tiefe Tom: lang, Tonhöhe fällt stark
                        val pf = h.f * 0.5 * (1.0 + 1.8 * exp(-t * 25))
                        h.ph += pf * dt
                        e = exp(-t * 6).toFloat()
                        v = tanh(sin(2 * PI * h.ph) * 1.6).toFloat() * e * h.amp * 0.55f
                    }
                    else -> { // Conga
                        val pf = h.f * (1.0 + 1.4 * exp(-t * 60))
                        h.ph += pf * dt
                        e = exp(-t * 16).toFloat()
                        v = sin(2 * PI * h.ph).toFloat() * e * h.amp * 0.55f
                    }
                }
                l += v * 0.62f; r += v * 0.38f
                h.t++
                if (e < 1e-3f) h.on = false
            }
            // Hats: Rauschen, Hochpass, kurz/offen
            var hs = 0f
            for (h in hats) {
                if (!h.on) continue
                val t = h.t * dt
                val e = exp(-t * (if (h.open) kt.hatOpen else kt.hatClosed)).toFloat()
                hs += e * h.amp
                h.t++
                if (e < 1e-3f) h.on = false
            }
            if (hs > 0f) {
                val x = nz()
                hp1 = kt.hatHp * (hp1 + x - hpx); hpx = x
                var v = hp1 * hs * 0.22f
                if (kt.hatMetal) { hph += 0.0 + 7400.0 * dt; v = v * 0.6f + (if ((hph - floor(hph)) < 0.5) 1f else -1f) * hs * 0.05f }
                l += v * 0.4f; r += v * 0.6f
            }
            // Rim / Shaker / Clap / Klick / Kuhglocke
            for (h in rims) {
                if (!h.on) continue
                val t = h.t * dt
                var v: Float
                if (h.alt && h.mode != 3) {
                    // Shaker: Bandpass-Rauschen mit weichem Anstieg
                    val x = nz()
                    bp1 += 0.35f * (x - bp1); bp2 += 0.35f * (bp1 - bp2)
                    val env = (min(1.0, t / 0.008) * exp(-t * 28)).toFloat()
                    v = (bp1 - bp2) * env * h.amp * 0.5f
                    if (t > 0.25) h.on = false
                } else when (h.mode) {
                    1 -> { // Clap: drei schnelle Rausch-Stöße + Fahne
                        val x = nz()
                        bp1 += 0.45f * (x - bp1); bp2 += 0.45f * (bp1 - bp2)
                        val burst = if (t < 0.03) (if ((t * 100).toInt() % 1 == 0 && (t % 0.01) < 0.004) 1.0 else 0.25) else exp(-(t - 0.03) * 18)
                        v = (bp1 - bp2) * burst.toFloat() * h.amp * 0.9f
                        if (t > 0.35) h.on = false
                    }
                    2 -> { // Klick
                        val e = exp(-t * 600).toFloat()
                        v = (nz() * 0.5f + (if (h.t < 6) 0.8f else 0f)) * e * h.amp * 0.5f
                        if (t > 0.02) h.on = false
                    }
                    3 -> { // Kuhglocke: zwei Rechtecke 540/800 Hz, Bandpass
                        h.ph += 540.0 * dt
                        val sq = (if ((h.ph - floor(h.ph)) < 0.5) 1f else -1f) + (if (((h.ph * 1.48) - floor(h.ph * 1.48)) < 0.5) 1f else -1f)
                        bp1 += 0.3f * (sq - bp1); bp2 += 0.3f * (bp1 - bp2)
                        val e = (if (t < 0.01) 1.0 else exp(-(t - 0.01) * 14)).toFloat()
                        v = (bp1 - bp2) * e * h.amp * 0.35f
                        if (e < 1e-3f) h.on = false
                    }
                    else -> { // Rim
                        h.ph += 1650.0 * dt
                        val e = exp(-t * 90).toFloat()
                        v = (sin(2 * PI * h.ph).toFloat() * 0.6f + nz() * 0.25f * exp(-t * 400).toFloat()) * e * h.amp * 0.4f
                        if (e < 1e-3f) h.on = false
                    }
                }
                h.t++
                l += v * 0.3f; r += v * 0.7f
            }
            // Pluck: Karplus-Strong / Kalimba / Chip-Blip / gedämpft
            var ps = 0f
            for (s in strs) {
                if (!s.on) continue
                when (s.mode) {
                    2 -> { // Kalimba: Sinus + 5,4-facher Teilton (Zungen-Klang)
                        val t = s.t * dt
                        s.ph += s.f * dt
                        ps += (sin(2 * PI * s.ph).toFloat() * exp(-t * 3.5).toFloat() + 0.3f * sin(2 * PI * s.ph * 5.4).toFloat() * exp(-t * 30).toFloat()) * s.amp * 0.55f
                    }
                    3 -> { // Chip-Blip: Rechteck, kurz
                        val t = s.t * dt
                        s.ph += s.f * dt
                        val e = exp(-t * 14).toFloat()
                        ps += (if ((s.ph - floor(s.ph)) < 0.25) 0.5f else -0.17f) * kotlin.math.ceil(e * 15f) / 15f * s.amp * 0.6f
                    }
                    else -> {
                        val i0 = s.w
                        val i1 = if (i0 + 1 >= s.len) 0 else i0 + 1
                        val y = 0.5f * (s.b[i0] + s.b[i1]) * (if (s.mode == 1) 0.965f else 0.994f)
                        s.b[i0] = y
                        s.w = i1
                        ps += y * s.amp
                    }
                }
                s.t++
                if (s.t > srN * 2) s.on = false
            }
            if (kt.crush) {
                // Glitch: Abtastrate und Bits reduzieren
                if (crN-- <= 0) { crN = 5; crL = kotlin.math.round(l * 12f) / 12f; crR = kotlin.math.round(r * 12f) / 12f; crP = kotlin.math.round(ps * 12f) / 12f }
                l = crL; r = crR; ps = crP
            }
            // Pluck mit punktiertem Achtel-Echo (im Tempo)
            val dl = ((bar / 16.0 * 3).toInt()).coerceIn(64, dlL.size - 1)
            var ri = dlW - dl; if (ri < 0) ri += dlL.size
            val eL = dlL[ri]; val eR = dlR[ri]
            dlL[dlW] = ps * 0.5f + eR * 0.42f
            dlR[dlW] = eL * 0.42f
            dlW++; if (dlW >= dlL.size) dlW = 0
            l += ps * 0.5f + eL * 0.6f
            r += ps * 0.5f + eR * 0.6f
            // etwas Raum für alles
            val rv = plate.tick((l + r) * 0.25f, 0.55f, 0.5f, 0.8f)
            l += rv * 0.5f; r += rv * 0.5f
            val g = level * vol * 0.9f
            oL[i] += tanh(l * g * 1.3f) / 1.3f
            oR[i] += tanh(r * g * 1.3f) / 1.3f
        }
        for (k in 0..3) laneFlash[k] *= 0.9f
        if (!on) for (k in 0..3) { lastStep[k] = Long.MIN_VALUE; laneStep[k] = -1 }
    }
}
