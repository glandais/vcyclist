#!/usr/bin/env python3
"""Cumulative ascent as a function of smoothing scale and hysteresis threshold.

Reads a GPX file's own ``<ele>`` stream — no DEM lookup, no pipeline — and prints D+ for a
grid of (triangular-kernel half-width, dead-band threshold) pairs. It exists to produce the
tables in ``docs/guides/elevation.md`` and to make the point they make: cumulative ascent is a
property of a route **and a measurement scale**, never of a route alone.

This is deliberately a standalone script and not a Kotlin test: it measures the *input* files,
before anything vcyclist does to them, and it must stay runnable without a Gradle build.
``ElevationGainMeasurementTest`` is the in-pipeline counterpart.

Usage:
    python3 tools/elevation/dplus_scale.py demo/public/gpx/*.gpx
"""

import math
import re
import sys

TRKPT = re.compile(
    r'<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>.*?<ele>([-\d.]+)</ele>',
    re.S,
)

SMOOTH_WINDOWS_M = (0, 10, 25, 50, 100, 150, 300)
THRESHOLDS_M = (0, 2, 3, 5, 10)

EARTH_RADIUS_M = 6371000.0


def read_track(path):
    """Return (cumulative distance [m], elevation [m]) for one GPX file."""
    with open(path, encoding="utf-8") as handle:
        points = TRKPT.findall(handle.read())
    lat = [math.radians(float(a)) for a, _, _ in points]
    lon = [math.radians(float(b)) for _, b, _ in points]
    ele = [float(c) for _, _, c in points]

    dist = [0.0]
    for i in range(1, len(points)):
        # Equirectangular is exact enough at trackpoint spacing and keeps the script dependency-free.
        dx = (lon[i] - lon[i - 1]) * math.cos((lat[i] + lat[i - 1]) / 2.0)
        dy = lat[i] - lat[i - 1]
        dist.append(dist[-1] + EARTH_RADIUS_M * math.hypot(dx, dy))
    return dist, ele


def smooth(dist, ele, window_m):
    """Distance-weighted triangular kernel — the same one as :elevation's ElevationSmoother.

    ``window_m`` is a half-width applied on each side, weight ``1 - d / window_m``.
    """
    if window_m <= 0:
        return list(ele)
    n = len(ele)
    out = []
    for i in range(n):
        lo = i
        while lo > 0 and dist[i] - dist[lo - 1] <= window_m:
            lo -= 1
        hi = i
        while hi < n - 1 and dist[hi + 1] - dist[i] <= window_m:
            hi += 1
        total_weight = 0.0
        weighted_sum = 0.0
        for j in range(lo, hi + 1):
            weight = 1.0 - abs(dist[j] - dist[i]) / window_m
            total_weight += weight
            weighted_sum += ele[j] * weight
        out.append(weighted_sum / total_weight if total_weight > 0 else ele[i])
    return out


def gain(ele, threshold_m):
    """Cumulative ascent with a hysteresis dead band.

    A turning-point tracker: a leg is banked once, in full, when the profile reverses by
    ``threshold_m``. It is therefore resample-invariant, and it does not double-count a climb
    that has sub-summits. ``threshold_m == 0`` degenerates to the plain sum of positive deltas.
    """
    if len(ele) < 2:
        return 0.0
    if threshold_m <= 0:
        return sum(max(0.0, ele[i] - ele[i - 1]) for i in range(1, len(ele)))

    total = 0.0
    hi = lo = ref = ext = ele[0]
    i_hi = i_lo = 0
    direction = 0
    for i in range(1, len(ele)):
        e = ele[i]
        if direction == 0:
            if e > hi:
                hi, i_hi = e, i
            if e < lo:
                lo, i_lo = e, i
            if hi - lo >= threshold_m:
                if i_hi > i_lo:
                    direction, ref, ext = 1, lo, max(hi, e)
                else:
                    direction, ref, ext = -1, hi, min(lo, e)
        elif direction == 1:
            if e > ext:
                ext = e
            elif ext - e >= threshold_m:
                total += ext - ref
                ref, ext, direction = ext, e, -1
        else:
            if e < ext:
                ext = e
            elif e - ext >= threshold_m:
                ref, ext, direction = ext, e, 1
    if direction == 1:
        total += ext - ref
    return total


def report(path):
    dist, ele = read_track(path)
    if len(ele) < 2:
        print(f"{path}: no trackpoints with elevation")
        return
    print(f"{path}  n={len(ele)}  distance={dist[-1] / 1000:.1f} km")
    print(f"{'smooth':>8} | " + " | ".join(f"t={t} m".rjust(6) for t in THRESHOLDS_M))
    for window in SMOOTH_WINDOWS_M:
        smoothed = smooth(dist, ele, window)
        row = " | ".join(f"{gain(smoothed, t):6.0f}" for t in THRESHOLDS_M)
        print(f"{window:>6} m | {row}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for arg in sys.argv[1:]:
        report(arg)
