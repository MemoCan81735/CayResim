# Testlabor Bildqualitaet

Relatives Rauschen (kleiner ist besser), Kantenbreite in Pixeln (kleiner ist schaerfer), Geisterbild in Helligkeitsstufen (nahe 0 ist gut), Helligkeit des dunklen Hintergrunds (0 bis 255).

Angepasste Kante: weiche Stufe ans Profil angepasst, stabil auch bei starkem Rauschen (ideal 0,2).

| Szene | Verfahren | Rauschen | Kante | angepasste Kante | Geist | Helligkeit |
|---|---|---|---|---|---|---|
| Dunkel, Stativ | Einzelbild | 0.277 | 0.8 | 0.44 |  | 24.0 |
| Dunkel, Stativ | Mittel ohne Ausrichtung | 0.044 | 0.8 | 0.22 |  | 24.8 |
| Dunkel, Stativ | Nacht-Kern | 0.021 | 0.8 | 0.44 |  | 39.5 |
| Dunkel, freihand | Einzelbild | 0.277 | 0.8 | 0.44 |  | 24.0 |
| Dunkel, freihand | Mittel ohne Ausrichtung | 0.044 | 17.4 | 17.58 |  | 24.8 |
| Dunkel, freihand | Nacht-Kern | 0.020 | 0.8 | 0.44 |  | 39.5 |
| Dunkel, freihand, Passant | Einzelbild | 0.277 | 0.8 | 0.44 |  | 24.0 |
| Dunkel, freihand, Passant | Mittel ohne Ausrichtung | 0.044 | 17.5 | 17.58 | 6.2 | 25.1 |
| Dunkel, freihand, Passant | Nacht-Kern | 0.020 | 0.8 | 0.44 | 0.6 | 39.4 |
| Daemmerung, freihand | Einzelbild | 0.070 | 0.8 | 0.22 |  | 39.5 |
| Daemmerung, freihand | Mittel ohne Ausrichtung | 0.015 | 9.4 | 11.21 |  | 39.6 |
| Daemmerung, freihand | Nacht-Kern | 0.012 | 0.8 | 0.22 |  | 39.7 |
| Sehr dunkel, freihand, 23 Bilder | Einzelbild | 0.370 | 0.8 | 0.44 |  | 19.5 |
| Sehr dunkel, freihand, 23 Bilder | Mittel ohne Ausrichtung | 0.070 | 17.5 | 17.58 |  | 20.4 |
| Sehr dunkel, freihand, 23 Bilder | Nacht-Kern | 0.028 | 0.8 | 0.44 |  | 35.3 |
| Dunkel, starkes Rauschen | Einzelbild | 0.983 | 9.8 | 0.44 |  | 26.4 |
| Dunkel, starkes Rauschen | Mittel ohne Ausrichtung | 0.120 | 0.8 | 0.22 |  | 36.3 |
| Dunkel, starkes Rauschen | Nacht-Kern | 0.061 | 2.0 | 1.10 |  | 38.8 |
| Starkes Rauschen, 1 Pixel Wackeln | Einzelbild | 0.983 | 9.8 | 0.44 |  | 26.4 |
| Starkes Rauschen, 1 Pixel Wackeln | Mittel ohne Ausrichtung | 0.120 | 2.3 | 2.20 |  | 36.3 |
| Starkes Rauschen, 1 Pixel Wackeln | Nacht-Kern | 0.059 | 2.0 | 1.10 |  | 38.8 |

## Farbszene (36 Bilder, Stativ)

| Messung | einfacher Mittelwert | Nacht-Kern |
|---|---|---|
| Kontrast Grau minus Schwarz | 41.8 | 133.3 |
| Schwarz | 0.6 | 0.7 |
| Saettigung Rot (Wahrheit 0.938) | 0.937 | 0.936 |
| Saettigung Gelb (Wahrheit 0.929) | 0.926 | 0.925 |
| Saettigung Blau (Wahrheit 0.929) | 0.926 | 0.926 |
| Saettigung Grau (Wahrheit 0) | 0.000 | 0.000 |

## Lichtloser Raum mit Tuer

Anteil Boden 0.98, Raum 0.0, Tuer 57.4, Blau minus Rot im Raum 0.0

## Restlicht (Anteil Boden, lichtlos ab 0.85)

Licht 6.0E-4: 0.18, Licht 4.5E-4: 0.40, Licht 0.004: 0.00

## Vorhang im fast lichtlosen Raum (72 Bilder, Rauschen bis Stufe 5)

Vorhang 21.9, Wand 7.4, Boden 6.3, Blau minus Rot am Boden 3.0, Vorhang Rot minus Blau 13.2 (Samsung: Vorhang 56, Wand 9, Boden 1; CayResim 0.1.90: alles 39 bis 43); Boden-Modus true, Anteil 0/1 0.80

Diagnose wie im Hinweis: Signal 0,006, Schwelle 0,017, Rauschen 0,145, Median 0,081 (linear), geschaetzt an R 100 / G 100 / B 100 %; Bezugsbild 56 % Nullen, Stufen 1,1 / 1,0 / 1,3

## Heller Vorhang

99-%-Helligkeit 188 (vorher 233, Samsung im Nachttest 201), Struktur 0.119 (Wahrheit etwa 0,15)

## RAW-Weg (simulierter Sensor)

Lichtloser Raum: Raum 0.5, Tuer 21.0, Blau minus Rot 1.1

## RAW-Weg, dunkler Raum

RAW gegen 8 Bit bei gleichem Rauschen: Helligkeit 38.2 / 39.0, angepasste Kante 0.88 / 1.10 px, Rauschen 0.083 / 0.061

## Rand bei Wackeln

Struktur Mitte 0.312, rechter Rand 0.298, oberer Rand 0.317; Bilder je Pixel Mitte 36.0, Rand 27.3

## Passant auf Kachelgrenzen

Geist 1.81 Stufen, Kanten 0.02, 0.17, 0.21

## Bewegungsunschaerfe in jedem dritten Bild

Struktur 0.316 gegen 0.313 ohne Unschaerfe, verworfen 12 von 36

## Langzeit freihand (20 Bilder, bis 10 Pixel)

Struktur ausgerichtet 0.288, ohne Ausrichtung 0.055, Stativ 0.279

## Menschen wegrechnen freihand (20 Bilder, bis 10 Pixel)

Geist 0.52 Stufen, Struktur 0.289 gegen 0.297 auf dem Stativ
