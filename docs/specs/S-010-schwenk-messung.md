# S-010: Schwenk-Messung (Ton und Lage gleichzeitig aufnehmen)

**Stand:** 10.10.2026 · **Status:** Entwurf, wartet auf Freigabe durch Arslan
**Anlass:** Wunsch Arslan (10.10., 14:57 Uhr): "Schwenk-Ortung", Funktion F12. Zwei Handy-Mikrofone liefern je Messung
nur den Winkel zur Mikrofonachse (S-008, S-009: links −0,48 ms, rechts +0,40 ms, quer gehalten). Die Quelle liegt
damit auf einem Kegel um die Achse; vorne und hinten, oben und unten sind nicht zu unterscheiden. Dreht man das Handy,
zeigt die Achse in andere Richtungen, und die Kegel schneiden sich nur in der Richtung der Quelle (wie beim Drehen des
Kopfes). Ob das mit dem S24+ genau genug wird, ist nicht gemessen. Diese Spec baut nur die Messung, nicht die Anzeige im
Kamerabild (später, eigene Specs).

## Ziel
Eine Debug-Funktion im Mikrofon-Test nimmt 25 s Stereo-Ton und gleichzeitig den Lagesensor auf, während Arslan das Handy
um eine feste Geräuschquelle schwenkt. Die App rechnet daraus sofort eine geschätzte Richtung der Quelle und zeigt, wie
gut Ton und Lage zeitlich zusammenpassen. Alle Rohdaten werden gespeichert, damit die Rechnung nachgeprüft werden kann.
Am Fotografieren ändert sich nichts.

## Rechnung
- Lage: Drehvektor-Sensor (`TYPE_ROTATION_VECTOR`, Quaternion, etwa 100 Hz). Mikrofonachse im Gerät: unten nach oben
  (y-Achse); ihr Vorzeichen (welcher Kanal unten ist) wird mit der ersten Messung festgestellt.
- Ton: Fenster von 50 ms, je Fenster die Laufzeit τ mit GCC-PHAT wie in S-008. Fenster ohne Signal (Pegel unter
  Grundrauschen plus 10 dB oder Spitze unter 0,1) fallen weg.
- Zeitbezug: Ton über `AudioRecord.getTimestamp` (Zeitbasis seit dem Einschalten), Sensor über `SensorEvent.timestamp`
  (dieselbe Zeitbasis laut Android). Prüfung im Lauf: Zu Beginn tippt Arslan zweimal auf die Rückseite; der Klopfer
  erscheint im Ton und im Beschleunigungssensor, der Abstand beider ist der Gleichlauffehler.
- Richtung: Je Fenster gilt τ = τ0 + k · (a · u) mit a = Mikrofonachse in Weltkoordinaten (aus der Lage), u = gesuchte
  Richtung, k = wirksamer Abstand / 343 m/s, τ0 = Versatz. Das ist linear in (τ0, k·u) und wird als Ausgleichsrechnung
  gelöst. Ergebnis: u, wirksamer Abstand (Gegenprobe zu S-009) und Restfehler. Eine Eichung vorab ist nicht nötig.
- Abdeckung: Streuung der Achsrichtungen (kleinster Eigenwert ihrer Streumatrix, auf 0 bis 1 normiert). Liegen alle
  Achsen in einer Ebene (nur nach links und rechts geschwenkt), ist die Richtung nicht eindeutig; dann meldet die App
  "Schwenk zu einseitig" statt einer Richtung.
- Anzeige der Richtung relativ zur Startlage: Grad nach links oder rechts und nach oben oder unten gegenüber der
  Blickrichtung der Kamera beim Start.

## Ablauf auf dem Gerät
1. Geräuschquelle vorbereiten: ein zweites Handy oder Radio mit gleichmäßigem Rauschen, 1,5 bis 2 m entfernt, fest
   stehend.
2. Einstellungen, "Mikrofon-Test", Knopf "Schwenk-Messung". Kamera beim Start auf die Quelle richten.
3. Ansage: "Zweimal auf die Rückseite tippen" (3 s), dann "Langsam schwenken" (22 s): nach links und rechts drehen,
   auf hochkant kippen, schräg, etwas nach oben und unten. Standort und Abstand bleiben gleich.
4. Ergebnis: Gleichlauf (ms), Sensorrate (Hz), genutzte Fenster, Abdeckung, geschätzte Richtung, wirksamer Abstand,
   Restfehler, Ordner der Dateien.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Künstlicher Schwenk (400 Fenster, Achsen aus Drehungen um alle drei Achsen bis ±60°, Abstand 15 cm, Versatz −0,04 ms, Messrauschen 0,02 ms): Richtung innerhalb 3° für Quellen vorne (0°/0°), schräg (30°/10°) und hinten (160°/−5°); wirksamer Abstand 15 ± 0,5 cm; vorne und hinten richtig unterschieden | `SweepMathTest > S-010 Ausgleich findet Quelle vorne, schraeg und hinten` |
| K2 | Nur um die Hochachse geschwenkt (alle Achsen waagrecht): "zu einseitig" statt Richtung; ganz ohne Drehung ebenso; weniger als 50 gültige Fenster: "zu wenige Messungen" | `SweepMathTest > S-010 einseitiger Schwenk wird erkannt` |
| K3 | Gleichlauf: Klopfer im Beschleunigungssensor bei t und im Ton bei t + 12 ms ergibt 12 ± 2 ms; ohne Klopfer kein Wert, kein Absturz | `SweepMathTest > S-010 Klopfer misst den Gleichlauf` |
| K4 | Laufzeit je Fenster aus künstlichem Stereo-Rauschen mit langsam wechselndem Versatz (−20 bis +20 Abtastwerte): je Fenster innerhalb 0,01 ms; stille Fenster fallen weg | `SweepMathTest > S-010 Laufzeit je Fenster` |
| K5 | Auswertung von 25 s Stereo bei 48 kHz mit 2.500 Lagewerten höchstens 2 s auf der JVM (bester von 3) | `SweepMathTest > S-010 Auswertung ist schnell genug` |
| K6 | UseCase mit Fakes: Ton und Lage laufen gleichzeitig; Abbruch gibt Mikrofon und Sensor frei (je genau ein `NonCancellable`-Block, R17); WAV, Lage-CSV und Meta-JSON mit Formatversion v1 gespeichert; Speicher voll: Lauf geht weiter mit Hinweis; ohne Berechtigung keine Aufnahme | `SweepUseCaseTest > S-010 ...` (vier Tests) |
| K7 | Adapter auf dem Emulator: Sensor wird an- und wieder abgemeldet, auch bei Abbruch; die Tonaufnahme liefert einen Zeitbezug oder meldet "grob" (Startzeit statt `getTimestamp`) | `MotionSensorAdapterTest > s010AnUndAbmelden`, `AudioRecordMicrophoneAdapterTest > s010Zeitbezug` |
| K8 | Bildschirm: Anleitung, Ansage "Langsam schwenken" und Ergebnis als Screenshot (je einer pro Test), Texte aus Ressourcen, keine Gedankenstriche | `SweepTest > schwenk_anleitung`, `schwenk_ansage`, `schwenk_ergebnis` |
| K9 | Gerätetest S24+: Gleichlauf höchstens 20 ms, Sensorrate mindestens 90 Hz, Quelle vor der Kamera innerhalb 15° erkannt (Vorzeichen der Achse wird dabei festgelegt), zweiter Lauf mit Quelle 90° rechts innerhalb 15°; wirksamer Abstand 13 bis 19 cm | Gerätetest |

## Nicht Teil dieser Änderung
- Keine Live-Anzeige, kein Pfeil, keine Einblendung ins Kamerabild, kein Abgleich mit einem Startbild (folgen, wenn K9
  zeigt, dass die Richtung genau genug ist).
- Keine Entfernung (Triangulation), keine mehreren Quellen gleichzeitig, keine Frequenzbänder.
- Keine Verschiebung des Handys im Raum berücksichtigt; nur Drehung.
- Keine Änderung am Quellen-Durchlauf und an der Klatsch-Probe aus S-008 und S-009.

## Betroffene Schichten und Regeln
- `:core:pure`: `SweepMath` (Quaternion zu Achse, Laufzeit je Fenster, Ausgleich, Abdeckung, Gleichlauf aus Klopfer),
  `Vec3`. Reine Funktionen mit Tests.
- `:core:boundary`: neu `MotionSensorBoundary` (`samples(): Flow<MotionSample>`, nur Zahlen: Zeit in ns, Quaternion,
  Beschleunigung) mit Fake; `MicCapture` bekommt den Zeitbezug (`anchorFrame`, `anchorBootNanos`, `timeExact`).
- **Neues Adapter-Modul `:core:sensors`** mit `MotionSensorAdapter` (`SensorManager`, `callbackFlow`, Abmelden in
  `awaitClose`). **Neue Regel R29**: `android.hardware.SensorManager`, `Sensor`, `SensorEvent`, `SensorEventListener`
  nur in `:core:sensors`, mit Prüfung und Mutationsproben (wie R28). Später auch für F1 (Stativ-Erkennung).
- `:core:audio`: Zeitbezug über `getTimestamp(..., TIMEBASE_BOOTTIME)`; Aufnahme von 25 s.
- `:core:data`: Speichern von CSV und JSON neben der WAV in `Recordings/CayResim/Schwenk-<Datum-Uhrzeit>/`.
- `:core:control`: `SweepUseCase`.
- `:feature:settings`: Knopf, Anleitung, Ansage, Ergebnis.
- **Freigabe nötig** (CLAUDE.md 1.3): neue Regel R29, neues Speichern von Daten (Lage-CSV, Meta-JSON). Keine neue
  Berechtigung: Sensoren bis 200 Hz brauchen keine, das Mikrofon ist seit S-008 erlaubt.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, Sensoren nur in `:core:sensors` (R29), Ton nur in `:core:audio` (R28) |
| Nur Zahlen, Schlüssel, Enums | `MotionSample` und Ergebnis nur Zahlen und Enums (R23) |
| Fehler und Ausweichweg | fehlender Sensor, kein Zeitbezug, zu wenige Messungen, zu einseitig: je eigener Ergebnisgrund, kein Absturz (R14, R24) |
| Abbruch und Freigabe | Sensor-Abmeldung in `awaitClose`, Mikrofon wie bisher; Test K6 und K7 (R17) |
| Puffer | 25 s Stereo 16 Bit = 4,8 MB, ein Puffer, ein Besitzer; Lagewerte etwa 2.500 × 8 Zahlen (R19) |
| Main-Thread | Aufnahme und Speichern auf IoDispatcher, Rechnen auf ComputeDispatcher (R16, R18) |
| Gespeicherte Daten | CSV mit Kopfzeile `format=v1`, JSON mit `"format": 1`; die App liest sie nicht zurück (R26) |
| Zeit und Speicher | Auswertung höchstens 2 s (K5), Lauf 25 s plus Speichern (R27) |
| neue Ausnahme | keine; neue Regel R29 |

## Risiken und Rückweg
- Der Zeitbezug des Tons fehlt auf manchen Geräten; dann gilt die Startzeit, und der Klopfer zeigt, wie groß der Fehler
  ist. Bei mehr als 20 ms wird die Richtung unscharf (bei 60°/s Schwenk etwa 1° je 17 ms).
- Hall im Raum verfälscht einzelne Fenster; die Ausgleichsrechnung mittelt, ein Ausreißerfilter folgt bei Bedarf.
- Die Mikrofonachse liegt nicht genau auf der y-Achse (hinteres Mikrofon sitzt auf der Rückseite). Der wirksame Abstand
  und der Restfehler zeigen das; die Achse kann aus den Daten nachgeschätzt werden.
- Datenschutz (CLAUDE.md 7): Die Aufnahmen bleiben auf dem Gerät, die Lage enthält keinen Ort; Testdaten sind künstlich.
- Rückweg: Knopf entfernen; `:core:sensors` hängt an nichts anderem.

## Kosten
Etwa 2 bis 3 kurze Läufe (neue Screenshots, neues Modul), 1 Release mit Emulator (etwa 30 Minuten). Unabhängige
Prüfung durch einen zweiten Agenten (deutlich über 150 Zeilen, geschätzt 700 bis 900). Gerätetest durch Arslan: zwei
Schwenk-Messungen (Quelle vorne, Quelle rechts), Screenshots und den Ordner schicken (etwa 5 MB je Lauf).

## Ergebnis
Noch offen.
