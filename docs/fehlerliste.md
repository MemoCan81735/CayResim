# Fehlerliste

Jeder Fehler, der erst in CI, auf dem Emulator oder auf dem S24+ auffiel. Spalte "Schutz": welcher Test oder welche
Regel ihn heute verhindert.

| Datum | Fehler | Ursache | Gefunden durch | Schutz |
|---|---|---|---|---|
| 7.10. | Selbsttest 10 s je Aufnahme | verfallene SurfaceRequest nach Neubinden | S24+ | Gerätetest mit Zeitgrenze, Bindungsnummer |
| 8.10. | Pipeline-Zähler blieb nach Abbruch stehen | `withContext` wirft nach dem Ende bei Abbruch | Emulator-Test | Merker im `try` (H1), CLAUDE.md Fallen |
| 8.10. | StrictMode beim Öffnen des Selbsttests | `filesDir` im Konstruktor auf dem Main-Thread | Emulator, StrictMode | Datei erst auf IO auflösen, Test R18 |
| 8.10. | Nachtserie nur 23 statt 36 Bilder | Obergrenze 30 aus dem Speicher-Stacking galt für den Strom | S24+ Hinweis | Test "Bildstrom nicht auf 30 begrenzt" |
| 8.10. | ISO 1919 statt 3200 | Regel "Automatik am Anschlag" prüfte nur die Zeit | S24+ Hinweis (zweimal) | Test mit den Gerätewerten |
| 9.10. | Lichtloser Raum grau und bläulich | Median-Ziel hellte den Rauschboden auf | S24+ gegen Samsung | Laborszene lichtloser Raum |
| 9.10. | RAW-Messung lief in die Zeitgrenze | `ensureSurface` fehlte ohne Sucher | Emulator-Test | CLAUDE.md Fallen |
| 9.10. | `return` in `withTimeoutOrNull` | nicht inline-Lambda | eigene Prüfung vor dem Push | CLAUDE.md Fallen |
| 9.10. | Typen aus `:core:pure` unsichtbar | `implementation` statt `api` | CI-Kompilierung | CLAUDE.md Fallen |
| 9.10. | "RAW, " ohne Leerzeichen | Android kürzt Leerzeichen am Textende | UI-Test | CLAUDE.md Fallen |
| 9.10. | Randstreifen, Doppelbild, Blockkanten | Randpixel wiederholt, Kachelgewichte sprangen | S24+ im Auto | Laborszenen Rand und Kachelgrenze |
| 9.10. | Kanten bei starkem Rauschen weich (3,6 px) | Entrauschen zu stark; Test verglich nur zwei Verfahren | eigene Prüfung (S-001) | Laborszene starkes Rauschen mit fester Grenze |
| 9.10. | geöffnete Kamera konnte beim Abbruch verloren gehen | zwei Zeitblöcke ineinander, Wert fiel beim Abbruch weg | Zweitprüfung (S-001) | ein Zeitblock, `resume` mit Schließen beim Abbruch |
| 9.10. | Laborbericht ohne Werte, wenn die Farbszene rot ist | Bericht erst nach den Prüfungen geschrieben | Zweitprüfung (S-001) | Bericht im `finally`, alte Teile werden gelöscht |
