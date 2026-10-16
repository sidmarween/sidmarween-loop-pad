package de.marween.looppad

/** Mitgelieferte, selbst synthetisierte (lizenzfreie) Samples. */
object SampleLibrary {
    class Sample(val title: String, val file: String, val oneShot: Boolean)

    val samples = listOf(
        Sample("🥁 Kick", "kick.wav", true),
        Sample("🥁 Snare", "snare.wav", true),
        Sample("🥁 Clap", "clap.wav", true),
        Sample("🥁 Rimshot", "rimshot.wav", true),
        Sample("🥁 Hi-Hat zu", "hihat_zu.wav", true),
        Sample("🥁 Hi-Hat offen", "hihat_offen.wav", true),
        Sample("🥁 Tom tief", "tom_tief.wav", true),
        Sample("🥁 Tom hoch", "tom_hoch.wav", true),
        Sample("🔊 808 Bass", "bass_808.wav", true),
        Sample("🎹 Synth Stab", "synth_stab.wav", true),
        Sample("🎹 Pad Akkord", "pad_akkord.wav", true),
        Sample("✨ Riser", "riser.wav", true),
        Sample("✨ Impact", "impact.wav", true),
        Sample("✨ Laser", "laser.wav", true),
        Sample("∞ Loop: Beat 120 BPM", "loop_beat_120.wav", false),
        Sample("∞ Loop: House 120 BPM", "loop_house_120.wav", false),
        Sample("∞ Loop: Bass 120 BPM", "loop_bass_120.wav", false),
        Sample("∞ Loop: Arpeggio 120 BPM", "loop_arp_120.wav", false)
    )
}
