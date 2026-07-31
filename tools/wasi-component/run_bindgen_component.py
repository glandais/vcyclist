"""Drive the component built from **generated** bindings (w13, second half).

`run_component.py` drives a component whose Canonical ABI glue was written by hand. This one
drives the same engine behind bindings that `Kotlin/wit-bindgen` generated from
`vcyclist-engine.wit` — the ABI v1 translated to WIT — with only three stub bodies filled in.

    ./generate-bindings.sh        # regenerate (needs cargo; the output is committed)
    ./reproduce.sh                # builds build/bindgen-guest.component.wasm too
    python3 run_bindgen_component.py

What it proves: `parse-gpx` takes a `str` and returns a **WIT resource** — the handle table is the
runtime\'s now, not `WasiAbi.kt`\'s — and `path.total-distance` answers what ABI v1 answers.
"""

from pathlib import Path

from wasmtime import Config, Engine, Store, WasiConfig
from wasmtime.component import Component, Linker

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
COMPONENT = HERE / "build" / "bindgen-guest.component.wasm"
STELVIO = REPO_ROOT / "demo/public/gpx/stelvio.gpx"

PATH_API = "vcyclist:engine/path-api@1.0.0"


def main() -> int:
    config = Config()
    config.wasm_function_references = True
    config.wasm_gc = True
    config.wasm_exceptions = True
    config.wasm_component_model = True
    engine = Engine(config)

    store = Store(engine)
    store.set_wasi(WasiConfig())
    linker = Linker(engine)
    linker.add_wasip2()
    instance = linker.instantiate(store, Component.from_file(engine, str(COMPONENT)))

    iface = instance.get_export_index(store, PATH_API)

    def func(name):
        return instance.get_func(store, instance.get_export_index(store, name, iface))

    gpx = STELVIO.read_text(encoding="utf-8")
    path = func("parse-gpx")(store, gpx)          # -> a wasmtime ResourceAny, not an Int handle
    size = func("[method]path.size")(store, path)
    distance = func("[method]path.total-distance")(store, path)

    print(f"component      : {COMPONENT.stat().st_size} bytes")
    print(f"parse-gpx      : {type(path).__name__}, {size} points, {distance} m")

    expected = (259, 3573.8048648177737)
    if (size, distance) != expected:
        print(f"FAIL: expected {expected}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
