#!/bin/sh
# Run from the CleanForge repository root after extracting this patch over the project.
set -eu
B=app/src/main/java/com/cleanforge
# Remove only the obsolete fake-data prototype. Keep Color.kt and Type.kt: this patch
# replaces them with the real web-app-derived palette and font typography.
for f in \
  ui/CleanForgeApp.kt \
  ui/screens/MemoryScreen.kt ui/screens/Sheets.kt ui/screens/ItemRow.kt \
  ui/components/CounterHeading.kt ui/components/HeroRing.kt ui/components/RamGuage.kt ui/components/VerseHero.kt \
  ui/components/WarmButton.kt ui/components/WarmCard.kt ui/components/WarmCheck.kt ui/components/WarmDock.kt \
  ui/components/WarmFab.kt ui/components/WarmSheet.kt ui/components/WarmSwitch.kt ui/components/WarmToast.kt \
  ui/theme/WarmPalette.kt ui/util/Haptics.kt data/ScanModels.kt; do
  if [ -f "$B/$f" ]; then rm "$B/$f"; echo "removed obsolete prototype: $f"; fi
done
# Preferences/Verses are legacy-only and harmless if they exist; repair package names to avoid stale imports.
[ ! -f "$B/data/Preferences.kt" ] || sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g' "$B/data/Preferences.kt"
[ ! -f "$B/data/Verses.kt" ] || sed -i 's/com\.yourpackage\.cleanforge/com.cleanforge/g' "$B/data/Verses.kt"
if grep -rnE 'yourpackage|LocalWarmPalette|SampleData|rememberHaptics' app/src --include='*.kt'; then
  echo 'Review the legacy references printed above before building.'
fi
