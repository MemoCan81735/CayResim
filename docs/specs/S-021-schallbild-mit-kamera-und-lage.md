# S-021: Schallbild bei laufender Kamera messen, mit Neigung und Bewegung des Handys

**Stand:** 9.11.2026 · **Status:** Entwurf, wartet auf Freigabe
**Anlass:** Plan F11, Stufe 2. Die Stufe 3 (Streifen im Sucherbild) taugt nur, wenn das Mikrofon bei laufender Kamera
denselben Ton liefert wie ohne. Samsung kann das Mikrofon dann anders schalten (bei Video nutzt die Kamera-App das
hintere Mikrofon als Zoom-Mikrofon). Gemessen ist bisher nur der Ton ohne Kamera (S-018 bis S-020: zwei Mikrofone, 16
und 17). Wunsch von Arslan (9.11., 23:33 Uhr): alle sinnvollen Sensoren einbeziehen, zum Beispiel die Neigung.

## Ziel
Das Schallbild bekommt eine Messung "Mit Kamera vergleichen": Sie nimmt denselben Ton nacheinander ohne Kamera, mit
laufender Kamera und wieder ohne auf und sagt in Zahlen, ob sich Richtung, Pegel und Kanäle ändern. Dazu zeigt das
Schallbild ab jetzt die Lage des Handys (Neigung quer, Kippen, Drehung seit dem Start, Erschütterung) und nutzt sie,
um unbrauchbare Momente zu erkennen. Reine Messung, es wird nichts gespeichert.

## Welche Sensoren und wozu

| Sensor | Wozu | In dieser Spec |
|---|---|---|
| Schwerkraft (aus der Beschleunigung, Tiefpass) | **Neigung quer** (Rollen): Die Mikrofonachse liegt dann schräg, ein Streifen muss im Bild schräg stehen. **Kippen** (nach oben oder unten schauen): verschiebt, wo die Höhe der Quelle im Bild liegt | ja: beide Winkel in Grad, live und je Messphase |
| Drehvektor ohne Kompass (Gyroskop mit Beschleunigung) | **Drehung seit dem Start:** Dreht man das Handy, muss die Richtung der Quelle im Raum gleich bleiben. Das prüft Ton und Lage gemeinsam und ist die Grundlage für das Schwenken (F12). **Drehgeschwindigkeit:** Bei schneller Drehung hinkt das Schallbild nach (Mittelung 0,4 s) | ja: "Richtung im Raum" und Drehgeschwindigkeit; Momente über 30 Grad je Sekunde gelten als bewegt |
| Beschleunigung ohne Schwerkraft | **Erschütterung:** Tippen und Griffgeräusche erzeugen Körperschall, der wie eine Quelle aussieht | ja: Momente mit Stoß werden markiert und nicht gewertet |
| Kamera: Brennweite und Sensorgröße | **Bildwinkel** quer und hoch in Grad. Stufe 3 braucht ihn, um einen Winkel in eine Stelle im Bild umzurechnen | ja: wird gemeldet und angezeigt |
| Kamera: Bilder je Sekunde im Sucherstrom | Stört die Tonrechnung die Kamera? | ja: je Messphase mit Kamera |
| Wärmereserve (Thermal API, schon in R27) | Kamera, Mikrofon und Rechnung zusammen erwärmen das Gerät | ja: Wert zu Beginn und am Ende der Messung |
| Zeitstempel von Ton und Lage (gleiche Uhr seit dem Einschalten) | Ton und Lage gehören zum selben Moment | ja: Versatz wird angezeigt |
| Kompass (Magnetfeld) | Himmelsrichtung | nein: Lautsprecher haben Magnete und stören ihn; der Drehvektor ohne Kompass reicht für "seit dem Start" |
| Luftdruck | nichts: Die Schallgeschwindigkeit hängt von der Temperatur ab, kaum vom Druck | nein |
| Lufttemperatur | Schallgeschwindigkeit (0 bis 35 Grad: etwa 3 % Unterschied, bei 45° rund 2° Fehler) | nein: Das S24+ hat keinen Sensor dafür; bleibt als bekannte Grenze |
| Annäherung und Helligkeit | verdecktes Mikrofon erkennen | nein: Der Sensor sitzt oben vorne, die Mikrofone unten und hinten; ein verdecktes Mikrofon zeigt sich schon am Pegel |
| Bildstabilisator | verschiebt das Bild um Bruchteile eines Grads | nein: erst in Stufe 3 prüfen, falls die Streifen sichtbar wackeln |

## Ablauf der Messung
Handy quer und aufrecht halten, eine gleichmäßige Tonquelle (Musik vom zweiten Handy) etwa 40 cm rechts. Sechs Phasen
zu je 4 Sekunden, davon die erste Sekunde zum Einschwingen ungewertet, zusammen etwa 26 Sekunden:

| Phase | Tonquelle | Kamera |
|---|---|---|
| 1, 2, 3 | Normal (`MIC`) | aus, an, aus |
| 4, 5, 6 | Video (`CAMCORDER`) | aus, an, aus |

Die dritte Phase ohne Kamera zeigt, ob sich die Szene selbst geändert hat. Je Phase: Hauptrichtung (Grad), Anteil
gerichtet, Pegel je Kanal (dBFS), Pegel je Tonhöhen-Bereich, zwei verschiedene Kanäle ja oder nein, aktive Mikrofone
laut Android, verlorene Tonstücke, Rechenzeit, Neigung quer und Kippen, Drehung seit dem Start, Anteil bewegter Momente,
mit Kamera zusätzlich Bilder je Sekunde.

Urteil je Tonquelle (genau eines):

| Urteil | Bedingung |
|---|---|
| gleich | Richtung mit Kamera weicht vom Mittel der beiden Phasen ohne Kamera höchstens 5° ab, Pegel je Kanal höchstens 3 dB, weiter zwei verschiedene Kanäle |
| verändert | zwei verschiedene Kanäle, aber Richtung oder Pegel über der Grenze |
| nur ein Kanal | mit Kamera kommen nicht mehr zwei verschiedene Kanäle an |
| kein Ton | Aufnahme mit Kamera scheitert oder ist stumm |
| Szene unruhig | die beiden Phasen ohne Kamera weichen untereinander mehr als 5° oder 3 dB ab |
| bewegt | mehr als 20 % der gewerteten Momente bewegt, oder Drehung seit dem Start über 5° |
| keine Quelle | ohne Kamera kein gerichteter Ton (Anteil unter 0,3) |
| Kamera scheitert | die Kamera ließ sich nicht starten; der Fehler steht als Wert da |

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Test |
|---|---|---|
| K1 | Neigung aus der Schwerkraft: Handy quer und aufrecht ergibt Neigung quer 0° und Kippen 0°; 10° gerollt und 20° gekippt innerhalb 0,5°; beide Querhaltungen gleich; flach liegend: "keine Aussage" statt einer Zahl | `DeviceTiltTest > S-021 Neigung quer und Kippen aus der Schwerkraft` |
| K2 | Drehung seit dem Start aus zwei Drehvektoren: 25° um die Hochachse innerhalb 0,5°, Vorzeichen rechts positiv; Drehgeschwindigkeit aus zwei Werten im Abstand 10 ms innerhalb 5 % | `DeviceTiltTest > S-021 Drehung seit dem Start und Drehgeschwindigkeit` |
| K3 | Richtung im Raum: Quelle fest bei +30°, Handy dreht in 5 Schritten von 0° auf +20°: gemessene Richtung fällt auf +10°, Richtung im Raum bleibt innerhalb 3° bei +30° | `SoundCameraCheckTest > S-021 Richtung im Raum bleibt beim Drehen stehen` |
| K4 | Erschütterung: Beschleunigung ohne Schwerkraft über 1,5 m/s² oder Drehgeschwindigkeit über 30 Grad je Sekunde markiert den Moment als bewegt; ruhige Hand (0,2 m/s², 3 Grad je Sekunde) nicht | `DeviceTiltTest > S-021 bewegte Momente` |
| K5 | Urteil: je Zeile der Tabelle oben ein Fall mit festen Zahlen ergibt genau dieses Urteil; Grenzfälle 5,0° und 3,0 dB sind noch "gleich" | `SoundCameraCheckTest > S-021 Urteil je Fall` |
| K6 | Bildwinkel aus Brennweite und Sensorgröße: 6,3 mm und 9,8 mm Breite ergeben 75,8° innerhalb 0,1°; fehlt ein Wert: keine Zahl | `ViewAngleTest > S-021 Bildwinkel aus Brennweite und Sensor` |
| K7 | Ablauf: sechs Phasen in der Reihenfolge der Tabelle; die Kamera läuft genau in Phase 2 und 5; die erste Sekunde jeder Phase wird nicht gewertet; Ergebnis je Phase und Urteil je Tonquelle; Lauf höchstens 30 s | `SoundCameraUseCaseTest > S-021 sechs Phasen, Kamera in Phase 2 und 5` |
| K8 | Fehlerfälle: Kamera startet nicht: Urteil "Kamera scheitert", Ton läuft zu Ende; Mikrofon scheitert in einer Phase: Fehler als Wert, die übrigen Phasen laufen; Lagesensor fehlt oder wirft: Messung läuft ohne Lage, Urteil "bewegt" entfällt, Zeile "Lage nicht verfügbar" | `SoundCameraUseCaseTest > S-021 Fehler enden als Werte` |
| K9 | Abbruch in jeder Phase: Kamera gestoppt, Mikrofon frei, Sensoren abgemeldet; danach lässt sich das Schallbild normal starten | `SoundCameraUseCaseTest > S-021 Abbruch gibt alles frei` |
| K10 | Schallbild zeigt live eine Zeile "Neigung quer +2°, Kippen -5°, Drehung seit Start +0°, ruhig" (oder "bewegt"); bewegte Momente zeigen keine neuen Streifen | `SoundImageTest > S-021 Lagezeile im Schallbild` |
| K11 | Ergebnis der Messung: Tabelle je Phase und je Tonquelle das Urteil in Worten mit den Zahlen dahinter, dazu Bildwinkel, Bilder je Sekunde, Wärmereserve | `SoundImageTest > S-021 Ergebnis der Messung mit Kamera` (ein Vergleichsbild) |
| K12 | Ohne Kamera-Berechtigung: Knopf fragt nach der Berechtigung; abgelehnt: Hinweis, das Schallbild ohne Kamera bleibt nutzbar | `SoundImageTest > S-021 ohne Kamera-Berechtigung` |
| K13 | Adapter (Emulator): Tonstrom und Kamera gleichzeitig starten und stoppen, zehnmal hintereinander, ohne Absturz und ohne offene Aufnahme; der Bildwinkel ist gesetzt oder ausdrücklich unbekannt | `SoundWithCameraDeviceTest > s021TonstromMitKamera` |
| K14 | Zeit und Speicher: Rechnung der Lage je Moment höchstens 0,1 ms (JVM, bester von 15 nach 20 Aufwärmläufen); keine neuen Puffer je Moment; Messzeile im Schallbild bleibt | `DeviceTiltTest > S-021 Rechenzeit der Lage` |
| K15 | Gerätetest S24+ (siehe unten) | Arslan |

**Gerätetest (K15):** Schallbild öffnen, Musik vom zweiten Handy 40 cm rechts, Handy quer und aufrecht ruhig halten,
"Mit Kamera vergleichen" starten, 26 Sekunden warten, Screenshot des Ergebnisses. Dann ohne Messung: Handy langsam
etwa 20° nach links drehen und zurück, Screenshot der Lagezeile am Ende der Drehung (die "Richtung im Raum" soll stehen
bleiben). Einmal das Handy 10° schräg halten, Screenshot.

## Nicht Teil dieser Änderung
- Keine Streifen im Sucherbild (Stufe 3) und kein sichtbares Kamerabild in dieser Messung.
- Kein Speichern von Ton, Bild oder Messwerten; der Nachweis ist der Screenshot.
- Keine Korrektur der Richtung durch die Neigung; sie wird gemessen und angezeigt. Die Umrechnung in eine Stelle im
  Bild gehört zu Stufe 3.
- Schwenk-Messung (F12), Mikrofon-Test und die Kamera-Modi bleiben unverändert.

## Betroffene Schichten und Regeln
- `:core:pure`: `DeviceTilt` (Neigung, Drehung, bewegte Momente), `SoundCameraCheck` (Urteil, Richtung im Raum),
  `ViewAngle`.
- `:core:boundary`: `CameraCapabilitiesSnapshot` bekommt den Bildwinkel der Hauptkamera (zwei Zahlen, ohne Standardwert,
  damit der Compiler jeden Adapter zwingt). Die Lagesensoren brauchen keine neue Schnittstelle: `MotionSensorBoundary`
  liefert Drehvektor und Beschleunigung schon (R29).
- `:core:camera`: Bildwinkel aus den Kameradaten lesen. Die Kamera läuft in der Messung ohne sichtbaren Sucher über die
  Ersatz-Fläche (`ensureSurface`, wie im Selbsttest).
- `:core:control`: `SoundCameraUseCase` (Ablauf); `SoundImageUseCase` um die Lagewerte je Momentaufnahme erweitert.
  Sensor-Flüsse mit `.catch` vor `flowOn` (R14).
- `:feature:settings`: Knopf, Lagezeile, Ergebnis; Kamera-Berechtigung im Schallbild.
- **Braucht Freigabe:**
  1. Kamera und Mikrofon laufen gleichzeitig. Ergänzung zu R28: "Kamera und Tonaufnahme dürfen gleichzeitig laufen;
     nur ein UseCase steuert beide, die Adapter kennen einander nicht."
  2. Neue Kamera-Sitzung außerhalb des Kamera-Screens (im Schallbild, ohne Sucher).
  3. Kamera-Berechtigung wird auch im Schallbild erfragt (keine neue Berechtigung der App, aber ein neuer Ort).
- Kein neues Speichern, keine Änderung am Bildergebnis.

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | Rechnung in pure; Kamera nur in `:core:camera`, Ton nur in `:core:audio`, Sensoren nur in `:core:sensors` (R1, R11, R28, R29) |
| Nur Zahlen, Schlüssel, Enums | Ergebnis je Phase und Urteil als Zahlen und Enums; Texte in der UI (R23) |
| Fehler und Ausweichweg | Kamera, Mikrofon und Sensor je als Fehlerwert; die Messung läuft ohne den ausgefallenen Teil zu Ende (R14, R24) |
| Abbruch und Freigabe | Kamera-Stopp mit Merker im `try`, Aufräumen in genau einem `NonCancellable`-Block; Tonstrom und Sensoren geben sich wie bisher selbst frei (R17, H1) |
| Bildpuffer | keiner: Die Messung liest keine Bilder, nur die Zahl der Bilder je Sekunde (R19) |
| Main-Thread | Start der Kamera und der Aufnahme nicht auf dem Main-Thread (R18) |
| Gespeicherte Daten | keine (R26) |
| Zeit und Speicher | Lage höchstens 0,1 ms je Moment (K14); Messzeile zeigt Rechenzeit, verlorene Tonstücke, Bilder je Sekunde, Wärmereserve (R27) |
| neue Ausnahme | keine Ausnahme, aber die Ergänzung zu R28 oben |

## Risiken und Rückweg
- Die Kamera ohne sichtbaren Sucher verhält sich womöglich anders als mit Sucher (Samsung schaltet den Ton vielleicht
  erst bei einer Video-Sitzung um). Die Messung gilt dann nur für die Foto-Sitzung; das Ergebnis nennt die Art der
  Sitzung. Stufe 3 wiederholt die Messung mit Sucher.
- Der Emulator hat ein anderes Mikrofon und keine echten Lagesensoren: K13 prüft nur Start, Stopp und Freigabe. Ob Ton
  und Kamera auf dem S24+ zusammen laufen, zeigt erst der Gerätetest.
- Adapter-Code läuft auf dem Seiten-Branch nicht im Emulator (Lehre aus S-018); der erste echte Lauf ist das Release.
- Die Vorzeichen von Neigung, Kippen und Drehung sind aus der Rechnung, nicht vom Gerät. Der Gerätetest (drehen,
  schräg halten) bestätigt oder dreht sie.
- Die Grenzen 5°, 3 dB, 1,5 m/s² und 30 Grad je Sekunde sind Startwerte aus dem Labor und den Messungen vom 9.11.
  (Klatscher rechts stabil auf 0,05 ms); nach dem Gerätetest werden sie bestätigt oder geändert, nicht nach einer
  einzelnen Szene.
- Rückweg: Knopf und Lagezeile entfernen; die Rechenfunktionen bleiben ungenutzt.

## Kosten
Zwei kurze Läufe auf einem Seiten-Branch, ein Lauf für die Vergleichsbilder (Schallbild ändert sich sichtbar), ein
Release (etwa 30 Minuten). Unabhängige Zweitprüfung (über 150 Zeilen, Kamera-Adapter). Gerätetest durch Arslan (K15).

## Ergebnis
(nach der Umsetzung)
