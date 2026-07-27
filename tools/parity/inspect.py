#!/usr/bin/env python3
"""
Print a side-by-side slice of two parity dumps, for diagnosing a divergence that
`compare.py` has localised to a stage and an index range.

    python3 inspect.py <ts-dir> <kt-dir> <stage-stem> --fields speed,dt,time --from 0 --to 12

`<stage-stem>` is the filename stem shared by both dumps, e.g. `05-virtualize`.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys


def load(dirname: str, stem: str):
    with open(os.path.join(dirname, stem + ".json"), encoding="utf-8") as fh:
        header = json.load(fh)
    with open(os.path.join(dirname, stem + ".f64"), "rb") as fh:
        raw = fh.read()
    return header, list(struct.unpack("<%dd" % (len(raw) // 8), raw))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ts_dir")
    ap.add_argument("kt_dir")
    ap.add_argument("stem")
    ap.add_argument("--fields", required=True, help="comma-separated field names")
    ap.add_argument("--from", dest="start", type=int, default=0)
    ap.add_argument("--to", dest="end", type=int, default=10)
    args = ap.parse_args()

    th, tv = load(args.ts_dir, args.stem)
    kh, kv = load(args.kt_dir, args.stem)
    nf = th["fieldCount"]
    names = th["fields"]

    wanted = [f.strip() for f in args.fields.split(",")]
    for f in wanted:
        if f not in names:
            raise SystemExit(f"unknown field {f!r}; known: {', '.join(names)}")

    print(f"stage={args.stem}  tsSize={th['size']}  ktSize={kh['size']}")
    for f in wanted:
        fi = names.index(f)
        print(f"\n--- {f}")
        print(f"{'idx':>6}  {'ts':>26}  {'kt':>26}  {'delta':>12}")
        for i in range(args.start, min(args.end, th["size"], kh["size"])):
            x, y = tv[i * nf + fi], kv[i * nf + fi]
            print(f"{i:>6}  {x:>26.17g}  {y:>26.17g}  {abs(x - y):>12.3e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
