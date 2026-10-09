# S-001: Prüfbefunde Nacht-Kern und RAW-Weg

**Stand:** 9. Oktober 2026 · **Status:** freigegeben (Arslan, 20:11)
**Anlass:** eigene Prüfung der Änderungen vom 9. Oktober, sieben Befunde. Befund 1 im Python-Modell bestätigt:
bei Leserauschen 40 Kante 0,77 px ohne Entrauschen, 3,62 px mit Entrauschen (Stärke 2,5).

## Ziel
Nachtfotos bei starkem Rauschen so scharf wie die Wahrheit, Dauer der Aufnahme auf dem Gerät sichtbar, keine zu
strenge Bewegungserkennung am Rand, im RAW-Fehlerfall kein langes Warten, vollständige Testberichte.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Szene "Dunkel, starkes Rauschen" (Leserauschen 40): Kante höchstens 1,5 px, Rauschen höchstens 40 % des Einzelbilds | `QualityLabTest > Testlabor Nacht-Kern ...` (Szenenschleife) |
| K2 | Szene "Starkes Rauschen, 1 Pixel Wackeln": Kante höchstens 1,5 px | wie K1 |
| K3 | alle bisherigen Laborgrenzen bleiben grün (Rauschen, Korn, Kante, Geist, Helligkeit, Farbe, Rand, Kachelgrenze) | bestehende Labortests |
| K4 | Kacheln, die ein verschobenes Bild nicht gesehen hat, fließen nicht in den Vergleichswert ein; Kacheln mit weniger als 4 Stichproben werden nicht abgewertet | Codeprüfung. Kein Rot-Nachweis möglich: in realistischen Szenen verschiebt der Fehler den Vergleichswert nur um wenige Prozent. `NightMergeTest > Randfall starkes Wackeln ...` bleibt als Schutz gegen Rückschritt |
| K5 | Hinweis nach der Nachtaufnahme zeigt die Dauer vom Auslösen bis gespeichert, zum Beispiel "4,2 s" | `NightUseCaseTest > Dauer wird gemessen`, `CameraContentTest > nacht_hinweis_zeigt_die_dauer` |
| K6 | RAW-Strom: Öffnen der Kamera insgesamt höchstens 3 s, danach leerer Strom (Rückfall auf 8 Bit) | Codeprüfung (Emulator kann keine belegte Kamera erzeugen), Konstante `RAW_OPEN_BUDGET_MS` |
| K7 | jeder Labortest schreibt eine eigene Berichtsdatei, der Gesamtbericht enthält alle Werte unabhängig von der Reihenfolge | Bericht im Zweig ci-logs-fast |
| K8 | RAW-Plan nennt die fehlende Vignettierung als offen | Projektdokument `claude/foto-app-raw-weg.md` |

## Nicht Teil dieser Änderung
- Vignettierung im RAW-Weg (erst, wenn ein Gerät RAW wählt)
- Bewegungserkennung je Pixel (größerer Umbau, eigene Spec, wenn K1 bis K3 auf dem Gerät nicht reichen)

## Betroffene Schichten und Regeln
`:core:pure` (NightTone, NightMerge), `:core:control` (NightUseCase mit Uhr), `:feature:camera` (Hinweis),
`:core:camera` (RAW-Öffnen). Regeln R2 (Zeit nur über Clock), R14, R17, R23, R27.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Uhr kommt als `Clock` in den UseCase (R2); keine neuen Abhängigkeiten |
| Nur Zahlen in Ergebnissen | Dauer als Millisekunden (`Long`), Text baut die UI (R23) |
| Fehler und Ausweichweg | RAW-Öffnen mit Zeitbudget, danach Rückfall auf 8 Bit (R14) |
| Abbruch und Freigaben | unverändert: Sperre mit Merker, Aufräumen in einem `NonCancellable`-Block |
| Bildpuffer, Main-Thread, Speichern | nicht betroffen |
| Zeit- und Speicherbudget | Spaltentabellen einmal je Bild statt je Zeile; Dauer jetzt sichtbar (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
Milderes Entrauschen lässt in ruhigen dunklen Szenen etwas mehr Korn: begrenzt durch die bestehende Korngrenze
(höchstens 10 % des Einzelbilds). Rückweg: Konstante `DENOISE_STRENGTH`.

## Kosten
Ein kurzer Lauf, ein Release (zusammen etwa 35 Minuten); Gerätetest durch Arslan.

## Ergebnis
(nach dem Lauf eintragen)
