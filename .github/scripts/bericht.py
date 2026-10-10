#!/usr/bin/env python3
"""S-005 Bericht: fasst den kurzen Lauf auf einer Seite zusammen (Lauf-Seite und ci-logs-fast/summary.md).

Aufruf: bericht.py <Eingang> <vorheriger quality-report.md oder ""> <Ausgabe summary.md>
<Eingang> enthaelt je Job einen Ordner out-<job> (Artefakte der Jobs).
Umgebung: NEEDS_JSON (toJSON(needs)), GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_SHA, GITHUB_REF_NAME,
PREV_LABEL (Commit des Vergleichsberichts).
"""
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

JOBS = [  # (needs-Schluessel, Name im Workflow, Ordner)
    ("kern", "Kern (JVM)", "out-kern"),
    ("oberflaeche", "Oberflaeche (Robolectric)", "out-oberflaeche"),
    ("analyse", "Analyse", "out-analyse"),
    ("apk-debug", "APK Debug", "out-apk-debug"),
    ("apk-release", "APK Release (R8)", "out-apk-release"),
]
RESULT = {"success": "gruen", "failure": "rot", "cancelled": "abgebrochen", "skipped": "uebersprungen"}
WORKDIR = re.compile(r"(file://)?/home/runner/work/[^/]+/[^/]+/")
WARN_SHARE = 0.10  # S-005 K7: Warnung ab 10 % Verschlechterung
LOWER_IS_BETTER = {"Rauschen", "Kante", "angepasste Kante", "Geist", "Schwarz"}
HIGHER_IS_BETTER = {"Helligkeit", "Kontrast Grau minus Schwarz"}


def num(s):
    s = s.strip().replace(",", ".")
    try:
        return float(s)
    except ValueError:
        return None


def de(x, digits=1):
    return f"{x:.{digits}f}".replace(".", ",")


def gh_json(path):
    try:
        out = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=60, check=True).stdout
        return json.loads(out)
    except Exception:  # Zeiten sind nur Zusatz; ohne API bleibt der Bericht vollstaendig
        return None


def parse_time(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None


def job_times():
    repo, run = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_RUN_ID")
    run_info = gh_json(f"repos/{repo}/actions/runs/{run}") or {}
    jobs = gh_json(f"repos/{repo}/actions/runs/{run}/jobs?per_page=100") or {}
    start = parse_time(run_info.get("run_started_at"))
    times = {}
    for j in jobs.get("jobs", []):
        a, b = parse_time(j.get("started_at")), parse_time(j.get("completed_at"))
        if a and b and start:
            times[j["name"]] = ((b - a).total_seconds() / 60, (b - start).total_seconds() / 60)
    return times


def junit(folder):
    total = skipped = 0
    failures = []
    for f in sorted(folder.rglob("*.xml")):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            total += int(s.get("tests", 0))
            skipped += int(s.get("skipped", 0))
            for case in s.findall("testcase"):
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is not None:
                    msg = (bad.get("message") or bad.text or "").strip().splitlines()
                    cls = (case.get("classname") or "").split(".")[-1]
                    failures.append(f"`{cls} > {case.get('name')}`: {msg[0][:200] if msg else 'ohne Meldung'}")
    return total, skipped, failures


def gradle_problems(log_text):
    compile_errors = [WORKDIR.sub("", line[3:]).strip() for line in log_text.splitlines() if line.startswith("e: ")]
    wrong, lines = [], log_text.splitlines()
    for i, line in enumerate(lines):
        if line.strip() == "* What went wrong:":
            for nxt in lines[i + 1:i + 4]:
                if nxt.strip():
                    wrong.append(WORKDIR.sub("", nxt.strip())[:200])
                    break
    return list(dict.fromkeys(compile_errors)), list(dict.fromkeys(wrong))


def lint_issues(folder):
    issues = []
    for f in sorted(folder.glob("*.xml")):
        if f.name.startswith("config_probes"):
            continue
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for issue in root.findall("issue"):
            if issue.get("id", "").startswith("LintBaseline"):
                continue
            loc = issue.find("location")
            where = WORKDIR.sub("", f"{loc.get('file')}:{loc.get('line', '?')}") if loc is not None else "?"
            issues.append(f"`{issue.get('id')}` {where}: {issue.get('message', '')[:160]}")
    return issues


def report_tables(text):
    """Werte des Nacht-Kerns aus den Markdown-Tabellen: {(Zeile, Spalte): Zahl}."""
    values, lines, i = {}, text.splitlines(), 0
    while i < len(lines):
        if lines[i].startswith("|") and i + 1 < len(lines) and set(lines[i + 1].replace("|", "").strip()) <= set("-: "):
            head = [c.strip() for c in lines[i].strip("|").split("|")]
            i += 2
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip("|").split("|")]
                row = dict(zip(head, cells))
                if row.get("Verfahren") == "Nacht-Kern":
                    for col in head[2:]:
                        v = num(row.get(col, ""))
                        if v is not None:
                            values[(row[head[0]], col)] = v
                elif "Nacht-Kern" in row:
                    v = num(row["Nacht-Kern"])
                    if v is not None:
                        values[(row[head[0]], row[head[0]])] = v
                i += 1
        else:
            i += 1
    return values


def other_lines(text):
    return [l for l in text.splitlines() if l.strip() and not l.startswith("|") and not l.startswith("#")]


def compare(prev_text, cur_text):
    rows, warnings = [], 0
    if not prev_text or not cur_text:
        return rows, warnings, []
    old, new = report_tables(prev_text), report_tables(cur_text)
    for key in sorted(set(old) & set(new)):
        a, b = old[key], new[key]
        if a == b:
            continue
        name, col = key
        measure = col if col in LOWER_IS_BETTER | HIGHER_IS_BETTER else name
        share = (b - a) / max(abs(a), 1e-9)
        worse = (measure in LOWER_IS_BETTER and share > WARN_SHARE) or (measure in HIGHER_IS_BETTER and share < -WARN_SHARE)
        warnings += worse
        label = name if name == col else f"{name}, {col}"
        rows.append(f"| {label} | {a:g} | {b:g} | {share * 100:+.0f} % | {'Warnung: schlechter' if worse else ''} |")
    changed_text = [f"- vorher: {o}\n  jetzt: {n}" for o, n in zip(other_lines(prev_text), other_lines(cur_text)) if o != n]
    return rows, warnings, changed_text[:10]


def main():
    src, prev_path, out_path = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3])
    needs = json.loads(os.environ.get("NEEDS_JSON", "{}"))
    times = job_times()
    sha = os.environ.get("GITHUB_SHA", "")[:7]
    ref = os.environ.get("GITHUB_REF_NAME", "")
    results = {k: needs.get(k, {}).get("result", "unbekannt") for k, _, _ in JOBS}
    all_green = all(r == "success" for r in results.values())

    md = [f"# Kurzer Lauf {sha} auf {ref}: {'gruen' if all_green else 'rot'}", ""]
    md += ["| Job | Ergebnis | Dauer | fertig nach Laufstart |", "|---|---|---|---|"]
    finish = []
    for key, name, _ in JOBS:
        d = times.get(name)
        if d:
            finish.append(d[1])
        md.append(f"| {name} | {RESULT.get(results[key], results[key])} | {de(d[0]) + ' min' if d else '?'} | "
                  f"{de(d[1]) + ' min' if d else '?'} |")
    kern = times.get("Kern (JVM)")
    if kern and finish:
        md += ["", f"Kern fertig nach {de(kern[1])} min (Ziel hoechstens 3), alle Jobs nach {de(max(finish))} min "
                   f"(Ziel hoechstens 5,7)."]

    total = skipped = 0
    failures, compile_errors, wrong = [], {}, []
    for key, name, folder in JOBS:
        d = src / folder
        t, s, f = junit(d / "results") if (d / "results").exists() else (0, 0, [])
        total, skipped = total + t, skipped + s
        failures += f
        for log in d.glob("*.log"):
            c, w = gradle_problems(log.read_text(encoding="utf-8", errors="replace"))
            for e in c:
                compile_errors.setdefault(e, []).append(name)
            if results[key] != "success":
                wrong += [f"{name}: {w_}" for w_ in w]
    md += ["", f"Tests: {total}, rot: {len(failures)}, uebersprungen: {skipped}"]
    if failures:
        md += ["", "## Rote Tests", ""] + [f"- {f}" for f in failures[:20]]
    if compile_errors:
        md += ["", "## Kompilierfehler", ""] + [f"- {e} ({', '.join(dict.fromkeys(j))})" for e, j in list(compile_errors.items())[:10]]
    if wrong and not failures and not compile_errors:
        md += ["", "## Gradle meldet", ""] + [f"- {w}" for w in list(dict.fromkeys(wrong))[:10]]

    a = src / "out-analyse"
    md += ["", "## Analyse", ""]
    summary = a / "analyse.txt"
    md += [f"- {l}" for l in summary.read_text(encoding="utf-8").splitlines()] if summary.exists() else ["- kein Ergebnis"]
    detail = []
    if (a / "fallen.txt").exists():
        detail += [WORKDIR.sub("", l) for l in (a / "fallen.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
    if (a / "detekt.txt").exists():
        detail += [WORKDIR.sub("", l) for l in (a / "detekt.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
    if (a / "lint").exists() and results["analyse"] != "success":
        detail += lint_issues(a / "lint")
    if detail:
        md += ["", "Neue Funde (hoechstens 15):", ""] + [f"- {d}" for d in detail[:15]]

    cur = src / "out-kern" / "quality-report.md"
    cur_text = cur.read_text(encoding="utf-8") if cur.exists() else ""
    prev_text = pathlib.Path(prev_path).read_text(encoding="utf-8") if prev_path and pathlib.Path(prev_path).exists() else ""
    rows, warnings, changed_text = compare(prev_text, cur_text)
    label = os.environ.get("PREV_LABEL", "den letzten Lauf auf main")
    md += ["", f"## Testlabor gegen {label}", ""]
    if not cur_text:
        md.append("Kein Laborbericht in diesem Lauf.")
    elif not prev_text:
        md.append("Kein frueherer Bericht zum Vergleich.")
    elif not rows and not changed_text:
        md.append("Alle Werte des Nacht-Kerns unveraendert.")
    else:
        if warnings:
            md.append(f"**{warnings} Warnungen:** Messgroessen mehr als 10 % schlechter. Rot bleibt nur ein verletzter Grenzwert.")
            md.append("")
        if rows:
            md += ["| Wert (Nacht-Kern) | vorher | jetzt | Aenderung | |", "|---|---|---|---|---|"] + rows
        if changed_text:
            md += ["", "Weitere geaenderte Zeilen:", ""] + changed_text
    out_path.write_text("\n".join(md) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
