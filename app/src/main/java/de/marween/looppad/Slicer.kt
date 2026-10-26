package de.marween.looppad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Slicer: ein Abschnitt (1–4 Takte) eines Pads, an den Schlägen in 16 Stücke zerlegt,
 * die auf 16 eigenen Pads gespielt und als Pattern aufgenommen werden (wie beim MPC).
 */
object Slicer {
    const val N = 16
    @Volatile var data: FloatArray? = null
        private set
    val bounds = IntArray(N + 1)
    /** Quelle: Pad, erster Takt im Pad, Länge in Takten. */
    @Volatile var srcPad = -1
    @Volatile var startBar = 0
    @Volatile var bars = 2
    @Volatile var on = true
    @Volatile var fx = true
    /** Choke: ein neuer Anschlag beendet den vorigen (wie ein zerschnittener Loop), sonst überlappen sie. */
    @Volatile var choke = true
    @Volatile var gain = 0.9f
    /** Pattern: Lautstärke je Slice und Schritt (0 = aus). */
    val steps = Array(N) { FloatArray(InstState.MAX) }
    val len: Int get() = (bars * 16).coerceIn(16, InstState.MAX)
    /** Für die Anzeige: zuletzt angeschlagener Slice und Zeitpunkt. */
    @Volatile var lastHit = -1
    @Volatile var lastHitAt = 0L

    // ---------------------------------------------------------------- Aufbau

    /** Takte im Quell-Pad (ganze Takte, mind. 1). */
    fun srcBars(): Int {
        val m = Engine.masterFrames()
        val n = Engine.padDataLen(srcPad)
        if (n <= 0) return 0
        return if (m > 0L) max(1, (n / m).toInt()) else 1
    }

    /** Abschnitt aus dem Quell-Pad schneiden und an den Schlägen in 16 Stücke teilen. */
    fun rebuild(): Boolean {
        val src = if (srcPad >= 0) Engine.loopData(srcPad) else null
        if (src == null || src.size < 64) { data = null; return false }
        val m = Engine.masterFrames()
        val total = srcBars()
        bars = bars.coerceIn(1, min(4, max(1, total)))
        startBar = startBar.coerceIn(0, max(0, total - bars))
        val barLen = if (m > 0L) m.toInt() else src.size
        val a = (startBar.toLong() * barLen).toInt().coerceIn(0, src.size - 2)
        val e = min(src.size, a + barLen * bars)
        val sec = src.copyOfRange(a, e)
        val b = Warp.sliceBounds(sec, Engine.sampleRate, N)
        for (i in 0..N) bounds[i] = b[i].coerceIn(0, sec.size)
        for (i in 1..N) if (bounds[i] < bounds[i - 1]) bounds[i] = bounds[i - 1]
        synchronized(lock) { queue.clear(); for (v in voices) v.active = false }
        data = sec
        return true
    }

    fun clearPattern() { for (r in steps) java.util.Arrays.fill(r, 0f) }
    fun hasPattern(): Boolean = steps.any { r -> (0 until len).any { r[it] > 0f } }

    // ---------------------------------------------------------------- Klang

    private class Ev(val frame: Long, val slice: Int, val vel: Float)
    private class Voice { var active = false; var pos = 0; var end = 0; var g = 1f; var rel = -1; var age = 0L; var fi = 0 }
    private val queue = ArrayList<Ev>()
    private val lock = Any()
    private val voices = Array(8) { Voice() }
    private var ageCtr = 0L

    /** frame < 0 = sofort. */
    fun queue(frame: Long, slice: Int, vel: Float) {
        if (slice !in 0 until N) return
        synchronized(lock) { queue.add(Ev(frame, slice, vel)) }
        lastHit = slice
        lastHitAt = System.nanoTime()
    }

    private fun start(e: Ev) {
        val d = data ?: return
        val a = bounds[e.slice]
        val b = bounds[e.slice + 1]
        if (b - a < 8) return
        if (choke) for (v in voices) if (v.active && v.rel < 0) v.rel = 0
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.minByOrNull { it.age }!!
        v.active = true; v.pos = a; v.end = min(b, d.size); v.g = e.vel.coerceIn(0.05f, 1f); v.rel = -1; v.age = ++ageCtr; v.fi = 0
    }

    fun render(out: FloatArray, n: Int, w: Long) {
        val d = data ?: return
        val evs: List<Ev>
        synchronized(lock) {
            if (queue.isEmpty()) evs = emptyList() else {
                evs = queue.filter { it.frame < w + n }.sortedBy { it.frame }
                queue.removeAll(evs.toSet())
            }
        }
        val fadeIn = 24
        val fadeOut = 96
        val relN = Engine.sampleRate / 200 // 5 ms Choke-Ausblende
        val g0 = gain
        var ei = 0
        for (i in 0 until n) {
            while (ei < evs.size && (evs[ei].frame < 0 || evs[ei].frame <= w + i)) { start(evs[ei]); ei++ }
            var s = 0f
            for (v in voices) {
                if (!v.active) continue
                if (v.pos >= v.end || v.pos >= d.size) { v.active = false; continue }
                var x = d[v.pos] * v.g
                if (v.fi < fadeIn) { x *= v.fi / fadeIn.toFloat(); v.fi++ }
                val left = v.end - v.pos
                if (left < fadeOut) x *= left / fadeOut.toFloat()
                if (v.rel >= 0) {
                    x *= 1f - v.rel / relN.toFloat()
                    v.rel++
                    if (v.rel >= relN) v.active = false
                }
                s += x
                v.pos++
            }
            out[i] += s * g0
        }
    }

    // ---------------------------------------------------------------- Projekt

    fun toJson(): JSONObject = JSONObject().apply {
        put("src", srcPad); put("start", startBar); put("bars", bars); put("on", on); put("fx", fx); put("choke", choke); put("gain", gain.toDouble())
        put("steps", JSONArray().apply { for (r in steps) put(JSONArray().apply { r.forEach { put(Math.round(it * 100) / 100.0) } }) })
    }

    fun fromJson(j: JSONObject?) {
        clearPattern()
        data = null
        if (j == null) { srcPad = -1; return }
        srcPad = j.optInt("src", -1); startBar = j.optInt("start", 0); bars = j.optInt("bars", 2)
        on = j.optBoolean("on", true); fx = j.optBoolean("fx", true); choke = j.optBoolean("choke", true); gain = j.optDouble("gain", 0.9).toFloat()
        j.optJSONArray("steps")?.let { a -> for (s in 0 until min(N, a.length())) a.optJSONArray(s)?.let { r -> for (k in 0 until min(InstState.MAX, r.length())) steps[s][k] = r.optDouble(k).toFloat() } }
        rebuild()
    }
}
