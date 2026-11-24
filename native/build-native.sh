#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r30}"
output_dir="${1:?Pass the absolute JNI output directory}"
native_root="$(cd "$(dirname "$0")" && pwd)"
clang_dir="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=1 GOOS=android
[[ "$(go env GOVERSION)" == go1.27.4 ]] || { echo "Go 1.27.4 required, found $(go env GOVERSION)" >&2; exit 1; }
grep -Eq '^Pkg\.Revision[[:space:]]*=[[:space:]]*30\.0\.16248370[[:space:]]*$' "$ANDROID_NDK_HOME/source.properties" || { echo "Android NDK r30 (30.0.16248370) required" >&2; exit 1; }
cd "$native_root/quic"
go mod verify >/dev/null
for target in arm64-v8a armeabi-v7a x86 x86_64; do
  case "$target" in
    arm64-v8a) arch=arm64; cc=aarch64-linux-android29-clang ;;
    armeabi-v7a) arch=arm; cc=armv7a-linux-androideabi29-clang ;;
    x86) arch=386; cc=i686-linux-android29-clang ;;
    x86_64) arch=amd64; cc=x86_64-linux-android29-clang ;;
  esac
  mkdir -p "$output_dir/$target"
  GOARCH="$arch" GOARM=7 CC="$clang_dir/$cc" go build -buildvcs=false -trimpath -buildmode=c-shared \
    -ldflags='-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384' \
    -o "$output_dir/$target/libdnsbunker_quic.so" .
  rm -f "$output_dir/$target/libdnsbunker_quic.h"
done
