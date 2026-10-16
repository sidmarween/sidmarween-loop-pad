# Marv PAD

Android-App für Musiker: 16 Neon-Pads, XY-Effektfläche und Drehregler im Kaoss-Pad-Stil zum Live-Loopen übers Mikrofon.

- **Pad halten** – nimmt auf, solange du hältst. Der erste Loop wird zum Taktgeber. Alle weiteren Loops rasten automatisch auf sein Raster ein: Länge wird auf 1/8, 1/4, 1/2, 1, 2, 4 oder 8 Takte gerundet, der Start auf den nächsten Rasterpunkt.
- **Pad mit Loop halten** – Overdub: nimmt zusätzlich auf den laufenden Loop drauf.
- **2× tippen** – Loop stumm / wieder an.
- **CLEAR**, dann Pad antippen – Loop löschen.
- **ALLE STUMM / ALLE AN** – alles auf einmal.

## Import / Export

Unter jedem Pad sitzt ein kleiner **•••**-Knopf:
- **Audio importieren** – WAV oder MP3 aufs Pad laden. Gibt es schon einen Takt, wird die Datei auf die passende Rasterlänge gebracht und läuft synchron.
- **Loop exportieren** – speichert den Loop als WAV (mono, 16 Bit).
- **Loop löschen**

## Effekte

Über den Pads liegt die **XY-Fläche**: Finger drauf = Effekt an, X- und Y-Achse modulieren ihn.
Darüber vier **Drehregler** (nach oben/unten ziehen): MIX, zwei effektabhängige Parameter, VOL.
Der **Effekt-Knopf** schaltet durch (links tippen = zurück, rechts = weiter):

FILTER · DELAY (taktsynchron) · STUTTER (taktsynchron) · FLANGER · CHORUS · REVERB · HALL · VOCODER (Loops oder Mikrofon steuern einen Synth-Träger) · DISTORTION · CRUSH

Maximal 30 Sekunden pro Aufnahme. Kopfhörer verhindern, dass laufende Loops mit aufgenommen werden.

## Installieren

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt sie unter **Releases** ab.

© marween
