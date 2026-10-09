# S-002: Nachtbelichtung im beleuchteten Raum, Automatik im Hinweis, RAW-Schwarzwert 0

**Stand:** 9. Oktober 2026 · **Status:** freigegeben (Arslan, 21:34: Repository öffentlich, also weiter)
**Anlass:** Gerätetest S24+ am 9. Oktober, 21:29 bis 21:31 (v0.1.82), beleuchtetes Wohnzimmer, Jeans aus der Nähe:
- Hinweis "1/10 s, ISO 3200, 36 Bilder, 0 verworfen, Aufhellung x1,0, Dauer 6,1 s". Bild grau statt blau, helle
  Stellen ohne Struktur, Stoffmuster verschmiert, heller Saum an Kanten. Samsung im selben Licht: scharf und farbig.
- Aufhellung x1,0 heißt: die Einzelbilder waren schon hell genug. Trotzdem volle ISO, weil die Regel "Automatik am
  Anschlag" schon bei 1/20 s Belichtung greift, auch wenn die ISO der Automatik niedrig ist. Vermutung: Einzelbilder
  bis etwa zehnmal heller als die Automatik, helle Stellen laufen voll. Die Werte der Automatik zeigt der Hinweis
  bisher nicht, die Vermutung ist also noch nicht gemessen.
- Selbsttest am selben Abend: RAW "Schwarz: 0/0/0/0", Nullen 0,0 % im hellen Raum (vorher 61,9 % im dunklen).
  Die Prüfung auf Abschneiden hängt also vom Raumlicht ab; gewählt wurde 8 Bit nur, weil die Probenacht 6,7 s dauerte.

## Ziel
Erst messen: der Hinweis zeigt, was die Automatik vor der Serie gemessen hat. Bis dahin eine Schutzgrenze gegen
Überbelichtung, die die gemessenen Nachtfälle vom 8. Oktober nicht verändert. RAW-Wahl unabhängig vom Raumlicht.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Nachtserie höchstens 4-mal so hell wie die Automatik (Belichtung mal ISO), auch "am Anschlag"; Automatik 1/20 s bei ISO 640 ergibt ISO 1280 statt 3200 | `NightPlanTest > Nachttest S24+ beleuchteter Raum ...` (alter Code: 3200, rot) |
| K2 | die Nachtfälle vom 8. Oktober bleiben bei ISO 3200 (Automatik 1/15 s bei ISO 1279: 3,75-fach; 1/25 s bei ISO 3200) | bestehende `NightPlanTest`-Fälle |
| K3 | Hinweis zeigt die Automatik, zum Beispiel "1/10 s, ISO 1280 (Automatik 1/20 s, ISO 640)"; ohne Messung wie bisher | `NightUseCaseTest > Bericht nennt die Messung der Automatik`, `CameraContentTest > nacht_hinweis_zeigt_die_automatik` |
| K4 | RAW mit Schwarzwert 0 an allen vier Positionen gilt als abgeschnitten, auch ohne Nullen im Bild | `NightPathRuleTest > Nachttest S24+ Schwarzwert 0 ...` (alter Code: RAW erlaubt, rot), `SelfTestUseCaseTest > Fehlerfall Schwarzwert 0 ...` |

## Nicht Teil dieser Änderung
- Kürzere Einzelbilder bei mehr Licht (gegen Wackeln in jedem Bild) und örtliche Ausrichtung (Bein bewegt sich):
  erst nach der Messung aus K3.
- Ob "Auto" in einem beleuchteten Raum überhaupt in den Nachtmodus wechselt (Dunkelgrenze 20): erst nach der Messung.

## Betroffene Schichten und Regeln
`:core:pure` (NightPlan, NightPathRule), `:core:control` (NightUseCase, SelfTestUseCase), `:feature:camera` (Hinweis).
Regeln R14, R23, R27.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | keine neuen Abhängigkeiten |
| Nur Zahlen in Ergebnissen | Messwerte als `Long` und `Int`, Text baut die UI (R23) |
| Fehler und Ausweichweg | RAW abgeschnitten führt zu 8 Bit mit Grund (R14) |
| Abbruch, Puffer, Main-Thread, Speichern | nicht betroffen; gespeicherte Wahl unverändert im Format |
| neue Ausnahme | keine |

Rot-Nachweis: K1 und K4 rechnerisch (alter Code ISO 3200 beziehungsweise RAW erlaubt), K3 kompiliert mit dem alten
Code nicht (Felder fehlten). Zweitprüfung: keine Kompilier- oder Testfehler; umgesetzt: Schwarzwert-Regel erst nach
der Kalibrierung (ohne Metadaten meldet der Adapter Schwarz 0), Kommentare, Prüfung im ViewModel-Test.

## Risiken und Rückweg
Die Grenze 4 ist aus dem Fall vom 8. Oktober abgeleitet (3,75-fach nötig), nicht aus dem beleuchteten Raum. Ob 4-fach
dort reicht, zeigt erst der nächste Gerätetest mit den Werten der Automatik. Rückweg: Konstante `MAX_BOOST`.

## Kosten
Ein Release (etwa 20 Minuten); Repository öffentlich, deshalb ohne Minutenverbrauch.

## Ergebnis
(nach dem Lauf eintragen)
