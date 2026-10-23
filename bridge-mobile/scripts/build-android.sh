#!/usr/bin/env bash
# Builds the on-device Messenger bridge (hydbridge) as an Android library for the app:
#   bridge-mobile/scripts/build-android.sh
# Needs Go, gomobile/gobind (go install golang.org/x/mobile/cmd/gomobile@latest, same for gobind), the Android
# NDK, and the patched mautrix-meta checkout in ../howyoudoin-bridges (howyoudoin-api/scripts/setup-mautrix-meta.sh).
# Telegram and Signal need mautrix-telegram and mautrix-signal (with its libsignal submodule) checked out there too;
# Signal's Rust library is built for Android with Rust + cargo-ndk (rustup target add aarch64-linux-android,
# cargo install cargo-ndk; also cmake and protoc).
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE_DIR/../composeApp/libs/hydbridge.aar"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$MODULE_DIR/../../howyoudoin-bridges/mautrix-meta" ] || { echo "mautrix-meta checkout missing" >&2; exit 1; }
[ -d "$NDK" ] || { echo "Android NDK not found under $SDK/ndk" >&2; exit 1; }
export PATH="$HOME/go/bin:/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH" ANDROID_HOME="$SDK" ANDROID_NDK_HOME="$NDK"

LIBSIGNAL="$MODULE_DIR/../../howyoudoin-bridges/mautrix-signal/pkg/libsignalgo/libsignal"
LIBSIGNAL_OUT="$LIBSIGNAL/target/aarch64-linux-android/release"
if [ ! -f "$LIBSIGNAL_OUT/libsignal_ffi.a" ]; then
    (cd "$LIBSIGNAL" && RUSTFLAGS="-Ctarget-feature=-crt-static" cargo ndk -t arm64-v8a -P 26 build -p libsignal-ffi --release)
fi
# libsignalgo links -lstdc++, which the NDK's clang turns into -lc++, i.e. libc++_shared.so at runtime; that isn't
# packaged in the app, so the library failed to load. A libc++ found first on the search path links the static
# C++ runtime instead.
STATIC_CXX="$MODULE_DIR/build/static-cxx"
mkdir -p "$STATIC_CXX"
echo "INPUT(-lc++_static -lc++abi)" > "$STATIC_CXX/libc++.so"
export CGO_LDFLAGS="-L$STATIC_CXX -L$LIBSIGNAL_OUT"
mkdir -p "$(dirname "$OUT")"
cd "$MODULE_DIR"
# arm64 covers current phones; add android/amd64 for emulators when needed
gomobile bind -target=android/arm64 -androidapi 26 -tags goolm -javapkg com.devvalor.howyoudoing.bridge -ldflags="-s -w" -o "$OUT" ./hydbridge
echo "Built $OUT"

# The license list shown in the app follows what was just linked
python3 "$MODULE_DIR/scripts/generate-licenses.py"
