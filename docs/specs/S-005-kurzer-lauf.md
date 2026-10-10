# S-005: Kurzer Lauf mit mehr Aussage

**Stand:** 9. Oktober 2026 · **Status:** freigegeben (Arslan, 22:41), in Umsetzung
**Anlass:** Rückblick vom 9. Oktober (`CLAUDE.md` Abschnitt 9, V2 und V8) und Auswertung der letzten 60 CI-Läufe
(7. bis 9. Oktober, GitHub API):
- Der Schnell-Job war in 21 von 60 Läufen rot. Ein roter Schnell-Job meldet sich im Median erst nach 6,6 Minuten
  (grün 5,7 Minuten), weil `gradle --continue` alles zu Ende baut, auch wenn das Kompilieren schon gescheitert ist.
- Alles läuft nacheinander in einem Job. Letzter grüner Lauf (37981736559): Gradle 4 min 3 s, 828 Tasks, 442 Tests
  in 44 Klassen. Die reine Logik (pure, entity, control, boundary, architecture) braucht zusammen etwa 29 s Testzeit,
  die Robolectric- und Compose-Tests (feature, core/data) etwa 145 s, allein `CameraContentTest` 42 s.
- Jeder Lauf lädt im Schritt "Versionen (Info)" Versionslisten und Media3-Archive herunter, die seit dem 7. Oktober
  niemand mehr braucht.
- Der Grund eines roten Laufs steht nicht auf der Lauf-Seite (die Hinweise zeigen nur "exit code 1"), sondern nur in
  `ci-logs-fast/fast.log`.
- Statische Analyse gibt es nur als `lintVital` im Release-Build. 8 von 97 Commits waren Korrekturen kleiner Fehler,
  die erst CI fand.
- `ubuntu-latest` wechselt ab dem 19. Oktober 2026 auf Ubuntu 26 (Hinweis in jedem Lauf); die Emulator-Skripte hängen
  von Systempaketen ab.

## Ziel
Der kurze Lauf meldet früher und mehr: Kompilier- und Logikfehler nach etwa 3 Minuten, statische Analyse bei jedem
Push, den Grund eines roten Laufs direkt auf der Lauf-Seite. Er wird dabei nicht länger. Weil das Repository öffentlich
ist und die Minuten frei sind, wird parallel statt nacheinander geprüft.

## Akzeptanzkriterien
| Nr. | Kriterium (messbar) | Nachweis |
|---|---|---|
| K1 | Job "Kern" (Kompilieren und Tests von pure, entity, control, boundary, architecture, mit Kover) ist im Median über 3 Läufe nach höchstens 3 Minuten fertig. Heute kommt jede Rückmeldung erst mit dem ganzen Schnell-Job (Median grün 5,7, rot 6,6 Minuten) | Zeiten in der Zusammenfassung (K7) |
| K2 | Bis alle Jobs ohne Emulator fertig sind: im Median über 3 Läufe höchstens 5,7 Minuten (heutiger Median grün) | Zusammenfassung |
| K3 | Parallele Jobs: Kern (mit Testlabor-Bericht), Oberfläche (Robolectric, Compose, Roborazzi), Analyse, APK (Debug und Release mit R8). Der Release-Job wartet auf alle und den Emulator | Workflow-Datei, ein Lauf |
| K4 | Ein roter Job bricht die anderen nicht ab; jeder meldet seinen eigenen Befund | Probelauf auf Zweig `probe/s-005` mit absichtlichem Kompilierfehler in `:core:pure`: Kern rot, Oberfläche und Analyse melden trotzdem; Zweig danach gelöscht |
| K5 | Statische Analyse bei jedem Push: detekt und Android Lint für alle Module, je mit Baseline (bestehende Funde blockieren nicht, neue schon). Selbstprüfung: je Werkzeug eine Probe-Datei mit bekanntem Fund; findet das Werkzeug ihn nicht, ist der Analyse-Job rot | Probe-Dateien unter `config/probes/` |
| K6 | Fallen-Prüfung als Skript: Ein Android-Text, der mit Leerzeichen endet und nicht in Anführungszeichen steht, macht den Analyse-Job rot. Mit Probe wie K5 | `.github/scripts/fallen.sh`, Probe |
| K7 | Job "Bericht" schreibt auf die Lauf-Seite (Job-Zusammenfassung) und nach `ci-logs-fast/summary.md`: je Job grün oder rot und Dauer, Zahl der Tests, Name und erste Fehlerzeile jedes roten Tests oder Kompilierfehlers, Laborwerte mit Änderung zum letzten Lauf auf main. Wird eine Messgröße um mehr als 10 % schlechter (Richtung je Messgröße wie bei den Grenzwerten), steht dort eine Warnung; rot bleibt nur ein verletzter Grenzwert | Probelauf rot (K4) und ein grüner Lauf |
| K8 | `ci-logs-fast` schreibt nur noch der Job Bericht (kein Wettlauf zwischen parallelen Jobs); Inhalt wie bisher plus `summary.md` | ein Lauf |
| K9 | "Versionen (Info)" läuft nur noch über "Run workflow" mit eigenem Schalter, nicht bei jedem Push | Workflow-Datei |
| K10 | Runner fest auf `ubuntu-24.04` statt `ubuntu-latest`; der Wechsel auf 26 kommt später bewusst mit eigenem Lauf | Workflow-Datei |
| K11 | Emulator-Regeln bleiben unverändert: welche Änderungen ihn starten, 2.000 oder vor einem Release 5.000 Schritte Zufallsbedienung | Workflow-Datei, Vergleich mit dem Stand davor |

Nachweis für "rot" (`CLAUDE.md` Abschnitt 1 Punkt 4): K4 bis K7 über Probe-Dateien und den Probezweig; K1 und K2 über
die gemessenen Zeiten gegen die heutigen Mediane.

## Nicht Teil dieser Änderung
- Inhalt des Emulator-Jobs und seine Regeln (K11).
- Szenenbibliothek (V3, eigene Spec); sie bekommt später einen eigenen parallelen Job im kurzen Lauf.
- Lokaler Gradle-Lauf für Claude (V1).
- App-Code, Tests der App, Screenshot-Baselines.
- S-004 ist für die Ausrichtung bei Langzeit, Wegrechnen und Fokus-Stacking vorgemerkt (S-003).

## Betroffene Schichten und Regeln
Keine App-Schicht. Dateien: `.github/workflows/ci.yml`, `.github/scripts/` (neu: `fallen.sh`, Skript für die
Zusammenfassung), `build-logic` (detekt und Lint als Convention), `config/detekt/detekt.yml`, Baselines je Modul, Probe-Dateien
unter `config/probes/`. Konsist und die Regeln R1 bis R27 bleiben unverändert.

Nach der Umsetzung anzupassen: `CLAUDE.md` Abschnitt 4 ("schneller Job grün" wird "alle Jobs des kurzen Laufs grün")
und Testkonzept Abschnitt 5, im Projektdokument und im Tab "Testkonzept".

## Architekturprüfung
| Frage | Ergebnis |
|---|---|
| Schicht und Importe | keine App-Änderung; Probe-Dateien liegen außerhalb aller Module und kommen nie in die App |
| Nur Zahlen in Ergebnissen | trifft nicht zu |
| Fehler und Ausweichweg | ein Werkzeug, das ausfällt, macht nur seinen Job rot (K4) |
| Abbruch und Freigaben | trifft nicht zu |
| Bildpuffer, Main-Thread, Speichern | trifft nicht zu |
| neue Ausnahme | keine |

## Risiken und Rückweg
- detekt unterstützt Kotlin 2.4 eventuell noch nicht, weil es neuen Kotlin-Versionen oft hinterherläuft. Scheitert die
  Einrichtung, entfällt detekt in dieser Spec; Lint und Fallen-Skript gelten trotzdem. Das wird im Ergebnis vermerkt.
- Jeder parallele Job richtet Gradle neu ein (etwa 30 bis 60 Sekunden). Der Gradle-Cache von `setup-gradle` mildert
  das, K2 prüft es. Reicht das nicht, werden Oberfläche und APK zu einem Job zusammengelegt.
- Eine große Lint-Baseline verdeckt alte Funde; neue Funde blockieren trotzdem. Alte Funde werden nach und nach abgebaut.
- Rückweg: Revert dieses Commits stellt die alte `ci.yml` wieder her.

## Kosten
Minuten frei (öffentliches Repository). Etwa 4 bis 6 Läufe für Einrichtung und Proben, je etwa 6 Minuten. Kein Release,
kein Gerätetest.

## Ergebnis
Umgesetzt am 10. Oktober 2026 auf dem Zweig `probe/s-005` (5 Probeläufe), danach auf main.

| Nr. | Ergebnis |
|---|---|
| K1 | Kern nach 2,7 / 2,7 / 2,8 / 2,6 min fertig (Probeläufe 2 bis 5); Messung auf main siehe unten |
| K2 | alles nach 4,9 / 6,7 / 6,5 min, solange APK Debug und Release ein Job waren (R8 plus doppeltes Kompilieren, 5 min 51 s Gradle-Zeit); nach der Teilung in zwei Jobs 5,0 und 4,5 min |
| K3 | fünf statt vier parallele Jobs: APK Debug und APK Release getrennt (Abweichung von der Spec, wegen K2) |
| K4 | Probelauf rot (Zweig `probe/s-005-rot`, Lauf 38017341210): roter Test in `:core:pure`, Kompilierfehler in `:feature:gallery`, Falle in `strings.xml`; alle fünf Jobs liefen zu Ende, jeder meldete seinen Befund |
| K5 | detekt 1.23.8 als eigenes Programm (unabhängig von Kotlin 2.4), nur Regeln mit Fehlerbezug, 23 alte Funde in `config/detekt/baseline.xml`; Android Lint für alle Android-Module, 94 alte Funde in 8 Baselines. JVM-Module prüft nur detekt (Lint findet dort kaum etwas). Alle drei Proben in jedem Lauf erkannt |
| K6 | `fallen.sh` erkennt Leerzeichen am Textende; Probe erkannt, im Probelauf rot den echten Fund gemeldet |
| K7 | Zusammenfassung auf der Lauf-Seite und in `ci-logs-fast/summary.md` (Probezweige: `ci-logs-probe`); gleiche Kompilierfehler mehrerer Jobs stehen einmal da. Laborvergleich lokal mit absichtlich verschlechtertem Bericht geprüft (Rauschen +19 % ergibt eine Warnung) |
| K8 | `ci-logs-fast` schreibt nur der Bericht-Job; `fast.log` bleibt als Zusammenfassung aller Protokolle |
| K9, K10, K11 | erfüllt (Workflow-Datei) |

**Nebenbefunde, die der Umbau aufgedeckt hat:**
- Der Architekturtest kam vom 7. Oktober 09:58 bis zum Umbau nur aus dem Build-Cache (`FROM-CACHE`), weil er die gelesenen
  Quelldateien nicht als Eingaben meldete. Er übersah so einen Verstoß gegen R11 (`MutableCoordinateTransformer` im
  Sucher, seit dem 7. Oktober 23:50). Behoben in `architecture/build.gradle.kts`; A1 um den Umrechner erweitert (Arslan,
  10. Oktober), mit Mutationsprobe.
- Aus demselben Grund fehlte der Laborbericht in Läufen, deren Tests aus dem Cache kamen (auch im letzten Lauf auf main
  vor dem Umbau). Behoben in `core/pure/build.gradle.kts`.
- Lint fand unter anderem `UnsafeOptInUsageError` (Camera2-Interop ohne `@OptIn`) und `RestrictedApi`
  (`SurfaceRequest.isServiced`) im Kamera-Adapter. Sie stehen in der Baseline und sollten bei Gelegenheit behoben werden.

**Offen:** Die Probezweige `probe/s-005` und `probe/s-005-rot` lassen sich aus dieser Umgebung nicht löschen (der
Proxy sperrt das Löschen von Zweigen); sie stören nicht.
