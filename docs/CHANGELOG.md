# Änderungsprotokoll

Neueste Version oben. Je Version: was sich für Arslan ändert, warum, was auf dem Gerät noch zu prüfen ist.

## Mikrofon-Test (10. Oktober 2026, S-008)
- Neu unter Einstellungen: "Mikrofon-Test". Liest die Mikrofone des Geräts, nimmt mit jeder Audioquelle 2 s auf und
  prüft, ob zwei getrennte Kanäle ankommen. Danach eine angesagte Klatsch-Probe (links, rechts, vorne) mit Laufzeit und
  Winkel je Klatscher. Alle Aufnahmen als WAV unter Recordings/CayResim/Mikrotest-Datum.
- Neue Berechtigung Mikrofon, nur im Mikrofon-Test abgefragt. Neue Regel R28: nur das neue Modul `:core:audio` kennt die
  Tonaufnahme. Am Fotografieren ändert sich nichts.
- Gerät: Mikrofon-Test in einem ruhigen Raum, Screenshots des Ergebnisses und den WAV-Ordner schicken.

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
