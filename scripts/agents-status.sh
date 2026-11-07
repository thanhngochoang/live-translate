#!/usr/bin/env bash
# Coordination overview for parallel agents (see AGENTS.md → "Git and multiple agents").
set -euo pipefail
ROOT="$(cd "$(git rev-parse --path-format=absolute --git-common-dir)/.." && pwd)"
COORD="$ROOT/.coord"

echo "== Worktrees"
git -C "$ROOT" worktree list

echo
echo "== Branches vs main (ahead/behind)"
for br in $(git -C "$ROOT" for-each-ref --format='%(refname:short)' refs/heads/agent/); do
  counts=$(git -C "$ROOT" rev-list --left-right --count "main...$br")
  behind=${counts%%[[:space:]]*}; ahead=${counts##*[[:space:]]}
  last=$(git -C "$ROOT" log -1 --format='%h %s (%cr)' "$br")
  echo "  $br: +$ahead / -$behind | $last"
done

echo
echo "== Uncommitted changes per worktree"
git -C "$ROOT" worktree list --porcelain | awk '/^worktree /{print $2}' | while read -r wt; do
  n=$(git -C "$wt" status --porcelain | wc -l | tr -d ' ')
  echo "  $wt: $n file(s)"
done

echo
echo "== Device lock"
if [ -d "$COORD/device.lock" ]; then
  echo "  HELD by: $(cat "$COORD/device.lock/owner" 2>/dev/null || echo '?') since $(stat -f '%Sm' "$COORD/device.lock")"
else
  echo "  free"
fi

echo
echo "== Board"
if [ -f "$COORD/board.md" ]; then cat "$COORD/board.md"; else echo "  missing: $COORD/board.md"; fi
