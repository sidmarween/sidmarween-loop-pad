package de.marween.looppad

/** 📘 Kurzes Handbuch zu den Instrumenten und Werkzeugen (Menü › Hilfe und Instrumente › Werkzeuge). */
object InstHelp {
    val TOPICS: List<Pair<String, String>> = listOf(
        "💾 Speichern, ↶ und MIDI" to """
Alle Instrumente werden mit dem Projekt gespeichert – Regler, Vorlagen, Muster, Satelliten, Kabel, gemaltes Spektrum, Vocal-Chop-Quelle und die Performance-Szenen.

↶ RÜCKGÄNGIG / ↷ WIEDERHOLEN: In jedem Instrument-Feld steht unten eine Leiste. Jede Änderung (Regler, Vorlage, Schalter) lässt sich zurücknehmen – auch nach dem Schließen über das normale ↶.

MIDI: Menü › 🎹 MIDI › Tastatur-Kanal → „Was spielt die Tastatur?“ – ein Pad, Vocal-Chop, Chord-Pad oder FX-Generator. Unter „🎛 Regler & Fader zuordnen“ gibt es Ziele für den Morph-Regler der Performance-Szenen, die Vocal-Chop-Tonhöhe und die FX-Intensität.""",
        "🎯 Takt & RASTER" to """
Alle Instrumente laufen am selben Takt-Raster wie der Sequenzer (inkl. Swing).
🎯 RASTER (Instrumente › Werkzeuge, Standard AN): Orbits, Rhythm Architect und Euklid-POLY spielen exakt auf 16teln. AUS = gewollt frei: Orbits nach Umlaufbahn, Architect mit eigenem Chaos-Swing und Human-Jitter, Euklid-POLY teilt den Takt in n gleiche Teile (5 gegen 4).
Stolpert trotzdem alles hörbar, kommt das Handy nicht hinterher – dann erscheint ein Hinweis mit der Rechenlast. Instrumente ausschalten, die gerade nicht klingen, oder bei Latenz einen größeren Puffer wählen.""",
        "🥁 DRUM 909 & ACID 303" to """
DRUM 909: Drumcomputer mit 11 Stimmen und 7 Klang-Modellen je Stimme (909, 808, Techno …). ▦ PRESETS hat Beats vieler Genres.
ACID 303: Acid-Bass mit Slide und Akzent – Cutoff, Resonanz, Hüllkurve wie am Original. Vorlagen und der Zufalls-Generator stehen unter PRESETS.""",
        "🎹 SYNTH, KEYS, BASSLINE" to """
Fünf Synth-Spuren: SYNTH 1–3, KEYS und BASSLINE. Jede hat ihren eigenen Klang (KLANG ▾): Piano, E-Piano, Orgel, Leads, Wavetables, Bässe, Chiptune und Orchester.
Akkorde, Arpeggiator, MONO + GLIDE. Presets je Spur, Akkordfolgen-Generator, MIDI-Datei laden. 💾 WAV speichert die Spur einzeln.""",
        "🎺 Orchester: ORCH-HIT, BRASS, STRINGS" to """
ORCH-HIT: der 90er-Orchester-Schlag (Streicher + Blech + Pauke). Kurz anschlagen, Akkord dazu – fertig ist der Rave-Stab.
BRASS: Bläsersatz. FILTER-HÜLLKURVE hoch = knackige Stabs, ATTACK hoch = langsames Anschwellen (Swell).
STRINGS: Streicher-Ensemble. ATTACK 0 = Pizzicato/Stab, ATTACK hoch = Fläche. VERSTIMMUNG = Breite.
Menü › Instrumente › 🎺 ORCHESTRA-HITS: 8 Vorlagen auf KEYS.""",
        "⬡ EUKLID" to """
Generative Polyrhythmen: vier Spuren, je k Schläge gleichmäßig auf n Schritte verteilt. DICHTE = mehr Schläge, KOMPLEXITÄT = ungerade Längen, MORPH = Wandel über die Zeit. POLY lässt Spuren gegeneinander laufen.""",
        "🌌 ATMOS" to """
Klangteppich und Drones in der Tonart des Songs. XY-Fläche: Helligkeit und Bewegung. „Schnipsel einfangen“ macht aus dem laufenden Mix eine Granular-Fläche.""",
        "🎤 VOCAL-HARMONIZER" to """
Chor aus einer Stimme (Mikro oder Gesang eines DJ-Decks). Akkord-Pads halten = Stimmen darüber/darunter; AUTO singt ohne Tasten Terzen, Quinten, Gospel …""",
        "🐺 WOLF (Loop-Zerhacker)" to """
Zerhackt den Loop eines Pads im Takt: Stottern, Rückwärts, Pitch, Gate, Tape-Stop. CHAOS = wie wild, DICHTE = wie oft. HALTEN friert den jetzigen Takt ein.""",
        "🧬 RHYTHM ARCHITECT" to """
Polyrhythmus- und Textur-Synth mit vier Spuren unterschiedlicher Länge. Zellen antippen = Wahrscheinlichkeit 100/75/50/25 %/aus. Spurname = Klang. XY: Dichte und Material, CHAOS bringt Swing und Ghost-Notes.""",
        "🪐 ORBITS" to """
Gravitations-Synth: Satelliten kreisen um einen Kern. Jeder Umlauf ist ein Ton, Zusammenstöße lösen Effekte aus. Satelliten ziehen/antippen, leere Fläche = Gravity Well (zieht oder stößt ab).""",
        "🖐 TOUCH-LAB" to """
Fünf Touch-Instrumente:
• MORPH-MATRIX – vier Klänge in den Ecken, der Finger mischt.
• WAVE TERRAIN – Finger fährt über eine Klanglandschaft.
• SPECTRAL PAINTER – Klang ins Spektrum malen, läuft im Takt.
• GESTEN – Kreisen = Modulation, Wischen = Helligkeit, mehrere Finger = Akkord.
• TOUCH MODULAR – Module mit Kabeln verbinden (Vorlagen-Patches).""",
        "🎛 LADDER (Moog-Art)" to """
Monofoner Analog-Synth im Stil der großen 70er-Klassiker: drei Oszillatoren (Wellenform und Fußlage antippen, Pegel und Verstimmung ziehen), Rauschen, DRIVE übersteuert den Mixer (macht fett), 24-dB-Ladder-Filter – EMPHASIS bis zum Pfeifen (Selbstoszillation), KONTUR = wie weit die Filter-Hüllkurve öffnet.
Zwei Hüllkurven (Filter F-…, Lautstärke A-…) mit gemeinsamem RELEASE, GLIDE wie am Original. Spielt auf BASSLINE (umschaltbar). 8 Vorlagen von Klassik-Bass bis Prog-Lead, ♪ LINIE schreibt eine Bassline.""",
        "🔊 BASS-LABOR: MAGMA, FUNK, ORGAN-BASS" to """
MAGMA: zäher, glühender Bass – Viskosität, Krater-Filter, Spucke (Glitches).
FUNK-SLAP: Slap (Daumen), Pop (Finger), Ghost-Notes; Auto-Wah und sprechender Formant.
ORGAN-BASS: perkussiver Garage-/Deep-House-Orgelbass, AUTO-LEGATO bindet direkt folgende Noten.
♪ LINIE schreibt eine passende Bassline in die BASSLINE-Spur.""",
        "🏠 VIBE-SYNTH" to """
House-Stabs: eine Taste = ein Club-Akkord (M1-Stil). DRIFT = analoges Schwanken, PUMP drückt im Takt (Sidechain), STAB ↔ ACID schaltet auf einen 303-Hybrid.""",
        "⚡ CHAOS-TRIGGER" to """
Zerlegt den laufenden Mix live: Pads halten = Stutter mit Gravitation (Zeile = Länge, Spalte = Art), Resonatoren antippen, unten halten = Portal-Warp, loslassen = Drop.""",
        "🎲 GROOVE-GENERATOR & 🧫 ORGANISM" to """
GROOVE-GENERATOR: kompletter Beat (Drums, Bass, Akkorde) auf Knopfdruck in vielen Genres – einzelne Teile neu würfeln.
GROOVE ORGANISM: der 909-Beat verändert sich an jeder Eins ein wenig nach deinen Regeln (Mutation, Dichte, Ghost-Notes, Akzente). ❄ friert ein, Stimmen-Namen antippen = sperren.""",
        "🚀 FX-GENERATOR" to """
Übergänge im Takt: Riser, Downlifter, Impact, Sub-Drop, Reverse-Crash, Sweep, Snare-Build, Whoosh. LÄNGE wählen – der Effekt startet auf der nächsten Eins und endet genau auf einer Eins. 💥 DROP-PAKET: Riser + Snare-Build, danach Impact + Sub-Drop.""",
        "🎼 CHORD-PAD & 🤖 VOCODER" to """
CHORD-PAD: die Akkorde der Tonart als Pads (I–VII), Stimmführung automatisch, STRUM wie eine Gitarre, Bass läuft mit. ○ FOLGE AUFNEHMEN → 📝 IN SPUREN schreibt sie in KEYS und BASSLINE.
VOCODER: deine Stimme (Mikro) formt einen Synth-Akkord – 16 Bänder. Folgt dem Chord-Pad oder eigenen Tasten; ROBOTER = ein fester Ton. Kopfhörer benutzen!""",
        "🗣 VOCAL-CHOP" to """
Zerlegt eine Stimme in Silben und legt sie auf 16 Chop-Pads.
Quelle: Demo-Stimme, Loop eines Pads (z. B. Gesang aus den KI-Stems) oder ● MIKRO AUFNEHMEN (läuft der Takt, wird ein Takt eingezählt; der Pegel wird angezeigt).
Tasten unten spielen den gewählten Chop in jeder Tonhöhe. GATE/ONE-SHOT, RÜCKWÄRTS, STUTTER im Takt. ▶ MUSTER: 10 Vorlagen (House-Hook, Future-Bass …). → PADS legt die Chops als ⚡ One-Shots auf freie Pads.""",
        "🔁 LIVE-LOOPER" to """
Vier Spuren wie bei einer RC-505 (Pads 13–16). Großer Knopf: Aufnahme → Abspielen → Overdub → Abspielen. LÄNGE: FREI (zweites Tippen schließt den Loop) oder 1–8 Takte (startet auf der Eins, schließt sich selbst). FX je Spur: Rückwärts, Oktave, Halbton, Normalisieren, Effekt-Kette. CLEAR halten löscht.""",
        "🎛 PERFORMANCE-SZENEN" to """
Zwei Mix-Zustände A und B (Lautstärken, Filter, Pads und Instrumente an/aus) und ein Morph-Regler dazwischen. AUTO-MORPH fährt ab der nächsten Eins über 1–16 Takte. Schalter springen in der Mitte.
SZENEN 1–6: halten = jetzigen Mix speichern, antippen = dorthin morphen.
Vorlagen machen aus dem jetzigen Mix ein B (Breakdown, Filter-Sweep, Drop …).
Im Song-Editor (Automation am MASTER) gibt es die Spur SZENEN-MORPH: unten = A, oben = B.""",
    )
}
