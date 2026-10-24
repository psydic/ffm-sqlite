#!/usr/bin/env bash
# Builds target/test-ext/libffmtest.so (src/test/c/ffmtest.c), which the extension tests load; without it
# they are skipped. Needs gcc and SQLite's headers: from $SQLITE_INCLUDE, or the official amalgamation.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=target/test-ext
mkdir -p "$OUT"
INCLUDE=${SQLITE_INCLUDE:-}
if [[ -z "$INCLUDE" ]]; then
  WORK=$(mktemp -d)
  trap 'rm -rf "$WORK"' EXIT
  curl -fsSL -o "$WORK/src.zip" "https://www.sqlite.org/2025/sqlite-amalgamation-3500300.zip"
  unzip -q "$WORK/src.zip" -d "$WORK"
  INCLUDE="$WORK/sqlite-amalgamation-3500300"
fi
gcc -O2 -fPIC -shared -I"$INCLUDE" -o "$OUT/libffmtest.so" src/test/c/ffmtest.c
echo "built $(pwd)/$OUT/libffmtest.so"
