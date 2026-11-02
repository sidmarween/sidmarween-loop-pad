package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 🪐 ORBITS – Gravitations-Synth: Rhythmus aus Umlaufbahnen statt Raster.
 *  - KERN in der Mitte: Kick oder Sub-Puls auf jedem Schlag.
 *  - SATELLITEN auf elliptischen Kepler-Bahnen (Periode in Schlägen, Exzentrizität, Richtung) – sie lösen ihren Klang aus,
 *    sobald sie die Trigger-Linie (nach oben) kreuzen. Nahe am Kern fliegen sie schneller → hellerer FM-Anschlag.
 *  - KOLLISION: kommen sich zwei zu nahe → Pitch-Drop-Crash, Filter-Sweep oder Bitcrush-Burst.
 *  - GRAVITY WELLS: Brunnen beschleunigen (Wirbel) oder bremsen; beim Herausschleudern gibt es einen Akzent.
 *  - STARDUST: jeder Schlag zieht einen körnigen Schweif hinter sich her.
 */
object Orbits {
    @Volatile var on = false
    const val MAX_SAT = 6
    const val MAX_WELL = 4

    class SType(val name: String, val f: Float, val ratio: Float, val index: Float, val dec: Float, val noise: Float, val bp: Float, val tonal: Boolean)
    val TYPES = listOf(
        SType("HAT", 5200f, 1.41f, 3f, 0.05f, 0.7f, 8000f, false),
        SType("KLICK", 1800f, 2.3f, 2f, 0.015f, 0.2f, 3000f, false),
        SType("SHAKER", 4000f, 1f, 0.5f, 0.07f, 1f, 6000f, false),
        SType("NOISE", 900f, 1f, 0.5f, 0.12f, 1f, 1400f, false),
        SType("BLIP", 440f, 2f, 1.5f, 0.12f, 0f, 2000f, true),
        SType("METALL", 700f, 3.71f, 4f, 0.35f, 0.1f, 4000f, true),
        SType("TOM", 140f, 1f, 1.2f, 0.25f, 0.05f, 800f, true)
    )
    val CORE_NAMES = arrayOf("KICK", "SUB-PULS", "AUS")

    class Sat {
        @Volatile var active = false
        @Volatile var type = 0
        @Volatile var period = 2f      // Schläge pro Umlauf
        @Volatile var a = 0.6f         // große Halbachse (0..1)
        @Volatile var e = 0.3f         // Exzentrizität
        @Volatile var omega = 0f       // Lage der Ellipse
        @Volatile var dir = 1
        @Volatile var tune = 0
        var m = 0.0                    // mittlere Anomalie
        var prevPhi = -1.0
        var inWell = false
        var accent = false
        // für die Anzeige
        @Volatile var x = 0f
        @Volatile var y = 0f
        @Volatile var flash = 0f
        @Volatile var speed = 1f
    }
    val sats = Array(MAX_SAT) { Sat() }

    class Well { @Volatile var active = false; @Volatile var x = 0f; @Volatile var y = 0f; @Volatile var pull = true }
    val wells = Array(MAX_WELL) { Well() }

    @Volatile var core = 0
    @Volatile var stardust = 0.45f
    @Volatile var collision = 0.5f
    @Volatile var gravity = 0.6f
    @Volatile var vol = 0.8f
    @Volatile var presetIdx = -1
    @Volatile var wellPull = true

    // Anzeige
    @Volatile var coreFlash = 0f
    @Volatile var meter = 0f
    /** Letzte Kollision: Ort und Stärke (für die Anzeige). */
    @Volatile var colX = 0f
    @Volatile var colY = 0f
    @Volatile var colFlash = 0f
    @Volatile var colName = ""

    class Preset(val name: String, val core: Int, val sats: List<FloatArray>, val wells: List<FloatArray> = emptyList(), val stardust: Float = 0.45f)
    // Satellit: type, period, a, e, omega, dir
    val PRESETS = listOf(
        Preset("Sonnensystem", 0, listOf(floatArrayOf(0f, 1f, 0.35f, 0.1f, 0f, 1f), floatArrayOf(2f, 1.5f, 0.5f, 0.3f, 1f, 1f), floatArrayOf(1f, 2.25f, 0.65f, 0.5f, 2f, 1f), floatArrayOf(4f, 3f, 0.8f, 0.2f, 4f, -1f), floatArrayOf(5f, 4.5f, 0.92f, 0.55f, 5f, 1f))),
        Preset("Techno-Umlauf", 0, listOf(floatArrayOf(0f, 0.5f, 0.3f, 0f, 0f, 1f), floatArrayOf(2f, 1f, 0.5f, 0.1f, 3f, 1f), floatArrayOf(3f, 2f, 0.7f, 0.2f, 1.5f, 1f), floatArrayOf(1f, 1.5f, 0.85f, 0.4f, 4f, -1f))),
        Preset("Asteroidengürtel", 1, listOf(floatArrayOf(2f, 0.75f, 0.45f, 0.6f, 0f, 1f), floatArrayOf(1f, 1.25f, 0.55f, 0.7f, 2f, -1f), floatArrayOf(0f, 1.75f, 0.62f, 0.5f, 4f, 1f), floatArrayOf(1f, 2.5f, 0.75f, 0.75f, 1f, 1f), floatArrayOf(2f, 0.6f, 0.4f, 0.4f, 5f, -1f)), stardust = 0.3f),
        Preset("Langsame Monde", 1, listOf(floatArrayOf(4f, 3f, 0.5f, 0.2f, 0f, 1f), floatArrayOf(5f, 4.5f, 0.7f, 0.35f, 2f, 1f), floatArrayOf(6f, 6f, 0.85f, 0.5f, 4f, -1f), floatArrayOf(2f, 2f, 0.4f, 0.1f, 1f, 1f)), stardust = 0.85f),
        Preset("Chaos-Nebel", 2, listOf(floatArrayOf(1f, 1.33f, 0.4f, 0.7f, 0f, 1f), floatArrayOf(3f, 2.71f, 0.6f, 0.6f, 2f, -1f), floatArrayOf(5f, 3.14f, 0.8f, 0.5f, 4f, 1f), floatArrayOf(2f, 1.62f, 0.5f, 0.8f, 1f, 1f), floatArrayOf(0f, 4.2f, 0.9f, 0.65f, 3f, -1f)),
            listOf(floatArrayOf(0.45f, -0.3f, 1f), floatArrayOf(-0.5f, 0.4f, 0f)), 0.6f),
        Preset("Doppelstern", 0, listOf(floatArrayOf(4f, 2f, 0.6f, 0.15f, 0f, 1f), floatArrayOf(4f, 2.5f, 0.62f, 0.15f, 3.1f, -1f), floatArrayOf(0f, 1f, 0.35f, 0.2f, 1f, 1f), floatArrayOf(6f, 4f, 0.85f, 0.3f, 2f, 1f)))
    )

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        core = p.core
        stardust = p.stardust
        for (s in sats) s.active = false
        for ((j, v) in p.sats.withIndex()) if (j < MAX_SAT) sats[j].apply {
            type = v[0].toInt(); period = v[1]; a = v[2]; e = v[3]; omega = v[4]; dir = if (v[5] < 0) -1 else 1; tune = 0
            m = 0.0; prevPhi = -1.0; inWell = false; accent = false; active = true
        }
        for (w in wells) w.active = false
        for ((j, v) in p.wells.withIndex()) if (j < MAX_WELL) wells[j].apply { x = v[0]; y = v[1]; pull = v[2] > 0.5f; active = true }
    }

    private val rnd = java.util.Random()

    /** 🎲 Neue Bahnen (Klänge bleiben). */
    fun reroll() {
        val periods = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.25f, 3f, 4.5f, 1.33f, 2.71f, 3.14f)
        for ((j, s) in sats.withIndex()) if (s.active) {
            s.period = periods[rnd.nextInt(periods.size)]
            s.a = 0.3f + 0.65f * (j + rnd.nextFloat()) / MAX_SAT
            s.e = rnd.nextFloat() * 0.75f
            s.omega = (rnd.nextFloat() * 2 * PI).toFloat()
            s.dir = if (rnd.nextFloat() < 0.75f) 1 else -1
        }
        presetIdx = -1
    }

    /** Neuen Satelliten einsetzen; gibt den Platz zurück (−1 = voll). */
    fun addSat(): Int {
        val j = sats.indexOfFirst { !it.active }
        if (j < 0) return -1
        sats[j].apply {
            type = rnd.nextInt(TYPES.size); period = floatArrayOf(1f, 1.5f, 2f, 3f)[rnd.nextInt(4)]
            a = 0.35f + 0.6f * rnd.nextFloat(); e = rnd.nextFloat() * 0.5f; omega = (rnd.nextFloat() * 2 * PI).toFloat(); dir = 1
            m = 0.0; prevPhi = -1.0; active = true
        }
        presetIdx = -1
        return j
    }

    /** Alle Bahnen wieder auf Anfang (nächster Schlag). */
    @Volatile private var resetReq = false
    fun sync() { resetReq = true }

    // ---------------------------------------------------------------- Bahn-Rechnung
    /** Position nach Kepler: Ort (x, y) und Winkel. */
    /** Position für die Anzeige berechnen (auch wenn ORBITS nicht läuft). */
    fun placeNow(s: Sat) { place(s) }

    private fun place(s: Sat): Double {
        val e = s.e.toDouble().coerceIn(0.0, 0.9)
        val mm = s.m
        var ee = mm + e * sin(mm)
        for (k in 0 until 4) ee -= (ee - e * sin(ee) - mm) / (1 - e * cos(ee))
        val nu = 2 * atan2(sqrt(1 + e) * sin(ee / 2), sqrt(1 - e) * cos(ee / 2))
        val r = s.a * (1 - e * cos(ee))
        val th = s.dir * nu + s.omega
        s.x = (r * cos(th)).toFloat()
        s.y = (r * sin(th)).toFloat()
        return th
    }

    // ---------------------------------------------------------------- Stimmen
    private class Voice {
        var active = false
        var delay = 0
        var t = 0
        var f = 440.0; var ratio = 1.0; var index = 1.0
        var env = 0f; var dec = 0f; var iEnv = 0f; var iDec = 0f
        var ph = 0.0; var mph = 0.0
        var noise = 0f
        var lp = 0f; var bp = 0f; var fq = 0f; var qq = 0.5f
        var tail = 0f; var tDec = 0f; var gAmp = 0f; var gTgt = 0f; var gCnt = 0; var tf = 0f; var tl = 0f; var tb = 0f
        var vel = 1f
        var panL = 1f; var panR = 1f
        var kick = false; var sub = false; var crash = false
        var r = 777
        fun nz(): Float { r = r * 1664525 + 1013904223; return ((r ushr 8) / 16777216f) * 2f - 1f }
    }
    private val voices = Array(24) { Voice() }

    private fun rootPc(): Int {
        for (d in Dj.decks) if (d.loaded() && d.playing && d.key >= 0) return Keys.root(d.effKey())
        if (Ghost.curKey >= 0) return Keys.root(Ghost.curKey)
        if (InstState.scaleType != 0) return InstState.scaleRoot
        if (GrooveGen.made) return GrooveGen.root
        return -1
    }

    private fun voice(): Voice {
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.maxByOrNull { it.t }!!
        v.active = true; v.t = 0; v.ph = 0.0; v.mph = 0.0; v.lp = 0f; v.bp = 0f
        v.kick = false; v.sub = false; v.crash = false
        return v
    }

    private fun trigSat(s: Sat, delay: Int, vel: Float, bright: Float, sr: Int, root: Int) {
        val ty = TYPES[s.type.coerceIn(0, TYPES.size - 1)]
        val v = voice()
        v.delay = delay; v.vel = vel
        var f = ty.f * 2.0.pow(s.tune / 12.0)
        if (ty.tonal && root >= 0) {
            val mm = 69 + 12 * ln(f / 440.0) / ln(2.0)
            var best = mm; var bd = 99.0
            for (n in (mm - 6).toInt()..(mm + 6).toInt()) {
                val pc = Math.floorMod(n - root, 12)
                if (pc != 0 && pc != 7 && pc != 3 && pc != 10) continue
                if (abs(n - mm) < bd) { bd = abs(n - mm); best = n.toDouble() }
            }
            f = 440.0 * 2.0.pow((best - 69) / 12.0)
        }
        v.f = f
        // FM-Verhältnis und Index wachsen mit der Bahngeschwindigkeit
        v.ratio = ty.ratio * (1.0 + bright * 1.5)
        v.index = ty.index * (0.5 + bright * 2.5)
        v.env = 1f
        v.dec = exp(-1.0 / (ty.dec * (1.3 - 0.6 * bright) * sr)).toFloat()
        v.iEnv = 1f; v.iDec = exp(-1.0 / (0.03 * sr)).toFloat()
        v.noise = ty.noise
        v.fq = (2 * sin(PI * min(ty.bp * (0.7 + bright * 0.8), sr / 6.0) / sr)).toFloat(); v.qq = 0.5f
        // Stardust-Schweif
        v.tail = stardust * stardust * 0.6f
        v.tDec = exp(-1.0 / ((0.08 + stardust * 2.2) * sr)).toFloat()
        v.tf = (2 * sin(PI * min(ty.bp * 1.2, sr / 6.0) / sr)).toFloat(); v.tl = 0f; v.tb = 0f
        v.gAmp = 0f; v.gTgt = 0f; v.gCnt = 0
        val p = s.x.coerceIn(-1f, 1f) * 0.8f
        v.panL = min(1f, 1f - p); v.panR = min(1f, 1f + p)
        s.flash = 1f
    }

    private fun trigCore(delay: Int, sr: Int) {
        val c = core
        if (c == 2) return
        val v = voice()
        v.delay = delay; v.vel = 1f
        v.kick = c == 0; v.sub = c == 1
        v.f = if (c == 0) 48.0 else 42.0
        v.env = 1f
        v.dec = exp(-1.0 / ((if (c == 0) 0.32 else 0.5) * sr)).toFloat()
        v.iEnv = 1f; v.iDec = exp(-1.0 / ((if (c == 0) 0.035 else 0.012) * sr)).toFloat()
        v.tail = 0f; v.noise = 0f
        v.panL = 1f; v.panR = 1f
        coreFlash = 1f
    }

    private fun trigCrash(kind: Int, x: Float, sr: Int) {
        if (kind != 0) return
        val v = voice()
        v.crash = true; v.delay = qDelay(0); v.vel = 0.6f + collision * 0.5f
        v.f = 900.0; v.env = 1f; v.dec = exp(-1.0 / (0.35 * sr)).toFloat()
        v.iEnv = 1f; v.iDec = exp(-1.0 / (0.09 * sr)).toFloat()
        v.noise = 0.6f; v.fq = (2 * sin(PI * 2500.0 / sr)).toFloat(); v.qq = 0.4f
        v.tail = 0.3f; v.tDec = exp(-1.0 / (0.6 * sr)).toFloat(); v.tf = (2 * sin(PI * 1500.0 / sr)).toFloat(); v.tl = 0f; v.tb = 0f
        val p = x.coerceIn(-1f, 1f) * 0.8f
        v.panL = min(1f, 1f - p); v.panR = min(1f, 1f + p)
    }

    // ---------------------------------------------------------------- Kollisions-Effekte am Ausgang
    private var fxKind = -1
    private var fxEnv = 0f
    private var fxDec = 0f
    private var fLp = 0f; private var fBp = 0f; private var fLpR = 0f; private var fBpR = 0f
    private var crushL = 0f; private var crushR = 0f; private var crushC = 0
    private val pairCool = IntArray(MAX_SAT * MAX_SAT)

    private fun collide(i: Int, j: Int, sr: Int) {
        val k = rnd.nextInt(3)
        colX = (sats[i].x + sats[j].x) / 2; colY = (sats[i].y + sats[j].y) / 2; colFlash = 1f
        colName = arrayOf("PITCH-DROP", "FILTER-SWEEP", "BITCRUSH")[k]
        trigCrash(if (k == 0) 0 else 1, colX, sr)
        if (k > 0) { fxKind = k; fxEnv = 1f; fxDec = exp(-1.0 / ((0.25 + collision * 0.6) * sr)).toFloat() }
    }

    // ---------------------------------------------------------------- Rendern
    private var lastBeat = Long.MIN_VALUE
    private var upd = 0
    private val UPD = 32

    fun active(): Boolean = on || voices.any { it.active }

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w: Long, ms: Long, ml: Long) {
        val bar = if (ml > 0L) ml.toDouble() else sr * 2.0
        val beat = bar / 4.0
        val root = rootPc()
        var pk = 0f
        for (i in 0 until n) {
            val f = w + i
            if (on) {
                val b = floor((f - ms) / beat).toLong()
                if (b != lastBeat) {
                    val first = lastBeat == Long.MIN_VALUE
                    lastBeat = b
                    if (resetReq || first) { for (s in sats) { s.m = 0.0; s.prevPhi = -1.0 }; resetReq = false }
                    if (!first) trigCore(0, sr)
                }
                if (upd <= 0) { upd = UPD; qNow = f; qMs = ms; qStep = beat / 4.0; orbitStep(beat, sr, root) }
                upd--
            }
            var l = 0f; var r = 0f
            for (v in voices) {
                if (!v.active) continue
                if (v.delay > 0) { v.delay--; continue }
                val s = vSample(v, sr)
                l += s * v.panL; r += s * v.panR
            }
            // Kollisions-Effekt
            if (fxKind > 0 && fxEnv > 0.001f) {
                if (fxKind == 1) {
                    // tiefer Resonanz-Filter, der wieder aufgeht
                    val fc = 120f * 2f.pow(7.5f * (1f - fxEnv))
                    val g = (2 * sin(PI * min(fc.toDouble(), sr / 6.0) / sr)).toFloat()
                    val q = 0.25f
                    fLp += g * fBp; val h = l - fLp - q * fBp; fBp += g * h
                    fLpR += g * fBpR; val hr = r - fLpR - q * fBpR; fBpR += g * hr
                    l = fLp; r = fLpR
                } else {
                    if (crushC <= 0) {
                        val bits = (2 + 6 * (1f - fxEnv)).toInt()
                        val qn = (1 shl bits).toFloat()
                        crushL = Math.round(l * qn) / qn; crushR = Math.round(r * qn) / qn
                        crushC = (2 + 14 * fxEnv).toInt()
                    }
                    crushC--
                    l = crushL; r = crushR
                }
                fxEnv *= fxDec
            } else { fLp = l; fBp = 0f; fLpR = r; fBpR = 0f }
            val gl = vol * 0.3f
            val yl = tanh((l * gl).toDouble()).toFloat()
            val yr = tanh((r * gl).toDouble()).toFloat()
            oL[i] += yl; oR[i] += yr
            if (abs(yl) > pk) pk = abs(yl)
        }
        meter = max(pk, meter * 0.9f)
        coreFlash *= 0.85f; colFlash *= 0.93f
        for (s in sats) s.flash *= 0.85f
        if (!on) lastBeat = Long.MIN_VALUE
    }

    // 🎯 RASTER: Umläufe und Zusammenstöße erklingen auf dem nächsten 16tel (mit Sequenzer-Swing) statt frei
    private var qNow = 0L; private var qMs = 0L; private var qStep = 6000.0
    private fun qDelay(off: Int): Int {
        if (!Engine.tightGrid || qStep < 1.0) return off
        val t = qNow + off
        val k = kotlin.math.ceil((t - qMs) / qStep - 1e-9).toLong()
        var at = qMs + Math.round(k * qStep)
        if (Math.floorMod(k, 2L) == 1L) at += Engine.swingFrames()
        return (at - qNow).toInt().coerceAtLeast(0)
    }

    private fun orbitStep(beat: Double, sr: Int, root: Int) {
        val g = gravity
        for ((idx, s) in sats.withIndex()) {
            if (!s.active) continue
            // Gravity Wells: Abstand → schneller (Wirbel) oder langsamer; Herausschleudern = Akzent
            var sp = 1.0
            var inside = false
            for (wl in wells) {
                if (!wl.active) continue
                val d = hypot((s.x - wl.x).toDouble(), (s.y - wl.y).toDouble())
                val k = exp(-(d / 0.18) * (d / 0.18))
                if (d < 0.18) inside = true
                sp *= if (wl.pull) 1.0 + 4.0 * g * k else max(0.12, 1.0 - 0.9 * g * k)
            }
            if (s.inWell && !inside) s.accent = true
            s.inWell = inside
            val dm = 2 * PI / (s.period.coerceIn(0.25f, 16f) * beat) * UPD * sp
            s.m += dm
            if (s.m > 2 * PI) s.m -= 2 * PI
            val prevX = s.x; val prevY = s.y
            val th = place(s)
            // Geschwindigkeit gegenüber dem Mittel (Kepler: nah am Kern schneller)
            val moved = hypot((s.x - prevX).toDouble(), (s.y - prevY).toDouble())
            val mean = 2 * PI * s.a / (s.period * beat) * UPD
            s.speed = if (mean > 0) (moved / mean).toFloat() else 1f
            // Trigger-Linie: nach oben (Winkel π/2)
            val phi = (th - PI / 2).let { val q = it % (2 * PI); if (q < 0) q + 2 * PI else q }
            val pp = s.prevPhi
            s.prevPhi = phi
            if (pp >= 0 && abs(phi - pp) > PI) {
                val frac = if (s.dir > 0) (2 * PI - pp) / (phi + 2 * PI - pp) else pp / (pp + 2 * PI - phi)
                val off = (frac.coerceIn(0.0, 1.0) * UPD).toInt()
                val bright = ((s.speed - 0.6f) / 2.4f).coerceIn(0f, 1f)
                val vel = if (s.accent) 1f else 0.75f
                trigSat(s, qDelay(off), vel, if (s.accent) 1f else bright, sr, root)
                s.accent = false
            }
        }
        // Kollisionen
        val cr = 0.05 + collision * 0.07
        for (i in 0 until MAX_SAT) for (j in i + 1 until MAX_SAT) {
            val a = sats[i]; val b = sats[j]
            val pc = i * MAX_SAT + j
            if (pairCool[pc] > 0) { pairCool[pc]--; continue }
            if (!a.active || !b.active || collision < 0.02f) continue
            if (hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < cr) {
                collide(i, j, sr)
                pairCool[pc] = (sr * 0.6 / UPD).toInt()
            }
        }
    }

    private fun vSample(v: Voice, sr: Int): Float {
        val tt = v.t
        v.t++
        var out = 0f
        if (v.kick || v.sub) {
            val fr = v.f * (1.0 + (if (v.kick) 3.0 else 0.6) * v.iEnv)
            v.ph += fr / sr; if (v.ph >= 1.0) v.ph -= 1.0
            out = (sin(2 * PI * v.ph) * v.env).toFloat() * (if (v.kick) 1f else 0.85f)
            if (v.kick && tt < sr / 1500) out += v.nz() * 0.3f
            v.env *= v.dec; v.iEnv *= v.iDec
        } else if (v.crash) {
            // Pitch-Drop: Sinus fällt von 900 Hz in den Keller, dazu Rauschen
            val fr = 40.0 + v.f * v.iEnv
            v.ph += fr / sr; if (v.ph >= 1.0) v.ph -= 1.0
            val n = v.nz()
            v.lp += v.fq * v.bp; val h = n - v.lp - v.qq * v.bp; v.bp += v.fq * h
            out = ((sin(2 * PI * v.ph) * 0.8 + v.bp * v.noise) * v.env).toFloat()
            v.env *= v.dec; v.iEnv *= v.iDec
        } else if (v.env > 1e-4f) {
            v.mph += v.f * v.ratio / sr; if (v.mph >= 1.0) v.mph -= 1.0
            v.ph += v.f / sr; if (v.ph >= 1.0) v.ph -= 1.0
            val fm = sin(2 * PI * v.ph + v.index * v.iEnv * sin(2 * PI * v.mph))
            var s = fm * (1f - v.noise)
            if (v.noise > 0f) {
                val n = v.nz()
                v.lp += v.fq * v.bp; val h = n - v.lp - v.qq * v.bp; v.bp += v.fq * h
                s += v.bp * v.noise * 1.5f
            }
            out = (s * v.env).toFloat()
            v.env *= v.dec; v.iEnv *= v.iDec
        }
        // Stardust: körniger Schweif, schwillt kurz an und verweht
        if (v.tail > 0f) {
            val n = v.nz()
            v.tl += v.tf * v.tb; val h = n - v.tl - 0.3f * v.tb; v.tb += v.tf * h
            if (v.gCnt <= 0) { v.gCnt = (sr * (0.006 + 0.03 * rnd.nextFloat())).toInt(); v.gTgt = rnd.nextFloat().let { it * it } }
            v.gCnt--
            v.gAmp += (v.gTgt - v.gAmp) * 0.004f
            val att = min(1f, tt / (sr * 0.03f))
            out += v.tb * v.gAmp * v.tail * att
            v.tail *= v.tDec
            if (v.tail < 1e-4f) v.tail = 0f
        }
        if (v.env <= 1e-4f && v.tail <= 0f && tt > sr / 100) v.active = false
        return out * v.vel
    }
}
