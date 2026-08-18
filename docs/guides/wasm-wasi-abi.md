# The vcyclist WASI ABI

How to run `vcyclist-engine.wasm` from any WASI runtime — wasmtime, WasmEdge, wazero, or an
embedding in Go, Rust, Python or the JVM. No JavaScript, and no Kotlin knowledge required.

This is the guide for **someone running the module**. The engineering notes on how it is built —
the Gradle target, the compiler's rough edges, why the ABI looks like this — are in
[`kotlin-wasm-wasi.md`](kotlin-wasm-wasi.md), and the work is tracked in
[`PLAN-WASM-WASI.md`](../archive/plans/PLAN-WASM-WASI.md).

**Everything here is exercised by [`tools/wasi`](../../tools/wasi/README.md)**, a reference host in
Python that CI runs on every pull request. When this document and that harness disagree, the
harness is right — it cannot pass while being wrong about the module.

---

## 1. Quick start

Build the module (a published binary lands with task w07):

```bash
./gradlew :engine:wasmModule       # -> engine/build/wasm/vcyclist-engine.wasm + .sha256
```

Then, with `pip install wasmtime`:

```python
from wasmtime import Config, Engine, Func, FuncType, Linker, Module, Store, ValType, WasiConfig

config = Config()
config.wasm_function_references = True      # write-only properties: hasattr() lies about them
config.wasm_gc = True
config.wasm_exceptions = True
engine = Engine(config)

store = Store(engine)
store.set_wasi(WasiConfig())
linker = Linker(engine)
linker.define_wasi()

staged = {"bytes": b""}          # what the guest will pull in
captured = {"bytes": b""}        # what the guest last pushed out

def read_input(caller, ptr, cap):
    data = staged["bytes"][:cap]
    caller.get("memory").write(caller, data, ptr)
    return len(data)

def write_output(caller, ptr, length):
    captured["bytes"] = bytes(caller.get("memory").read(caller, ptr, ptr + length))

def fetch_tile(caller, zoom, x, y, ptr, cap):
    return 0                     # "no DEM tile here" — see §8

i32 = ValType.i32()
linker.define(store, "vcyclist", "read_input",
              Func(store, FuncType([i32, i32], [i32]), read_input, access_caller=True))
linker.define(store, "vcyclist", "write_output",
              Func(store, FuncType([i32, i32], []), write_output, access_caller=True))
linker.define(store, "vcyclist", "fetch_tile",
              Func(store, FuncType([i32] * 5, [i32]), fetch_tile, access_caller=True))

exports = linker.instantiate(store, Module.from_file(engine, "vcyclist-engine.wasm")).exports(store)

print(exports["vcAbiVersion"](store))                  # 1

staged["bytes"] = open("ride.gpx", "rb").read()
handle = exports["vcParseGpx"](store, len(staged["bytes"]))
print(exports["vcPathSize"](store, handle), "points")
print(exports["vcPathTotalDistance"](store, handle), "m")
exports["vcRelease"](store, handle)
```

For anything longer than this, start from [`tools/wasi/host.py`](../../tools/wasi/host.py) instead of
copying from here: it wraps every export in a Python method, and CI keeps it honest.

## 2. Module shape

- **WASI Preview 1 core module**, not a component. There is no WIT, no Component Model — Kotlin
  does not emit one. A `wit-component` wrapper on the host side is possible and out of scope.
- **Reactor, not command.** The module has no `main`, so the Wasm `start` section runs the
  global initialisers at instantiation. There is **no `_start` and no `_initialize` to call**:
  instantiate, then call exports.
- **`memory` is exported.** The host reads and writes it during the callbacks of §4.
- **Required proposals**: `function-references`, `gc`, `exceptions`. Kotlin 2.4 emits the *new*
  exception handling (`exnref`). A runtime without them rejects the module while parsing, not at
  instantiation — if you see "unsupported proposal", the runtime is too old rather than the
  module wrong. wasmtime 46+ is known good (that is what the build itself uses).
- **Single-threaded.** No `wasi-threads`, no shared memory. One instance serves one caller at a
  time.

## 3. Imports the host must provide

All three, in module `"vcyclist"`:

| Import | Signature | Contract |
|---|---|---|
| `read_input` | `(ptr: i32, cap: i32) -> i32` | write up to `cap` bytes of the staged payload at `ptr`, return how many were written |
| `write_output` | `(ptr: i32, len: i32) -> ()` | copy `len` bytes from `ptr`; the memory is gone when the call returns |
| `fetch_tile` | `(zoom: i32, x: i32, y: i32, ptr: i32, cap: i32) -> i32` | write one DEM tile at `ptr` and return how many bytes; `0` means "no tile here"; more than `cap` is a host failure (§8) |

**All three are mandatory**, including `fetch_tile` for a host that will never correct an
elevation: Wasm imports are resolved at instantiation, so one missing means the module does not
load at all. The minimal implementation is `return 0`.

## 4. The transfer protocol

Objects never cross the boundary and neither do strings. `@WasmExport` only accepts numbers.

**Inbound.** The host stages the bytes on its side, then calls an export with their **length**.
The guest allocates that many bytes in a scoped arena and calls `read_input`, during which the
host writes them into linear memory. The guest copies them onto its own heap before the arena
closes.

**Outbound.** The guest writes UTF-8 (or raw bytes, for `vcPathFieldBytes`) into a scoped arena
and calls `write_output`; the host must copy during the call. The export returns the byte length.

**Two rules, both of which corrupt state rather than fail cleanly if broken:**

1. **Do not call any export from inside a callback.** The scoped allocator forbids nested scopes
   and throws. If your `fetch_tile` needs data from the module, fetch it before the call.
2. **Do not keep a pointer past the end of the callback that handed it out.** The arena is gone.

Text is always UTF-8. JSON payloads follow §9.

## 5. Handles

Anything that is not a scalar lives in a guest-side table and crosses as a positive `Int`:

- a **path handle** — one GPS trace;
- a **list handle** — several paths, from `vcParseGpxMulti`. Walk it with `vcListSize` and
  `vcListGet`, which registers a path handle of its own.

Both tables share one counter, so a list handle passed where a path handle is expected is simply
unknown (`-2`) rather than silently reinterpreted. **A released handle is never reissued**, so a
stale handle on the host side cannot resurrect as someone else's path.

Release what you take: `vcRelease(handle)` returns 1 if it existed and 0 otherwise (never an
error). `vcReleaseAll()` empties both tables and returns how many it dropped — that is what a
host reusing one instance across several rides calls between runs. **Nothing else reclaims a
handle**: forget one and its path lives as long as the instance.

## 6. Errors

An exception cannot cross a Wasm boundary, so every export catches and returns a negative
sentinel. The codes are `WasiAbi.kt`'s, and `tools/wasi/host.py` asserts each of them:

| Code | Name | Meaning |
|---|---|---|
| `-1` | `ERR_GENERIC` | something failed; the message is in `vcLastError` |
| `-2` | `ERR_UNKNOWN_HANDLE` | that handle is not (or no longer) in the table |
| `-3` | `ERR_INVALID_ARGUMENT` | a length, an index, a mode or a JSON option is out of range or unknown |
| `-4` | `ERR_UNSUPPORTED` | the capability exists in the API but not on this target. Nothing returns it since w12 — it stays reserved |

Exports returning a `Double` return the same codes as a `Double` (`-2.0`, …), which is
unambiguous because every quantity they carry is non-negative. The single exception is
`vcDominantHeadwindAzimuth`, where the whole azimuth circle is a valid answer: it returns **NaN**
instead, for an unknown handle as well as for a course with no answer.

`vcLastError()` pushes the last message through `write_output` and returns its length. It never
fails, and it is **not** cleared by a successful call: a host that ignores a sentinel and asks
later still gets the truth about what it ignored.

`vcAbiVersion()` is the first thing to call. It is the only export guaranteed never to touch an
import, so it answers before `read_input` is wired to anything real. **A host reading a version
it does not know must refuse the module rather than guess.** Version 1 is the ABI described here.

## 7. Export reference

Options-taking exports take the **byte length** of a JSON object, or `0` for "all defaults".
"→ output" means the result is pushed through `write_output` and the return value is its byte
length.

### Version, errors, handles

| Export | Signature | Returns |
|---|---|---|
| `vcAbiVersion` | `() -> i32` | `1`. Never fails |
| `vcLastError` | `() -> i32` | → the last error message (empty if none) |
| `vcRelease` | `(handle) -> i32` | 1 if the handle existed, 0 otherwise |
| `vcReleaseAll` | `() -> i32` | how many handles were dropped |

### Parsing

| Export | Signature | Returns |
|---|---|---|
| `vcParseGpx` | `(byteLen) -> i32` | path handle for the document's **first track** |
| `vcParseGpxMulti` | `(byteLen, mode) -> i32` | **list handle**; `mode` 0 = tracks *and* routes, 1 = segments, 2 = tracks only, 3 = routes only |
| `vcParseGpxWaypointsJson` | `(byteLen) -> i32` | → JSON array of waypoints |
| `vcListSize` | `(listHandle) -> i32` | number of paths |
| `vcListGet` | `(listHandle, index) -> i32` | a fresh path handle onto that path |

### Metrics

| Export | Signature | Returns |
|---|---|---|
| `vcPathSize` | `(handle) -> i32` | number of points |
| `vcPathTotalDistance` | `(handle) -> f64` | meters |
| `vcPathDurationMs` | `(handle) -> f64` | milliseconds |
| `vcPathElevationGain` | `(handle) -> f64` | meters |
| `vcPathElevationLoss` | `(handle) -> f64` | meters, **negative** by convention |
| `vcPathLatitudeDeg` | `(handle, i) -> f64` | degrees |
| `vcPathLongitudeDeg` | `(handle, i) -> f64` | degrees |
| `vcDominantHeadwindAzimuth` | `(handle) -> f64` | degrees, or NaN. See §6 |
| `vcDominantHeadwindAzimuthOfTracks` | `(listHandle) -> f64` | same, over a list |

### Fields

| Export | Signature | Returns |
|---|---|---|
| `vcFieldDefinitionsJson` | `() -> i32` | → the field catalog, `index` included |
| `vcGetField` | `(handle, fieldIndex, pointIndex) -> f64` | one value |
| `vcPathFieldBytes` | `(handle, fieldIndex) -> i32` | → the whole field, `8 × vcPathSize` bytes |
| `vcPointJson` | `(handle, i) -> i32` | → one point as JSON |

`fieldIndex` comes from `vcFieldDefinitionsJson` — it is the field's position in that array, and
the list has grown before. Do not hard-code it; read the catalog once at start-up.

**`vcPathFieldBytes` is how you read a profile.** The values are raw little-endian `f64`s, in
point order, with no header — Wasm memory is little-endian, so on any mainstream host they map
straight onto a native array (`numpy.frombuffer`, `Float64Array`, `binary.LittleEndian`). One
export call per point per field is a boundary crossing per value, which no 50 000-point trace
survives.

### Simulation

| Export | Signature | Returns |
|---|---|---|
| `vcEnhance` | `(handle, optionsJsonLen) -> i32` | a **new** path handle; the input is untouched |
| `vcEnhanceWithCourse` | `(handle, payloadJsonLen) -> i32` | same, with a full physics course |
| `vcDetectClimbsJson` | `(handle, optionsJsonLen) -> i32` | → JSON array of climbs |
| `vcAnalyzeRacingLineJson` | `(handle, optionsJsonLen) -> i32` | → JSON report, or `null`. Moves nothing |

### Serialisation

| Export | Signature | Returns |
|---|---|---|
| `vcWriteGpx` | `(handle, optionsJsonLen) -> i32` | → GPX 1.1, single track |
| `vcWriteGpxTracks` | `(listHandle, optionsJsonLen) -> i32` | → GPX 1.1, one `<trk>` per path |
| `vcPathToCsv` | `(handle, optionsJsonLen) -> i32` | → CSV, one row per point |
| `vcPathToJson` | `(handle, optionsJsonLen) -> i32` | → JSON, **column-oriented** (one array per field) |
| `vcPathToFit` | `(handle, payloadJsonLen) -> i32` | → a Garmin FIT Course file, **binary** |
| `vcPathsToFit` | `(listHandle, payloadJsonLen) -> i32` | → one FIT course holding every path of the list |

### Elevation

| Export | Signature | Returns |
|---|---|---|
| `vcSetElevationConfig` | `(jsonLen) -> i32` | `1` on success; sticky, validated immediately |
| `vcTileGeometryJson` | `() -> i32` | → what `fetch_tile` must be ready to write |

## 8. Elevation: the host serves the tiles

WASI has no HTTP client, so `fixElevation` is the one step that needs the host. The module asks
for a tile by `zoom/x/y` and the host answers, in one of two formats — set with
`vcSetElevationConfig`, reported by `vcTileGeometryJson`:

| `tileFormat` | The host writes | Needs |
|---|---|---|
| `"rgba"` (default) | decoded pixels, exactly `expectedBytes` of them | an image library |
| `"webp"` | **the file as downloaded**, and returns its length | an HTTP client, nothing else |

`"webp"` exists because the module carries its own lossless-WebP decoder (pure Kotlin, checked
byte for byte against the JVM's on a real tile). In that mode a host is one `GET` — no Pillow, no
`image` crate, no decoding at all. `"rgba"` remains the default so that hosts written against the
earlier contract keep working unchanged.

The format is declared rather than sniffed: a WebP file is smaller than a decoded tile today, but
deciding by length would misread the day it is not, and a misread tile is wrong elevations rather
than an error.

The rest of this section describes the pixels — what the host sends in `"rgba"` mode, and what
the module decodes the file into in `"webp"` mode.

```json
// vcTileGeometryJson
{"tileSize": 512, "bytesPerPixel": 4, "expectedBytes": 1048576,
 "layout": "RGBA", "encoding": "terrarium", "zoomLevel": 12, "tileFormat": "rgba"}
```

- `expectedBytes` is the `cap` the guest passes, so a host can also just trust `cap`. In
  `"webp"` mode it is the size of the buffer **offered**, not of the answer: write the file and
  return its length.
- **RGBA**, 4 bytes per pixel, row-major from the top-left. Only R, G and B are read; alpha may
  be anything. Elevation is Terrarium: `(r × 256 + g + b / 256) − 32768` meters.
- Returning **`0`** means "no tile here" — sea, outside coverage, a failed download, whatever the
  host decides. The guest substitutes a tile that reads as exactly 0 m. It does **not** zero-fill,
  which would decode as −32768 m and then bleed through the smoother.
- Any other return value is a host failure: the guest throws and the calling export answers `-1`.

Tile requests arrive **one at a time**, in the middle of a `vcEnhance` call. That is also why the
re-entrancy rule of §4 matters here: do not call back into the module from `fetch_tile`.

`vcSetElevationConfig` takes `{"zoomLevel": 12, "tileSize": 512, "cacheSize": 100,
"tileFormat": "rgba"}`, any subset.
It is validated on the spot — a tile size that is not a power of two, or a zoom outside 0..15, is
`-3` right there rather than three calls later — and it is sticky for the instance.

Default tile URLs, attribution and coverage of the reference DEM are in
[`elevation/README.md`](../../elevation/README.md#live-http-integration-tests). The host is what talks to that service, so
its terms of use are the host's to respect.

## 9. JSON schemas

One object per call, UTF-8, pulled through `read_input`. Two rules hold everywhere:

- **An absent field means the engine's default.** The defaults are read from the engine itself,
  never restated, so they cannot drift from what the JVM and JS builds do.
- **An unknown field is an error** (`-3`), and the message names it. This is the one deliberate
  difference with the JavaScript façade, and it goes the right way: a typo in `massKg` must not
  silently simulate the default rider.

### `vcEnhance` — options

```json
{"fixElevation": false, "computeMaxSpeeds": true, "virtualizeTrack": true,
 "computeOnePointPerSecond": false,
 "simplifyEnabled": false, "simplifyToleranceM": 10.0, "simplifyZExaggeration": 3.0}
```

Those are also the defaults, which are the **JavaScript façade's** rather than the engine's own:
no DEM fetch, no 1 Hz resample, no simplification. Ask for them explicitly if you want the full
pipeline.

### `vcEnhanceWithCourse` — payload

Up to five optional sub-objects; each is the matching JS DTO, field for field.

```json
{"cyclist": {"massKg": 72, "cd": 0.7, "frontalAreaM2": 0.5,
             "maxLeanAngleDeg": 35, "maxBrakeG": 0.4, "maxSpeedKmH": 100,
             "roadCondition": "dry"},
 "bike": {"crr": 0.004, "inertiaFront": 0.0771, "inertiaRear": 0.1055,
          "wheelRadiusM": 0.35, "efficiency": 0.95, "maxPedalingLeanAngleDeg": 20},
 "wind": {"windSpeed": 5.0, "windDirection": 270},
 "power": {"type": "constant", "power": 280, "useHarmonics": false,
           "criticalPower": 250, "wPrime": 20000,
           "pacing": false, "maxSlewWPerS": 0},
 "options": {"computeOnePointPerSecond": true,
             "wPrimeBalanceEnabled": true,
             "wPrimeBalanceCriticalPower": 250, "wPrimeBalanceWPrime": 20000}}
```

- `wind.windDirection` is in **degrees** and names the direction the wind blows *toward*. It is
  the same convention `vcDominantHeadwindAzimuth` returns, so that value feeds straight back in —
  no 180° flip.
- `power.type` is `constant`, `durability` (fades with work done above `criticalPower`),
  `critical-power` (spends the `wPrime` reserve, then settles at `criticalPower`) or `from_data`
  (replays the power recorded in the input path). Anything else is `-3`. The list comes from the
  engine's `PowerModel` catalog, shared with the CLI and the JS façade, so it cannot fall behind
  them again.
- `power.pacing` and `power.maxSlewWPerS` are **decorators**: they compose over whichever `type`
  was chosen, in the order `base → pacing → slew`. `maxSlewWPerS` is a rate in W/s and `0`
  disables it.
- `cyclist.roadCondition` is `dry` or `wet`, case-insensitive, and **overrides**
  `maxLeanAngleDeg` and `maxBrakeG` when present — wet takes grip from cornering *and* braking
  together. Omit it to keep the raw values.
- `options.wPrimeBalance*` calibrate the `wPrimeBalance` output field. The pass is **on by
  default** and always has been; these only set the CP and W′ it scores against.

**Changed in task 43.** `critical-power`, `wPrime`, `pacing`, `maxSlewWPerS`, `roadCondition` and
the three `wPrimeBalance*` keys are new — every one of them additive, so an existing payload keeps
its meaning. One value did move: omitting `power.power` now yields
`EngineConstants.DEFAULT_CYCLIST_POWER_W` = **280 W**, where this façade previously hardcoded
250 W. The CLI always used 280 W for the same unconfigured rider; the two are now the same number
because they read the same constant.

Unknown keys remain an error (`-3`), and the allowlist is derived from the catalog, so it cannot
drift from what the engine accepts.

### `vcAnalyzeRacingLineJson` — options and answer

Takes the `EnhanceOptionsDto` shape; only the `racingLine*` and `curvature*` keys are read, and
`0` means the defaults. Read-only — it reports what the stage *would* do and never moves a
coordinate, which is what makes an opt-in stage that rewrites every position inspectable.

```json
{"racingLineCorridor": "full-road", "racingLineRoadWidthM": 6.0}
```

The answer is a `RacingLineReportDto`-shaped object, or the literal `null` when the path cannot
be projected — too short, non-finite coordinates, or too near a pole. That `null` is a successful
call answering "no", so the return value is its byte length, not an error code.

### `vcDetectClimbsJson` — options

```json
{"minMinClimbElevationM": 10, "maxMinClimbElevationM": 35, "minClimbElevationRatio": 100,
 "minGradePercent": 3, "maxDiffRealGrade": 1.3, "booster": 1.3}
```

The six parameters of the JS `detectClimbsWithOptions`, under the same names. Omit the object
entirely (length `0`) for `detectClimbs`' defaults.

### `vcWriteGpx` / `vcWriteGpxTracks` — options

```json
{"writeExtensions": true, "trackName": "virtualized", "startTimeEpochMs": 1714550400000}
```

`writeExtensions: false` drops power, heart rate, cadence, temperature and the `gpxtpx`
namespace; `<ele>` and `<time>` are standard GPX and stay.

`startTimeEpochMs` is what `writeGpxAt` does on the JS side: **`<time>` = `startTimeEpochMs` +
`time(i)`**. On a simulated path, where `time(0) == 0`, that dates the ride as you would expect.
On a path that was merely *parsed*, `time(i)` still holds absolute epoch milliseconds and the
output lands decades in the future — enhance first, or leave the option out.

### `vcPathToCsv` / `vcPathToJson` — options

```json
{"separator": ",", "unitsInHeader": true, "decimals": null}
{"pretty": false, "decimals": null, "includeMeta": true}
```

`separator` takes its first character only.

### `vcPathToFit` / `vcPathsToFit` — payload

```json
{"name": "Col de la Madeleine", "startTimeEpochMs": 1785225600000, "interPathGapMs": 0}
```

**`startTimeEpochMs` is mandatory**, and `0` for the payload length is refused with
`ERR_INVALID_ARGUMENT` — the only export in this ABI with no usable default. A `Path`'s clock is
relative (`time(0) == 0`) and FIT has no way to express that, so an absent start would date every
course to 1989-12-31 rather than fail.

`interPathGapMs` only means anything to `vcPathsToFit`: it shifts each path after the first by
that many milliseconds, `0` running them straight on. It is not a pause — FIT expresses those
with `TIMER`/`PAUSE` events, which this port does not emit.

The output is **binary**, not UTF-8: a 14-byte header (`.FIT` at bytes 8..11), the data, and a
two-byte CRC. One `file_id`, one `course`, one `lap` per path, and each path's records bracketed
by a `TIMER`/`START`…`STOP` pair, the last one carrying `STOP_ALL`.

### Output shapes

`vcPointJson`, `vcParseGpxWaypointsJson`, `vcFieldDefinitionsJson`, `vcDetectClimbsJson` and
`vcAnalyzeRacingLineJson` emit the JS DTOs of `EngineJsApi` — `PointDto`, `WaypointDto`,
`FieldDefinitionDto` (plus `index`), `ClimbDto` with nested `ClimbPartDto`,
`RacingLineReportDto` with nested `CornerDto`. A consumer of `@glandais/vcyclist-engine` can
reuse its types unchanged.

**One shape cannot be mirrored exactly.** `RacingLineReportDto`'s per-point arrays carry `NaN`
where a value was not computed, and JSON has no `NaN` literal, so those slots arrive as `null`.
Read `null` and `NaN` as the same "not computed"; the JS `DoubleArray` keeps the real `NaN`.

Non-finite doubles are written as `null`: JSON has neither `NaN` nor `Infinity`, and
`dominantHeadwindAzimuthDeg` legitimately returns `NaN` for a symmetric loop.

## 10. Coming from `@glandais/vcyclist-engine`

One row per `@JsExport` of `EngineJsApi`. The table is generated from
[`WasiExportCatalog.kt`](../../engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiExportCatalog.kt),
and a JVM test fails the build if a JS export ever appears without a decision here.

| JavaScript | WASI | |
|---|---|---|
| `parseGpx` | `vcParseGpx` | ported |
| `parseGpxTracks` | `vcParseGpxMulti(mode=0)` | list handle instead of an array |
| `parseGpxSegments` | `vcParseGpxMulti(mode=1)` | idem |
| `parseGpxTracksOnly` | `vcParseGpxMulti(mode=2)` | idem |
| `parseGpxRoutesOnly` | `vcParseGpxMulti(mode=3)` | idem |
| `parseGpxWaypoints` | `vcParseGpxWaypointsJson` | JSON instead of objects |
| `writeGpx` | `vcWriteGpx` | `writeExtensions` moved into the options |
| `writeGpxAt` | `vcWriteGpx` | same export; `startTimeEpochMs` in the options |
| `writeGpxTracks` | `vcWriteGpxTracks` | **waypoints are not written** — the ABI has no waypoint handle |
| `pathSize`, `pathTotalDistance`, `pathDurationMs`, `pathElevationGain`, `pathElevationLoss` | `vcPathSize`, `vcPathTotalDistance`, `vcPathDurationMs`, `vcPathElevationGain`, `vcPathElevationLoss` | ported |
| `pathLatitudeDeg`, `pathLongitudeDeg` | `vcPathLatitudeDeg`, `vcPathLongitudeDeg` | ported |
| `pointAt` | `vcPointJson` | `PointDto` as JSON |
| `getField` | `vcGetField` | by field **index**, not by name |
| `fieldDefinitions` | `vcFieldDefinitionsJson` | JSON array, `index` included |
| `enhance` | `vcEnhance` | synchronous, not a `Promise` |
| `enhanceWithCourse` | `vcEnhanceWithCourse` | the five DTOs become sub-objects of one payload |
| `detectClimbs` | `vcDetectClimbsJson` | no options object means the defaults |
| `detectClimbsWithOptions` | `vcDetectClimbsJson` | same export; the six scalars become fields |
| `analyzeRacingLine` | `vcAnalyzeRacingLineJson` | JSON report; `NaN` slots arrive as `null` |
| `pathToCsv`, `pathToJson` | `vcPathToCsv`, `vcPathToJson` | arguments moved into the options |
| `dominantHeadwindAzimuth` | `vcDominantHeadwindAzimuth` | ported |
| `dominantHeadwindAzimuthOfTracks` | `vcDominantHeadwindAzimuthOfTracks` | takes a list handle |
| `pathToFit` | `vcPathToFit` | `name` and `startTimeEpochMs` moved into the payload |
| `pathsToFit` | `vcPathsToFit` | takes a list handle; `interPathGapMs` is a payload field |

Three things have no JS counterpart: `vcAbiVersion`, `vcSetElevationConfig`,
`vcTileGeometryJson`, plus the handle plumbing (`vcRelease`, `vcReleaseAll`, `vcListSize`,
`vcListGet`) and the bulk read `vcPathFieldBytes`.

## 11. Known limits

- **FIT export costs 183 KB of binary.** It works since w12 — the encoder is pure Kotlin over a
  multiplatform FIT SDK — but that SDK's `Mesg` reaches a `Factory` naming all 123 profile
  message classes, so dead-code elimination keeps every one of them although this encoder writes
  five. There is one binary and no way to opt out.
- **Only lossless WebP.** The module's own decoder (task w11) reads VP8L, which is what the
  reference DEM serves. A lossy `VP8 ` or extended `VP8X` file is refused with its fourcc named;
  a host facing one must decode it itself and use `"rgba"` mode.
- **No Component Model / WIT.** Core module, custom imports. Wrapping it with `wit-component` on
  the host side is possible — task w13 did it, and got a component that runs the engine correctly
  — and it is still not something this project does. The measurement, and why, is in
  [`wasm-wasi-component-model.md`](../archive/plans/wasm-wasi-component-model.md).
- **No concurrency.** One instance, one caller, tiles fetched one at a time.
- **`vcWriteGpxTracks` drops waypoints**, as noted above. Keep them from
  `vcParseGpxWaypointsJson` and merge them yourself if you need them.

## 12. See also

| Question | Where |
|---|---|
| A working host, in full | [`tools/wasi/host.py`](../../tools/wasi/host.py) — run by CI |
| How the target is built, and the compiler's rough edges | [`kotlin-wasm-wasi.md`](kotlin-wasm-wasi.md) |
| What remains to be done on WASI | [`PLAN-WASM-WASI.md`](../archive/plans/PLAN-WASM-WASI.md) |
| The JavaScript façade this mirrors | [`kotlin-js-jvm-webp.md`](kotlin-js-jvm-webp.md) |
| DEM tiles, attribution, coverage | [`elevation/README.md`](../../elevation/README.md#live-http-integration-tests) |
| How artefacts ship | [`publishing.md`](publishing.md) |
| Why there is no WIT / component | [`wasm-wasi-component-model.md`](../archive/plans/wasm-wasi-component-model.md) |
