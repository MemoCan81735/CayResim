# S-012: Schwenk-Messung meldet "Signal zu schwach" (F12, Schritt 1 der empfohlenen Reihenfolge)

**Stand:** 10.10.2026 · **Status:** freigegeben von Arslan (10.10., 20:16 Uhr: "3 ja", eigene Spec für die Signalprüfung)
**Anlass:** Erster echter Schwenk (S24+, 10.10., 16:05 Uhr, `Schwenk-20261010-160510`). Die App meldete
"unplausibel", obwohl die Ursache ein zu schwaches Signal war: Pegel −50 bis −60 dBFS, GCC-PHAT-Spitze im Median 0,074,
nur 23 % der Fenster mit Spitze ab 0,1, Laufzeit fast immer um 0 ms unabhängig von der Drehung. Die 100 Fenster über
0,1 waren Zufallsspitzen; die Ausgleichsrechnung lieferte daraus einen Abstand von 2,3 cm (M1) bzw. 0,1 cm (M3) und
damit "unplausibel". Der Hinweis hat den Nutzer auf die falsche Spur geführt (Plan F12, erster echter Lauf).

## Ziel
Ist das Signal zu schwach, nennt die Schwenk-Messung genau das und sagt, was zu tun ist (lauter, näher, Musik oder
Sprache statt Rauschen). Das Ergebnis zeigt die Signalstärke als Zahl, damit der nächste Gerätetest sie mitliefert.

## Grenzwert, gemessen statt geschätzt
Median der GCC-PHAT-Spitze über alle Fenster nach der Klopfphase. Modell `tools/schwenk-modell` (Raum mit Hall, Achse
geneigt, Schwenk weit, je 2 Läufe) gegen den echten Lauf:

| Fall | Median Spitze | Anteil Fenster ≥ 0,1 | Richtung gefunden |
|---|---|---|---|
| Modell ideal | 0,98 | 1,00 | ja |
| Modell realistisch | 0,52 | 1,00 | ja |
| Modell reiner Ton 2 kHz | 0,27 | 1,00 | ja |
| Modell Quelle 3 dB unter dem Raumgeräusch | 0,14 | 0,98 | ja |
| Modell Quelle 6 dB darunter | 0,088 | 0,26 | im Modell ja |
| Modell Quelle 10 dB darunter | 0,056 | 0,00 | nein |
| **S24+ 10.10., 16:05 Uhr** | **0,074** | **0,23** | **nein** (Laufzeit um 0, unabhängig von der Drehung) |

Grenze **0,10**: Der echte Fehlschlag (0,074) liegt klar darunter, alle Modellfälle mit sicherer Richtung (ab 0,14)
klar darüber. Der Modellfall mit 6 dB (0,088) wird bewusst abgewiesen: Am echten Gerät war ein Median von 0,074 schon
unbrauchbar, das Modell kennt das gemeinsame Rauschen beider Mikrofone im Gerät nicht. Die Grenze ist gleich der
bestehenden Mindesthöhe je Fenster (`MIN_PEAK` 0,1): "Das typische Fenster muss eine klare Spitze haben."

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Künstlicher Schwenk mit Quelle unter dem Rauschen (Median der Spitze unter 0,10): Grund `WEAK_SIGNAL`, nicht `IMPLAUSIBLE` oder `TOO_FEW_MEASUREMENTS`; Median im Bericht unter 0,10 | `SweepMathTest > S-012 schwaches Signal wird erkannt` |
| K2 | Derselbe Schwenk mit klarer Quelle: Richtung wie bisher (höchstens 5°), Median im Bericht über 0,5; Stille: `WEAK_SIGNAL` (vorher `TOO_FEW_MEASUREMENTS`); kurze Aufnahme mit klarem Signal: weiter `TOO_FEW_MEASUREMENTS` | `SweepMathTest > S-010 Gruende ohne Richtung sind getrennt` (angepasst) |
| K3 | `meta.json` der WAV-Datei enthält `"peakMedian"` (Formatversion bleibt 1, neues Feld ist rein zusätzlich) | `SweepUseCaseTest > S-012 Signalstaerke in meta` |
| K4 | Oberfläche: Grund "Signal zu schwach" mit Anleitung, Zeile "Signalstärke (Median der Spitze)" mit Zahl im Ergebnis; ein Screenshot | `SweepTest > schwenk_signal_zu_schwach` |
| K5 | Gerätetest S24+: derselbe Aufbau wie am 10.10. (leise) zeigt "Signal zu schwach" mit Median unter 0,10; mit Musik in etwa 1 m ein Median über 0,10 | Gerätetest |

Tests zuerst rot: K1 bis K3 lokal (`run-pure-tests.sh`, `run-core-tests.sh`), weil Grund und Feld fehlen; K4 in CI,
weil Text und Zeile fehlen.

## Nicht Teil dieser Änderung
- Keine Änderung an Ausgleichsrechnung, Fensterlänge, `MIN_PEAK`, Abdeckung, Klopfern oder Aufnahme.
- Kein Eichschwenk, keine Richtungskarte (Schritte 2 und 3 im Plan F12, eigene Specs).
- Kein neues Speichern; `meta.json` bekommt nur ein Feld dazu.

## Betroffene Schichten und Regeln
- `:core:pure`: `SweepMath` sammelt die Spitzenhöhe aller Fenster, `SweepReport.peakMedian`, Grund `WEAK_SIGNAL`
  (geprüft nach `NO_STEREO`, vor allen anderen).
- `:core:control`: `SweepUseCase.meta` schreibt `peakMedian`.
- `:feature:settings`: Spiegel-Enum, Text, Zeile im Ergebnis. Screenshot `sweep_result.png` ändert sich (neue Zeile).

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, Text in der UI (R1, R23) |
| Nur Zahlen, Schlüssel, Enums | Median als Zahl, Grund als Enum (R23) |
| Fehler und Ausweichweg | eigener Grund statt falscher Meldung (R14, R24) |
| Abbruch und Freigabe | unverändert |
| Bildpuffer | keiner; eine Liste mit etwa 450 Zahlen je Messung |
| Gespeicherte Daten | `meta.json` Format 1, ein zusätzliches Feld; Leser ignorieren Unbekanntes (R26) |
| Zeit und Speicher | Median über etwa 450 Werte, unter 1 ms (R27); Prüfung im bestehenden Zeittest |
| neue Ausnahme | keine |

## Risiken
- Grenze zu streng für sehr leise, aber nutzbare Quellen (Modell 6 dB): Die Zahl im Ergebnis zeigt, wie knapp es war;
  bei Bedarf senken mit neuem Befund.
- Samsungs Rauschunterdrückung kann gleichmäßiges Rauschen dämpfen: Die Anleitung empfiehlt Musik oder Sprache.

## Kosten
Ein kurzer Lauf (ein neuer und ein geänderter Screenshot), Emulator nicht nötig (keine Kamera, kein Speicher, kein
Sensor). Release nur auf Wunsch, gebündelt mit dem Nachtrag zu S-011.

## Umsetzung (10.10.2026)
- `SweepMath.analyze` sammelt die Spitze jedes Fensters (Stille als 0), Median im Bericht (`peakMedian`), Grund
  `WEAK_SIGNAL` vor allen anderen Gründen außer `NO_STEREO`. `meta.json` mit `"peakMedian"`.
- Künstlicher Schwenk mit Quelle etwa 10 dB unter dem Rauschen je Kanal: Median 0,073 (wie das S24+ mit 0,074),
  55 Fenster über 0,1. Mit dem alten Code gingen diese 55 Zufallsfenster in die Rechnung (mehr als 50), genau wie beim
  echten Lauf; jetzt `WEAK_SIGNAL`. Klare Quelle: Median über 0,5, Richtung unverändert.
- Stille meldet jetzt "Signal zu schwach" statt "zu wenige Messfenster"; "zu wenige Messfenster" bleibt für kurze
  Aufnahmen mit klarem Signal (Test angepasst, ebenso der ViewModel-Test mit stillem Mikrofon).
- Lokal: `:core:pure` 166 Tests, `SweepUseCaseTest` und `MicTestUseCaseTest` 17 Tests, Architekturregeln 0 Verstöße,
  Fallen-Skript ohne Funde, neue Strukturprüfung ohne Funde.
- Geänderte Screenshot-Grundlagen: `sweep_result.png` (neue Zeile Signalstärke), neu `sweep_weak.png`.

## Ergebnis
Noch offen.
