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
| 1 | **Schallbild ohne Kamera** (S-018): laufender Ton, Streifen je Frequenzband auf dunkler Fläche, Zahlen je Band, Quelle `MIC` oder `CAMCORDER` wählbar. Misst zugleich, welche Bänder auf dem Gerät brauchbar sind und ob links und rechts stimmen | Tonstrom in der Mikrofon-Boundary; kein Speichern, keine Kamera, keine neue Berechtigung | umgesetzt in v0.1.143 (9.11.), Gerätetest offen |
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

## Prüfung des Stands gegen das Ziel (9.11.2026, 20:40 Uhr, auf Wunsch von Arslan)

Ziel: Im Sucherbild sehen, woher ein Ton kommt.

| Baustein | Stand | Belegt durch | Offen |
|---|---|---|---|
| Richtung links/rechts je Tonhöhen-Bereich | gebaut (`SoundBands`) | Labor: 30° in allen Bereichen innerhalb 6°, kein falscher Streifen in 648 Aufnahmen mit unabhängigem Rauschen | Gerät (K19) |
| Laufender Ton aus dem Mikrofon | gebaut (`MicrophoneBoundary.stream`) | nur der Fake; der Adapter ist weder übersetzt noch gelaufen | CI, Emulator (läuft auf dem Seiten-Branch nicht), Gerät |
| Anzeige als Streifen | gebaut (`SoundImageScreen`) | noch nichts; erster Lauf ohne Vergleichsbilder | CI, Bilder ansehen |
| Haltung quer, links/rechts richtig herum | gebaut | Rechnung; Vorzeichen der Achse nur aus einem Schwenk-Lauf | Gerät: Quelle rechts muss rechts stehen |
| Mikrofon bei laufender Kamera | nicht begonnen | | Freigabe, Messung (Stufe 2) |
| Streifen im Sucherbild | nicht begonnen | | Stufe 3; Bildwinkel der Hauptkamera quer etwa 70°, also nur ±35° der ±90° im Bild |
| Oben/unten | nicht möglich aus einer Haltung mit zwei Mikrofonen | | Schwenken (F12) oder drittes Mikrofon |
| Vorne/hinten | wie oben | | wie oben |

### Die vier größten Lücken
1. **Eine Zeile, kein Bild.** Zwei Mikrofone liefern nur den Winkel zu ihrer Achse. Geräte, die man "akustische
   Kamera" nennt, haben Dutzende Mikrofone. Erreichbar ist mit dem Handy: ein Streifen an der richtigen Stelle im
   Sucher für eine laute, eher nahe Quelle. Zwei Quellen im selben Tonhöhen-Bereich trennen sich erst ab etwa 40 bis
   60° Abstand.
2. **Hall.** Am 10.10. kam bei Musik in 1 m über 1 kHz fast nur Raumschall an; unter 800 Hz zieht der Raumschall die
   Anzeige zur Mitte (Labor: 15° und 24° statt 40°). Ob nahe Quellen (40 cm) alle Bereiche füllen, misst K19.
3. **Nichts davon ist auf dem Gerät bestätigt:** Tonstrom, Vorzeichen, Tonquelle `CAMCORDER`, Verhalten mit Kamera.
4. **Das dritte Mikrofon ist ungeprüft.** Das S24+ hat ein Mikrofon unten, eines oben und ein verstecktes neben der
   Ultraweitwinkel-Kamera (Android nennt es "back"). Der Mikrofon-Test hat nur ein und zwei Kanäle angefragt und
   bekam unten plus hinten. Liefert Android auf Anfrage drei Kanäle, liegen die Mikrofone nicht mehr auf einer Linie:
   dann wäre oben/unten oder vorne/hinten ohne Schwenken messbar. Das ist die größte Chance und billig zu prüfen.

### Vorschlag für die Reihenfolge
1. S-018 fertigstellen (CI, Zweitprüfung, Vergleichsbilder), Release, Gerätetest K19.
2. Im selben Release messen, ob Android drei oder vier Kanäle liefert (Erweiterung des Mikrofon-Tests um eine Anfrage
   mit Kanal-Index-Maske, eigene kleine Spec). Kein neues Speichern außer den WAV-Dateien, die der Test schon ablegt.
3. Danach entscheiden: mit drittem Mikrofon erst die Höhe einbauen, sonst gleich Stufe 2 und 3 (Kamera und Sucher).
4. F12 (Schwenken) bleibt der Weg zur Höhe, falls es kein drittes Mikrofon gibt. Dort fehlen weiter die vier Läufe.

Quellen: TechRadar zum versteckten Mikrofon des S24
(https://www.techradar.com/phones/samsung-galaxy-phones/i-didnt-know-this-existed-samsung-galaxy-s24-owners-have-spotted-the-phones-hidden-microphone).

### Nachtrag 9.11., 22:05 Uhr: drittes Mikrofon gemessen (S-019)
Das S24+ liefert auf Anfrage drei und vier Kanäle, füllt Kanal 3 und 4 aber mit Stille (`MIC` und `CAMCORDER`). Es
bleibt bei zwei Mikrofonen. Punkt 3 der Reihenfolge ist damit entschieden: weiter mit Stufe 2 und 3 (Kamera und
Sucher, Freigabe nötig); die Höhe bleibt Sache des Schwenkens (F12). Davor steht noch der Gerätetest des Schallbilds (K19).

