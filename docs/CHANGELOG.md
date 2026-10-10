# Änderungsprotokoll

Neueste Version oben. Je Version: was sich für Arslan ändert, warum, was auf dem Gerät noch zu prüfen ist.

## Signal zu schwach, Schalter Nachtserie (10. Oktober 2026, S-012 und Nachtrag S-011, noch nicht veröffentlicht)
- Schwenk-Messung: Ist die Quelle zu leise, steht jetzt "Signal zu schwach" mit Anleitung (Musik oder Sprache, lauter,
  etwa 1 m) statt "unplausibel". Neue Zeile "Signalstärke" im Ergebnis, ab 0,10 brauchbar.
- Nachtserie: Der Schalter schaltet sich nach einer gespeicherten Serie selbst aus.
- Gerät: Schwenk mit Musik in etwa 1 m, Screenshot des Ergebnisses; Nachtserie einmal speichern und prüfen, dass der
  Schalter danach aus ist.

## 0.1.122 Nachtserie speichern (10. Oktober 2026, S-011)
- Neu unter Einstellungen: Schalter "Nachtserie speichern" (nur zum Messen, gilt bis zum Neustart). Eingeschaltet legt
  jede Nachtaufnahme zusätzlich eine ZIP-Datei in Download/CayResim ab: Helligkeit aller Einzelbilder, drei Farbbilder,
  je Bild Zeitstempel, Versatz, Schärfe, Helligkeit und Anteil Nullen, dazu die Lage während der Serie. Etwa 50 MB.
- Der Hinweis nach der Nachtaufnahme nennt Dateiname und Größe oder "Nachtserie nicht gespeichert".
- Das Nachtbild selbst bleibt gleich. Auswertung am Rechner mit `tools/nacht-serie/auswerten.py`.
- Gerät: zwei Nachtaufnahmen derselben Szene (Schalter an und aus), Hinweise als Screenshot, ZIP über Google Drive,
  dazu ein Samsung-Foto derselben Szene.

## 0.1.115 Schwenk-Messung (10. Oktober 2026, S-010)
- Neu im Mikrofon-Test: Knopf "Schwenk-Messung (25 s)". Nimmt Ton und Lage (Drehvektor, Beschleunigung, je 200 Hz)
  gleichzeitig auf, während das Handy um eine feste Geräuschquelle geschwenkt wird, und schätzt daraus die Richtung
  der Quelle gegenüber dem Kamerablick am Start, mit wirksamem Mikrofonabstand, Abdeckung und Gleichlauf.
- Gespeichert wird eine WAV-Datei (Recordings/CayResim/Schwenk-Datum) mit Lage und Kenndaten als Zusatzblöcken.
- Neues Modul `:core:sensors`, neue Regel R29 (Lagesensoren nur dort). Keine neue Berechtigung.
- Gerät: Quelle (zweites Handy mit Rauschen) 1,5 bis 2 m vor dich, zwei Messungen (Quelle vorne, Quelle 90° rechts),
  Screenshots und WAV-Dateien schicken.

## 0.1.112 Mikrofon-Test mit Eichung (10. Oktober 2026, S-009)
- Vor jeder Klatsch-Phase 2 s Pause mit Ansage ("Gleich links klatschen"), erst dann wird aufgenommen. Klatscher der
  vorigen Seite landen so nicht mehr in der nächsten Phase.
- Die App eicht sich bei jedem Lauf aus den Klatschern links und rechts: wirksamer Mikrofonabstand und Mitte, danach
  sind links −90°, vorne 0°, rechts +90°. Hochkant gehalten erscheint "Handy hochkant gehalten?" statt falscher Winkel.
- "verschieden" heißt jetzt wirklich zwei Mikrofone (Korrelation der Änderungen unter 0,3, steht zusätzlich in der Liste).
- Gerät: Mikrofon-Test einmal quer, einmal hochkant; Screenshots der Klatsch-Probe schicken.

## Mikrofon-Test (10. Oktober 2026, S-008)
- Neu unter Einstellungen: "Mikrofon-Test". Liest die Mikrofone des Geräts, nimmt mit jeder Audioquelle 2 s auf und
  prüft, ob zwei getrennte Kanäle ankommen. Danach eine angesagte Klatsch-Probe (links, rechts, vorne) mit Laufzeit und
  Winkel je Klatscher. Alle Aufnahmen als WAV unter Recordings/CayResim/Mikrotest-Datum.
- Neue Berechtigung Mikrofon, nur im Mikrofon-Test abgefragt. Neue Regel R28: nur das neue Modul `:core:audio` kennt die
  Tonaufnahme. Am Fotografieren ändert sich nichts.
- Gerät: Mikrofon-Test in einem ruhigen Raum, Screenshots des Ergebnisses und den WAV-Ordner schicken.
- Gerätetest S24+: Ortung über die Laufzeit funktioniert mit der Quelle MIC, wenn das Handy quer gehalten wird (links
  etwa −0,45 ms, rechts etwa +0,37 ms, vorne 0). Hochkant zeigt sie nichts, weil beide Mikrofone auf der Längsachse liegen.

## Funktionsliste: Raumscan (10. Oktober 2026)
- `docs/ideen.md`: F8 Raumscan und F9 Raumscan mit ARCore aufgenommen, beide nach der Nacht. Keine Änderung an der App.

## Funktionsliste (10. Oktober 2026)
- `docs/ideen.md`: sieben neue Funktionen auf der Liste (F1 bis F7), mit Reihenfolge. Keine Änderung an der App.

## 0.1.102 (10. Oktober 2026, S-007)
- Nacht-Hinweis mit zweiter Zeile: ob der Boden-Modus gegriffen hat und nach welchen Werten (Signal, Schwelle, Rauschen,
  Median, Anteil geschätzter Pixel), dazu Nullen und Helligkeit des Bezugsbilds. Der Hinweis bleibt 10 s stehen.
- Selbsttest: Stabilisator "vom Gerät nicht gemeldet (angefordert: ein)" oder "kein Aufnahmeergebnis" statt "unbekannt".
- Das Foto bleibt unverändert; es geht nur um Messwerte.
- Gerät: die Vorhang-Szene noch einmal, Screenshot des Hinweises und des Selbsttests.

## 0.1.98 (10. Oktober 2026, S-006)
- Nacht fast ohne Licht: Hintergrund bleibt schwarz statt blaugrauem Nebel, das wenige Licht bleibt sichtbar und warm.
- Tiefe Dunkelheit: bis 72 statt 36 Bilder (etwa 9 s Aufnahme).
- Selbsttest zeigt, ob der Stabilisator aktiv ist; der Nacht-Hinweis sagt "Wackeln nicht messbar" statt "0 px", wenn es im Rauschen nicht erkennbar ist.
- Gerät: der Vorhang von heute Nacht noch einmal (Hinweis-Screenshot, Dauer unter 10 s?), Selbsttest.

## Kurzer Lauf (10. Oktober 2026, S-005)
- CI prüft parallel in fünf Jobs (Kern, Oberfläche, Analyse, APK Debug, APK Release); der Kern meldet sich nach etwa
  3 Minuten, alles nach etwa 5. Die Lauf-Seite zeigt rote Tests, Kompilierfehler, Analysefunde und Laborwerte im
  Vergleich zum letzten Lauf auf main (Warnung ab 10 % schlechter).
- Neu bei jedem Push: detekt, Android Lint und Fallen-Skript, je mit Probe; alte Funde stehen in Lint-Baselines und einer
  detekt-Zählung je Regel und Datei (jeder Fund mehr ist rot).
- Behoben: Architekturtest und Laborbericht kamen aus dem Build-Cache, ohne wirklich zu laufen. A1 erlaubt jetzt auch
  den Koordinaten-Umrechner des Suchers (Arslan, 10.10.).
- Keine Änderung an der App, kein Gerätetest nötig.

## Werkzeug (10. Oktober 2026)
- `tools/run-pure-tests.sh`: Tests von `:core:pure` lokal in etwa einer Minute, ohne Netz. Keine Änderung an der App.

## 0.1.90 (10. Oktober 2026, S-004)
- Langzeit, Menschen wegrechnen und Fokus-Stacking richten die Bilder vorher aus (wie der Nachtmodus): freihand scharf.
- Pro-Modus warnt bei Belichtungszeiten über 1/24 s vor Verwackeln.
- Gerät: Langzeit und Menschen wegrechnen einmal freihand, Pro mit langer Zeit.

## 0.1.86 (9. Oktober 2026, S-003)
- Nacht: das schärfste der ersten 3 Bilder wird Bezug; verwackelte Einzelbilder werden so zuverlässiger verworfen.
- Nacht-Hinweis zeigt das Wackeln, z. B. ", Wackeln bis 12 px".
- Optischer Stabilisator wird angefordert; der Selbsttest zeigt, ob er angeboten und aktiv ist.
- Selbstauslöser 2 s (Schalter "Timer" oben im Sucher).
- Gerät: Selbsttest (Zeile Stabilisator), Nachtfoto freihand mit Hinweis, Timer einmal ausprobieren.

## 0.1.83 (9. Oktober 2026, S-002)
- Nachtaufnahme im beleuchteten Raum höchstens 4-mal so hell wie die Automatik (vorher bis 10-fach, Bild grau und flau).
- Hinweis nennt die Messung der Automatik, z. B. "(Automatik 1/20 s, ISO 640)".
- Selbsttest: RAW mit Schwarzwert 0 gilt immer als abgeschnitten, nicht nur im Dunkeln.
- Gerät: Nachtfoto im beleuchteten Raum (Jeans wie am 9. Oktober) und in einem dunklen Raum, je mit Hinweis-Screenshot und Samsung-Vergleich; Selbsttest einmal im Hellen.

## 0.1.82 (9. Oktober 2026, S-001)
- Nachtfotos bei starkem Rauschen schärfer (Entrauschen milder), Dauer der Nachtaufnahme im Hinweis.
- Bewegungserkennung am Bildrand nicht mehr zu streng; RAW-Weg wartet im Fehlerfall höchstens 3 s.
- Testlabor: stabilere Kantenmessung bei starkem Rauschen.
- Gerät: Nachtfoto mit Hinweis-Screenshot (Dauer muss unter 10 s liegen) und Samsung-Vergleich.

## 0.1.79 (9. Oktober 2026)
- Nacht-Kern: Gewichte je Pixel, Kacheln 8 Pixel mit weichem Übergang, Randpixel ohne Streifen.

## 0.1.76 (9. Oktober 2026)
- Zwei Nachtwege (8 Bit und RAW), der Selbsttest wählt je Gerät; S24+: 8 Bit, weil RAW unter Schwarz abgeschnitten ist.
