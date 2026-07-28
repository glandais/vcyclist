"""The Kotlin fixtures and their expected metrics, read straight out of the Kotlin sources.

The harness asserts the same numbers as `EnhancerParityTest`, on the same GPX documents. Copying
either into Python would create a second source of truth that drifts silently — and a parity
harness whose references are stale is worse than none, because it keeps passing.

So both are extracted from the sources at run time:

- the GPX documents from `GpxFixtures.kt`, where they are raw Kotlin strings;
- the expected metrics from `ParityFixtures.kt`, where each carries the TS reference value and
  a quantified explanation of the gap (see docs/parity.md).

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
PARITY_FIXTURES_KT = (
    REPO_ROOT / "engine/src/commonTest/kotlin/io/github/glandais/engine/parity/ParityFixtures.kt"
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


def parity_metrics(name: str) -> dict:
    """The five expected metrics of `ParityFixtures.<name>`.

    Reads the named `ParityMetrics(...)` block and pulls `field = <number>` out of it, ignoring
    the comment lines that carry the TS reference values — those are documentation, not
    assertions (docs/parity.md explains why the TS numbers are deliberately not asserted).
    """
    text = _read(PARITY_FIXTURES_KT)
    match = re.search(rf"val {name} =\s*ParityMetrics\((.*?)\n        \)", text, re.S)
    if not match:
        raise LookupError(f"no `val {name} = ParityMetrics(...)` in {PARITY_FIXTURES_KT}")

    body = "\n".join(line for line in match.group(1).splitlines() if not line.strip().startswith("//"))
    fields = {}
    for key in ("totalDistance", "totalElevationGain", "totalElevationLoss", "pointCount", "durationMs"):
        value = re.search(rf"{key}\s*=\s*(-?[0-9_.eE+-]+)", body)
        if not value:
            raise LookupError(f"{name}.{key} not found in {PARITY_FIXTURES_KT}")
        fields[key] = float(value.group(1).replace("_", ""))
    fields["pointCount"] = int(fields["pointCount"])
    return fields


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
