# Using vcyclist from Java

vcyclist is Kotlin-first. Two Kotlin features do not survive the trip to Java — **default
arguments**, which are a compile-time trick of the Kotlin compiler, and **`suspend` functions**,
which Java cannot call at all without hand-writing a `Continuation`. This guide is about the
bridges that give both back.

Nothing here adds behaviour. Every function delegates to the Kotlin original, which stays
public and is what a Kotlin caller should keep using.

## The shape of it

**Every entry point has a `…Jvm` twin, in the same package as the Kotlin original.** Call the
twin and every optional parameter becomes optional again.

| Module | Facades |
|---|---|
| `:gpx` | `GpxParserJvm`, `GpxWriterJvm`, `GpxToPathJvm`, `GpxFromPathJvm`, `GpxModelJvm`, `PathJvm`, `PathSimplifierJvm`, `ElevationStepJvm`, `TabularWritersJvm` |
| `:engine` | `EnhancerJvm`, `EngineModelJvm`, `ClimbDetectorJvm`, `PathWindJvm` |
| `:elevation` | `ElevationProviderJvm`, `TileFetcherJvm` |
| `:fit` | `PathToFitJvm` |
| `:map` | `MapFactoriesJvm` (`:map` is JVM-only, so this is the only door it has) |

For the asynchronous entry points — elevation lookups, and the `Enhancer` pipeline that may call
them — each bridge comes in two shapes:

| Shape | Suffix | Returns | For |
|---|---|---|---|
| Blocking | `…Blocking` | the value | batch jobs, CLIs, tests |
| Asynchronous | `…Async` | `CompletableFuture<T>` | servers, UIs |

The `suspend` original stays; the bridge is an addition.

## A complete round trip

```java
import io.github.glandais.elevation.ElevationProvider;
import io.github.glandais.elevation.ElevationProviderJvm;
import io.github.glandais.engine.EnhancerJvm;
import io.github.glandais.engine.gpx.GpxParserJvm;
import io.github.glandais.engine.gpx.GpxToPathJvm;
import io.github.glandais.engine.gpx.GpxWriterJvm;
import io.github.glandais.engine.path.Path;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

String xml = Files.readString(java.nio.file.Path.of("route.gpx"));
Path input = GpxToPathJvm.firstTrackAsPath(GpxParserJvm.parse(xml));

// Physics only — nothing touches the network:
Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(input);
String out = GpxWriterJvm.write(enhanced);

// Or with elevation correction, off the calling thread:
ElevationProvider provider = ElevationProviderJvm.newElevationProvider();
CompletableFuture<Path> future = EnhancerJvm.enhanceCourseDefaultAsync(input, provider);
```

Note `java.nio.file.Path` and `io.github.glandais.engine.path.Path` collide by simple name. Import
one and fully-qualify the other, as above.

## Calling rules

- **`…Blocking` parks the calling thread. Never call it from a UI thread, from inside a coroutine,
  or from a thread of the executor you passed** — the first two freeze the caller, the third can
  deadlock the pool. Exceptions propagate unchanged.
- **`…Async` takes an optional `java.util.concurrent.Executor`** (default: the coroutines IO
  dispatcher, the right pool for network-bound work). Cancelling the returned future cancels the
  work underneath; failures arrive wrapped as a `CompletionException`, so unwrap with
  `getCause()`.
- **Everything else in the library is already synchronous and needs no bridge**: `GpxParser`,
  `GpxWriter`, `ElevationStep.smoothElevation`, `PathSimplifier`, the resamplers, FIT and the
  CSV / JSON writers. They still have `…Jvm` twins, but only for the default arguments.

## Building the inputs

Kotlin data classes have defaulted constructors, so Java would otherwise have to pass every
field. `EngineModelJvm` supplies `@JvmOverloads` factories instead:

```java
import io.github.glandais.engine.Bike;
import io.github.glandais.engine.Cyclist;
import io.github.glandais.engine.EnhanceOptions;
import io.github.glandais.engine.EngineModelJvm;

Cyclist rider = EngineModelJvm.cyclist(72.0);              // 72 kg, everything else default
Bike bike = EngineModelJvm.bike();
EnhanceOptions options = EngineModelJvm.enhanceOptions();  // also simplifyPathOptions()
```

The pipeline's sub-option objects have factories of their own, so every stage is configurable
from Java:

```java
EnhanceOptions tuned =
    EngineModelJvm.enhanceOptions(
        false, true, true, true,
        EngineModelJvm.simplifyPathOptions(true, 10.0, 3.0),
        EngineModelJvm.wPrimeBalanceOptions(true, 260.0, 22000.0),
        EngineModelJvm.curvatureOptions(true),
        EngineModelJvm.racingLineOptions(true, CorridorMode.LANE, 6.0));
```

`racingLineOptions` exposes the three knobs the CLI, JS and WASI doors expose, not all 23 of
`RacingLineOptions` — the other twenty were tuned by measurement and are not part of any door's
public surface. Reach for Kotlin if you need one.

`GpxModelJvm` does the same for the GPX model (`trackPoint`, `waypoint`, `track`, `document`),
`TabularWritersJvm` for the export options (`csvOptions`, `jsonOptions`), and
`ClimbDetectorJvm.climbOptions` for climb detection.

These factories call their data-class constructors positionally, which keeps Java call sites
compiling when a field is appended — and is how four capabilities once became unreachable from
Java while every other door exposed them. `EngineModelJvmCoverageTest` now fails the build when a
factory falls behind its class.

Defaults are meant to come from `EngineConstants` (or from the stage's own options object), never
from a literal restated here — so they cannot drift from what the CLI, the JavaScript façade and
the WASI module do. **Two facades break that rule today**: `ClimbDetectorJvm.climbOptions` restates
seven values and `EngineModelJvm.simplifyPathOptions` / `curvatureOptions` eight more. Step S6 of
[`surface-alignment.md`](../tasks/surface-alignment.md) fixes it and adds the
`factory() == DataClass()` assertion that keeps it fixed.

### Assembling a whole ride

```java
Course course = EngineModelJvm.course(samplePath(), EngineModelJvm.cyclist(72.0), EngineModelJvm.bike());
CoursePhysics physics =
    EngineModelJvm.coursePhysics(
        course, EngineModelJvm.cyclistPowerSpec(PowerModel.CRITICAL_POWER).toProvider());

Path enhanced = EnhancerJvm.enhanceCourseBlocking(physics);
```

`CyclistPowerSpec` is the class the CLI, the JS façade and the WASI module all parse into, and it
had no factory until S5 — changing one field meant all seven arguments positionally, naming
`DEFAULT_CYCLIST_POWER_W` and friends yourself, because `copy()` is Kotlin-only. `CoursePhysics` and
`Course` were in the same position, and `EnhancerJvm.enhanceCourseBlocking` needs a `CoursePhysics`
that no factory produced.

**`coursePhysics` does not take its parameters in `CoursePhysics`' order**, and that is deliberate.
`@JvmOverloads` truncates from the right, so the order decides what you can omit: the data class
puts `cyclistPowerProvider` last, which would have made the one provider anybody overrides reachable
only by also naming the three nobody touches. The factories are ordered by how often each argument
is actually set.

## Outputs

```java
import io.github.glandais.engine.climb.ClimbDetectorJvm;
import io.github.glandais.engine.gpx.GpxWriterJvm;
import io.github.glandais.engine.io.TabularWritersJvm;
import io.github.glandais.engine.path.PathSimplifierJvm;
import io.github.glandais.fit.PathToFitJvm;

String gpx  = GpxWriterJvm.write(enhanced);
String csv  = TabularWritersJvm.writeCsv(enhanced);
String json = TabularWritersJvm.writeJson(enhanced);
byte[] fit  = PathToFitJvm.toFitBytes(enhanced, "My route", startEpochMs);

Path smaller = PathSimplifierJvm.simplify(enhanced, 10.0);        // Douglas-Peucker 3D

// A bare GPX — no <extensions>, so no power, heart rate, cadence or temperature.
// writeExtensions is the fifth parameter of this overload, so the ones before it
// must be spelled out even to take their defaults:
String bare = GpxWriterJvm.write(enhanced, "noname", null, null, false);
var climbs   = ClimbDetectorJvm.detect(enhanced);
```

FIT has no relative clock, so the start instant is **mandatory**. `PathToFitJvm` takes
either a `kotlin.time.Instant` or a plain `long` of epoch milliseconds, so Java does not have to
name a Kotlin type to get one. The output is a Course file — a
route to follow — which is what a virtualized trace should be, not an Activity.

Static map rendering lives in `:map` and is JVM-only by construction (it draws on `java.awt`).
Its factories are in `MapFactoriesJvm`; see [`map/README.md`](../../map/README.md), which also
covers the tile-source usage-policy obligations — vcyclist ships no default tile source for maps.

## Bringing your own tile transport

`ElevationProviderJvm.newElevationProvider(config, fetcher)` takes a
`java.util.function.Function<String, RawTile>`, so a disk cache — or any other transport — plugs
in from Java. The fetcher may block (it runs on the IO dispatcher) but **must be thread-safe**: up
to ten tiles are fetched at once. `elevation/README.md` has a complete example, and
`TileFetcherJvm` exposes the default fetch/decode steps individually
(`fetchTileBytesBlocking`, `decodeTileBytesBlocking`, `fetchAndDecodeTileBlocking`, each with an
`…Async` twin) if you only want to wrap one of them.

`getElevationsAlongBlocking` / `…Async` sample elevations along a path, densifying to `step` metres:

```java
List<CoordinatesElevation> along =
    ElevationProviderJvm.getElevationsAlongBlocking(
        provider, path, 25.0, 1.0, true,
        ElevationProviderJvm.smoothingOptions(50.0, true),
        ElevationProviderJvm.filterOptions(10.0, 3.0, true));
```

Until S5 this was the one member of the public façade a Java caller could not invoke at all without
hand-writing a `Continuation` — and the one with five defaulted parameters, so also the one that
most needed the ladder. Its two option types had no factory either.

## Why the facades exist at all, and how they are kept honest

`kotlin.jvm.*` annotations **do not resolve from a common source set** — `@JvmOverloads`,
`@JvmStatic`, all of them — and the whole API lives in `commonMain`. So the overload ladder is
generated on a JVM-only delegate instead, with `@file:JvmName` giving it the class name Java sees.

The indirection buys one thing beyond brevity: adding a defaulted parameter to a common function
no longer breaks Java callers, as long as the facade keeps its own signature.

Java callability is something no Kotlin test can check, so each module carries Java tests under
`src/jvmTest/java/`, run as part of `jvmTest`. If a bridge disappears, they stop compiling.

`EngineModelJvmCoverageTest` checks two things across every `*Jvm.kt` facade: that each factory's
widest overload has the **arity** of the data class's primary constructor, and that calling it with
no arguments **equals** constructing the data class with none. The second is what catches a factory
restating `10.0` where the class now says `12.0` — right arity, wrong answer, and invisible to every
key check. It does not check parameter *order*, deliberately, because `coursePhysics` reorders on
purpose (above).

The coverage ledger grew a JVM/Java column in August 2026 precisely because a capability can be ✅ on
all four wire doors and out of Java's reach — see
[`surface-coverage.md`](../ledgers/surface-coverage.md).

## See also

- [`kotlin-js-jvm-webp.md`](kotlin-js-jvm-webp.md) — the interop conventions behind these facades
- [`using-from-javascript.md`](using-from-javascript.md) — the same surface from JS / TypeScript
- [`../../elevation/README.md`](../../elevation/README.md) — DEM tiles, attribution, coverage
- [`../../map/README.md`](../../map/README.md) — static map rendering, and choosing a tile source
