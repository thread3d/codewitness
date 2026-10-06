#!/bin/sh
#
# Runs the demonstration: CodeWitness verifying a deliberately defective project.
#
# The fixture in fixtures/withdrawal-ledger contains four introduced defects behind five
# requirements. This script shows the tool finding them and producing reproducible evidence.

set -eu

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SELF_DIR"

FIXTURE="fixtures/withdrawal-ledger"

if [ ! -f "codewitness-cli/target/codewitness.jar" ]; then
  echo "Building CodeWitness first..."
  ./build.sh -DskipTests
  echo
fi

echo "======================================================================"
echo " 1. What is in this project?  (no code is executed)"
echo "======================================================================"
./codewitness inspect "$FIXTURE"
echo

echo "======================================================================"
echo " 2. Verify it against its requirements"
echo "======================================================================"
set +e
./codewitness verify "$FIXTURE" --requirements "$FIXTURE/requirements.md" --max-duration 10m
STATUS=$?
set -e
echo
echo "codewitness verify exited with $STATUS"
echo "(0 = nothing failed, 1 = a requirement has a counterexample, 2 = blocked, 3 = internal error)"
echo

echo "======================================================================"
echo " 3. Read the reports"
echo "======================================================================"
RUN_DIR=$(ls -1t "$FIXTURE/.codewitness/runs" | head -1)
echo "HTML report : $FIXTURE/.codewitness/runs/$RUN_DIR/report.html"
echo "JSON report : $FIXTURE/.codewitness/runs/$RUN_DIR/report.json"
echo
echo "Recent runs:"
./codewitness report --project "$FIXTURE"
echo

echo "======================================================================"
echo " 4. Reproduce a counterexample"
echo "======================================================================"
./codewitness reproduce "$RUN_DIR" --project "$FIXTURE"

exit 0
