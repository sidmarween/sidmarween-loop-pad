# Marv PAD

Android-App für Musiker: 16 Neon-Pads, XY-Effektfläche und Drehregler im Kaoss-Pad-Stil zum Live-Loopen übers Mikrofon.

- **Pad halten** – nimmt auf, solange du hältst. Der erste Loop wird zum Taktgeber. Alle weiteren Loops rasten automatisch auf sein Raster ein: Länge wird auf 1/8, 1/4, 1/2, 1, 2, 4 oder 8 Takte gerundet, der Start auf den nächsten Rasterpunkt.
- **Pad mit Loop halten** – Overdub: nimmt zusätzlich auf den laufenden Loop drauf.
- **Antippen** – Loop an / aus (steigt immer synchron zu den anderen Pads ein).
- **CLEAR**, dann Pad antippen – Loop löschen.
- **ALLE STUMM / ALLE AN** – alles auf einmal.

## Menü ☰ (oben rechts)

- **Projekt speichern / laden** – sichert alles zum Weitermachen: Pads (Audio), Modi, Tempo, Sequenzer-Pattern, Effekt und Regler.
- **Neues Projekt** – alles leeren.
- **Meine Aufnahmen** – jede Mikrofon-Aufnahme wird automatisch gespeichert; von hier auf ein Pad laden, als WAV exportieren oder löschen. Sie erscheinen auch oben in der Bibliothek.
- **Design wählen**

Nach einer **Pattern-Aufnahme**: als Audio (WAV, nahtloser 1-Takt-Loop) oder als Projekt speichern.
Nach einer **Song-Aufnahme**: als Audio (WAV), als Projekt (inkl. Song) speichern oder verwerfen.

## Designs & Display

Im Menü ☰ → Design wählen (Standard: NOIR) – NEON (Regenbogen), GLOW (Orange glühend), STUDIO (Grau & Orange), HUD (Tech-Linien), RETRO (Grau/Grün/Orange mit Spektrum-Anzeigen für Ausgang und Mikrofon), COMIC (Pop-Art), GLAS, PASTELL, HOLZ (Walnuss/Messing/Leinen), CREME, NOIR (Schwarz im Walnussrahmen), GRAU. Die Wahl wird gespeichert.
Das **Display** in der Kopfzeile zeigt den aktuellen Effekt, die X/Y-Werte und den Takt. Links antippen öffnet die Effektliste, rechts das Tempo.

## Master-Tempo

Im Display rechts auf **BPM / TEMPO ▸** tippen: Tempo 60–200 BPM (Regler, −/+, **Tap-Tempo**), **Metronom** an/aus, oder **Automatisch** (erster Loop bestimmt das Tempo). Ein festes Tempo gilt für neue Aufnahmen (werden auf Takte gerundet), Sequenzer, Delay/Stutter und Metronom.

## Effekt-Ziel

Der Effekt wirkt auf den **ganzen Song** oder **nur auf ein Pad**: im •••-Menü des Pads „Effekt nur auf dieses Pad“ oder in der Effektliste (Display links antippen) „Nur auf ein Pad …“. Das Display zeigt das Ziel (→ALLE / →P3).

## 🎹 Tastatur

Der kleine Klavier-Knopf unter jedem Pad blendet eine Tastatur (2 Oktaven, Oktave −/+) ein: das Sample wird höher/tiefer gespielt, mehrere Finger = Akkord. Mit „📌 Ton fürs Pad“ wird der gespielte Ton zur festen Tonhöhe des Pads (gilt für ⚡-Antippen und den Sequenzer).
Unten in der Tastatur: **● REC** (Pattern oder Song), **▶** und **■**. Im Pattern-Modus werden gespielte Töne und Akkorde mit Tonhöhe und Länge auf das 16-Schritt-Raster gerastet und laufen im Sequenzer mit (Punkt oben im Schritt = Note).

## 〰 Wave-Editor

Im •••-Menü: Start-/Endpunkt an der Wellenform ziehen, **Zuschneiden**, mit dem **SLICES**-Regler in Stücke teilen und **Slicen (Tempo)** – die Slices starten auf ihren Rasterplätzen, der Loop folgt so jedem Master-Tempo ohne Tonhöhenänderung (Länge über den **TAKTE**-Regler). **Slices auf Pads** legt die Stücke als ⚡-One-Shots auf freie Pads.

## Sequenzer

Statt der unteren Pad-Reihe: 16-Schritt-Sequenzer, synchron zum Takt (ohne Takt: 120 BPM).
- **● REC** – Auswahl:
  - **Pattern aufnehmen** – startet die Wiedergabe; angetippte ⚡-One-Shot-Pads landen auf dem nächsten Schritt.
  - **Song aufnehmen** – nimmt alles auf, was du hörst (Loops, One-Shots, Sequenzer, Effekte), bis 15 Minuten. REC oder STOP beendet die Aufnahme, danach als WAV speichern.
- **▶ PLAY** – Pattern abspielen · **■ STOP** – anhalten · **STOP halten** – Pattern leeren
- **Schritte antippen** – Schritt für das zuletzt gespielte ⚡-Pad an/aus
- **Schritt hoch/runter ziehen** – Lautstärke dieses Schritts (Füllhöhe zeigt sie an)

## Pad-Modi

Im **•••**-Menü jedes Pads: **∞ LOOP** (läuft im Takt endlos, Regenbogen-Leuchten) oder **⚡ ONE SHOT** (spielt bei jedem Antippen einmal, Feuer-Leuchten).

## Bibliothek

„Aus Bibliothek laden" im •••-Menü, nach Kategorien: 🎙 Meine Aufnahmen · 🥁 Drums · 🪘 Percussion · 🎹 Tasten · 🎼 Melodisch · 🔊 Synth & Bass · ✨ FX · ∞ Loops (90–174 BPM) – über 90 Sounds.
Quellen: selbst synthetisiert (frei) und die **Versilian Community Sample Library (VCSL)**, CC0 1.0 (gemeinfrei) – https://github.com/sgossner/VCSL. Tonale Samples liegen meist auf C4 und lassen sich mit der 🎹-Tastatur spielen.

## Import / Export

Unter jedem Pad sitzt ein kleiner **•••**-Knopf:
- **Audio importieren** – WAV oder MP3 aufs Pad laden. Gibt es schon einen Takt, wird die Datei auf die passende Rasterlänge gebracht und läuft synchron.
- **Loop exportieren** – speichert den Loop als WAV (mono, 16 Bit).
- **Loop löschen**

## Resample & Live-Aufnahme

**Eingang pro Pad** (•••-Menü → „Eingang“): **🎙 Mikrofon** oder **〰 Wave** – Wave nimmt auf ein leeres Pad auf, was die App gerade ausgibt (inkl. Effekte und XY-Modulation). So lässt sich ein Pad mit Effekt direkt auf das Nachbar-Pad aufnehmen. Das Symbol unten links am Pad zeigt den Eingang.

Unten zwei Schalter:
- **🎙 ALLE MIKRO ↔ 🔁 ALLE WAVE** – stellt den Eingang aller Pads auf einmal um (zeigt „GEMISCHT“, wenn Pads unterschiedlich eingestellt sind).
- **✋ HALTEN ↔ ⚡ LIVE 1/2/4 TAKTE** – Im Live-Modus ein leeres Pad nur antippen: es zeigt „BEREIT“, die Aufnahme startet auf dem nächsten Takt, läuft genau die eingestellte Länge, stoppt selbst und spielt sofort synchron weiter. Erneutes Antippen bricht ab. Ohne Tempo: Antippen startet, nochmal Antippen stoppt.

## Effekte

Über den Pads liegt die **XY-Fläche**: Finger drauf = Effekt an, X- und Y-Achse modulieren ihn. **🔒 HOLD** (Ecke oben rechts) hält den Effekt an der letzten Position aktiv, auch ohne Finger – auf der Fläche ziehen verschiebt ihn weiter, nochmal HOLD schaltet ihn aus.
**● REC** daneben speichert Fingerbewegungen: tippen → Fläche berühren startet die Aufnahme → nochmal tippen beendet sie. Die Bewegung wird auf Schläge gerundet und läuft als **▶ AUTO** synchron zum Takt in Schleife (Cursor fährt mit). Selbst anfassen hat Vorrang; AUTO antippen löscht sie.
Darüber vier **Drehregler** (nach oben/unten ziehen): MIX, zwei effektabhängige Parameter, VOL.
Der **Effekt-Knopf** schaltet durch (links tippen = zurück, rechts = weiter, lange drücken = Liste):

**Filter:** LOWPASS · HIGHPASS · BANDPASS · NOTCH (je 12/24/36/48 dB über den STEIL-Regler) · BELL (Peaking-EQ, ±18 dB, 1–3 Stufen) · PEAK (Resonanzspitze) · COMB (Feedback oder Feedforward)

**Modulation & Klang:** PHASER · TREMOLO (frei/Takt) · VIBRATO · EQ (3 Bänder) · FILTER (Multimode) · SATURATE · OVERDRIVE

**Granular & Spektral:** GRAIN DLY · PITCH (Pitch-Shifter) · STRETCH (Time-Stretch) · GRANULAR (Granular-Wolke) · KRISTALL (rückwärts gepitchte Granular-Echos) · SMEAR · FREEZE (Spektral-Freeze/Blur)

**Experimentell:** RINGMOD · FM · FREQ SHIFT · RESONATOR (Akkord-Resonatoren) · SHUFFLE (Buffer-Shuffler im Takt) · SEQ-FX (Effekt-Sequenzer pro Schritt) · MULTI-FX (Morph-Kette Filter/Drive/Echo)

**Weitere:** DELAY (taktsynchron) · STUTTER (taktsynchron) · FLANGER · CHORUS · REVERB · HALL · VOCODER (Loops oder Mikrofon steuern einen Synth-Träger) · DISTORTION · CRUSH

Maximal 30 Sekunden pro Aufnahme. Kopfhörer verhindern, dass laufende Loops mit aufgenommen werden.

## Installieren

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt sie unter **Releases** ab.

© marween
