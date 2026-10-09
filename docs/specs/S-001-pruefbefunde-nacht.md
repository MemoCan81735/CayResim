# S-001: Prüfbefunde Nacht-Kern und RAW-Weg

**Stand:** 9. Oktober 2026 · **Status:** umgesetzt in v0.1.82, Gerätetest offen
**Anlass:** eigene Prüfung der Änderungen vom 9. Oktober, sieben Befunde. Befund 1 im Python-Modell bestätigt:
bei Leserauschen 40 Kante 0,77 px ohne Entrauschen, 3,62 px mit Entrauschen (Stärke 2,5).

## Ziel
Nachtfotos bei starkem Rauschen so scharf wie die Wahrheit, Dauer der Aufnahme auf dem Gerät sichtbar, keine zu
strenge Bewegungserkennung am Rand, im RAW-Fehlerfall kein langes Warten, vollständige Testberichte.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Szene "Dunkel, starkes Rauschen" (Leserauschen 40): angepasste Kante höchstens 1,3 px (ideale Stufe 0,2; geändert im Lauf, siehe Ergebnis), Rauschen höchstens 40 %, Korn höchstens 10 % des Einzelbilds | `QualityLabTest > Testlabor Nacht-Kern ...` (Szenenschleife) |
| K2 | Szene "Starkes Rauschen, 1 Pixel Wackeln": angepasste Kante höchstens 1,3 px | wie K1 |
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
Erster Lauf (a0e2a73) rot, zu Recht und aus einem zweiten Grund: Kante 2,0 px im 8-Bit-Weg. Das Python-Modell hatte
0,92 gezeigt, aber nur für ein einziges Rauschmuster. Über 12 Muster schwankte die 10-90-Messung zwischen 1,2 und
3,7 px, und schon für ein ungefiltertes, also scharfes Mittel bis 3 px: Bei starkem Rauschen springt sie auf einzelne
Ausreißer im Profil. Neue Messung `ImageQuality.edgeWidthFit` (weiche Stufe angepasst), selbst geprüft in
`ImageQualityTest` (scharf bleibt scharf, Weichzeichnen wird erkannt). Python, 8 Muster: ohne Entrauschen 0,33,
Entrauschen 2,5 (vorher) 2,44, Entrauschen 1,5 (neu) 1,02 bis 1,10. Befund 1 war also echt, die Messung aber zu
unruhig, um ihn sicher zu erkennen.

| Kriterium | Wert im Lauf b5eaa0f | Grenze |
|---|---|---|
| K1 angepasste Kante / Rauschen | 1,10 px / 0,061 (Einzelbild 0,983) | 1,3 px / 0,098 |
| K2 angepasste Kante | 1,10 px (Mittel ohne Ausrichtung 2,20) | 1,3 px |
| RAW, 8 Bit bei Leserauschen 40 | 0,88 / 1,10 px | 1,3 px |
| K3 übrige Szenen | grün, Kante 0,8 px, Rauschen unverändert | wie bisher |
| K5, K6 | Tests grün (Dauer), Codeprüfung (3 s) | |

Zweitprüfung (unabhängiger Agent): sieben Befunde, umgesetzt: Kamera-Öffnen ohne zweiten Zeitblock und mit Schließen
beim Abbruch, Bericht auch bei roter Farbszene, Teil für Restlicht, alte Teile gelöscht, Uhr im Test läuft mit den
Bildern (Start beim Auslösen, RAW-Versuch zählt mit), Dauer nie negativ. Offen: Dauer nutzt die Systemuhr
(`System.currentTimeMillis`), eine monotone Uhr wäre genauer.
