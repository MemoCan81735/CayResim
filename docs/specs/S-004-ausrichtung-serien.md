# S-004: Ausrichtung für Langzeit, Menschen wegrechnen und Fokus-Stacking, Warnung im Pro-Modus

**Stand:** 9. Oktober 2026 · **Status:** umgesetzt in v0.1.90, Gerätetest offen
**Anlass:** Prüfung "Schutz gegen Verwackeln" am 9. Oktober. Langzeit (Mittelwert) und Menschen wegrechnen (Median)
mitteln etwa 20 Bilder ohne Ausrichtung; aus der Hand gibt das Doppelbilder oder Unschärfe. Fokus-Stacking setzt Blöcke
aus unausgerichteten Bildern zusammen. Der Pro-Modus erlaubt lange Zeiten ohne Hinweis.

## Ziel
Die Ausrichtung des Nacht-Kerns wird ein gemeinsamer Baustein (`FrameAligner`, `FrameAlignment.alignInPlace`) und vor
jedem Mittel, Median und Fokus-Stacking angewandt. Der Pro-Modus warnt bei Verwacklungsgefahr.

## Akzeptanzkriterien
| Nr. | Kriterium | Test |
|---|---|---|
| K1 | Ausrichtung findet die Verschiebung exakt (unregelmäßige Blöcke, Versatz bis 12 Pixel) und legt die Bilder auf das erste | `FrameAlignmentTest > Guter Fall ...` |
| K2 | Langzeit aus der Hand (Versatz bis 10 Pixel): Struktur mindestens 90 % der ruhigen Serie | `QualityLabTest > Testlabor Langzeit freihand ...` (ohne Ausrichtung deutlich weniger, wird im Bericht mitgemessen) |
| K3 | Menschen wegrechnen aus der Hand mit Passant: Geist unter 3 Stufen, Struktur mindestens 90 % | `QualityLabTest > Testlabor Menschen wegrechnen freihand ...` |
| K4 | Fokus-Stacking aus der Hand: Abweichung zur scharfen Wahrheit höchstens halb so groß wie ohne Ausrichtung | `FrameAlignmentTest > Fokus-Stacking ...` |
| K5 | Nacht-Kern rechnet mit dem gemeinsamen Baustein unverändert (alle Nacht- und Labortests grün, Werte im Bericht gleich) | bestehende Tests, lokaler Lauf |
| K6 | Pro-Modus: ab einer Belichtungszeit über 1/24 s Hinweis "Verwacklungsgefahr: Handy abstützen oder Timer nutzen" | `CameraViewModelTest > Pro warnt ...`, `CameraContentTest > pro_warnt_vor_verwackeln` |
| K7 | kleine Bilder (unter 32 Pixel) bleiben unverändert, die Verarbeitung bleibt abbrechbar | `FrameAlignmentTest > Randfall ...` |

Grenze K6: Kehrwertregel (1 durch Brennweite, Hauptkamera etwa 24 mm Kleinbild), also 1/24 s. Der Stabilisator erlaubt
oft längere Zeiten; der Hinweis warnt nur und sperrt nichts. Die in der Prüfung genannte 1/48 s fand ich in den
Projektdokumenten nicht.

## Nicht Teil dieser Änderung
Drehung, örtliche Ausrichtung, Gyroskop; Wackeln im Hinweis der Serienmodi.

## Betroffene Schichten und Regeln
`:core:pure` (FrameAlignment neu, NightMerge nutzt es), `:core:processing` (vor dem Stapeln ausrichten),
`:feature:camera` (Pro-Hinweis). Regeln R17 (abbrechbar), R19 (ein Hilfspuffer, Serie wird an Ort ausgerichtet), R27.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | reine Rechnung in `:core:pure`, Adapter ruft sie nur auf |
| Doppelte Logik | Ausrichtung des Nacht-Kerns herausgelöst statt kopiert |
| Bildpuffer | Ausrichtung in den Puffern der Serie, ein Hilfspuffer (4,7 MB bei 1,6 MP) (R19) |
| Abbruch | `ensureActive` vor jedem Bild (R17) |
| Zeit | etwa 20 Mio. Rechenschritte je Bild wie im Nacht-Kern, bei 20 Bildern etwa 1 bis 2 s |
| neue Ausnahme | keine |

Zweitprüfung und lokale Messung: Erstmals lief `:core:pure` hier lokal (Kotlin-Compiler aus der Gradle-Installation,
128 bis 130 Tests in etwa 1 Minute). Befund: Mit der einfachen Fehlersumme zog ein heller Passant die Ausrichtung in 9
von 19 Bildern um bis zu 35 Pixel weg (Struktur 82 %). Versuche, gemessen an allen Labortests:

| Verfahren | Wegrechnen | Nacht-Labor |
|---|---|---|
| einfache Summe (wie Nacht) | 9 Bilder falsch, 82 % | grün |
| schlechteste 25 % der Felder weglassen | grün | rot (Kante 5,7 px: flache Nacht verliert ihre Kantenfelder) |
| Feldfehler begrenzt auf 2-fachen Median | grün | rot (starkes Rauschen) |
| begrenzt auf 3-fachen Median, Kandidaten aus begrenzter und einfacher Grobsuche, null, vorige Verschiebung | grün, alle Verschiebungen exakt, 97 % | grün |

Gewählt: die letzte Zeile für die Serienmodi (`robust = true`). Der Nacht-Kern behält das einfache Verfahren
(`robust = false`), weil er im Bildstrom ein Zeitbudget je Bild hat und bewegte Objekte dort über die Kachelgewichte
behandelt werden; seine Ergebnisse sind unverändert, er wurde durch die einmal berechnete Helligkeit sogar schneller
(Desktop-JVM, 1,6 MP: 58 ms statt 73 ms je Bild). Die robuste Suche kostet etwa 100 ms je Bild (Desktop), bei 20
Bildern also etwa 2 s, auf dem Handy vermutlich mehr: im Gerätetest messen.

## Risiken und Rückweg
Am Bildrand wiederholte Randpixel (Streifen bis zur Größe des Versatzes). Rückweg: Aufruf im Adapter entfernen.

## Kosten
Ein kurzer Lauf für die neuen Screenshots (Pro-Hinweis), ein Release.

## Ergebnis
Lauf 38014887134 grün (schneller Job, Emulator mit den Stapel-Tests, Release v0.1.90); davor ein Lauf rot nur wegen
der neuen Screenshot-Grundlage (Pro-Hinweis, angesehen, übernommen).

| Kriterium | Wert | Grenze |
|---|---|---|
| K1 Ausrichtung | Verschiebungen exakt | exakt |
| K2 Langzeit freihand | Struktur 0,288 gegen 0,279 Stativ (103 %), ohne Ausrichtung 0,055 | mindestens 90 %, ohne unter 70 % |
| K3 Wegrechnen freihand | Geist 0,52 Stufen, Struktur 0,289 gegen 0,297 (97 %) | unter 3 Stufen, mindestens 90 % |
| K4 Fokus-Stacking | grün | höchstens halb so große Abweichung |
| K5 Nacht | alle Laborwerte unverändert | |
| K6, K7 | Tests grün | |

Offen: Gerätetest mit Zeitmessung der Serienmodi (robuste Suche etwa 100 ms je Bild auf dem Desktop).
