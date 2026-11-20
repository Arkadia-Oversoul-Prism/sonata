#!/usr/bin/env bash
# Narration core — compile and run the full JUnit suite.
#
# This module is pure JVM: no Android framework, no SDK. It is therefore the one
# part of the product whose tests can be executed anywhere, including CI, and its
# result is the strongest test evidence the project has.
#
# Exit code is the JUnit exit code, so a failing test fails the build.
set -euo pipefail

KOTLINC=${KOTLINC:-kotlinc}
LIB=${LIB:-/opt/tools/lib}
STD=${STD:-$(dirname "$(command -v "$KOTLINC")")/../lib}
OUT=${OUT:-build/test-classes}

rm -rf "$OUT"; mkdir -p "$OUT"

mapfile -t MAIN < <(find src/main/kotlin -name '*.kt' | sort)
mapfile -t TEST < <(find src/test/kotlin -name '*.kt' | sort)
echo "narration_core_main_sources=${#MAIN[@]}"
echo "narration_core_test_sources=${#TEST[@]}"

"$KOTLINC" -nowarn -jvm-target 17 \
  -cp "$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
  -d "$OUT" "${MAIN[@]}" "${TEST[@]}"
echo "COMPILE_EXIT=0"

CP="$OUT:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$STD/kotlin-stdlib.jar:$STD/kotlin-stdlib-jdk8.jar:$STD/kotlin-stdlib-jdk7.jar"
mapfile -t CLASSES < <(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "test_classes=${#CLASSES[@]}"
java -cp "$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
