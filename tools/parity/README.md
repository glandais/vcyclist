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

The last block is the **elevation (DEM) sweep**, which unlike the pipeline sweep **needs
network**: it resolves the 10 `ELEVATION_COORDS` on both sides against live Terrarium tiles
and writes `reports/elevation.txt`. It exercises each side's own decoder — TwelveMonkeys on
the JVM, `sharp` in the TS reference since `elevation` 3.2.3 — with no substitution.

### The two modes

| Mode | What it measures |
|---|---|
| `as-is` | Both implementations exactly as they ship. **Not reproducible run to run** — `VirtualizeService.ts:67` seeds its clock from `new Date()`. |
| `clock-pinned` | The TS simulation clock is pinned to epoch 0, isolating the ports from that one defect. **This is the mode to use when judging port fidelity.** |

Pinning the clock does not modify the reference library: since `virtual-cyclist` 1.3.0 it is
a **supported parameter** — `VirtualizeService.virtualizeTrack(course, startTime)`, also
reachable as `EnhanceOptions.startTime`. The harness used to monkey-patch `globalThis.Date`
instead; that hack is gone, and the pinned mode now exercises the shipped API exactly as a
caller would. Verified behaviour-preserving: all 8 stage dumps are byte-identical to the
patched-`Date` run.

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
| `ts/liveElevation.ts` + `:tools:parity:dumpElevation` | DEM sweep: the same 10 `ELEVATION_COORDS` resolved live through each side's own WebP decoder. Run by `run-all.sh`; needs network |

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

## Also useful for: proving a refactor changed nothing

The two dump directories the harness compares do not have to be TS vs Kotlin. Dumping the
Kotlin pipeline before and after a refactor and comparing the two proves, field by field,
that the numbers did not move:

```bash
git checkout <before> && ./gradlew :tools:parity:dumpPipeline \
    -Pargs="--gpx ../virtual-cyclist/gpx/stelvio.gpx --out /tmp/before --simplify"
git checkout <after>  && ./gradlew :tools:parity:dumpPipeline \
    -Pargs="--gpx ../virtual-cyclist/gpx/stelvio.gpx --out /tmp/after  --simplify"
python3 tools/parity/compare.py /tmp/before /tmp/after
```

This was used to check the gpx2web module split (`:engine` → `:gpx` + `:engine` + `:map` +
`:cli` + `:fit`): **all 36 fields bit-identical at all 8 stages**, so moving the `Path` and
GPX types into `:gpx` changed no arithmetic.

## Known gaps

- ~~**The TS Node elevation path cannot decode WebP at all.**~~ **Fixed in `elevation`
  3.2.3** — see the closing note on this entry. Diagnosis retained: not "rejects the lossless
  variant" — `node-canvas` 3.2.3 links no libwebp: `ldd
  ../elevation/node_modules/canvas/build/Release/canvas.node | grep -i webp` matches nothing
  (30 other `.so`s are listed: cairo, libpng16, libjpeg.62, libgif.7, librsvg, pango …), and
  `strings … | grep -ci webp` is `0`. Its exports carry `PNGStream`, `JPEGStream`,
  `PDFStream`, `jpegVersion`, `gifVersion` — nothing WebP. `loadImage` then fails with
  cairo's generic `Unsupported image type`. Reproduce with
  `node -e "require('canvas').loadImage(require('fs').readFileSync('tile.webp'))"`.
  There is no PNG fallback either: `https://tiles.mapterhorn.com/12/2094/1467.png` is
  **404** while the `.webp` is `200 image/webp` (335 460 B, `RIFF`/`WEBP`/`VP8L`, 512×512).
  So no TS↔Kotlin elevation comparison is possible through Node, and TS `fixElevation=true`
  is non-functional there.
  **Fixed in `elevation` 3.2.3** (`33c7ecc`): node-canvas is replaced by `sharp` for the
  decode. The sweep now runs against the shipped chain with no harness shim. What it found
  instead is divergence 4 in `docs/parity.md`: TS's `toPixel` floors before
  `ElevationCalculator` derives `dx`/`dy`, so its "bilinear" interpolation is a floor-pixel
  lookup — 8/10 coordinates diverge, up to 8.59 m, against a `~1e-9` bar.
- ~~**The Kotlin browser decoders are unmeasured.**~~ **Closed.** `elevation/src/wasmJsTest/`
  now exists, and the integration gate moved to `commonTest/…/IntegrationGate.kt` with a
  Karma-injected flag for the browser targets (`elevation/karma.config.d/integration.js`), so
  `INTEGRATION=1` reaches `jsBrowserTest` and `wasmJsBrowserTest`. `ReferenceTileDigestTest`
  asserts every target reproduces the JVM's decoded-RGBA SHA-256; both browsers match
  byte-for-byte. Skipped integration tests now print that they skipped.
- **Premultiplication remains unmeasured, despite the digest test.** The reference tile is
  fully opaque, and premultiplying by alpha `255` is the identity — so that test can only
  catch a `colorSpaceConversion` regression, never a `premultiplyAlpha` one.
  `TileFetcher.wasmJs.kt:24` and the browser branch of `TileFetcher.js.kt:75` still call
  `createImageBitmap(blob)` with no `ImageBitmapOptions`, leaving both at `"default"` — the
  implementation's choice. Terrarium packs elevation into the RGB bits
  (`ele = R*256 + G + B/256 - 32768`), so premultiplication against a non-opaque alpha yields
  wrong metres with no exception (one LSB of R is 256 m). Sampled tiles are opaque today
  (`VP8L alpha_is_used = 0` on four tiles; PIL alpha extrema `(255,255)` on
  `12/2094/1467.webp`) — a property of the current tiles, not a guarantee of the API. Closing
  this needs a fixture with a real alpha plane. Coverage is also ChromeHeadless/Linux only.
- **CORS is not the blocker for a browser harness.** `curl -D - -H 'Origin:
  http://localhost:9876' https://tiles.mapterhorn.com/12/2094/1467.webp` returns `HTTP/2 200`
  with `access-control-allow-origin: *`, so a Karma page can fetch real tiles.
- **Do not use the ±1 m band as the decoder tolerance.** ±1 m is Terrarium tile resolution
  (noise between data sources). Between two decoders on the *same* tile bytes the bar is
  ~`1e-9`, per the KDoc of `src/main/kotlin/io/github/glandais/parity/ElevationDump.kt`:
  "a metre-scale gap would mean a decode or bilinear-interpolation bug, not tile noise".
