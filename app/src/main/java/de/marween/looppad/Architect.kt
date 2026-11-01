package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🧬 RHYTHM ARCHITECT – Polyrhythmus- & Textur-Synth.
 * Vier Spuren mit eigener Länge (z. B. 5, 7, 12, 16 Schritte → Muster, die sich ständig verschieben), Wahrscheinlichkeit
 * je Schritt und Micro-Jitter. Jeder Schlag besteht aus drei Schichten:
 *  - IMPACT: Sinus mit Pitch-Hüllkurve + FM (Kick-Klick, Snare-Ping, metallischer Anschlag)
 *  - BODY: Modal-Synthese (5 Resonatoren: Fell ↔ Holz ↔ Metall)
 *  - TAIL: körniges, gefiltertes Rauschen (Atmen, Hallfahne, Knistern, Shaker)
 * XY: X = weich/texturiert ↔ hart/knackig, Y = gerader Beat ↔ gebrochene Micro-Rhythmen (Geister-Schläge, Ratschen, 1/32-Versatz).
 * Makros: SNAP, MATERIAL (Holz ↔ Metall), DECAY/AIR, CHAOS/SWING.
 */
object Architect {
    @Volatile var on = false
    const val T = 4

    class Type(
        val name: String, val f0: Float,
        val imp: Float, val pAmt: Float, val pTau: Float, val iTau: Float, val fm: Float,
        val body: Float, val bDec: Float, val mat: Float,
        val tail: Float, val tDec: Float, val tHz: Float, val tQ: Float, val grain: Float, val tonal: Boolean
    )
    val TYPES = listOf(
        Type("KICK", 50f, 1f, 3.5f, 0.03f, 0.2f, 0f, 0.45f, 0.35f, 0f, 0.06f, 0.04f, 400f, 0.5f, 0.1f, true),
        Type("SNARE", 185f, 0.6f, 1.2f, 0.012f, 0.03f, 0f, 0.55f, 0.14f, 0.12f, 0.85f, 0.17f, 3800f, 0.35f, 0.35f, false),
        Type("TOM", 105f, 0.6f, 1.4f, 0.04f, 0.12f, 0f, 0.8f, 0.4f, 0.02f, 0.1f, 0.06f, 900f, 0.5f, 0.2f, true),
        Type("CONGA", 230f, 0.5f, 0.6f, 0.015f, 0.04f, 0f, 0.9f, 0.25f, 0.15f, 0.12f, 0.04f, 1800f, 0.5f, 0.2f, true),
        Type("RIM", 480f, 0.8f, 0.3f, 0.004f, 0.006f, 0.6f, 0.7f, 0.06f, 0.5f, 0.06f, 0.02f, 2600f, 0.4f, 0f, true),
        Type("HOLZ", 820f, 0.6f, 0.15f, 0.003f, 0.005f, 0f, 0.9f, 0.12f, 0.5f, 0.04f, 0.02f, 3000f, 0.5f, 0f, true),
        Type("CLAP", 1100f, 0.25f, 0f, 0.01f, 0.004f, 0f, 0.1f, 0.05f, 0.3f, 2.2f, 0.2f, 1300f, 0.55f, 0.85f, false),
        Type("HAT", 3200f, 0.45f, 0f, 0.01f, 0.003f, 1.2f, 0.35f, 0.05f, 1f, 0.9f, 0.06f, 8000f, 0.3f, 0.2f, false),
        Type("GLOCKE", 620f, 0.4f, 0f, 0.01f, 0.01f, 0.8f, 1f, 1.2f, 1f, 0.08f, 0.1f, 4000f, 0.4f, 0.1f, true),
        Type("SHAKER", 5000f, 0f, 0f, 0.01f, 0.01f, 0f, 0f, 0.05f, 0.5f, 1f, 0.09f, 6000f, 0.3f, 0.8f, false),
        Type("ATEM", 1200f, 0f, 0f, 0.01f, 0.01f, 0f, 0f, 0.05f, 0.5f, 1f, 0.6f, 900f, 0.2f, 0.6f, false),
        Type("KNISTERN", 2000f, 0f, 0f, 0.01f, 0.01f, 0f, 0f, 0.05f, 0.5f, 1f, 0.45f, 2500f, 0.35f, 1f, false)
    )

    // ---- Spuren
    val type = intArrayOf(0, 3, 4, 9)
    val len = intArrayOf(16, 12, 5, 7)
    /** 0 = aus, 1..4 = Wahrscheinlichkeit 25/50/75/100 %. */
    val steps = Array(T) { IntArray(16) }
    val acc = Array(T) { BooleanArray(16) }
    val tune = IntArray(T)
    val level = floatArrayOf(0.9f, 0.8f, 0.75f, 0.7f)
    val mute = BooleanArray(T)
    private val PAN = floatArrayOf(0f, -0.25f, 0.3f, 0.15f)

    // ---- Makros & XY
    @Volatile var snap = 0.55f
    @Volatile var material = 0.5f
    @Volatile var air = 0.45f
    @Volatile var chaos = 0.25f
    @Volatile var x = 0.6f
    @Volatile var y = 0.3f
    @Volatile var vol = 0.8f
    @Volatile var pitchTrack = true
    @Volatile var presetIdx = -1

    // ---- Anzeige
    val pos = IntArray(T) { -1 }
    val flash = FloatArray(T)
    @Volatile var meter = 0f
    @Volatile var rootShown = -1

    class Preset(val name: String, val types: IntArray, val lens: IntArray, val hits: IntArray, val rot: IntArray,
                 val snap: Float, val mat: Float, val air: Float, val chaos: Float, val x: Float, val y: Float)
    val PRESETS = listOf(
        Preset("Afro-Polyrhythmus", intArrayOf(0, 3, 4, 9), intArrayOf(16, 12, 5, 7), intArrayOf(4, 5, 2, 5), intArrayOf(0, 2, 1, 0), 0.5f, 0.35f, 0.4f, 0.35f, 0.6f, 0.3f),
        Preset("Organic Techno", intArrayOf(0, 6, 7, 10), intArrayOf(16, 16, 12, 7), intArrayOf(4, 2, 7, 2), intArrayOf(0, 4, 1, 3), 0.65f, 0.6f, 0.5f, 0.15f, 0.7f, 0.25f),
        Preset("IDM-Micro", intArrayOf(0, 4, 7, 11), intArrayOf(13, 11, 7, 16), intArrayOf(4, 5, 4, 9), intArrayOf(0, 3, 2, 0), 0.75f, 0.7f, 0.35f, 0.5f, 0.75f, 0.75f),
        Preset("Hand-Percussion", intArrayOf(3, 2, 5, 9), intArrayOf(16, 12, 5, 16), intArrayOf(6, 3, 2, 12), intArrayOf(0, 2, 3, 0), 0.4f, 0.3f, 0.45f, 0.4f, 0.55f, 0.35f),
        Preset("Metall-Mechanik", intArrayOf(0, 8, 7, 4), intArrayOf(16, 7, 5, 9), intArrayOf(4, 3, 4, 4), intArrayOf(0, 1, 0, 2), 0.7f, 0.9f, 0.4f, 0.05f, 0.8f, 0.2f),
        Preset("Atmende Textur", intArrayOf(2, 10, 11, 8), intArrayOf(16, 9, 5, 11), intArrayOf(3, 2, 3, 2), intArrayOf(0, 4, 1, 5), 0.3f, 0.5f, 0.85f, 0.3f, 0.2f, 0.4f),
        Preset("Gebrochener Breakbeat", intArrayOf(0, 1, 7, 3), intArrayOf(16, 16, 16, 7), intArrayOf(3, 2, 10, 3), intArrayOf(0, 4, 0, 1), 0.6f, 0.4f, 0.4f, 0.3f, 0.65f, 0.5f),
        Preset("Holz & Fell", intArrayOf(5, 3, 2, 9), intArrayOf(12, 16, 10, 8), intArrayOf(5, 7, 3, 6), intArrayOf(0, 0, 2, 1), 0.5f, 0.45f, 0.5f, 0.45f, 0.5f, 0.35f)
    )

    private val rnd = java.util.Random()

    /** Euklidische Verteilung: [hits] Schläge auf [n] Schritte, um [rot] gedreht; erster Schlag betont. */
    fun fillEuclid(t: Int, hits: Int, n: Int, rot: Int) {
        len[t] = n.coerceIn(2, 16)
        java.util.Arrays.fill(steps[t], 0); java.util.Arrays.fill(acc[t], false)
        val h = hits.coerceIn(0, len[t])
        var first = true
        for (k in 0 until len[t]) {
            val kk = Math.floorMod(k - rot, len[t])
            if ((kk * h) % len[t] < h) {
                steps[t][k] = if (first || rnd.nextFloat() < 0.65f) 4 else 3
                acc[t][k] = first
                first = false
            }
        }
    }

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        for (t in 0 until T) { type[t] = p.types[t]; tune[t] = 0; mute[t] = false; fillEuclid(t, p.hits[t], p.lens[t], p.rot[t]) }
        snap = p.snap; material = p.mat; air = p.air; chaos = p.chaos; x = p.x; y = p.y
    }

    /** 🎲 Neue Muster (Klänge bleiben): Längen und Schläge neu, Wahrscheinlichkeiten gemischt. */
    fun reroll() {
        val lens = intArrayOf(5, 7, 9, 11, 12, 13, 16)
        for (t in 0 until T) {
            val n = if (t == 0) (if (rnd.nextFloat() < 0.7f) 16 else 12) else lens[rnd.nextInt(lens.size)]
            val hits = max(1, (n * (0.2f + rnd.nextFloat() * (if (TYPES[type[t]].name == "SHAKER" || TYPES[type[t]].name == "HAT") 0.5f else 0.3f))).roundToInt())
            fillEuclid(t, hits, n, rnd.nextInt(n))
            for (k in 0 until len[t]) if (steps[t][k] > 0 && !acc[t][k] && rnd.nextFloat() < 0.35f) steps[t][k] = 1 + rnd.nextInt(3)
        }
        presetIdx = -1
    }

    // ---- Vorhören (aus der Oberfläche)
    private val previewQ = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    fun preview(t: Int) { previewQ.add(t) }

    // ---------------------------------------------------------------- Stimmen
    private class Voice {
        var active = false
        var t = 0
        var delay = 0
        var track = 0
        var vel = 1f
        var f0 = 100.0
        var imp = 0f; var pAmt = 0f; var pDec = 0f; var iDec = 0f; var fm = 0f
        var iEnv = 0f; var pEnv = 0f
        var ph = 0.0; var mph = 0.0
        val a1 = DoubleArray(5); val a2 = DoubleArray(5); val g = DoubleArray(5); val y1 = DoubleArray(5); val y2 = DoubleArray(5)
        var nModes = 0
        var bodyLv = 0f
        var exc = 0          // Restlänge des Rausch-Anregungsstoßes
        var tail = 0f; var tEnv = 0f; var tDec = 0f; var tg = 0f
        var lp = 0f; var bp = 0f; var fq = 0f; var qq = 0f
        var grain = 0f; var gAmp = 1f; var gTgt = 1f; var gCnt = 0
        var clap = false; var crackle = false
        var panL = 1f; var panR = 1f
        var r = 12345
        fun noise(): Float { r = r * 1664525 + 1013904223; return ((r ushr 8) / 16777216f) * 2f - 1f }
    }
    private val voices = Array(16) { Voice() }

    private val MEM = doubleArrayOf(1.0, 1.59, 2.14, 2.30, 2.65)
    private val WOOD = doubleArrayOf(1.0, 2.76, 5.40, 8.93, 13.34)
    private val METAL = doubleArrayOf(1.0, 1.47, 2.09, 2.56, 3.52)
    private val A_MEM = doubleArrayOf(1.0, 0.6, 0.45, 0.35, 0.25)
    private val A_WOOD = doubleArrayOf(1.0, 0.4, 0.2, 0.1, 0.05)
    private val A_MET = doubleArrayOf(0.8, 0.55, 0.45, 0.35, 0.3)
    private val D_MEM = doubleArrayOf(1.0, 0.7, 0.5, 0.45, 0.4)
    private val D_WOOD = doubleArrayOf(1.0, 0.35, 0.2, 0.12, 0.08)
    private val D_MET = doubleArrayOf(1.0, 0.9, 0.8, 0.75, 0.7)

    private fun lerp3(m: Double, a: DoubleArray, b: DoubleArray, c: DoubleArray, i: Int): Double =
        if (m < 0.5) a[i] + (b[i] - a[i]) * m * 2 else b[i] + (c[i] - b[i]) * (m - 0.5) * 2

    /** Grundton (Tonhöhenklasse) zum Mitstimmen: Deck → Ghost → Tonleiter → Groove-Generator, −1 = keiner. */
    private fun rootPc(): Int {
        for (d in Dj.decks) if (d.loaded() && d.playing && d.key >= 0) return Keys.root(d.effKey())
        if (Ghost.curKey >= 0) return Keys.root(Ghost.curKey)
        if (InstState.scaleType != 0) return InstState.scaleRoot
        if (GrooveGen.made) return GrooveGen.root
        return -1
    }

    private fun trigger(tr: Int, vel: Float, delay: Int, sr: Int, root: Int) {
        val ty = TYPES[type[tr].coerceIn(0, TYPES.size - 1)]
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.maxByOrNull { it.t }!!
        v.active = true; v.t = 0; v.delay = delay; v.track = tr; v.vel = vel
        var f = ty.f0 * 2.0.pow(tune[tr] / 12.0)
        if (pitchTrack && ty.tonal && root >= 0) {
            // auf Grundton oder Quinte der Tonart ziehen
            val m = 69 + 12 * ln(f / 440.0) / ln(2.0)
            var best = m; var bd = 99.0
            for (n in (m - 7).toInt()..(m + 7).toInt()) {
                val pc = Math.floorMod(n - root, 12)
                if (pc != 0 && pc != 7) continue
                val dd = abs(n - m)
                if (dd < bd) { bd = dd; best = n.toDouble() }
            }
            f = 440.0 * 2.0.pow((best - 69) / 12.0)
        }
        v.f0 = f
        val hard = x
        val sn = snap
        // IMPACT
        v.imp = ty.imp * (0.3f + 1.4f * hard) * (0.4f + 1.2f * sn)
        v.pAmt = ty.pAmt * (0.4f + 1.2f * sn)
        v.pDec = exp(-1.0 / (ty.pTau * (1.4 - sn) * sr)).toFloat()
        v.iDec = exp(-1.0 / (ty.iTau * (1.3 - 0.6 * sn) * sr)).toFloat()
        v.fm = ty.fm * (0.5f + material)
        v.iEnv = 1f; v.pEnv = 1f; v.ph = 0.0; v.mph = 0.0
        // BODY: Material aus Typ + Makro
        val mat = (ty.mat + (material - 0.5f) * 1.2f).coerceIn(0f, 1f).toDouble()
        val dec = ty.bDec * (0.4 + 1.6 * air) * (1.2 - 0.4 * hard)
        v.nModes = 0
        for (k in 0 until 5) {
            val fr = f * lerp3(mat, MEM, WOOD, METAL, k)
            if (fr > sr * 0.45) break
            val w = 2 * PI * fr / sr
            val tk = dec * lerp3(mat, D_MEM, D_WOOD, D_MET, k)
            val r = exp(-1.0 / (tk * sr))
            v.a1[k] = 2 * r * cos(w); v.a2[k] = -r * r
            v.g[k] = lerp3(mat, A_MEM, A_WOOD, A_MET, k) * sin(w)
            v.y1[k] = 0.0; v.y2[k] = 0.0
            v.nModes++
        }
        v.bodyLv = ty.body
        v.exc = (sr * (0.0015 + 0.003 * (1 - sn))).toInt()
        // TAIL
        v.tail = ty.tail * (1.5f - hard) * (0.6f + 0.8f * air)
        v.tDec = exp(-1.0 / (ty.tDec * (0.4 + 2.2 * air) * sr)).toFloat()
        v.tEnv = 1f
        v.fq = (2 * sin(PI * min(ty.tHz.toDouble(), sr / 6.0) / sr)).toFloat()
        v.qq = 1f - ty.tQ * 0.9f
        v.lp = 0f; v.bp = 0f
        v.grain = (ty.grain + (1f - hard) * 0.3f).coerceIn(0f, 1f)
        v.gAmp = 1f; v.gTgt = 1f; v.gCnt = 0
        v.clap = ty.name == "CLAP"; v.crackle = ty.name == "KNISTERN"
        val p = PAN[tr]
        v.panL = min(1f, 1f - p); v.panR = min(1f, 1f + p)
        flash[tr] = 1f
    }

    // ---------------------------------------------------------------- Raster & Klang
    private val lastStep = LongArray(T) { Long.MIN_VALUE }
    private var lastG = Long.MIN_VALUE

    fun active(): Boolean = on || voices.any { it.active }

    /** [ms]/[ml] = Raster-Start und Taktlänge (Ausgabe-Proben), [w] = Ausgabe-Frame des Blocks. */
    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w: Long, ms: Long, ml: Long) {
        val bar = if (ml > 0L) ml.toDouble() else sr * 2.0
        val stepLen = bar / 16.0
        val root = rootPc()
        rootShown = root
        while (true) { val t = previewQ.poll() ?: break; trigger(t, 1f, 0, sr, root) }
        var pk = 0f
        for (i in 0 until n) {
            val f = w + i
            if (on) {
                val g = floor((f - ms) / stepLen).toLong()
                if (g != lastG) {
                    val first = lastG == Long.MIN_VALUE
                    lastG = g
                    if (!first) stepHit(g, stepLen, sr, root)
                }
            }
            var l = 0f; var r = 0f
            for (v in voices) {
                if (!v.active) continue
                if (v.delay > 0) { v.delay--; continue }
                val s = voiceSample(v, sr)
                l += s * v.panL; r += s * v.panR
            }
            val gl = vol * 0.55f
            val yl = tanh((l * gl).toDouble()).toFloat()
            val yr = tanh((r * gl).toDouble()).toFloat()
            oL[i] += yl; oR[i] += yr
            val a = abs(yl)
            if (a > pk) pk = a
        }
        meter = max(pk, meter * 0.9f)
        for (t in 0 until T) flash[t] *= 0.85f
        if (!on) { lastG = Long.MIN_VALUE; for (t in 0 until T) pos[t] = -1 }
    }

    private var swingNow = 0.0
    private fun stepHit(g: Long, stepLen: Double, sr: Int, root: Int) {
        swingNow = Engine.swingFrames().toDouble()
        val ch = chaos
        val dens = y
        for (t in 0 until T) {
            val n = len[t].coerceIn(1, 16)
            val k = Math.floorMod(g, n.toLong()).toInt()
            pos[t] = k
            if (mute[t]) continue
            val sv = steps[t][k]
            var vel = 0f
            if (sv > 0) {
                // Wahrscheinlichkeit; CHAOS lockert sie zusätzlich
                val p = sv / 4f * (1f - ch * 0.25f * (if (acc[t][k]) 0f else 1f))
                if (rnd.nextFloat() < p) vel = if (acc[t][k]) 1f else 0.72f + rnd.nextFloat() * 0.15f
            } else if (rnd.nextFloat() < dens * dens * 0.35f) vel = 0.3f + rnd.nextFloat() * 0.2f   // Geister-Schlag
            if (vel <= 0f) continue
            vel *= level[t]
            // Swing (ungerade 16tel später, bis „Afro-Cuban“), Human-Jitter, bei hoher Dichte 1/32-Versatz
            var d = 0.0
            if (g % 2L != 0L) d += stepLen * 0.42 * ch + swingNow   // eigener Chaos-Swing + Sequenzer-Swing (groovt mit der 909)
            d += (rnd.nextGaussian() * 0.006 * ch * sr).coerceIn(-0.4 * stepLen, 0.4 * stepLen)
            if (dens > 0.6f && rnd.nextFloat() < (dens - 0.6f) * 0.8f) d += stepLen * 0.5
            val di = max(0, d.toInt())
            trigger(t, vel, di, sr, root)
            // Ratschen (Wirbel) mit hoher Dichte
            if (rnd.nextFloat() < dens * 0.22f) {
                val parts = if (dens > 0.7f && rnd.nextBoolean()) 3 else 2
                for (q in 1 until parts) trigger(t, vel * (0.75f - 0.1f * q), di + (stepLen * q / parts).toInt(), sr, root)
            }
        }
    }

    private fun voiceSample(v: Voice, sr: Int): Float {
        val tt = v.t
        v.t++
        var out = 0f
        // IMPACT
        if (v.imp > 0f && v.iEnv > 1e-4f) {
            val fr = v.f0 * (1.0 + v.pAmt * v.pEnv)
            v.pEnv *= v.pDec
            v.ph += fr / sr; if (v.ph >= 1.0) v.ph -= 1.0
            var s: Double
            if (v.fm > 0f) {
                v.mph += fr * 3.71 / sr; if (v.mph >= 1.0) v.mph -= 1.0
                s = sin(2 * PI * v.ph + v.fm * 2.5 * v.iEnv * sin(2 * PI * v.mph))
            } else s = sin(2 * PI * v.ph)
            out += (s * v.iEnv * v.imp).toFloat()
            if (tt < sr / 2000) out += v.noise() * 0.5f * v.imp * snap
            v.iEnv *= v.iDec
        }
        // BODY
        var bodyActive = false
        if (v.bodyLv > 0f && v.nModes > 0) {
            val x = (if (tt == 0) 1.0 else 0.0) + (if (v.exc > 0) { v.exc--; v.noise() * 0.25 } else 0.0)
            var b = 0.0
            for (k in 0 until v.nModes) {
                val yk = v.a1[k] * v.y1[k] + v.a2[k] * v.y2[k] + x * v.g[k]
                v.y2[k] = v.y1[k]; v.y1[k] = yk
                b += yk
            }
            out += (b * v.bodyLv * 0.4).toFloat()
            if (abs(v.y1[0]) > 1e-4 || tt < sr / 50) bodyActive = true
        }
        // TAIL: körniges Bandpass-Rauschen
        if (v.tail > 0f && v.tEnv > 1e-4f) {
            var nz = v.noise()
            if (v.crackle) nz = if (rnd.nextFloat() < 0.004f) v.noise() * 4f else nz * 0.04f
            v.lp += v.fq * v.bp
            val hp = nz - v.lp - v.qq * v.bp
            v.bp += v.fq * hp
            if (v.gCnt <= 0) {
                v.gCnt = (sr * (0.004 + 0.02 * rnd.nextFloat())).toInt()
                v.gTgt = (1f - v.grain) + v.grain * rnd.nextFloat() * rnd.nextFloat() * 2f
            }
            v.gCnt--
            v.gAmp += (v.gTgt - v.gAmp) * 0.01f
            var e = v.tEnv
            if (v.clap && tt < sr * 0.03) e *= 1f - ((tt % (sr / 100)).toFloat() / (sr / 100)) * 0.8f
            out += v.bp * e * v.gAmp * v.tail * 0.9f
            v.tEnv *= v.tDec
        }
        if (v.iEnv <= 1e-4f && !bodyActive && v.tEnv <= 1e-4f && tt > sr / 50) v.active = false
        return out * v.vel
    }
}
