#!/usr/bin/env bash
# Runs hydbridge's Go tests on this computer:
#   bridge-mobile/scripts/test-go.sh            offline tests against local fake servers
#   HYD_LIVE=1 bridge-mobile/scripts/test-go.sh  also checks logins that can't work against the real services
# Needs Signal's Rust library built for this computer once (in the libsignal checkout):
#   cargo build -p libsignal-ffi --release
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LIBSIGNAL="$MODULE_DIR/../../howyoudoin-bridges/mautrix-signal/pkg/libsignalgo/libsignal/target/release"
[ -f "$LIBSIGNAL/libsignal_ffi.a" ] || { echo "Build libsignal for this computer first (see above)" >&2; exit 1; }
cd "$MODULE_DIR"
# The Rust library targets a newer macOS than Go's linker assumes; its warnings about that are noise
CGO_LDFLAGS="-L$LIBSIGNAL" go test -tags goolm -count=1 "$@" ./hydbridge/ 2>&1 | grep -v 'ld: warning: object file'
