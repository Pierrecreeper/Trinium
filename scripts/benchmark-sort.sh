#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt -Dmdep.includeScope=test
java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/test-classes:$(cat target/benchmark-classpath.txt)" fr.pierrecreeper.trinium.GameBenchmark
