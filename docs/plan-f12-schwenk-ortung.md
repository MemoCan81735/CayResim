# F12 Schwenk-Ortung: Plan, offene Punkte und Risiken

**Stand:** 10.10.2026 · Funktion F12 in `docs/ideen.md` · Stufe 1 ist S-010 (Schwenk-Messung, v0.1.115)

## Stufen
1. **Messung (S-010):** 25 s Ton und Lage, Richtung einer festen Quelle relativ zum Kamerablick am Start, alle
   Rohdaten in einer WAV-Datei. Zweck: messen, ob die Richtung mit dem S24+ genau genug wird.
2. **Peilen in 3D live:** Pfeil auf die Quelle, laufend nachgeführt, ohne Kamera. Erst wenn Stufe 1 die Ziele aus
   S-010 K9 erreicht.
3. **Startbild, Abgleich, Einblendung:** Schallkarte auf dem Kamerabild (Weg zu F11 ohne Zusatzgerät).

## Genauigkeit der Richtung
| Blocker | Wirkung | Stand |
|---|---|---|
| Ton und Lage zeitlich versetzt | bei 60°/s Schwenk etwa 1° je 17 ms | gemessen über Klopfer, Ziel höchstens 20 ms (S-010 K9) |
| Startrichtung ruckelt beim Klopfen | bis 12° | behoben in S-010 (Mittel aus der Ruhephase) |
| Kompass springt | Richtung springt | behoben in S-010 (Drehvektor ohne Kompass) |
| Zu wenig gedreht | Richtung nicht eindeutig | erkannt ("zu einseitig"), Abdeckung angezeigt |
| Mikrofonabstand, Schallgeschwindigkeit | Winkel verzerrt | in jeder Messung mitgeschätzt; Temperatur egal |
| Vorzeichen der Achse (welcher Kanal unten) | Richtung um 180° gedreht | Gerätetest S-010 |
| Mikrofonachse nicht genau die Längsachse (hinteres Mikrofon auf der Rückseite) | einige Grad, je nach Haltung | **offen**: Achse aus den Messdaten mitschätzen (3 Zahlen mehr); zuerst an den WAV-Dateien des Gerätetests prüfen |
| Schall läuft ums Gehäuse (S-009: links −0,48, rechts +0,40 ms) | richtungsabhängiger Fehler | **offen**: Eichtabelle je Richtung, nur wenn der Gerätetest es verlangt |
| Hall im Raum | Ausreißer, Geisterquellen an Wänden | teilweise (GCC-PHAT); **offen**: Ausreißerfilter in der Ausgleichsrechnung |
| Handy verschoben statt gedreht | bei 1,5 m Abstand und 20 cm Weg bis etwa 7° | nur Anleitung; später als Triangulation nutzbar |
| Samsung wechselt beim Drehen die Mikrofone | Messung bricht | **offen**: aktive Mikrofone bei der Schwenk-Messung mitschreiben |
| Reine Töne über etwa 1,1 kHz (15 cm Abstand) | mehrdeutig | Rauschen und Sprache nicht betroffen; Drehen löst es teilweise; für Frequenzstreifen (F11) beachten |
| Quelle bewegt sich oder pausiert | Unsinn oder zu wenige Fenster | Annahme dokumentiert; eigener Grund "zu wenige Messfenster" |

## Darstellung im Kamerabild (Stufe 3)
1. **Genauigkeit ist nicht gleich Auflösung.** Eine Quelle wird auf wenige Grad getroffen; zwei nahe Quellen
   trennen sich bei 15 cm Abstand erst ab etwa 40 bis 60° (mittlere Töne). Drehen vergrößert die Öffnung nicht, nur
   Verschieben. Darstellung deshalb als Marker mit Fehlerkreis statt großem Farbfleck; bei mehreren Quellen nur die
   lautesten je Frequenzband.
2. **Mehrere Quellen:** Die Ausgleichsrechnung nimmt eine Quelle an. Für eine Schallkarte wird sie durch eine Karte
   über alle Richtungen ersetzt, die alle Messungen aufsummiert.
3. **Kamera und Mikrofon gleichzeitig:** Mit laufender Kamera kann Samsung das Mikrofon anders schalten
   (Videoprofil). Neu messen, bevor eingeblendet wird; braucht Freigabe (Regel R28).
4. **Richtung zu Bildpunkt:** Bildwinkel der Kamera aus CameraX, Zuordnung zum Startbild; das Startbild korrigiert
   die langsame Drift der Lage.
5. **Live-Hilfe beim Schwenken:** Abdeckung schon während des Schwenks anzeigen, damit es weniger Fehlversuche gibt.

## Lösungswege, im Modell geprüft (10.10.2026)
Modell: `tools/schwenk-modell/schwenk_modell.py` (Raum 5 × 4 × 2,7 m mit Spiegelquellen, Schwenk ±50° seitlich,
±25° Nicken, quer bis hochkant, 22 s, Fenster 50 ms). Echte Dateien: `tools/schwenk-modell/auswerten.py`.
Fehler der Richtung in Grad, Median aus 3 Läufen:

| Szenario | M1 heute | M3 robust | M4 Richtungskarte | M5 Achse je Lauf | M6 Achse geeicht + Karte |
|---|---|---|---|---|---|
| ideal | 0,1 | 0,1 | 0,1 | 0,3 | (*) |
| realistisch: Hall, Achse 10° geneigt, Versatz 30 ms, Hand ±8 cm | 12,9 | 14,7 | 14,7 | 4,2 | **2,1** |
| reiner Ton 2 kHz | 21,8 | 5,5 | 11,7 | 22,5 | **2,5** |
| zwei Quellen (zweite 3 dB leiser, 70° rechts) | 12,8 | 14,7 | 75,5 (zweite Quelle) | 3,9 | **2,2** |
| schmaler Schwenk (±40°, wenig Kippen) | 2,5 | 0,3 | 0,3 | 85,3 | 4,5 |
| leise Quelle (Raumgeräusch 3 dB über der Quelle) | 12,9 | 15,0 | 14,8 | 4,0 | **2,2** |

(*) M6 nutzt die Achse eines Geräts mit geneigter Achse; im Modell ohne Neigung passt sie nicht (11°). Auf dem echten
Gerät stimmen Eichung und Gerät überein.

Fehlerbudget, je eine Störung allein mit dem heutigen Verfahren: Achse 10° geneigt 11,0°; Hand ±8 cm 2,4°; Hall 0,0°;
Zeitversatz 30 ms 0,2°; Lagerauschen 0,5° ergibt 0,1°.

Erkenntnisse:
1. **Die Lage der Mikrofonachse ist der größte Fehler.** Sie wird einmal je Gerät mit einem weiten Eichschwenk
   (Quelle vorne, quer bis hochkant) geschätzt (Rang-1-Zerlegung) und dann festgehalten. Je Lauf mitschätzen (M5)
   scheitert bei schmalem Schwenk (85°) und bei reinen Tönen.
2. **Richtungskarte plus robuste Rechnung mit geeichter Achse (M6)** hält in allen realistischen Fällen 2 bis 2,5°,
   auch bei reinem Ton und zwei Quellen. Die Karte allein findet nicht immer die lautere Quelle (75,5°): für die
   Darstellung alle Gipfel zeigen, für die Richtung den Gipfel nehmen, dessen Fenster am besten passen.
3. **Zeitversatz aus den Daten suchen lohnt nicht:** 30 ms kosten nur 0,2°, und die Suche liefert ohne große
   Drehgeschwindigkeit keine verlässliche Zahl (in einzelnen Läufen 74 bis 87 ms). Die Klopfer bleiben die Messung.
4. **Hall** schadet im Modell kaum (Spiegelquellen bis zweiter Ordnung); echte Räume prüft erst der Gerätetest.
5. **Hand ±8 cm** kostet 2,4°: Anleitung reicht; Verschieben wird erst mit ARCore zur Triangulation.

Erster echter Lauf (S24+, 10.10., 16:05 Uhr, `Schwenk-20261010-160510`): Schwenk gut (quer bis hochkant, ±40°),
Gleichlauf 19,5 ms, Lage 125 Hz, aber die Quelle kam kaum an: Pegel −50 bis −60 dBFS, GCC-Spitze im Median 0,07
(Klatscher 0,5 bis 0,7), Laufzeit fast immer um 0 ms unabhängig von der Drehung. Keines der Verfahren findet eine
Richtung; zuerst muss das Signal stimmen (lauter, näher, Musik oder Sprache statt Rauschen, weil Samsungs
Rauschunterdrückung gleichmäßiges Rauschen dämpft). Daraus für die App: bei schwachem Signal "Signal zu schwach"
statt "unplausibel" melden.

Empfohlene Reihenfolge für die App (je eigene Spec):
1. Signalprüfung (Median der GCC-Spitze) mit klarer Meldung. Umgesetzt in S-012 (Grenze 0,10).
2. Eichschwenk je Gerät, Achse speichern (neues Speichern von Daten, Freigabe nötig).
3. Richtungskarte plus robuste Rechnung mit der geeichten Achse (M6), Ergebnis mit Fehlerkreis.
4. Live-Hilfe beim Schwenken (fehlende Richtung als Ansage).

## Nächste Schritte nach dem Gerätetest S-010
1. Aktive Mikrofone in der Schwenk-Messung mitschreiben.
2. Mikrofonachse aus den Daten mitschätzen; zuerst offline an den WAV-Dateien.
3. Ausreißerfilter in der Ausgleichsrechnung.

Jeder Schritt mit eigener Spec und Freigabe (CLAUDE.md Abschnitt 1).
