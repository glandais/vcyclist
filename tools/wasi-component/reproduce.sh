#!/usr/bin/env bash
# Rebuild every artefact the w13 spike measured, from scratch, into ./build.
#
# Nothing here is part of the vcyclist build or of CI. Read `docs/wasm-wasi-component-model.md`
# for what the numbers mean; this script exists so they can be checked rather than believed.
#
# Needs: cargo (for wasm-tools), python3 with `wasmtime`, network access for the adapter and the
# wasi-http WIT.
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
BUILD=$PWD/build
mkdir -p "$BUILD"

WASMTIME_VERSION=47.0.1                       # must match tools/wasi/requirements.txt
WASM_TOOLS_VERSION=1.255.0

command -v wasm-tools >/dev/null || cargo install wasm-tools --locked --version "$WASM_TOOLS_VERSION"

echo "== deps =="
[ -f "$BUILD/wasi_snapshot_preview1.reactor.wasm" ] || curl -sL -o "$BUILD/wasi_snapshot_preview1.reactor.wasm" \
  "https://github.com/bytecodealliance/wasmtime/releases/download/v$WASMTIME_VERSION/wasi_snapshot_preview1.reactor.wasm"
# wasi:http's WIT is not published past 0.2.7; wasmtime 47 accepts it as semver-compatible with
# the 0.2.12 interfaces its adapter emits.
[ -d "$BUILD/wasi-http-0.2.7" ] || {
  curl -sL -o "$BUILD/wasi-http.tgz" https://github.com/WebAssembly/wasi-http/archive/refs/tags/v0.2.7.tar.gz
  tar xzf "$BUILD/wasi-http.tgz" -C "$BUILD"
}

echo
echo "== step 1: the published module, unmodified =="
"$ROOT/gradlew" -p "$ROOT" :engine:wasmModule -q
cp "$ROOT/engine/build/wasm/vcyclist-engine.wasm" "$BUILD/"
# Custom imports have no WIT to resolve against, so they need an adapter — here a stub, since
# this step only asks whether the encoder digests a WASM-GC + exnref module at all.
cat > "$BUILD/vcyclist-stub.wat" <<'WAT'
(module
  (func (export "read_input") (param i32 i32) (result i32) i32.const 0)
  (func (export "write_output") (param i32 i32))
  (func (export "fetch_tile") (param i32 i32 i32 i32 i32) (result i32) i32.const 0)
)
WAT
wasm-tools parse "$BUILD/vcyclist-stub.wat" -o "$BUILD/vcyclist-stub.wasm"
wasm-tools component new "$BUILD/vcyclist-engine.wasm" \
  --adapt "wasi_snapshot_preview1=$BUILD/wasi_snapshot_preview1.reactor.wasm" \
  --adapt "vcyclist=$BUILD/vcyclist-stub.wasm" \
  -o "$BUILD/vcyclist-engine.component.wasm"
wasm-tools validate --features=all "$BUILD/vcyclist-engine.component.wasm"
echo "   module    $(stat -c%s "$BUILD/vcyclist-engine.wasm") bytes"
echo "   component $(stat -c%s "$BUILD/vcyclist-engine.component.wasm") bytes, valid — and with zero exports:"
wasm-tools component wit "$BUILD/vcyclist-engine.component.wasm" | sed -n '/^world root/,/^}/p'

echo
echo "== step 4: the throwaway guest, with Canonical ABI exports =="
# The spike is a *separate* Gradle build (it must not touch the vcyclist one), so it consumes the
# engine from mavenLocal rather than by project reference.
ENGINE_VERSION=$(sed -n 's/^version=//p' "$ROOT/gradle.properties")
"$ROOT/gradlew" -p "$ROOT" publishToMavenLocal -q
"$ROOT/gradlew" -p spike-guest -PengineVersion="$ENGINE_VERSION" compileProductionExecutableKotlinWasmWasiOptimize -q
cp spike-guest/build/compileSync/wasmWasi/main/productionExecutable/optimized/wasi-component-spike.wasm \
   "$BUILD/spike-guest.wasm"

rm -rf "$BUILD/wit-e2e"
mkdir -p "$BUILD/wit-e2e/deps/http"
cp -r "$BUILD/wasi-http-0.2.7/wit/deps/"* "$BUILD/wit-e2e/deps/"
cp "$BUILD/wasi-http-0.2.7/wit/"*.wit "$BUILD/wit-e2e/deps/http/"
cp spike.wit "$BUILD/wit-e2e/spike.wit"

wasm-tools component embed "$BUILD/wit-e2e" --world engine "$BUILD/spike-guest.wasm" -o "$BUILD/spike-guest.embedded.wasm"
wasm-tools component new "$BUILD/spike-guest.embedded.wasm" \
  --adapt "wasi_snapshot_preview1=$BUILD/wasi_snapshot_preview1.reactor.wasm" \
  -o "$BUILD/spike-guest.component.wasm"
wasm-tools validate --features=all "$BUILD/spike-guest.component.wasm"
echo "   guest     $(stat -c%s "$BUILD/spike-guest.wasm") bytes"
echo "   component $(stat -c%s "$BUILD/spike-guest.component.wasm") bytes, valid"

echo
echo "== the .wit of ABI v1, checked =="
rm -rf "$BUILD/witcheck" && mkdir -p "$BUILD/witcheck"
cp -r "$BUILD/wasi-http-0.2.7/wit/deps" "$BUILD/witcheck/deps"
mkdir -p "$BUILD/witcheck/deps/http"
cp "$BUILD/wasi-http-0.2.7/wit/"*.wit "$BUILD/witcheck/deps/http/"
cp vcyclist-engine.wit "$BUILD/witcheck/"
wasm-tools component wit "$BUILD/witcheck" > /dev/null && echo "   vcyclist-engine.wit resolves"

echo
echo "== run =="
python3 run_component.py "$@"
