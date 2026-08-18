"""The Kotlin fixtures and their expected metrics, read straight out of the Kotlin sources.

The harness asserts the same numbers as `EnhancerParityTest`, on the same GPX documents. Copying
either into Python would create a second source of truth that drifts silently — and a parity
harness whose references are stale is worse than none, because it keeps passing.

So both are extracted from the sources at run time:

- the GPX documents from `GpxFixtures.kt`, where they are raw Kotlin strings;
- the expected metrics from `ParityFixtures.kt`, where each carries a quantified explanation of
  the tolerance it is asserted within.

If a fixture is renamed or moved, extraction fails loudly with the file it looked in, rather
than falling back to a stale copy.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

GPX_FIXTURES_KT = (
    REPO_ROOT / "gpx/src/commonTestFixtures/kotlin/io/github/glandais/engine/gpx/GpxFixtures.kt"
)
STELVIO_GPX = REPO_ROOT / "demo/public/gpx/stelvio.gpx"


def _read(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"{path} not found — did it move? The harness reads the Kotlin sources.")
    return path.read_text(encoding="utf-8")


def gpx_fixture(name: str) -> bytes:
    """The raw GPX behind `GpxFixtures.<name>`, as UTF-8 bytes."""
    text = _read(GPX_FIXTURES_KT)
    match = re.search(rf'val {name}: String =\s*"""(.*?)"""', text, re.S)
    if not match:
        raise LookupError(f"no `val {name}: String` triple-quoted fixture in {GPX_FIXTURES_KT}")
    return match.group(1).encode("utf-8")


#: Reference metrics for `vcEnhance` under :data:`PARITY_OPTIONS`, one entry per GPX fixture.
#:
#: These used to be read out of `ParityFixtures.kt`, which commit 865dd0b deleted along with the
#: rest of the TypeScript parity harness — and `run-all.sh` has raised `FileNotFoundError` on two
#: tests ever since. The values themselves stay useful: they are the Kotlin pipeline's own output,
#: measured on the JVM, so asserting them here is still what proves the wasmWasi target agrees with
#: the JVM one across an ABI. So they live here now, and this harness is self-contained.
#:
#: **When to update**: only when the physics or the resampling/simplification defaults change on
#: purpose. Print the measured values from a JVM run of the same pipeline and paste them in, with a
#: comment saying why they moved. `assertRelative` tolerates 0.5 %, which is the cross-target band
#: documented in `CLAUDE.md` — a change that trips these numbers is a change in behaviour.
PARITY_METRICS = {
    # SAMPLE_GPX — 7 trkpts, ~420 m, collapses to 3 points after Douglas-Peucker.
    "SAMPLE": {
        "totalDistance": 420.0556496172967,
        "totalElevationGain": 0.21774882435903464,
        "totalElevationLoss": -0.30713405604768695,
        "pointCount": 3,
        "durationMs": 49_000.0,
    },
    # GARMIN_GPX — 3 trkpts, ~14 m, ~18 s span; collapses to start + end.
    "GARMIN": {
        "totalDistance": 14.929920010888091,
        "totalElevationGain": 0.0,
        "totalElevationLoss": -0.004834919456122577,
        "pointCount": 2,
        "durationMs": 5_000.0,
    },
}


def parity_metrics(name: str) -> dict:
    """The five expected metrics for a fixture — see :data:`PARITY_METRICS`."""
    try:
        return PARITY_METRICS[name]
    except KeyError:
        raise LookupError(
            f"no parity metrics for {name!r} — known fixtures: {', '.join(sorted(PARITY_METRICS))}"
        ) from None


#: The options `EnhancerParityTest.runPipeline` uses: `EnhanceOptions.DEFAULT` minus the
#: elevation fetch. Spelled out because the WASI defaults are the *JS* ones (no 1 Hz resample,
#: no simplify), which would measure a different pipeline.
PARITY_OPTIONS = {
    "fixElevation": False,
    "computeMaxSpeeds": True,
    "virtualizeTrack": True,
    "computeOnePointPerSecond": True,
    "simplifyEnabled": True,
}
