#!/usr/bin/env bash
#
# Build the standalone WASI module and run the reference host against it.
#
#   ./tools/wasi/run-all.sh                 # offline
#   INTEGRATION=1 ./tools/wasi/run-all.sh   # also downloads DEM tiles for the elevation test
#
# Prerequisites: python3 with `pip install -r tools/wasi/requirements.txt`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VCYCLIST="$(cd "$HERE/../.." && pwd)"

echo "== Building the module =="
(cd "$VCYCLIST" && ./gradlew --quiet :engine:wasmModule :engine:checkWasmModuleSize)

echo "== Running the reference host =="
# From the harness directory: `host` and `fixtures` are plain modules next to the tests, and
# `fixtures` finds the repository by walking up from its own path, not from the working directory.
cd "$HERE"
exec python3 -m unittest discover -s . -p 'test_*.py' "$@"
