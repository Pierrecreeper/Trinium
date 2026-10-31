#!/usr/bin/env bash
# Linux-only negative native tests. These C libraries are never distributed in the mod.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Linux ]]; then echo 'This negative-fixture test script targets Linux only.' >&2; exit 2; fi
[[ -d target/test-classes ]] || bash scripts/build-offline.sh
JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
for mode in abi mismatch crash; do
  flags=(); [[ "$mode" != abi ]] || flags+=(-DBAD_ABI)
  [[ "$mode" != crash ]] || flags+=(-DABORT_TEST)
  cc -shared -fPIC "${flags[@]}" -I"$JDK/include" -I"$JDK/include/linux" src/test/native/invalid_jni.c -o "$tmp/lib-$mode.so"
done
java -ea -cp target/classes:target/test-classes fr.pierrecreeper.trinium.LocalNativeBuildTest \
 "$tmp/lib-abi.so" "$tmp/lib-mismatch.so" "$tmp/lib-crash.so"
