package de.marween.looppad

/**
 * 🌋🎸 BASS-LABOR: Einstellungen der beiden Bass-Motoren der BASSLINE.
 *  - MAGMA (Klang 25): Viskosität flüssig → kochend → Ausbruch, Sub-Anker getrennt vom zerstörten Oberteil,
 *    Krater-Filter (4-Pol-Ladder mit Selbstoszillation), Spucke (Pitch-Glitches, Noise-Bursts, FM-Feedback).
 *  - FUNK (Klang 26): Saiten-Modell mit Slap (Daumen) / Pop (Finger) / Ghost-Note, Auto-Wah (Quack und Ow),
 *    Phaser, Formant-Filter (sprechender Bass), Funk-Juice, Slap-Tightness, Thumb-Sub.
 */
object BassLab {
    const val MAGMA = 25
    const val FUNK = 26

    // ---- MAGMA
    @Volatile var visc = 0.4f
    @Volatile var sub = 0.8f
    @Volatile var spit = 0.15f
    @Volatile var drive = 0.4f
    @Volatile var craterCut = 0.45f
    @Volatile var craterRes = 0.35f
    /** Krater-Kick: wird beim Wisch nach rechts auf 1 gesetzt und klingt im Audio-Thread ab. */
    @Volatile var kick = 0f
    fun craterKick() { kick = 1f }

    // ---- FUNK
    @Volatile var juice = 0.55f
    @Volatile var tight = 0.4f
    @Volatile var thumbSub = 0.5f
    @Volatile var talk = 0.35f

    class MPreset(val name: String, val visc: Float, val sub: Float, val spit: Float, val drive: Float, val cut: Float, val res: Float)
    val MAGMA_PRESETS = listOf(
        MPreset("Tiefsee-Sub", 0.05f, 1f, 0f, 0.1f, 0.3f, 0.1f),
        MPreset("Blubbernder Schlamm", 0.48f, 0.8f, 0.1f, 0.35f, 0.4f, 0.45f),
        MPreset("Kochender Kessel", 0.62f, 0.75f, 0.25f, 0.55f, 0.5f, 0.6f),
        MPreset("Vulkan-Ausbruch", 0.92f, 0.7f, 0.35f, 0.8f, 0.6f, 0.5f),
        MPreset("Krater-Pfeifen", 0.4f, 0.8f, 0.05f, 0.4f, 0.35f, 0.95f),
        MPreset("Lava-Spucke", 0.7f, 0.7f, 0.85f, 0.6f, 0.55f, 0.4f)
    )
    class FPreset(val name: String, val juice: Float, val tight: Float, val thumb: Float, val talk: Float)
    val FUNK_PRESETS = listOf(
        FPreset("Bootsy-Wah", 0.85f, 0.55f, 0.5f, 0.3f),
        FPreset("Parliament-Dämpfer", 0.4f, 0.05f, 0.6f, 0.1f),
        FPreset("Knallender Slap", 0.3f, 0.85f, 0.45f, 0f),
        FPreset("Sprechender Bass (Yow-Waw)", 0.7f, 0.5f, 0.5f, 0.9f),
        FPreset("Disco-Dirt", 0.95f, 0.6f, 0.65f, 0.35f),
        FPreset("Club-Funk mit 808", 0.6f, 0.35f, 1f, 0.25f)
    )
    @Volatile var mPreset = -1
    @Volatile var fPreset = -1

    fun applyMagma(i: Int) {
        val k = Math.floorMod(i, MAGMA_PRESETS.size); val p = MAGMA_PRESETS[k]
        mPreset = k; visc = p.visc; sub = p.sub; spit = p.spit; drive = p.drive; craterCut = p.cut; craterRes = p.res
    }
    fun applyFunk(i: Int) {
        val k = Math.floorMod(i, FUNK_PRESETS.size); val p = FUNK_PRESETS[k]
        fPreset = k; juice = p.juice; tight = p.tight; thumbSub = p.thumb; talk = p.talk
    }

    /** BASSLINE auf einen der Motoren stellen (Mono mit etwas Glide); leere Spur bekommt eine passende Linie. */
    fun useOn(wave: Int) {
        val t = InstState.synTracks[InstState.BASS_TRACK]
        t.wave = wave
        t.mono = true
        if (t.glide < 0.05f) t.glide = if (wave == FUNK) 0.12f else 0.2f
        t.atk = 0f; t.rel = if (wave == FUNK) 0.08f else 0.15f
        t.vol = 0.7f
        if (t.oct > -1) t.oct = -1
        t.on = true
        if (!t.hasNotes()) {
            val old = InstState.synCur
            InstState.synCur = InstState.BASS_TRACK
            val list = InstPresets.SYNTHS.filter { it.genre == "Bassline" }
            val p = list.firstOrNull { if (wave == FUNK) it.name.contains("Funk") else it.name.contains("Reese") } ?: list.first()
            InstPresets.applySynth(p)
            t.wave = wave; t.mono = true
            if (wave == FUNK) { for (k in 0 until t.len) if (t.gate[k] && !t.tie[k] && k % 4 == 3) t.vel[k] = 0.35f } // Ghost-Notes
            InstState.synCur = old
        }
    }
}
