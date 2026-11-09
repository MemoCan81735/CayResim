# S-019: Mikrofon-Test fragt drei und vier Kanäle an

**Stand:** 9.11.2026 · **Status:** umgesetzt in v0.1.143, Gerätetest erledigt (9.11., 22:03 Uhr); freigegeben von Arslan am 9.11.2026, 20:39 Uhr ("Ja bitte", mit ins Release von S-018)
**Anlass:** Prüfung des Stands der akustischen Kamera (`docs/plan-f11-akustische-kamera.md`, 9.11.). Das S24+ hat drei
Mikrofone (unten, oben, verstecktes neben der Ultraweitwinkel-Kamera). Der Mikrofon-Test (S-008) fragt nur ein und zwei
Kanäle an und bekam am 10.10. die Mikrofone 16 (bottom) und 17 (back). Mit drei Mikrofonen, die nicht auf einer Linie
liegen, wäre mehr als links/rechts messbar.

## Ziel
Der Mikrofon-Test nimmt zusätzlich mit drei und vier angefragten Kanälen auf (`MIC` und `CAMCORDER`) und zeigt, wie
viele Kanäle ankommen, wie viele davon wirklich verschiedene Mikrofone sind und welche Mikrofone Android als aktiv
meldet. Reine Messung; an Ortung und Schallbild ändert sich nichts.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Drei unabhängige Rauschkanäle: 3 verschiedene, Korrelation der Änderungen je Paar unter 0,1. Dritter Kanal Kopie des ersten: 2. Dritter Kanal stumm: 2. Vier Kanäle aus zwei Paaren gleicher: 2. Mono: 1. Zwei Kanäle: 2 genau dann, wenn sie bisher als verschieden galten | `AudioMathTest > S-019 zaehlt verschiedene Mikrofone` |
| K2 | Plan endet mit `MIC` 3, `MIC` 4, `CAMCORDER` 3, `CAMCORDER` 4 Kanälen zu je 1 s (die bisherigen Aufnahmen behalten Reihenfolge und Dateinamen); Datei `…-MIC-3kanal-v1.wav` mit 3 Kanälen; die Klatsch-Probe wählt nie eine Quelle mit mehr als zwei Kanälen; Lauf weiter höchstens 30 s | `MicTestUseCaseTest > S-019 drei und vier Kanaele am Ende des Plans` |
| K3 | Ergebnis zeigt je Zeile "MIC, 3 Kanäle angefragt" und "Verschiedene Mikrofone: 2; Korrelation der Änderungen je Paar: 1-2: 0,02; 1-3: 1,00; 2-3: 0,02" | `MicTestTest > S-019 Zeile fuer drei Kanaele` |
| K4 | Adapter (Emulator): Anfrage mit 3 Kanälen endet als Aufnahme mit 1 bis 3 Kanälen oder als Fehlerwert, nie als Absturz; danach keine offene Aufnahme | `AudioRecordMicrophoneAdapterTest > s019DreiKanaele` |
| K5 | Gerätetest S24+: Mikrofon-Test laufen lassen, Screenshot der vier neuen Zeilen | Arslan |

## Nicht Teil dieser Änderung
- Keine Ortung mit drei Mikrofonen, keine Änderung an Schallbild, Klatsch-Probe oder Schwenk-Messung.
- Der Tonstrom (S-018) bleibt bei zwei Kanälen.

## Betroffene Schichten und Regeln
- `:core:pure`: `AudioMath.RecordingStats.distinctCount`, `pairDiffCorrelation`.
- `:core:audio`: Anfragen mit 3 und 4 Kanälen über die Kanal-Index-Maske (R28 unverändert).
- `:core:control`: `MicTestUseCase.plan`, Dateiname, Wahl der Klatsch-Quelle.
- `:feature:settings`: zwei Texte, zwei Felder in `SourceRowUi`.
- Kein neues Speichern (vier weitere WAV-Dateien im Ordner des Tests, wie bisher), keine neue Berechtigung.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Zählung in pure, Maske nur im Adapter (R1, R28) |
| Nur Zahlen, Schlüssel, Enums | zwei Zahlenfelder (R23) |
| Fehler und Ausweichweg | Gerät lehnt die Maske ab: Fehlerwert INIT_FAILED, der Lauf geht weiter (R14, R24) |
| Abbruch und Freigabe | unverändert ein Freigabeblock |
| Zeit und Speicher | 4 Aufnahmen zu 1 s, zusammen etwa 5 s und 1,3 MB; Lauf höchstens 30 s (K2, R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
Android kann fehlende Kanäle auffüllen (Kopie oder Stille); deshalb zählt K1 nur wirklich verschiedene. Oberes und
hinteres Mikrofon liegen nah beieinander und könnten als "gleich" gelten; die Korrelation je Paar steht deshalb als
Zahl dabei. Rückweg: vier Einträge aus dem Plan nehmen.

## Kosten
Im Lauf und Release von S-018 enthalten; Vergleichsbilder des Mikrofon-Tests ändern sich (vier Zeilen mehr).

## Ergebnis
Lokal: K1 und K2 grün (`AudioMathTest`, `MicTestUseCaseTest`). Vergleichsbilder des Mikrofon-Tests übernommen (vier Zeilen mehr).

Lauf auf main 42689855066 (9.11.): alle Jobs grün, auch der Emulator; Release v0.1.143. Emulator: drei Kanäle angefragt, 3 geliefert (K4); was das S24+ liefert, zeigt K5. Gerätetest offen.

Gerätetest S24+ v0.1.143 (9.11., 22:03 Uhr, Ordner `Mikrotest-20261109-220219`, K5):

| Anfrage | geliefert | Pegel (dBFS) | verschiedene Mikrofone | aktive Mikrofone |
|---|---|---|---|---|
| `MIC`, 3 Kanäle | 3 | −48,6 / −45,4 / −inf | 2 | 16, 17 |
| `MIC`, 4 Kanäle | 4 | −53,5 / −49,1 / −inf / −inf | 2 | 16, 17 |
| `CAMCORDER`, 3 Kanäle | 3 | −51,4 / −49,4 / −inf | 2 | nicht gemeldet |
| `CAMCORDER`, 4 Kanäle | 4 | −54,4 / −51,2 / −inf / −inf | 2 | nicht gemeldet |

Ergebnis: **Das S24+ gibt einer fremden App nur zwei Mikrofone.** Android öffnet die Aufnahme mit drei und vier
Kanälen, füllt Kanal 3 und 4 aber mit Stille. Das dritte Mikrofon (oben) ist über diesen Weg nicht erreichbar. Android
meldet daneben einen Eintrag Nr. 22 (Lage PERIPHERAL, ohne Position), kein eingebautes Mikrofon. Lauf 21,3 s (unter 30 s).
Nebenbefund: Die Korrelation der Änderungen zwischen den beiden Mikrofonen schwankt je Aufnahme zwischen 0,00 und 0,43;
zwei Zeilen ("Richtung zum Bildschirm", "zur Rückseite") galten dadurch in diesem Lauf als "nicht verschieden" (Grenze
0,3), am 10.10. und im Lauf von 22:00 Uhr lagen sie bei 0,01 bis 0,04. Vermutlich Geräusche im Raum während der
Aufnahme; die Klatsch-Probe war nicht betroffen (rechts +0,45 ms, wie am 10.10. +0,40 bis +0,44).
Folge für F11: Höhe und vorne/hinten gibt es nur über Schwenken (F12), nicht über ein drittes Mikrofon.

