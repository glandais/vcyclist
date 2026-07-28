"""A reference WASI host for `vcyclist-engine.wasm`, in the shape a real embedder would write.

This is the only place the ABI of `EngineWasiApi` is exercised end to end: KGP's
`wasmWasiWasmtimeTest` cannot supply the custom `vcyclist` imports, so it never reaches an
export that uses one (see docs/kotlin-wasm-wasi.md §5). Everything the module promises a host —
the callback protocol, handles, error codes, host-served DEM tiles — is checked from here.

It is also the executable documentation of that ABI: `docs/wasm-wasi-abi.md` points at this file
rather than copying Python into a markdown that would drift.

Usage:

    from host import VcyclistHost
    with VcyclistHost(wasm_path) as host:
        handle = host.parse_gpx(open("ride.gpx", "rb").read())
        enhanced = host.enhance(handle, {"computeOnePointPerSecond": True})
        print(host.total_distance(enhanced))
"""

from __future__ import annotations

import json
import struct
from typing import Callable, Optional

from wasmtime import (Config, Engine, Func, FuncType, Linker, Module, Store,
                      ValType, WasiConfig)

#: The three Wasm proposals Kotlin 2.4 emits, and that KGP itself passes to the wasmtime CLI.
#: A host that forgets them fails at `Module.from_file` with a parse error, not at instantiation.
REQUIRED_PROPOSALS = ("wasm_function_references", "wasm_gc", "wasm_exceptions")

#: Error codes of the ABI (WasiAbi.kt). Kept here in full so a failure reads as a name.
ERR_GENERIC = -1
ERR_UNKNOWN_HANDLE = -2
ERR_INVALID_ARGUMENT = -3
ERR_UNSUPPORTED = -4

ERROR_NAMES = {
    ERR_GENERIC: "ERR_GENERIC",
    ERR_UNKNOWN_HANDLE: "ERR_UNKNOWN_HANDLE",
    ERR_INVALID_ARGUMENT: "ERR_INVALID_ARGUMENT",
    ERR_UNSUPPORTED: "ERR_UNSUPPORTED",
}

#: Parse modes of `vcParseGpxMulti`.
TRACKS_AND_ROUTES, SEGMENTS, TRACKS_ONLY, ROUTES_ONLY = 0, 1, 2, 3


class WasiCallFailed(RuntimeError):
    """An export answered a negative sentinel; the message comes from `vcLastError`."""

    def __init__(self, export: str, code: int, message: str):
        self.export, self.code, self.message = export, code, message
        name = ERROR_NAMES.get(code, str(code))
        super().__init__(f"{export} failed with {name} ({code}): {message}")


#: A tile source: `(zoom, x, y, expected_bytes) -> bytes | None`. `None` means "no tile here",
#: which the guest turns into a sea-level tile rather than into -32768 m.
TileSource = Callable[[int, int, int, int], Optional[bytes]]


def no_tiles(zoom: int, x: int, y: int, expected_bytes: int) -> Optional[bytes]:
    """The default tile source: there is no DEM. `fixElevation` then yields 0 m everywhere."""
    return None


class VcyclistHost:
    """One instantiation of the module, with the three imports wired.

    All three are **mandatory**, including for a host that never fixes an elevation: Wasm
    imports are resolved at instantiation, so a missing one means the module does not load at
    all. `fetch_tile` may simply always answer "no tile" — that is what [no_tiles] does.
    """

    def __init__(self, wasm_path: str, tile_source: TileSource = no_tiles):
        config = Config()
        for proposal in REQUIRED_PROPOSALS:
            # These are write-only properties: hasattr() lies about them, so they are set blind.
            setattr(config, proposal, True)
        self._engine = Engine(config)
        self._module = Module.from_file(self._engine, wasm_path)
        self._store = Store(self._engine)
        wasi = WasiConfig()
        wasi.inherit_stdout()
        self._store.set_wasi(wasi)

        self._staged = b""          # what the guest will pull through read_input
        self._captured = b""        # what the guest last pushed through write_output
        self._tile_source = tile_source
        self.tiles_served = 0
        self.tiles_absent = 0

        linker = Linker(self._engine)
        linker.define_wasi()
        i32 = ValType.i32()
        linker.define(
            self._store, "vcyclist", "read_input",
            Func(self._store, FuncType([i32, i32], [i32]), self._read_input, access_caller=True))
        linker.define(
            self._store, "vcyclist", "write_output",
            Func(self._store, FuncType([i32, i32], []), self._write_output, access_caller=True))
        linker.define(
            self._store, "vcyclist", "fetch_tile",
            Func(self._store, FuncType([i32] * 5, [i32]), self._fetch_tile, access_caller=True))

        self._exports = linker.instantiate(self._store, self._module).exports(self._store)

    # ── The three imports ─────────────────────────────────────────────────────────────────

    def _read_input(self, caller, ptr: int, cap: int) -> int:
        data = self._staged[:cap]
        caller.get("memory").write(caller, data, ptr)
        return len(data)

    def _write_output(self, caller, ptr: int, length: int) -> None:
        self._captured = bytes(caller.get("memory").read(caller, ptr, ptr + length))

    def _fetch_tile(self, caller, zoom: int, x: int, y: int, ptr: int, cap: int) -> int:
        data = self._tile_source(zoom, x, y, cap)
        if data is None:
            self.tiles_absent += 1
            return 0
        if len(data) != cap:
            raise AssertionError(f"tile {zoom}/{x}/{y}: {len(data)} bytes, guest expected {cap}")
        caller.get("memory").write(caller, data, ptr)
        self.tiles_served += 1
        return cap

    # ── Calling conventions ───────────────────────────────────────────────────────────────

    def _call(self, name: str, *args):
        return self._exports[name](self._store, *args)

    def _checked(self, name: str, *args) -> int:
        """Call an export returning a count or a handle, raising on a negative sentinel."""
        result = self._call(name, *args)
        if result < 0:
            raise WasiCallFailed(name, int(result), self.last_error())
        return int(result)

    def _with_input(self, payload: bytes, name: str, *args) -> int:
        self._staged = payload
        return self._checked(name, len(payload), *args)

    def _with_options(self, name: str, handle: int, options: Optional[dict]) -> int:
        """Options-taking exports: 0 means "all defaults", otherwise the JSON is staged."""
        if options is None:
            return self._checked(name, handle, 0)
        self._staged = json.dumps(options).encode()
        return self._checked(name, handle, len(self._staged))

    def _text(self, name: str, *args) -> str:
        self._checked(name, *args)
        return self._captured.decode()

    # ── Version, errors, handles ──────────────────────────────────────────────────────────

    def abi_version(self) -> int:
        """The one export that never touches an import, so it answers before anything is wired."""
        return int(self._call("vcAbiVersion"))

    def last_error(self) -> str:
        self._call("vcLastError")
        return self._captured.decode()

    def release(self, handle: int) -> int:
        return int(self._call("vcRelease", handle))

    def release_all(self) -> int:
        return int(self._call("vcReleaseAll"))

    def raw(self, name: str, *args):
        """Call an export without checking its result — for exercising the error paths."""
        return self._call(name, *args)

    # ── Parsing ───────────────────────────────────────────────────────────────────────────

    def parse_gpx(self, gpx: bytes) -> int:
        return self._with_input(gpx, "vcParseGpx")

    def parse_gpx_multi(self, gpx: bytes, mode: int = TRACKS_AND_ROUTES) -> int:
        return self._with_input(gpx, "vcParseGpxMulti", mode)

    def waypoints(self, gpx: bytes) -> list:
        self._staged = gpx
        self._checked("vcParseGpxWaypointsJson", len(gpx))
        return json.loads(self._captured.decode())

    def list_size(self, list_handle: int) -> int:
        return self._checked("vcListSize", list_handle)

    def list_get(self, list_handle: int, index: int) -> int:
        return self._checked("vcListGet", list_handle, index)

    def paths_of(self, list_handle: int) -> list:
        return [self.list_get(list_handle, i) for i in range(self.list_size(list_handle))]

    # ── Metrics ───────────────────────────────────────────────────────────────────────────

    def size(self, handle: int) -> int:
        return self._checked("vcPathSize", handle)

    def _double(self, name: str, handle: int) -> float:
        value = float(self._call(name, handle))
        if value < 0:
            raise WasiCallFailed(name, int(value), self.last_error())
        return value

    def total_distance(self, handle: int) -> float:
        return self._double("vcPathTotalDistance", handle)

    def duration_ms(self, handle: int) -> float:
        return self._double("vcPathDurationMs", handle)

    def elevation_gain(self, handle: int) -> float:
        return self._double("vcPathElevationGain", handle)

    def elevation_loss(self, handle: int) -> float:
        """Negative by convention, so it is read raw rather than through the sentinel check."""
        return float(self._call("vcPathElevationLoss", handle))

    def latitude_deg(self, handle: int, i: int) -> float:
        return float(self._call("vcPathLatitudeDeg", handle, i))

    def longitude_deg(self, handle: int, i: int) -> float:
        return float(self._call("vcPathLongitudeDeg", handle, i))

    def dominant_headwind_azimuth(self, handle: int) -> float:
        """NaN when the question has no answer — the one export that does not use a sentinel."""
        return float(self._call("vcDominantHeadwindAzimuth", handle))

    def dominant_headwind_azimuth_of_tracks(self, list_handle: int) -> float:
        return float(self._call("vcDominantHeadwindAzimuthOfTracks", list_handle))

    # ── Fields ────────────────────────────────────────────────────────────────────────────

    def field_definitions(self) -> list:
        return json.loads(self._text("vcFieldDefinitionsJson"))

    def field_index(self, prop: str) -> int:
        for field in self.field_definitions():
            if field["prop"] == prop:
                return int(field["index"])
        raise KeyError(f"no PointField named {prop!r}")

    def get_field(self, handle: int, field_index: int, point_index: int) -> float:
        return float(self._call("vcGetField", handle, field_index, point_index))

    def field_values(self, handle: int, field_index: int) -> tuple:
        """A whole field in one crossing, as little-endian f64 — the only usable bulk read."""
        length = self._checked("vcPathFieldBytes", handle, field_index)
        return struct.unpack(f"<{length // 8}d", self._captured)

    def point(self, handle: int, i: int) -> dict:
        return json.loads(self._text("vcPointJson", handle, i))

    # ── Simulation, climbs, elevation ─────────────────────────────────────────────────────

    def enhance(self, handle: int, options: Optional[dict] = None) -> int:
        return self._with_options("vcEnhance", handle, options)

    def enhance_with_course(self, handle: int, payload: Optional[dict] = None) -> int:
        return self._with_options("vcEnhanceWithCourse", handle, payload)

    def climbs(self, handle: int, options: Optional[dict] = None) -> list:
        self._with_options("vcDetectClimbsJson", handle, options)
        return json.loads(self._captured.decode())

    def set_elevation_config(self, config: Optional[dict] = None) -> int:
        if config is None:
            return self._checked("vcSetElevationConfig", 0)
        self._staged = json.dumps(config).encode()
        return self._checked("vcSetElevationConfig", len(self._staged))

    def tile_geometry(self) -> dict:
        return json.loads(self._text("vcTileGeometryJson"))

    # ── Serialisation ─────────────────────────────────────────────────────────────────────

    def write_gpx(self, handle: int, options: Optional[dict] = None) -> str:
        self._with_options("vcWriteGpx", handle, options)
        return self._captured.decode()

    def write_gpx_tracks(self, list_handle: int, options: Optional[dict] = None) -> str:
        self._with_options("vcWriteGpxTracks", list_handle, options)
        return self._captured.decode()

    def to_csv(self, handle: int, options: Optional[dict] = None) -> str:
        self._with_options("vcPathToCsv", handle, options)
        return self._captured.decode()

    def to_json(self, handle: int, options: Optional[dict] = None) -> dict:
        self._with_options("vcPathToJson", handle, options)
        return json.loads(self._captured.decode())

    # ── Context manager ───────────────────────────────────────────────────────────────────

    def __enter__(self) -> "VcyclistHost":
        return self

    def __exit__(self, *exc) -> None:
        self.release_all()


def http_tile_source(url_template: str = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp",
                     user_agent: str = "vcyclist-wasi-harness/1.0") -> TileSource:
    """A real tile source: download the WebP, decode it, hand back RGBA.

    This is the half of `fixElevation` that lives outside the module, and it is three lines of a
    library every language already has. `Pillow` is imported lazily so the offline tests need
    neither it nor a network.

    The `User-Agent` is not decoration: the tile server answers 403 to Python's default one.
    """
    import io
    import urllib.request

    from PIL import Image

    cache: dict = {}

    def fetch(zoom: int, x: int, y: int, expected_bytes: int):
        key = (zoom, x, y)
        if key not in cache:
            url = url_template.format(z=zoom, x=x, y=y)
            request = urllib.request.Request(url, headers={"User-Agent": user_agent})
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    raw = response.read()
            except Exception:                       # noqa: BLE001 — the host decides what absent means
                cache[key] = None
            else:
                cache[key] = Image.open(io.BytesIO(raw)).convert("RGBA").tobytes()
        return cache[key]

    return fetch
