#!/usr/bin/env bash
# Fetches and compiles llama.cpp with Apple Metal support for macOS arm64.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/macos/Engines/llama.cpp"
LIB_DEST="$ROOT/macos/Engines/bin"

echo "Checking llama.cpp source..."
if [ ! -d "$BUILD_DIR" ]; then
    echo "Cloning llama.cpp (shallow)..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$BUILD_DIR"
fi

echo "Compiling llama.cpp with Apple Metal acceleration..."
cd "$BUILD_DIR"
cmake -B build -DGGML_METAL=ON -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j$(sysctl -n hw.ncpu) --target llama

echo "Installing compiled library to $LIB_DEST..."
mkdir -p "$LIB_DEST/include"
cp build/bin/libllama.dylib "$LIB_DEST/" 2>/dev/null || cp build/src/libllama.dylib "$LIB_DEST/"
cp build/bin/libggml*.dylib "$LIB_DEST/" 2>/dev/null || cp build/ggml/src/libggml*.dylib "$LIB_DEST/"
cp include/llama.h "$LIB_DEST/include/"
cp ggml/include/ggml.h "$LIB_DEST/include/"

echo "Success! llama.cpp with Metal acceleration built at: $LIB_DEST"
