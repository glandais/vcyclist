# TS ↔ Kotlin numeric parity harness

Measures, on identical inputs, whether the Kotlin port (`:engine`, `:elevation`) computes the
same numbers as the TypeScript references (`../virtual-cyclist`, `../elevation`).

Results and the divergence analysis live in [`docs/parity.md`](../../docs/parity.md). This
file is the operating manual.

## Prerequisites

| Tool | Version used | Notes |
|---|---|---|
| Node | 22.17.0 | `>=18` per both reference `package.json` |
| npm | 10.9.2 | |
| JDK | 25 (Gradle toolchain pins 21) | |
| Python | 3.11+ | stdlib only, no venv needed |

The two reference repos must be siblings of `vcyclist/` and have their dependencies
installed. **They stay read-only** — the harness only ever reads their sources:

```bash
cd ../virtual-cyclist && npm install --no-save --ignore-scripts && npm rebuild canvas
cd ../elevation      && npm ci
git -C ../virtual-cyclist status --short   # must be empty
git -C ../elevation      status --short    # must be empty
```

`npm ci` fails in `virtual-cyclist` (its lockfile is missing a semantic-release transitive
dep). `npm install --no-save` populates the gitignored `node_modules/` without touching
`package.json` or `package-lock.json`. `npm rebuild canvas` is needed because
`--ignore-scripts` skips the native binding download.

## Running everything

```bash
./run-all.sh [output-root]     # default output root: ~/.cache/vcyclist-parity
```

Roughly 6-8 minutes and **~1.5 GB of dumps** for the 7 sample GPX files in both modes.
Keep the output root off `/tmp` if `/tmp` is a tmpfs. Nothing is written into any repo.

### The two modes

| Mode | What it measures |
|---|---|
| `as-is` | Both implementations exactly as they ship. **Not reproducible run to run** — `VirtualizeService.ts:67` seeds its clock from `new Date()`. |
| `clock-pinned` | The TS wall-clock read is pinned to epoch 0, isolating the ports from that one defect. **This is the mode to use when judging port fidelity.** |

Pinning the clock does not modify the reference library; it controls an ambient input the
library reads (see `withZeroClock` in `ts/pipelineDump.ts`).

## Running one piece at a time

```bash
# End-to-end cascade, one fixture, TypeScript side (cwd MUST be virtual-cyclist/)
cd ../virtual-cyclist
npx tsx ../vcyclist/tools/parity/ts/pipelineDump.ts \
    --gpx gpx/stelvio.gpx --out /tmp/ts/stelvio --simplify --zero-clock

# ... Kotlin side
cd ../vcyclist
./gradlew :tools:parity:dumpPipeline \
    -Pargs="--gpx ../virtual-cyclist/gpx/stelvio.gpx --out /tmp/kt/stelvio --simplify"

# Compare
python3 tools/parity/compare.py /tmp/ts/stelvio /tmp/kt/stelvio --profile --top 8

# Unit-level sweep (pure functions, edge cases)
cd ../elevation && npx tsx ../vcyclist/tools/parity/ts/unitElevation.ts \
    --cases ../vcyclist/tools/parity/cases/units.json --out /tmp/units-ts.json
cd ../vcyclist && ./gradlew :tools:parity:dumpUnits \
    -Pargs="--cases $PWD/tools/parity/cases/units.json --out /tmp/units-kt.json"
python3 tools/parity/compare-units.py /tmp/units-ts.json /tmp/units-kt.json
```

`cd ../virtual-cyclist` (or `../elevation`) before invoking `tsx` is not optional: that is
how `tsx` picks up the repo's `tsconfig.json` `paths` and its `node_modules`.

## Dump format

Two files per pipeline stage, written identically by both sides:

- `<nn>-<stage>.f64` — raw IEEE-754 binary64, little-endian, row-major.
  `value(point i, field f)` sits at byte offset `(i * fieldCount + f) * 8`.
  Field order is `PointField` ordinal, identical to the TS `POINT_FIELDS` order.
- `<nn>-<stage>.json` — stage name, point count, field names, path aggregates.

Binary rather than JSON Lines so no decimal formatting sits between the computed double and
the comparison, and so a 68 k-point stage costs ~20 MB instead of ~200 MB.

## Tools

| Script | Purpose |
|---|---|
| `run-all.sh` | Full sweep: 7 fixtures × 2 modes, dumps + reports |
| `compare.py` | Per-stage, per-field deltas and verdicts. `--profile` shows the divergence curve, `--drop-last N` isolates last-point effects, `--rebase-time` neutralises the TS clock origin |
| `inspect.py` | Side-by-side raw values for a field over an index range — the tool to reach for once `compare.py` has localised a divergence |
| `summarize.py` | Aggregates the per-fixture JSON reports into the cross-fixture tables in `docs/parity.md` |
| `compare-units.py` | Diffs the two flat maps from the unit-level runners |

## Tolerance model

A field passes when **either** the relative delta is within `1e-9` (the composed-trig band
from `CLAUDE.md`) **or** the absolute delta is below a unit-derived floor (`1e-9` rad,
`1e-6` m, `1e-9` W …). The absolute floor matters: `bearing` and `grade` legitimately pass
through zero, and a relative error measured against a ~1e-11 denominator is arithmetic
noise, not a divergence. Verdicts are `EXACT`, `OK`, `DRIFT` (accumulating fields within
0.5 %), `DIVERGED`, `NAN-MISMATCH`.

## When a divergence appears

1. **Find the first stage that diverges.** Everything downstream of a diverging stage is
   uninterpretable. `compare.py` prints stages in pipeline order.
2. **Read the `1st>tol` column.** A divergence appearing at index ~0 is algorithmic. One
   that first appears late and grows is accumulated drift.
3. **Read the `--profile` curve.** A rising ramp is drift. A step is a branch divergence.
   A curve that *collapses back* to ~1e-10 is drift being wiped by a waypoint snap — the
   simulation re-synchronises on GPS waypoints, so this is expected.
4. **Check for catastrophic cancellation before crying bug.** `pComputedTotalPower` and
   friends are `Δ(v²)/Δt`. Where the true value crosses zero, a 1e-10 speed difference
   shows up as a 100 % relative error on a physically negligible absolute one. Always read
   `max|abs|` next to `max|rel|`.
5. **Drop the last point** (`--drop-last 1`). TS does not simulate the final point; that
   difference alone accounts for a ~2 m and 1 s shortfall on every fixture.
6. **Then use `inspect.py`** on the field and index range to read the actual numbers.

## Known gaps

- **The TS Node elevation path cannot decode the current tiles.** `node-canvas` 3.2.3
  rejects the lossless WebP that `tiles.mapterhorn.com` serves (`Unsupported image type`),
  so no TS↔Kotlin elevation comparison is possible through Node. Reproduce with
  `node -e "require('canvas').loadImage(require('fs').readFileSync('tile.webp'))"`.
- **Browser WebP decoders are unmeasured.** `:elevation`'s integration gate is
  `typeof process !== 'undefined' && process.env.INTEGRATION === '1'`; `process` does not
  exist in a browser, so those tests are silently skipped on the browser targets. Measuring
  `createImageBitmap` against TwelveMonkeys needs a browser harness that does not exist yet.
