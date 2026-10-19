#!/bin/sh
# Run from the repository root AFTER copying update2/ over it.
# Removes stray copies of main files that were placed in the TEST source set by mistake.
set -e
for f in Preferences ScanModels Verses; do
  p="app/src/test/java/com/cleanforge/data/$f.kt"
  if [ -f "$p" ]; then rm "$p"; echo "removed $p"; fi
done
