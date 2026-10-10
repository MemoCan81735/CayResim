"""Grobe Strukturpruefung fuer Kotlin-Dateien, die lokal nicht kompiliert werden (Android-Module, V1 offen).

Prueft je Datei, ob runde, eckige und geschweifte Klammern aufgehen, ohne Text in Zeichenketten, Zeichen und
Kommentaren zu zaehlen (Vorlagen ${...} werden mitgezaehlt). Anlass: S-011, eine ueberzaehlige Klammer im
Serien-Adapter fiel erst in CI auf (docs/fehlerliste.md).

Aufruf:  python3 -I tools/check-kotlin-structure.py [Dateien ...]
Ohne Dateien: alle .kt-Dateien, die sich gegenueber main aendern (git diff main...HEAD und Arbeitskopie).
Exit 1 bei Funden.
"""
import pathlib
import subprocess
import sys

PAIRS = {")": "(", "]": "[", "}": "{"}


def check(text):
    """Liefert eine Liste von (Zeile, Meldung)."""
    found = []
    stack = []  # (Zeichen, Zeile); "$" markiert eine Vorlage ${ in einer Zeichenkette
    modes = []  # Zeichenketten-Ebenen: '"' oder '"""'
    i, line, n = 0, 1, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
        if modes and modes[-1] != "code":
            raw = modes[-1] == '"""'
            if not raw and c == "\\":
                i += 2
                continue
            if text.startswith("${", i):
                stack.append(("$", line)); modes.append("code"); i += 2
                continue
            if raw and text.startswith('"""', i):
                j = i + 3
                while j < n and text[j] == '"':
                    j += 1
                modes.pop(); i = j
                continue
            if not raw and c == '"':
                modes.pop(); i += 1
                continue
            i += 1
            continue
        # Code (auch innerhalb ${...})
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1; i += 2
                elif text.startswith("*/", i):
                    depth -= 1; i += 2
                else:
                    if text[i] == "\n":
                        line += 1
                    i += 1
            continue
        if text.startswith('"""', i):
            modes.append('"""'); i += 3
            continue
        if c == '"':
            modes.append('"'); i += 1
            continue
        if c == "'":
            j = i + 1
            if j < n and text[j] == "\\":
                j += 2
                while j < n and text[j] != "'":
                    j += 1
            else:
                j += 1
            i = j + 1
            continue
        if c in "([{":
            stack.append((c, line))
        elif c in ")]}":
            if c == "}" and stack and stack[-1][0] == "$":
                stack.pop(); modes.pop()
            elif not stack or stack[-1][0] != PAIRS[c]:
                found.append((line, f"'{c}' ohne passende oeffnende Klammer" + (f" (offen: '{stack[-1][0]}' aus Zeile {stack[-1][1]})" if stack else "")))
                if stack and stack[-1][0] != "$":
                    stack.pop()
            else:
                stack.pop()
        i += 1
    for ch, ln in stack:
        found.append((ln, f"'{ch}' wird nicht geschlossen"))
    if modes and modes[-1] != "code":
        found.append((line, "Zeichenkette wird nicht geschlossen"))
    return found


def changed_files():
    out = subprocess.run(["git", "diff", "--name-only", "main...HEAD"], capture_output=True, text=True).stdout.split()
    out += subprocess.run(["git", "diff", "--name-only", "HEAD"], capture_output=True, text=True).stdout.split()
    out += subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], capture_output=True, text=True).stdout.split()
    return sorted({f for f in out if f.endswith(".kt") and pathlib.Path(f).exists()})


def main():
    files = sys.argv[1:] or changed_files()
    bad = 0
    for f in files:
        for ln, msg in check(pathlib.Path(f).read_text(encoding="utf-8")):
            print(f"{f}:{ln}: {msg}"); bad += 1
    print(f"Struktur: {len(files)} Dateien geprueft, {bad} Funde")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
