# S-011: Nachtserie zum Nachmessen speichern (V3, erster Teil)

**Stand:** 10.10.2026 · **Status:** Entwurf, wartet auf Freigabe durch Arslan
**Anlass:** Gerätetest Nacht S24+ (10.10., 18:30 Uhr, Innenraum Auto, freihändig) mit Samsung-Vergleich:

| Messgröße | CayResim | Samsung |
|---|---|---|
| Helligkeit | 49 | 48 |
| dunkelste / hellste 1 % | 7 / 163 | 4 / 183 |
| Sättigung | 21 % | 23 % |
| Korn dunkle Flächen | 1,6 | 4,8 |
| Schärfe (Laplace) | 7,3 | 26,7 |

Hinweis der App: 1/10 s, ISO 3200 (Automatik 1/25 s), 67 Bilder, 0 verworfen, Aufhellung ×15,7, Wackeln bis 35 px,
Dauer 9,7 s, Boden-Modus nein, Bezugsbild 29 % Nullen. Im Bild: Krümel als Grüppchen aus 3 bis 4 Kopien,
verwaschene Kanten, ein 34 px breiter dunkler Streifen am linken Rand. Ob einzelne Bilder falsch ausgerichtet sind,
ob sich das Handy dreht oder ob jedes Bild schon in sich verwackelt ist, lässt sich am fertigen Foto nicht trennen.
Ohne die Einzelbilder würde jede Änderung geraten (CLAUDE.md Abschnitt 5, V3).

## Ziel
Ein Schalter in den Einstellungen ("Nachtserie speichern") lässt die nächsten Nachtaufnahmen zusätzlich ihre
Einzelbilder und Messwerte je Bild in einer ZIP-Datei ablegen. Damit wird offline gemessen: Versatz und Güte der
Ausrichtung je Bild, Schärfe je Bild, Drehung und Bewegung aus dem Lagesensor, Ränder. Das Nachtbild selbst bleibt
Bit für Bit gleich.

## Inhalt der ZIP-Datei (`Download/CayResim/Nachtserie-<Datum-Uhrzeit>.zip`)
- `meta.json` (`"format": 1`): Bildgröße, Drehung, Belichtung und ISO (gesetzt und Automatik), Bildzahl, Dauer,
  Ergebnis der Zusammenführung (verwendet, verworfen, Aufhellung, größter Versatz, Diagnose aus S-007), je Bild:
  Zeitstempel, Versatz dx/dy, "Versatz verworfen", "Bild verworfen (verwackelt)", Schärfe, mittlere Helligkeit,
  Anteil Nullen.
- `y-000.bin` bis `y-NNN.bin`: Helligkeit jedes Bilds in voller Auflösung (1440 × 1080, 1 Byte je Pixel). Das reicht
  für Ausrichtung und Schärfe und hält die Datei klein.
- `rgb-first.bin`, `rgb-mid.bin`, `rgb-last.bin`: drei Bilder in Farbe (3 Byte je Pixel), für Farbe und Ton.
- `lage.csv` (`format=v1`): Drehvektor und Beschleunigung während der Serie (Sensor aus S-010, R29).
- Größe geschätzt (Probe mit künstlichen dunklen Bildern): etwa 0,6 MB je Bild, bei 67 Bildern etwa 45 MB, dazu
  7 MB für die Farbbilder.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | `NightMerge` liefert je hinzugefügtem Bild einen Eintrag (Index, dx, dy, Versatz verworfen, Bild verworfen, Schärfe, Helligkeit, Anteil Nullen); künstliche Serie mit bekannten Verschiebungen (bis 30 px) und einem weichgezeichneten Bild: Versätze exakt, das weiche Bild als verworfen; das Ergebnisbild ist Bit für Bit gleich wie ohne Einträge | `NightSeriesTest > S-011 Eintraege je Bild` |
| K2 | Archiv-Format: Schreiben und Lesen ergeben dieselben Bytes und Werte; unbekannte Formatversion oder fehlende Datei ergibt null (R26); Helligkeit aus RGB wie `lumaAt` | `NightSeriesTest > S-011 Archiv Rundreise und Formatversion` |
| K3 | Helligkeit berechnen und mit Stufe 1 packen für 1440 × 1080: höchstens 40 ms je Bild auf der JVM (bester von 5); Probe dunkle Szene höchstens 1 MB je Bild | `NightSeriesTest > S-011 Zeit und Groesse je Bild` |
| K4 | `NightUseCase` mit Fakes: Schalter aus, kein Aufruf des Speichers; Schalter an, jedes Bild nach der Messphase in Reihenfolge abgelegt, danach `meta.json` mit den Einträgen aus K1; Lage läuft parallel und wird abgemeldet | `NightUseCaseTest > S-011 Serie aus und an` |
| K5 | Abbruch während der Serie: halbe Datei wird gelöscht, Belichtung wiederhergestellt, Sensor abgemeldet (R17); Speicherfehler: Nachtbild trotzdem gespeichert, Hinweis "Serie nicht gespeichert" | `NightUseCaseTest > S-011 Abbruch und Speicherfehler` |
| K6 | Kamera-Adapter: jedes Bild trägt den Zeitstempel der Aufnahme (Zeitbasis seit dem Einschalten wie die Sensoren); auf dem Emulator steigend und zwischen Start und Ende des Stroms | `CameraXCameraAdapterTest > s011Zeitstempel` (Emulator) |
| K7 | Daten-Adapter: ZIP wird in `Download/CayResim/` geschrieben, ist danach unter seinem Namen lesbar; abgebrochene Datei ist weg | `MediaStoreSeriesAdapterTest > s011SchreibenUndAbbrechen` (Emulator) |
| K8 | Oberfläche: Schalter in den Einstellungen mit Hinweis "bis zum Neustart, keine Personen fotografieren"; Hinweis nach der Nachtaufnahme mit Dateiname und Größe; je ein Screenshot | `SettingsAndGuideTest > einstellungen_nachtserie`, `CameraScreenTest > nacht_hinweis_serie` |
| K9 | Gerätetest S24+: Serie wird gespeichert, Zahl der Y-Bilder = verwendet + verworfen; Bilder je Sekunde mit Speichern höchstens 10 % unter ohne Speichern (zwei Aufnahmen derselben Szene); Datei offline lesbar mit `tools/nacht-serie/auswerten.py` | Gerätetest |

Tests zuerst rot: K1 bis K5 lokal (`tools/run-pure-tests.sh`, `tools/run-core-tests.sh`), weil Einträge, Archiv
und Speicher noch fehlen; K6 bis K8 in CI, weil Zeitstempel, Adapter und Texte fehlen.

## Nicht Teil dieser Änderung
- Keine Änderung an Ausrichtung, Verwerfen, Belichtung, Bildzahl, Tonkurve oder Rand. Diese folgen erst nach der
  Messung mit eigenen Specs (Rand abschneiden, schlecht passende Bilder verwerfen, kürzere Belichtung).
- Kein RAW-Weg (auf dem S24+ ohnehin abgewählt), keine anderen Modi (Langzeit, Wegrechnen).
- Kein Samsung-Vergleichsfoto in der Datei; das macht Arslan getrennt.
- Der Schalter wird nicht gespeichert und steht nach jedem Neustart auf aus (kein neues Speichern von Einstellungen).

## Betroffene Schichten und Regeln
- `:core:pure`: `NightMerge` sammelt Einträge je Bild (nur Zahlen); neues `NightSeries` (Format, Schreiben in einen
  `OutputStream` mit `java.util.zip`, Lesen für Tests und Werkzeug).
- `:core:boundary`: `Frame.timestampNs` (optional); neue `SeriesArchiveBoundary` (anlegen, Bild ablegen, beenden,
  abbrechen); `DebugOptionsBoundary` mit dem Schalter als `StateFlow` (im Speicher). Fakes.
- `:core:camera`: Zeitstempel aus `ImageProxy.imageInfo.timestamp` in `Frame` (eine Zeile, Kamera-Adapter:
  unabhängige Prüfung nach CLAUDE.md 3).
- `:core:data`: `MediaStoreSeriesAdapter` (Downloads, IoDispatcher, Abbruch löscht die Datei); `InMemoryDebugOptions`.
- `:core:control`: `NightUseCase` legt bei eingeschaltetem Schalter jedes Bild ab und sammelt die Lage parallel.
- `:feature:settings`, `:feature:camera`: Schalter und Hinweiszeile.
- **Freigabe nötig** (CLAUDE.md 1.3): neues Speichern von Daten (ZIP in Downloads), Änderung am Kamera-Adapter
  (Zeitstempel), ein Release.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Format und Rechnung in `:core:pure`, Speichern nur in `:core:data`, Kamera nur in `:core:camera` (R1, R11, R28, R29) |
| Nur Zahlen, Schlüssel, Enums | Einträge und Meta nur Zahlen; Dateiname als Schlüssel; Text baut die UI (R23) |
| Fehler und Ausweichweg | Speichern scheitert, Nachtbild geht vor, Hinweis im Ergebnis (R14, R24) |
| Abbruch und Freigabe | Datei in genau einem `NonCancellable`-Block gelöscht, Sensor in `awaitClose`, Belichtung wie bisher (R17) |
| Bildpuffer | je Bild eine Helligkeitskopie (1,5 MB), höchstens 2 unterwegs zum Speicher; Farbkopien nur für 3 Bilder (R19) |
| Main-Thread | Packen auf ComputeDispatcher, Schreiben auf IoDispatcher (R16, R18) |
| Gespeicherte Daten | `meta.json` mit `"format": 1`, `lage.csv` mit `format=v1`; das Werkzeug lehnt Unbekanntes ab (R26) |
| Zeit und Speicher | K3 und K9; der Hinweis zeigt Bilder je Sekunde und Dateigröße (R27) |
| neue Ausnahme | keine |

## Risiken und Rückweg
- Speichern bremst die Serie (Packen 10 bis 20 ms je Bild auf dem Gerät erwartet): K9 misst es; notfalls nur jedes
  zweite Bild oder Stufe 0 (ungepackt, größer).
- Datei groß (etwa 50 MB): zum Schicken über Google Drive statt Chat; die Datei bleibt auf dem Gerät, nie im
  Repository (CLAUDE.md 7). Der Hinweis am Schalter bittet, keine Personen zu fotografieren.
- Zeitstempel liefert der Emulator eventuell nicht verlässlich: dann prüft K6 nur "steigend", K9 auf dem Gerät.
- Rückweg: Schalter entfernen; alles andere bleibt ungenutzt.

## Kosten
Etwa 3 kurze Läufe (zwei neue Screenshots, Emulator wegen Kamera- und Daten-Adapter), 1 Release mit Emulator.
Unabhängige Prüfung (Kamera-Adapter, über 150 Zeilen, geschätzt 600 bis 800). Gerätetest: zwei Nachtaufnahmen
derselben Szene (Schalter an und aus), Hinweise als Screenshot, ZIP über Google Drive schicken, dazu ein
Samsung-Foto derselben Szene.

## Ergebnis
Noch offen.
