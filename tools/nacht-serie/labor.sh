#!/bin/bash
# S-015: Nachtserie (ZIP aus der App) offline durch den Nacht-Kern von :core:pure rechnen, ohne Gradle und ohne Netz.
# Aufruf:  tools/nacht-serie/labor.sh <Nachtserie.zip> <ausgabe.jpg> [Bezeichnung]
# Fuer Varianten: Quellen nach SRC kopieren, dort aendern, SRC=<ordner>/kotlin tools/nacht-serie/labor.sh ...
# Die Serie und das Ergebnis bleiben ausserhalb des Repositorys (CLAUDE.md Abschnitt 7).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC=${SRC:-$ROOT/core/pure/src/main/kotlin}
LIB=$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -1)
jar() { ls "$LIB"/$1-*.jar | sort -V | tail -1; }
STDLIB=$(jar kotlin-stdlib)
CP="$(jar kotlin-compiler-embeddable):$STDLIB:$(jar trove4j):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
OUT=$(mktemp -d)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-reflect -d "$OUT" -cp "$STDLIB" $(find "$SRC" -name '*.kt') "$ROOT/tools/nacht-serie/Labor.kt" 2>&1 | grep -v '^warning' || true
java -Xmx3g -cp "$OUT:$STDLIB" LaborKt "$1" "$2" "${3:-Labor}"
rm -rf "$OUT"
