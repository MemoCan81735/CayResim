#!/bin/bash
# Lokaler Lauf von :core:pure, :core:boundary (mit Fakes) und :core:control ohne Gradle und ohne Netz (CLAUDE.md, V1).
# Kompiliert alle Hauptquellen dieser Module und die genannten Testklassen aus core/*/src/test.
# Aufruf:  tools/run-core-tests.sh MicTestUseCaseTest NightUseCaseTest   (Kurznamen, Paket app.cayresim.core.control)
#          tools/run-core-tests.sh app.cayresim.core.boundary.FakeCameraBoundaryContractTest
# Grenzen: runTest laeuft mit echter Zeit (tools/pure-tests/CoroutinesTestStub.kt), Turbine fehlt.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LIB=$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -1)
[ -n "$LIB" ] || { echo "Keine Gradle-Installation unter /opt gefunden" >&2; exit 2; }
jar() { ls "$LIB"/$1-*.jar | sort -V | tail -1; }
STD=$(jar kotlin-stdlib); CO=$(jar kotlinx-coroutines-core-jvm); INJ=$(ls "$LIB"/javax.inject-*.jar | head -1)
CP="$(jar kotlin-compiler-embeddable):$STD:$(jar trove4j):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar annotations):$CO"
OUT=${OUT:-$ROOT/build/core-tests}; rm -rf "$OUT"; mkdir -p "$OUT"
[ $# -gt 0 ] || { echo "Testklassen angeben" >&2; exit 2; }
CLASSES=$(for c in "$@"; do case $c in *.*) echo "$c";; *) echo "app.cayresim.core.control.$c";; esac; done)
TSRC=$(for c in $CLASSES; do find "$ROOT/core" -path '*src/test*' -name "${c##*.}.kt"; done)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-reflect -Xallow-kotlin-package -d "$OUT" -cp "$STD:$CO:$INJ" \
  $(find "$ROOT/core/pure/src/main/kotlin" "$ROOT/core/boundary/src/main/kotlin" "$ROOT/core/boundary/src/testFixtures/kotlin" "$ROOT/core/control/src/main/kotlin" -name '*.kt') \
  $TSRC "$ROOT/tools/pure-tests/KotlinTestStub.kt" "$ROOT/tools/pure-tests/Runner.kt" "$ROOT/tools/pure-tests/CoroutinesTestStub.kt" 2>&1 | grep -v '^warning' || true
[ -f "$OUT/Runner.class" ] || { echo "Kompilieren fehlgeschlagen" >&2; exit 1; }
cd "$OUT" && java -cp "$OUT:$STD:$CO:$INJ" Runner $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
