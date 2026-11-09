# F11 Akustische Kamera: Plan, Stufen und Risiken

**Stand:** 9.11.2026 · Funktion F11 in `docs/ideen.md` · Auftrag von Arslan am 9.11., 20:06 Uhr ("Fange die
umfangreiche Bearbeitung an") · hängt zusammen mit F10 (Mikrofon-Test) und F12 (`docs/plan-f12-schwenk-ortung.md`)

## Ziel
Im Sucherbild ist zu sehen, woher ein Ton kommt: je Frequenzband ein senkrechter Streifen an der Stelle der Quelle,
Farbe nach Tonhöhe, Helligkeit nach Lautstärke.

## Was das S24+ hergibt (gemessen am 10.10.)
| Befund | Folge |
|---|---|
| Eine fremde App bekommt zwei verschiedene Mikrofone (unten und hinten oben), Quellen `MIC` und `CAMCORDER` | nur der Winkel zur Längsachse des Handys: quer gehalten links und rechts, nicht vorne/hinten, nicht oben/unten |
| Laufzeit links −0,48 ms, rechts +0,40 ms, vorne −0,02 ms (Klatscher, Handy quer) | Mitte etwa −0,04 ms, halbe Spanne 0,44 ms (wie 15 cm Abstand) |
| Musik in 1 m: Gleichklang der Kanäle 0,84 (100 bis 300 Hz), 0,54 (300 bis 1000 Hz), 0,18 (1 bis 3 kHz) | im halligen Raum kommt über 1 kHz fast nur Raumschall an; hohe Bänder zeigen erst bei nahen Quellen etwas |
| Tiefer Raumschall erreicht beide Mikrofone fast gleichzeitig | Bänder unter etwa 800 Hz ziehen im Hall zur Bildmitte |
| Reine Töne über etwa 1,1 kHz passen zu mehreren Laufzeiten | Geisterstreifen; Anzeige zeigt sie blasser mit an |
| Vorzeichen der Mikrofonachse aus einem Schwenk-Lauf vorläufig bestimmt | Stufe 1 bestätigt es mit einer Quelle rechts |

## Stufen
| Stufe | Inhalt | Braucht | Stand |
|---|---|---|---|
| 1 | **Schallbild ohne Kamera** (S-018): laufender Ton, Streifen je Frequenzband auf dunkler Fläche, Zahlen je Band, Quelle `MIC` oder `CAMCORDER` wählbar. Misst zugleich, welche Bänder auf dem Gerät brauchbar sind und ob links und rechts stimmen | Tonstrom in der Mikrofon-Boundary; kein Speichern, keine Kamera, keine neue Berechtigung | in Arbeit |
| 2 | **Mikrofon bei laufender Kamera** messen: bleibt das Schallbild gleich, wenn die Kamera läuft (Samsung kann das Mikrofon dann anders schalten)? | Kamera und Mikrofon gleichzeitig: **Freigabe** (Ergänzung zu R28, Berechtigung im Kamera-Screen) | offen |
| 3 | **Einblendung im Sucher:** Streifen über dem Kamerabild, Winkel zu Bildpunkt über den Bildwinkel der Kamera; Quellen außerhalb des Bilds als Pfeil am Rand | Stufe 2; neuer Modus im Sucher | offen |
| 4 | **Foto mit Schallbild** speichern (Streifen ins Foto gerechnet, Original bleibt) | Änderung am Bildergebnis: **Freigabe** | offen |
| 5 | **Oben/unten und vorne/hinten** über Schwenken (F12 Stufe 2 und 3): Schallkarte auf einem Startbild | F12: Achse geeicht, Richtungskarte (neues Speichern: **Freigabe**) | offen |

## Rechenweg Stufe 1 (in `:core:pure`, `SoundBands`)
1. Ton in Rahmen von 1024 Werten (21 ms, Hann-Fenster, halb überlappt), je Rahmen das Spektrum beider Kanäle.
2. Je Frequenz gleitend gemittelt (Zeitkonstante 0,4 s): Leistung je Kanal und Kreuzspektrum.
3. Je Band (200 bis 400, 400 bis 800, 800 bis 1600, 1600 bis 3200, 3200 bis 6400 Hz) die Kreuzkorrelation über 61
   mögliche Laufzeiten zwischen ganz links und ganz rechts. Ihr Maximum ist die Richtung; seine Höhe (0 bis 1) ist der
   Anteil des Schalls, der aus dieser einen Richtung kommt ("Anteil gerichtet").
4. Ein Band gilt nur, wenn der Anteil mindestens 0,3 beträgt und der Pegel über −75 dBFS liegt. Sonst kein Streifen:
   lieber nichts zeigen als etwas Falsches.
5. Hauptrichtung aus dem Mittel der gültigen Bänder.
6. Lagesensor (Schwerkraft): welche Schmalseite links ist, sonst "bitte quer halten".

Verworfen: GCC-PHAT je Band (gewichtet alle Frequenzen gleich, im Hall unbrauchbar, siehe F12-Plan); Gewichtung nach
Gleichklang je Frequenz (ein reiner Ton füllt nur 1 bis 3 von bis zu 68 Frequenzen eines Bands und fiele unter die
Grenze).

## Risiken
| Risiko | Wirkung | Umgang |
|---|---|---|
| Hall im Raum | hohe Bänder leer, tiefe zur Mitte gezogen | Anzeige zeigt nur Bänder über der Grenze; Zahlen je Band im Gerätetest; später Hallmodell abziehen |
| Vorzeichen der Achse falsch | links und rechts vertauscht | Gerätetest Stufe 1 (Quelle rechts); eine Konstante drehen |
| Samsung schaltet das Mikrofon mit laufender Kamera anders | Stufe 3 unbrauchbar | Stufe 2 misst das vorher |
| Zwei Mikrofone | eine Zeile statt einer Fläche; keine Höhe | Streifen über die ganze Bildhöhe; Höhe erst mit Stufe 5 |
| Bildwinkel der Hauptkamera etwa 70° | Quellen außerhalb des Bilds | Pfeil am Rand (Stufe 3) |
| Mitte der Laufzeit je Gerät verschieden (S24+: −0,04 ms, etwa 5°) | Streifen leicht versetzt | Stufe 1 rechnet mit Mitte 0; Eichung speichern erst mit Freigabe (F12 Schritt 2) |

## Reihenfolge
Stufe 1 bauen und auf dem S24+ messen. Erst mit diesen Zahlen Stufe 2 und 3 vorschlagen (je eigene Spec, Freigabe).
