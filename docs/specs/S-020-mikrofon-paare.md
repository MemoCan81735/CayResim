# S-020: Mikrofon-Test mit weiteren Tonquellen und zwei Aufnahmen gleichzeitig

**Stand:** 9.11.2026 · **Status:** freigegeben von Arslan am 9.11.2026, 22:09 Uhr ("Ja"), samt der Ausnahme von "eine
Aufnahme zur Zeit" für diese Messung und einem Release
**Anlass:** S-019 (9.11., 22:03 Uhr): Über drei und vier Kanäle kommt auf dem S24+ nur Stille; Android meldet nur die
Mikrofone 16 (bottom) und 17 (back), das obere fehlt. Ungeprüft sind (Plan F11, "Wege zum dritten Mikrofon"): andere
Tonquellen (Telefonie nutzt das obere Mikrofon) und ob `CAMCORDER` ein anderes Paar nutzt als `MIC` (Android meldet
dort keine aktiven Mikrofone).

## Ziel
Der Mikrofon-Test nimmt zusätzlich mit `VOICE_COMMUNICATION` und `VOICE_PERFORMANCE` auf und macht zwei Proben mit je
zwei Aufnahmen gleichzeitig (`MIC` mit `CAMCORDER`, `MIC` mit `VOICE_COMMUNICATION`). Je Probe zeigt er, wie viele
verschiedene Mikrofone in den zusammen bis zu vier Kanälen stecken. Reine Messung.

## Messprinzip
Im stillen Raum besteht der hohe Tonbereich fast nur aus dem Eigenrauschen jedes Mikrofons; das ist von Mikrofon zu
Mikrofon unabhängig. Kommen zwei Kanäle vom selben Mikrofon, gleichen sie sich dort (Gleichklang nahe 1), auch wenn
die Aufnahmen zeitlich versetzt und verschieden gefiltert sind; der Versatz wird vorher über die Kreuzkorrelation
ausgeglichen. Zwei verschiedene Mikrofone bleiben nahe 0. Als Gegenprobe dient das Paar aus derselben Aufnahme
(Mikrofone 16 und 17): Ist dessen Gleichklang hoch, war es zu laut, und die Probe gilt nicht.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Zwei Aufnahmen aus unabhängigem Rauschen; zweite Aufnahme: Kanal 1 ist Kanal 1 der ersten, um 7,3 ms versetzt, anders gefiltert und halb so laut, Kanal 2 ein neues Rauschen: 3 verschiedene Mikrofone, Gleichklang des gleichen Paars mindestens 0,8, aller anderen höchstens 0,2, Versatz innerhalb 0,05 ms | `AudioMathTest > S-020 zwei Aufnahmen zusammen zeigen ein drittes Mikrofon` |
| K2 | Zweite Aufnahme enthält dieselben zwei Mikrofone (auch vertauscht): 2. Zweite Aufnahme doppeltes Mono eines der beiden: 2; eines neuen: 3 | `AudioMathTest > S-020 gleiche Mikrofone in beiden Aufnahmen zaehlen einmal` |
| K3 | Fehlerfall lauter Raum (gemeinsamer Ton in allen Kanälen): Probe ungültig. Randfälle: stumme Kanäle zählen nicht, zu kurze Aufnahme ungültig, Rechenzeit einer Probe höchstens 1 s (JVM) | `AudioMathTest > S-020 Probe gilt nur im stillen Raum` |
| K4 | Plan endet mit `VOICE_COMMUNICATION` und `VOICE_PERFORMANCE` (je 1 s, 2 Kanäle); danach die zwei Proben mit je zwei Aufnahmen zu 1 s; vier weitere WAV-Dateien; Ergebnis im Bericht; scheitert die zweite Aufnahme, steht der Fehler als Wert da; Lauf höchstens 30 s | `MicTestUseCaseTest > S-020 neue Quellen und zwei Aufnahmen gleichzeitig` |
| K5 | Ergebnis zeigt je Probe eine Zeile "Normal (MIC) mit Video (CAMCORDER): verschiedene Mikrofone zusammen 3 …" mit Gleichklang je Paar, oder "zu laut" oder den Fehler | `MicTestTest > S-020 Zeilen fuer zwei Aufnahmen gleichzeitig` |
| K6 | Adapter (Emulator): zwei Aufnahmen gleichzeitig enden je als Aufnahme oder Fehlerwert, nie als Absturz; danach keine offene Aufnahme | `AudioRecordMicrophoneAdapterTest > s020ZweiAufnahmenGleichzeitig` |
| K7 | Gerätetest S24+: Mikrofon-Test in einem stillen Raum (nach dem Start nicht bewegen, nicht sprechen, bis die Klatsch-Probe beginnt); Screenshot des Abschnitts "Zwei Aufnahmen gleichzeitig" und der Zeilen der zwei neuen Tonquellen | Arslan |

## Nicht Teil dieser Änderung
- Keine Ortung mit einem dritten Mikrofon; Schallbild, Klatsch-Probe und Schwenk-Messung bleiben unverändert.
- Der Tonstrom bleibt bei einer Aufnahme.

## Betroffene Schichten und Regeln
- `:core:pure`: `AudioMath.pairProbe` (Versatz, Gleichklang im hohen Tonbereich, Gruppen gleicher Mikrofone).
- `:core:boundary`: `AudioSourceKey` um zwei Werte erweitert; `MicrophoneBoundary.recordPair`.
- `:core:audio`: zwei `AudioRecord` gleichzeitig unter derselben Sperre; jede gibt sich in ihrem einen
  `NonCancellable`-Block frei (R28). **Ausnahme von "eine Aufnahme zur Zeit"**, nur in `recordPair`, freigegeben.
- `:core:control`: Plan, Proben, Bericht. `:feature:settings`: Zeilen und Texte.
- Kein neues Speichern außer vier weiteren WAV-Dateien im Ordner des Tests; keine neue Berechtigung.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in pure, `AudioRecord` nur im Adapter (R1, R28) |
| Nur Zahlen, Schlüssel, Enums | Bericht und UiState mit Zahlen und Enums (R23) |
| Fehler und Ausweichweg | lehnt Android die zweite Aufnahme ab: Fehlerwert, der Lauf geht weiter (R14, R24) |
| Abbruch und Freigabe | beide Aufnahmen laufen als Kinder eines Scopes; Abbruch beendet beide, jede gibt sich frei (R17, R28) |
| Zeit und Speicher | Probe höchstens 1 s Rechnen (K3), Lauf höchstens 30 s (K4); je Probe etwa 4 MB Rechenpuffer (R27) |
| neue Ausnahme | ja, siehe oben, freigegeben |

## Risiken und Rückweg
- Die Probe gilt nur im stillen Raum; sonst meldet sie "zu laut".
- Samsungs Bearbeitung des Telefonie-Tons (Rauschunterdrückung) kann den Gleichklang auch beim selben Mikrofon senken;
  dann erscheint ein Mikrofon fälschlich als neu. Der Pegel und die WAV-Dateien bleiben zur Kontrolle.
- Android kann die zweite Aufnahme ablehnen oder stumm schalten; das ist dann das Messergebnis.
- Rückweg: Einträge aus dem Plan nehmen; `recordPair` bleibt ungenutzt.

## Kosten
Ein Seiten-Lauf, ein Lauf für die Vergleichsbilder des Mikrofon-Tests, ein Release. Gerätetest durch Arslan (K7).

## Ergebnis
(nach der Umsetzung)
