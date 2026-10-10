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

## Nächste Schritte nach dem Gerätetest S-010
1. Aktive Mikrofone in der Schwenk-Messung mitschreiben.
2. Mikrofonachse aus den Daten mitschätzen; zuerst offline an den WAV-Dateien.
3. Ausreißerfilter in der Ausgleichsrechnung.

Jeder Schritt mit eigener Spec und Freigabe (CLAUDE.md Abschnitt 1).
