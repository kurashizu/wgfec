#!/bin/bash
# Builds speederv2 for arm64 Android (UDPspeeder, MIT licence) and puts it where Gradle packs it:
# android/build/jniLibs/arm64-v8a/libspeederv2.so. The app starts it as a child process.
# Needs the Android NDK: set ANDROID_NDK_HOME, or ANDROID_HOME with NDK 26.3.11579264 installed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
REF=b6a1b5941d9ebb6ef4f6866abdf1e03a47b99b2d          # UDPspeeder commit this app was built and tested with
NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_NDK_HOME or ANDROID_HOME}/ndk/26.3.11579264}
CXX=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++
[ -x "$CXX" ] || { echo "NDK compiler not found: $CXX"; exit 1; }
W=$HERE/build/UDPspeeder
[ -d "$W/.git" ] || git clone -q https://github.com/wangyu-/UDPspeeder "$W"
git -C "$W" checkout -q "$REF"
echo "const char gitversion[] = \"$REF\";" > "$W/git_version.h"
OUT=$HERE/build/jniLibs/arm64-v8a; mkdir -p "$OUT"
cd "$W"
"$CXX" -o "$OUT/libspeederv2.so" -I. -include string.h -Dbcmp=memcmp \
  main.cpp log.cpp common.cpp lib/fec.cpp lib/rs.cpp crc32/Crc32.cpp packet.cpp delay_manager.cpp fd_manager.cpp \
  connection.cpp fec_manager.cpp misc.cpp tunnel_client.cpp tunnel_server.cpp my_ev.cpp -isystem libev \
  -std=c++11 -O2 -fPIE -pie -static-libstdc++ -Wno-unused-parameter -Wno-deprecated-declarations
ls -la "$OUT/libspeederv2.so"
