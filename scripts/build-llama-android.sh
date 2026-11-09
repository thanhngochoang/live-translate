#!/usr/bin/env bash
# Fetches and compiles llama.cpp for Android arm64-v8a using Android NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_DIR="/Users/macbook/Documents/Projects/MINE/photo-booking/booking/.android-sdk/ndk/28.2.13676358"
BUILD_DIR="$ROOT/macos/Engines/llama.cpp"
JNI_DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"

echo "Checking Android NDK..."
if [ ! -d "$NDK_DIR" ]; then
    echo "Error: Android NDK not found at $NDK_DIR" >&2
    exit 1
fi

if [ ! -d "$BUILD_DIR" ]; then
    echo "Cloning llama.cpp (shallow)..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$BUILD_DIR"
fi

echo "Compiling llama.cpp for Android arm64-v8a..."
cd "$BUILD_DIR"
cmake -B build-android \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_OPENMP=OFF

cmake --build build-android --config Release -j$(sysctl -n hw.ncpu) --target llama

echo "Installing libllama.so to $JNI_DEST..."
mkdir -p "$JNI_DEST"
cp build-android/bin/libllama.so "$JNI_DEST/" 2>/dev/null || cp build-android/src/libllama.so "$JNI_DEST/"
cp build-android/bin/libggml*.so "$JNI_DEST/" 2>/dev/null || cp build-android/ggml/src/libggml*.so "$JNI_DEST/"

echo "Success! Android arm64-v8a shared libraries installed to: $JNI_DEST"
