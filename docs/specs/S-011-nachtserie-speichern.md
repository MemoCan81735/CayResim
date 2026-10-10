# S-011: Nachtserie zum Nachmessen speichern (V3, erster Teil)

**Stand:** 10.10.2026 · **Status:** freigegeben von Arslan (10.10., 18:56 Uhr: neues Speichern der ZIP-Datei in Downloads, Änderung am Kamera-Adapter für den Zeitstempel, ein Release)
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
| K3 | Helligkeit berechnen und mit Stufe 1 packen für 1440 × 1080: höchstens 80 ms je Bild auf der JVM (bester von 5; Grenze aus 10 Bildern je Sekunde mit Reserve, gemessen lokal 22 ms, CI-Runner 57 ms; die erste Grenze von 40 ms war für den Runner zu knapp); Probe dunkle Szene höchstens 1 MB je Bild | `NightSeriesTest > S-011 Zeit und Groesse je Bild` |
| K4 | `NightUseCase` mit Fakes: Schalter aus, kein Aufruf des Speichers; Schalter an, jedes Bild nach der Messphase in Reihenfolge abgelegt, danach `meta.json` mit den Einträgen aus K1; Lage läuft parallel und wird abgemeldet | `NightUseCaseTest > S-011 Serie aus und an` |
| K5 | Abbruch während der Serie: halbe Datei wird gelöscht, Belichtung wiederhergestellt, Sensor abgemeldet (R17); Speicherfehler: Nachtbild trotzdem gespeichert, Hinweis "Serie nicht gespeichert" | `NightUseCaseTest > S-011 Abbruch und Speicherfehler` |
| K6 | Kamera-Adapter: jedes Bild trägt den Zeitstempel der Aufnahme (Zeitbasis seit dem Einschalten wie die Sensoren); auf dem Emulator steigend und zwischen Start und Ende des Stroms | `CameraXAdapterContractTest > s011Zeitstempel` (Emulator) |
| K7 | Daten-Adapter: ZIP wird in `Download/CayResim/` geschrieben, ist danach unter seinem Namen lesbar; abgebrochene Datei ist weg | `MediaStoreSeriesAdapterTest > s011SchreibenUndAbbrechen` (Emulator) |
| K8 | Oberfläche: Schalter in den Einstellungen mit Hinweis "bis zum Neustart, keine Personen fotografieren"; Hinweis nach der Nachtaufnahme mit Dateiname und Größe; je ein Screenshot | `SettingsAndGuideTest > einstellungen_nachtserie`, `CameraContentTest > nacht_hinweis_serie` |
| K10 | Speichern bremst die Serie nicht (Zweitprüfung B2): Speicher braucht 350 ms je Eintrag bei 10 Bildern je Sekunde; alle 20 Bilder gehen ins Nachtbild, nicht geschaffte Bilder fehlen in der Datei und stehen in `meta.json` als `"archived": false`; Zahl der Y-Dateien = Zahl der abgelegten; Nachlauf höchstens Warteschlange plus Abschluss | `NightUseCaseTest > S-011 langsamer Speicher bremst die Serie nicht` |
| K1b | Nachtbild unverändert (Zweitprüfung B3): feste dunkle Serie mit Versatz bis 28 px, SHA-256 des Ergebnisses gleich dem Wert aus dem Code von main vor S-011 | `NightSeriesTest > S-011 Ergebnis unveraendert und Versatz bis 28 px` |
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
- `:core:processing`: der GPU-Adapter gibt die Einträge je Bild in `NightStats` weiter (nachgetragen nach der
  Zweitprüfung, Befund B1).
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
| Bildpuffer | Warteschlange mit 2 Plätzen (Verweise auf die Bilder des Adapters, keine Kopie), dazu eins in Arbeit; je Bild eine kurzlebige Helligkeitskopie (1,5 MB). Ist die Schlange voll, wird das Bild ausgelassen statt gewartet (R19) |
| Main-Thread | Helligkeit rechnen, Lage sammeln und Texte bauen auf dem ComputeDispatcher; Packen und Schreiben im Adapter auf dem IoDispatcher (R16, R18) |
| Gespeicherte Daten | `meta.json` mit `"format": 1`, `lage.csv` mit `format=v1`; das Werkzeug lehnt Unbekanntes ab (R26) |
| Zeit und Speicher | K3, K10 und K9; der Hinweis zeigt Dateiname und Größe, Bilder je Sekunde und ausgelassene Bilder zeigt das Werkzeug (R27) |
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

## Umsetzung (10.10.2026)
- Schalter in den Einstellungen (`SettingsViewModel` mit `DebugOptionsBoundary`, nur im Speicher), Hinweiszeile nach
  der Nachtaufnahme, ZIP über `MediaStoreSeriesAdapter` in Download/CayResim.
- `NightUseCase` legt die Bilder über eine Warteschlange mit 2 Plätzen ab, die ein eigener Schreiber leert; der
  Sammler der Verarbeitung wartet nie. Bilder anderer Größe zählen wie in der Verarbeitung nicht mit, damit die
  Nummern der Dateien zu den Einträgen passen.
- Werkzeug `tools/nacht-serie/auswerten.py`: Vollständigkeit, Takt, eigener Versatz je Bild gegen den der App,
  Schärfe je Bild, Kippen aus der Lage, Rand. Geprüft an einer künstlichen Serie aus `NightMerge` und `NightSeries`
  (Versätze bis 20 px exakt wiedergefunden, Kippen 0 bis 3° aus einer künstlichen Lage).
- Lokal: `:core:pure` 165 Tests, `:core:control` 98 Tests, Architekturregeln 0 Verstöße, Fallen-Skript ohne Funde.
- Screenshots angesehen und übernommen: `settings.png` (neue Zeile mit Schalter aus), `settings_nightseries.png`,
  `camera_night_series.png`.

### Zweitprüfung (unabhängiger Agent, nur gegen Spec und Regeln)
| Nr. | Befund | Schwere | Erledigt |
|---|---|---|---|
| B1 | GPU-Adapter gab die Einträge je Bild nicht weiter; auf dem Gerät wäre `meta.json` ohne Versatz und Schärfe gewesen. Tests grün, weil der Fake sie selbst setzte | Fehler | `records` ohne Standardwert, Adapter gibt sie weiter; Werkzeug warnt bei fehlenden Werten; Fehlerliste |
| B2 | Ablegen lief in Reihe im Sammler: Packen und Schreiben hätten die Serie gebremst (K9 gefährdet, Nachtbild mit Schalter dann anders) | Warnung | Warteschlange ohne Warten, `"archived"` je Bild, K10 |
| B3 | "Ergebnis Bit für Bit gleich" ohne Test, Versatz nur bis 15 px geprüft | Warnung | K1b mit Goldwert aus main und Versatz bis 28 px; die Prüfung hat dasselbe mit 6 Serien unabhängig nachgerechnet |
| B4 | `open` vor dem `try` und mit `withContext`: beim Abbruch blieb eine angelegte Datei liegen | Warnung | `open` im `try`, im Adapter unter `NonCancellable` |
| B5 | Strom blieb nach Schreibfehler offen, innerer Strom nicht geschlossen | Hinweis | Schließen immer, innerer Strom zusätzlich |
| B6 | Zurücksetzen der Belichtung vor dem Löschen im selben Block | Hinweis | Löschen im inneren `finally` |
| B7 | Fehler beim Ablegen oder im Lagesensor hätte das Nachtbild gekostet | Hinweis | Fehler beenden nur das Speichern; Sensorstrom mit `catch` |
| B8 | Bilder anderer Größe verschieben die Nummern | Hinweis | gleiche Regel wie die Verarbeitung |
| B9 | Lage sammeln und Texte bauen im Kontext des Aufrufers | Hinweis | auf dem ComputeDispatcher |
| B10 | letztes Farbbild bis nach dem Zusammenrechnen gehalten | Hinweis | geschrieben, sobald der Strom endet |
| B11 | Zeitbasis der Kamera nicht in `meta.json`; K6 lässt "monoton" zu | Hinweis | offen: das Werkzeug meldet, wenn Lage und Bilder keine gemeinsame Zeit haben; Gerätetest zeigt es |
| B13 | Einstellungen nicht scrollbar, großer Text schiebt den Schalter weg | Hinweis | scrollbar |
| B14 | Testname K8 falsch | Hinweis | korrigiert |
| B15 | Spec versprach Bilder je Sekunde im Hinweis | Hinweis | Spec angepasst, Werkzeug zeigt sie |
| B16 | MediaStore benennt bei gleichem Namen um, Hinweis zeigte den alten | Hinweis | Name nach dem Anlegen gelesen |
| B17 | Schalter bleibt an, solange die App im Speicher ist, unter Umständen tagelang | Hinweis | offen, Frage an Arslan: Schalter nach einer Aufnahme selbst ausschalten? |

Nicht beanstandet: Schichten und Importe, `api(...)`, Abbruch löscht die halbe Datei, Fehlerweg mit Hinweis,
gültiges JSON, Vorzeichen und Rand im Werkzeug, Zeitstempel im Kamera-Adapter (ein Thread, `@Volatile` reicht).

### Release
v0.1.122 (Lauf 38073283167, 10.10., 20:02 Uhr): alle Jobs grün, Emulator mit `s011Zeitstempel` (Zeitbasis seit dem
Einschalten: ja, steigend: ja, also dieselbe Zeitbasis wie der Lagesensor) und `s011SchreibenUndAbbrechen`.
Kurze Läufe bis dahin: 5, davon rot durch fehlende Screenshot-Grundlagen (erwartet), zwei Kompilierfehler in
`:core:data` (lokal nicht prüfbar), die Zeitgrenze K3 auf dem CI-Runner und zwei detekt-Funde.

## Ergebnis
Gerätetest offen.
