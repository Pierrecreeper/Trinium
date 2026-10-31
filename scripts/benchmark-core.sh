#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d target/test-classes ] || { echo "Compiler et tester le projet d'abord."; exit 1; }
java -cp target/classes:target/test-classes fr.pierrecreeper.trinium.CoreBenchmark
