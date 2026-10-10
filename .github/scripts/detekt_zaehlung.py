#!/usr/bin/env python3
"""S-005: detekt-Funde je Regel und Datei gegen die erlaubte Anzahl pruefen.

Eine detekt-Baseline fasst gleiche Funde ohne Zeilennummer zusammen; ein weiteres verschlucktes catch in derselben
Funktion fiel so nicht auf (unabhaengige Pruefung S-005). Die Zaehlung erkennt jeden Fund mehr als bisher.
Aufruf: detekt_zaehlung.py <detekt-Bericht.txt> <funde.txt>   Exit 1, wenn eine Anzahl steigt oder neu ist.
funde.txt: je Zeile "Anzahl Regel Datei" (Datei relativ zum Repository), '#' leitet Kommentare ein.
"""
import collections
import os
import re
import sys

report, allowed_path = sys.argv[1], sys.argv[2]
root = os.getcwd().rstrip("/") + "/"
found = collections.Counter()
for line in open(report, encoding="utf-8"):
    m = re.match(r"(\w+) - .* at (\S+?):\d+:\d+", line)
    if m:
        found[(m.group(1), m.group(2).replace(root, ""))] += 1
allowed = collections.Counter()
for line in open(allowed_path, encoding="utf-8"):
    parts = line.split("#")[0].split()
    if len(parts) == 3:
        allowed[(parts[1], parts[2])] = int(parts[0])
worse = [(k, n, allowed[k]) for k, n in sorted(found.items()) if n > allowed[k]]
better = [(k, allowed[k], found[k]) for k in sorted(allowed) if found[k] < allowed[k]]
for (rule, path), n, a in worse:
    print(f"{path}: {rule} {n}-mal, erlaubt {a}")
for (rule, path), a, n in better:
    print(f"Hinweis: {path}: {rule} nur noch {n}-mal statt {a}, funde.txt kann sinken", file=sys.stderr)
sys.exit(1 if worse else 0)
