# Änderungsprotokoll

Neueste Version oben. Je Version: was sich für Arslan ändert, warum, was auf dem Gerät noch zu prüfen ist.

## nächste Version (S-003)
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
