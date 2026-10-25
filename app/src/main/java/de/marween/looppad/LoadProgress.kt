package de.marween.looppad

/**
 * Lade-Fortschritt für lange Vorgänge (Song dekodieren, Tempo-Analyse, ins Raster dehnen).
 * Der ladende Thread legt mit [run] fest, wohin der Fortschritt geht; Dekoder und Warp melden mit [report].
 */
object LoadProgress {
    private val cb = ThreadLocal<((Float) -> Unit)?>()
    private val span = ThreadLocal<FloatArray?>()

    fun <T> run(onProgress: (Float) -> Unit, block: () -> T): T {
        cb.set(onProgress)
        span.set(floatArrayOf(0f, 1f))
        try {
            onProgress(0f)
            return block()
        } finally {
            cb.remove()
            span.remove()
        }
    }

    /** Ab jetzt läuft [report] 0..1 auf den Bereich [from]..[to] des Gesamtfortschritts. */
    fun stage(from: Float, to: Float) {
        val s = span.get() ?: return
        s[0] = from; s[1] = to
        report(0f)
    }

    fun report(f: Float) {
        val c = cb.get() ?: return
        val s = span.get() ?: return
        c(s[0] + (s[1] - s[0]) * f.coerceIn(0f, 1f))
    }
}
