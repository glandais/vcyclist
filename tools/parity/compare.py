#!/usr/bin/env python3
"""
Compare two parity dumps (TS vs Kotlin) produced by `ts/pipelineDump.ts` and
`:tools:parity:dumpPipeline`.

Reads the byte-identical `.f64` + `.json` stage format written by both sides and reports,
per stage and per field: max |delta| absolute, max |delta| relative, the index of the worst
case, and a verdict against the tolerance bands documented in vcyclist/CLAUDE.md.

    python3 compare.py <ts-dir> <kt-dir> [--json <out.json>] [--top N]

Only stdlib. Exit code is 0 whatever the verdict — this is a measurement tool, and the
report is the product; use --fail-on-diverged to make it gate a build instead.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys

# Tolerance model (vcyclist/CLAUDE.md "Numerical tolerances").
#
# A field passes when EITHER the relative delta is within 1e-9 (composed trig band) OR the
# absolute delta is below a floor where the difference is physically meaningless. The
# absolute floor matters: `bearing` and `grade` legitimately pass through zero, and a
# relative error against a ~1e-11 denominator is arithmetic noise, not a divergence.
REL_TOL_DEFAULT = 1e-9

ABS_FLOOR_BY_UNIT = {
    "radians": 1e-9,    # 1e-9 rad ~ 6 nm of arc on Earth's surface
    "meters": 1e-6,     # 1 micrometre
    "ms": 1e-6,         # 1 nanosecond
    "m/s": 1e-9,
    "watts": 1e-9,
    "%": 1e-9,          # grade is stored as a ratio
    "aero": 1e-12,
    "celsius": 1e-9,
    "bpm": 1e-9,
    "rpm": 1e-9,
}

FIELD_UNITS = {
    "latitude": "radians", "longitude": "radians", "distance": "meters", "dx": "meters",
    "time": "ms", "elapsed": "ms", "dt": "ms",
    "bearing": "radians",
    "elevation": "meters",
    "grade": "%",
    "radius": "meters",
    "aeroCoef": "aero",
    "windBearing": "radians", "windAlpha": "radians",
    "pAero": "watts", "pGravity": "watts", "pRollingResistance": "watts",
    "pWheelBearings": "watts", "pInputPower": "watts",
    "pCyclistProvidedOptimalPower": "watts",
    "pCyclistProvidedOptimalPowerWithHarmonics": "watts",
    "pCyclistPowerNeeded": "watts", "pCyclistProvidedMuscular": "watts",
    "pCyclistProvidedWheel": "watts", "pComputedTotalPower": "watts",
    "pComputedWheelPower": "watts", "pComputedPower": "watts",
    "speed": "m/s", "speedMax": "m/s", "speedMaxIncline": "m/s",
    "virtSpeedCurrent": "m/s",
    "temperature": "celsius", "windSpeed": "m/s", "windDirection": "radians",
    "heartRate": "bpm", "cadence": "rpm",
}

# Fields where drift accumulated over ~100 k integration steps is expected rather than a
# bug: they are running sums, so ULP noise compounds along the trace.
DRIFT_TOLERANT_FIELDS = {"distance", "time", "elapsed", "dt", "dx", "speed"}


def abs_floor(field: str) -> float:
    return ABS_FLOOR_BY_UNIT.get(FIELD_UNITS.get(field, ""), 1e-12)


def read_header(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def read_stage(dirname: str, stem: str) -> tuple[dict, list[float]]:
    header = read_header(os.path.join(dirname, stem + ".json"))
    with open(os.path.join(dirname, stem + ".f64"), "rb") as fh:
        raw = fh.read()
    count = len(raw) // 8
    values = list(struct.unpack("<%dd" % count, raw))
    expected = header["size"] * header["fieldCount"]
    if count != expected:
        raise SystemExit(
            f"{dirname}/{stem}.f64: {count} doubles but header says {expected}"
        )
    return header, values


def stems(dirname: str) -> list[str]:
    out = []
    for name in sorted(os.listdir(dirname)):
        if name.endswith(".json"):
            out.append(name[:-5])
    return out


def compare_field(
    a: list[float],
    b: list[float],
    field: str,
    fi: int,
    nfields: int,
    n: int,
) -> dict:
    """Return the worst absolute and relative deviation for one field across n points."""
    max_abs, max_abs_i = 0.0, -1
    max_rel, max_rel_i = 0.0, -1
    nan_mismatch = 0
    first_bad = -1
    floor = abs_floor(field)
    # Divergence profile: max relative delta over 10 equal slices of the trace. A flat or
    # rising ramp means accumulated drift; a step from 0 to a plateau means the two
    # implementations took different branches at one identifiable point.
    buckets = [0.0] * 10
    for i in range(n):
        x = a[i * nfields + fi]
        y = b[i * nfields + fi]
        x_nan, y_nan = math.isnan(x), math.isnan(y)
        if x_nan or y_nan:
            if x_nan != y_nan:
                nan_mismatch += 1
            continue
        d = abs(x - y)
        if d > max_abs:
            max_abs, max_abs_i = d, i
        scale = max(abs(x), abs(y))
        r = (d / scale) if scale > 0.0 else 0.0
        if r > max_rel:
            max_rel, max_rel_i = r, i
        if first_bad < 0 and d > floor and r > REL_TOL_DEFAULT:
            first_bad = i
        if n > 0:
            b_i = min(9, (i * 10) // n)
            if r > buckets[b_i]:
                buckets[b_i] = r
    return {
        "maxAbs": max_abs,
        "maxAbsIndex": max_abs_i,
        "maxRel": max_rel,
        "maxRelIndex": max_rel_i,
        "nanMismatch": nan_mismatch,
        "firstBadIndex": first_bad,
        "firstBadFraction": (first_bad / n) if (first_bad >= 0 and n) else None,
        "profile": buckets,
    }


def verdict_for(field: str, stat: dict) -> str:
    if stat["nanMismatch"]:
        return "NAN-MISMATCH"
    if stat["maxAbs"] == 0.0:
        return "EXACT"
    if stat["maxAbs"] <= abs_floor(field):
        return "OK"
    if stat["maxRel"] <= REL_TOL_DEFAULT:
        return "OK"
    if field in DRIFT_TOLERANT_FIELDS and stat["maxRel"] <= 5e-3:
        return "DRIFT"
    return "DIVERGED"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ts_dir")
    ap.add_argument("kt_dir")
    ap.add_argument("--json", dest="json_out")
    ap.add_argument("--top", type=int, default=6, help="worst N fields to print per stage")
    ap.add_argument("--fail-on-diverged", action="store_true")
    ap.add_argument(
        "--profile", action="store_true",
        help="print the max relative delta over 10 slices of the trace, per reported "
             "field: a rising ramp is accumulated drift, a step is a branch divergence",
    )
    ap.add_argument(
        "--rebase-time", action="store_true",
        help="subtract each side's own time[0] before comparing `time`. Required after "
             "VirtualizeService: the TS implementation seeds the simulation clock from "
             "new Date().getTime() (wall clock) while Kotlin seeds it from 0, so absolute "
             "timestamps are incomparable by construction (and vary run to run in TS).",
    )
    ap.add_argument(
        "--drop-last", type=int, default=0, metavar="N",
        help="exclude the last N points from the per-field comparison, to separate a "
             "last-point handling difference from a whole-trace divergence",
    )
    args = ap.parse_args()

    ts_stems, kt_stems = stems(args.ts_dir), stems(args.kt_dir)
    common = [s for s in ts_stems if s in kt_stems]
    report = {"stages": [], "tsOnly": [s for s in ts_stems if s not in kt_stems],
              "ktOnly": [s for s in kt_stems if s not in ts_stems]}

    any_diverged = False
    for stem in common:
        th, tv = read_stage(args.ts_dir, stem)
        kh, kv = read_stage(args.kt_dir, stem)

        nfields = th["fieldCount"]
        size_match = th["size"] == kh["size"]
        if not size_match:
            any_diverged = True
        n = max(0, min(th["size"], kh["size"]) - args.drop_last)

        fields = th["fields"]

        if args.rebase_time and "time" in fields and n > 0:
            ti = fields.index("time")
            for vals, hdr in ((tv, th), (kv, kh)):
                origin = vals[ti]
                for i in range(hdr["size"]):
                    vals[i * nfields + ti] -= origin
        stats = {}
        for fi, fname in enumerate(fields):
            st = compare_field(tv, kv, fname, fi, nfields, n)
            st["verdict"] = verdict_for(fname, st)
            if st["verdict"] in ("DIVERGED", "NAN-MISMATCH"):
                any_diverged = True
            stats[fname] = st

        aggregates = {}
        for key in ("totalDistance", "durationMs", "elevationGain", "elevationLoss"):
            a, b = th.get(key), kh.get(key)
            if a is None or b is None:
                continue
            d = abs(a - b)
            scale = max(abs(a), abs(b))
            aggregates[key] = {"ts": a, "kt": b, "abs": d,
                               "rel": (d / scale) if scale else 0.0}

        report["stages"].append({
            "stage": stem, "tsSize": th["size"], "ktSize": kh["size"],
            "sizeMatch": size_match, "comparedPoints": n,
            "fields": stats, "aggregates": aggregates,
        })

        flag = "" if size_match else f"  !! SIZE {th['size']} vs {kh['size']}"
        print(f"\n=== {stem}  (n={n}){flag}")
        ranked = sorted(stats.items(), key=lambda kv: kv[1]["maxRel"], reverse=True)
        worst = [x for x in ranked if x[1]["maxAbs"] > 0.0][: args.top]
        if not worst:
            print("    all 36 fields bit-identical")
        else:
            print(f"    {'field':<42}{'maxAbs':>13}{'maxRel':>12}{'@idx':>9}"
                  f"{'1st>tol':>9}  verdict")
            for fname, st in worst:
                fb = st["firstBadIndex"]
                fb_s = "-" if fb < 0 else (f"{fb}" if st["firstBadFraction"] is None
                                           else f"{fb}({st['firstBadFraction'] * 100:.0f}%)")
                print(f"    {fname:<42}{st['maxAbs']:>13.3e}{st['maxRel']:>12.3e}"
                      f"{st['maxRelIndex']:>9}{fb_s:>9}  {st['verdict']}")
            if args.profile:
                for fname, st in worst:
                    curve = " ".join(f"{v:.0e}" for v in st["profile"])
                    print(f"      profile {fname:<34} {curve}")
            exact = sum(1 for _, s in stats.items() if s["maxAbs"] == 0.0)
            print(f"    ({exact}/{len(fields)} fields bit-identical)")
        for key, agg in aggregates.items():
            print(f"    ~ {key:<20} ts={agg['ts']!r} kt={agg['kt']!r} rel={agg['rel']:.3e}")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
        print(f"\nJSON report -> {args.json_out}")

    if report["tsOnly"] or report["ktOnly"]:
        print(f"\nstages only in TS: {report['tsOnly']}   only in KT: {report['ktOnly']}")

    return 1 if (args.fail_on_diverged and any_diverged) else 0


if __name__ == "__main__":
    sys.exit(main())
