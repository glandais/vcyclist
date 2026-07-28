# Add a `wasmWasi` target with a purely numeric façade

## Context

vcyclist compiles to JVM, JS (Node + browser) and Wasm/JS (browser). Every non-JVM target
therefore needs a JavaScript host: the `@JsExport` façades (`EngineJsApi`, `ElevationJsApi`)
speak in `JsReference`, `JsArray`, `String` and `Promise`, all of which exist only because a JS
engine sits on the other side of the boundary.

A `wasmWasi` target removes that constraint. The engine becomes a standalone `.wasm` reactor
module runnable under wasmtime, WasmEdge, Deno, or embedded in a Go/Rust/Python/JVM host through
any WASM runtime — no JS engine, no npm, no DOM. The cost is that the ABI must be **purely
numeric**: `@WasmExport` rejects `String`, `ByteArray` and every class outright, so objects become
integer handles and strings/bytes travel through linear memory with an explicit alloc protocol.

Two capabilities have no WASI-compatible implementation today and must be rewritten in pure
Kotlin — they are the substance of this task, not a footnote:

- **WebP decoding** — TwelveMonkeys (JVM), `createImageBitmap` + canvas (browsers),
  `@jsquash/webp` (Node). All three need a host runtime WASI does not have.
- **FIT encoding** — the Garmin Java SDK (JVM) and `@garmin/fitsdk` (JS/Wasm).

### Decisions taken with the user

1. Tiles reach the module through a **host callback declared with `@WasmImport`**.
2. Pure-Kotlin **VP8L decoder + FIT encoder**, wired as the `wasmWasi` `actual` only. JVM, JS and
   wasmJs keep TwelveMonkeys / jsquash / Garmin unchanged.
3. All four KMP modules (`:gpx`, `:elevation`, `:fit`, `:engine`) gain the target; the façade lives
   in `engine/src/wasmWasiMain`.
4. CI runs **Node only** (`wasmWasiNodeTest`), joining `./gradlew check` with no new toolchain.

---

## What exploration established (verified, not assumed)

**The target works and produces a reactor.** I parsed the compiled `.wasm` from JetBrains'
`kotlin-wasm-wasi-template`, which is checked out and already built at
`/home/glandais/code/other/kotlin-wasm-wasi-template`. Exports are exactly `_initialize`, `memory`
and the `@WasmExport` functions; there is **no `_start` and no start section**, and the generated
`.mjs` calls `wasi.initialize()`, not `wasi.start()`. Exports are callable repeatedly. At least one
`@WasmExport` must exist or WasmEdge skips the reactor init — that is why the template carries a
`@WasmExport fun dummy() {}`. Its memory declares **min 0 pages**, unbounded: Kotlin objects live on
the WasmGC heap, and linear memory is used *only* by `kotlin.wasm.unsafe`.

**The dependencies are all there.** `kotlinx-coroutines-core:1.11.0`, `xmlutil:1.0.1` and
`kotlinx-coroutines-test:1.11.0` all publish `wasm-wasi` variants (checked in the Gradle module
metadata and on Maven Central). Those are the *only* third-party dependencies in the `commonMain`
of the four modules. So GPX parse/write, the physics pipeline, the resamplers and the simplifier
port with **zero source changes**, and `commonTest`'s `runTest` compiles. `kotlinx-browser` has no
wasi variant but is only ever used from `jsMain`/`wasmJsMain`. Ktor is unavailable — irrelevant,
since HTTP is host-injected by decision 1.

**Only two `expect`s block the target**: `elevation/src/commonMain/.../TileFetcher.kt:16` and
`fit/src/commonMain/.../FitEncoder.kt:23`, plus `IntegrationGate.kt` in `elevation/src/commonTest`.

**Terrarium tiles are lossless VP8L** — confirmed on the inline fixture *and* on a live tile
(`curl` of `tiles.mapterhorn.com/12/2126/1459.webp` → `RIFF`/`WEBP`/`VP8L`, 372 KB, 512×512). A
VP8L-only decoder is complete for this project; lossy VP8 (3000–5000 lines) is not needed.

**`TileManager(urlTemplate, cacheSize, fetcher: suspend (String) -> RawTile = ::fetchAndDecodeTile)`**
already takes the fetcher as a lambda — the injection seam exists.

### The one hard constraint that shapes the whole ABI

Kotlin does **not** export `malloc`/`free`. `withScopedMemoryAllocator` is a bump allocator whose
cursor resets to address 0 on scope exit, so a pointer cannot naively outlive the call. The only
persistent arena is semi-internal:

```kotlin
@ComponentModelInternalApi fun componentModelRealloc(originalPtr: Int, originalSize: Int, newSize: Int): Int
@ComponentModelInternalApi fun freeAllComponentModelReallocAllocatedMemory()
```

It survives across host calls, but carries two rules that are load-bearing:

1. **No per-pointer free** — it is a bump allocator; you can only free everything.
2. **While the arena is live, `withScopedMemoryAllocator` throws** (`createAllocatorInTheNewScope`
   starts with `check(reallocAllocator == null)`). That means `println`, `Clock.System.now()`,
   `Random`, and every WASI syscall are **forbidden between `vcAlloc` and `vcFreeAll`** — they all
   go through the scoped allocator. `Random.Default` is genuinely reachable from the pipeline
   (`PowerProviderConstant`, `PowerProviderConstantWithTiring`, `CyclistPowerProviderBase` all take
   `random: Random = Random.Default`), so this is not hypothetical.

Hence the ABI rule below: **the arena is live only inside a narrow transfer window; no compute
export may run while it is live.** Nested scopes also throw, so the tile callback must open exactly
one scope and the host must not re-enter the module during it.

---

## Step 1 — Build wiring

Add to `gpx/`, `elevation/`, `fit/`, `engine/build.gradle.kts`, next to the existing `wasmJs` block:

```kotlin
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
wasmWasi { nodejs() }        // + binaries.executable() on :engine only
```

- `nodejs()` is required — KGP warns `WasmWasiEnvironmentNotChosenExplicitly` otherwise.
- Only `:engine` needs `binaries.executable()`; the other three are libraries consumed via `api()`.
- No `generateTypeScriptDefinitions()`, no `packageJson`, no npm publication — WASI is not an npm
  artifact. Maven Central publication comes free from the root `subprojects` block.
- Shippable output lands at
  `engine/build/compileSync/wasmWasi/main/productionExecutable/optimized/*.wasm` (wasm-opt'd).
- `wasmWasiTest` needs `implementation(kotlin("test"))`.
- The `INTEGRATION` env propagation in `engine/` and `elevation/build.gradle.kts` filters on
  `KotlinJsTest`; `wasmWasiNodeTest` **is** a `KotlinJsTest`, so it should be picked up — verify
  rather than assume.

---

## Step 2 — Pure-Kotlin VP8L decoder (`:elevation`)

New `elevation/src/commonMain/kotlin/io/github/glandais/elevation/webp/` — in `commonMain`, not
`wasmWasiMain`, so it is **tested on all five targets** even though only WASI uses it in production.

- `RiffParser.kt` — walk the RIFF container, locate `VP8L`, reject `VP8 `/`VP8X` with a message
  naming the unsupported fourcc. (Mapterhorn emits bare `VP8L`: no ALPH, ANIM or ICCP to handle.)
- `Vp8lBitReader.kt` — LSB-first bit reader; 5-byte VP8L header (signature `0x2F`, 14-bit width-1,
  14-bit height-1, alpha hint, version).
- `HuffmanTable.kt` — canonical codes, the 19-symbol code-length-code permutation, and the
  simple-code (1–2 symbol) shortcut.
- `Vp8lDecoder.kt` — 5 Huffman trees per meta-group (green+length+dist 280, R/B/A 256, distance 40),
  the meta-Huffman entropy image, LZ77 backward references with the 120-entry distance map, and the
  color cache (`(0x1e35a7bd * argb) ushr (32 - cacheBits)`).
- `Vp8lTransforms.kt` — the four inverse transforms applied in reverse order of signalling:
  predictor (14 modes), color transform, subtract-green, color-indexing (incl. pixel bundling).

Output is packed RGBA — exactly what `RawTile(width, height, rgba)` requires.

Useful ports to read: `https://github.com/fencl/whale` (minimal VP8L-only) and
`https://github.com/KarpelesLab/gowebp` (pure Go). Spec:
`https://developers.google.com/speed/webp/docs/webp_lossless_bitstream_specification`.

**Validation is why this is safe to attempt** — both fixtures already exist:

- `InlineWebpFixture` (`elevation/src/commonTest/.../InlineWebpFixture.kt`) — a 4×1 VP8L with known
  exact RGBA, offline. New `Vp8lDecoderTest` in `commonTest` asserts byte equality.
- `ReferenceTile.RGBA_SHA256` — the real 512×512 tile digest produced by the JVM decoder. A new
  `INTEGRATION=1`-gated test decodes the live tile with the Kotlin decoder and asserts the same
  digest.

Byte-exactness is not negotiable: the fixture KDoc records that ±1 on B is 4 mm and ±1 on R is
**256 m**. Also add a `commonTest` cross-check of the pure-Kotlin decoder against the bytes the JVM
`ImageIO` path produces, so drift is caught without network.

---

## Step 3 — Host tile callback (`:elevation`, `wasmWasiMain`)

`elevation/src/wasmWasiMain/kotlin/.../TileFetcher.wasmWasi.kt`, using the pattern JetBrains
endorses (guest owns the scope; host reads/writes while it is open):

```kotlin
@WasmImport("vcyclist", "fetch_tile")
private external fun hostFetchTile(urlPtr: Int, urlLen: Int, dstPtr: Int, dstCap: Int): Int
```

Contract:

- `actual suspend fun fetchAndDecodeTile(url)` opens **one** `withScopedMemoryAllocator`, writes the
  URL as UTF-8, allocates a destination buffer, and calls the import.
- The host writes at most `dstCap` bytes and returns the **full** byte length, or a negative errno.
- If the returned length exceeds `dstCap`, the guest retries with a larger buffer — so the host must
  tolerate being asked for the same URL twice. Documented explicitly.
- The guest copies the bytes into a Kotlin `ByteArray` **before the scope closes**, then decodes
  with Step 2.
- The host must not call back into any module export during the callback: that would open a nested
  scope, which throws.

It is synchronous despite being `suspend`. Every host must supply this import — imports are
mandatory at instantiation — so ship a documented no-op stub (`return -1`) for hosts that never use
elevation.

---

## Step 4 — Pure-Kotlin FIT encoder (`:fit`)

New `fit/src/commonMain/kotlin/io/github/glandais/fit/PureFitEncoder.kt`, plus
`fit/src/wasmWasiMain/.../FitEncoder.wasmWasi.kt` delegating to it.

Most semantics already exist in `commonMain` and are reused rather than re-derived:
`FitCourse`/`FitRecord`/`FitLap` (message model), `FitUnits` (semicircles, the 1989-12-31 FIT epoch)
and `FitMessageNumbers` (the wire constants both web actuals already share). What is new is only
the binary layer: file header, definition + data message records, little-endian base types, and the
two CRC-16s.

**Message and field ordering is load-bearing** — definition messages derive from key order, and
`FitReferenceBytes` already pins the JS and Wasm encoders byte-identical. The new encoder must
reproduce those same bytes, which turns this from "hope it's right" into a hard assertion. Add
`PureFitEncoderTest` in `commonTest` (runs on every target) asserting equality against
`FitReferenceBytes`, and keep the JVM `FitRoundTripTest` decoding the pure encoder's output with the
Garmin SDK.

---

## Step 5 — The numeric façade (`engine/src/wasmWasiMain/.../EngineWasiApi.kt`)

Mirrors `EngineJsApi` in coverage; every signature is `Int`/`Double`. All functions carry
`@OptIn(ExperimentalWasmInterop::class)` and explicit `@WasmExport("vc…")` names.

**Memory ABI (`WasiMemory.kt`)** — `vcAlloc(size): Int` over `componentModelRealloc`, and
`vcFreeAll()` over `freeAllComponentModelReallocAllocatedMemory`. There is deliberately no
per-pointer `vcFree`: the underlying allocator is a bump arena and pretending otherwise would be a
lie in the ABI. The **transfer-window discipline** is the core rule:

```
vcAlloc(n) → host writes bytes → vcParseGpx(ptr, n) [copies to GC heap] → vcFreeAll()
                                 ↑ arena live: no compute, no logging, no Random
… compute freely (arena not live) …
vcBufLen(h) → vcAlloc(len) → vcBufCopyTo(h, ptr) → host reads → vcFreeAll()
```

Every compute export begins by asserting the arena is not live and returns a negative sentinel
rather than letting an opaque `IllegalStateException` escape. Strings cross as UTF-8 `(ptr, len)`.

**Handle table (`WasiHandles.kt`)** — `Int → Any` with a monotonic counter, holding `Path`,
`ElevationProvider` and result buffers; `vcRelease(handle)` drops one. This replaces
`JsReference<Path>`, which does not exist off the JS boundary. Keeping results on the GC heap and
copying only in the transfer window is what makes the arena rule tractable.

**Coroutine driver** — the pipeline is `suspend` and `Flux.kt` uses `coroutineScope { async { } }`.
There is no event loop on WASI, so drive with `startCoroutine` on `Dispatchers.Unconfined`, which
runs children inline and completes synchronously because the tile callback is synchronous. Assert
completion and raise a clear error if the coroutine ever actually suspends, rather than silently
returning a zero handle. (`Dispatchers.IO` does not exist on wasi; it is not used in `commonMain`.)

Exported surface, one-for-one with the JS façade:

| JS façade | WASI export |
|---|---|
| `parseGpx(xml): JsReference<Path>` | `vcParseGpx(ptr, len): Int` (path handle) |
| `parseGpxTracks` / `parseGpxSegments` | `vcParseGpxTracks(ptr, len): Int` (handle to an int array) + `vcArrayLen` / `vcArrayGet` |
| `pathSize` / `pathTotalDistance` / `pathDurationMs` / `pathElevationGain` / `pathElevationLoss` | same names, `(handle) -> Int`/`Double` — already numeric |
| `pointAt(h, i): PointDto` | `vcGetField(h, i, fieldId: Int): Double`, field ids = `PointField` ordinals |
| `writeGpx` / `writeGpxAt` / `pathToCsv` / `pathToJson` | return a **buffer handle**; read via `vcBufLen` / `vcBufCopyTo` / `vcRelease` |
| `pathToFit(h, name, startMs): Uint8Array` | `vcPathToFit(h, namePtr, nameLen, startMs): Int` (buffer handle) |
| `enhance(h, EnhanceOptionsDto): Promise<…>` | `vcEnhance(h, flags: Int, tolerance: Double, zExag: Double): Int` — synchronous |
| `detectClimbs` / `detectClimbsWithOptions` | `vcDetectClimbs(h): Int` + numeric per-climb accessors |
| — | `vcLastErrorLen()` / `vcLastErrorCopyTo(ptr)` — exceptions cannot cross a WASI boundary, so every export catches and returns a negative sentinel |

Reuse what `jsMain` already proves out: `getField(path, i, prop)` and `fieldDefinitions()` exist
there, and `vcGetField` uses the same `PointField` catalog keyed by ordinal.

---

## Step 6 — Docs

- New `docs/wasi-abi.md` — the authoritative host guide: the transfer-window rule and *why* it
  exists, handle lifetimes, the `fetch_tile` contract (retry-on-short-buffer, no re-entrancy, no-op
  stub), the error-code table, and a worked wasmtime example.
- `docs/kotlin-wasm-jvm-webp.md` §5 currently says only *"il faut porter un décodeur"* — replace
  with what was ported, and record the `componentModelRealloc` constraint.
- `CLAUDE.md` — add the target to the module table and build commands, and fix the stale Tools
  section: it says Kotlin 2.3.21 / xmlutil 0.91.3 / imageio 3.13.1, the catalog says
  **2.4.10 / 1.0.1 / 3.14.0**.
- `docs/PLAN.md` — new phase rows; task specs under `docs/tasks/` in the house format.

---

## Verification

```bash
./gradlew :elevation:allTests          # VP8L decoder on all 5 targets, incl. wasmWasiNodeTest
./gradlew :fit:allTests                # pure FIT encoder byte-equal to FitReferenceBytes
./gradlew :engine:wasmWasiNodeTest     # façade smoke
./gradlew check                        # full matrix, no regression on JVM/JS/wasmJs
./gradlew ktlintCheck
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ReferenceTile*'   # live-tile digest
```

End-to-end outside Kotlin — the real proof the ABI works for a non-JS host:

1. Build the optimized `.wasm` (Step 1 path above).
2. Instantiate under `wasmtime` (flags: `-W function-references,gc,exceptions`) or a small Node
   script, supplying the `vcyclist.fetch_tile` import, then run
   `vcAlloc` → write `sample.gpx` UTF-8 → `vcParseGpx` → `vcFreeAll` → `vcEnhance` → `vcWriteGpx` →
   `vcBufLen`/`vcAlloc`/`vcBufCopyTo` → read back → `vcRelease`/`vcFreeAll`.
3. Assert the resulting distance/duration match `:cli` on the same file within the 0.5 % pipeline
   tolerance from `CLAUDE.md` — this cross-checks the whole port, not just the ABI.
4. Deliberately violate the transfer-window rule (call `vcEnhance` with the arena live) and confirm
   it returns the error sentinel instead of trapping.
5. Loop the round-trip and check `memory.size` plateaus rather than growing without bound.

## Risks and sequencing

- **`componentModelRealloc` is `@ComponentModelInternalApi`** — it works today but JetBrains
  reserves the right to change it. If it becomes unusable, the fallback is the pure callback ABI
  (host calls `vcParseGpxViaCallback(callId)`; the guest opens a scope and calls a host
  `read_input(callId, ptr, cap)` import), which uses no internal API at all but abandons the
  alloc/free shape. Confirm the arena behaves as documented in a throwaway spike **before** writing
  the façade.
- **VP8L is the largest single piece** (~800–1500 lines), de-risked by two byte-exact fixtures that
  already exist. Land it green on its own before Step 5.
- Watch `https://github.com/bytecodealliance/wasmtime/issues/9701` — repeated host calls into a
  Kotlin guest using `withScopedMemoryAllocator` have hit GC-heap OOM. Step 5 of the verification
  loop is there to catch it.
- `:engine` `api()`-depends on the other three modules, so they must gain the target first: the step
  order here is a hard dependency order, not a preference.
- A pure-Kotlin VP8L decoder plus a pure-Kotlin FIT encoder would eventually let JVM/JS drop
  TwelveMonkeys, `@jsquash/webp` and `@garmin/fitsdk` entirely. That is explicitly **out of scope**
  here (decision 2 keeps existing targets untouched), but worth noting as the natural follow-up.
