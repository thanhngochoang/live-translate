#!/usr/bin/env bash
# Fetches and compiles llama.cpp with Apple Metal and Apple Silicon M4 optimizations
# for macOS 15+ / 27 (V2 Edition).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/macos/Engines/llama.cpp"
LIB_DEST="$ROOT/macos/Engines/bin-m4"

echo "Checking llama.cpp source..."
if [ ! -d "$BUILD_DIR" ]; then
    echo "Cloning llama.cpp (shallow)..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$BUILD_DIR"
fi

echo "Compiling llama.cpp V2 for Apple M4 / macOS 15+..."
cd "$BUILD_DIR"
cmake -B build-m4 \
    -DGGML_METAL=ON \
    -DGGML_METAL_EMBED_LIBRARY=ON \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_C_FLAGS="-O3 -mcpu=apple-m2" \
    -DCMAKE_CXX_FLAGS="-O3 -mcpu=apple-m2"

cmake --build build-m4 --config Release -j$(sysctl -n hw.ncpu) --target llama

echo "Installing compiled V2 libraries to $LIB_DEST..."
mkdir -p "$LIB_DEST/include"
cp -a build-m4/bin/*.dylib* "$LIB_DEST/"
cp -a include/* "$LIB_DEST/include/"
cp -a ggml/include/* "$LIB_DEST/include/"

echo "Compiling libllama_bridge.dylib for M4 V2..."
clang++ -std=c++17 -O3 -mcpu=apple-m2 \
    -I "$LIB_DEST/include" \
    -I "$ROOT/macos/Engines/bin" \
    -L "$LIB_DEST" \
    "$ROOT/macos/Engines/bin/LlamaBridge.cpp" \
    -lllama -lggml -lggml-base -lggml-cpu -lggml-metal \
    -shared -o "$LIB_DEST/libllama_bridge.dylib" \
    -Wl,-rpath,@loader_path

echo "Success! M4 V2 Metal-accelerated engine built at: $LIB_DEST"
