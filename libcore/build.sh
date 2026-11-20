#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"

# Build standalone root sing-box binary (libsingbox.so) for each Android ABI
# Packaged via app/executableSo/<abi>/libsingbox.so so Android extracts it to nativeLibraryDir
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CORE_TAGS="with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
EXEC_SO_DIR="../app/executableSo"
mkdir -p "$EXEC_SO_DIR/arm64-v8a" "$EXEC_SO_DIR/armeabi-v7a" "$EXEC_SO_DIR/x86_64" "$EXEC_SO_DIR/x86"

build_root_core() {
  local abi="$1"
  local goarch="$2"
  local goarm="$3"
  local cc_bin="$4"
  echo ">> building libsingbox.so for $abi ($goarch)..."
  if [ -n "$goarm" ]; then
    CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM="$goarm" CC="$cc_bin" \
      go build -v -trimpath -ldflags='-s -w -checklinkname=0' -tags="$CORE_TAGS" \
      -o "$EXEC_SO_DIR/$abi/libsingbox.so" ./cmd/tsukimibox-core || exit 1
  else
    CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc_bin" \
      go build -v -trimpath -ldflags='-s -w -checklinkname=0' -tags="$CORE_TAGS" \
      -o "$EXEC_SO_DIR/$abi/libsingbox.so" ./cmd/tsukimibox-core || exit 1
  fi
}

build_root_core "arm64-v8a" "arm64" "" "${ANDROID_ARM64_CC:-$NDK_BIN/aarch64-linux-android21-clang}"
build_root_core "armeabi-v7a" "arm" "7" "${ANDROID_ARM_CC:-$NDK_BIN/armv7a-linux-androideabi21-clang}"
build_root_core "x86_64" "amd64" "" "${ANDROID_X86_64_CC:-$NDK_BIN/x86_64-linux-android21-clang}"
build_root_core "x86" "386" "" "${ANDROID_X86_CC:-$NDK_BIN/i686-linux-android21-clang}"
echo ">> installed root sing-box binaries into $(realpath $EXEC_SO_DIR)"
