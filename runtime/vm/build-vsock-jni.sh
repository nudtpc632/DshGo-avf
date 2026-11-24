#!/usr/bin/env bash
# Builds libvsock_client.so (host-side AF_VSOCK JNI client for the AVF backend)
# into runtime/out-vm/jniLibs/<abi>/, which app/android/app/build.gradle.kts
# picks up as a jniLibs source dir. Requires the Android NDK (r28 default,
# same as Flutter's ndkVersion); set NDK_DIR to override.
#
#   bash runtime/vm/build-vsock-jni.sh            # arm64-v8a
#   NDK_DIR=/path/to/ndk bash runtime/vm/build-vsock-jni.sh
set -euo pipefail

HERE="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../../app/android/app/src/main/cpp/vsock_client.c"
OUT="${VM_OUT:-$HERE/../out-vm}/jniLibs"
ABI="${1:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android ;;
  x86_64)    TRIPLE=x86_64-linux-android ;;
  *) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

NDK_DIR="${NDK_DIR:-}"
if [[ -z "$NDK_DIR" ]]; then
  for root in "$HOME/Android/Sdk/ndk" "$HOME/dev/android-sdk/ndk" "${ANDROID_HOME:-}/ndk"; do
    [[ -d "$root" ]] || continue
    NDK_DIR="$(find "$root" -maxdepth 1 -mindepth 1 -type d | LC_ALL=C sort -V | tail -1)"
    [[ -n "$NDK_DIR" ]] && break
  done
fi
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || { echo "Android NDK not found; set NDK_DIR" >&2; exit 1; }
HOST_TAG="$(ls "$NDK_DIR/toolchains/llvm/prebuilt" | head -1)"
CC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin/clang"
[[ -x "$CC" ]] || { echo "missing $CC" >&2; exit 1; }

mkdir -p "$OUT/$ABI"
"$CC" --target="$TRIPLE"26 -shared -fPIC -O2 -Wall -Wextra \
  -o "$OUT/$ABI/libvsock_client.so" "$SRC"
echo "built $OUT/$ABI/libvsock_client.so ($(stat -c %s "$OUT/$ABI/libvsock_client.so") bytes)"