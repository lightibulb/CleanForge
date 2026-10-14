#!/bin/sh
# Run from the repository root AFTER copying partD-fixes/ over it.
# Removes the old prototype UI (package com.yourpackage.cleanforge, fake SampleData, "Warm" design) that the real
# Material 3 UI replaces. Nothing in the real UI references these files.
set -e
B=app/src/main/java/com/cleanforge
for f in ui/CleanForgeApp.kt \
         ui/screens/MemoryScreen.kt ui/screens/Sheets.kt ui/screens/ItemRow.kt \
         ui/components/CounterHeading.kt ui/components/HeroRing.kt ui/components/RamGuage.kt ui/components/VerseHero.kt \
         ui/components/WarmButton.kt ui/components/WarmCard.kt ui/components/WarmCheck.kt ui/components/WarmDock.kt \
         ui/components/WarmFab.kt ui/components/WarmSheet.kt ui/components/WarmSwitch.kt ui/components/WarmToast.kt \
         ui/theme/Color.kt ui/theme/Type.kt ui/theme/WarmPalette.kt ui/util/Haptics.kt \
         data/ScanModels.kt; do
  if [ -f "$B/$f" ]; then rm "$B/$f"; echo "removed $f"; fi
done
# Preferences/Verses are kept (self-contained, currently unused): only fix their package.
[ -f "$B/data/Preferences.kt" ] && sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g' "$B/data/Preferences.kt"
[ -f "$B/data/Verses.kt" ] && sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g' "$B/data/Verses.kt"
echo "References to the removed prototype (should print nothing):"
grep -rnE "yourpackage|LocalWarmPalette|SampleData|rememberHaptics" app/src --include=*.kt || true
