# S-014: Schwenk-Messung mit bebilderter Anleitung und festem Ablauf (F12)

**Stand:** 10.10.2026 · **Status:** freigegeben von Arslan (10.10., 22:23 Uhr "Ok" zum Einplanen, 22:44 Uhr "Ja" zur
Reihenfolge S-014 vor S-013)
**Anlass:** Arslan (10.10., 22:21 Uhr): "Wäre eine bebilderte Anleitung nicht besser, statt Text wie das Handy und wann
gedreht wird". Dazu aus der Auswertung: Zwei echte Läufe mit freiem Schwenken sind schwer vergleichbar; S-013 braucht
mehrere Läufe mit gleichem Ablauf (Plan F12, Modellprüfung). Abdeckung zeigt die App bisher erst nach dem Lauf
(echte Läufe: 0,22 und 0,23).

## Ziel
Vor dem Start zeigt die Schwenk-Messung jeden Schritt als Bild mit Zeitangabe. Während der Messung steht groß die
aktuelle Bewegung als Bild mit Text, Restzeit und nächster Bewegung, dazu ein Balken für die Abdeckung der Drehungen.
Jeder Lauf folgt so demselben Ablauf.

## Ablauf (fest, 25 s)
| Zeit | Bewegung | Bild |
|---|---|---|
| vorher | Handy quer, Kamera auf die Quelle, Start tippen | Handy quer, Pfeil zur Quelle |
| 0 bis 3 s | zweimal auf die Rückseite tippen | Handy mit Tipp-Kreisen |
| 3 bis 9 s | links und rechts drehen (Gieren) | Handy, Bogenpfeil unten |
| 9 bis 15 s | auf hochkant kippen und zurück (Rollen) | Handy quer und hochkant, Pfeil |
| 15 bis 21 s | nach oben und unten (Nicken), schräg halten | Handy, Bogenpfeil seitlich |
| 21 bis 25 s | langsam kreisen, alles mischen | Handy im Kreispfeil |

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Ablauf in `:core:pure`: zu jeder Sekunde die richtige Bewegung, Restzeit (aufgerundet) und nächste Bewegung; Grenzen 0, 3, 9, 15, 21 s; ab 25 s keine | `SweepGuideTest > S-014 Ablauf` |
| K2 | Abdeckung laufend: nach jedem Hinzufügen gleich `SweepMath.coverage` aller bisherigen Achsen (Abweichung unter 1e-9), leer 0 | `SweepGuideTest > S-014 Abdeckung laufend wie am Ende` |
| K3 | `SweepUseCase` meldet den Fortschritt mindestens alle 250 ms in der Reihenfolge Tippen, Drehen, Kippen, Nicken, Kreisen, danach Auswertung und Ergebnis; die Abdeckung im Fortschritt ist in der Klopfphase 0, steigt während des künstlichen Schwenks und liegt am Ende über 0,03 (der künstliche Schwenk ohne Rollen erreicht 0,048, die echten Läufe 0,22 und 0,23); `meta.json` enthält `"guide": "v1"` | `SweepUseCaseTest > S-014 Ablauf mit Abdeckung` |
| K4 | Oberfläche: Anleitung mit sechs Bildern (Screenshot), Anzeige während der Messung mit Bild, Restzeit, nächster Bewegung und Abdeckung (Screenshot), auch quer gehalten ganz sichtbar ohne Scrollen (Screenshot); ViewModel bildet den Fortschritt ab | `SweepTest > schwenk_anleitung`, `SweepTest > schwenk_ansage`, `SweepTest > schwenk_ansage_quer`, `SweepTest > S-014 Fortschritt wird abgebildet` |
| K5 | Gerätetest S24+: vier Läufe nach Anleitung (Quelle vorne und 90° rechts, je nah etwa 40 cm und fern etwa 1,5 m); Abdeckung am Ende je mindestens 0,15 | Gerätetest |

Tests zuerst rot: K1 bis K3 lokal, weil Ablauf, laufende Abdeckung und Fortschritt fehlen; K4 in CI, weil Bilder und
Fortschritt in der Oberfläche fehlen (`schwenk_anleitung` und `schwenk_ansage` prüfen neue Texte).

## Nicht Teil dieser Änderung
- Keine Änderung an Rechnung, Signalprüfung, Achse (Vorzeichen bleibt bis zum Lauf mit Quelle 90° rechts), Dauer,
  Aufnahme oder Speichern (außer dem Feld `"guide"` in `meta.json`, Formatversion bleibt 1).
- Keine Animation (feste Bilder, damit Screenshots gleich bleiben), keine Töne oder Vibration.
- Keine Bilddateien: Die Bilder zeichnet die App selbst (Compose Canvas), keine fremden Grafiken.

## Betroffene Schichten und Regeln
- `:core:pure`: `SweepGuide` (Ablauf), `SweepMath.AxisCoverage` (laufende Abdeckung, gleiche Rechnung wie `coverage`).
- `:core:control`: `SweepUseCase` meldet `SweepEvent.Progress(move, secondsLeft, next, coverage)` statt `Tap` und `Sweep`;
  die Abdeckung rechnet der Sammler des Sensors selbst (kein geteilter Zustand zwischen zwei Coroutinen außer einer
  `AtomicReference` mit dem letzten Wert).
- `:feature:settings`: Bilder, Anleitung, Anzeige während der Messung, Texte. Geänderte Screenshots:
  `sweep_guide.png`, `sweep_prompt.png`, voraussichtlich `sweep_result.png` und `sweep_weak.png` (Anleitung oben).

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Ablauf und Abdeckung in `:core:pure`, Ereignisse in `:core:control`, Bilder in der UI (R1, R11) |
| Nur Zahlen, Schlüssel, Enums | `Progress` trägt Enum, Sekunden, Zahl; Texte baut die UI (R23) |
| Fehler und Ausweichweg | unverändert |
| Abbruch und Freigabe | Fortschritt-Takt läuft im selben `coroutineScope` wie die Aufnahme und endet mit ihr (R17) |
| Bildpuffer | keine |
| Main-Thread | Abdeckung im Sammler auf dem ComputeDispatcher, je Lagewert O(1) (R18) |
| Gespeicherte Daten | `meta.json` Format 1, ein Feld mehr (R26) |
| Zeit und Speicher | laufende Abdeckung: feste Summen statt Liste (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
- Fester Ablauf passt nicht zu jeder Hand: Restzeit und nächste Bewegung kündigen den Wechsel an; Zeiten in einer
  Tabelle in `SweepGuide`, leicht änderbar.
- Rückweg: alte Textanzeige; die Rechnung ist unberührt.

## Kosten
Etwa 2 kurze Läufe (Screenshots ansehen und übernehmen), Emulator nicht nötig. Release nach Wunsch. Gerätetest: vier
Läufe, je Screenshot und WAV.

## Umsetzung (10.10.2026)
- `SweepGuide` legt Ablauf, Klopfphase und Dauer fest; `SweepUseCase` übernimmt die Konstanten.
- Fortschritt alle 250 ms aus einem Takt im selben `coroutineScope` wie die Aufnahme; die Abdeckung rechnet der
  Sammler des Sensors laufend (`SweepMath.AxisCoverage`, Summen), der Takt liest den letzten Wert.
- Der Balken zählt alle Drehlagen ab Ende der Klopfphase, die Auswertung danach nur Fenster mit klarer Spitze.
  Deshalb stehen beide Werte in `meta.json`: `"coverage"` (Auswertung) und `"coverageLive"` (Balken).
- Während der Messung zeigt der Bildschirm nur die Anzeige des Schritts (Bild links, Text rechts), damit sie auch quer
  ganz sichtbar ist.
- Lokal: `:core:pure` 168 Tests, `SweepUseCaseTest` 7 Tests, Architekturregeln 0 Verstöße, Struktur und Fallen ohne
  Funde. Screenshots angesehen und übernommen.

### Zweitprüfung (unabhängiger Agent, nur gegen Spec und Regeln): kein Fehler
| Nr. | Befund | Schwere | Erledigt |
|---|---|---|---|
| W1 | Klopfphase und Dauer doppelt festgelegt (Guide 3,0, UseCase 3) | Warnung | nur noch in `SweepGuide`, UseCase übernimmt |
| W2 | Balken zählt alle Drehlagen, Auswertung nur Fenster mit Spitze; K5 nicht bewertbar | Warnung | `"coverageLive"` in `meta.json`, Test vergleicht mit dem letzten Fortschritt |
| W3 | Anzeige während der Messung quer vermutlich unter dem sichtbaren Bereich | Warnung | nur Anzeige während der Messung, Bild neben Text, Test und Screenshot quer |
| H4 | Spec nannte `@Volatile`, umgesetzt ist `AtomicReference` | Hinweis | Spec korrigiert |
| H5 | Takt nur über die Anzahl geprüft | Hinweis | größter Abstand und jede Meldung gegen den Ablauf geprüft |
| H6, H7 | ganze Sekunden und eng gebündelte Achsen nicht geprüft | Hinweis | ergänzt |
| H8, H9 | Zuordnung über Reihenfolge der Enums und `valueOf` | Hinweis | erschöpfendes `when`; Start über denselben Fortschritt |
| H10 | `return@Column` beim Wechsel zur Auswertung | Hinweis | `if/else` |
| H13 | Kommentar "drei Viertel" | Hinweis | "etwa zwei Drittel" |
| H14 | Fake rundet Pausen je Wert ab | Hinweis | Zielzeit ab dem ersten Wert |
| H4 Rest | Anzeige läuft 0,1 bis 0,3 s vor der Tonzeit (Takt beginnt vor AudioRecord) | Hinweis | bewusst gelassen, für die Anleitung unkritisch |

## Ergebnis
Release v0.1.133 (Lauf 38090609651, 11.10., 00:27 Uhr): alle Jobs grün, Emulator grün. Gerätetest (K5) offen.
