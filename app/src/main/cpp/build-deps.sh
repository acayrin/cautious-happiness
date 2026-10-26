#!/usr/bin/env bash
# Builds the prebuilt static libraries that CMakeLists.txt links against.
# Only needed when the deps change or on a fresh machine.
set -euo pipefail

SDK="${ANDROID_HOME:-/home/acayrin/android-sdk}"
NDK="$SDK/ndk/27.2.12479018"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
NINJA="$SDK/cmake/3.22.1/bin/ninja"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/deps"

# Fetch vendored sources on a fresh clone (they are git-ignored).
[ -d ctranslate2 ] || git clone --depth 1 --branch v4.8.2 https://github.com/OpenNMT/CTranslate2 ctranslate2
[ -d sentencepiece ] || git clone --depth 1 --branch v0.2.1 https://github.com/google/sentencepiece sentencepiece
( cd ctranslate2 && git submodule update --init --depth 1 third_party/spdlog third_party/cpu_features )

# sentencepiece (tokenizer)
$CMAKE -S sentencepiece -B build-spm -G Ninja -DCMAKE_MAKE_PROGRAM=$NINJA \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release -DSPM_ENABLE_SHARED=OFF -DSPM_ENABLE_TENSORFLOW=OFF \
  -DSPM_BUILD_TEST=OFF
# The spm_* CLI targets fail to link on Android (need -llog); the .a is enough.
$NINJA -C build-spm src/libsentencepiece.a || true
test -f build-spm/src/libsentencepiece.a

# ctranslate2 (inference engine; local patch: thread_pool.cc no-ops affinity on Android)
$CMAKE -S ctranslate2 -B build-ct2 -G Ninja -DCMAKE_MAKE_PROGRAM=$NINJA \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_CLI=OFF \
  -DBUILD_TESTS=OFF -DWITH_MKL=OFF -DENABLE_CPU_DISPATCH=OFF -DOPENMP_RUNTIME=NONE
$NINJA -C build-ct2
test -f build-ct2/libctranslate2.a

# whisper.cpp (speech-to-text for the subtitle feature)
[ -d whisper.cpp ] || git clone --depth 1 https://github.com/ggml-org/whisper.cpp whisper.cpp
$CMAKE -S whisper.cpp -B build-whisper -G Ninja -DCMAKE_MAKE_PROGRAM=$NINJA \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
$NINJA -C build-whisper
test -f build-whisper/src/libwhisper.a

echo "deps OK"
