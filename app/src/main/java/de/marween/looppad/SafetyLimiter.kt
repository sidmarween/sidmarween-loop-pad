package de.marween.looppad

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Schutz-Limiter ganz am Ende der Master-Kette (Stereo verkoppelt, immer aktiv).
 * Statt Spitzen hart abzuschneiden (klirrt), wird die Lautstärke kurz vorher sanft abgesenkt:
 * 1 ms Vorausschau, Absenkung als Rampe, weiches Zurückkehren. Unterhalb der Grenze völlig unverändert.
 */
class SafetyLimiter(private val sr: Int, private val ceiling: Float = 0.93f, releaseMs: Double = 80.0) {
    /** Vorausschau in Samples (= zusätzliche Verzögerung). */
    val look = max(8, sr / 1000)
    // Verzögerung look − 1: dann deckt die Rampe (Mittel über look Werte) genau das Spitzen-Sample ab
    private val dl = FloatArray(look - 1)
    private val dr = FloatArray(look - 1)
    private var dpos = 0
    private val req = FloatArray(look)    // nötige Verstärkung je Sample (Fenster für das Minimum)
    private val box = FloatArray(look)    // Fenster für die Glättung
    private var pos = 0
    private var env = 1f
    private var sum = look.toDouble()
    private var cnt = 0
    private val rel = (1.0 - exp(-1.0 / (releaseMs * 0.001 * sr))).toFloat()
    /** Aktuelle Absenkung in dB (Anzeige). */
    @Volatile var grDb = 0f

    init { java.util.Arrays.fill(req, 1f); java.util.Arrays.fill(box, 1f) }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        var minG = 1f
        for (i in 0 until n) {
            val a = max(abs(l[i]), abs(r[i]))
            val g = if (a > ceiling && a.isFinite()) ceiling / a else if (a.isFinite()) 1f else 0f
            req[pos] = g
            // Minimum der nötigen Verstärkung im Vorausschau-Fenster
            var m = 1f
            for (k in 0 until look) { val v = req[k]; if (v < m) m = v }
            env = if (m < env) m else env + (m - env) * rel
            // Rampe: gleitender Mittelwert über das Fenster → die volle Absenkung ist genau beim Spitzen-Sample erreicht
            sum += env - box[pos]
            box[pos] = env
            if (++cnt >= 4096) { cnt = 0; var s = 0.0; for (v in box) s += v; sum = s }
            val gain = (sum / look).toFloat().coerceAtMost(1f)
            if (gain < minG) minG = gain
            val ol = dl[dpos]; val or = dr[dpos]
            dl[dpos] = l[i]; dr[dpos] = r[i]
            if (++dpos >= dl.size) dpos = 0
            l[i] = ol * gain
            r[i] = or * gain
            if (++pos >= look) pos = 0
        }
        val gr = if (minG < 0.9999f) (-20.0 * kotlin.math.log10(minG.toDouble())).toFloat() else 0f
        grDb = max(gr, grDb * 0.9f)
    }
}
