#!/usr/bin/env bash
# Regenerate `spike-bindgen/src/wasmWasiMain/kotlin/` from `vcyclist-engine.wit` with JetBrains'
# Kotlin fork of wit-bindgen — the half of the w13 spike that answers "does anything generate the
# Canonical ABI glue for Kotlin?".
#
# Separate from reproduce.sh because it builds a Rust CLI from source (~5 min the first time).
# The generated files are committed, so reading them needs neither cargo nor this script.
set -euo pipefail

cd "$(dirname "$0")"
BUILD=$PWD/build
mkdir -p "$BUILD"

# There is no release and no crates.io publication: the generator lives on the `kotlin` branch of
# a fork, and the sample project itself clones and builds it. Pin the commit that w13 measured.
REPO=https://github.com/Kotlin/wit-bindgen.git
BRANCH=kotlin
COMMIT=efcd80ba8            # 2026-07-21, "Fix wit-bindgen issue template path for windows"

if [ ! -x "$BUILD/wit-bindgen/target/release/wit-bindgen" ]; then
  [ -d "$BUILD/wit-bindgen" ] || git clone -q --branch "$BRANCH" "$REPO" "$BUILD/wit-bindgen"
  git -C "$BUILD/wit-bindgen" checkout -q "$COMMIT" 2>/dev/null || true
  cargo build --release --manifest-path "$BUILD/wit-bindgen/Cargo.toml" -p wit-bindgen-cli
fi
BINDGEN=$BUILD/wit-bindgen/target/release/wit-bindgen
"$BINDGEN" --version

# The `engine` world (the wasi:http one) is dropped from the input: the generator refuses the
# wasi-http WIT tree with "Duplicate interface names found in generation plan (most likely due to
# multiple versions of the package)" — wasi:filesystem/preopens appears at both 0.2.6 and 0.2.7 in
# it. That is a finding, not a workaround: see §2 of docs/wasm-wasi-component-model.md.
rm -rf "$BUILD/wit-hosted" && mkdir -p "$BUILD/wit-hosted"
awk '/^world engine \{/{exit} {print}' vcyclist-engine.wit > "$BUILD/wit-hosted/vcyclist-engine.wit"

rm -rf spike-bindgen/src/wasmWasiMain/kotlin
mkdir -p spike-bindgen/src/wasmWasiMain/kotlin
"$BINDGEN" kotlin "$BUILD/wit-hosted" \
  --world engine-hosted-tiles \
  --kotlin-package-name io.github.glandais.engine.wit \
  --generate-stubs \
  --out-dir spike-bindgen/src/wasmWasiMain/kotlin

echo
echo "generated, in lines:"
wc -l spike-bindgen/src/wasmWasiMain/kotlin/*.kt spike-bindgen/src/wasmWasiMain/kotlin/runtime/*.kt

cat <<'EOF'

NOTE: `EngineHostedTilesImpl.kt` is the *stub* file — the only one a human writes. Regenerating
overwrites the three method bodies the spike filled in (parseGpx, size, totalDistance); `git diff`
shows them, `git checkout` puts them back.
EOF
