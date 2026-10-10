#!/usr/bin/env bash
# S-005 Analyse-Job: Fallen-Skript, detekt und Android Lint, jedes mit Selbstpruefung an einer Probe.
# Schreibt nach out/: analyse.txt (Kurzfassung), fallen.txt, detekt.txt, lint.log, lint/*.xml und
# baselines/ (Lint-Baselines, die in diesem Lauf neu entstanden sind und uebernommen werden muessen).
# Exit 1, wenn ein Werkzeug einen neuen Fund meldet, seine Probe nicht findet oder selbst scheitert.
set -uo pipefail
mkdir -p out/lint out/baselines
fail=0
say() { echo "$*" | tee -a out/analyse.txt; }

# 1. Fallen (CLAUDE.md Abschnitt 3)
if bash .github/scripts/fallen.sh app core feature > out/fallen.txt 2>&1; then
  say "Fallen: keine Funde"
else
  say "Fallen: $(grep -c . out/fallen.txt) Funde"; fail=1
fi
if bash .github/scripts/fallen.sh config/probes/fallen > /dev/null 2>&1; then
  say "Fallen-Probe: NICHT erkannt"; fail=1
else
  say "Fallen-Probe: erkannt"
fi

# 2. detekt (eigenes Programm, unabhaengig von der Kotlin-Version des Projekts)
DETEKT_VERSION=1.23.8
DETEKT_SHA256=2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1
if curl -sSfL -o detekt.jar "https://github.com/detekt/detekt/releases/download/v${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar" \
   && echo "${DETEKT_SHA256}  detekt.jar" | sha256sum -c - > /dev/null; then
  D=(java -jar detekt.jar --config config/detekt/detekt.yml --build-upon-default-config)
  "${D[@]}" --input config/probes/detekt --report txt:out/detekt-probe.txt > /dev/null 2>&1
  if grep -q ExplicitGarbageCollectionCall out/detekt-probe.txt 2> /dev/null; then
    say "detekt-Probe: erkannt"
  else
    say "detekt-Probe: NICHT erkannt"; fail=1
  fi
  # Selbstpruefung der Zaehlung: der Probe-Fund ist in funde.txt nicht erlaubt und muss gemeldet werden
  if python3 -I .github/scripts/detekt_zaehlung.py out/detekt-probe.txt config/detekt/funde.txt > /dev/null 2>&1; then
    say "detekt-Zaehlung: Probe NICHT gemeldet"; fail=1
  fi
  "${D[@]}" --input app,core,feature --excludes "**/build/**,**/test/**,**/androidTest/**,**/testFixtures/**" \
    --report txt:out/detekt-alle.txt > out/detekt.log 2>&1
  rc=$?
  if [ "$rc" -ne 0 ] && [ "$rc" -ne 2 ]; then
    say "detekt: Programmfehler (Exit $rc, siehe detekt.log)"; fail=1
  elif python3 -I .github/scripts/detekt_zaehlung.py out/detekt-alle.txt config/detekt/funde.txt > out/detekt.txt 2> out/detekt-hinweise.txt; then
    say "detekt: keine neuen Funde"
  else
    say "detekt: $(grep -c . out/detekt.txt) Regeln mit mehr Funden als erlaubt (funde.txt)"; fail=1
  fi
else
  say "detekt: Herunterladen oder Pruefsumme gescheitert"; fail=1
fi

# 3. Android Lint, mit dem Probe-Modul in einem Aufruf
touch .lint-start
gradle --continue lintDebug -Pprobes > out/lint.log 2>&1
lint_rc=$?
failed_modules=$(grep -oE "Execution failed for task '[^']+'" out/lint.log | sed -E "s/.*task '(.*):[^:]+'/\1/" | sort -u)
while read -r f; do
  m=${f#./}; m=${m%%/build/*}; cp "$f" "out/lint/$(echo "$m" | tr '/' '_').xml"
done < <(find . -path ./out -prune -o -path '*/build/reports/lint-results-debug.xml' -print)
if echo "$failed_modules" | grep -qx ":probe-lint" && grep -q 'id="HardcodedText"' out/lint/config_probes_lint.xml 2> /dev/null; then
  say "Lint-Probe: erkannt"
else
  say "Lint-Probe: NICHT erkannt"; fail=1
fi
new_baselines=$(find app core feature -name lint-baseline.xml -newer .lint-start 2> /dev/null)
for b in $new_baselines; do mkdir -p "out/baselines/$(dirname "$b")"; cp "$b" "out/baselines/$b"; done
real_failed=$(echo "$failed_modules" | grep -vx ":probe-lint" | grep . || true)
# Jedes Modul mit Baseline muss ein Ergebnis haben; sonst hat Lint dort nicht geprueft (unabhaengige Pruefung S-005)
missing=""
while read -r b; do
  m=$(dirname "$b"); [ -f "out/lint/$(echo "$m" | tr '/' '_').xml" ] || missing="$missing $m"
done < <(find app core feature -name lint-baseline.xml)
if [ -n "$new_baselines" ]; then
  say "Lint: Baseline neu erzeugt in $(echo "$new_baselines" | wc -l) Modulen, muss uebernommen werden (out/baselines)"; fail=1
elif [ -n "$missing" ]; then
  say "Lint: kein Ergebnis fuer$missing (Gradle Exit $lint_rc, siehe lint.log)"; fail=1
elif [ -z "$failed_modules" ] && [ "$lint_rc" -ne 0 ]; then
  say "Lint: Gradle scheiterte ohne Taskangabe (Exit $lint_rc, siehe lint.log)"; fail=1
elif [ -n "$real_failed" ]; then
  say "Lint: neue Funde oder Fehler in $(echo "$real_failed" | tr '\n' ' ')"; fail=1
else
  say "Lint: keine neuen Funde"
fi

exit $fail
