#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
javac -source 8 -target 8 -d "$output" src/space/glidetv/launcher/IconPalette.java tests/IconPaletteTest.java
java -cp "$output" space.glidetv.launcher.IconPaletteTest
