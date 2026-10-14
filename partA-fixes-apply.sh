#!/bin/sh
# Run from the repository root AFTER copying partA-fixes/ over it.
set -e
SRC=app/src
echo "Fixing placeholder package com.yourpackage.cleanforge -> com.cleanforge"
grep -rl "com.yourpackage.cleanforge" "$SRC" | xargs -r sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g'

# ScanModels.kt is an old prototype (a second ScanItem/Reversibility, fake SampleData). Delete it only if nothing uses it.
F="$SRC/main/java/com/cleanforge/data/ScanModels.kt"
if [ -f "$F" ]; then
  USES=$(grep -rnE "SampleData|import com\.cleanforge\.data\.(\*|ScanItem|Risk|Confidence|Reversibility|formatBytes)" "$SRC" --include=*.kt | grep -v "ScanModels.kt" || true)
  if [ -n "$USES" ]; then
    echo "KEEPING ScanModels.kt, still referenced:"; echo "$USES"
  else
    rm "$F"; echo "Deleted unreferenced prototype ScanModels.kt"
  fi
fi
