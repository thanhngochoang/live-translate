#!/usr/bin/env bash
# Downloads the bundled ASR/VAD models listed in app/src/main/assets/models/models.json
# into app/src/main/assets/models/ and verifies SHA-256. Skips files that already verify.
# Needs: bash, curl, tar, python3, shasum. Usage: scripts/fetch-models.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/app/src/main/assets/models"
CFG="$DIR/models.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

ok() { [ -f "$1" ] && [ "$(sha "$1")" = "$2" ]; }

# Emit "kind|url|sha|id|innerDir" for each archive and file, then per-file rows.
python3 - "$CFG" > "$TMP/plan.tsv" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
for a in c["archives"]:
    print("\t".join(["A", a["url"], a["sha256"], a["id"], a["innerDir"]]))
    for f in a["files"]:
        print("\t".join(["AF", a["id"], f["inner"], f["dest"], f["sha256"]]))
for f in c["files"]:
    print("\t".join(["F", f["url"], f["sha256"], f["id"], f["dest"]]))
PY

# Direct files
while IFS=$'\t' read -r kind a b c d; do
  [ "$kind" = "F" ] || continue
  dest="$DIR/$d"
  if ok "$dest" "$b"; then echo "ok      $d"; continue; fi
  mkdir -p "$(dirname "$dest")"
  echo "fetch   $d"
  curl -fL --retry 3 -o "$TMP/$c" "$a"
  [ "$(sha "$TMP/$c")" = "$b" ] || { echo "SHA-256 mismatch for $d" >&2; exit 1; }
  mv "$TMP/$c" "$dest"
done < "$TMP/plan.tsv"

# Archives: only download when at least one member is missing or wrong.
while IFS=$'\t' read -r kind url sha id inner; do
  [ "$kind" = "A" ] || continue
  need=0
  while IFS=$'\t' read -r k2 aid iname dest fsha; do
    [ "$k2" = "AF" ] && [ "$aid" = "$id" ] || continue
    ok "$DIR/$dest" "$fsha" || need=1
  done < "$TMP/plan.tsv"
  if [ "$need" = 0 ]; then echo "ok      archive $id"; continue; fi
  echo "fetch   archive $id (large)"
  curl -fL --retry 3 -o "$TMP/$id.tar.bz2" "$url"
  [ "$(sha "$TMP/$id.tar.bz2")" = "$sha" ] || { echo "SHA-256 mismatch for archive $id" >&2; exit 1; }
  mkdir -p "$TMP/x/$id" && tar xjf "$TMP/$id.tar.bz2" -C "$TMP/x/$id"
  while IFS=$'\t' read -r k2 aid iname dest fsha; do
    [ "$k2" = "AF" ] && [ "$aid" = "$id" ] || continue
    src="$TMP/x/$id/$inner/$iname"
    [ "$(sha "$src")" = "$fsha" ] || { echo "SHA-256 mismatch for $iname" >&2; exit 1; }
    mkdir -p "$(dirname "$DIR/$dest")"
    cp "$src" "$DIR/$dest"
    echo "placed  $dest"
  done < "$TMP/plan.tsv"
done < "$TMP/plan.tsv"

echo "All model files present and verified."
