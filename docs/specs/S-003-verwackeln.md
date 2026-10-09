# S-003: Schutz gegen Verwackeln, Teil 1

**Stand:** 9. Oktober 2026 · **Status:** freigegeben (Arslan, 22:00)
**Anlass:** Prüfung "Schutz gegen Verwackeln" am 9. Oktober und Gerätetest S24+ (Jeans im beleuchteten Raum, 36 Bilder,
0 verworfen, Stoffmuster verschmiert). Befunde im Code:
- Nacht: jedes Bild wird nur mit dem ersten verglichen. Ist das erste verwackelt, kommen verwackelte Bilder durch
  (Grenze 50 % der Schärfe des ersten Bilds). Python-Modell (Blockmuster, Bewegungsunschärfe 8 Pixel in jedem dritten
  Bild, auch im ersten): alter Weg nimmt alle 36 Bilder, Struktur 75 % der unverwackelten Serie; mit dem schärfsten der
  ersten 3 Bilder als Bezug 24 Bilder, Struktur 100 %.
- Wie stark gewackelt wurde, zeigt das Gerät nicht. Das Testlabor kennt nur Versatz zwischen Bildern, keine
  Unschärfe innerhalb eines Bilds.
- Der optische Stabilisator wird nirgends angefordert; ob er läuft, ist nicht gemessen.
- Kein Selbstauslöser: der Druck auf den Auslöser verwackelt selbst.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Nacht: Bezug ist das schärfste der ersten 3 Bilder (Median der Kantenenergie je Feld, damit ein vorbeilaufendes helles Objekt nicht Bezug wird: `NightMergeTest > S-003 Fehlerfall vorbeilaufendes ...`); Laborszene "Bewegungsunschärfe in jedem dritten Bild, auch im ersten": Struktur mindestens 90 % der unverwackelten Serie, mindestens 12 Bilder verworfen | `QualityLabTest > Testlabor verwackelte Einzelbilder ...` (alter Code: 0 verworfen, etwa 75 %, rot) |
| K2 | Nacht-Hinweis zeigt das Wackeln, z. B. ", Wackeln bis 12 px" (größter angenommener Versatz zum Bezug, in Pixeln des Nachtbilds) | `NightTest > Wackeln wird gemessen`, `NightUseCaseTest > Bericht nennt das Wackeln`, `CameraContentTest > nacht_hinweis_zeigt_das_wackeln` |
| K3 | Optischer Stabilisator wird angefordert, wo das Gerät ihn anbietet (eigene Pipeline, Pro und RAW-Sitzung; Samsungs Modi regeln ihn selbst) | Codeprüfung (Emulator hat keinen Stabilisator) |
| K4 | Selbsttest zeigt "Optischer Stabilisator: ja, aktiv: ja" (angeboten laut Gerät, aktiv laut letzter Aufnahme) | `SelfTestUseCaseTest > Geraetewerte nennen den Stabilisator`, `SelfTestTest > bild_geraetewerte` |
| K5 | Selbstauslöser 2 s: Schalter oben im Sucher, Countdown sichtbar, erneuter Druck bricht ab, Verlassen des Screens bricht ab | `CameraViewModelTest > Selbstausloeser ...` (3 Fälle), `CameraContentTest > selbstausloeser_zeigt_countdown_und_schalter` |
| K6 | alle bisherigen Laborgrenzen bleiben grün | bestehende Labortests |

## Nicht Teil dieser Änderung
Ausrichtung für Langzeit, Wegrechnen und Fokus-Stacking, Pro-Warnung (S-004); kürzere Einzelbilder bei mehr Licht
(nach den Gerätewerten aus S-002); Gyroskop; Ausrichtung mit Drehung.

## Betroffene Schichten und Regeln
`:core:pure` (NightMerge), `:core:boundary` (NightStats, DeviceReport, CameraStateSnapshot), `:core:control`
(NightUseCase, SelfTestUseCase), `:core:processing` (Kennzahlen weiterreichen), `:core:camera` (Stabilisator),
`:feature:camera` (Hinweis, Selbstauslöser), `:feature:settings` (Gerätewerte). Regeln R17, R19, R23, R27.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | keine neuen Abhängigkeiten |
| Nur Zahlen in Ergebnissen | Wackeln als `Int`, Stabilisator als `Boolean?`, Countdown als `Int?` (R23) |
| Fehler und Ausweichweg | Stabilisator nur, wenn angeboten; sonst wie bisher (R14) |
| Abbruch und Freigaben | Countdown als eigener Job, bricht bei erneutem Druck und beim Verlassen ab (R17) |
| Bildpuffer | bis 2 Kopien für die Wahl des Bezugs (8 Bit, je 4,7 MB); RAW behält das erste Bild als Bezug (je 36 MB) (R19) |
| Main-Thread, Speichern | nicht betroffen; Selbstauslöser wird nicht gespeichert |
| neue Ausnahme | keine |

Zweitprüfung (unabhängiger Agent, vor dem ersten Lauf): zwei Tests wären rot gewesen. Ein Bild mit hellem, bewegtem
Objekt hatte mit dem Mittelwert der Kantenenergie 4-fache "Schärfe" und wäre Bezug geworden (Geisterbild); jetzt Median
je Feld (Python: 1,04-fach). Der Wackeltest nutzte das Schachbrett, das für die Ausrichtung mehrdeutig ist; jetzt
unregelmäßige 3-Pixel-Blöcke (Python mit derselben Ausrichtung: exakt). Außerdem umgesetzt: Timer-Schalter zuletzt in der
Leiste (Serienwahl bleibt sichtbar), scharf geschalteter Auslöser wird ohne Countdown entschärft, alter
Stabilisator-Wert wird beim Stopp und in Samsungs Modi verworfen. Screenshot-Grundlagen der Kamera und der Gerätewerte
werden neu aufgenommen und nach Sichtprüfung übernommen.

## Risiken und Rückweg
Mehr verworfene Bilder können die Serie kürzen ("Serie gekürzt" ab weniger als 27 Bildern): ehrlich gemeldet.
Rückweg: `REF_CANDIDATES = 1`.

## Kosten
Ein Release (etwa 20 Minuten, Repository öffentlich, keine Minuten).

## Ergebnis
(nach dem Lauf eintragen)
