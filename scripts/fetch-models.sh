#!/usr/bin/env bash
# Downloads the published model files listed in app/src/main/assets/models/models.json (the same catalog the app
# reads) and verifies each SHA-256. Files that are already present and correct are skipped.
# The JVM tests read them from build/models (or $VOICETRANS_MODELS_DIR).
# Needs: bash, curl, python3, shasum. Usage: scripts/fetch-models.sh [dest-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$ROOT/app/src/main/assets/models/models.json"
DEST="${1:-$ROOT/build/models}"
mkdir -p "$DEST"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

# One "url <TAB> sha256 <TAB> dest" row per file, from every group the app downloads.
plan() {
  python3 - "$CFG" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
for group in ("files", "nmt"):
    for f in c.get(group, []):
        print("\t".join([f["url"], f["sha256"], f["dest"]]))
PY
}

count=0
while IFS=$'\t' read -r url want dest; do
  count=$((count + 1))
  out="$DEST/$dest"
  if [ -f "$out" ] && [ "$(sha "$out")" = "$want" ]; then echo "ok      $dest"; continue; fi
  mkdir -p "$(dirname "$out")"
  echo "fetch   $dest"
  curl -fL --retry 3 --retry-delay 2 -sS -o "$out.part" "$url"
  if [ "$(sha "$out.part")" != "$want" ]; then
    rm -f "$out.part"
    echo "SHA-256 mismatch for $dest" >&2
    exit 1
  fi
  mv "$out.part" "$out"
done < <(plan)

[ "$count" -gt 0 ] || { echo "No model files listed in $CFG" >&2; exit 1; }
echo "All $count model files present and verified in $DEST"
