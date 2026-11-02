package de.marween.looppad

/**
 * 🎛 LADDER-SYNTH (Klang 36) im Stil des legendären monofonen Analog-Synths aus den 70ern (Moog-Art):
 *  - drei Oszillatoren mit Fußlage (32' … 4'), Wellenform (Dreieck, Säge, Rechteck, Puls breit/schmal), Pegel
 *    und Verstimmung (OSC 2/3), dazu Rauschen
 *  - Mixer mit Übersteuerung (DRIVE) vor dem Filter – der berühmte „fette“ Trick
 *  - 24-dB-Ladder-Filter mit sättigenden Stufen, EMPHASIS bis zur Selbstoszillation (pfeift dann als Sinus)
 *  - Filter- und Lautstärke-Hüllkurve (Attack, Decay, Sustain) + gemeinsamer Release, Glide
 * Spielt auf einer Synth-Spur (Standard: BASSLINE) – Noten, Muster, Export laufen wie bei jeder Spur.
 */
object Ladder {
    const val WAVE = 36
    val WAVE_NAMES = arrayOf("△", "SÄGE", "▢", "PULS", "SCHMAL")
    val RANGE_NAMES = arrayOf("32'", "16'", "8'", "4'")

    val oscWave = intArrayOf(1, 1, 1)
    val oscRange = intArrayOf(1, 1, 0)          // 0 = 32' (eine Oktave tiefer) … 3 = 4'
    val oscLevel = floatArrayOf(1f, 0.8f, 0.6f)
    /** Verstimmung OSC 2 (nach oben) / OSC 3 (nach unten): 0..1 → 0 … 50 Cent */
    @Volatile var detune2 = 0.08f
    @Volatile var detune3 = 0.15f
    @Volatile var noise = 0f
    @Volatile var drive = 0.3f
    @Volatile var cutoff = 0.35f
    @Volatile var emphasis = 0.35f
    @Volatile var contour = 0.55f
    @Volatile var fAtk = 0f; @Volatile var fDec = 0.62f; @Volatile var fSus = 0.25f
    @Volatile var aAtk = 0f; @Volatile var aDec = 0.65f; @Volatile var aSus = 0.85f
    @Volatile var release = 0.15f
    @Volatile var glide = 0.1f
    @Volatile var keyTrack = 0.5f
    @Volatile var track = InstState.BASS_TRACK
    @Volatile var presetIdx = -1

    /** Zeit-Regler 0..1 → Sekunden (2 ms … ~4 s, exponentiell wie am Original). */
    fun secs(v: Float): Double = 0.002 * Math.pow(2000.0, v.toDouble())

    class Preset(
        val name: String, val w: IntArray, val r: IntArray, val l: FloatArray, val d2: Float, val d3: Float, val noise: Float, val drive: Float,
        val cut: Float, val emph: Float, val cont: Float, val fA: Float, val fD: Float, val fS: Float, val aA: Float, val aD: Float, val aS: Float,
        val rel: Float, val glide: Float, val oct: Int, val bass: Boolean
    )

    val PRESETS = listOf(
        Preset("Klassik-Bass (3 Sägen, fett)", intArrayOf(1, 1, 1), intArrayOf(1, 1, 0), floatArrayOf(1f, 0.85f, 0.7f), 0.06f, 0.1f, 0f, 0.45f,
            0.3f, 0.35f, 0.55f, 0f, 0.62f, 0.2f, 0f, 0.65f, 0.85f, 0.12f, 0.08f, -1, true),
        Preset("Funk-Bass (zupfend)", intArrayOf(1, 2, 0), intArrayOf(1, 1, 0), floatArrayOf(1f, 0.6f, 0.5f), 0.04f, 0f, 0f, 0.3f,
            0.22f, 0.45f, 0.75f, 0f, 0.5f, 0f, 0f, 0.55f, 0.35f, 0.1f, 0.05f, -1, true),
        Preset("Sub-Bass (Dreieck, rund)", intArrayOf(0, 0, 0), intArrayOf(1, 0, 1), floatArrayOf(1f, 0.6f, 0f), 0f, 0f, 0f, 0.15f,
            0.4f, 0.1f, 0.15f, 0f, 0.6f, 0.6f, 0f, 0.65f, 1f, 0.15f, 0.06f, -1, true),
        Preset("Prog-Lead (Sägen, Glide)", intArrayOf(1, 1, 2), intArrayOf(2, 2, 1), floatArrayOf(1f, 0.9f, 0.5f), 0.1f, 0.2f, 0f, 0.4f,
            0.42f, 0.45f, 0.5f, 0.15f, 0.7f, 0.55f, 0.02f, 0.7f, 0.9f, 0.3f, 0.35f, 0, false),
        Preset("Robo-Sequenz (Puls)", intArrayOf(3, 4, 2), intArrayOf(1, 1, 2), floatArrayOf(1f, 0.6f, 0.3f), 0.02f, 0f, 0f, 0.2f,
            0.36f, 0.35f, 0.6f, 0f, 0.42f, 0f, 0f, 0.45f, 0f, 0.08f, 0f, 0, false),
        Preset("Acid-Ladder (Resonanz)", intArrayOf(1, 1, 1), intArrayOf(1, 1, 1), floatArrayOf(1f, 0.3f, 0f), 0.02f, 0f, 0f, 0.5f,
            0.18f, 0.82f, 0.7f, 0f, 0.55f, 0.1f, 0f, 0.6f, 0.7f, 0.1f, 0.12f, -1, true),
        Preset("Filter-Swell (weiche Fläche)", intArrayOf(1, 1, 0), intArrayOf(1, 1, 2), floatArrayOf(0.9f, 0.9f, 0.5f), 0.12f, 0.25f, 0.05f, 0.2f,
            0.45f, 0.25f, 0.5f, 0.55f, 0.7f, 0.6f, 0.4f, 0.7f, 1f, 0.55f, 0.2f, 0, false),
        Preset("Pfeifen (Selbstoszillation)", intArrayOf(1, 1, 1), intArrayOf(1, 1, 1), floatArrayOf(0.25f, 0f, 0f), 0f, 0f, 0f, 0.1f,
            0.5f, 0.97f, 0.3f, 0f, 0.5f, 0.8f, 0f, 0.5f, 1f, 0.3f, 0.3f, 0, false),
    )

    fun applyPreset(i: Int) {
        val k = Math.floorMod(i, PRESETS.size)
        val p = PRESETS[k]
        presetIdx = k
        for (j in 0 until 3) { oscWave[j] = p.w[j]; oscRange[j] = p.r[j]; oscLevel[j] = p.l[j] }
        detune2 = p.d2; detune3 = p.d3; noise = p.noise; drive = p.drive
        cutoff = p.cut; emphasis = p.emph; contour = p.cont
        fAtk = p.fA; fDec = p.fD; fSus = p.fS; aAtk = p.aA; aDec = p.aD; aSus = p.aS; release = p.rel; glide = p.glide
        attach()
        InstState.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)].oct = p.oct
    }

    /** Auf die Spur legen: Klang 36, MONO + GLIDE wie am Original. */
    fun attach() {
        val t = InstState.synTracks[track.coerceIn(0, InstState.SYN_TRACKS - 1)]
        t.wave = WAVE
        t.chord = 0
        t.mono = true
        t.glide = glide
        t.atk = 0f
        t.on = true
    }
}
