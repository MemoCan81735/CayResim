# S-018: Schallbild ohne Kamera (Stufe 1 der akustischen Kamera, F11)

**Stand:** 9.11.2026 · **Status:** umgesetzt in v0.1.143, Gerätetest offen; Auftrag von Arslan am 9.11.2026, 20:06 Uhr
("Fange die umfangreiche Bearbeitung an"), Release gewünscht am 9.11.2026, 20:39 Uhr ("Ja bitte", zusammen mit S-019).
Kein Punkt aus CLAUDE.md Abschnitt 1 Nr. 3 trifft zu (keine Regeländerung, kein Speichern, keine Kamera-Sitzung, keine
neue Berechtigung, ein Release).
**Anlass:** F11 in `docs/ideen.md`; Plan in `docs/plan-f11-akustische-kamera.md`. Messwerte S24+ vom 10.10.: zwei
Mikrofone über `MIC` und `CAMCORDER`, Laufzeit links −0,48 ms, rechts +0,40 ms; Musik in 1 m: Gleichklang der Kanäle
0,84 (100 bis 300 Hz), 0,54 (300 bis 1000 Hz), 0,18 (1 bis 3 kHz).

## Ziel
Ein neuer Bildschirm "Schallbild" (Eintrag in den Einstellungen) zeigt laufend, aus welcher Richtung Töne kommen:
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
| K11 | Der UseCase fragt Stereo mit 48 kHz in Stücken von 100 ms an (Tonstrom, keine Einzelaufnahme); Abbruch gibt Mikrofon und Sensor frei. "Eine Aufnahme zur Zeit" sichert die Sperre des Adapters (K18) | `SoundImageUseCaseTest > S-018 Abbruch gibt Mikrofon und Sensor frei` |
| K12 | UseCase mit künstlicher Quelle aus +30° und Oberkante links: Hauptrichtung +30° ± 5°; Oberkante rechts: −30° ± 5° | `SoundImageUseCaseTest > S-018 Quelle rechts steht rechts, gedreht links` |
| K13 | Hochkant: Meldung "nicht quer", keine Bänder | `SoundImageUseCaseTest > S-018 hochkant zeigt keine Streifen` |
| K14 | Fehlerfälle: keine Berechtigung, Aufnahme scheitert, nur ein Mikrofon (beide Kanäle gleich): je ein Fehlerwert, kein Absturz; Sensorfehler beendet das Schallbild nicht (Haltung "unbekannt") | `SoundImageUseCaseTest > S-018 Fehlerfaelle enden als Werte` |
| K15 | ViewModel: Start nach Berechtigung, Stopp gibt frei, Wechsel der Quelle startet neu (weiter nur eine Aufnahme offen), Messwerte im Zustand | `SoundImageTest > S-018 ViewModel startet, stoppt und wechselt die Quelle` |
| K15b | ViewModel: Berechtigung abgelehnt oder entzogen, Aufnahme scheitert, nur ein Kanal: je ein Merker im Zustand, kein Lauf | `SoundImageTest > S-018 ViewModel meldet Fehler im Zustand` |
| K16 | Bildschirm zeigt Streifen, "Hauptrichtung: 30° rechts", je Band eine Zeile "400 bis 800 Hz: 30° rechts, Anteil 0,85, -42 dBFS" und die Messzeile | `SoundImageTest > S-018 Bildschirm zeigt Streifen und Zahlen` (Screenshot `soundimage_running`) |
| K17 | Bildschirm ohne Lauf (Anleitung, Startknopf), "Bitte quer und aufrecht halten", keine Berechtigung, Fehler | `SoundImageTest > S-018 Zustaende ohne Streifen` (Screenshot `soundimage_idle`) |
| K18 | Adapter (Emulator): Tonstrom liefert in 3 s mindestens 8 Stücke zu je 100 ms mit steigenden Nummern; nach Abbruch keine offene Aufnahme; danach gehen Einzelaufnahme und neuer Strom | `AudioRecordMicrophoneAdapterTest > s018TonstromLiefertStueckeUndGibtFrei` |
| K19 | Gerätetest S24+: Musik vom zweiten Handy 40 cm rechts: Streifen und Hauptrichtung rechts; links: links; vorne: Mitte. Dasselbe in 1,5 m. Je Stelle ein Screenshot, mit `MIC` und mit `CAMCORDER`, in beiden Querhaltungen (Oberkante links, Oberkante rechts). Dabei auch: Der Bildschirm dreht quer mit, auch wenn "automatisch drehen" aus ist, bleibt an und dreht beim Verlassen zurück; die Messzeile zeigt etwa 10 Bilder je Sekunde und 0 verlorene Tonstücke | Arslan |
| K20 | Die Streifen folgen der Haltung des Handys, nicht der Drehung der Oberfläche: alle 8 Fälle aus Haltung und Anzeige-Drehung, ohne bekannte Haltung wie Oberkante links | `SoundImageTest > S-018 Streifen folgen der Haltung, nicht der Drehung der Oberflaeche` |
| K21 | Oberfläche hochkant, Handy quer: Streifen liegen quer, "links" oben, "rechts" unten | `SoundImageTest > S-018 Bildschirm hochkant zeigt die Streifen quer` (Screenshot `soundimage_upright`) |
| K22 | Ton endet: Nach einem lauten Ton (−26 dBFS) und 1,5 s Grundrauschen (−70 dBFS) kein Streifen mehr (ohne die Regel 4 s, Zweitprüfung); ein gleichmäßiger Ton behält seine Streifen in 17 Momentaufnahmen | `SoundBandsTest > S-018 Ton endet, Streifen verschwindet` |
| K23 | Messwerte (R27): Momentaufnahmen und mittlere Rechenzeit der letzten vollen Sekunde, verworfene Tonstücke seit dem Start sofort; mit künstlicher Uhr 10 je Sekunde, 3,0 ms, 2 verloren | `SoundImageUseCaseTest > S-018 Messwerte zeigen Takt, Rechenzeit und verlorene Stuecke` |

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
  Flow; R24: Fehler als Wert `Failed`), `MicChunk` mit laufender Nummer. Fake erweitert.
- `:core:audio`: `AudioRecordMicrophoneAdapter.stream`; dieselbe Sperre wie `record` (eine Aufnahme zur Zeit),
  Freigabe in genau einem `NonCancellable`-Block (R28 unverändert).
- `:core:control`: `SoundImageUseCase` (Ton und Schwerkraft, Rechnen auf dem ComputeDispatcher; Sensorfluss mit
  `.catch`, R14).
- `:feature:settings`: `SoundImageViewModel`, `SoundImageScreen`, Texte; Einstieg in den Einstellungen und ein Abschnitt
  in der Anleitung. Solange der Bildschirm offen ist, dreht die Oberfläche quer mit (auch bei "automatisch drehen" aus)
  und der Bildschirm bleibt während des Laufs an; beim Verlassen gilt wieder der Wert von vorher.
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
| Zeit und Speicher | K8 im Labor, K23 und die Messzeile auf dem Gerät; 10 Momentaufnahmen je Sekunde, Speicher fest etwa 100 kB (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
- Hall: hohe Bänder bleiben leer, tiefe ziehen zur Mitte (K9 misst es im Labor, K19 auf dem Gerät).
- Vorzeichen der Mikrofonachse nur aus einem Schwenk-Lauf: K19 bestätigt oder dreht die Konstante.
- Der Tonstrom hält das Mikrofon, solange der Bildschirm offen ist; Verlassen oder Hintergrund gibt es frei.
- Rückweg: Eintrag in den Einstellungen entfernen; die Boundary-Erweiterung stört niemanden.

## Kosten
Probeläufe auf einem Seiten-Branch (kurzer Lauf plus Emulator, weil `:core:audio` betroffen ist), ein Lauf für die
Screenshot-Grundlagen, ein Release auf Wunsch. Gerätetest durch Arslan (K19).

## Ergebnis
Labor (9.11., `tools/run-pure-tests.sh SoundBandsTest`, künstliche Signale):

| Kriterium | Messwert |
|---|---|
| K1 Rauschen aus 30° | alle 5 Bänder gültig, innerhalb 6°, Hauptrichtung innerhalb 3° |
| K3 unabhängiges Rauschen, 648 Momentaufnahmen mit 8 Neustarts | kein Streifen; größter Anteil je Band 0,259 / 0,177 / 0,189 / 0,125 / 0,107 (Grenze 0,3; die 0,259 fällt ins Einschwingen, das die Regel ausschließt). Eingeschwungen in 16.800 Aufnahmen höchstens 0,21, Zweitprüfung in 18.000 höchstens 0,25 |
| K8 Rechenzeit je 100 ms Ton | 0,33 ms (JVM) |
| K9 Hall so laut wie die Quelle (40°) | 200 Hz: 15°, 400 Hz: 24°, 800 Hz: 40°, 1600 Hz: 40°, 3200 Hz: 39°, Hauptrichtung 39°; Anteile 0,90 / 0,60 / 0,59 / 0,54 / 0,52 |
| K22 Ton endet | Streifen weg nach 1,5 s (vorher 3,8 bis 4,0 s, gemessen von der Zweitprüfung) |

Erster Lauf auf dem Seiten-Branch (42684435096): alles übersetzt, 617 Tests, rot nur die 5 erwarteten Vergleichsbilder;
Lint und detekt ohne neue Funde (auch kein Fund zur Drehsperre). Bilder angesehen; die Marke der Hauptrichtung war auf
einem weißen Streifen kaum sichtbar und hat jetzt einen dunklen Rand.

Zweitprüfung (unabhängiger Agent, 9.11.): kein Fehler in Rechnung, Vorzeichen, Zuordnung links/rechts und Freigabe
(Winkel bei 8 bis 192 kHz innerhalb 0,4°, Pegel innerhalb 0,6 dB, 6 schnelle Quellenwechsel nie mehr als eine Aufnahme
offen). Behoben: Messwerte auf dem Gerät fehlten (K23, Messzeile); Streifen blieben nach einem lauten Ton 4 s stehen
(K22); K3 druckte seine Werte erst nach den Prüfungen; Kommentar zur Grenze nannte 0,21 statt 0,25; Zeitfenster von K18
zu knapp; Drehsperre setzte fest "unspecified" statt des Werts von vorher und stand nicht in der Spec; Texte erklärten
"Anteil" und "dBFS" nicht; Haltung flackerte an der Grenze (jetzt 0,5 hinein, 0,4 hinaus); Abfrage des Sensors lag
außerhalb des Fehlerfangs; doppelter Freigabeblock im Adapter (jetzt eine Funktion); Kriterien ohne Test und Tests ohne
Kriterium (K11 gekürzt, K15b, K20, K21 ergänzt). Offen bleibt: K18 läuft erst mit dem Emulator im Lauf auf main; die
Konstante für das Vorzeichen der Achse kann nur K19 bestätigen.

Eigene Fehler: Der Einstieg stand in der Spec im Mikrofon-Test, gebaut wurde er in den Einstellungen (Spec angepasst).

Lauf auf main 42689855066 (9.11.): alle Jobs grün, auch der Emulator; Release v0.1.143. Emulator: Tonstrom lieferte Stücke und gab frei (K18). Gerätetest offen.
