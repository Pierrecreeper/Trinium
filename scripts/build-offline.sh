#!/usr/bin/env bash
# Requires an already prepared official client and cached Maven plugins/dependencies.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -o -Pnative clean verify
