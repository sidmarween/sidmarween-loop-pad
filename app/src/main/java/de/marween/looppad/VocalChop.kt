package de.marween.looppad

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

/**
 * 🗣 VOCAL-CHOP: zerlegt eine Stimme (Pad, Mikrofon-Aufnahme oder Demo-Stimme) automatisch in Silben,
 * legt sie auf 16 Chop-Pads und spielt sie über eine Tasten-Leiste in jeder Tonhöhe – mit Gate, Rückwärts,
 * Stutter im Takt und Chop-Mustern als Vorlagen.
 */
object VocalChop {
    const val N = 16

    class Src(val data: FloatArray, val starts: IntArray, val ends: IntArray, val name: String) {
        val count get() = starts.size
    }

    @Volatile var src: Src? = null
    @Volatile var vol = 0.8f
    @Volatile var gate = true
    @Volatile var reverse = false
    @Volatile var stutter = 0          // 0 aus · 1 = 1/8 · 2 = 1/16 · 3 = 1/32
    @Volatile var pitch = 0            // −12 … +12
    @Volatile var release = 0.25f
    @Volatile var tone = 0.5f          // 0 dunkel … 1 hell
    @Volatile var chopMode = 0         // 0 Silben (auto) · 1 = 8 gleich · 2 = 16 gleich
    @Volatile var sel = 0              // zuletzt gespielter Chop (für die Tasten-Leiste)
    @Volatile var patOn = false
    @Volatile var patIdx = -1
    @Volatile var curStep = -1
    @Volatile var meter = 0f
    val flash = FloatArray(N)
    val STUTTER_NAMES = arrayOf("AUS", "1/8", "1/16", "1/32")
    val MODE_NAMES = arrayOf("SILBEN", "8 GLEICH", "16 GLEICH")

    // ------------------------------------------------------------------ Vorlagen
    /** Ein Muster: 16 Schritte. Token: "-" Pause · "3" Chop 3 · "3^5" +5 Halbtöne · "3~2" zwei Schritte lang · "3r" rückwärts. */
    class Pat(val name: String, src: String) {
        val chop = IntArray(16) { -1 }; val semi = IntArray(16); val len = IntArray(16) { 1 }; val rev = BooleanArray(16)
        init {
            val t = src.trim().split(Regex("\\s+"))
            for (k in 0 until min(16, t.size)) {
                var s = t[k]
                if (s == "-") continue
                if (s.endsWith("r")) { rev[k] = true; s = s.dropLast(1) }
                val li = s.indexOf('~'); if (li >= 0) { len[k] = s.substring(li + 1).toIntOrNull() ?: 1; s = s.substring(0, li) }
                val pi = s.indexOf('^'); if (pi >= 0) { semi[k] = s.substring(pi + 1).toIntOrNull() ?: 0; s = s.substring(0, pi) }
                chop[k] = s.toIntOrNull() ?: -1
            }
        }
    }

    val PATTERNS = arrayOf(
        Pat("House-Hook", "0 - 0 1 - 1 2 - 0 - 0 1 - 3 2 -"),
        Pat("Future-Bass Melodie", "0~2 - 0^3 0^5~2 - 0^7 0^5 0^3~2 - 0 0^-2~2 - 0^3 -"),
        Pat("Stutter-Build", "0 0 0 0 1 1 1 1 2 2 2 2 3 3 3 3"),
        Pat("Garage-Shuffle", "0 - - 1 - 2 - - 0 - 3 - - 2 1 -"),
        Pat("Soul-Flip", "0~4 - - - 1~2 - 2~2 - 3~4 - - - 1~2 - 4~2 -"),
        Pat("Trap Ad-Libs", "0 - - - - - 1^-5 - - - 2 - - 3r - -"),
        Pat("Moll-Melodie", "0 - 0^3 - 0^5 - 0^7 0^10 - 0^7 - 0^5 0^3 - 0 -"),
        Pat("Rückwärts-Echo", "0 - 0r - 1 - 1r - 2 - 2r - 3 3r 3 3r"),
        Pat("Disco-Diva", "0~2 - 1 1 2~2 - 3 - 0~2 - 1 1 4~2 - 5 -"),
        Pat("Tribal-Chant", "0 - 1 - 0 0 1 - 2 - 1 - 0 2 1 -"),
    )

    fun applyPattern(i: Int) {
        patIdx = Math.floorMod(i, PATTERNS.size)
        patOn = true
    }

    // ------------------------------------------------------------------ Quellen
    fun loadPad(p: Int): Boolean {
        val d = Engine.loopData(p) ?: return false
        if (d.size < Engine.sampleRate / 10) return false
        install(d, "Pad ${p + 1}")
        return true
    }

    fun loadRecording(d: FloatArray): Boolean {
        if (d.size < Engine.sampleRate / 5) return false
        // Gleichanteil weg, auf −3 dB normalisieren
        var m = 0.0; for (v in d) m += v; val dc = (m / d.size).toFloat()
        var pk = 0f; for (i in d.indices) { d[i] -= dc; pk = max(pk, abs(d[i])) }
        if (pk < 0.003f) return false
        val g = 0.7f / pk; for (i in d.indices) d[i] *= g
        install(d, "Mikrofon")
        return true
    }

    val DEMO_NAMES = arrayOf("Diva Ooh-Aah", "Yeah! Hey! Oh!", "La-La Melodie", "Chor-Seufzer")
    @Volatile var demoIdx = -1
    fun loadDemo(k: Int) {
        demoIdx = Math.floorMod(k, DEMO_NAMES.size)
        install(Demo.make(demoIdx, Engine.sampleRate), "Demo: " + DEMO_NAMES[demoIdx])
    }

    private var lastData: FloatArray? = null
    private var lastName = ""
    private fun install(d: FloatArray, name: String) {
        lastData = d; lastName = name
        src = analyze(d, name)
        sel = 0
    }

    /** Neu zerlegen nach Umschalten von SILBEN / GLEICH. */
    fun rechop() { val d = lastData ?: return; src = analyze(d, lastName) }

    fun clear() { src = null; lastData = null; patOn = false; demoIdx = -1 }

    /** Die Chops als Einzel-Samples (für „auf Pads“). */
    fun chopData(): List<FloatArray> {
        val s = src ?: return emptyList()
        return (0 until s.count).map { s.data.copyOfRange(s.starts[it], s.ends[it]) }
    }

    // ------------------------------------------------------------------ Silben-Erkennung
    private fun analyze(d: FloatArray, name: String): Src {
        val sr = Engine.sampleRate
        val hop = 256
        val m = d.size / hop
        val e = FloatArray(max(1, m))
        for (i in 0 until m) { var s = 0.0; for (j in 0 until hop) { val v = d[i * hop + j]; s += v * v }; e[i] = sqrt(s / hop).toFloat() }
        val es = FloatArray(e.size) { i -> (e[max(0, i - 1)] + e[i] + e[min(e.size - 1, i + 1)]) / 3f }
        val peak = es.maxOrNull() ?: 0f
        val thr = max(peak * 0.06f, 0.002f)
        // hörbarer Bereich
        var first = es.indexOfFirst { it > thr }.coerceAtLeast(0)
        var last = es.indexOfLast { it > thr }.let { if (it < 0) e.size - 1 else it }
        if (last <= first) { first = 0; last = e.size - 1 }
        val starts = ArrayList<Int>()
        if (chopMode == 0) {
            val gap = max(2, (0.09 * sr / hop).toInt())
            val cand = ArrayList<Pair<Int, Float>>()
            var lastOn = -gap
            for (i in first..last) {
                val prev = es[max(0, i - 2)]
                val dlog = (ln((es[i] + 1e-4f).toDouble()) - ln((prev + 1e-4f).toDouble())).toFloat()
                val rising = (es[max(0, i - 1)] < thr && es[i] >= thr) || dlog > 0.85f
                val localMax = es[i] >= es[max(0, i - 1)] || dlog > 1.4f
                if (rising && localMax && es[i] > thr && i - lastOn >= gap) { cand.add(i to dlog * es[i] + (if (es[max(0, i - 1)] < thr) peak else 0f)); lastOn = i }
            }
            if (cand.isEmpty() || cand[0].first > first + gap) cand.add(0, first to peak * 2f)
            val best = cand.sortedByDescending { it.second }.take(N).map { it.first }.sorted()
            for (b in best) starts.add(b)
        }
        if (starts.size < 3) {
            starts.clear()
            val cnt = if (chopMode == 2) 16 else 8
            for (k in 0 until cnt) starts.add(first + (last - first + 1) * k / cnt)
        }
        val sA = ArrayList<Int>(); val eA = ArrayList<Int>()
        val maxLen = sr * 2
        for (k in starts.indices) {
            val a = (starts[k] * hop - hop / 2).coerceIn(0, d.size - 1)
            var bHop = if (k + 1 < starts.size) starts[k + 1] else last + 2
            // Ausklang der Silbe: Ende, wenn die Energie lange unter die Schwelle fällt
            if (chopMode == 0) {
                var quiet = 0
                for (i in starts[k] until min(bHop, e.size)) {
                    if (es[i] < thr * 0.5f) { quiet++; if (quiet * hop > sr * 0.06) { bHop = i; break } } else quiet = 0
                }
            }
            val b = (bHop * hop).coerceIn(a + sr / 50, min(d.size, a + maxLen))
            if (b - a < sr / 40) continue
            sA.add(a); eA.add(b)
        }
        if (sA.isEmpty()) { sA.add(0); eA.add(d.size) }
        return Src(d, sA.toIntArray(), eA.toIntArray(), name)
    }

    // ------------------------------------------------------------------ Spielen
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<IntArray>()
    /** Chop [c] anschlagen; [id] ≥ 0 = gehalten (Loslassen mit [off]). */
    fun on(c: Int, semi: Int, id: Int) { sel = c; queue.add(intArrayOf(0, c, semi, id, if (reverse) 1 else 0)) }
    fun off(id: Int) { queue.add(intArrayOf(1, 0, 0, id, 0)) }
    fun allOff() { queue.add(intArrayOf(2, 0, 0, 0, 0)) }

    private class V(val s: Src, val chop: Int, val inc: Double, val rev: Boolean, val id: Int, var delay: Int, var offIn: Long) {
        val a = s.starts[chop]; val b = s.ends[chop]
        var pos = if (rev) (b - 1).toDouble() else a.toDouble()
        var env = 0f; var rel = false; var t = 0L
    }
    private val voices = ArrayList<V>()
    private var lp = 0f; private var lp2 = 0f

    fun active(): Boolean = src != null && (voices.isNotEmpty() || queue.isNotEmpty() || patOn)

    private fun start(s: Src, c: Int, semi: Int, id: Int, rev: Boolean, delay: Int, offIn: Long) {
        if (c !in 0 until s.count) return
        if (voices.size >= 8) voices.removeAt(0)
        // gleicher Finger → alte Stimme ausklingen lassen
        if (id >= 0) for (v in voices) if (v.id == id) v.rel = true
        val inc = 2.0.pow((semi + pitch).coerceIn(-36, 36) / 12.0)
        voices.add(V(s, c, inc, rev, id, delay, offIn))
        flash[c] = 1f
    }

    fun render(oL: FloatArray, oR: FloatArray, n: Int, sr: Int, w: Long, ms: Long, bar: Long, playing: Boolean, beatLen: Double, swing: Long = 0L) {
        val s = src
        if (s == null) { voices.clear(); queue.clear(); return }
        while (true) {
            val q = queue.poll() ?: break
            when (q[0]) {
                0 -> start(s, q[1], q[2], q[3], q[4] == 1, 0, -1L)
                1 -> for (v in voices) if (v.id == q[3]) { if (gate || stutter > 0) v.rel = true; if (!gate) { /* One-Shot spielt zu Ende */ } }
                else -> for (v in voices) v.rel = true
            }
        }
        // Muster im Takt
        val pat = if (patOn && playing && patIdx >= 0) PATTERNS[patIdx] else null
        if (pat != null && bar > 0) {
            // gleiche Rasterpunkte wie der Sequenzer (ms + n·Takt/16, ganzzahlig gerundet) – kein Wegdriften über viele Takte
            val stepLen = max(1L, bar / 16)
            var st = -Math.floorDiv(-(w - ms) * 16, bar)
            while (true) {
                val at = ms + Math.floorDiv(st * bar, 16L)
                if (at >= w + n) break
                val k = Math.floorMod(st, 16L).toInt()
                curStep = k
                val c = pat.chop[k]
                val sw = if (k % 2 == 1) swing else 0L   // Sequenzer-Swing auf ungeraden 16teln
                if (c >= 0 && s.count > 0) start(s, c % s.count, pat.semi[k], -1, pat.rev[k] != reverse, (at - w + sw).toInt(), pat.len[k] * stepLen)
                st++
            }
        } else curStep = -1
        if (voices.isEmpty()) { meter *= 0.9f; return }
        val att = 1f / (0.003f * sr)
        val relC = exp(-1.0 / ((0.01 + release * 0.45) * sr)).toFloat()
        val stLen = if (stutter > 0) max(64.0, (if (beatLen > 1) beatLen else sr / 2.0) / (1 shl stutter)).toLong() else 0L
        val g = (2 * sin(PI * min(sr / 2.3, 600.0 * 2.0.pow(tone * 5.0)) / sr)).toFloat().coerceAtMost(0.99f)
        val d = s.data
        var pk = 0f
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            var dead = false
            for (i in 0 until n) {
                if (v.delay > 0) { v.delay--; continue }
                if (v.offIn >= 0 && v.t >= v.offIn) v.rel = true
                // Stutter: solange gehalten, springt der Chop im Takt an den Anfang
                if (stLen > 0 && !v.rel && v.t > 0 && v.t % stLen == 0L) { v.pos = if (v.rev) (v.b - 1).toDouble() else v.a.toDouble(); v.env = 0f }
                v.env = if (v.rel) v.env * relC else min(1f, v.env + att)
                val ip = v.pos.toInt()
                if (ip < v.a || ip >= v.b - 1 || (v.rel && v.env < 1e-4f)) { dead = true; break }
                val fr = (v.pos - ip).toFloat()
                var x = d[ip] + (d[ip + 1] - d[ip]) * fr
                // Rand-Blende gegen Knackser am Chop-Ende
                val toEnd = (if (v.rev) v.pos - v.a else v.b - 1 - v.pos) / v.inc
                if (toEnd < 96) x *= (toEnd / 96).toFloat()
                x *= v.env
                v.pos += if (v.rev) -v.inc else v.inc
                v.t++
                oL[i] += x; oR[i] += x
            }
            if (dead) it.remove()
        }
        // Klang-Filter (Tiefpass) und Pegel
        for (i in 0 until n) {
            lp += g * (oL[i] - lp); lp2 += g * (lp - lp2)
            val y = (if (tone >= 0.98f) oL[i] else lp2) * vol * 0.9f
            val yy = (y / (1f + abs(y) * 0.3f))
            oL[i] = yy; oR[i] = yy
            val a = abs(yy); if (a > pk) pk = a
        }
        meter = max(pk, meter * 0.9f)
    }

    fun decayFlash() { for (k in 0 until N) flash[k] *= 0.88f }

    // ------------------------------------------------------------------ Demo-Stimmen (Formant-Synthese)
    private object Demo {
        // Vokal-Formanten F1, F2, F3
        private val F = mapOf(
            'a' to floatArrayOf(800f, 1200f, 2500f), 'e' to floatArrayOf(450f, 1900f, 2600f), 'i' to floatArrayOf(300f, 2300f, 3000f),
            'o' to floatArrayOf(480f, 820f, 2500f), 'u' to floatArrayOf(330f, 700f, 2400f), 'l' to floatArrayOf(350f, 1100f, 2600f)
        )
        /** Silbe: Anfangs-Laut (Gleiten von), Vokal, MIDI-Ton, Dauer s, Hauch am Anfang. */
        private class Syl(val from: Char, val vow: Char, val midi: Int, val dur: Float, val h: Boolean = false)

        private val PH = arrayOf(
            arrayOf(Syl('o', 'o', 69, 0.45f), Syl('a', 'a', 72, 0.5f), Syl('e', 'e', 76, 0.3f), Syl('a', 'a', 74, 0.6f), Syl('o', 'o', 72, 0.4f), Syl('i', 'i', 79, 0.25f), Syl('a', 'a', 76, 0.7f), Syl('u', 'u', 69, 0.5f)),
            arrayOf(Syl('i', 'a', 64, 0.35f), Syl('e', 'e', 67, 0.25f, true), Syl('o', 'o', 64, 0.3f), Syl('i', 'a', 71, 0.22f), Syl('e', 'e', 69, 0.22f, true), Syl('o', 'o', 67, 0.4f), Syl('u', 'a', 64, 0.18f), Syl('u', 'a', 62, 0.3f)),
            arrayOf(Syl('l', 'a', 60, 0.28f), Syl('l', 'a', 62, 0.28f), Syl('l', 'a', 64, 0.28f), Syl('l', 'a', 67, 0.4f), Syl('l', 'i', 69, 0.28f), Syl('l', 'a', 67, 0.28f), Syl('l', 'o', 64, 0.4f), Syl('l', 'a', 60, 0.5f)),
            arrayOf(Syl('a', 'a', 57, 0.8f, true), Syl('o', 'o', 55, 0.8f), Syl('e', 'e', 59, 0.6f, true), Syl('u', 'u', 52, 0.9f)),
        )

        fun make(k: Int, sr: Int): FloatArray {
            val ph = PH[k]
            val gap = 0.07f
            val total = ph.sumOf { (it.dur + gap).toDouble() } + 0.2
            val out = FloatArray((total * sr).toInt())
            var p = (0.05 * sr).toInt()
            var rnd = 12345
            val y1 = FloatArray(3); val y2 = FloatArray(3)
            val bw = floatArrayOf(90f, 110f, 160f)
            for (s in ph) {
                val len = (s.dur * sr).toInt()
                val hLen = if (s.h) (0.05 * sr).toInt() else 0
                val f0 = 440.0 * 2.0.pow((s.midi - 69) / 12.0)
                var phase = 0.0
                val ff = F[s.from]!!; val ft = F[s.vow]!!
                java.util.Arrays.fill(y1, 0f); java.util.Arrays.fill(y2, 0f)
                val co = Array(3) { FloatArray(3) }
                for (i in 0 until hLen + len) {
                    if (p + i >= out.size) break
                    val t = i - hLen
                    val glide = if (t < 0) 0f else min(1f, t / (0.08f * sr))
                    if (i % 32 == 0) for (j in 0 until 3) {
                        val f = ff[j] + (ft[j] - ff[j]) * glide
                        val r = exp(-PI * bw[j] / sr)
                        co[j][0] = (2 * r * cos(2 * PI * f / sr)).toFloat(); co[j][1] = (-r * r).toFloat(); co[j][2] = (1 - r).toFloat()
                    }
                    rnd = rnd * 1664525 + 1013904223
                    val nz = ((rnd ushr 8) / 16777216f) * 2f - 1f
                    val x: Float
                    val env: Float
                    if (t < 0) { x = nz * 0.6f; env = sin(PI * i / max(1, hLen)).toFloat() * 0.5f }
                    else {
                        val vib = 1.0 + 0.012 * sin(2 * PI * 5.5 * t / sr) * min(1.0, t / (0.25 * sr))
                        phase += f0 * vib / sr; if (phase >= 1.0) phase -= 1.0
                        // weicher Stimmlippen-Puls
                        val gl = if (phase < 0.6) sin(PI * phase / 0.6).pow(2.0) else 0.0
                        x = (gl - 0.35).toFloat() + nz * 0.03f
                        val a = min(1f, t / (0.02f * sr)); val rl = min(1f, (len - t) / (0.07f * sr))
                        env = a * rl
                    }
                    var sum = 0f
                    for (j in 0 until 3) {
                        val y = co[j][2] * x + co[j][0] * y1[j] + co[j][1] * y2[j]
                        y2[j] = y1[j]; y1[j] = y
                        sum += y * (if (j == 0) 1f else if (j == 1) 0.7f else 0.35f)
                    }
                    out[p + i] += sum * env
                }
                p += hLen + len + (gap * sr).toInt()
            }
            var pk = 0f; for (v in out) pk = max(pk, abs(v))
            if (pk > 0) { val g = 0.7f / pk; for (i in out.indices) out[i] *= g }
            return out
        }
    }
}
