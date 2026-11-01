package de.marween.looppad

/**
 * 🎹 ORGAN-BASS (Klang 28): perkussiver Orgel-Bass wie in Deep House / UK Garage (M1-„Organ“-Art).
 * Zugriegel-Sinusse + holziger FM-Anschlag + Klick, sehr kurze Decays; AUTO-SUSTAIN/LEGATO: direkt
 * aufeinanderfolgende Noten werden gebunden (rollende Linien, kein Neuanschlag), gehaltene Noten klingen weiter.
 */
object OrganBass {
    const val WAVE = 28
    @Volatile var click = 0.55f
    @Volatile var decay = 0.3f
    @Volatile var wood = 0.6f
    @Volatile var bars = 0.45f
    @Volatile var sub = 0.4f
    @Volatile var tone = 0.5f
    @Volatile var legato = false
    @Volatile var preset = -1

    class P(val name: String, val click: Float, val decay: Float, val wood: Float, val bars: Float, val sub: Float, val tone: Float, val legato: Boolean)
    val PRESETS = listOf(
        P("Show-Me-Love-Orgel", 0.6f, 0.32f, 0.55f, 0.55f, 0.35f, 0.55f, false),
        P("Garage-Orgelbass", 0.7f, 0.22f, 0.7f, 0.4f, 0.45f, 0.5f, false),
        P("Deep-House-Holz", 0.4f, 0.28f, 0.85f, 0.25f, 0.5f, 0.35f, false),
        P("Kurzer Stab", 0.8f, 0.1f, 0.5f, 0.6f, 0.3f, 0.65f, false),
        P("Rollende Legato-Linie", 0.45f, 0.45f, 0.5f, 0.4f, 0.55f, 0.45f, true),
        P("Percussion-Orgel (hell)", 0.9f, 0.18f, 0.35f, 0.85f, 0.15f, 0.8f, false)
    )
    fun apply(i: Int) {
        val k = Math.floorMod(i, PRESETS.size); val p = PRESETS[k]
        preset = k; click = p.click; decay = p.decay; wood = p.wood; bars = p.bars; sub = p.sub; tone = p.tone; legato = p.legato
    }
}
