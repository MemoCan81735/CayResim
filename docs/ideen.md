# Funktionsliste

Neue Funktionen, aufgenommen mit Arslan am 10. Oktober 2026. Jede bekommt vor dem Bau eine eigene Spec mit Freigabe
(CLAUDE.md Abschnitt 1). Nach V9 ist höchstens ein Modus gleichzeitig in Arbeit; zuerst wird die Nacht fertig.

| Nr. | Funktion | Was sie tut | Baut auf | Aufwand | Status |
|---|---|---|---|---|---|
| F1 | Stativ-Erkennung | Lagesensor (Gyroskop, ohne Berechtigung) erkennt ein ruhig liegendes Handy; dann längere Einzelbilder und mehr davon (Samsung belichtet so bis 8 s). Freihand bleibt wie bisher | Nacht-Kern, NightPlan | mittel | offen, nach der Vorhang-Messung |
| F2 | Dunkelbild-Kalibrierung | Finger auf die Linse, die App misst das echte Sensorrauschen je ISO und speichert es; der Nachtmodus rechnet mit gemessenen statt geschätzten Werten | Declip, Selbsttest | mittel (neues Speichern von Daten, R26) | offen, nach der Vorhang-Messung |
| F3 | Lichtspuren | je Pixel der hellste Wert der Serie: Autolichter als Streifen, Lichtmalerei; Vorschau baut sich live auf | Serien, Ausrichtung | klein | offen |
| F4 | Bewegungsfolge | ein bewegtes Motiv mehrfach im selben Bild (Stroboskop-Foto); Hintergrund aus dem Median, Motiv aus dem Unterschied je Bild | Wegrechnen (Median) | mittel | offen |
| F5 | Seidenwasser mit scharfem Motiv | Bewegtes wird weich wie bei Langzeit, das Motiv bleibt scharf; Maske aus dem Unterschied zwischen Mittel und Median | Langzeit, Wegrechnen | mittel | offen |
| F6 | Super-Auflösung freihand | Bruchteile von Pixeln aus dem Zittern der Hand nutzen, z. B. schärferer 6-fach-Zoom aus der 3-fach-Kamera | Ausrichtung (dann mit Bruchteilen) | groß | offen |
| F7 | Fokus-Hervorhebung und Zebra | im Pro-Modus scharfe Kanten farbig, überbelichtete Stellen gestreift | Pro-Modus, Sucher | klein | offen |

Vorgeschlagene Reihenfolge: F1 und F2 (machen die Nacht fertig), dann F3, danach nach Wunsch.
