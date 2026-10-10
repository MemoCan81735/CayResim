#!/bin/bash
# Lokaler Lauf der Tests von :core:pure ohne Gradle und ohne Netz (CLAUDE.md, V1 teilweise).
# Nutzt den Kotlin-Compiler, der in der Gradle-Installation liegt, und einen kleinen Ersatz fuer kotlin.test.
# Aufruf:  tools/run-pure-tests.sh                 alle Testklassen
#          tools/run-pure-tests.sh NightMergeTest   einzelne Klassen (Kurzname)
#          FILTER=wegrechnen tools/run-pure-tests.sh QualityLabTest   nur Tests, deren Name das enthaelt
# Gilt nur fuer reine Rechnung (:core:pure). Android, Compose und Screenshots laufen weiter nur in CI.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LIB=$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -1)
[ -n "$LIB" ] || { echo "Keine Gradle-Installation unter /opt gefunden" >&2; exit 2; }
jar() { ls "$LIB"/$1-*.jar | sort -V | tail -1; }
STDLIB=$(jar kotlin-stdlib)
CP="$(jar kotlin-compiler-embeddable):$STDLIB:$(jar trove4j):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
OUT=${OUT:-$ROOT/build/pure-tests}
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes" "$OUT/run"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-reflect -Xallow-kotlin-package -d "$OUT/classes" -cp "$STDLIB" \
  $(find "$ROOT/core/pure/src/main/kotlin" "$ROOT/core/pure/src/test/kotlin" -name '*.kt') \
  "$ROOT/tools/pure-tests/KotlinTestStub.kt" "$ROOT/tools/pure-tests/Runner.kt" ${EXTRA:-} 2>&1 | grep -v '^warning' || true
[ -f "$OUT/classes/Runner.class" ] || { echo "Kompilieren fehlgeschlagen" >&2; exit 1; }
if [ $# -eq 0 ]; then
  CLASSES=$(grep -rhoE '^class [A-Za-z0-9]+Test' "$ROOT/core/pure/src/test/kotlin" | awk '{print "app.cayresim.core.pure."$2}' | sort -u)
else
  CLASSES=$(for c in "$@"; do case $c in *.*) echo "$c";; *) echo "app.cayresim.core.pure.$c";; esac; done)
fi
# Labortests schreiben ihre Berichte relativ zum Arbeitsordner (build/quality-report.md)
cd "$OUT/run" && java -Xmx2g -cp "$OUT/classes:$STDLIB" Runner $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
