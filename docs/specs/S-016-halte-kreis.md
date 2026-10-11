# S-016: Halte-Kreis bei der Nachtaufnahme (F14)

**Stand:** 11.10.2026 · **Status:** freigegeben von Arslan (11.10., 06:56 Uhr Wunsch "virtueller Kreis wie beim S24+",
08:18 Uhr "Ok" zum Bauen parallel zur Sammlung der Nachtserien)
**Anlass:** Nachtserie 11.10., 06:42 Uhr (S-011 Ergebnis): Das Bild war zu dunkel zum Ausrichten, alle 66 Versätze
verworfen; der Lagesensor zeigt bis 0,94° Drehung in 9,4 s (Median 0,54°), etwa 20 px je Grad, also bis etwa 18 px
Verschmieren. Samsung zeigt beim Nachtmodus einen Kreis, in dem man einen Punkt halten soll.

## Ziel
Während der Nachtserie zeigt der Sucher in der Mitte einen Kreis und einen Punkt, der zeigt, wohin die Kamera gegenüber
dem Start zeigt. Ist die Abweichung höchstens 0,5°, ist der Punkt grün, sonst orange. Ein Ring um den Kreis zeigt den
Fortschritt der Aufnahme. Danach nennt der Hinweis, wie ruhig gehalten wurde (Anteil der Zeit im Kreis, größte
Abweichung), damit jeder Gerätetest diese Zahl mitliefert.

## Rechnung
- Blickrichtung jetzt (Gerät −z, mit dem Drehvektor in die Welt gedreht) im Gerätesystem des Starts: Winkel nach rechts
  (Gerät x) und nach oben (Gerät y) in Grad. Grün bei Gesamtabweichung höchstens 0,5° (etwa 10 px).
- Auf den Bildschirm je nach Drehung der Anzeige: 0°: rechts = x, unten = −y; 90° (Oberkante links): rechts = −y,
  unten = −x; 180°: rechts = −x, unten = y; 270° (Oberkante rechts): rechts = y, unten = x. Der Punkt liegt bei
  0,5° auf dem Kreis und höchstens beim Dreifachen.
- Fortschritt nach der Zeit gegen die geplante Dauer der Nachtaufnahme (Budget der Aufnahme plus Messen).

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Abweichung: Drehung 0,3° nach rechts ergibt rechts 0,3° (±0,01) und grün; 0,8° nach oben ergibt oben 0,8° und nicht grün; Rollen um die Blickachse allein ergibt 0; Bildschirm-Abbildung für alle vier Drehungen der Anzeige wie oben | `HoldGuideTest > S-016 Abweichung und Bildschirm` |
| K2 | Kennzahlen: Anteil der Zeit im Kreis und größte Abweichung aus einer Folge von Lagen (zeitgewichtet) | `HoldGuideTest > S-016 Kennzahlen` |
| K3 | `HoldGuideUseCase` meldet je Lagewert (höchstens etwa 30 je Sekunde) die Abweichung gegen den ersten Wert; beim Abbruch ist der Sensor abgemeldet (R17, R29) | `HoldGuideUseCaseTest > S-016 Abweichung und Abmelden` |
| K4 | Kamera-ViewModel: während der Nachtaufnahme ist der Halte-Zustand gesetzt, danach leer; der Hinweis trägt Anteil und größte Abweichung | `CameraViewModelTest > S-016 Halte-Kreis waehrend der Nachtaufnahme` |
| K5 | Oberfläche: Kreis, Punkt und Ring im Sucher (Screenshot, Zustand "im Kreis" oder "außerhalb des Kreises" für TalkBack); Hinweis ergänzt um ", ruhig gehalten 78 % der Zeit, Abweichung bis 0,9°" | `CameraContentTest > nacht_halte_kreis`, `CameraContentTest > nacht_hinweis_haltung` |
| K6 | Gerätetest S24+: Punkt folgt der Drehung in die richtige Richtung (Kamera nach rechts drehen, Punkt geht nach rechts), hochkant und quer; Hinweis nennt Anteil und Abweichung | Gerätetest |

Tests zuerst rot: K1 bis K4 lokal bzw. über CI (Kamera-ViewModel ist ein Android-Modul), weil Rechnung, UseCase und
Zustand fehlen; K5 in CI.

## Nicht Teil dieser Änderung
- Keine Änderung am Nachtbild, an Belichtung, Bildzahl oder Ausrichtung (Ausrichten mit dem Lagesensor ist S-017).
- Kein Abbruch oder Warten, wenn der Punkt außerhalb ist; nur Anzeige.
- Keine neue Berechtigung (Lagesensor ohne Berechtigung, S-010).

## Betroffene Schichten und Regeln
- `:core:pure`: `HoldGuide` (Abweichung, Bildschirm-Abbildung, Kennzahlen).
- `:core:control`: `HoldGuideUseCase` mit `MotionSensorBoundary` (R29: Sensor nur über die Boundary).
- `:feature:camera`: Halte-Zustand im ViewModel, Overlay im Sucher, Hinweiszeile. Geänderte Screenshots: neu
  `camera_night_hold.png`; bestehende bleiben, weil der Kreis nur während der Nachtaufnahme erscheint.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in `:core:pure`, Sensor über Boundary in `:core:control`, Zeichnen in der UI (R1, R29) |
| Nur Zahlen, Schlüssel, Enums | Halte-Zustand: Winkel, Ja/Nein, Anteil (R23) |
| Fehler und Ausweichweg | ohne Drehvektor kein Kreis, die Aufnahme läuft wie bisher (R14) |
| Abbruch und Freigabe | Sammeln des Sensors endet mit der Nachtaufnahme, auch bei Abbruch (R17) |
| Bildpuffer | keine |
| Main-Thread | Rechnung je Lagewert O(1) auf dem ComputeDispatcher, Anzeige höchstens etwa 30 je Sekunde (R18) |
| Gespeicherte Daten | keine |
| Zeit und Speicher | Messung sichtbar im Hinweis (Anteil, Abweichung) (R27) |
| neue Ausnahme | keine |

## Risiken
- Richtung des Punkts falsch für eine Drehung der Anzeige: K6 prüft hochkant und quer.
- Toleranz 0,5° zu streng oder zu locker: im Hinweis gemessen, nach Gerätetests anpassbar (eine Zahl).

## Kosten
Etwa 2 kurze Läufe (neuer Screenshot), Emulator wegen Kamera-Modul, ein Release nach Wunsch.

## Umsetzung (11.10.2026)
- `HoldGuide` rechnet Abweichung, Bildschirm-Abbildung und zeitgewichtete Kennzahlen; Werte mit älterem Zeitstempel
  werden verworfen.
- `HoldGuideUseCase` führt die Kennzahlen aus jedem Lagewert (etwa 100 je Sekunde) und meldet höchstens etwa 30 je
  Sekunde an die Anzeige. Ein Sensorfehler beendet nur den Kreis, nie die Nachtaufnahme (`.catch`).
- Der Kreis (Halbmesser 30 dp, Ring 40 dp) liegt in einer Zeichenfläche von 200 dp; der Punkt wird kreisförmig auf das
  Dreifache des Kreises begrenzt. Kein Text im Kreis (der Hinweis "Handy ruhig halten" steht schon oben); TalkBack liest
  "Ruhig halten, im Kreis" oder "außerhalb des Kreises".
- Lokal: `:core:pure` 171 Tests, Boundary und Control 36 Tests, Architekturregeln 0 Verstöße, Struktur und Fallen ohne
  Funde.

### Zweitprüfung (unabhängiger Agent, nur gegen Spec und Regeln): kein Fehler, Geometrie für alle vier Drehungen nachgerechnet
| Nr. | Befund | Schwere | Erledigt |
|---|---|---|---|
| W1 | Sensorfehler im Halte-Job bricht die Nachtaufnahme ab (R14) | Warnung | `.catch { }` im UseCase, Test mit werfendem Sensor |
| W2 | K1 nur mit Startlage "gerade", vertauschte Drehreihenfolge fiele nicht auf | Warnung | Test `S-016 Abweichung mit echter Haltung` (hochkant aufrecht, quer) |
| W3 | Kennzahlen aus gedrosselten Werten, größte Abweichung zu klein | Warnung | Kennzahlen je Lagewert im UseCase, `HoldUpdate` trägt sie |
| H4 | Zeit rückwärts zählt doppelt; "0 %" bei Gesamtzeit 0 | Hinweis | ältere Werte verworfen, Hinweis nur bei gemessener Zeit über 0, Randfälle in K2 |
| H5 | Punkt nur bis etwa 1,8-fach, eckige Grenze, verdeckt den Ring | Hinweis | größere Fläche, kreisförmige Grenze beim Dreifachen |
| H6 | Text verschiebt den Kreis aus der Mitte, TalkBack ohne Zusammenfassung | Hinweis | Text entfernt, `mergeDescendants` |
| H10 | K4-Grenze 0,2 bis 0,5 zu weit | Hinweis | 0,34 ± 0,02 |
| H7 | Ring bleibt bei kurzer Nacht halb voll (Fortschritt gegen feste 9,5 s) | Hinweis | offen, nach Gerätetest K6 |
| H8 | Bezugslage ist der Moment des Tippens (Ruck) | Hinweis | offen, im Gerätetest beobachten |
| H9 | etwa 30 Neuaufbauten des Suchers je Sekunde | Hinweis | offen, nur bei Ruckeln auf dem Gerät |

## Ergebnis
Noch offen.
