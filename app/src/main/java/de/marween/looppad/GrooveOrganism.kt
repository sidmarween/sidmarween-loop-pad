package de.marween.looppad

import kotlin.math.max
import kotlin.math.min

/**
 * 🧫 GROOVE ORGANISM: der 909-Beat entwickelt sich Takt für Takt selbst weiter – innerhalb deiner Regeln.
 * Regeln: MUTATION (wie viel pro Takt), DICHTE (mehr/weniger Schläge), GHOSTS, AKZENTE, ANKER (Kick auf den Vierteln bleibt),
 * gesperrte Stimmen. EINFRIEREN hält den Stand, ZURÜCK holt den Ausgangs-Beat, ÜBERNEHMEN macht den Stand zum neuen Ausgang.
 */
object GrooveOrganism {
    @Volatile var on = false
    @Volatile var frozen = false
    @Volatile var mutation = 0.35f
    @Volatile var density = 0.5f
    @Volatile var ghosts = 0.4f
    @Volatile var accents = 0.3f
    @Volatile var anchor = true
    /** gesperrte Stimmen (BD SD LT MT HT RS CP CH OH CR RD) */
    val locked = BooleanArray(InstState.DV)
    class Rules(val name: String, val mutation: Float, val density: Float, val ghosts: Float, val accents: Float, val anchor: Boolean, val lockedVoices: IntArray)
    val PRESETS = listOf(
        Rules("Minimal (sanft)", 0.2f, 0.4f, 0.5f, 0.2f, true, intArrayOf(1, 6)),
        Rules("House-Groove", 0.35f, 0.5f, 0.4f, 0.3f, true, intArrayOf(6)),
        Rules("Techno rollend", 0.45f, 0.65f, 0.3f, 0.45f, true, intArrayOf()),
        Rules("Broken Beat (wild)", 0.75f, 0.6f, 0.5f, 0.5f, false, intArrayOf()),
        Rules("Nur Hats & Percussion", 0.6f, 0.6f, 0.45f, 0.35f, true, intArrayOf(0, 1, 6))
    )
    @Volatile var presetIdx = -1
    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size); val p = PRESETS[k]
        presetIdx = k; mutation = p.mutation; density = p.density; ghosts = p.ghosts; accents = p.accents; anchor = p.anchor
        java.util.Arrays.fill(locked, false); for (v in p.lockedVoices) if (v in locked.indices) locked[v] = true
    }
    @Volatile var generation = 0
    @Volatile var lastChange = ""
    /** zuletzt geänderte Zellen (Anzeige): Stimme*100+Schritt */
    val changed = java.util.concurrent.CopyOnWriteArrayList<Int>()

    private var base: Array<IntArray>? = null
    private var baseVel: Array<FloatArray>? = null
    private val rnd = java.util.Random()

    /** Ausgang merken (beim Start / ÜBERNEHMEN). */
    fun capture() {
        val st = InstState
        base = Array(InstState.DV) { st.drumSteps[it].copyOf() }
        baseVel = Array(InstState.DV) { st.drumVel[it].copyOf() }
        generation = 0
    }

    fun restore() {
        val b = base ?: return
        val bv = baseVel ?: return
        for (v in 0 until InstState.DV) { System.arraycopy(b[v], 0, InstState.drumSteps[v], 0, InstState.MAX); System.arraycopy(bv[v], 0, InstState.drumVel[v], 0, InstState.MAX) }
        generation = 0
        changed.clear()
    }

    fun start() {
        if (!InstState.drumHasNotes()) { InstState.drumApplyPreset(0); InstState.drumOn = true }
        capture()
        on = true
    }

    /** Vom Sequenzer an jeder Eins aufgerufen. */
    fun onBar() {
        if (!on || frozen) return
        val st = InstState
        val len = st.drumLen.coerceIn(16, InstState.MAX)
        val b = base ?: return
        val n = max(1, (mutation * mutation * 10).toInt() + if (rnd.nextFloat() < mutation) 1 else 0)
        changed.clear()
        var desc = ""
        // Stimmen mit Inhalt (und nicht gesperrt) – leere Stimmen bekommen nur selten etwas
        val voices = (0 until InstState.DV).filter { v -> !locked[v] && (b[v].any { it > 0 } || rnd.nextFloat() < 0.15f * density) }
        if (voices.isEmpty()) return
        repeat(n) {
            val v = voices[rnd.nextInt(voices.size)]
            val k = rnd.nextInt(len)
            val row = st.drumSteps[v]
            val baseHits = (0 until len).count { b[v][it % 16 + (it / 16) * 16] > 0 }.coerceAtLeast(1)
            val nowHits = (0 until len).count { row[it] > 0 }
            val wantMore = nowHits < baseHits * (0.6f + density * 0.9f)
            // Anker: Kick auf den Vierteln nicht anfassen
            if (anchor && v == 0 && k % 4 == 0) return@repeat
            val r = rnd.nextFloat()
            when {
                r < 0.35f && row[k] == 0 && wantMore -> {
                    // neuer Schlag, eher auf Offbeats; manchmal als Ghost
                    val off = k % 2 == 1 || rnd.nextFloat() < 0.4f
                    if (!off) return@repeat
                    row[k] = 1
                    st.drumVel[v][k] = if (rnd.nextFloat() < ghosts) 0.3f + rnd.nextFloat() * 0.15f else 0.75f
                    desc = "+ ${st.drumNames[v]}"
                }
                r < 0.55f && row[k] > 0 && !wantMore -> { row[k] = 0; desc = "− ${st.drumNames[v]}" }
                r < 0.75f && row[k] > 0 -> {
                    // verschieben (±1 Schritt)
                    val to = Math.floorMod(k + if (rnd.nextBoolean()) 1 else -1, len)
                    if (row[to] == 0 && !(anchor && v == 0 && to % 4 == 0)) { row[to] = row[k]; st.drumVel[v][to] = st.drumVel[v][k]; row[k] = 0; desc = "↔ ${st.drumNames[v]}" }
                }
                row[k] > 0 -> {
                    // Dynamik: Akzent oder Ghost
                    if (rnd.nextFloat() < accents) { row[k] = 2; st.drumVel[v][k] = 1f; desc = "! ${st.drumNames[v]}" }
                    else { row[k] = 1; st.drumVel[v][k] = min(1f, max(0.25f, st.drumVel[v][k] + (rnd.nextFloat() - 0.5f) * 0.4f)) }
                }
                else -> {}
            }
            changed.add(v * 100 + k)
        }
        // nicht zu weit weg: zu viel Abweichung → einzelne Zellen zurück zum Ausgang
        var diff = 0
        for (v in 0 until InstState.DV) for (k in 0 until len) if ((st.drumSteps[v][k] > 0) != (b[v][k] > 0)) diff++
        val maxDiff = (6 + mutation * 18).toInt()
        while (diff > maxDiff) {
            val v = rnd.nextInt(InstState.DV); val k = rnd.nextInt(len)
            if ((st.drumSteps[v][k] > 0) != (b[v][k] > 0)) { st.drumSteps[v][k] = b[v][k]; st.drumVel[v][k] = baseVel!![v][k]; diff-- }
        }
        generation++
        if (desc.isNotEmpty()) lastChange = desc
    }
}
