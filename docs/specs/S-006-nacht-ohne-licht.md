# S-006: Nacht fast ohne Licht: schwarz statt Nebel, bis 72 Bilder, Stabilisator und Wackeln richtig gemeldet

**Stand:** 10. Oktober 2026 · **Status:** freigegeben (Arslan, 4:29)
**Anlass:** Nachttest S24+ am 10. Oktober, 4:21 Uhr (v0.1.90), fast lichtloser Raum mit schwach beleuchtetem Vorhang.
Hinweis: "1/10 s, ISO 3200 (Automatik 1/25 s, ISO 3200), 36 Bilder, 0 verworfen, Aufhellung x59,5, Wackeln bis 0 px,
Dauer 6,1 s". Gemessen an denselben Bildstellen:

| Stelle | Samsung (bis 8 s belichtet) | CayResim 0.1.90 |
|---|---|---|
| Vorhang | 70, 54, 13 (warm) | 43, 42, 50 |
| Wand | 15, 8, 7 | 39, 39, 48 |
| Boden | 3, 1, 6 | 39, 39, 49 |
| Median des Bilds | 2,5 | 40 |

Ursache: Jedes 8-Bit-Bild ist bei 0 abgeschnitten. Ohne Licht ist das Mittel vieler Bilder dann der abgeschnittene
Rauschanteil (0,4-mal das Rauschen), und die Aufhellung nach dem Median hob ihn 59-fach zu blaugrauem Nebel. Der
Boden-Modus (Raum bleibt schwarz) griff nicht, weil er nur Pixel auf Stufe 0 oder 1 zaehlte; bei ISO 3200 rauschen sie
bis Stufe 5 (Anteil 0,80 statt 0,85). Selbsttest: "Optischer Stabilisator: ja, aktiv: unbekannt", weil der Wert vor dem
ersten Aufnahmeergebnis gelesen wurde.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Laborszene nach dem Foto (Rauschen der Einzelbilder bis Stufe 5, Vorhang warm, Wand dunkel, 72 Bilder): Boden höchstens 10, Vorhang mindestens 15 über dem Boden, Wand unter dem Vorhang, Blau minus Rot am Boden höchstens 3, Vorhang Rot minus Blau mindestens 10 | `QualityLabTest > Nachttest S24+ Vorhang im fast lichtlosen Raum ...` (0.1.90: Boden 27, Blaustich 6,3, rot) |
| K2 | Boden-Modus auch bei Rauschen über Stufe 1: Signal des mittleren Pixels (abgeschnittenes Rauschen zurückgerechnet) nicht über dem Rauschen der Serie | `NightMergeTest > S-006 Boden-Modus erkennt ...` |
| K3 | alle bisherigen Laborszenen unverändert grün (Küche, Dämmerung, starkes Rauschen, lichtloser Raum mit Tür, Restlicht) | bestehende Tests, lokal |
| K4 | tiefe Dunkelheit (Lichtwert der Automatik ab 80): 72 Bilder, sonst 36 | `NightUseCaseTest > Nachttest S24+ tiefe Dunkelheit nimmt 72 Bilder ...` |
| K5 | Selbsttest wartet bis 1,5 s auf das erste Aufnahmeergebnis für "aktiv" | `SelfTestUseCaseTest > Geraetewerte nennen den Stabilisator` |
| K6 | "Wackeln nicht messbar", wenn bei der Hälfte der Bilder oder mehr keine Verschiebung klar besser passte als keine | `NightMergeTest > S-006 Wackeln ist bei reinem Rauschen ...`, `NightUseCaseTest > Bericht sagt ...`, `CameraContentTest > nacht_hinweis_wackeln_nicht_messbar` |

K1, Grenze 15 statt Samsungs 55: Samsung hat etwa 2,2-mal mehr Licht (bis 8 s, wir 7,2 s aus 72 Bildern zu 1/10 s
und bei Samsung vermutlich längere Einzelbilder). Gemessen im Labor: 36 Bilder Vorhang 23 / Boden 9,5 / Korn 0,18,
72 Bilder 22 / 6,3 / 0,15.

## Weg (lokal gemessen, alle Labortests)
| Verfahren | Ergebnis |
|---|---|
| je Pixel zurückrechnen (Mittel und Streuung) | Boden 6, Vorhang 20, aber Rauschen bei starkem Rauschen 3,5-fach, zwei Labortests rot |
| Rauschen für das ganze Bild schätzen, je Pixel aus dem Mittel zurückrechnen | gleiches Problem: die Umkehrung verstärkt das Rauschen bis 6-fach |
| Entscheidung über das zurückgerechnete Signal des mittleren Pixels, im Boden-Modus nur den Rauschanteil abziehen | gewählt: Boden 6,3, Vorhang 22, Blaustich 3,0, warm 13; alle anderen Szenen unverändert |

Im Boden-Modus darf die Aufhellung doppelt so hoch gehen wie sonst (bis 128), weil der Hintergrund schwarz bleibt und
fast nur das Licht verstärkt wird.

## Nicht Teil dieser Änderung
Längere Einzelbilder (das S24+ erlaubt Drittanbietern 1/10 s); RAW (abgeschnitten); stärkeres Entrauschen im Boden-Modus.

## Betroffene Schichten und Regeln
`:core:pure` (Declip neu, NightMerge, NightTone, NightPlan), `:core:boundary` (NightStats), `:core:control`
(NightUseCase, SelfTestUseCase), `:core:processing` (Kennzahlen), `:feature:camera` (Hinweis). Regeln R17, R19, R23, R27.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, keine neuen Abhängigkeiten |
| Nur Zahlen in Ergebnissen | `shakeMeasurable` als Boolean (R23) |
| Bildpuffer und Speicher | ein Float-Puffer mehr (Quadrate je Kanal, 18,7 MB bei 1,6 MP, nur 8 Bit) (R19, R27) |
| Zeit | 72 Bilder bei etwa 9 Bildern je Sekunde plus 7 Mess- und Einschwingbilder: etwa 9 s, Grenze 10 s; der Hinweis zeigt die Dauer (R27) |
| Abbruch | Strom wird nach der gewählten Zahl beendet (`takeWhile`), Rückstellung der Belichtung unverändert im `finally` |
| neue Ausnahme | keine |

## Risiken und Rückweg
Mehr Korn im Boden-Modus durch die höhere Aufhellung; Dauer nahe 10 s. Rückweg: `FLOOR_GAIN_FACTOR = 1`,
`FRAMES_DEEP = 36`, `FLOOR_SNR = 0`.

## Kosten
Ein Release (Repository öffentlich, keine Minuten).

## Ergebnis
(nach dem Lauf eintragen)
