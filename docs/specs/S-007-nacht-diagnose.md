# S-007: Nacht-Diagnose im Hinweis und Stabilisator-Anzeige

**Stand:** 10.10.2026 · **Status:** freigegeben (Arslan, 10.10., 5:53 Uhr)
**Anlass:** Gerätetest S24+ v0.1.98 (10.10., 5:44 Uhr), Vorhang-Szene im fast lichtlosen Raum. CayResim: Mittel RGB
39,0 / 39,2 / 48,5, Median 42, q0,99 58, Aufhellung x60,4 (Boden-Modus hat nicht gegriffen). Samsung dieselbe Szene:
Mittel 3,4 / 3,1 / 3,2, Median 2,7, q0,99 16, Vorhang 13 / 11 / 5, Boden 2. Die Laborszene aus S-006 ist grün. Warum
das Gerät anders entscheidet, zeigt die App nicht. Selbsttest: Stabilisator "aktiv: unbekannt" auch nach 1,5 s Warten;
ob das Gerät den Wert nicht meldet oder gar kein Aufnahmeergebnis kam, ist nicht unterscheidbar.

## Ziel
Der Hinweis nach der Nachtaufnahme zeigt die Werte, nach denen der Boden-Modus entschieden wird, und zwei Werte des
ersten Einzelbilds. Der Selbsttest unterscheidet "aktiv", "aus", "vom Gerät nicht gemeldet" und "kein
Aufnahmeergebnis". Das Bild selbst ändert sich nicht.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Lichtlose 8-Bit-Serie (lineares Gauss-Rauschen σ 0,003, bei 0 abgeschnitten, 24 Bilder): Diagnose geprüft, Boden-Modus ja, geschätztes Rauschen innerhalb 15 % der Wahrheit, Signal ≤ Schwelle, Schätzung an mindestens 50 % der Pixel | `NightMergeTest > S-007 Diagnose lichtlos schaetzt das Rauschen nahe der Wahrheit` |
| K2 | Erstes Bild mit genau 25 % Kanalwerten auf 0 und festen Mitteln: Nullen-Anteil 0,25 ± 0,001, Mittel je Kanal ± 0,01 | `NightMergeTest > S-007 Diagnose misst das erste Bild` |
| K3 | Helle Szene (Stufe 60 bis 120, 24 Bilder): geprüft, Boden-Modus nein, Signal > Schwelle | `NightMergeTest > S-007 Diagnose helle Szene ist kein Boden` |
| K4 | Weniger als 8 Bilder: nicht geprüft, Boden-Modus nein; RAW-Weg: keine Diagnose | `NightMergeTest > S-007 Randfall wenige Bilder und RAW` |
| K5 | Die Diagnose der Verarbeitung kommt unverändert im NightReport an (8 Bit) | `NightUseCaseTest > S-007 Diagnose kommt im Bericht an` |
| K6 | Hinweis zeigt eine zweite Zeile, z. B. "Boden-Modus nein: Signal 1,20, Schwelle 0,40, Rauschen 3,10, Mittel 2,00, geschätzt an R 60 / G 55 / B 70 %; Bild 1: 41 % Nullen, Mittel 12,0 / 11,0 / 15,0" (lineare Stufen, 1 = 1/255 von Weiß) | `CameraContentTest > nacht_hinweis_zeigt_die_diagnose` |
| K7 | Ohne Prüfung: "Boden-Modus nicht geprüft" | `CameraContentTest > nacht_hinweis_diagnose_nicht_geprueft` |
| K8 | ViewModel reicht die Diagnose an den Hinweis weiter | `CameraViewModelTest > S-007 Nacht-Hinweis traegt die Diagnose` |
| K9 | Selbsttest: Gerät meldet den Wert nicht, dann "vom Gerät nicht gemeldet (angefordert: ein)"; kein Ergebnis in 1,5 s, dann "kein Aufnahmeergebnis" | `SelfTestUseCaseTest > S-007 Stabilisator nicht gemeldet`, `SelfTestTest > S-007 Stabilisator nicht gemeldet` |

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
| Zeit und Speicher | zusätzlich ein Durchlauf über das erste Bild (1,6 MP, unter 10 ms); keine neue Kopie (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
Langer Hinweis: der Snackbar-Text wird zwei Zeilen länger; bei großer Schrift kann er abgeschnitten werden.
Rückweg: Zeile aus `nightDetail` entfernen.

## Kosten
Ein kurzer Lauf für die Screenshot-Grundlage `selftest_device` (Text "kein Aufnahmeergebnis"), ein Release. Gerätetest
durch Arslan: dieselbe Vorhang-Szene, Hinweis und Selbsttest als Screenshot.

## Ergebnis
(nach der Umsetzung)
