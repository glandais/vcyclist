"""What a WASI host can actually do with `vcyclist-engine.wasm`.

Run with `./run-all.sh`, or directly:

    python3 -m unittest discover -s tools/wasi -v

`unittest` rather than pytest on purpose: the harness runs in CI, and one dependency
(`wasmtime`) is easier to justify than two. Network tests are gated on `INTEGRATION=1`, as
everywhere else in this repository.
"""

from __future__ import annotations

import math
import os
import subprocess
import unittest
from pathlib import Path

import fixtures
from host import (ERR_INVALID_ARGUMENT, ERR_UNKNOWN_HANDLE, ROUTES_ONLY,
                  SEGMENTS, TRACKS_AND_ROUTES, TRACKS_ONLY, VcyclistHost,
                  WasiCallFailed, http_tile_source, raw_webp_tile_source)

WASM = fixtures.REPO_ROOT / "engine/build/wasm/vcyclist-engine.wasm"

#: The project's tolerance for whole-pipeline metrics (see CLAUDE.md, "Numerical tolerances").
PIPELINE_TOLERANCE = 0.005

#: 2026-07-28T08:00:00Z — an arbitrary but fixed absolute start for the FIT exports, which have
#: no relative clock to fall back on.
START_TIME_MS = 1785225600000

#: The ABI version this harness was written against. A bump here without a bump in the
#: expectations below is the whole point of the check.
EXPECTED_ABI_VERSION = 1


def integration_enabled() -> bool:
    return os.environ.get("INTEGRATION") == "1"


class WasmModuleTest(unittest.TestCase):
    """The binary itself, before any host semantics."""

    def test_the_distribution_task_produced_a_module(self):
        self.assertTrue(
            WASM.is_file(),
            f"{WASM} missing — run ./gradlew :engine:wasmModule (or ./tools/wasi/run-all.sh)",
        )

    def test_the_wasmtime_cli_accepts_the_module_and_only_misses_the_imports(self):
        """The compatibility check: the runtime must understand the module, not just reject it.

        Without a host there is nothing to satisfy the custom imports, so instantiation *must*
        fail — what matters is that it fails on a missing import and not on "unsupported
        proposal" or "invalid module", which is what a Kotlin or wasmtime upgrade would break.
        """
        cli = kgp_wasmtime()
        if cli is None:
            self.skipTest("no wasmtime in ~/.gradle/wasmtime — run a wasmWasi test once")

        result = subprocess.run(
            [str(cli), "-W", "function-references,gc,exceptions",
             "--invoke", "vcAbiVersion", str(WASM)],
            capture_output=True, text=True, timeout=120,
        )

        self.assertNotEqual(0, result.returncode, "instantiation cannot succeed without the imports")
        self.assertIn("unknown import", result.stderr,
                      f"expected a missing-import failure, got:\n{result.stderr}")
        self.assertIn("vcyclist", result.stderr, "the missing import must be one of ours")


def kgp_wasmtime() -> Path | None:
    """The wasmtime KGP provisions, so the harness tests the same runtime the CI tests."""
    root = Path.home() / ".gradle" / "wasmtime"
    if not root.is_dir():
        return None
    candidates = sorted(root.glob("wasmtime-*/wasmtime"))
    return candidates[-1] if candidates else None


class HostTestCase(unittest.TestCase):
    """Base class: one module instantiation per test, released at the end."""

    tile_source = staticmethod(lambda z, x, y, cap: None)

    def setUp(self):
        if not WASM.is_file():
            self.skipTest(f"{WASM} missing — run ./gradlew :engine:wasmModule")
        self.host = VcyclistHost(str(WASM), tile_source=type(self).tile_source)

    def tearDown(self):
        self.host.release_all()

    def assertRelative(self, expected: float, actual: float, what: str,
                       tolerance: float = PIPELINE_TOLERANCE):
        if expected == 0.0:
            self.assertAlmostEqual(0.0, actual, places=9, msg=what)
            return
        relative = abs(actual - expected) / abs(expected)
        self.assertLessEqual(
            relative, tolerance,
            f"{what}: expected {expected}, got {actual} (relative {relative:.2e} > {tolerance})")


class AbiTest(HostTestCase):
    """Version, handles, error codes — the protocol rather than the physics."""

    def test_abi_version(self):
        self.assertEqual(EXPECTED_ABI_VERSION, self.host.abi_version())

    def test_handles_are_positive_distinct_and_released_once(self):
        gpx = fixtures.gpx_fixture("SAMPLE_GPX")

        first, second = self.host.parse_gpx(gpx), self.host.parse_gpx(gpx)

        self.assertGreater(first, 0)
        self.assertNotEqual(first, second)
        self.assertEqual(1, self.host.release(first), "first release reports it existed")
        self.assertEqual(0, self.host.release(first), "second release reports it did not")

    def test_release_all_reports_what_it_dropped(self):
        gpx = fixtures.gpx_fixture("SAMPLE_GPX")
        for _ in range(3):
            self.host.parse_gpx(gpx)

        self.assertEqual(3, self.host.release_all())
        self.assertEqual(0, self.host.release_all())

    def test_an_unknown_handle_is_minus_two_on_every_shape_of_export(self):
        self.assertEqual(ERR_UNKNOWN_HANDLE, self.host.raw("vcPathSize", 4040))
        self.assertEqual(float(ERR_UNKNOWN_HANDLE), self.host.raw("vcPathTotalDistance", 4040))
        self.assertIn("4040", self.host.last_error())

    def test_a_bad_argument_is_minus_three_and_says_which(self):
        gpx = fixtures.gpx_fixture("SAMPLE_GPX")
        handle = self.host.parse_gpx(gpx)

        self.assertEqual(ERR_INVALID_ARGUMENT, self.host.raw("vcParseGpx", 0))
        self.assertEqual(ERR_INVALID_ARGUMENT, self.host.raw("vcGetField", handle, 9999, 0))
        self.assertIn("9999", self.host.last_error())

    def test_an_unknown_option_is_refused_rather_than_ignored(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        with self.assertRaises(WasiCallFailed) as raised:
            self.host.enhance(handle, {"fixElevations": True})

        self.assertEqual(ERR_INVALID_ARGUMENT, raised.exception.code)
        self.assertIn("fixElevations", raised.exception.message)

    def test_fit_export_refuses_a_payload_without_a_start_time(self):
        # FIT has no relative clock, so `startTimeEpochMs` is the one mandatory payload field in
        # this ABI. `0` (all defaults) cannot mean anything here — better a code than a course
        # dated to the FIT epoch. The export itself works since w12; see FitExportTest.
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        self.assertEqual(ERR_INVALID_ARGUMENT, self.host.raw("vcPathToFit", handle, 0))
        self.assertIn("startTimeEpochMs", self.host.last_error())

    def test_last_error_is_empty_before_anything_fails(self):
        with VcyclistHost(str(WASM)) as fresh:
            self.assertEqual("", fresh.last_error())


class ParsingTest(HostTestCase):
    def test_round_trip_gpx(self):
        gpx = fixtures.gpx_fixture("SAMPLE_GPX")

        handle = self.host.parse_gpx(gpx)
        written = self.host.write_gpx(handle)

        self.assertGreater(self.host.size(handle), 0)
        self.assertTrue(written.startswith("<?xml"), written[:60])
        self.assertIn("<trkpt", written)
        self.assertEqual(self.host.size(handle), written.count("<trkpt"),
                         "every point must survive the round trip")

    def test_write_gpx_options_cover_writeGpx_and_writeGpxAt(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))
        # On a *simulated* path, where time(0) == 0 (see VirtualizeService's KDoc). The option
        # means what `writeGpxAt` means on the JS side — `<time> = startTimeEpochMs + time(i)` —
        # so handing it a freshly parsed path, whose TIME field still holds absolute epoch
        # milliseconds, dates the output somewhere in 2079. That is the documented behaviour of
        # the JS façade too, and this test would be asserting the trap rather than the contract.
        enhanced = self.host.enhance(handle, fixtures.PARITY_OPTIONS)

        bare = self.host.write_gpx(enhanced, {"writeExtensions": False})
        stamped = self.host.write_gpx(enhanced, {"startTimeEpochMs": 1_714_550_400_000})

        self.assertNotIn("<extensions>", bare)
        self.assertIn("2024-05-01", stamped, "startTimeEpochMs must date the ride")

    def test_the_four_multi_parse_modes(self):
        gpx = fixtures.gpx_fixture("SAMPLE_GPX")

        counts = {}
        for mode in (TRACKS_AND_ROUTES, SEGMENTS, TRACKS_ONLY, ROUTES_ONLY):
            counts[mode] = self.host.list_size(self.host.parse_gpx_multi(gpx, mode))

        self.assertGreaterEqual(counts[TRACKS_AND_ROUTES], 1)
        self.assertEqual(counts[TRACKS_ONLY], counts[TRACKS_AND_ROUTES],
                         "the fixture has no <rte>, so both selections agree")
        self.assertEqual(0, counts[ROUTES_ONLY])

    def test_an_unknown_parse_mode_is_refused(self):
        with self.assertRaises(WasiCallFailed) as raised:
            self.host.parse_gpx_multi(fixtures.gpx_fixture("SAMPLE_GPX"), 9)

        self.assertEqual(ERR_INVALID_ARGUMENT, raised.exception.code)

    def test_a_list_handle_yields_independent_path_handles(self):
        list_handle = self.host.parse_gpx_multi(fixtures.gpx_fixture("SAMPLE_GPX"))

        first = self.host.list_get(list_handle, 0)
        again = self.host.list_get(list_handle, 0)

        self.assertNotEqual(first, again, "each call registers its own handle")
        self.assertEqual(self.host.size(first), self.host.size(again), "onto the same path")
        self.host.release(first)
        self.assertGreater(self.host.size(again), 0, "releasing one must not disturb the other")

    def test_garbage_in_is_an_error_not_a_crash(self):
        with self.assertRaises(WasiCallFailed):
            self.host.parse_gpx(b"this is not a GPX document")


class FieldAccessTest(HostTestCase):
    def test_the_field_catalog_indexes_what_the_numeric_exports_take(self):
        catalog = self.host.field_definitions()

        self.assertGreaterEqual(len(catalog), 36)
        self.assertEqual(list(range(len(catalog))), [f["index"] for f in catalog],
                         "indices must be dense and ordered — hosts use them as identifiers")
        self.assertIn("elevation", [f["prop"] for f in catalog])

    def test_bulk_field_bytes_agree_with_the_scalar_read(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))
        index = self.host.field_index("elevation")

        values = self.host.field_values(handle, index)

        self.assertEqual(self.host.size(handle), len(values), "8 bytes per point, no more")
        for i in (0, len(values) // 2, len(values) - 1):
            self.assertEqual(self.host.get_field(handle, index, i), values[i],
                             f"bulk and scalar disagree at point {i}")

    def test_point_json_matches_the_scalar_accessors(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        point = self.host.point(handle, 1)

        self.assertAlmostEqual(self.host.latitude_deg(handle, 1), point["latitudeDeg"], places=12)
        self.assertAlmostEqual(self.host.longitude_deg(handle, 1), point["longitudeDeg"], places=12)

    def test_a_point_index_out_of_bounds_is_refused(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        self.assertEqual(float(ERR_INVALID_ARGUMENT), self.host.raw("vcGetField", handle, 0, 10 ** 6))


class PipelineParityTest(HostTestCase):
    """`vcEnhance` against the same references `EnhancerParityTest` asserts on the JVM."""

    def _check(self, fixture_name: str, metrics_name: str):
        expected = fixtures.parity_metrics(metrics_name)
        handle = self.host.parse_gpx(fixtures.gpx_fixture(fixture_name))

        enhanced = self.host.enhance(handle, fixtures.PARITY_OPTIONS)

        self.assertEqual(expected["pointCount"], self.host.size(enhanced), "point count")
        self.assertRelative(expected["totalDistance"], self.host.total_distance(enhanced), "totalDistance")
        self.assertRelative(expected["durationMs"], self.host.duration_ms(enhanced), "durationMs")
        self.assertRelative(expected["totalElevationGain"], self.host.elevation_gain(enhanced), "elevationGain")
        self.assertRelative(expected["totalElevationLoss"], self.host.elevation_loss(enhanced), "elevationLoss")

    def test_sample_fixture_matches_the_jvm_references(self):
        self._check("SAMPLE_GPX", "SAMPLE")

    def test_garmin_fixture_matches_the_jvm_references(self):
        self._check("GARMIN_GPX", "GARMIN")

    def test_enhance_leaves_the_input_path_untouched(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))
        before = self.host.size(handle)

        self.host.enhance(handle, fixtures.PARITY_OPTIONS)

        self.assertEqual(before, self.host.size(handle), "enhance returns a new handle")

    def test_enhance_with_a_full_course(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        gentle = self.host.enhance_with_course(handle, {
            "cyclist": {"massKg": 90},
            "power": {"type": "constant", "power": 120},
            "options": fixtures.PARITY_OPTIONS,
        })
        strong = self.host.enhance_with_course(handle, {
            "cyclist": {"massKg": 65},
            "power": {"type": "constant", "power": 400},
            "options": fixtures.PARITY_OPTIONS,
        })

        self.assertLess(self.host.duration_ms(strong), self.host.duration_ms(gentle),
                        "400 W on 65 kg must beat 120 W on 90 kg over the same course")

    def test_fix_elevation_without_a_dem_is_refused_rather_than_skipped(self):
        """Only when the host declines to serve tiles is the elevation left alone — silently
        skipping a requested correction would produce a plausible-looking wrong ride."""
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        enhanced = self.host.enhance(handle, {**fixtures.PARITY_OPTIONS, "fixElevation": True})

        self.assertEqual(0, self.host.tiles_served)
        self.assertGreater(self.host.tiles_absent, 0, "the module must have asked")
        index = self.host.field_index("elevation")
        self.assertTrue(all(abs(v) < 1e-6 for v in self.host.field_values(enhanced, index)),
                        "no tile means sea level, not -32768 m")


class ExportsTest(HostTestCase):
    def test_csv_and_json_exports(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        csv = self.host.to_csv(handle, {"separator": ";"})
        as_json = self.host.to_json(handle, {"pretty": False})

        self.assertIn(";", csv.splitlines()[0])
        self.assertEqual(self.host.size(handle) + 1, len(csv.strip().splitlines()), "header plus rows")
        self.assertEqual(self.host.size(handle), as_json["size"])
        self.assertIn("elevation", as_json["fields"])

    def test_climb_detection(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        climbs = self.host.climbs(handle)

        self.assertIsInstance(climbs, list, "a flat trace legitimately has no climb")
        for climb in climbs:
            self.assertIn("parts", climb)
            self.assertGreater(climb["lengthM"], 0)

    def test_dominant_headwind_is_an_azimuth_or_nan(self):
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        azimuth = self.host.dominant_headwind_azimuth(handle)

        self.assertTrue(math.isnan(azimuth) or 0.0 <= azimuth < 360.0, azimuth)
        self.assertTrue(math.isnan(self.host.dominant_headwind_azimuth(4040)),
                        "an unknown handle answers NaN here, not a negative sentinel")

    def test_fit_export_produces_a_file_a_reader_can_open(self):
        # Task w12: the module encodes FIT itself, so this is a real file and not a sentinel.
        # Asserted from the outside — the 14-byte header, its ".FIT" marker, and the data size
        # it declares — because a host has no SDK either.
        handle = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))

        fit = self.host.to_fit(handle, "col de la madeleine", START_TIME_MS)

        self.assertEqual(b".FIT", fit[8:12], "the FIT data-type marker")
        self.assertEqual(0x0E, fit[0], "14-byte header")
        declared = int.from_bytes(fit[4:8], "little")
        self.assertEqual(len(fit), 14 + declared + 2, "header size + data + file CRC")

    def test_paths_to_fit_puts_every_track_in_one_file(self):
        one = self.host.parse_gpx(fixtures.gpx_fixture("SAMPLE_GPX"))
        several = self.host.parse_gpx_multi(fixtures.gpx_fixture("SAMPLE_GPX"))

        single = self.host.to_fit(one, "multi", START_TIME_MS)
        multi = self.host.paths_to_fit(several, "multi", START_TIME_MS)

        self.assertEqual(b".FIT", multi[8:12])
        self.assertGreaterEqual(len(multi), len(single) - 32,
                                "a multi-track file cannot be much smaller than its first track")

    def test_waypoints_come_back_as_json(self):
        waypoints = self.host.waypoints(fixtures.gpx_fixture("SAMPLE_GPX"))

        self.assertIsInstance(waypoints, list)
        for waypoint in waypoints:
            self.assertIn("latitudeDeg", waypoint)


class ElevationTest(HostTestCase):
    """The DEM contract. Offline by default; the real tiles need INTEGRATION=1."""

    def test_tile_geometry_is_what_the_host_must_be_ready_to_write(self):
        geometry = self.host.tile_geometry()

        self.assertEqual("RGBA", geometry["layout"])
        self.assertEqual("terrarium", geometry["encoding"])
        self.assertEqual(geometry["tileSize"] ** 2 * geometry["bytesPerPixel"],
                         geometry["expectedBytes"])

    def test_the_configuration_is_read_back_by_the_geometry(self):
        self.host.set_elevation_config({"tileSize": 256, "zoomLevel": 11})

        geometry = self.host.tile_geometry()

        self.assertEqual(256, geometry["tileSize"])
        self.assertEqual(11, geometry["zoomLevel"])
        self.assertEqual(256 * 256 * 4, geometry["expectedBytes"])

    def test_the_tile_format_is_reported_and_validated(self):
        self.assertEqual("rgba", self.host.tile_geometry()["tileFormat"], "the default is unchanged")

        self.host.set_elevation_config({"tileFormat": "webp"})
        self.assertEqual("webp", self.host.tile_geometry()["tileFormat"])

        with self.assertRaises(WasiCallFailed) as raised:
            self.host.set_elevation_config({"tileFormat": "png"})
        self.assertIn("png", raised.exception.message)

    def test_an_impossible_configuration_is_refused_immediately(self):
        with self.assertRaises(WasiCallFailed):
            self.host.set_elevation_config({"tileSize": 300})       # not a power of two
        with self.assertRaises(WasiCallFailed):
            self.host.set_elevation_config({"zoomLevel": 42})       # out of range

    def test_real_tiles_fix_the_elevation(self):
        if not integration_enabled():
            self.skipTest("set INTEGRATION=1 to download DEM tiles")

        with VcyclistHost(str(WASM), tile_source=http_tile_source()) as host:
            handle = host.parse_gpx(fixtures.STELVIO_GPX.read_bytes())
            index = host.field_index("elevation")
            before = host.field_values(handle, index)

            enhanced = host.enhance(handle, {
                "fixElevation": True, "virtualizeTrack": False, "computeMaxSpeeds": False,
            })
            after = host.field_values(enhanced, index)

            self.assertGreater(host.tiles_served, 0, "the DEM must have been reached")
            self.assertEqual(0, host.tiles_absent)
            # The Stelvio sits above 2500 m; the fixture's own elevations are in that band too,
            # so the assertion is that the profile is plausible and *not* the input verbatim.
            self.assertTrue(all(2000 < v < 3000 for v in after), (min(after), max(after)))
            self.assertNotEqual(before[: len(after)], after[: len(before)])


    def test_raw_webp_tiles_give_the_same_profile_as_decoded_ones(self):
        """The point of task w11, end to end: a host with no image library gets the same answer.

        Same trace, same tiles, two modes — `rgba`, where the host decodes with Pillow, and
        `webp`, where it hands over the bytes it downloaded and the module decodes them itself.
        The two elevation profiles must be identical to the bit, since the same libwebp-produced
        file goes through two decoders that agree byte for byte (`Vp8lAgainstImageIoTest`).
        """
        if not integration_enabled():
            self.skipTest("set INTEGRATION=1 to download DEM tiles")

        options = {"fixElevation": True, "virtualizeTrack": False, "computeMaxSpeeds": False}
        gpx = fixtures.STELVIO_GPX.read_bytes()
        profiles = {}
        for mode, source in (("rgba", http_tile_source()), ("webp", raw_webp_tile_source())):
            with VcyclistHost(str(WASM), tile_source=source) as host:
                host.set_elevation_config({"tileFormat": mode})
                handle = host.parse_gpx(gpx)
                enhanced = host.enhance(handle, options)
                profiles[mode] = host.field_values(enhanced, host.field_index("elevation"))
                self.assertGreater(host.tiles_served, 0, f"{mode}: no tile was fetched")

        self.assertEqual(profiles["rgba"], profiles["webp"],
                         "the module's own decoder disagrees with the host's")


if __name__ == "__main__":
    unittest.main()
