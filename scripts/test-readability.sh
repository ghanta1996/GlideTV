#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" src/space/glidetv/launcher/Readability.java tests/ReadabilityTest.java
java -cp "$out" space.glidetv.launcher.ReadabilityTest
