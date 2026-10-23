#!/usr/bin/env bash
# Builds the on-device Messenger bridge (hydbridge) as static C libraries for the iOS app:
#   bridge-mobile/scripts/build-ios.sh
# Output: composeApp/libs/ios/<slice>/libhydbridge.a + libhydbridge.h, used by composeApp's cinterop.
# A plain C API (cmd/hydbridgec) is used because Kotlin/Native's cinterop can't read gomobile's Objective-C
# headers with current Xcode SDKs. Needs Go, Xcode and the patched mautrix-meta checkout (see build-android.sh).
set -euo pipefail
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$MODULE_DIR/../composeApp/libs/ios"
MIN_IOS=15.0
[ -d "$MODULE_DIR/../../howyoudoin-bridges/mautrix-meta" ] || { echo "mautrix-meta checkout missing" >&2; exit 1; }
cd "$MODULE_DIR"

build() { # slice sdk goarch clang-target
    local slice=$1 sdk=$2 goarch=$3 target=$4
    local sysroot; sysroot=$(xcrun --sdk "$sdk" --show-sdk-path)
    mkdir -p "$OUT/$slice"
    CGO_ENABLED=1 GOOS=ios GOARCH="$goarch" \
        CC="$(xcrun --sdk "$sdk" -f clang) -isysroot $sysroot -target $target" \
        CGO_CFLAGS="-fembed-bitcode=off" \
        go build -buildmode=c-archive -tags goolm -trimpath -ldflags="-s -w" -o "$OUT/$slice/libhydbridge.a" ./cmd/hydbridgec
    echo "Built $slice"
}

build ios-arm64 iphoneos arm64 "arm64-apple-ios$MIN_IOS"
build ios-simulator-arm64 iphonesimulator arm64 "arm64-apple-ios$MIN_IOS-simulator"
build ios-simulator-x64 iphonesimulator amd64 "x86_64-apple-ios$MIN_IOS-simulator"
