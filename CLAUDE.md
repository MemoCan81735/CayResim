# CayResim: Arbeitsweise

Diese Regeln gelten für jede Änderung am Code, an den Tests und an der Build-Konfiguration. Sie ergänzen die
Architekturvorgaben (`claude/foto-app-architekturvorgaben.md`, Regeln R1 bis R27, Ausnahmen A1 bis A3) und das
Testkonzept (`claude/foto-app-testkonzept.md`). Festgelegt mit Arslan am 9. Oktober 2026.

## 1. Ablauf jeder Änderung

1. **Anlass festhalten:** Befund vom Gerät, Prüfbefund oder Wunsch, mit Messwerten, wenn es welche gibt.
2. **Spec schreiben** (`docs/specs/S-NNN-name.md`, Vorlage `docs/specs/VORLAGE.md`): Ziel, Akzeptanzkriterien als
   Zahlen, was ausdrücklich nicht geändert wird, betroffene Schichten und Regeln, Risiken, Kosten in GitHub-Minuten.
   Kleine Korrekturen unter etwa 20 Zeilen ohne neues Verhalten brauchen keine eigene Spec, aber einen Eintrag im
   Änderungsprotokoll.
3. **Freigabe einholen**, bevor Code entsteht, wenn eines davon zutrifft: neue Ausnahme oder Regeländerung, neues
   Speichern von Daten, neue Kamera-Sitzung oder neue Berechtigung, mehr als ein Release-Lauf, Änderung am
   Bildergebnis, die man auf dem Foto sieht.
4. **Test zuerst (rot):** Aus jedem Akzeptanzkriterium wird ein Test, bevor der Code entsteht. Er muss mit dem alten
   Code scheitern. Lokal gibt es keinen Gradle-Lauf; als Nachweis für "rot" gilt deshalb einer von diesen:
   ein CI-Lauf, das Python-Modell im Arbeitsordner mit derselben Rechnung oder eine schriftliche Begründung im
   Spec, warum der alte Code scheitern muss. Ein Test, der nie rot sein konnte, zählt nicht.
5. **Code (grün):** die kleinste Änderung, die die Tests erfüllt.
6. **Aufräumen:** Namen, Kommentare mit Anlass, doppelte Logik entfernen; Tests bleiben grün.
7. **Architekturprüfung** (Abschnitt 3), bei größeren Änderungen zusätzlich eine unabhängige Prüfung.
8. **Ein Push je Änderung**, gebündelt; Release nur auf Wunsch (Abschnitt 6).
9. **Gerätetest:** klare Anleitung an Arslan, was er tun und schicken soll.
10. **Rückblick:** Was der Gerätetest zeigt, kommt mit Messwerten in die Spec; jeder neue Fehler in `docs/fehlerliste.md`.

## 2. Testgetrieben arbeiten

- Jedes Akzeptanzkriterium hat genau einen benannten Test; der Testname nennt den Fall ("Nachttest S24+ ...").
- Guter Fall, Fehlerfall, Randfall (Testkonzept Abschnitt 6).
- Bildqualität wird im Testlabor gemessen, nicht geschätzt. Ein neuer Grenzwert wird so gewählt, dass der alte
  Fehler ihn verletzt und der neue Code ihn sicher einhält.
- Prüfe die Prüfung: Vergleicht ein Test nur zwei Verfahren miteinander, braucht er zusätzlich eine feste Grenze
  gegen die Wahrheit (Lehre aus S-001: RAW gegen 8 Bit verdeckte, dass beide weich waren).
- Tests sind unabhängig von ihrer Reihenfolge; jeder Test schreibt seine Berichtsdatei selbst.
- Zeit und Speicher sind Prüfwerte. Eine Funktion mit Zeitgrenze braucht eine Messung, die auf dem Gerät sichtbar ist.

## 3. Architekturprüfung bei jeder Änderung

Vor dem Push wird der Diff gegen diese Liste gelesen. Treffer werden behoben oder in der Spec begründet.

| Frage | Regel |
|---|---|
| In welcher Schicht liegt der Code, und importiert er nur, was diese Schicht darf? | R1, R11, Konsist |
| Tragen Snapshots, Ergebnisse und Boundary-Typen nur Zahlen, Schlüssel und Enums? | R23 |
| Endet jeder Fehler in einem Ergebnistyp, und hat er einen Ausweichweg? | R14, R24 |
| Wird bei Abbruch alles freigegeben? Sperren und Zähler mit Merker im `try`, Aufräumen in genau einem `NonCancellable`-Block | R17, Befund H1 |
| Hat jeder Bildpuffer genau einen Besitzer, und ist die Zahl der Kopien begrenzt? | R19 |
| Kein Speicherzugriff auf dem Main-Thread, auch nicht in Konstruktoren, die Hilt baut | R18 |
| Gespeicherte Daten mit Formatversion; Unbekanntes führt zum sicheren Standard | R26 |
| Zeit- und Speicherbudget genannt und gemessen | R27 |
| Braucht die Änderung eine neue Ausnahme? Dann vorher Freigabe | Abschnitt 11 der Vorgaben |

**Unabhängige Prüfung:** Bei Änderungen über etwa 150 Zeilen, am Kamera-Adapter oder an der Bildverarbeitung prüft
ein zweiter Agent den Diff ohne die Begründungen des ersten, nur gegen Spec und Regeln.

**Bekannte Fallen** (aus `docs/fehlerliste.md`), vor jedem Push gezielt suchen:
- `return` in einem nicht inline-Lambda (zum Beispiel in `withTimeoutOrNull`) kompiliert nicht.
- `withContext(...)` wirft nach dem Ende, wenn der Aufrufer abgebrochen wurde; Freigaben deshalb nicht dahinter.
- Leerzeichen am Ende von Android-Texten verschwinden; mit Anführungszeichen schreiben.
- Neue Typen in Boundary-Schnittstellen aus `:core:pure` brauchen `api(...)`, sonst sehen Verbraucher sie nicht.
- Ohne sichtbaren Sucher braucht CameraX für jede Aufnahme die Ersatz-Fläche (`ensureSurface`).
- Regelmäßige Testmuster sind für die Ausrichtung mehrdeutig; Testszenen unregelmäßig wählen.
- Keine zwei Zeitblöcke (`withTimeoutOrNull`) um eine Ressource, die beim Abbruch geschlossen werden muss; ein geöffnetes
  Gerät mit `resume(wert) { ... schließen }` übergeben.
- Testberichte vor den Prüfungen schreiben (oder im `finally`), sonst fehlen die Werte genau im roten Lauf.

## 4. Definition of Done

Eine Änderung ist fertig, wenn alles davon stimmt:
- alle Akzeptanzkriterien der Spec durch Tests belegt, schneller Job und, falls betroffen, Emulator-Job grün
- Testlabor-Bericht angesehen, Werte in der Spec eingetragen
- Architekturvorgaben und Testkonzept synchron: Projektdokument und Tab im Claude Doc "Foto-App Plan"
- Änderungsprotokoll `docs/CHANGELOG.md` ergänzt
- Grenzen und offene Punkte ehrlich genannt, auch die eigenen Fehler
- bei Releases: Anleitung für den Gerätetest an Arslan

## 5. Messen statt vermuten

- Jede Bildänderung wird erst im Python-Modell oder Testlabor gemessen, dann gebaut.
- Gerätebefunde kommen mit Zahlen zurück (Hinweis nach der Aufnahme, Selbsttest). Was das Gerät nicht meldet, kann
  nicht geprüft werden; fehlt eine Messung, wird sie eingebaut, bevor weiter optimiert wird.
- Vergleiche mit Samsung immer mit denselben Messgrößen: Helligkeit, dunkelste und hellste Stellen, Sättigung, Korn.

## 6. GitHub-Minuten

- Das Repository bleibt öffentlich (entschieden mit Arslan am 9. Oktober 2026, V2). Actions-Minuten auf
  Standard-Runnern sind damit frei; größere Runner kosten auch bei öffentlichen Repositories und werden nicht genutzt.
- Gespart wird nur noch Zeit: Ein kurzer Lauf dauert etwa 5, ein Release etwa 30 Minuten. Ein absehbar scheiternder
  Lauf wird sofort abgebrochen.
- Release: `[release]` in der Commit-Nachricht oder "Run workflow" auf GitHub.
- Weil alles öffentlich ist, gilt Abschnitt 7 ohne Ausnahme.
- Noch nicht umgestellt (eigene Änderung, Freigabe nötig): `ci.yml` startet den Emulator weiter nur bei Änderungen
  an Kamera, Verarbeitung, Speicher, App-Shell, Gerätetests oder Build und nimmt außer vor einem Release 2.000 statt
  5.000 Schritte Zufallsbedienung; das Testkonzept nennt noch die Grenze von 2.000 Minuten.

## 7. Sicherheit und Datenschutz

- Keine echten Fotos, Nummernschilder, Gesichter oder Ortsangaben in Repository, Tests oder Protokollen; Testbilder
  werden künstlich erzeugt.
- Keine Schlüssel oder Passwörter im Repository; Testfotos des Selbsttests werden gelöscht.

## 8. Zusammenarbeit

- Keine Änderung ohne Arslans OK, außer Korrekturen eigener Fehler innerhalb einer bereits freigegebenen Aufgabe.
- Annahmen, Kosten und Risiken offen nennen; einfache Erklärungen; keine Gedankenstriche in Texten.

## 9. Schnellere Schleifen

Beschlossen mit Arslan am 9. Oktober 2026 nach einem Rückblick über die ersten 97 Commits (7. bis 9. Oktober).
Rund 40 % davon gingen nicht ins Produkt: 18 für CI und Proben, 10 für Screenshot-Baselines, 8 für Kompilierfehler,
die erst CI fand, 6 für einen später gelöschten Tastentest. Bei der Bildqualität wurde die Regel "Automatik am
Anschlag" in 25 Stunden dreimal geändert, jeweils nach einer einzelnen Szene vom Gerät; 7 von 17 Einträgen der
Fehlerliste fand erst das S24+. Der Engpass ist nicht das Schreiben von Code, sondern die Dauer jeder Rückmeldung.
Mehr Prozess hilft dagegen nicht, kürzere Schleifen und echte Testdaten schon.

Jede Maßnahme wird mit eigener Spec umgesetzt, sofern sie Code betrifft. Der Status wird hier gepflegt.

| Nr. | Maßnahme | Wirkung | Status |
|---|---|---|---|
| V1 | Lokaler Gradle-Lauf für Claude: Netzwerkfreigabe der Cloud-Umgebung für `repo.maven.apache.org`, `dl.google.com`, `plugins.gradle.org`, `services.gradle.org` | Kompilieren und JVM-Tests vor dem Push statt über CI | offen, Einstellung durch Arslan |
| V2 | Öffentlich oder privat entscheiden. Öffentlich: Actions-Minuten auf Standard-Runnern frei, Emulator bei jedem Push, Abschnitt 6 entfällt weitgehend. Privat: Abschnitt 6 bleibt | Keine Sparlogik ohne Grund | entschieden 9.10.: öffentlich (Abschnitt 6); CI-Umstellung offen |
| V3 | Szenenbibliothek: Debug-Funktion "Szene aufzeichnen" speichert die Serie (RAW oder 8 Bit) mit Metadaten und dazu ein Samsung-Foto derselben Szene. 10 bis 20 Szenen ohne Personen, privat abgelegt, nie im öffentlichen Repository. Ein JVM-Lauf rechnet alle Szenen und schreibt einen Bericht je Szene | Jede Parameteränderung wird an allen Szenen gemessen, nicht an einer | offen, Spec (neues Speichern von Daten) |
| V4 | Varianten je Aufnahme: Im Debug-Modus wird eine Aufnahme mit 2 bis 3 Parametersätzen gerechnet und nebeneinander gespeichert | Ein Gerätetest liefert mehrere Datenpunkte ohne neue Version | offen, Spec |
| V5 | Bericht als Datei: Knopf "Bericht teilen" erzeugt eine ZIP mit Messwerten (JSON) und kleinen Vorschaubildern | Kein Abtippen von Screenshots | offen, Spec |
| V6 | Automatische Updates auf dem S24+ mit Obtainium aus den GitHub-Releases (bei privatem Repository mit Zugangsschlüssel) | Kein Herunterladen und Installieren von Hand | offen, Einrichtung durch Arslan |
| V7 | Eine Quelle der Wahrheit: Bildrechnung nur in Kotlin (`:core:pure`) mit einem kleinen Kommandozeilenwerkzeug für Bildordner. Ein Python-Modell, falls noch nötig, liegt versioniert unter `tools/`, nie nur im Arbeitsordner einer Sitzung. Regeln stehen in dieser Datei; das Projektdokument spiegelt sie nur | Keine abweichenden Rechnungen, weniger Abgleich | offen; danach Abschnitt 1 Punkt 4 und Abschnitt 5 anpassen |
| V8 | Statische Analyse im schnellen Job: detekt und Android Lint. Die Fallen aus Abschnitt 3 werden, wo möglich, zu automatischen Regeln | Fehler vor dem Lauf statt im Lauf | offen, Spec |
| V9 | Fertig-Kriterium je Modus in Zahlen gegen die Szenenbibliothek, zum Beispiel für Nacht der Abstand zu Samsung je Messgröße. Danach kein weiteres Abstimmen ohne neuen Befund. Höchstens ein Modus gleichzeitig in Arbeit | Das Abstimmen endet planbar | offen, Zahlen mit Arslan festlegen |

Vorgeschlagene Reihenfolge: V1 und V2, dann V3 und V4, dann der Rest.
