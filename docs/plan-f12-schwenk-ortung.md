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

Zweiter echter Lauf (S24+, v0.1.127, 10.10., 22:14 Uhr, `Schwenk-20261010-221421`, Musik vom zweiten Handy in
etwa 1 m): App meldet richtig "Signal zu schwach" (Median der Spitze 0,088, 182 von 440 Fenstern über 0,1, Gleichlauf
21,3 ms, Lage 125 Hz). Pegel im Median −49 dBFS, also rund 25 dB über dem ruhigen Raum (−70 bis −79 dBFS im
Mikrofon-Test); am Pegel liegt es nicht. Kohärenz der beiden Kanäle in Blöcken von 250 ms: 0,84 (100 bis 300 Hz),
0,54 (300 bis 1000 Hz), 0,18 (1 bis 3 kHz), 0,15 (3 bis 8 kHz). Das entspricht fast genau einem Hallfeld bei 15 cm
Abstand: Das Handy hört vor allem den Raum, nicht den direkten Schall. GCC-PHAT gewichtet alle Frequenzen gleich und
ertränkt so den brauchbaren tiefen Teil.

Gegenprobe mit kohärenzgewichteter GCC (Hannan-Thomson, Kreuzspektrum über 250 ms gemittelt, 80 bis 4000 Hz,
`tools/schwenk-modell/kohaerenz.py`) auf derselben Datei:

| Block | Abstand | Richtung seitlich / Höhe (M1) | (M3 robust) | Korrelation Laufzeit zu Drehung |
|---|---|---|---|---|
| 0,15 s | 10,7 cm | −24 / +19 | −12 / +23 | 0,36 |
| 0,25 s | 13,5 cm | −33 / +13 | −16 / +22 | 0,41 |
| 0,5 s | 12,9 cm | −24 / +20 | −14 / +23 | 0,46 |

Mit GCC-PHAT (App heute): Abstand 0 bis 2,7 cm, keine Richtung. Die Quelle stand laut Anleitung vor der Kamera;
das Ergebnis passt dazu nur mit **umgekehrtem Vorzeichen der Mikrofonachse** (sonst +147 bis +164°, also hinten).
Damit ist das Vorzeichen aus S-010 K9 vorläufig bestimmt (Bestätigung durch Arslan und einen Lauf mit Quelle 90°
rechts offen). Der verbleibende, über alle Blocklängen gleiche Versatz von etwa −15° seitlich und +20° Höhe passt zur
geneigten Mikrofonachse (Modell: Achse 10° geneigt ergibt 11°) und wäre Sache der Eichung je Gerät (Schritt 2).
Der erste Lauf (Rauschen) bleibt auch so unbrauchbar (Abstand 6 bis 8 cm, Richtung beliebig): Samsungs
Rauschunterdrückung.

Modellprüfung der kohärenzgewichteten GCC (S-013, Arslan "Ok" 10.10., 22:20 Uhr, Bedingung: gute Fälle nicht
schlechter). Neue Szenarien S7 und S8 mit Hallfeld (32 ebene Wellen aus zufälligen Richtungen) 6 bzw. 12 dB über dem
direkten Schall. Fehler in Grad, Median aus 3 Läufen (M7 bis 4 kHz; Varianten bis 1,1 und 2 kHz aus 2 Läufen):

| Szenario | M1 heute | M6 geeicht + Karte | M7 bis 4 kHz | M7 bis 2 kHz | M7 bis 1,1 kHz | PHAT-Median |
|---|---|---|---|---|---|---|
| S1 ideal | 0,1 | 11,0 (*) | 0,0 | | | 0,98 |
| S2 realistisch | 12,9 | 2,1 | 13,1 | 10,1 | 14,7 | 0,52 |
| S3 Ton 2 kHz | 21,8 | 2,5 | 72,7 | 72,3 | 62,6 | 0,28 |
| S4 zwei Quellen | 12,8 | 2,2 | 18,0 | 31,1 | 26,3 | 0,31 |
| S6 leise Quelle | 12,9 | 2,2 | 13,0 | | | 0,14 |
| S7 Hallfeld 6 dB | 6,4 | 2,3 | 13,8 | 7,3 | 8,5 | 0,11 |
| S8 Hallfeld 12 dB | 89,6 | 6,3 | 26,4 | 29,0 | 40,1 | 0,09 |

Echter Lauf 22:14 Uhr mit M7 (Achse umgekehrt): bis 4 kHz 13,5 cm, −32/+13; bis 2 kHz 11,9 cm, −32/+25; bis 1,1 kHz
7,3 cm, +3/+45 (Abstand unplausibel).

Ergebnis: M7 ist **kein klarer Gewinn**. Es hilft nur bei starkem Hall (S8 von 90° auf 26°), verschlechtert reinen Ton
(73° statt 22°) und zwei Quellen (18° statt 13°) und hängt beim echten Lauf deutlich vom Band ab. Am robustesten
bleibt im Modell die Richtungskarte mit geeichter Achse (M6, auch bei 12 dB Hall 6°). Ein einziger echter Lauf mit
unbestätigter Lage der Quelle reicht nicht für eine Änderung der App (CLAUDE.md 5 und 9). S-013 wird deshalb
zurückgestellt, bis mehr echte Läufe vorliegen: Quelle vorne und 90° rechts, nah (etwa 40 cm, wenig Hall) und fern
(1 bis 2 m), je mit festem Ablauf (S-014).

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
