#!/usr/bin/env bash
# Checks out the network clients hydbridge is built from, at the versions the app uses, next to this repository in
# ../howyoudoin-bridges (where bridge-mobile/go.mod expects them), and applies our patch to mautrix-meta:
#   bridge-mobile/scripts/fetch-bridges.sh
# Used by the CI build; an existing checkout is left alone.
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BRIDGES="$MODULE_DIR/../../howyoudoin-bridges"

# name, repository, commit (mautrix-meta is tag v0.2610.0)
PINNED=(
    "mautrix-meta https://github.com/mautrix/meta 3c16226686c71af64df294df0803791685064e0b"
    "mautrix-telegram https://github.com/mautrix/telegram 531adba3b6f17c599a651c5e5eaba4d292857cc3"
    "mautrix-signal https://github.com/mautrix/signal 46aff24f80816f10dae5ceff210b3dacb588a094"
    "mautrix-slack https://github.com/mautrix/slack 26a8655786662abd60fceec1d57f825fd9e4602e"
)

mkdir -p "$BRIDGES"
for entry in "${PINNED[@]}"; do
    read -r name repo commit <<< "$entry"
    dir="$BRIDGES/$name"
    if [ -d "$dir/.git" ]; then
        echo "$name: already checked out, left as it is"
        continue
    fi
    git init -q "$dir"
    git -C "$dir" fetch -q --depth 1 "$repo" "$commit"
    git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
    git -C "$dir" submodule update -q --init --depth 1
    echo "$name: $commit"
done

meta="$BRIDGES/mautrix-meta"
if git -C "$meta" diff --quiet && git -C "$meta" apply --check "$MODULE_DIR/patches/mautrix-meta.patch" 2>/dev/null; then
    git -C "$meta" apply "$MODULE_DIR/patches/mautrix-meta.patch"
    echo "mautrix-meta: patch applied"
fi
