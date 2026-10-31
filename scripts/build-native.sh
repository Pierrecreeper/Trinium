#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo 'JDK 25+ requis pour compiler; Java 25+ pour Minecraft 26.3.'; exit 1; }
command -v mvn >/dev/null || { echo 'Maven 3.9+ requis.'; exit 1; }
command -v cargo >/dev/null || { echo 'Rust/Cargo et un linker natif sont requis.'; exit 1; }
python3 scripts/prepare-game.py
mvn -Pnative clean verify
java -jar target/Trinium-0.1.0-mc26.3.jar --require-native --selftest
