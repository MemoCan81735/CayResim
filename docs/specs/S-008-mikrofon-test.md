# S-008: Mikrofon-Test

**Stand:** 10.10.2026 · **Status:** Entwurf, wartet auf Freigabe
**Anlass:** Wunsch Arslan (10.10.): Geräusche mit den Mikrofonen des S24+ orten. Vorprobe mit einem Video der
Samsung-Kamera (10.10., 10:02 Uhr, quer gehalten, je drei Klatscher links, rechts, vorne, hinten, schräg in etwa 2 m):
Der Ton hat zwei verschiedene Kanäle (Korrelation 0,17 über die ganze Aufnahme), ist aber stark bearbeitet. Links und
rechts ergaben dasselbe Vorzeichen der Laufzeitdifferenz (+0,44 bis +0,48 ms und +0,13 bis +0,81 ms), ein Wert liegt
über dem physikalisch möglichen Maximum von 0,44 ms bei 15 cm Abstand, derselbe Klatscher ähnelt sich auf beiden
Kanälen nur mit 0,2 bis 0,5, und bei der ersten Gruppe ist ein Kanal um 45 bis 53 dB leiser (abgeschaltet). Ortung
ist damit nicht möglich. Ob unbearbeiteter Ton zu bekommen ist, weiß niemand; das klärt dieser Test.

## Ziel
Ein neuer Punkt "Mikrofon-Test" in den Einstellungen misst, welche Mikrofone das Gerät meldet und welche Audioquellen
eine fremde App in Stereo und unbearbeitet bekommt. Eine kurze Klatsch-Probe zeigt, ob sich daraus eine Richtung
ablesen lässt. Alle Aufnahmen werden als WAV gespeichert, damit Arslan sie zur Auswertung schicken kann. Am Fotografieren
ändert sich nichts.

## Ablauf auf dem Gerät
1. Einstellungen, "Mikrofon-Test", Knopf "Start". Beim ersten Mal fragt Android nach der Mikrofon-Berechtigung.
2. **Liste:** alle Mikrofone aus `AudioManager.getMicrophones()` (Ort, Position in Metern, Richtcharakteristik,
   Empfindlichkeit) und alle Eingabegeräte aus `getDevices(GET_DEVICES_INPUTS)` (Typ, Adresse, Kanalzahlen).
3. **Quellen-Durchlauf** (still halten, Raum ruhig), je 2 s Stereo mit 48 kHz, 16 Bit:
   `MIC`, `CAMCORDER`, `VOICE_RECOGNITION`, `UNPROCESSED` (nur wenn `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`),
   dazu `MIC` mit bevorzugter Richtung "zur Kamera" und "weg von der Kamera" und `MIC` je eingebautem Eingabegerät
   über `setPreferredDevice`. Je Aufnahme: tatsächliche Kanalzahl, ob beide Kanäle gleich sind, Grundrauschen je Kanal,
   Korrelation der Kanäle, welches Gerät Android tatsächlich nutzt (`getRoutedDevice`) und die aktiven Mikrofone
   (`getActiveMicrophones`).
4. **Klatsch-Probe** mit der Quelle, die am ehesten roh ist (Reihenfolge `UNPROCESSED`, `VOICE_RECOGNITION`, `MIC`;
   die erste mit zwei verschiedenen Kanälen): Handy quer, die App sagt an "Links klatschen" (4 s), "Rechts klatschen"
   (4 s), "Vor dem Handy klatschen" (4 s). Je Klatscher: Laufzeitdifferenz (GCC-PHAT), Winkel, Ähnlichkeit der Kanäle.
5. **Ergebnis** auf dem Bildschirm als Liste, dazu der Ordner der WAV-Dateien.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Synthetischer Klatscher (Rauschimpuls 10 ms, Abstand 15 cm, Rauschen −50 dB), Bruchteil-Verzögerung für −60, −30, 0, +30, +60 Grad: Laufzeit innerhalb 0,02 ms, Winkel innerhalb 3 Grad der Wahrheit | `AudioMathTest > S-008 GCC-PHAT findet die Richtung synthetischer Klatscher` |
| K2 | Zwei identische Kanäle: "gleich" erkannt (Anteil gleicher Abtastwerte ≥ 99,9 %); ein Kanal mit 1 Abtastwert Versatz: nicht gleich | `AudioMathTest > S-008 erkennt doppeltes Mono` |
| K3 | Grundrauschen in dBFS je Kanal innerhalb 0,5 dB der Wahrheit (Gauss-Rauschen −60 dBFS); stummer Kanal ergibt −inf als eigener Wert, kein NaN | `AudioMathTest > S-008 Grundrauschen und stummer Kanal` |
| K4 | Klatsch-Erkennung: 9 synthetische Klatscher in 12 s Aufnahme mit Sprache-ähnlichem Rauschen −30 dB dazwischen: genau 9 Treffer, Zeit innerhalb 5 ms | `AudioMathTest > S-008 findet Klatscher, nicht das Rauschen` |
| K5 | Randfälle: Laufzeit größer als physikalisch möglich wird als "unplausibel" markiert statt in einen Winkel umgerechnet; leere oder zu kurze Aufnahme ergibt Fehlerwert, keine Exception | `AudioMathTest > S-008 Randfaelle` |
| K6 | GCC-PHAT für 40 ms Fenster bei 48 kHz höchstens 5 ms (bester von 5, JVM) | `AudioMathTest > S-008 GCC-PHAT ist schnell genug` |
| K7 | WAV-Schreiber: Kopf nach RIFF/WAVE-Format (44 Byte, PCM 16 Bit, Kanalzahl, Rate); Rücklesen ergibt dieselben Abtastwerte | `WavTest > S-008 WAV-Kopf und Rundreise` |
| K8 | UseCase mit Fake-Boundary: alle angebotenen Quellen in fester Reihenfolge, nicht angebotene übersprungen und als "nicht angeboten" gemeldet; Quelle mit Fehler bricht den Lauf nicht ab | `MicTestUseCaseTest > S-008 Durchlauf mit fehlender und fehlerhafter Quelle` |
| K9 | Abbruch (Coroutine, Bildschirm verlassen) mitten in einer Aufnahme: Aufnahmegerät wird in genau einem `NonCancellable`-Block freigegeben, Fake zählt 0 offene Aufnahmen | `MicTestUseCaseTest > S-008 Abbruch gibt das Mikrofon frei` |
| K10 | Ohne Berechtigung: Ergebnis "keine Berechtigung", keine Aufnahme gestartet | `MicTestUseCaseTest > S-008 ohne Berechtigung` |
| K11 | Klatsch-Probe wählt die erste Quelle mit zwei verschiedenen Kanälen; gibt es keine, steht "Ortung nicht möglich: kein echtes Stereo" und es wird nicht geklatscht | `MicTestUseCaseTest > S-008 Quelle fuer die Klatsch-Probe` |
| K12 | Bildschirm: Liste, Quellen-Ergebnisse und Klatsch-Ergebnis als Screenshot (hell, dunkel); Texte nur aus Ressourcen | `MicTestScreenTest > mikrotest_ergebnis`, `mikrotest_ohne_stereo` |
| K13 | Adapter auf dem Emulator: Aufnahme von 2 s liefert 96.000 ± 2.400 Frames, WAV-Datei existiert und lässt sich lesen; danach ist kein `AudioRecord` mehr offen | `AudioRecordMicrophoneAdapterTest > S-008 Aufnahme und Freigabe` |
| K14 | Gesamtdauer des Laufs ohne Klatsch-Probe höchstens 30 s, gemessen und angezeigt | `MicTestUseCaseTest > S-008 Dauer wird gemessen`, Gerätetest |

## Nicht Teil dieser Änderung
- Keine Ortung im Kamera-Modus, kein Audio-Zoom, kein Ton zu Videos oder Zeitraffern.
- Kein Hochladen oder Teilen-Knopf; die Dateien liegen in der Medienablage und werden von Hand geschickt.
- Keine Änderung am Foto-Selbsttest; der Mikrofon-Test ist ein eigener Punkt, weil er die Mikrofon-Berechtigung braucht
  und Mitarbeit (Klatschen) verlangt.
- Keine Bewertung von Klangqualität (Frequenzgang, Verzerrung).

## Betroffene Schichten und Regeln
- `:core:pure`: `AudioMath` (GCC-PHAT, Klatsch-Erkennung, Grundrauschen, Kanalvergleich, Winkel), `Wav` (Kopf, Kodierung).
  Reine Funktionen mit Tests (R21 sinngemäß für Ton).
- `:core:boundary`: `MicrophoneBoundary` mit `inventory(): MicInventory`, `record(request): MicRecordResult`;
  Snapshots `MicInfo`, `InputDeviceInfo`, `MicRecording` (nur Zahlen, Schlüssel, Enums, URI), Enum `AudioSourceKey`,
  versiegeltes `MicRecordResult` mit `MicFailure` (NO_PERMISSION, NOT_OFFERED, INIT_FAILED, STORAGE, CANCELLED). Fake
  in den Testquellen.
- **Neues Adapter-Modul `:core:audio`** mit `AudioRecordMicrophoneAdapter`: einziges Modul, das `android.media.AudioRecord`
  und `AudioManager` für Aufnahmen kennt. Neue Regel **R28** (analog R11) mit Konsist-Prüfung und Mutationsprobe.
- `:core:data`: Speichern der WAV-Dateien über MediaStore unter `Recordings/CayResim/Mikrotest-<Datum-Uhrzeit>/` auf dem
  IoDispatcher.
- `:core:control`: `MicTestUseCase` (Ablauf, Auswahl der Quelle, Dauer, Freigabe).
- `:feature:settings`: `MicTestViewModel`, `MicTestScreen`, Eintrag in den Einstellungen, Berechtigungsabfrage.
- `:app`: Manifest `RECORD_AUDIO`, Verdrahtung in Hilt.
- **Neue Berechtigung** `RECORD_AUDIO`, **neues Speichern** von Dateien, **neue Regel R28**: Freigabe nötig (CLAUDE.md 1.3).

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, Android-Ton nur in `:core:audio` (R28), UI kennt nur ViewModel (R1) |
| Nur Zahlen, Schlüssel, Enums | Ergebnisse als Zahlen und Enums; Texte und Einheiten baut die UI (R23) |
| Fehler und Ausweichweg | jede Quelle einzeln mit Ergebnistyp; fehlende Quelle "nicht angeboten", Lauf geht weiter (R14, R24) |
| Abbruch und Freigabe | `AudioRecord.stop/release` in genau einem `NonCancellable`-Block mit Merker (R17, Befund H1), K9 |
| Bildpuffer | Tonpuffer fester Größe je Aufnahme (2 s: 384 KB), höchstens eine Aufnahme gleichzeitig (R19 sinngemäß) |
| Main-Thread | Aufnahme auf eigenem Lese-Thread des Adapters, Speichern auf IoDispatcher (R16, R18) |
| Gespeicherte Daten | WAV ist ein festes Format; Dateiname enthält Quelle und Testversion "v1" (R26) |
| Zeit und Speicher | Lauf höchstens 30 s plus 12 s Klatsch-Probe, etwa 6 MB WAV je Lauf; GCC-PHAT gemessen (K6, K14, R27) |
| neue Ausnahme | keine; neue Regel R28 |

## Risiken und Rückweg
- Samsung kann auch bei `UNPROCESSED` bearbeiteten oder Mono-Ton liefern. Dann ist das Ergebnis "Ortung nicht möglich"
  und trotzdem wertvoll: Die Frage ist beantwortet, ohne die Kamera-App anzufassen.
- `setPreferredDevice` und die Richtungswahl können still ignoriert werden; das zeigt `getRoutedDevice` und
  `getActiveMicrophones`, deshalb werden beide mitgemessen.
- Die Berechtigung erscheint im System als "Mikrofon" für eine Kamera-App; sie wird nur im Mikrofon-Test abgefragt,
  nicht beim Start.
- Datenschutz (CLAUDE.md 7): Aufnahmen bleiben auf dem Gerät, nie im Repository; Testdaten in CI sind synthetisch.
- Rückweg: Einstellungs-Eintrag entfernen; das Modul `:core:audio` hängt an nichts anderem.

## Kosten
Etwa 2 kurze Läufe (Screenshot-Grundlagen für den neuen Bildschirm), 1 Release (etwa 30 Minuten, Emulator wegen neuem
Modul). Gerätetest durch Arslan: Mikrofon-Test in ruhigem Raum, Ergebnis-Screenshot, WAV-Ordner schicken (etwa 6 MB).

## Ergebnis
Offen.
