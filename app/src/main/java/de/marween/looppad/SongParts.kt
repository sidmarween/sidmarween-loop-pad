package de.marween.looppad

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Song-Struktur: teilt einen Track in Abschnitte (Intro, Hauptteil, Build-up, Drop, Break, Outro).
 * Grundlage je Takt: Lautheit und Bass-Pegel. Gerechnet wird in 4-Takt-Blöcken im Phrasenraster;
 * Grenzen liegen dort, wo sich Lautheit/Bass deutlich ändern.
 */
object SongParts {
    const val INTRO = 0
    const val MAIN = 1
    const val BUILD = 2
    const val DROP = 3
    const val BREAK = 4
    const val OUTRO = 5
    val NAMES = arrayOf("INTRO", "TEIL", "BUILD-UP", "DROP", "BREAK", "OUTRO")

    /** Abschnitt: Takte [bar0, bar1) und Art. */
    class Part(val bar0: Int, val bar1: Int, val kind: Int)

    /** [e] Lautheit, [l] Bass je Takt (beliebige Skala); [off] = erster Takt einer Phrase (0..3 genügt). */
    fun segment(e: DoubleArray, l: DoubleArray, off: Int): List<Part> {
        val bars = min(e.size, l.size)
        if (bars < 8) return emptyList()
        // Blöcke à 4 Takte im Phrasenraster (der erste kann kürzer sein)
        val starts = ArrayList<Int>()
        starts.add(0)
        var b = Math.floorMod(off, 4)
        if (b == 0) b = 4
        while (b < bars) { starts.add(b); b += 4 }
        val nb = starts.size
        val be = DoubleArray(nb)
        val bl = DoubleArray(nb)
        for (i in 0 until nb) {
            val a = starts[i]
            val z = if (i + 1 < nb) starts[i + 1] else bars
            var se = 0.0; var sl = 0.0
            for (k in a until z) { se += e[k]; sl += l[k] }
            be[i] = se / (z - a); bl[i] = sl / (z - a)
        }
        fun norm(a: DoubleArray) {
            val s = a.sorted()
            val top = max(1e-9, s[(s.size * 0.9).toInt().coerceAtMost(s.size - 1)])
            for (i in a.indices) a[i] = (a[i] / top).coerceAtMost(1.2)
        }
        norm(be); norm(bl)
        // Grenzen: deutliche Änderung (bei 8-Takt-Raster schon etwas weniger)
        val cut = BooleanArray(nb)
        for (i in 1 until nb) {
            val d = abs(be[i] - be[i - 1]) + 1.3 * abs(bl[i] - bl[i - 1])
            val on8 = Math.floorMod(starts[i] - off, 8) == 0
            if (d > 0.28 || (on8 && d > 0.16)) cut[i] = true
        }
        // Segmente
        val seg = ArrayList<IntArray>() // [ersterBlock, letzterBlock+1]
        var s0 = 0
        for (i in 1..nb) if (i == nb || cut[i]) { seg.add(intArrayOf(s0, i)); s0 = i }
        val lev = DoubleArray(seg.size)
        val bass = DoubleArray(seg.size)
        for ((k, sg) in seg.withIndex()) {
            var a = 0.0; var c = 0.0
            for (i in sg[0] until sg[1]) { a += 0.5 * be[i] + 0.5 * bl[i]; c += bl[i] }
            lev[k] = a / (sg[1] - sg[0]); bass[k] = c / (sg[1] - sg[0])
        }
        val maxLev = lev.maxOrNull() ?: 1.0
        val kind = IntArray(seg.size) { MAIN }
        for (k in seg.indices) {
            kind[k] = when {
                bass[k] >= 0.7 && lev[k] >= 0.78 * maxLev -> DROP
                bass[k] < 0.45 && k > 0 && k < seg.size - 1 -> BREAK
                else -> MAIN
            }
        }
        // Build-up: direkt vor einem Drop, ruhiger als der Drop
        for (k in 1 until seg.size) if (kind[k] == DROP && kind[k - 1] != DROP) {
            val len = starts.getOrElse(seg[k - 1][1]) { bars } - starts[seg[k - 1][0]]
            if (len <= 16 && (kind[k - 1] == BREAK || bass[k - 1] < 0.6)) kind[k - 1] = BUILD
        }
        if (kind[0] != DROP && lev[0] < 0.85 * maxLev) kind[0] = INTRO
        val last = seg.size - 1
        if (last > 0 && kind[last] != DROP && lev[last] < 0.85 * maxLev) kind[last] = OUTRO
        // gleiche Nachbarn zusammenfassen
        val out = ArrayList<Part>()
        for ((k, sg) in seg.withIndex()) {
            val a = starts[sg[0]]
            val z = if (sg[1] < nb) starts[sg[1]] else bars
            val prev = out.lastOrNull()
            if (prev != null && prev.kind == kind[k]) out[out.size - 1] = Part(prev.bar0, z, kind[k]) else out.add(Part(a, z, kind[k]))
        }
        return out
    }
}
