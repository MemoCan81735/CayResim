#!/usr/bin/env bash
# S-005: bekannte Fallen aus CLAUDE.md Abschnitt 3, soweit ein Skript sie sicher erkennt.
# Aufruf: fallen.sh <Ordner>...  Ausgabe: je Fund eine Zeile "Datei:Zeile: Text"; Exit 1, wenn es Funde gibt.
set -euo pipefail
python3 -I - "$@" <<'PY'
import pathlib, re, sys
found = 0
# Android kuerzt Leerzeichen am Ende eines Texts, wenn er nicht in Anfuehrungszeichen steht (Fehlerliste 9.10.).
pat = re.compile(r'<(string|item)\b[^>]*>([^<]*?)</\1>', re.S)
for root in sys.argv[1:]:
    for f in sorted(pathlib.Path(root).rglob('*.xml')):
        if '/build/' in str(f) or '/values' not in str(f.parent):
            continue
        text = f.read_text(encoding='utf-8')
        for m in pat.finditer(text):
            body = m.group(2)
            if body and body[-1] in ' \t' and not body.rstrip().endswith('"'):
                line = text.count('\n', 0, m.start()) + 1
                print(f'{f}:{line}: Leerzeichen am Textende ohne Anfuehrungszeichen verschwindet: {m.group(0).strip()[:80]}')
                found += 1
sys.exit(1 if found else 0)
PY
