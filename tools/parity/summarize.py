#!/usr/bin/env python3
"""
Aggregate the per-fixture JSON reports written by `compare.py` into the cross-fixture
table used in docs/parity.md.

    python3 summarize.py <reports-dir> --mode clock-pinned
"""

from __future__ import annotations

import argparse
import glob
import json
import os

STAGE_ORDER = [
    "00-parsed", "01-ppd-30", "02-ppd-2", "03-smooth",
    "04-maxspeed", "05-virtualize", "06-pointpersecond", "07-simplify",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("reports_dir")
    ap.add_argument("--mode", default="clock-pinned")
    ap.add_argument("--drop-last-note", action="store_true")
    args = ap.parse_args()

    reports = {}
    for path in sorted(glob.glob(os.path.join(args.reports_dir, f"*.{args.mode}.json"))):
        fixture = os.path.basename(path).split(".")[0]
        with open(path, encoding="utf-8") as fh:
            reports[fixture] = json.load(fh)

    fixtures = sorted(reports)
    print(f"mode: {args.mode}   fixtures: {', '.join(fixtures)}\n")

    # --- point counts ------------------------------------------------------
    print("## Point counts (TS / Kotlin)\n")
    header = f"| {'stage':<18} | " + " | ".join(f"{f:<15}" for f in fixtures) + " |"
    print(header)
    print("|" + "-" * 20 + "|" + "|".join(["-" * 17] * len(fixtures)) + "|")
    for stage in STAGE_ORDER:
        cells = []
        for f in fixtures:
            st = next((s for s in reports[f]["stages"] if s["stage"] == stage), None)
            if st is None:
                cells.append(f"{'-':<15}")
            elif st["sizeMatch"]:
                cells.append(f"{st['tsSize']:<15}")
            else:
                cells.append(f"{str(st['tsSize']) + '/' + str(st['ktSize']):<15}")
        print(f"| {stage:<18} | " + " | ".join(cells) + " |")

    # --- worst field per stage --------------------------------------------
    # Ranked by verdict severity first, then by relative delta. Ranking on maxRel alone is
    # misleading: a field passing through zero (grade on flat ground) shows rel = 2.0 at
    # abs = 7e-12, which is arithmetic noise, not a divergence.
    severity = {"NAN-MISMATCH": 3, "DIVERGED": 2, "DRIFT": 1, "OK": 0, "EXACT": 0}
    print("\n## Worst field per stage (ranked by verdict, then relative delta)\n")
    print(f"| {'stage':<18} | {'worst field':<28} | {'max|rel|':<10} | {'max|abs|':<11} "
          f"| {'fixture':<14} | verdict |")
    print("|" + "-" * 20 + "|" + "-" * 30 + "|" + "-" * 12 + "|" + "-" * 13
          + "|" + "-" * 16 + "|---------|")
    for stage in STAGE_ORDER:
        best = None
        n_div = 0
        for f in fixtures:
            st = next((s for s in reports[f]["stages"] if s["stage"] == stage), None)
            if st is None:
                continue
            for fname, fs in st["fields"].items():
                if severity[fs["verdict"]] >= 2:
                    n_div += 1
                key = (severity[fs["verdict"]], fs["maxRel"])
                if best is None or key > (severity[best[1]["verdict"]], best[1]["maxRel"]):
                    best = (fname, fs, f)
        if best is None:
            continue
        fname, fs, fixture = best
        suffix = f" ({n_div} diverged)" if n_div else ""
        print(f"| {stage:<18} | {fname:<28} | {fs['maxRel']:<10.2e} | {fs['maxAbs']:<11.2e} "
              f"| {fixture:<14} | {fs['verdict']}{suffix} |")

    # --- aggregates --------------------------------------------------------
    print("\n## Aggregates, final stage\n")
    print(f"| {'fixture':<15} | {'metric':<16} | {'TS':<22} | {'Kotlin':<22} | rel |")
    print("|" + "-" * 17 + "|" + "-" * 18 + "|" + "-" * 24 + "|" + "-" * 24 + "|-------|")
    for f in fixtures:
        last = reports[f]["stages"][-1]
        for key, agg in last["aggregates"].items():
            print(f"| {f:<15} | {key:<16} | {agg['ts']:<22.12g} | {agg['kt']:<22.12g} "
                  f"| {agg['rel']:.2e} |")

    # --- verdict census ----------------------------------------------------
    print("\n## Verdict census (field x stage x fixture)\n")
    census: dict[str, int] = {}
    for f in fixtures:
        for st in reports[f]["stages"]:
            for fs in st["fields"].values():
                census[fs["verdict"]] = census.get(fs["verdict"], 0) + 1
    total = sum(census.values())
    for verdict, n in sorted(census.items(), key=lambda kv: -kv[1]):
        print(f"  {verdict:<14} {n:>6}  ({100 * n / total:.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
