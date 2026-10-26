package de.marween.looppad

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Audio aus Loop Station und Slicer für den Export: im Takt ausgerichtet, gleich lang (Stems liegen in jeder DAW übereinander).
 */
object PanelBounce {
    /** Spuren der Loop Station (Pads 13–16), die etwas enthalten. */
    fun stationPads(): List<Int> = (LoopStationPanel.FIRST until LoopStationPanel.FIRST + LoopStationPanel.TRACKS).filter { Engine.hasLoop(it) }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /**
     * Jede Spur ab der Takt-Eins des Masters, alle auf dieselbe Länge (kleinstes gemeinsames Vielfaches,
     * höchstens 8 × die längste Spur) – mit Fader-Lautstärke.
     */
    fun stationStems(): List<Pair<Int, FloatArray>> {
        val ps = stationPads()
        if (ps.isEmpty()) return emptyList()
        val datas = ps.map { Engine.loopData(it) ?: FloatArray(0) }
        val maxLen = datas.maxOf { it.size }.toLong()
        if (maxLen <= 0L) return emptyList()
        var l = datas.first { it.isNotEmpty() }.size.toLong()
        for (d in datas) if (d.isNotEmpty()) { l = l / gcd(l, d.size.toLong()) * d.size; if (l > maxLen * 8) { l = maxLen; break } }
        val len = min(l, Engine.sampleRate.toLong() * 600).toInt()
        val origin = if (Engine.masterFrames() > 0L) Engine.masterStartFrame() else Engine.loopStart(ps[0])
        return ps.indices.mapNotNull { i ->
            val d = datas[i]
            if (d.isEmpty()) return@mapNotNull null
            val p = ps[i]
            val g = Engine.padGain(p)
            val st = Engine.loopStart(p)
            val n = d.size.toLong()
            val out = FloatArray(len)
            var k = Math.floorMod(origin - st, n).toInt()
            for (t in 0 until len) { out[t] = d[k] * g; k++; if (k == d.size) k = 0 }
            p to out
        }
    }

    /** Mix der Loop Station (ohne stumme Spuren), Spitzen sanft begrenzt. */
    fun stationMix(): FloatArray? {
        val st = stationStems()
        if (st.isEmpty()) return null
        val len = st[0].second.size
        val out = FloatArray(len)
        for ((p, d) in st) if (!Engine.padMuted(p)) for (i in 0 until len) out[i] += d[i]
        return limit(out)
    }

    private fun limit(x: FloatArray): FloatArray {
        var pk = 0f
        for (v in x) pk = max(pk, kotlin.math.abs(v))
        if (pk > 0.98f) { val g = 0.98f / pk; for (i in x.indices) x[i] *= g }
        return x
    }

    /** Takt-Länge für den Slicer (Master-Tempo, sonst Abschnitt / Takte). */
    private fun slicerBar(): Int {
        val m = Engine.masterFrames()
        val d = Slicer.data ?: return 0
        return if (m > 0L) m.toInt() else max(1, d.size / max(1, Slicer.bars))
    }

    /** Die 16 Slices einzeln (mit kurzen Blenden gegen Knacksen). */
    fun slicerSlices(): List<FloatArray> {
        val d = Slicer.data ?: return emptyList()
        val fade = Engine.sampleRate / 333
        return (0 until Slicer.N).mapNotNull { i ->
            val a = Slicer.bounds[i]; val b = min(d.size, Slicer.bounds[i + 1])
            if (b - a < 16) return@mapNotNull null
            val s = d.copyOfRange(a, b)
            val f = min(fade, s.size / 4)
            for (k in 0 until f) { val g = k.toFloat() / f; s[k] *= g; s[s.size - 1 - k] *= g }
            s
        }
    }

    /** Das Slicer-Pattern als Audio (ein Durchlauf, nahtlos loopbar: Ausklang wird an den Anfang gelegt). */
    fun slicerPattern(): FloatArray? {
        val d = Slicer.data ?: return null
        if (!Slicer.hasPattern()) return null
        val bar = slicerBar()
        if (bar <= 0) return null
        val steps = Slicer.len
        fun at(k: Int) = (k.toDouble() * bar / 16.0).roundToInt()
        val total = at(steps)
        val maxSlice = (0 until Slicer.N).maxOf { Slicer.bounds[it + 1] - Slicer.bounds[it] }
        val buf = FloatArray(total + maxSlice + 16)
        val relN = Engine.sampleRate / 200
        // Anschläge in zeitlicher Reihenfolge; Choke beendet den vorigen Slice mit 5 ms Blende
        val hits = ArrayList<Triple<Int, Int, Float>>()
        for (k in 0 until steps) for (s in 0 until Slicer.N) { val v = Slicer.steps[s][k]; if (v > 0f) hits.add(Triple(at(k), s, v)) }
        hits.sortBy { it.first }
        for ((idx, h) in hits.withIndex()) {
            val a = Slicer.bounds[h.second]; val b = min(d.size, Slicer.bounds[h.second + 1])
            if (b - a < 8) continue
            var end = b - a
            var rel = -1
            if (Slicer.choke) {
                val next = hits.drop(idx + 1).firstOrNull { it.first > h.first }
                if (next != null && next.first - h.first < end) { rel = next.first - h.first; end = min(end, rel + relN) }
            }
            val g = h.third.coerceIn(0.05f, 1f) * Slicer.gain
            for (i in 0 until end) {
                var x = d[a + i] * g
                if (i < 24) x *= i / 24f
                val left = (b - a) - i
                if (left < 96) x *= left / 96f
                if (rel >= 0 && i >= rel) x *= 1f - (i - rel).toFloat() / relN
                buf[h.first + i] += x
            }
        }
        val out = buf.copyOf(total)
        for (i in total until buf.size) out[(i - total) % total] += buf[i]
        return limit(out)
    }
}
