# S-009: Mikrofon-Test mit Pause, Eichung und besserem Stereo-Kriterium

**Stand:** 10.10.2026 · **Status:** freigegeben von Arslan (10.10., 12:48 Uhr: Spec wie Entwurf, ein Release), umgesetzt in v0.1.112, Zweitprüfung abgearbeitet, Gerätetest offen
**Anlass:** Gerätetest S-008 auf dem S24+ (v0.1.109, 10.10., 12:22 und 12:33 Uhr), nachgerechnet aus den WAV-Dateien
(Ergebnis in S-008):
- Quer gehalten funktioniert die Ortung: links −0,48 bis −0,50 ms, rechts +0,39 bis +0,44 ms, vorne −0,05 bis 0,00 ms.
- Die angenommenen 15 cm passen nicht: Links erscheint als "unmöglich" oder −90°, rechts nur als +52° bis +66°. Links
  und rechts sind nicht spiegelgleich (Mitte etwa −0,04 ms, halbe Spanne etwa 0,44 ms).
- Die Aufnahme jeder Phase beginnt sofort mit der Ansage. Am Anfang von "rechts" stehen deshalb noch zwei Klatscher mit
  den Werten von links (−0,48 und −0,41 ms), am Anfang von "links" drei Griffgeräusche mit Laufzeit 0.
- Hochkant gehalten liegen beide Mikrofone auf der Längsachse; dann ergibt jede Richtung etwa 0 ms. Die App merkt das
  nicht, und auch die Auswertung hat es zuerst übersehen.
- "verschieden: ja" auch bei nur einem aktiven Mikrofon. Messwerte der Kanal-Korrelation:

  | Aufnahme | Korrelation | Korrelation der Änderungen |
  |---|---|---|
  | `MIC` (zwei Mikrofone), Lauf 1 / Lauf 2 | 0,41 / 0,15 | −0,01 / 0,00 |
  | `MIC` Richtung Rückseite, Lauf 1 | 0,86 | −0,01 |
  | nur Gerät 16, Lauf 1 / Lauf 2 | 0,99 / 0,85 | 0,59 / 0,60 |
  | nur Gerät 17, Lauf 1 / Lauf 2 | 0,94 / 0,92 | 0,64 / 0,61 |
  | `VOICE_RECOGNITION` (doppeltes Mono) | 1,00 | 1,00 |

  Die einfache Korrelation trennt nicht: echtes Stereo kommt bis 0,86 (tiefe Raumgeräusche erreichen beide Mikrofone
  gleich), ein einzelnes Mikrofon ab 0,85. Die Korrelation der Änderungen von Abtastwert zu Abtastwert (ein einfacher
  Hochpass) trennt klar: echtes Stereo um 0, ein Mikrofon um 0,6.

## Ziel
Die Klatsch-Probe liefert brauchbare Winkel ohne angenommenen Abstand: Die App eicht sich bei jedem Lauf selbst aus den
Klatschern links und rechts, gibt vor jeder Phase Zeit zum Hinstellen und sagt, wenn links und rechts nicht zu
unterscheiden sind (Handy hochkant). "verschieden" bedeutet wirklich zwei Mikrofone.

## Ablauf auf dem Gerät (geändert gegenüber S-008)
1. Quellen-Durchlauf wie bisher.
2. Vor jeder Klatsch-Phase 2 s Vorbereitung ohne Aufnahme: "Gleich links klatschen. Jetzt hinstellen, noch nicht
   klatschen. Das Handy nicht berühren." Danach wie bisher "Links neben dem Handy klatschen" mit 4 s Aufnahme.
3. Nach den drei Phasen: Eichung aus links und rechts, danach alle Winkel geeicht.
4. Im Ergebnis neu: wirksamer Mikrofonabstand und Mitte aus der Eichung, oder warum nicht geeicht wurde.

## Eichung
- Je Seite der Median der Laufzeiten aller Klatscher dieser Phase (robust gegen einzelne Griffgeräusche).
- Mitte = (rechts + links) / 2, halbe Spanne = (rechts − links) / 2, wirksamer Abstand = |halbe Spanne| · 343 m/s.
- Winkel = asin((Laufzeit − Mitte) / halbe Spanne). Links ist damit −90°, rechts +90°, unabhängig vom Vorzeichen des
  Geräts. Über 1,1 bleibt "unmöglich".
- Keine Eichung, mit Grund im Ergebnis, wenn:
  - eine Seite weniger als 3 Klatscher hat ("zu wenige Klatscher"),
  - |rechts − links| unter 0,1 ms liegt ("links und rechts gleich: Handy hochkant gehalten?"),
  - der wirksame Abstand außerhalb 3 bis 30 cm liegt ("unplausibel").
  Dann gelten die Winkel wie bisher aus dem Abstand laut Gerät oder 15 cm, so beschriftet.
- Die Eichung gilt nur für diesen Lauf und wird nicht gespeichert.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Laufzeiten des Quertests S24+ aus S-008 (links 7 Werte: −0,48 viermal und die Griffgeräusche 0,00, −0,02, −0,20; rechts 8 Werte von +0,33 bis +0,40 ohne die zwei Klatscher von links; vorne 7 Werte von −0,06 bis +0,01 ms): Mitte −0,04 ms ± 0,01, wirksamer Abstand 15,1 cm ± 0,3; die vier Klatscher links ≤ −75°, rechts 7 von 8 ≥ +75° (+0,33 ms ergibt +57°), vorne alle innerhalb ±10°. Rot mit altem Code: +0,39 ms ergibt bei 15 cm nur +63° | `AudioMathTest > S-009 Quertest S24+ ergibt geeichte Winkel` |
| K2 | Gründe ohne Eichung: Hochkant-Werte des ersten Laufs (alle Seiten innerhalb ±0,06 ms) ergeben "links und rechts gleich"; 2 Klatscher auf einer Seite "zu wenige"; Spanne 2 ms "unplausibel"; NaN-Laufzeiten zählen nicht mit | `AudioMathTest > S-009 Eichung lehnt Hochkant, zu wenige und Unsinn ab` |
| K3 | Stereo-Kriterium über die Korrelation der Änderungen, Grenze 0,3: (a) ein Signal auf beiden Kanälen plus je eigenes Rauschen, Korrelation der Änderungen 0,6: nicht verschieden (rot mit altem Code, dort Korrelation 0,9 unter 0,99); (b) zwei unabhängige Rauschkanäle plus gemeinsames tiefes Brummen, Korrelation 0,85: verschieden; (c) doppeltes Mono mit Zittern ±1: nicht verschieden (K2 aus S-008 bleibt grün) | `AudioMathTest > S-009 Stereo nur bei zwei Mikrofonen` |
| K4 | Vor jeder Phase das Ereignis "vorbereiten" mit 2 s; die Aufnahme der Phase beginnt frühestens 2 s danach (virtuelle Zeit); Abbruch während der Vorbereitung startet keine Aufnahme | `MicTestUseCaseTest > S-009 Pause vor jeder Klatsch-Phase` |
| K5 | Lauf mit Fake: Klatscher links mit −10 Abtastwerten, rechts mit +8, vorne mit −1 ergeben Eichung im Bericht und geeichte Winkel (links ≤ −80°, rechts ≥ +80°, vorne 0° ± 5; nahe ±90° ist der Winkel empfindlich, deshalb keine engere Grenze); alle Phasen mit Versatz 0 ergeben keine Eichung mit Grund "gleich" und Winkel aus dem Abstand | `MicTestUseCaseTest > S-009 Bericht mit und ohne Eichung` |
| K6 | Bildschirm: Ergebnis mit Eichung (wirksamer Abstand, Mitte, Winkel "geeicht"), Ergebnis ohne Eichung mit Hinweis "Handy hochkant?", Ansage der Vorbereitung; je ein Screenshot pro Test, Texte aus Ressourcen, keine Gedankenstriche. Neue Grundlagen: `mictest_not_calibrated`, `mictest_prepare`; geänderte: `mictest_claps`, `mictest_clap` (Ansagetext), `mictest_result` und `mictest_no_stereo` (Zeile der Quellen mit Korrelation der Änderungen) | `MicTestTest > mikrotest_klatsch_ergebnis`, `mikrotest_nicht_geeicht`, `mikrotest_vorbereiten`, `S-009 Vorbereitung vor jeder Klatsch-Phase` |
| K7 | Gerätetest S24+ quer: Winkel links ≤ −75°, rechts ≥ +75°, vorne innerhalb ±15°, wirksamer Abstand 13 bis 19 cm, erster Klatscher von "rechts" hat das Vorzeichen von rechts; hochkant: Hinweis "Handy hochkant?" | Gerätetest |

Für K1, K2, K3 und K5 laufen die Tests lokal vor dem Code rot (`tools/run-pure-tests.sh`, `tools/run-core-tests.sh`).
K6 ist rot, weil die neuen Texte und Screenshots fehlen (CI); K4 ist rot, weil das Ereignis "vorbereiten" fehlt.

## Nicht Teil dieser Änderung
- Keine gespeicherte Eichung, kein Peilen mit Pfeil, keine Ortung im Kamera-Modus.
- Vorne und hinten bleiben ununterscheidbar (zwei Mikrofone liefern nur den Winkel zur Achse).
- Quellen-Durchlauf, Dateinamen und WAV-Format (v1) bleiben gleich; die Klatsch-Probe nimmt weiter 4 s je Phase auf.
- Keine Erkennung von Griffgeräuschen; der Median und die Pause genügen nach den Messwerten.

## Betroffene Schichten und Regeln
- `:core:pure`: `AudioMath.RecordingStats` bekommt `diffCorrelation`, `distinctChannels` nutzt sie (Grenze 0,3 statt
  einfacher Korrelation unter 0,99); neu `AudioMath.Calibration` (Mitte, halbe Spanne, wirksamer Abstand),
  `CalibrationFailure` (TOO_FEW_CLAPS, SIDES_NOT_DISTINCT, IMPLAUSIBLE), `calibrate(left, right)` und
  `calibratedAngle(delay, calibration)`.
- `:core:control`: `MicTestUseCase` mit Ereignis `ClapPrepare(phase, millis)` und `delay`; Bericht mit `calibration`
  oder `calibrationFailure`, Winkel nach der Eichung neu gesetzt.
- `:feature:settings`: ViewModel spiegelt die neuen Werte (eigene UI-Enums), Bildschirm und Texte.
- Keine Änderung an Boundary, Adaptern, Manifest oder Regeln. Keine neue Ausnahme, kein neues Speichern, keine neue
  Berechtigung.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, Ablauf in `:core:control`, Anzeige in `:feature:settings` (R1, R11, R28 unverändert) |
| Nur Zahlen, Schlüssel, Enums | Eichung als Zahlen, Grund als Enum; Texte baut die UI (R23) |
| Fehler und Ausweichweg | keine Eichung führt zu den bisherigen Winkeln mit Grund (R14, R24) |
| Abbruch und Freigabe | `delay` ist abbrechbar; die Aufnahme startet erst danach, Freigabe im Adapter wie bisher (R17), K4 |
| Bildpuffer | unverändert |
| Main-Thread | Rechnen weiter auf dem ComputeDispatcher (R16, R18) |
| Gespeicherte Daten | keine neuen |
| Zeit und Speicher | Klatsch-Probe 6 s länger (3 × 2 s); Eichung rechnet nur Mediane weniger Werte |
| neue Ausnahme | keine |

## Risiken und Rückweg
- Wer links oder rechts nicht seitlich, sondern schräg klatscht, verkleinert die Spanne; dann werden die Winkel zu groß.
  Die Ansage sagt "Links neben dem Handy"; der Gerätetest prüft die Spanne gegen die Messung von heute (K7).
- S-008 K2 ändert sich: Ein um einen Abtastwert verschobenes Mono galt als "verschieden", jetzt nicht mehr (Korrelation
  der Änderungen −0,5). Grenze: Bei 2 und mehr Abtastwerten Versatz desselben Mikrofons fällt diese Korrelation für
  breites Rauschen auf etwa 0; solch ein Kanal gälte wieder als "verschieden". Auf dem Gerät nicht beobachtet.
- Scheitert die Aufnahme einer Seite, lautet der Grund "zu wenige Klatscher"; der eigentliche Fehler steht in der
  Zeile der Phase. Bekannt, nicht behoben.
- Die Grenze 0,3 für die Korrelation der Änderungen beruht auf zwei Läufen eines Geräts. Rückweg: Grenze anpassen,
  beide Korrelationen stehen im Ergebnis.
- Rückweg insgesamt: Commit zurücknehmen; nichts wird gespeichert.

## Kosten
Etwa 3 kurze Läufe (zwei neue und vier geänderte Screenshot-Grundlagen, Lint), 1 Release (etwa 30 Minuten). Unabhängige
Prüfung durch einen zweiten Agenten (über 150 Zeilen erwartet). Gerätetest durch Arslan: Mikrofon-Test einmal quer und
einmal hochkant, Screenshots schicken (WAV-Dateien nur bei Auffälligkeiten).

## Umsetzung und Zweitprüfung
Tests zuerst rot: K1, K2, K4 und K5 lokal durch fehlende Funktionen (Kompilierfehler); K3 zusätzlich durch den Wert,
nachgewiesen mit dem alten Kriterium (Korrelation unter 0,99): "ein Mikrofon ist kein Stereo" scheitert. Danach lokal
grün: 150 Tests in `:core:pure`, 10 in `MicTestUseCaseTest`, Architektur 41 Proben, 0 Verstöße. Der lokale Ersatz für
`runTest` bekam `testScheduler.currentTime` (echte Uhr), damit derselbe Testcode lokal und mit Gradle läuft.

Erster kurzer Lauf auf `probe/s009` (38046760631): Kern und APKs grün, rot erwartungsgemäß 6 Screenshots und ein
Lint-Fund `PluralsCandidate` ("%d Sekunden"; Text jetzt "Pause in Sekunden: %1$d"). Zweiter Lauf (38047345112): alles
grün außer der fehlenden Grundlage `mictest_prepare`; alle sechs Bilder angesehen und übernommen.

Unabhängige Prüfung (zweiter Agent, 10.10.), kein blockierender Befund; behoben:
- Spec nannte nur eine geänderte Screenshot-Grundlage statt vier (K6, Kosten ergänzt),
- Änderung an S-008 K2 nicht genannt (Risiken ergänzt),
- Funktionsliste F10 noch "Entwurf",
- Winkelgrenzen ±2° nahe ±90° zu empfindlich (jetzt ≤ −80° / ≥ +80°),
- `diffCorrelation` mit stillem Standardwert (jetzt ohne) und zwei Kopien je Analyse (jetzt ohne Kopien),
- untere Grenze von "unplausibel" ungetestet (Fall 2,1 cm ergänzt),
- veralteter Kommentar zur Laufdauer, Grenze des lokalen Ersatzes im Kopf von `tools/run-core-tests.sh`.
Bewusst nicht behoben: Grund "zu wenige Klatscher" bei gescheiterter Aufnahme (Risiken).

## Ergebnis
CI auf main (38047611072, mit Emulator und Release): alle Jobs grün, 534 Tests, 0 rot; Release v0.1.112.
Gerätetest noch offen (K7).
