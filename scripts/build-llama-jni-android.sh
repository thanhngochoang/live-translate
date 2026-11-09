#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_DIR="/Users/macbook/Documents/Projects/MINE/photo-booking/booking/.android-sdk/ndk/28.2.13676358"
CXX="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

echo "Compiling libllama_jni.so for Android arm64-v8a..."
"$CXX" -std=c++17 -O3 -fPIC -shared \
    -I "$ROOT/macos/Engines/bin/include" \
    -I "$ROOT/app/src/main/cpp" \
    -L "$JNI_DIR" \
    "$ROOT/app/src/main/cpp/llama_jni.cpp" \
    -lllama -lggml -lggml-base -lggml-cpu -llog \
    -o "$JNI_DIR/libllama_jni.so"

echo "libllama_jni.so built successfully at $JNI_DIR/libllama_jni.so"
