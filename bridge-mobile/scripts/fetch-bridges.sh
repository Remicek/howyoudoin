#!/usr/bin/env bash
# Checks out the network clients hydbridge is built from, at the versions the app uses, next to this repository in
# ../howyoudoin-bridges (where bridge-mobile/go.mod expects them), and applies our patches (mautrix-meta, and mautrix-linkedin: starting a chat with the user's own first
# message, and people's headlines in search results):
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
    "mautrix-linkedin https://github.com/mautrix/linkedin f1aac6b7e0b297671940f6acf41eec836f81c441"
    "mautrix-gvoice https://github.com/mautrix/gvoice 4f5ca4619d79a03e074009c1cdd5bb31c4c6dd69"
    "mautrix-gmessages https://github.com/mautrix/gmessages 138a57dcecca697feb492dd2d94fa4a30034c000"
    "mautrix-twitter https://github.com/mautrix/twitter bf81ec50dba36469207547a0191f9da06c6f3842"
    # SQLCipher driver (tag v0.2.1), used in place of mattn/go-sqlite3: see below and bridge-mobile/go.mod
    "go-sqlcipher https://github.com/thinkgos/go-sqlcipher ad3e272e584b1704eb586b46ce1d3c28f76b7711"
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

# go.mod replaces github.com/mattn/go-sqlite3 with the SQLCipher driver, whose module has to carry that path
sqlcipher="$BRIDGES/go-sqlcipher/go.mod"
if grep -q '^module github.com/thinkgos/go-sqlcipher' "$sqlcipher"; then
    sed -i.bak 's#^module github.com/thinkgos/go-sqlcipher#module github.com/mattn/go-sqlite3#' "$sqlcipher" && rm -f "$sqlcipher.bak"
    echo "go-sqlcipher: module path set"
fi

# Each patch goes on an unchanged checkout only, so running this again leaves a patched one alone
for name in mautrix-meta mautrix-linkedin; do
    dir="$BRIDGES/$name"
    if git -C "$dir" diff --quiet && git -C "$dir" apply --check "$MODULE_DIR/patches/$name.patch" 2>/dev/null; then
        git -C "$dir" apply "$MODULE_DIR/patches/$name.patch"
        echo "$name: patch applied"
    fi
done
