#!/bin/sh
# Runs CodeWitness against every fixture and prints a compact summary of the outcome.
# Used to produce docs/fixtures.md and to check that the tool still distinguishes the cases.
set -u

CW_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$CW_DIR"
JAR="$CW_DIR/codewitness-cli/target/codewitness.jar"

for dir in fixtures/*/; do
  name=$(basename "$dir")
  req="$dir/requirements.md"
  [ -f "$dir/requirements.yaml" ] && req="$dir/requirements.yaml"
  rm -rf "$dir/.codewitness"
  echo "### $name"
  java -jar "$JAR" verify "$dir" --requirements "$req" --max-duration 10m --quiet > "/tmp/cw-fixture-$name.log" 2>&1
  code=$?
  echo "exit=$code"
  run=$(ls -1t "$dir/.codewitness/runs" 2>/dev/null | head -1)
  python3 - "$dir/.codewitness/runs/$run/report.json" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
except Exception as e:
    print("no report:", e); raise SystemExit
print("overall:", d["status"])
for r in d["requirements"]:
    print(f'  {r["status"]:20} {r["id"]}  {r["title"][:44]}')
for c in d["counterexamples"][:6]:
    print(f'  CE {c["id"]} {c["requirementId"]}: inputs={c["inputs"]} actual={str(c["actual"])[:60]}')
m=d["mutation"]
print("  mutation:", "measured" if m["measured"] else "not measured",
      f'gen={m["generated"]} killed={m["killed"]} survived={m["survived"]}' if m["measured"] else "-")
f=[x for x in d["findings"]]
print("  findings:", [(x["rule"],x["severity"],x["file"]) for x in f][:6])
print("  limitations:", len(d["limitations"]))
PY
  echo
done
