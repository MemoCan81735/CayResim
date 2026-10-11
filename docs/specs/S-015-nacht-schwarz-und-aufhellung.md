# S-015: Nacht bei sehr wenig Licht: Schwarz statt Schleier

**Stand:** 11.10.2026 · **Status:** freigegeben von Arslan (11.10., 06:56 Uhr: "Ja bitte"); Laborprüfung ergibt keinen
eindeutigen Weg, **Umsetzung zurückgestellt** bis mehr echte Nachtserien vorliegen (Entscheidung bei Arslan)
**Anlass:** S24+ 11.10., 06:39 und 06:42 Uhr, Innenraum mit Fenster in der Dämmerung (S-011 Ergebnis, Fehlerliste 11.10.):
CayResim Schwarz 26, Blau +9, Lichter 58, Helligkeit 40; Samsung Schwarz 1, Lichter 138, Helligkeit 13.

## Ursache (gemessen an der Nachtserie)
- Einzelbilder bei 1,3 von 255, 48 % Nullen; Mittel aller 67 Bilder: Hintergrund 1,27, Fenster 3,55.
- Ohne Boden-Modus zieht die App den Versatz des abgeschnittenen Rauschens nicht ab und hellt nach dem Median auf
  (Ziel 0,021 linear, etwa 40). Der Median ist hier zur Hälfte dieser Versatz (Mittel 0,000422, Signal 0,000222).
- Die Boden-Entscheidung vergleicht das Signal des Medians mit dem Rauschen des Mittels: Gerät 3,4-mal (Auto am 10.10.
  12,7-mal). Bisherige Grenze 1.

## Laborprüfung (11.10.)
Offline mit dem Code von `:core:pure` über `tools/nacht-serie/labor.sh` (Nachtserie nur als Helligkeit, grau; Varianten über `SRC=`), gemessen mit `tools/nacht-serie/vergleich.py`:

| Variante | Helligkeit | 1 % | 99 % | Korn | Schwarz |
|---|---|---|---|---|---|
| heute (Grenze 1) | 36,4 | 20 | 54 | 4,4 | 27,4 |
| A: Grenze 6 (Boden-Modus früher) | 17,0 | 2 | 40 | 5,0 | 8,1 |
| A mit doppelter Aufhellung | 27,3 | 6 | 60 | 8,5 | 13,6 |
| B: Versatz je Kanal am Median abziehen (Grenze 1) | 14,2 | 1 | 34 | 4,1 | 6,2 |
| Samsung (JPEG) | 12,9 | 0,9 | 137,7 | 1,9 | 2,7 / 0,7 / 3,9 |

An der echten Serie kommen A und B nahe an Samsung (dunkel, Schwarz 1 bis 2). Mehr Aufhellung hebt nur das Korn: Wand und
Vorhang sind in den 8-Bit-Bildern bei 1/10 s kaum enthalten.

Testlabor (`QualityLabTest`, künstliche Szenen):
- A bricht drei Grenzen: Farbszene zu flau (Grau minus Schwarz 88 statt mindestens 100), "Dunkel, starkes Rauschen"
  Rauschen 0,79 statt höchstens 0,39, angepasste Kante 1,32 statt höchstens 1,3 px. Diese Laborszenen liegen im selben
  Bereich (Signal 2,9- bis 3,0-mal das Rauschen, 40 % Nullen) und sollen dort aufgehellt werden.
- B bricht zwei: Korn bei starkem Rauschen 0,118 statt höchstens 0,098 (Aufhellung steigt, weil der Median kleiner
  wird), angepasste Kante 1,32 px. Mit gleicher Aufhellung wie vorher (Variante C) wird die Laborszene dunkler (23
  statt 39) und das relative Korn 0,140.
- Künstliche Prüfszene wie das Gerät (Signal 3,7-mal, 42 % Nullen): heute Schwarz 29, A Schwarz 2,8, B 13,0, C 11,1.

## Ergebnis der Prüfung
Kein Weg erfüllt beides: die echte Szene wie Samsung dunkel lassen und die Laborszenen mit gleichem Signal-Rausch-
Verhältnis wie bisher aufhellen. Samsung hellt nach dem Gerätetest vom 8.10. eine ähnlich dunkle Szene auf 35 auf, hier
auf 13; was Samsung unterscheidet, zeigt eine einzelne Szene nicht. Nach CLAUDE.md 5 und 9 (keine Regel nach einer
einzelnen Szene, V3, V9) wird zuerst die Szenenbibliothek ergänzt: 4 bis 6 Nachtserien unterschiedlicher Dunkelheit mit
Samsung-Foto. Danach Entscheidung zwischen A, B oder einer Regel, die beide Fälle trennt, gemessen an allen Szenen.

Vorbereitete Prüfungen (nicht im Repository, Stand der Sitzung): `S-015 Nacht 11-10 schwaches Licht wird schwarz statt
Schleier` (Schwarz höchstens 5, Hintergrund höchstens 20, Streifen mindestens 15 darüber) und `S-015 deutlich
beleuchtet bleibt ohne Boden` (Auto-ähnliche Szene, Verhältnis mindestens 9). Beide waren mit dem alten Code rot.

## Nicht Teil dieser Änderung
- Ausrichten mit dem Lagesensor (S-016), Halte-Kreis (F14).
