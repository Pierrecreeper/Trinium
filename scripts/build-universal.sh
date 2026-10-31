#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Requires six previously built/tested native bundles. It does NOT cross-compile them.
python3 scripts/prepare-game.py
mvn -Puniversal clean verify
java -jar target/Trinium-0.1.0-mc26.3-universal.jar --verify-universal --require-native --selftest
