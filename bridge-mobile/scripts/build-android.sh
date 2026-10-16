#!/usr/bin/env bash
# Builds the on-device Messenger bridge (hydbridge) as an Android library for the app:
#   bridge-mobile/scripts/build-android.sh
# Needs Go, gomobile/gobind (go install golang.org/x/mobile/cmd/gomobile@latest, same for gobind), the Android
# NDK, and the patched mautrix-meta checkout in ../howyoudoin-bridges (howyoudoin-api/scripts/setup-mautrix-meta.sh).
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE_DIR/../composeApp/libs/hydbridge.aar"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$MODULE_DIR/../../howyoudoin-bridges/mautrix-meta" ] || { echo "mautrix-meta checkout missing" >&2; exit 1; }
[ -d "$NDK" ] || { echo "Android NDK not found under $SDK/ndk" >&2; exit 1; }
export PATH="$HOME/go/bin:$PATH" ANDROID_HOME="$SDK" ANDROID_NDK_HOME="$NDK"
mkdir -p "$(dirname "$OUT")"
cd "$MODULE_DIR"
# arm64 covers current phones; add android/amd64 for emulators when needed
gomobile bind -target=android/arm64 -androidapi 26 -javapkg com.devvalor.howyoudoing.bridge -ldflags="-s -w" -o "$OUT" ./hydbridge
echo "Built $OUT"
