"""Drive the w13 spike component — the measured half of `docs/wasm-wasi-component-model.md`.

`tools/wasi/host.py` is what an integrator writes today against ABI v1: 392 lines, three imports
wired by hand, a staging buffer, a `write_output` capture, one method per export doing its own
JSON. This file is the same job against the Component Model — and everything below `instantiate`
is the *whole* host: no imports to implement, no linear memory to read or write, no byte lengths,
no JSON. `wasmtime.component` types the calls from the component's own type section.

    pip install -r ../wasi/requirements.txt
    ./reproduce.sh                      # builds build/spike-guest.component.wasm
    python3 run_component.py            # this file

`--offline` skips the one test that needs the network (the `wasi:http` GET).
"""

import sys
from pathlib import Path

from wasmtime import Config, Engine, Store, WasiConfig
from wasmtime.component import Component, Linker

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
COMPONENT = HERE / "build" / "spike-guest.component.wasm"
STELVIO = REPO_ROOT / "demo/public/gpx/stelvio.gpx"
CORE_MODULE = REPO_ROOT / "engine/build/wasm/vcyclist-engine.wasm"

#: The tile the `wasi:http` test asks for — a real one, from the DEM server the engine uses.
TILE_HOST = "tiles.mapterhorn.com"
TILE_PATH = "/12/2129/1465.webp"


def instantiate():
    config = Config()
    config.wasm_function_references = True
    config.wasm_gc = True
    config.wasm_exceptions = True
    config.wasm_component_model = True
    engine = Engine(config)

    store = Store(engine)
    store.set_wasi(WasiConfig())
    store.set_wasi_http()  # without this, wasmtime-py 47.0.1 aborts the process on the first call

    linker = Linker(engine)
    linker.add_wasip2()
    linker.add_wasi_http()

    return store, linker.instantiate(store, Component.from_file(engine, str(COMPONENT)))


def core_module_reference() -> tuple[int, float]:
    """The same two numbers, through ABI v1 and `tools/wasi/host.py`. The point of the spike is
    that these are *the same engine*: if the component disagrees, the component is wrong."""
    sys.path.insert(0, str(REPO_ROOT / "tools" / "wasi"))
    import host  # noqa: PLC0415 — imported late so `--help` works without the module built

    reference = host.VcyclistHost(str(CORE_MODULE))
    path = reference.parse_gpx(STELVIO.read_bytes())
    return reference.size(path), reference.total_distance(path)


def main() -> int:
    offline = "--offline" in sys.argv
    store, instance = instantiate()

    def call(name, *args):
        return instance.get_func(store, name)(store, *args)

    gpx = STELVIO.read_text(encoding="utf-8")
    failures = []

    print(f"component      : {COMPONENT.stat().st_size} bytes")
    print("abi-version    :", call("abi-version"))

    # 1. The wall. Any scoped allocation — which is every WASI syscall the stdlib makes — throws
    #    while the caller's `cabi_realloc` memory is still alive.
    print("probe-scoped   :", call("probe-scoped", "x"), "|", call("last-error"))

    # 2. The workaround: copy the argument onto the GC heap, free the linear memory, then work.
    handle = call("parse-gpx-freeing", gpx)
    size, distance = call("path-size", handle), call("total-distance", handle)
    print(f"parse-gpx      : handle {handle}, {size} points, {distance} m")

    ref_size, ref_distance = core_module_reference()
    print(f"ABI v1 says    : {ref_size} points, {ref_distance} m")
    if (size, distance) != (ref_size, ref_distance):
        failures.append("component and core module disagree on the same GPX")

    # 3. Repeatable, not one-shot: the freeing trick must survive being done again.
    handles = [call("parse-gpx-freeing", gpx) for _ in range(3)]
    print("three more     :", handles, "->", [call("path-size", h) for h in handles])
    if any(h < 0 for h in handles):
        failures.append("parse-gpx-freeing is not repeatable")

    # 4. A component-model *import* with a `list<u8>` result, called from Kotlin by hand.
    packed = call("random-sum", 16)
    print("random-sum     :", f"{packed // 100000} bytes from wasi:random")
    if packed // 100000 != 16:
        failures.append("wasi:random import did not return 16 bytes")

    # 5. The only functional gain of the whole exercise: the guest fetching its own DEM tile.
    if offline:
        print("http-get-status: skipped (--offline)")
    else:
        status = call("http-get-status", TILE_HOST, TILE_PATH)
        # `last-error` is deliberately not printed here: like `vcLastError`, it is never cleared,
        # so it still holds the message `probe-scoped` provoked above.
        print("http-get-status:", status, f"for https://{TILE_HOST}{TILE_PATH}")
        if status != 200:
            failures.append(f"wasi:http GET returned {status}")

    for failure in failures:
        print("FAIL:", failure)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
