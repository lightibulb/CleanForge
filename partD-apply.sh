#!/bin/sh
# Run from the repository root AFTER copying partD-fixes/ over it.
set -e
B=app/src/main/java/com/cleanforge

# Superseded: the unfinished Material NavHost UI, and the fake-data prototype model. The Warm UI replaces them.
for f in ui/CleanForgeNavHost.kt ui/screens/RamScreen.kt ui/components/ItemComponents.kt data/ScanModels.kt; do
  if [ -f "$B/$f" ]; then rm "$B/$f"; echo "removed $f"; fi
done

# Placeholder package in the two data files the Warm shell uses.
for f in data/Preferences.kt data/Verses.kt; do
  [ -f "$B/$f" ] && sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g' "$B/$f"
done

echo "References to the placeholder package or the fake data (should print nothing):"
grep -rnE "yourpackage|SampleData" app/src --include=*.kt || true
