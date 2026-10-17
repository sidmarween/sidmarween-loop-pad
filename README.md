# Marv PAD

Android-App für Musiker: 16 Neon-Pads, XY-Effektfläche und Drehregler im Kaoss-Pad-Stil zum Live-Loopen übers Mikrofon.

- **Aufnahme-Modus** (Schalter unten rechts): **✋ HALTEN** – nimmt auf, solange der Finger liegt; **👆 TIPPEN** – 1. Tipp startet, 2. Tipp setzt den Endpunkt. In beiden Fällen entsteht automatisch ein synchroner Loop mit **1, 2, 4, 8, 16 oder 32 Takten** – was der Haltezeit am nächsten liegt; der Start rastet auf den nächsten Taktanfang. Der erste Loop (ohne festes Tempo) wird zum Taktgeber.
- **Pad mit Loop halten** – Overdub: nimmt zusätzlich auf den laufenden Loop drauf.
- **Antippen** – Loop an / aus (steigt immer synchron zu den anderen Pads ein).
- **ERASE**, dann Pad antippen – erst das Pattern des Pads, beim zweiten Tipp das Sample löschen.
- **MUTE lange drücken** – alle Loops stumm / an.

## Maschine-Workflow (Funktionsleiste & Transport)

Angelehnt an Native Instruments Maschine – Leiste links und rechts vom Effekt-Knopf:
- **SZENE** – die Pads werden zu 12 Szenen. Eine Szene speichert Sequenzer-Schritte, Matrix-Noten, ACID/DRUM-Pattern und welche Pads stumm sind. Antippen startet sie auf der nächsten Eins, eine leere Szene wird als Kopie der aktuellen angelegt. Szene lange drücken: Länge in Takten, Kette, Löschen. **SZENE lange drücken = Kette (Song-Modus)**: die Szenen laufen nacheinander.
- **MUTE / SOLO** – Pads antippen schaltet sie stumm bzw. solo (auch ⚡-Pads im Sequenzer). SOLO lange drücken = alle Solos aus.
- **ERASE** – Pad-Pattern bzw. Sample löschen, Sequenzer-Schritte antippen = Pattern leeren, mit SZENE: Szene löschen.
- **DUPL** – Quell-Pad, dann Ziel-Pad antippen (Sample, Modus, Tonhöhe, Schritte, Noten). Mit SZENE: Szenen kopieren.
- **REPEAT** – Note Repeat: gehaltenes ⚡-Pad oder DRUM-Pad wiederholt im Raster (lange drücken: 1/4, 1/8, 1/16, 1/32, 1/8T, 1/16T), bei Pattern-Aufnahme direkt ins Pattern.
- Alle Modus-Knöpfe: antippen = einrasten, halten + Pad = nur kurz.
- **Transport**: ▶ startet Loops, Sequenzer und Instrumente gemeinsam auf der Eins, ■ hält alles an. Eine Aufnahme auf ein Pad startet den Transport automatisch.
- **● REC antippen** = Pattern-Aufnahme direkt (steht alles: 1 Takt Einzählen), nochmal antippen = Punch-out, alles läuft weiter. **REC lange drücken** = Song-Aufnahme, Pattern als WAV, Einzähler an/aus.
- **↶ UNDO / ↷ REDO** – Aufnahmen, Overdubs, Löschen, Laden, Schritte, Noten und Pattern-Aufnahmen zurücknehmen bzw. wiederholen.

## Live-Konzept: Clips wie in Ableton Live

- **Status-Farben** auf jedem Pad: Rot pulsierend = Aufnahme (Ring zeigt den Takt), Grün = spielt (Lichtring läuft im Tempo), Gelb = Overdub, Blinken = wartet auf das Start-Raster, Grau ▶ = geladen und bereit. Leere Pads zeigen **+ MIC / + WAVE**. Im Menü abschaltbar.
- **📂 auf leeren Pads** – WAV/MP3, Bibliothek oder eigene Aufnahme laden. Tempo und Länge werden erkannt (Dateiname, Länge, Rhythmus-Analyse) und der Clip per Time-Stretch auf das Master-Tempo **gewarpt – ohne Tonhöhenänderung**. Ändert sich das Tempo, werden alle Loops neu gedehnt und bleiben im Takt.
- **Start-Raster ⏱** (unten rechts): AUS, 1/4, 1/2, 1, 2, 4 Takte – angetippte Loops starten/stoppen genau dort (Pad blinkt bis dahin). Geladene Clips warten, bis man sie antippt.
- **Tipp auf laufenden Loop = Overdub** (gelb, nochmal tippen beendet) – im Menü auf „Stopp“ umschaltbar. Halten = Overdub, solange der Finger liegt.
- **Gesten**: ← wischen = Pad löschen (UNDO holt es zurück), ↕ wischen = Pad-Lautstärke, zwei Finger = stumm (lang: Solo).
- **FX-Schalter** unten rechts in jedem Pad: antippen = Pad mit/ohne Effekt.

## 🌐 Web-Version

Im Ordner `web/` steckt Marv PAD als Browser-App (gleiche Engine als AudioWorklet: 12 Pads, Status-Farben, alle 37 Effekte, Effekt-Kette je Pad, Sequenzer, Piano-Roll mit Klaviatur links, Song-Matrix, Szenen, Undo, Note Repeat, ACID 303, DRUM 909, Bibliothek, Projekte im Browser-Speicher, WAV-Export).
- Bauen: `sh web/build.sh` – danach `web/index.html` über einen Webserver öffnen (z. B. `python3 -m http.server` im Ordner `web`).
- GitHub Pages: in den Repo-Einstellungen unter *Pages* als Quelle „GitHub Actions“ wählen – der Workflow `pages.yml` veröffentlicht dann jede Änderung automatisch. Dort funktioniert auch das Mikrofon.

## Menü ☰ (oben rechts)

- **🔌 Audio-Geräte** – Ein- und Ausgang getrennt wählen (USB-Audio-Interface, USB-Mikrofon, USB-Headset, Kopfhörer, Lautsprecher …) oder Automatisch. Class-compliant USB-Audio-Geräte (USB-C oder OTG) bindet Android selbst ein; Marv PAD erkennt Ein-/Ausstecken und startet das Audio automatisch neu, Loops bleiben erhalten. Die Auswahl wird gemerkt.

- **ℹ Info-Modus** (Haken setzen) – jedes angetippte Element (Knöpfe, Regler, Pads, XY-Fläche, Sequenzer, Tastatur, Matrix, Editor) wird hervorgehoben und erklärt, statt ausgelöst zu werden. ☰ und die Schließen-Knöpfe funktionieren weiter.

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

## Virtuelle Instrumente: 🔊 ACID 303 und 🥁 DRUM 909

Über der XY-Fläche: **🎛 EFFEKTE | 🔊 ACID 303 | 🥁 DRUM 909** – die Fläche wird zum Instrument.

**🔊 ACID** (303-artiger Bass, synthetisiert): 16 Schritte mit Ton (antippen = an/aus, ziehen = Tonhöhe), **A**ccent und **S**lide. Kopfzeile: AN/AUS, SAW/SQR, FX (durch die Effekt-Kette), Oktave −/+, 🎲 Zufalls-Linie, Leeren. Unten die Spielfläche: X = Ton (2 Oktaven), Y = Filter – Gleiten ergibt Slides; bei laufender Pattern-Aufnahme werden die Töne in die Schritte geschrieben. Regler oben: CUTOFF, RESO, ENV MOD, DECAY, LEVEL.

**🥁 DRUM** (909-artig, synthetisiert): BD, SD, LT, MT, HT, RS, CP, CH, OH, CR, RD. Instrument wählen, 16 Schritte (antippen: aus → an → Accent). Presets House/Techno/Breakbeat/Electro, ▶/■, FX, Leeren. Darunter Finger-Drum-Pads (mit ACCENT-Schalter); bei laufender Pattern-Aufnahme werden Schläge gerastert eingetragen. Regler oben: TUNE/DECAY/LEVEL des gewählten Instruments, DRIVE, VOL.

Beide laufen synchron mit Sequenzer, Tempo und Swing, landen in Song-Aufnahme und Resample und werden im Projekt gespeichert.

## Effekt-Kette (bis zu 3 Effekte)

Oben links auf der XY-Fläche: drei Plätze **1 › 2 › 3**. Antippen wählt den Platz – Effekt-Knopf, Effektliste und die Regler MIX/P1/P2 gelten dann für diesen Platz (jeder Platz merkt sich seine Werte). Den gewählten Platz nochmal antippen schaltet ihn an/aus. Die Effekte laufen nacheinander (z. B. FILTER › DELAY › HALL), die XY-Fläche steuert alle aktiven gleichzeitig. Die Kette wird im Projekt gespeichert.

## Effekt pro Pad

Jedes Pad hat einen eigenen Effekt-Schalter: im •••-Menü „Effekt: AN/AUS“ (oder „Effekt nur auf dieses Pad“). In der Effektliste (Display links antippen) unter „Pads wählen …“ alle auf einmal ankreuzen. Pads ohne Effekt zeigen unten rechts ein durchgestrichenes **FX**; das Display zeigt das Ziel (→ALLE, →P3, →5P, →AUS). Auch 🎹-Tastatur und Sequenzer-Noten folgen der Einstellung ihres Pads.

**Song-Aufnahme**: beim REC wählen – **mit Effekten** (genau wie du es hörst, je Pad wie eingestellt) oder **ohne Effekte** (trocken).

## 🎹 Tastatur

Der kleine Klavier-Knopf unter jedem Pad blendet eine Tastatur (2 Oktaven, Oktave −/+) ein: das Sample wird höher/tiefer gespielt, mehrere Finger = Akkord. Mit „📌 Ton fürs Pad“ wird der gespielte Ton zur festen Tonhöhe des Pads (gilt für ⚡-Antippen und den Sequenzer).
Unten in der Tastatur: **● REC** (Pattern oder Song), **▶** und **■**.

**▦ Matrix (Piano-Roll)** – oben in der Tastatur: zeigt alle aufgenommenen Töne (Tonhöhe senkrecht, Zeit waagrecht).
- **TAKTE** 1/2/4/8 – Länge der Matrix für dieses Pad
- **▦ Raster** frei / 1/4 / 1/8 / 1/16 / 1/32 – wird schon bei der Aufnahme angewendet („frei“ = genau wie gespielt)
- **QUANT.** – vorhandene Noten nachträglich aufs Raster setzen
- **LEEREN** – alle Noten des Pads löschen
- Ins Raster tippen setzt eine Note, auf eine Note tippen löscht sie. Im Pattern-Modus werden gespielte Töne und Akkorde mit Tonhöhe und Länge auf das 16-Schritt-Raster gerastet und laufen im Sequenzer mit (Punkt oben im Schritt = Note).

## 〰 Wave-Editor

Im •••-Menü: Start-/Endpunkt an der Wellenform ziehen, **Zuschneiden**, mit dem **SLICES**-Regler in Stücke teilen und **Slicen (Tempo)** – die Slices starten auf ihren Rasterplätzen, der Loop folgt so jedem Master-Tempo ohne Tonhöhenänderung (Länge über den **TAKTE**-Regler). **Slices auf Pads** legt die Stücke als ⚡-One-Shots auf freie Pads.

**💾 In Bibliothek speichern** – der bearbeitete Ausschnitt wandert als eigenes Sample in die Bibliothek. Dabei wird eine **Kategorie** abgefragt (vorhandene oder ＋ neue) und ein Name. Ist das Sample in Slices geteilt (z. B. ein importiertes MP3), lassen sich die Slices als **Stems** speichern – in die Bibliothek („Name · Stem 01 …“) oder als einzelne WAV-Dateien in einen Ordner.

## Sequenzer

Statt der unteren Pad-Reihe: 16-Schritt-Sequenzer, synchron zum Takt (ohne Takt: 120 BPM).
- **● REC** antippen – Pattern aufnehmen (mit Einzähler, wenn alles steht); angetippte ⚡-One-Shot-Pads landen auf dem nächsten Schritt.
- **● REC lange drücken** – Song aufnehmen (mit oder ohne Effekte, bis 15 Minuten), Pattern als WAV speichern, Einzähler an/aus.
- **▶ PLAY** – alles startet auf der Eins · **■ STOP** – alles anhalten · **STOP halten** – Pattern leeren
- **Schritte antippen** – Schritt für das zuletzt gespielte ⚡-Pad an/aus
- **Schritt hoch/runter ziehen** – Lautstärke dieses Schritts (Füllhöhe zeigt sie an)

## Pad-Modi

Im **•••**-Menü jedes Pads: **∞ LOOP** (läuft im Takt endlos, Regenbogen-Leuchten) oder **⚡ ONE SHOT** (spielt bei jedem Antippen einmal, Feuer-Leuchten).

## Bibliothek

„Aus Bibliothek laden" im •••-Menü, nach Kategorien: 🎙 Meine Aufnahmen · 🥁 Drums · 🪘 Percussion · 🎹 Tasten · 🎼 Melodisch · 🔊 Synth & Bass · ✨ FX · ∞ Loops (90–174 BPM) – über 90 Sounds.
Eigene Samples aus dem Wave-Editor erscheinen mit ★ in ihrer Kategorie (laden, als WAV exportieren, löschen).
Quellen: selbst synthetisiert (frei) und die **Versilian Community Sample Library (VCSL)**, CC0 1.0 (gemeinfrei) – https://github.com/sgossner/VCSL. Tonale Samples liegen meist auf C4 und lassen sich mit der 🎹-Tastatur spielen.

## Import / Export

Unter jedem Pad sitzt ein kleiner **•••**-Knopf:
- **Audio importieren** – WAV oder MP3 aufs Pad laden. Gibt es schon einen Takt, wird die Datei auf die passende Rasterlänge gebracht und läuft synchron.
- **Loop exportieren** – speichert den Loop als WAV (mono, 16 Bit).
- **Loop löschen**

## Resample & Live-Aufnahme

**🔁 Bounce** (wie beim alten Mehrspurrekorder, im Menü ☰ an/aus, Standard: an): Nach einer Aufnahme auf ein Wave-Pad werden alle Pads, die dabei mitgespielt haben, automatisch stummgeschaltet – es spielt nur noch die gebouncte Spur, nichts klingt doppelt oder übersteuert. Antippen holt ein Quell-Pad zurück.

**Eingang pro Pad** (•••-Menü → „Eingang“): **🎙 Mikrofon** oder **〰 Wave** – Wave nimmt auf ein leeres Pad auf, was die App gerade ausgibt (inkl. Effekte und XY-Modulation). So lässt sich ein Pad mit Effekt direkt auf das Nachbar-Pad aufnehmen. Das Symbol unten links am Pad zeigt den Eingang.

Unten zwei Schalter:
- **🎙 ALLE MIKRO ↔ 🔁 ALLE WAVE** – stellt den Eingang aller Pads auf einmal um (zeigt „GEMISCHT“, wenn Pads unterschiedlich eingestellt sind).
- **✋ HALTEN → 👆 TIPPEN → ⚡ LIVE 1/2/4/8/16 TAKTE** – HALTEN/TIPPEN siehe oben. Im Live-Modus ein leeres Pad nur antippen: es zeigt „BEREIT“, die Aufnahme startet auf dem nächsten Takt, läuft genau die eingestellte Länge, stoppt selbst und spielt sofort synchron weiter. Erneutes Antippen bricht ab. Ohne Tempo: Antippen startet, nochmal Antippen stoppt.

## Effekte

Über den Pads liegt die **XY-Fläche**: Finger drauf = Effekt an, X- und Y-Achse modulieren ihn. **🔒 HOLD** (Ecke oben rechts) hält den Effekt an der letzten Position aktiv, auch ohne Finger – auf der Fläche ziehen verschiebt ihn weiter, nochmal HOLD schaltet ihn aus.
**● REC** daneben speichert Fingerbewegungen: tippen → Fläche berühren startet die Aufnahme → nochmal tippen beendet sie. Die Bewegung wird auf Schläge gerundet und läuft als **▶ AUTO** synchron zum Takt in Schleife (Cursor fährt mit). Selbst anfassen hat Vorrang; AUTO antippen löscht sie.
Darüber fünf **Drehregler** (nach oben/unten ziehen): MIX, zwei effektabhängige Parameter, VOL und **SWING** (Shuffle: verschiebt jede zweite 1/16 im Sequenzer und in der Matrix nach hinten, 50 % = gerade … 75 % = stark geshuffelt).
Der **Effekt-Knopf** schaltet durch (links tippen = zurück, rechts = weiter, lange drücken = Liste):

**Filter:** LOWPASS · HIGHPASS · BANDPASS · NOTCH (je 12/24/36/48 dB über den STEIL-Regler) · BELL (Peaking-EQ, ±18 dB, 1–3 Stufen) · PEAK (Resonanzspitze) · COMB (Feedback oder Feedforward)

**Modulation & Klang:** PHASER · TREMOLO (frei/Takt) · VIBRATO · EQ (3 Bänder) · FILTER (Multimode) · SATURATE · OVERDRIVE

**Granular & Spektral:** GRAIN DLY · PITCH (Pitch-Shifter) · STRETCH (Time-Stretch) · GRANULAR (Granular-Wolke) · KRISTALL (rückwärts gepitchte Granular-Echos) · SMEAR · FREEZE (Spektral-Freeze/Blur)

**Experimentell:** RINGMOD · FM · FREQ SHIFT · RESONATOR (Akkord-Resonatoren) · SHUFFLE (Buffer-Shuffler im Takt) · SEQ-FX (Effekt-Sequenzer pro Schritt) · MULTI-FX (Morph-Kette Filter/Drive/Echo)

**Weitere:** DELAY (taktsynchron) · STUTTER (taktsynchron) · FLANGER · CHORUS · REVERB · HALL · VOCODER (Loops oder Mikrofon steuern einen Synth-Träger) · DISTORTION · CRUSH

Maximal 66 Sekunden pro Aufnahme (16 Takte ab 60 BPM). Kopfhörer verhindern, dass laufende Loops mit aufgenommen werden.

## Installieren

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt sie unter **Releases** ab.

© marween


## ⚡ Live-Reaktion & Latenz

☰ → „⚡ Latenz & Live-Reaktion“: **AUTO** startet mit dem kleinsten Audio-Puffer des Geräts und vergrößert ihn nur, wenn es knackst (oder fest MINIMAL/AUSGEWOGEN/STABIL). Touch-Ereignisse kommen ohne Bildtakt-Verzögerung an, ONE SHOTS klingen im nächsten Audio-Block (ohne abgeschnittenen Anschlag), Loops starten schon beim **Berühren**. „Start-Raster AUS“ lässt Loops sofort (phasenrichtig im Takt) einsetzen. Bluetooth-Kopfhörer verzögern um 150–300 ms – für Live Kabel oder USB-Audio verwenden.

## 🎙 Live-Mikrofon mit Effekten

☰ → „Live-Mikrofon“: die Stimme live hören, mit bis zu **3 eigenen Effekten** und optional zusätzlich durch die XY-Effekte. Mit Effekten aufnehmen: Pad-Eingang auf WAVE.

## 🎹 MIDI

USB-Controller (OTG) und MIDI-Apps: Pads per Note (ab C1 = 36 oder einzeln **gelernt**), Anschlagdynamik, Tastatur-Kanal spielt die Piano-Roll chromatisch (C4 = Originalton), **CC lernen** für XY, Regler, Crossfader, Deck-Fader/Filter/Pitch/Effekt, Mikro. MIDI-Clock empfangen (Tempo folgt) oder senden, Start/Stopp, Programmwechsel = Szene.

## 🎹 Piano-Roll bearbeiten

**✏ STIFT** (setzen, ziehen = länger, antippen = löschen, Note ziehen = verschieben, rechten Rand ziehen = Länge) und **⬚ AUSWAHL** (antippen/Rahmen ziehen markiert, markierte ziehen verschiebt, ins Leere tippen = Einfügemarke). **ALLE · KOPIE · EINF. · LÖSCH · GRUPPE · QUANT** (nur Markierte). Zoom bis 32× mit 🔍 oder zwei Fingern. **⇅ MIDI**: Noten als .mid exportieren/importieren.

## 〰 Wave-Editor-Lupe

🔍 bis auf einzelne Samples, zwei Finger aufziehen, beim Ziehen der Marker eine runde **Lupe**, ⌖ S/E springt zum Marker, **0-SNAP** rastet auf Nulldurchgänge (kein Knacksen), **📍 CUE** setzt Hot Cues 1–4 für die DJ-Bibliothek.

## 🎧 DJ-Modus

☰ → Design → „🎧 DJ · …“ (18 Hardware-Designs: Stahl, Akai-Farbe, Walnuss, Retro-Creme, Glas, Porzellan, Neon, Matt-Schwarz, Pastell, Graphit, Marv-Orange, Messing/Leder/Leinen …).
- **Display**: zwei Decks mit Wellenform (Bass-Farbe), Beat-Raster, Cue/Hot Cues, Loop; Wellenform ziehen = scratchen, Übersicht antippen = springen, Titel antippen = laden (Datei MP3/WAV, Bibliothek, Aufnahmen, Pad, **DJ-Bibliothek**).
- **4 Regler**: HI/MID/LOW (Kill bis +6 dB) und FILTER (links Tiefpass, rechts Hochpass) fürs gewählte Deck (DECK A/B), Doppeltipp = Mitte.
- **Fader**: außen Pitch (±8 %, Doppeltipp = 0), innen Lautstärke, dazwischen PLAY · CUE (halten am Cue = vorhören) · SYNC (Tempo + Takt auf das Master-Tempo der Pads) · 🎧 Vorhören. Crossfader weich/hart (SAMPLER lang drücken).
- **XY-Fläche**: Effekt des Decks (Taster FX wählt), loslassen = aus, Doppeltipp = halten.
- **Pads** CUES: je Deck Hot Cue 1–4 (Doppeltipp löscht), Beat-Jump, Loop ½× / an-aus / 2×. **SAMPLER**: die 12 Pads der App – leeres Pad antippen lädt Audio (DJ-Bibliothek, Deck ab Cue-Punkt, Datei); es startet beim Antippen **synchron zu den laufenden Pads ab dem Startpunkt**.
- DECK A/B lang drücken = Split-Cue (links Master, rechts Vorhören).
- **DJ-Bibliothek**: im Wave-Editor „💾 → 💿 DJ-Bibliothek“ (Startpunkt = S, Hot Cues aus 📍 CUE bzw. den Slices) oder im Deck-Menü „Cue-Punkte speichern“.


## ✎ ACID 303 & DRUM 909 – Editor

Auf der ACID- bzw. DRUM-Fläche **✎ EDIT**: links Klaviatur (ACID) bzw. die 11 Instrumente (DRUM), rechts die Matrix in 1/16-Schritten, Länge 1–4 Takte.
- **✏ STIFT**: Feld antippen setzt eine Note (ACID: nach rechts ziehen = länger/gebunden), antippen löscht, **lange drücken = Accent**, Note ziehen verschiebt. DRUM: über Felder ziehen malt Schläge.
- **⬚ AUSWAHL**, **ALLE · KOPIE · EINF. · LÖSCH · QUANT** (auf 1/8 oder 1/4), Zoom, ⇅ HALBTON (ACID) bzw. ▦ PRESET (DRUM), **⇅ MIDI** laden/exportieren (DRUM als General-MIDI-Drums auf Kanal 10).
- ACID: Spuren **ACCENT** und **SLIDE** unter der Matrix. **⚙ SOUND**: Cutoff, Resonanz, Env Mod, Decay, Accent, **Glide-Zeit**, Drive, Tune, Oktave, Swing, Lautstärke, Säge/Rechteck, FX-Bus, Zufall.
- DRUM **⚙ KITS**: je Instrument Tune, Decay, Level, Stumm; Drive, Swing, Lautstärke, FX-Bus, Presets.
- **Klaviatur-/Instrument-Taste** spielt den Ton und markiert alle Noten dieser Reihe – eine davon ziehen verschiebt die ganze Gruppe (auch in der Piano-Roll).

## VEL – Lautstärke je Note

In Piano-Roll, ACID- und DRUM-Editor liegt unter der Matrix die **VEL**-Spur: je Note ein Balken, mit dem Finger hoch/runter ziehen (über mehrere Noten wischen geht auch; markierte Noten ändern sich gemeinsam).

## ▦ Presets für DRUM 909 und ACID 303

Im ACID/DRUM-Editor **▦ PRESETS** (bei DRUM auch auf der Fläche): 37 Drum-Beats in 7 Gruppen (House, Techno, Trance & Hard, Electro & Bass, Breaks & DnB, Hip-Hop & Trap, Band & World, Fills) und 21 Acid-Linien (Klassisch, Dunkel, Hypnotisch, Funky, Hart, 2 Takte) mit passenden Klang-Einstellungen. Tempo und Swing werden übernommen, solange noch keine Loops laufen. Dazu ein **🎲 Zufalls-Generator** und der Import eigener MIDI-Dateien.

Quellen & Lizenzen: Die Beats sind genretypische Grundmuster, die Acid-Linien eigene Kompositionen – beide für Marv PAD neu notiert. Der Zufalls-Generator folgt dem Algorithmus von **„Acid Banger“ von David Whiting** (https://github.com/vitling/acid-banger), lizenziert unter **Creative Commons Attribution 4.0** (https://creativecommons.org/licenses/by/4.0/) – nach Kotlin übertragen und an die App angepasst.
