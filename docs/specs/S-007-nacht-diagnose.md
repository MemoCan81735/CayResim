# S-007: Nacht-Diagnose im Hinweis und Stabilisator-Anzeige

**Stand:** 10.10.2026 · **Status:** freigegeben (Arslan, 10.10., 5:53 Uhr)
**Anlass:** Gerätetest S24+ v0.1.98 (10.10., 5:44 Uhr), Vorhang-Szene im fast lichtlosen Raum. CayResim: Mittel RGB
39,0 / 39,2 / 48,5, Median 42, q0,99 58, Aufhellung x60,4 (Boden-Modus hat nicht gegriffen). Samsung dieselbe Szene:
Mittel 3,4 / 3,1 / 3,2, Median 2,7, q0,99 16, Vorhang 13 / 11 / 5, Boden 2. Die Laborszene aus S-006 ist grün. Warum
das Gerät anders entscheidet, zeigt die App nicht. Selbsttest: Stabilisator "aktiv: unbekannt" auch nach 1,5 s Warten;
ob das Gerät den Wert nicht meldet oder gar kein Aufnahmeergebnis kam, ist nicht unterscheidbar.

## Ziel
Der Hinweis nach der Nachtaufnahme zeigt die Werte, nach denen der Boden-Modus entschieden wird, und zwei Werte des
Bezugsbilds (das erste Einzelbild, nach einem Bezugswechsel das neue Bezugsbild). Der Hinweis bleibt 10 statt 4 s
stehen, damit ein Screenshot gelingt. Der Selbsttest unterscheidet "aktiv", "aus", "vom Gerät nicht gemeldet" und "kein
Aufnahmeergebnis". Das Bild selbst ändert sich nicht.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Lichtlose 8-Bit-Serie (lineares Gauss-Rauschen σ 0,003, bei 0 abgeschnitten, 24 Bilder): Diagnose geprüft, Boden-Modus ja, geschätztes Rauschen innerhalb 15 % der Wahrheit, Signal ≤ Schwelle, Schätzung an mindestens 50 % der Pixel | `NightMergeTest > S-007 Diagnose lichtlos schaetzt das Rauschen nahe der Wahrheit` |
| K2 | Erstes Bild mit genau 25 % Kanalwerten auf 0 und festen Mitteln: Nullen-Anteil 0,25 ± 0,001, Mittel je Kanal ± 0,01 | `NightMergeTest > S-007 Diagnose misst das erste Bild` |
| K2b | Nach einem Bezugswechsel gehören Nullen-Anteil und Mittel zum neuen Bezugsbild | `NightMergeTest > S-007 Diagnose misst nach einem Bezugswechsel das neue Bezugsbild` |
| K2c | Messung des Bezugsbilds bei 1,6 MP höchstens 10 ms (bester von 5 Läufen, JVM) | `NightMergeTest > S-007 Messung des Bezugsbilds dauert bei 1,6 MP hoechstens 10 ms` |
| K3 | Schwach beleuchtete Fläche (lineares Signal 0,01, Rauschen 0,003, 24 Bilder): geprüft, Boden-Modus nein, Signal > Schwelle, Signal innerhalb 0,002 der Wahrheit. Helle Fläche (Stufe 87 bis 93): Rauschen 0 (nicht schätzbar), unter 5 % der Pixel geschätzt, Boden-Modus nein | `NightMergeTest > S-007 Diagnose helle Szene ist kein Boden` |
| K4 | Weniger als 8 Bilder: nicht geprüft, Boden-Modus nein; RAW-Weg: keine Diagnose | `NightMergeTest > S-007 Randfall wenige Bilder und RAW` |
| K5 | Die Diagnose der Verarbeitung kommt unverändert im NightReport an (8 Bit) | `NightUseCaseTest > S-007 Diagnose kommt im Bericht an` |
| K6 | Hinweis zeigt eine zweite Zeile, z. B. "Boden-Modus nein: Signal 1,200, Schwelle 0,400, Rauschen 3,100, Median 2,000 (linear), geschätzt an R 60 / G 55 / B 70 %; Bezugsbild 41 % Nullen, Stufen 12,0 / 11,0 / 15,0". Signal, Schwelle, Rauschen, Median: lineares Licht mal 255 (1 = ein 255tel von Weiß), 3 Nachkommastellen. Stufen: 8-Bit-Werte der Kamera (sRGB) | `CameraContentTest > nacht_hinweis_zeigt_die_diagnose` |
| K7 | Ohne Prüfung: "Boden-Modus nicht geprüft" | `CameraContentTest > nacht_hinweis_diagnose_nicht_geprueft` |
| K7b | Rauschen nicht schätzbar (nirgends abgeschnitten): "Boden-Modus nein: kein Abschneiden erkannt, geschätzt an R .. %" | `CameraContentTest > nacht_hinweis_diagnose_kein_abschneiden` |
| K8 | ViewModel reicht die Diagnose an den Hinweis weiter | `CameraViewModelTest > S-007 Nacht-Hinweis traegt die Diagnose` |
| K9 | Selbsttest wartet bis 1,5 s auf einen gemeldeten Wert (ON oder OFF). Danach: Gerät meldet den Wert nicht, dann "vom Gerät nicht gemeldet (angefordert: ein)"; kein Ergebnis, dann "kein Aufnahmeergebnis"; erst ohne, später mit Wert, dann der gemeldete Wert | `SelfTestUseCaseTest > S-007 Stabilisator nicht gemeldet`, `SelfTestTest > S-007 Stabilisator nicht gemeldet` |
| K10 | Adapter: Ergebnis ohne Wert ergibt "nicht gemeldet", überschreibt aber nie ein gemeldetes ON oder OFF | `ModeMappingTest > S-007 Stabilisator ohne Wert im Ergebnis heisst nicht gemeldet, ueberschreibt aber keinen Wert` |
| K11 | Gerät ohne Stabilisator: "Optischer Stabilisator: nein", ohne "aktiv" | `SelfTestTest > S-007 ohne Stabilisator kein Wert fuer aktiv` |

## Nicht Teil dieser Änderung
- Keine Änderung an Rechnung, Schwellen oder Aufhellung: das Foto bleibt Bit für Bit gleich (Laborwerte unverändert).
- Kein Speichern von Serien (V3), kein Teilen-Knopf (V5).
- RAW-Weg ohne Diagnose (dort entscheidet der bekannte Schwarzwert).

## Betroffene Schichten und Regeln
- `:core:pure`: neuer Typ `NightDiagnosis` (nur Zahlen und Boolean), `NightMerge.diagnosis`.
- `:core:boundary`: `NightStats.diagnosis`; neues Enum `OisState` (ON, OFF, NOT_REPORTED) für
  `CameraStateSnapshot.stabilization` und `DeviceReport.oisActive` (R23). Fakes angepasst.
- `:core:processing`: reicht die Diagnose weiter.
- `:core:camera`: Aufnahmeergebnis ohne Stabilisator-Schlüssel ergibt NOT_REPORTED.
- `:core:control`: NightReport.diagnosis; Selbsttest übernimmt den Zustand.
- `:feature:camera`, `:feature:settings`: Texte.
- Keine neue Ausnahme, keine neue Berechtigung, kein neues Speichern.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | `NightDiagnosis` in `:core:pure`, über `api` der Boundary sichtbar (R1, R11) |
| Nur Zahlen, Schlüssel, Enums | Diagnose: Float, Boolean; Stabilisator als Enum (R23) |
| Fehler und Ausweichweg | Diagnose ist optional (null bei RAW); fehlende Werte ergeben "nicht geprüft" (R14, R24) |
| Abbruch und Freigabe | trifft nicht zu, kein neuer Ablauf |
| Bildpuffer | kein neuer Puffer; die Mittel je Kanal werden bei der Medianberechnung ohnehin erzeugt (R19) |
| Main-Thread | trifft nicht zu |
| Gespeicherte Daten | trifft nicht zu |
| Zeit und Speicher | zusätzlich ein Durchlauf über das Bezugsbild, gemessen 2,6 ms bei 1,6 MP auf der JVM (K2c; erste Fassung laut Zweitprüfung 12 bis 26 ms); bei einem Bezugswechsel ein Durchlauf mehr; keine neue Kopie (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
Langer Hinweis: der Snackbar-Text wird zwei Zeilen länger; bei großer Schrift kann er abgeschnitten werden.
Rückweg: Zeile aus `nightDetail` entfernen.

## Kosten
Ein kurzer Lauf für die Screenshot-Grundlage `selftest_device` (Text "kein Aufnahmeergebnis"), ein Release. Gerätetest
durch Arslan: dieselbe Vorhang-Szene, Hinweis und Selbsttest als Screenshot.

## Ergebnis
Labor (Vorhang-Szene aus S-006, gleiche Darstellung wie im Hinweis): Boden-Modus ja, Signal 0,006, Schwelle 0,017,
Rauschen 0,145, Median 0,081 (linear), geschätzt an 100 % der Pixel; Bezugsbild 56 % Nullen, Stufen 1,1 / 1,0 / 1,3.
Der Abstand zur Schwelle ist im Labor klein. Bildergebnis Bit für Bit gleich (Zweitprüfung: 6 Szenen per Prüfsumme
gegen main, Testlabor ohne geänderte Tabellenwerte).

Zweitprüfung (unabhängiger Agent): Kompilieren und Texte in Ordnung. Behoben: Ein Ergebnis ohne Stabilisator-Wert
überschrieb ein gemeldetes ON, und der Selbsttest nahm das erste Ergebnis, auch wenn es vor der Anforderung lag (K9,
K10); ohne Stabilisator stand "aktiv: kein Aufnahmeergebnis" (K11); Maßstäbe im Hinweis unklar (Median linear, Stufen
der Kamera); Messung des Bezugsbilds zu langsam (K2c); Hinweis nur 4 s sichtbar. Ergänzt: Tests K2b und K7b. Die
Rauschschätzung liegt bei reinem 8-Bit-Rauschen in 12 Läufen 8,5 bis 10 % unter der Wahrheit (vermutlich die Rundung
auf 8 Bit); K1 hält die Grenze von 15 % ein. K2b schützt ein Verhalten, das schon vorher stimmte; er konnte mit dem
alten Code nicht rot werden. Nach der Freigabe geändert (bitte bestätigen): K3 an den Test angepasst, K6 mit 3
Nachkommastellen und "Bezugsbild" statt "Bild 1", K7b bis K11 und die längere Anzeige des Hinweises ergänzt.
