# S-018: Schallbild ohne Kamera (Stufe 1 der akustischen Kamera, F11)

**Stand:** 9.11.2026 · **Status:** in Arbeit; Auftrag von Arslan am 9.11.2026, 20:06 Uhr ("Fange die umfangreiche
Bearbeitung an"). Kein Punkt aus CLAUDE.md Abschnitt 1 Nr. 3 trifft zu (keine Regeländerung, kein Speichern, keine
Kamera-Sitzung, keine neue Berechtigung, ein Release). Release erst auf Wunsch.
**Anlass:** F11 in `docs/ideen.md`; Plan in `docs/plan-f11-akustische-kamera.md`. Messwerte S24+ vom 10.10.: zwei
Mikrofone über `MIC` und `CAMCORDER`, Laufzeit links −0,48 ms, rechts +0,40 ms; Musik in 1 m: Gleichklang der Kanäle
0,84 (100 bis 300 Hz), 0,54 (300 bis 1000 Hz), 0,18 (1 bis 3 kHz).

## Ziel
Ein neuer Bildschirm "Schallbild" (erreichbar aus dem Mikrofon-Test) zeigt laufend, aus welcher Richtung Töne kommen:
je Frequenzband ein senkrechter Streifen zwischen links (−90°) und rechts (+90°), Farbe nach Tonhöhe, Helligkeit nach
Lautstärke; dazu die Hauptrichtung und je Band Winkel, Anteil gerichteten Schalls und Pegel als Zahlen. Das Handy wird
quer gehalten. Der Bildschirm ist zugleich die Messung für die nächsten Stufen: Welche Bänder sind auf dem S24+
brauchbar, stimmen links und rechts, taugt `CAMCORDER` besser als `MIC`?

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Rauschen aus +30° (200 bis 6400 Hz), Störrauschen je Kanal 10 dB leiser, 2 s: alle 5 Bänder gültig, Winkel je Band innerhalb 6°, Hauptrichtung innerhalb 3°, Anteil je Band mindestens 0,8 | `SoundBandsTest > S-018 Rauschen aus 30 Grad steht in allen Baendern` |
| K2 | Zwei Quellen zugleich: tief (200 bis 800 Hz) aus −40°, hoch (1600 bis 6400 Hz) aus +35°: die zwei tiefen Bänder zeigen −40° ± 8°, die zwei hohen +35° ± 8° | `SoundBandsTest > S-018 zwei Quellen stehen je Band an ihrer Stelle` |
| K3 | Fehlerfall: zwei unabhängige Rauschkanäle, 60 s: in 600 Momentaufnahmen kein gültiges Band, keine Hauptrichtung | `SoundBandsTest > S-018 Fehlerfall unabhaengiges Rauschen zeigt nichts` |
| K4 | Reiner Ton 2,5 kHz aus +20°: Band 1600 bis 3200 Hz gültig, eines der Maxima seiner Kurve liegt innerhalb 5° der Wahrheit; die anderen Bänder ungültig | `SoundBandsTest > S-018 reiner Ton steht im richtigen Band` |
| K5 | Randfall: digitale Stille und Pegel unter −75 dBFS: kein gültiges Band, keine NaN | `SoundBandsTest > S-018 Randfall Stille` |
| K6 | Pegel: Sinus 1 kHz mit halber Vollaussteuerung ergibt im Band 800 bis 1600 Hz −9,0 dBFS ± 0,5 | `SoundBandsTest > S-018 Pegel je Band` |
| K7 | Trägheit: Quelle springt von −40° auf +40°; nach 1,5 s zeigt die Hauptrichtung +40° ± 5° | `SoundBandsTest > S-018 folgt einem Sprung der Quelle` |
| K8 | Zeit (R27): 100 ms Ton einrechnen und Momentaufnahme höchstens 5 ms (bester von 5, JVM) | `SoundBandsTest > S-018 Rechnung haelt das Zeitbudget` |
| K9 | Hall (Prüfung gegen die Wahrheit, nicht nur gegen sich selbst): Quelle aus +40° plus Raumschall gleicher Leistung (32 Richtungen): Bänder ab 800 Hz, soweit gültig, innerhalb 10°; die Abweichung der tiefen Bänder steht im Bericht | `SoundBandsTest > S-018 Hall zieht nur tiefe Baender zur Mitte` |
| K10 | Haltung aus der Schwerkraft: quer mit Oberkante links ergibt +1, Oberkante rechts −1, hochkant und flach liegend "nicht quer" | `SoundBandsTest > S-018 Haltung aus der Schwerkraft` |
| K11 | Tonstrom der Boundary (Fake): liefert Stücke von 100 ms, gibt das Mikrofon bei Abbruch frei, eine Aufnahme zur Zeit | `SoundImageUseCaseTest > S-018 Abbruch gibt Mikrofon und Sensor frei` |
| K12 | UseCase mit künstlicher Quelle aus +30° und Oberkante links: Hauptrichtung +30° ± 5°; Oberkante rechts: −30° ± 5° | `SoundImageUseCaseTest > S-018 Quelle rechts steht rechts, gedreht links` |
| K13 | Hochkant: Meldung "nicht quer", keine Bänder | `SoundImageUseCaseTest > S-018 hochkant zeigt keine Streifen` |
| K14 | Fehlerfälle: keine Berechtigung, Aufnahme scheitert, nur ein Mikrofon (beide Kanäle gleich): je ein Fehlerwert, kein Absturz; Sensorfehler beendet das Schallbild nicht (Haltung "unbekannt") | `SoundImageUseCaseTest > S-018 Fehlerfaelle enden als Werte` |
| K15 | ViewModel: Start nach Berechtigung, Stopp gibt frei, Wechsel der Quelle startet neu, Fehler im Zustand | `SoundImageTest > S-018 ViewModel startet, stoppt und wechselt die Quelle` |
| K16 | Bildschirm zeigt Streifen, "Hauptrichtung: 30° rechts" und je Band eine Zeile "400 bis 800 Hz: 30° rechts, Anteil 0,85, −42 dBFS" | `SoundImageTest > S-018 Bildschirm zeigt Streifen und Zahlen` (Screenshot `soundimage_running`) |
| K17 | Bildschirm ohne Lauf (Anleitung, Startknopf), "Bitte quer halten", keine Berechtigung, Fehler | `SoundImageTest > S-018 Zustaende ohne Streifen` |
| K18 | Adapter (Emulator): Tonstrom liefert in 1,5 s mindestens 8 Stücke zu je 100 ms; nach Abbruch keine offene Aufnahme | `AudioRecordMicrophoneAdapterTest > S-018 Tonstrom liefert Stuecke und gibt frei` |
| K19 | Gerätetest S24+: Musik vom zweiten Handy 40 cm rechts: Streifen und Hauptrichtung rechts; links: links; vorne: Mitte. Dasselbe in 1,5 m. Je Stand ein Screenshot, einmal mit `MIC`, einmal mit `CAMCORDER` | Arslan |

## Nicht Teil dieser Änderung
- Keine Kamera, kein Kamerabild, keine Einblendung im Sucher (Stufen 2 und 3).
- Kein Speichern: weder Ton noch Eichung noch Einstellungen (die gewählte Quelle gilt bis zum Verlassen).
- Kein Oben/Unten und kein Vorne/Hinten (Stufe 5, F12).
- Mikrofon-Test und Schwenk-Messung bleiben unverändert.
- Keine Eichung der Mitte: gerechnet wird mit Mitte 0 ms und dem Abstand aus `MicTestUseCase.spacing` (S24+: 15 cm
  angenommen, gemessen 0,44 ms halbe Spanne); die gemessene Mitte von −0,04 ms entspricht etwa 5° Versatz.

## Betroffene Schichten und Regeln
- `:core:pure`: `SoundBands` (Spektren, Bänder, Richtung, Pegel, Haltung). Nutzt `AudioMath.fft`.
- `:core:boundary`: `MicrophoneBoundary.stream(request, chunkMillis): Flow<MicStreamEvent>` (R25: Meldungen nur als
  Flow; R24: Fehler als Wert `Failed`), `MicChunk`. Fake erweitert.
- `:core:audio`: `AudioRecordMicrophoneAdapter.stream`; dieselbe Sperre wie `record` (eine Aufnahme zur Zeit),
  Freigabe in genau einem `NonCancellable`-Block (R28 unverändert).
- `:core:control`: `SoundImageUseCase` (Ton und Schwerkraft, Rechnen auf dem ComputeDispatcher; Sensorfluss mit
  `.catch`, R14).
- `:feature:settings`: `SoundImageViewModel`, `SoundImageScreen`, Texte; Einstieg im Mikrofon-Test.
- `:app`: Navigationsschlüssel `SoundImageKey`.
- Keine neue Ausnahme, keine neue Berechtigung (`RECORD_AUDIO` gibt es seit S-008), kein Speichern.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in pure, Android nur in `:core:audio`; UI kennt nur ViewModel-Typen und pure (R1, R28) |
| Nur Zahlen, Schlüssel, Enums | `MicChunk`, Ereignisse und UiState tragen Zahlen, Listen von Zahlen und Enums (R23) |
| Fehler und Ausweichweg | Fehler als `Failed`-Werte; Sensorfehler lässt das Schallbild weiterlaufen (R14, R24) |
| Abbruch und Freigabe | Tonstrom: ein `NonCancellable`-Block im `finally`; Sensor meldet sich in `awaitClose` ab (R17, R28, R29) |
| Bildpuffer | trifft nicht zu; Tonstücke: je Stück ein neues Feld, das der Empfänger besitzt; die Rechnung nutzt feste Puffer (R19 sinngemäß) |
| Main-Thread | Rechnen auf dem ComputeDispatcher, Lesen auf dem IoDispatcher (R16, R18) |
| Gespeicherte Daten | keine |
| Zeit und Speicher | K8; 10 Momentaufnahmen je Sekunde, Speicher fest etwa 100 kB (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
- Hall: hohe Bänder bleiben leer, tiefe ziehen zur Mitte (K9 misst es im Labor, K19 auf dem Gerät).
- Vorzeichen der Mikrofonachse nur aus einem Schwenk-Lauf: K19 bestätigt oder dreht die Konstante.
- Der Tonstrom hält das Mikrofon, solange der Bildschirm offen ist; Verlassen oder Hintergrund gibt es frei.
- Rückweg: Einstieg im Mikrofon-Test entfernen; die Boundary-Erweiterung stört niemanden.

## Kosten
Probeläufe auf einem Seiten-Branch (kurzer Lauf plus Emulator, weil `:core:audio` betroffen ist), ein Lauf für die
Screenshot-Grundlagen, ein Release auf Wunsch. Gerätetest durch Arslan (K19).

## Ergebnis
(nach der Umsetzung)
