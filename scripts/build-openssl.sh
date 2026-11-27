#!/usr/bin/env bash
# Builds a static libcrypto (OpenSSL 3.0 LTS) for every Android ABI the app ships.
# Output: app/src/main/cpp/third_party/openssl/<ABI>/{include,lib/libcrypto.a}
#
# Requires: ANDROID_NDK_ROOT (or ANDROID_NDK_HOME), perl, make, curl.
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
API="${ANDROID_API:-24}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/cpp/third_party/openssl"
WORK="${OPENSSL_WORK_DIR:-$ROOT/build/openssl}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "ANDROID_NDK_ROOT is not set or does not exist" >&2
  exit 1
fi
export ANDROID_NDK_ROOT="$NDK"

case "$(uname -s)" in
  Linux)  HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"

mkdir -p "$WORK"
TARBALL="$WORK/openssl-$OPENSSL_VERSION.tar.gz"
if [ ! -f "$TARBALL" ]; then
  curl -fL --retry 3 -o "$TARBALL" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
fi

JOBS="$( (nproc || sysctl -n hw.ncpu) 2>/dev/null || echo 4)"

for ABI in $ABIS; do
  if [ -f "$OUT/$ABI/lib/libcrypto.a" ]; then
    echo "OpenSSL for $ABI already built"
    continue
  fi
  case "$ABI" in
    arm64-v8a)   TARGET=android-arm64 ;;
    armeabi-v7a) TARGET=android-arm ;;
    x86_64)      TARGET=android-x86_64 ;;
    x86)         TARGET=android-x86 ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  SRC="$WORK/src-$ABI"
  rm -rf "$SRC"
  mkdir -p "$SRC"
  tar -xzf "$TARBALL" -C "$SRC" --strip-components=1
  (
    cd "$SRC"
    ./Configure "$TARGET" -D__ANDROID_API__="$API" \
      no-shared no-tests no-engine no-dso no-ui-console no-ssl3 no-comp \
      --prefix="$OUT/$ABI" --libdir=lib
    make -j"$JOBS" build_libs
    make install_dev
  )
  rm -f "$OUT/$ABI/lib/libssl.a"
  rm -rf "$OUT/$ABI/lib/pkgconfig" "$OUT/$ABI/lib/cmake" "$SRC"
  echo "OpenSSL $OPENSSL_VERSION for $ABI installed in $OUT/$ABI"
done
