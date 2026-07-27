#!/usr/bin/env python3
"""
Compare the two flat {key: number} maps written by the unit-level parity runners.

    python3 compare-units.py <ts-results.json> <kt-results.json> [--tol 1e-9]

Reports, per key: absolute delta, relative delta and a verdict; plus any key present on
only one side (a key mismatch means the two runners disagree about what was evaluated,
which is a harness bug, not a parity result).
"""

from __future__ import annotations

import argparse
import json
import math


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ts_file")
    ap.add_argument("kt_file")
    ap.add_argument("--tol", type=float, default=1e-9)
    ap.add_argument("--abs-floor", type=float, default=1e-9)
    ap.add_argument("--show-all", action="store_true")
    args = ap.parse_args()

    with open(args.ts_file, encoding="utf-8") as fh:
        ts = json.load(fh)
    with open(args.kt_file, encoding="utf-8") as fh:
        kt = json.load(fh)

    only_ts = sorted(set(ts) - set(kt))
    only_kt = sorted(set(kt) - set(ts))
    common = sorted(set(ts) & set(kt))

    worst = []
    exact = 0
    for k in common:
        a, b = ts[k], kt[k]
        a_nan, b_nan = (a is None or (isinstance(a, float) and math.isnan(a))), \
                       (b is None or (isinstance(b, float) and math.isnan(b)))
        if a_nan or b_nan:
            if a_nan != b_nan:
                worst.append((float("inf"), k, a, b, "NAN-MISMATCH"))
            else:
                exact += 1
            continue
        d = abs(a - b)
        if d == 0.0:
            exact += 1
            continue
        scale = max(abs(a), abs(b))
        rel = d / scale if scale else 0.0
        verdict = "OK" if (rel <= args.tol or d <= args.abs_floor) else "DIVERGED"
        worst.append((rel, k, a, b, verdict))

    worst.sort(key=lambda t: -t[0])
    diverged = [w for w in worst if w[4] != "OK"]

    print(f"keys: {len(common)} compared, {exact} bit-identical, "
          f"{len(worst)} differing, {len(diverged)} beyond tolerance")
    if only_ts or only_kt:
        print(f"\n!! key mismatch (harness bug, not a parity result)")
        for k in only_ts:
            print(f"   only TS: {k}")
        for k in only_kt:
            print(f"   only KT: {k}")

    shown = worst if args.show_all else (diverged if diverged else worst[:10])
    if shown:
        print(f"\n{'key':<44}{'ts':>24}{'kt':>24}{'rel':>11}  verdict")
        for rel, k, a, b, verdict in shown:
            print(f"{k:<44}{a!r:>24}{b!r:>24}{rel:>11.2e}  {verdict}")

    return 1 if (diverged or only_ts or only_kt) else 0


if __name__ == "__main__":
    raise SystemExit(main())
