package de.marween.looppad

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Master-Bereich: Mastering-Kette am Ausgang (Stereo).
 * EQ › Kompressor › Multiband › Sättigung › Stereo-Breite › Limiter, mit Pegel- und Reduktionsanzeigen.
 * Alle Regler 0..1 (Anzeige-Umrechnung in [label]).
 */
object MasterBus {
    // ---------------------------------------------------------------- Module
    const val EQ = 0
    const val COMP = 1
    const val MB = 2
    const val SAT = 3
    const val WIDTH = 4
    const val LIM = 5
    val MODULES = arrayOf("EQ", "KOMPRESSOR", "MULTIBAND", "SÄTTIGUNG", "STEREO", "LIMITER")
    val on = BooleanArray(6)
    @Volatile var bypass = false

    /** Parameter je Modul (0..1). */
    val par: Array<FloatArray> = arrayOf(
        floatArrayOf(0.1f, 0.5f, 0.5f, 0.45f, 0.5f, 0.5f),            // EQ: Low-Cut, Bass, Mitten, Mitten-Freq, Höhen, Luft
        floatArrayOf(0.6f, 0.25f, 0.35f, 0.4f, 0.5f, 0.15f, 1f),      // KOMP: Schwelle, Ratio, Attack, Release, Knee, Make-up, Mix
        floatArrayOf(0.4f, 0.3f, 0.3f, 0.5f, 0.5f, 0.5f),             // MB: Stärke Bass/Mitte/Höhe, Gain Bass/Mitte/Höhe
        floatArrayOf(0.3f, 0f, 0.6f),                                 // SAT: Drive, Typ (Tape/Röhre/Clip), Mix
        floatArrayOf(0.5f, 0.3f),                                     // STEREO: Breite, Mono-Bass bis
        floatArrayOf(0.25f, 0.9f, 0.3f)                               // LIM: Gain, Ceiling, Release
    )
    val NAMES: Array<Array<String>> = arrayOf(
        arrayOf("LOW-CUT", "BASS", "MITTEN", "MITTEN-FREQ", "HÖHEN", "LUFT"),
        arrayOf("SCHWELLE", "RATIO", "ATTACK", "RELEASE", "KNEE", "MAKE-UP", "MIX"),
        arrayOf("BASS STÄRKE", "MITTE STÄRKE", "HÖHE STÄRKE", "BASS GAIN", "MITTE GAIN", "HÖHE GAIN"),
        arrayOf("DRIVE", "TYP", "MIX"),
        arrayOf("BREITE", "MONO-BASS"),
        arrayOf("GAIN", "CEILING", "RELEASE")
    )

    // Anzeigen
    @Volatile var inPeak = 0f
    @Volatile var outPeak = 0f
    @Volatile var compGr = 0f
    @Volatile var limGr = 0f
    @Volatile var mbGr = FloatArray(3)
    @Volatile var loud = -60f

    fun anyOn(): Boolean = !bypass && on.any { it }

    // ---------------------------------------------------------------- Umrechnungen
    private fun db(v: Float, range: Float) = (v - 0.5f) * 2f * range
    fun lowCutHz(v: Float) = 20f * 10f.pow(v * 1.2f)          // 20 … 317 Hz
    fun midHz(v: Float) = 200f * 25f.pow(v)                    // 200 … 5000 Hz
    fun threshDb(v: Float) = -40f + v * 40f                    // −40 … 0 dB  (Regler = 1 − Stärke)
    fun ratio(v: Float) = 1f + v * v * 19f                     // 1 … 20
    fun attackMs(v: Float) = 0.1f * 1000f.pow(v)                // 0,1 … 100 ms
    fun releaseMs(v: Float) = 10f * 100f.pow(v)                 // 10 … 1000 ms
    fun ceilDb(v: Float) = -3f + v * 3f                        // −3 … 0 dBFS
    fun limGainDb(v: Float) = v * 12f                          // 0 … +12 dB
    fun limRelMs(v: Float) = 5f * 100f.pow(v)                  // 5 … 500 ms
    fun monoBassHz(v: Float) = if (v < 0.03f) 0f else 40f + v * 200f

    fun label(m: Int, i: Int): String {
        val v = par[m][i]
        fun sdb(x: Float) = (if (x > 0) "+" else "") + String.format(java.util.Locale.GERMANY, "%.1f dB", x)
        return when (m) {
            EQ -> when (i) {
                0 -> if (v < 0.02f) "AUS" else "${lowCutHz(v).toInt()} Hz"
                3 -> "${midHz(v).toInt()} Hz"
                else -> sdb(db(v, 12f))
            }
            COMP -> when (i) {
                0 -> String.format(java.util.Locale.GERMANY, "%.1f dB", threshDb(v))
                1 -> String.format(java.util.Locale.GERMANY, "%.1f : 1", ratio(v))
                2 -> String.format(java.util.Locale.GERMANY, "%.1f ms", attackMs(v))
                3 -> "${releaseMs(v).toInt()} ms"
                4 -> "${(v * 12).toInt()} dB"
                5 -> sdb(v * 24f)
                else -> "${(v * 100).toInt()} %"
            }
            MB -> if (i < 3) "${(v * 100).toInt()} %" else sdb(db(v, 6f))
            SAT -> when (i) {
                1 -> arrayOf("TAPE", "RÖHRE", "CLIP")[min(2, (v * 3f).toInt())]
                else -> "${(v * 100).toInt()} %"
            }
            WIDTH -> if (i == 0) "${(v * 200).toInt()} %" else if (monoBassHz(v) <= 0f) "AUS" else "${monoBassHz(v).toInt()} Hz"
            else -> when (i) {
                0 -> sdb(limGainDb(v))
                1 -> String.format(java.util.Locale.GERMANY, "%.1f dBFS", ceilDb(v))
                else -> "${limRelMs(v).toInt()} ms"
            }
        }
    }

    // ---------------------------------------------------------------- Presets
    val PRESETS = arrayOf("AUS", "SANFT", "CLUB", "RADIO LAUT", "WARM (TAPE)", "PUNCH", "BREIT", "LO-FI")

    fun applyPreset(i: Int) {
        java.util.Arrays.fill(on, false)
        fun set(m: Int, vararg v: Float) { for (k in v.indices) par[m][k] = v[k] }
        set(EQ, 0.1f, 0.5f, 0.5f, 0.45f, 0.5f, 0.5f)
        set(COMP, 0.6f, 0.25f, 0.35f, 0.4f, 0.5f, 0.15f, 1f)
        set(MB, 0.4f, 0.3f, 0.3f, 0.5f, 0.5f, 0.5f)
        set(SAT, 0.3f, 0f, 0.6f)
        set(WIDTH, 0.5f, 0.3f)
        set(LIM, 0.25f, 0.9f, 0.3f)
        when (i) {
            1 -> { on[COMP] = true; on[LIM] = true; set(COMP, 0.75f, 0.12f, 0.45f, 0.45f, 0.6f, 0.08f, 1f); set(LIM, 0.15f, 0.93f, 0.35f) }
            2 -> { on[EQ] = true; on[COMP] = true; on[MB] = true; on[LIM] = true
                set(EQ, 0.15f, 0.62f, 0.47f, 0.4f, 0.56f, 0.58f); set(COMP, 0.6f, 0.2f, 0.45f, 0.35f, 0.5f, 0.12f, 1f)
                set(MB, 0.55f, 0.3f, 0.35f, 0.55f, 0.5f, 0.52f); set(LIM, 0.45f, 0.93f, 0.25f) }
            3 -> { on[EQ] = true; on[COMP] = true; on[MB] = true; on[SAT] = true; on[LIM] = true
                set(EQ, 0.2f, 0.56f, 0.52f, 0.5f, 0.58f, 0.6f); set(COMP, 0.45f, 0.35f, 0.3f, 0.3f, 0.5f, 0.2f, 1f)
                set(MB, 0.6f, 0.55f, 0.55f, 0.5f, 0.52f, 0.53f); set(SAT, 0.25f, 0.5f, 0.4f); set(LIM, 0.75f, 0.95f, 0.15f) }
            4 -> { on[SAT] = true; on[COMP] = true; on[EQ] = true; on[LIM] = true
                set(EQ, 0.08f, 0.56f, 0.5f, 0.4f, 0.44f, 0.42f); set(SAT, 0.45f, 0f, 0.75f); set(COMP, 0.65f, 0.15f, 0.55f, 0.55f, 0.7f, 0.1f, 1f); set(LIM, 0.2f, 0.92f, 0.4f) }
            5 -> { on[COMP] = true; on[EQ] = true; on[LIM] = true
                set(EQ, 0.12f, 0.6f, 0.46f, 0.3f, 0.54f, 0.5f); set(COMP, 0.5f, 0.3f, 0.6f, 0.2f, 0.3f, 0.18f, 0.6f); set(LIM, 0.35f, 0.93f, 0.25f) }
            6 -> { on[WIDTH] = true; on[EQ] = true; on[LIM] = true
                set(WIDTH, 0.72f, 0.4f); set(EQ, 0.1f, 0.5f, 0.5f, 0.45f, 0.55f, 0.6f); set(LIM, 0.15f, 0.93f, 0.3f) }
            7 -> { on[EQ] = true; on[SAT] = true; on[LIM] = true
                set(EQ, 0.45f, 0.5f, 0.62f, 0.35f, 0.25f, 0.2f); set(SAT, 0.65f, 0.9f, 0.6f); set(LIM, 0.2f, 0.9f, 0.3f) }
        }
        preset = i
        dirty = true
    }

    @Volatile var preset = 0
    @Volatile var dirty = true

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("on", org.json.JSONArray().apply { on.forEach { put(it) } })
        j.put("par", org.json.JSONArray().apply { par.forEach { a -> put(org.json.JSONArray().apply { a.forEach { put(it.toDouble()) } }) } })
        j.put("preset", preset)
        j.put("bypass", bypass)
        return j
    }

    fun fromJson(j: JSONObject?) {
        if (j == null) return
        j.optJSONArray("on")?.let { a -> for (i in 0 until min(on.size, a.length())) on[i] = a.optBoolean(i) }
        j.optJSONArray("par")?.let { a ->
            for (m in 0 until min(par.size, a.length())) a.optJSONArray(m)?.let { b -> for (i in 0 until min(par[m].size, b.length())) par[m][i] = b.optDouble(i).toFloat() }
        }
        preset = j.optInt("preset", 0)
        bypass = j.optBoolean("bypass", false)
        dirty = true
    }

    // ---------------------------------------------------------------- DSP

    private class Bq {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = if (abs(y) < 1e-20) 0.0 else y
            return y.toFloat()
        }
        fun copyFrom(o: Bq) { b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2 }
        fun set(t: Int, sr: Int, f0: Double, q: Double, gainDb: Double) {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * min(f0, sr * 0.45) / sr
            val cs = cos(w); val sn = sin(w)
            val al = sn / (2 * q)
            var nb0: Double; var nb1: Double; var nb2: Double; val na0: Double; val na1: Double; val na2: Double
            when (t) {
                0 -> { // Hochpass
                    nb0 = (1 + cs) / 2; nb1 = -(1 + cs); nb2 = (1 + cs) / 2; na0 = 1 + al; na1 = -2 * cs; na2 = 1 - al
                }
                1 -> { // Tiefpass
                    nb0 = (1 - cs) / 2; nb1 = 1 - cs; nb2 = (1 - cs) / 2; na0 = 1 + al; na1 = -2 * cs; na2 = 1 - al
                }
                2 -> { // Glocke
                    nb0 = 1 + al * a; nb1 = -2 * cs; nb2 = 1 - al * a; na0 = 1 + al / a; na1 = -2 * cs; na2 = 1 - al / a
                }
                3 -> { // Low-Shelf
                    val s2 = 2 * sqrt(a) * al
                    nb0 = a * ((a + 1) - (a - 1) * cs + s2); nb1 = 2 * a * ((a - 1) - (a + 1) * cs); nb2 = a * ((a + 1) - (a - 1) * cs - s2)
                    na0 = (a + 1) + (a - 1) * cs + s2; na1 = -2 * ((a - 1) + (a + 1) * cs); na2 = (a + 1) + (a - 1) * cs - s2
                }
                else -> { // High-Shelf
                    val s2 = 2 * sqrt(a) * al
                    nb0 = a * ((a + 1) + (a - 1) * cs + s2); nb1 = -2 * a * ((a - 1) + (a + 1) * cs); nb2 = a * ((a + 1) + (a - 1) * cs - s2)
                    na0 = (a + 1) - (a - 1) * cs + s2; na1 = 2 * ((a - 1) - (a + 1) * cs); na2 = (a + 1) - (a - 1) * cs - s2
                }
            }
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
        }
    }

    private var sr = 48000
    // EQ: [Kanal][Low-Cut, Bass, Mitten, Höhen, Luft]
    private val eq = Array(2) { Array(5) { Bq() } }
    // Multiband-Weichen (Linkwitz-Riley 4 = 2× Butterworth) je Kanal: LP200, HP200, LP3k, HP3k
    private val xo = Array(2) { Array(8) { Bq() } }
    private val monoLp = Array(2) { Bq() }
    private val monoHp = Array(2) { Bq() }
    private var compEnv = 0f
    private val mbEnv = FloatArray(3)
    private var limGain = 1f
    private var loudAcc = 0.0
    private var loudN = 0
    // Limiter: 1,5 ms Vorausschau
    private var la = FloatArray(0)
    private var lb = FloatArray(0)
    private var laPos = 0
    private val peakHold = FloatArray(512)

    private fun configure(rate: Int) {
        sr = rate
        val q = 0.7071
        val p = par[EQ]
        for (c in 0..1) {
            val lc = lowCutHz(p[0]).toDouble()
            eq[c][0].set(0, sr, lc, q, 0.0)
            eq[c][1].set(3, sr, 100.0, q, db(p[1], 12f).toDouble())
            eq[c][2].set(2, sr, midHz(p[3]).toDouble(), 0.9, db(p[2], 12f).toDouble())
            eq[c][3].set(4, sr, 5000.0, q, db(p[4], 12f).toDouble())
            eq[c][4].set(4, sr, 12000.0, q, db(p[5], 12f).toDouble())
            val f1 = 200.0
            val f2 = 3000.0
            xo[c][0].set(1, sr, f1, q, 0.0); xo[c][1].set(1, sr, f1, q, 0.0)
            xo[c][2].set(0, sr, f1, q, 0.0); xo[c][3].set(0, sr, f1, q, 0.0)
            xo[c][4].set(1, sr, f2, q, 0.0); xo[c][5].set(1, sr, f2, q, 0.0)
            xo[c][6].set(0, sr, f2, q, 0.0); xo[c][7].set(0, sr, f2, q, 0.0)
            val mb = max(30f, monoBassHz(par[WIDTH][1])).toDouble()
            monoLp[c].set(1, sr, mb, q, 0.0)
            monoHp[c].set(0, sr, mb, q, 0.0)
        }
        val n = max(8, sr * 3 / 2000)
        if (la.size != n) {
            la = FloatArray(n)
            lb = FloatArray(n)
            laPos = 0
        }
        dirty = false
    }

    private fun sat(x: Float, type: Int, drive: Float): Float {
        val d = 1f + drive * 8f
        return when (type) {
            0 -> tanh(x * d) / tanh(d).coerceAtLeast(0.01f)                              // Tape: weich
            1 -> { val y = x * d; (if (y >= 0) 1f - exp(-y) else -(1f - exp(y)) * 0.9f) / (1f - exp(-d)).coerceAtLeast(0.01f) } // Röhre: asymmetrisch
            else -> (x * d).coerceIn(-1f, 1f) / min(1f, d).coerceAtLeast(0.01f)         // Clip
        }
    }

    private fun dbToLin(d: Float) = 10f.pow(d / 20f)
    private fun linToDb(v: Float) = if (v <= 1e-6f) -120f else 20f * ln(v) / ln(10f)

    /** Verarbeitet den Master (L/R) im Mischer-Thread. */
    fun process(l: FloatArray, r: FloatArray, n: Int, rate: Int) {
        var ip = 0f
        for (i in 0 until n) { val a = max(abs(l[i]), abs(r[i])); if (a > ip) ip = a }
        inPeak = max(ip, inPeak * 0.9f)
        if (!anyOn()) {
            outPeak = inPeak
            compGr *= 0.8f; limGr *= 0.8f
            return
        }
        if (dirty || rate != sr) configure(rate)
        val oEq = on[EQ]; val oC = on[COMP]; val oMb = on[MB]; val oS = on[SAT]; val oW = on[WIDTH]; val oL = on[LIM]
        val pc = par[COMP]
        val thr = threshDb(pc[0]); val rat = ratio(pc[1])
        val att = exp(-1.0 / (attackMs(pc[2]) * 0.001 * sr)).toFloat()
        val rel = exp(-1.0 / (releaseMs(pc[3]) * 0.001 * sr)).toFloat()
        val knee = pc[4] * 12f
        val makeup = dbToLin(pc[5] * 24f)
        val cMix = pc[6]
        val pm = par[MB]
        val mbAtt = exp(-1.0 / (0.005 * sr)).toFloat()
        val mbRel = exp(-1.0 / (0.12 * sr)).toFloat()
        val mbGain = floatArrayOf(dbToLin(db(pm[3], 6f)), dbToLin(db(pm[4], 6f)), dbToLin(db(pm[5], 6f)))
        val ps = par[SAT]
        val sType = min(2, (ps[1] * 3f).toInt())
        val sMix = ps[2]
        val width = par[WIDTH][0] * 2f
        val monoBass = monoBassHz(par[WIDTH][1]) > 0f
        val pl = par[LIM]
        val lGain = dbToLin(limGainDb(pl[0]))
        val ceil = dbToLin(ceilDb(pl[1]))
        val lRel = exp(-1.0 / (limRelMs(pl[2]) * 0.001 * sr)).toFloat()
        var cg = 0f
        var lg = 0f
        val mbg = FloatArray(3)
        var op = 0f
        val la = la
        val lb = lb
        val laN = la.size
        val laAtt = exp(-4.0 / laN).toFloat()
        for (i in 0 until n) {
            var x = l[i]
            var y = r[i]
            if (oEq) {
                if (par[EQ][0] > 0.02f) { x = eq[0][0].run(x); y = eq[1][0].run(y) }
                for (k in 1..4) { x = eq[0][k].run(x); y = eq[1][k].run(y) }
            }
            if (oC) {
                // Stereo-verkoppelter Spitzen-Detektor mit weichem Knie
                val lv = max(abs(x), abs(y))
                compEnv = if (lv > compEnv) att * compEnv + (1 - att) * lv else rel * compEnv + (1 - rel) * lv
                val e = linToDb(compEnv)
                val over = e - thr
                val red = when {
                    knee > 0f && over > -knee / 2 && over < knee / 2 -> (1f / rat - 1f) * (over + knee / 2).let { it * it } / (2 * knee)
                    over > 0f -> over * (1f / rat - 1f)
                    else -> 0f
                }
                val g = dbToLin(red) * makeup
                if (-red > cg) cg = -red
                x = x * (1 - cMix) + x * g * cMix
                y = y * (1 - cMix) + y * g * cMix
            }
            if (oMb) {
                var ox = 0f
                var oy = 0f
                for (c in 0..1) {
                    val s = if (c == 0) x else y
                    val lo = xo[c][1].run(xo[c][0].run(s))
                    val hiAll = xo[c][3].run(xo[c][2].run(s))
                    val mid = xo[c][5].run(xo[c][4].run(hiAll))
                    val hi = xo[c][7].run(xo[c][6].run(hiAll))
                    var sum = 0f
                    for (b in 0..2) {
                        val band = when (b) { 0 -> lo; 1 -> mid; else -> hi }
                        if (c == 0) {
                            val lv = abs(band)
                            mbEnv[b] = if (lv > mbEnv[b]) mbAtt * mbEnv[b] + (1 - mbAtt) * lv else mbRel * mbEnv[b] + (1 - mbRel) * lv
                        }
                        val amt = pm[b]
                        val t = -6f - amt * 30f
                        val rr = 1f + amt * 5f
                        val over = linToDb(mbEnv[b]) - t
                        val red = if (over > 0f) over * (1f / rr - 1f) else 0f
                        if (-red > mbg[b]) mbg[b] = -red
                        // Make-up: die Hälfte der typischen Reduktion zurück
                        sum += band * dbToLin(red + amt * 6f) * mbGain[b]
                    }
                    if (c == 0) ox = sum else oy = sum
                }
                x = ox
                y = oy
            }
            if (oS) {
                x = x * (1 - sMix) + sat(x, sType, ps[0]) * sMix
                y = y * (1 - sMix) + sat(y, sType, ps[0]) * sMix
            }
            if (oW) {
                val m = (x + y) * 0.5f
                var sd = (x - y) * 0.5f * width
                if (monoBass) {
                    // Bass mono: Seitensignal unterhalb der Grenze entfernen
                    val lowS = monoLp[0].run(sd)
                    sd -= lowS
                }
                x = m + sd
                y = m - sd
            }
            if (oL) {
                x *= lGain
                y *= lGain
                // Vorausschau: Spitze der nächsten 1,5 ms bestimmt die Verstärkung
                val pk = max(abs(x), abs(y))
                la[laPos] = x
                lb[laPos] = y
                var need = if (pk > ceil) ceil / pk else 1f
                // einfache Spitzen-Halte-Kette: niedrigsten Wert über das Fenster halten
                peakHold[laPos % peakHold.size] = need
                var mn = need
                for (k in 0 until min(laN, peakHold.size)) if (peakHold[k] < mn) mn = peakHold[k]
                need = mn
                // Absenken über die Vorausschau verteilt (kein Knacksen), Erholen mit RELEASE
                limGain = if (need < limGain) need + (limGain - need) * laAtt else lRel * limGain + (1 - lRel) * need
                laPos = (laPos + 1) % laN
                x = la[laPos] * limGain
                y = lb[laPos] * limGain
                // harte Sicherung
                x = x.coerceIn(-ceil, ceil)
                y = y.coerceIn(-ceil, ceil)
                val grd = -linToDb(limGain)
                if (grd > lg) lg = grd
            }
            l[i] = x
            r[i] = y
            val a = max(abs(x), abs(y))
            if (a > op) op = a
            loudAcc += (x * x + y * y) * 0.5
            loudN++
        }
        compGr = max(cg, compGr * 0.85f)
        limGr = max(lg, limGr * 0.85f)
        for (b in 0..2) mbGr[b] = max(mbg[b], mbGr[b] * 0.85f)
        outPeak = max(op, outPeak * 0.9f)
        if (loudN > sr * 0.4) {
            loud = 10f * kotlin.math.log10((loudAcc / loudN).toFloat().coerceAtLeast(1e-9f))
            loudAcc = 0.0
            loudN = 0
        }
    }
}
