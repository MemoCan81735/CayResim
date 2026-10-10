#!/bin/bash
# Lokale Architekturpruefung ohne Gradle und ohne Netz (CLAUDE.md, V1): Mutationsproben und alle Hauptdateien
# gegen ArchitectureRules. Ersetzt nicht den Konsist-Test in CI, findet aber Verstoesse vor dem Push.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LIB=$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -1)
[ -n "$LIB" ] || { echo "Keine Gradle-Installation unter /opt gefunden" >&2; exit 2; }
jar() { ls "$LIB"/$1-*.jar | sort -V | tail -1; }
STD=$(jar kotlin-stdlib)
CP="$(jar kotlin-compiler-embeddable):$STD:$(jar trove4j):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
OUT=${OUT:-$ROOT/build/architecture-check}; rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-reflect -Xallow-kotlin-package -d "$OUT" -cp "$STD" \
  "$ROOT/architecture/src/main/kotlin/app/cayresim/architecture/ArchitectureRules.kt" \
  "$ROOT/architecture/src/test/kotlin/app/cayresim/architecture/MutationProbesTest.kt" \
  "$ROOT/tools/pure-tests/KotlinTestStub.kt" "$ROOT/tools/pure-tests/Runner.kt" "$ROOT/tools/pure-tests/ProjectCheck.kt" 2>&1 | grep -v '^warning' || true
[ -f "$OUT/Runner.class" ] || { echo "Kompilieren fehlgeschlagen" >&2; exit 1; }
java -cp "$OUT:$STD" Runner app.cayresim.architecture.MutationProbesTest app.cayresim.architecture.NamingFalsePositiveTest 2>&1 | grep -v JAVA_TOOL_OPTIONS
out=$(java -cp "$OUT:$STD" ProjectCheck "$ROOT" 2>&1 | grep -v JAVA_TOOL_OPTIONS); echo "$out"
echo "$out" | head -1 | grep -q "Verstoesse 0, ohne Schicht 0"
