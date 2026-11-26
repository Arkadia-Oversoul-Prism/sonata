#!/usr/bin/env bash
# Sonata — the single CI entrypoint, run from the repository root.
#
#   ./ci/run-ci.sh            run both jobs, write ci-evidence.log
#   ./ci/run-ci.sh --check    run both jobs, leave ci-evidence.log alone
#
# WHY THIS IS TRACKED
# The Stage 7b candidate originally cited ci-evidence.log as its proof, but the
# harness that produced it lived under build/, which .gitignore excludes. A
# reviewer checking out the candidate SHA could not regenerate the evidence.
# Stage 7a's B1 finding was "the reviewed artifact was not the shipped
# artifact"; an untracked harness is the same defect one level down. The
# harness now lives in ci/ and is part of the tree it measures.
#
# WHAT RUNS
#   job 1  app core        the Android-independent sources of app/src
#   job 2  narration core  the whole narration-core module
# Both are pure JVM. The Android Gradle build (assembleRelease/bundleRelease),
# lint and the instrumented device tests need the Android SDK and a physical
# device. Neither exists in the environment that prepared this candidate, so
# they are recorded as NOT RUN — never as passing.
#
# Exit code is non-zero if either job fails, so a red run is visibly red.
set -uo pipefail

cd "$(dirname "$0")/.."

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

# Toolchain resolution. On a GitHub runner kotlinc is on PATH; in the sandbox
# that prepared this candidate it lives under /opt/tools. Resolve in that order
# so the same script works in both places without editing.
if [ -z "${KOTLINC:-}" ]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC=$(command -v kotlinc)
  else
    KOTLINC=/opt/tools/kotlinc/bin/kotlinc
  fi
fi
LIB=${LIB:-/opt/tools/lib}
STD=${STD:-$(dirname "$KOTLINC")/../lib}
export KOTLINC LIB STD

EV=ci-evidence.log
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

SHA=$(git rev-parse HEAD)
TREE=$(git rev-parse HEAD^{tree})
BRANCH=$(git rev-parse --abbrev-ref HEAD)
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

CP_JARS="$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar"
CP_JARS="$CP_JARS:$LIB/kotlinx-coroutines-core-jvm-1.7.3.jar:$LIB/kotlinx-coroutines-test-jvm-1.7.3.jar"
CP_JARS="$CP_JARS:$LIB/room-common-2.6.1.jar:$LIB/annotation-1.7.1.jar"
CP_JARS="$CP_JARS:$LIB/okhttp-4.12.0.jar:$LIB/okio-jvm-3.6.0.jar:$LIB/json-20240303.jar"

# ------------------------------------------------------------------ preflight
#
# Every classpath jar must be a real zip. A Maven mirror that rate-limits a
# shared runner IP answers with a short error page and HTTP 200, and curl writes
# it to disk as if it were a jar. kotlinc then fails with a wall of unresolved
# references and the harness reports "COMPILE FAILED" — which reads as a code
# defect and is not one. This check names the real cause and stops.
#
# It is here rather than only in the workflow because the harness is the thing
# that claims to have verified the tree. A harness that cannot tell a broken
# toolchain from broken code is not evidence.
BAD_JARS=0
for jar in $(echo "$CP_JARS" | tr ':' ' '); do
  if [ ! -f "$jar" ]; then
    echo "MISSING JAR: $jar"
    BAD_JARS=1
  elif [ "$(head -c2 "$jar" 2>/dev/null)" != "PK" ]; then
    echo "NOT A JAR: $jar ($(stat -c%s "$jar" 2>/dev/null || echo 0) bytes)"
    BAD_JARS=1
  fi
done
if [ "$BAD_JARS" -ne 0 ]; then
  echo ""
  echo "RESULT=TOOLCHAIN_BROKEN"
  echo "The classpath is not usable, so nothing was verified. This is NOT a code"
  echo "failure and must not be reported as one."
  exit 2
fi

{
  echo "=== CI EVIDENCE ==="
  echo "commit_sha=$SHA"
  echo "short_sha=$(git rev-parse --short HEAD)"
  echo "tree_sha=$TREE"
  echo "branch=$BRANCH"
  echo "dirty_files=$DIRTY"
  echo "date_utc=$DATE"
  echo "harness=ci/run-ci.sh (tracked in this tree)"
  echo "android_sdk=ABSENT - Gradle build, lint and device tests NOT RUN"
} > "$WORK/ev"

{
  echo ""
  echo "=== TOOLCHAIN ==="
  "$KOTLINC" -version 2>&1
  java -version 2>&1 | head -1
} >> "$WORK/ev"

echo "commit_sha=$SHA tree_sha=$TREE dirty_files=$DIRTY"

# ------------------------------------------------------------------ job 1
SCOPE=$(python3 ci/scope.py)
MAIN=$(echo "$SCOPE" | awk '$1=="MAIN"{print $2}')
TEST=$(echo "$SCOPE" | awk '$1=="TEST"{print $2}')

{
  echo ""
  echo "=== JOB 1: app core ==="
  echo "=== SCOPE ==="
  echo "jvm_main_sources=$(echo "$MAIN" | grep -c . )"
  echo "jvm_test_sources=$(echo "$TEST" | grep -c . )"
  echo "excluded_sources=$(echo "$SCOPE" | awk '$1=="OUT"' | grep -c .)"
  echo "$SCOPE" | awk '$1=="OUT"{print "  EXCLUDED " $2}'
  echo ""
  echo "=== COMPILE ==="
} >> "$WORK/ev"

mkdir -p "$WORK/j1"
# shellcheck disable=SC2086
"$KOTLINC" -nowarn -jvm-target 17 -cp "$CP_JARS" -d "$WORK/j1" $MAIN $TEST \
  > "$WORK/j1-compile.log" 2>&1
C1=$?
tail -40 "$WORK/j1-compile.log" >> "$WORK/ev"
echo "COMPILE_EXIT=$C1" >> "$WORK/ev"

if [ "$C1" -ne 0 ]; then
  echo "RESULT=COMPILE_FAILED" >> "$WORK/ev"
  echo "job 1 app core: COMPILE FAILED"
  [ "$CHECK_ONLY" -eq 0 ] && cp "$WORK/ev" "$EV"
  exit 1
fi

mapfile -t CLASSES < <(cd "$WORK/j1" && find . -name '*Test.class' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)

{
  echo ""
  echo "=== TEST ==="
  echo "test_classes=${#CLASSES[@]}"
  for c in "${CLASSES[@]}"; do echo "  CLASS $c"; done
} >> "$WORK/ev"

RUN_CP="$WORK/j1:$CP_JARS:$STD/kotlin-stdlib.jar:$STD/kotlin-stdlib-jdk8.jar:$STD/kotlin-stdlib-jdk7.jar"
java -cp "$RUN_CP" org.junit.runner.JUnitCore "${CLASSES[@]}" > "$WORK/j1.log" 2>&1
T1=$?
grep -E '^(JUnit version|OK \(|FAILURES|Tests run|Time:)' "$WORK/j1.log" >> "$WORK/ev"
{
  echo ""
  echo "TEST_EXIT=$T1"
  echo "RESULT=$([ $T1 -eq 0 ] && echo PASS || echo FAIL)"
  echo "APP_CORE_EXIT=$T1"
} >> "$WORK/ev"
echo "job 1 app core: exit=$T1"

# ------------------------------------------------------------------ job 2
{
  echo ""
  echo "=== JOB 2: narration core ==="
} >> "$WORK/ev"

OUT=narration-core/build/test-classes \
  LIB="$LIB" STD="$STD" KOTLINC="$KOTLINC" \
  ./narration-core/build.sh > "$WORK/j2.log" 2>&1
T2=$?
cat "$WORK/j2.log" >> "$WORK/ev"
{
  echo ""
  echo "NARRATION_EXIT=$T2"
} >> "$WORK/ev"
echo "job 2 narration core: exit=$T2"

# ------------------------------------------------------------------ summary
TOTAL=$(grep -oE 'OK \([0-9]+ tests?\)' "$WORK/ev" | grep -oE '[0-9]+' \
  | awk '{s+=$1} END{print s+0}')
{
  echo ""
  echo "SUMMARY total_tests=${TOTAL:-0} job1_exit=$T1 job2_exit=$T2"
  echo "VERDICT=$([ $T1 -eq 0 ] && [ $T2 -eq 0 ] && echo PASS || echo FAIL)"
} >> "$WORK/ev"

echo "SUMMARY total_tests=${TOTAL:-0} job1_exit=$T1 job2_exit=$T2"

if [ "$CHECK_ONLY" -eq 0 ]; then
  cp "$WORK/ev" "$EV"
  echo "wrote $EV"
fi

[ "$T1" -eq 0 ] && [ "$T2" -eq 0 ]
