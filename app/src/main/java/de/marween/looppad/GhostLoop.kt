package de.marween.looppad

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Audio-Loop für den Ghost-Producer: wird ins aktuelle Tempo gedehnt (Takte bleiben Takte) und – wenn er
 * eine erkennbare Tonart hat – in die Tonart des Tracks gepitcht, ohne das Tempo zu ändern.
 */
object GhostLoop {
    @Volatile var src: FloatArray? = null
        private set
    @Volatile var srcBars = 1f
        private set
    /** Tonart des Loops (Keys-Zahl), -1 = ohne Tonart (z. B. Drums). */
    @Volatile var srcKey = -1
        private set
    @Volatile var name = "–"
        private set
    @Volatile var data: FloatArray? = null
        private set
    @Volatile var on = true
    @Volatile var gain = 0.85f
    @Volatile private var builtLen = 0L
    @Volatile var builtShift = 0
        private set
    @Volatile var building = false
        private set

    fun clear() { src = null; data = null; name = "–"; srcKey = -1 }

    /** Loop übernehmen: Tempo/Länge und Tonart erkennen (im Hintergrund aufrufen). */
    fun load(x: FloatArray, sr: Int, title: String) {
        val info = Warp.analyze(x, sr, title)
        val body = if (info.cut) {
            val o = info.offset.coerceIn(0, max(0, x.size - 2))
            val want = (info.bars * 240.0 / info.bpm * sr).toInt()
            x.copyOfRange(o, minOf(x.size, o + max(2, want)))
        } else x
        srcBars = max(0.25f, info.bars)
        srcKey = detectKey(body, sr)
        name = title
        src = body
        data = null
        builtLen = 0L
        on = true
    }

    private fun detectKey(x: FloatArray, sr: Int): Int {
        val f = max(1, (sr / 11025.0).roundToInt())
        val m = x.size / f
        if (m < 8192 * 2) return -1
        val y = FloatArray(m) { i -> var s = 0f; for (j in 0 until f) s += x[i * f + j]; s / f }
        val (k, conf) = Keys.detectMono(y, sr.toDouble() / f)
        return if (conf >= 0.3f) k else -1
    }

    fun desiredShift(): Int = if (srcKey < 0) 0 else Ghost.shiftTo(Keys.root(srcKey), Keys.isMinor(srcKey)) ?: 0

    /** Aus der UI aufrufen (pro Bild): neu dehnen/pitchen, wenn Tempo oder Tonart sich geändert haben. */
    fun poll() {
        val s = src ?: return
        if (building) return
        val m = Engine.masterFrames()
        if (m <= 0L) return
        val sh = desiredShift()
        if (data != null && builtLen == m && builtShift == sh) return
        building = true
        val sr = Engine.sampleRate
        Thread {
            try {
                val target = (srcBars * m).toInt().coerceAtLeast(64)
                val pitched = if (sh != 0) Warp.resampleLen(s, (s.size / 2.0.pow(sh / 12.0)).toInt()) else s
                data = Warp.stretch(pitched, target, sr)
                builtLen = m
                builtShift = sh
            } catch (e: Throwable) {
                data = null
                src = null
            } finally { building = false }
        }.apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Mischer-Thread: im Takt mitlaufen (Position aus dem Master-Raster, auch während neu gedehnt wird). */
    fun render(out: FloatArray, n: Int, w: Long) {
        val d = data ?: return
        if (!on || !Ghost.on || !Engine.seqPlaying) return
        val m = Engine.masterFrames()
        if (m <= 0L) return
        val loopLen = max(1L, (srcBars * m).toLong())
        val ms = Engine.masterStartFrame()
        val g = gain
        val size = d.size
        for (i in 0 until n) {
            val rel = Math.floorMod(w + i - ms, loopLen)
            val idx = (rel * size / loopLen).toInt().coerceIn(0, size - 1)
            out[i] += d[idx] * g
        }
    }
}
