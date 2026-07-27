#!/usr/bin/env bash
#
# Run the full TS <-> Kotlin parity cascade over every sample GPX, in both modes, and
# write the comparison reports.
#
#   ./run-all.sh [output-root]        # default: ~/.cache/vcyclist-parity
#
# Modes:
#   as-is        each implementation exactly as it ships (TS seeds its simulation clock
#                from the wall clock, so this mode is NOT reproducible run to run)
#   clock-pinned the TS wall-clock read is pinned to epoch 0, isolating the ports from
#                that one defect (see tools/parity/README.md)
#
# Prerequisites: see README.md. This writes GBs of dumps; keep it off tmpfs.
set -euo pipefail

ROOT="${1:-$HOME/.cache/vcyclist-parity}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VCYCLIST="$(cd "$HERE/../.." && pwd)"
REF_TS="$(cd "$VCYCLIST/../virtual-cyclist" && pwd)"

FIXTURES=(amazfit garmin movescount sample sports-tracker stelvio strava)

mkdir -p "$ROOT/reports"

echo "== Kotlin dumps (simplify on) =="
for f in "${FIXTURES[@]}"; do
    echo "-- $f"
    (cd "$VCYCLIST" && ./gradlew --quiet :tools:parity:dumpPipeline \
        -Pargs="--gpx $REF_TS/gpx/$f.gpx --out $ROOT/kt/$f --simplify")
done

echo "== TypeScript dumps =="
for f in "${FIXTURES[@]}"; do
    for mode in as-is clock-pinned; do
        extra=""
        [ "$mode" = "clock-pinned" ] && extra="--zero-clock"
        echo "-- $f ($mode)"
        (cd "$REF_TS" && npx tsx "$HERE/ts/pipelineDump.ts" \
            --gpx "$REF_TS/gpx/$f.gpx" --out "$ROOT/ts-$mode/$f" --simplify $extra)
    done
done

echo "== Comparisons =="
for f in "${FIXTURES[@]}"; do
    for mode in as-is clock-pinned; do
        out="$ROOT/reports/$f.$mode"
        # --rebase-time is required in as-is mode (TS absolute epoch vs Kotlin 0); it is a
        # no-op in clock-pinned mode, where both axes already start at 0.
        python3 "$HERE/compare.py" "$ROOT/ts-$mode/$f" "$ROOT/kt/$f" \
            --rebase-time --profile --top 8 --json "$out.json" > "$out.txt" 2>&1 || true
        echo "-- $out.txt"
    done
done

echo
echo "Reports in $ROOT/reports/"
