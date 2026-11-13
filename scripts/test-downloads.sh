#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
mkdir -p build/download-tests
javac -source 8 -target 8 -d build/download-tests src/space/glidetv/launcher/ApkSource.java tests/ApkSourceTest.java
java -cp build/download-tests space.glidetv.launcher.ApkSourceTest
