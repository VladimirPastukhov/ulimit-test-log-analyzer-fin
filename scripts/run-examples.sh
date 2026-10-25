#!/usr/bin/env bash
# Runs the analyzer on every examples/INC-*.txt with the configured LLM.
# Diagnosis -> examples/output/<id>.json, stage trace -> examples/output/<id>.log
set -uo pipefail
cd "$(dirname "$0")/.."

# Without a key every run fails; stop before overwriting the saved outputs.
if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "error: set ANTHROPIC_API_KEY or OPENAI_API_KEY (export it first)" >&2
  exit 1
fi

./gradlew -q shadowJar || exit 1
mkdir -p examples/output

status=0
for log in examples/INC-*.txt; do
  id=$(basename "$log" .txt)
  java -jar build/libs/analyzer.jar "$log" >"examples/output/$id.json" 2>"examples/output/$id.log"
  code=$?
  attempts=$(grep -c '\[5/5\] attempt' "examples/output/$id.log")
  echo "$id: exit=$code attempts=$attempts"
  [ "$code" -eq 0 ] || { status=1; rm -f "examples/output/$id.json"; }
done
exit $status
