#!/usr/bin/env bash
# Downloads Qwen2.5-0.5B-Instruct-Q4_K_M.gguf into app/src/main/assets/models/llm/
# for context-aware on-device translation on both Android and macOS.
# Lightweight (~468 MB), ultra-fast on-device inference (~70 ms/sentence).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="$ROOT/app/src/main/assets/models/llm"
TARGET_FILE="$TARGET_DIR/qwen2.5-0.5b-instruct-q4_k_m.gguf"
MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
EXPECTED_SIZE=491400032

mkdir -p "$TARGET_DIR"

if [ -f "$TARGET_FILE" ]; then
    ACTUAL_SIZE=$(stat -f%z "$TARGET_FILE" 2>/dev/null || stat -c%s "$TARGET_FILE" 2>/dev/null || echo 0)
    if [ "$ACTUAL_SIZE" -eq "$EXPECTED_SIZE" ]; then
        echo "Qwen 2.5 0.5B GGUF model already present and verified ($ACTUAL_SIZE bytes)."
        exit 0
    else
        echo "File exists but size mismatch ($ACTUAL_SIZE vs $EXPECTED_SIZE). Re-downloading..."
    fi
fi

echo "Downloading Qwen 2.5 0.5B Instruct GGUF (~468 MB) from Hugging Face..."
TMP_FILE="${TARGET_FILE}.tmp"
curl -L --retry 3 --progress-bar -o "$TMP_FILE" "$MODEL_URL"

ACTUAL_SIZE=$(stat -f%z "$TMP_FILE" 2>/dev/null || stat -c%s "$TMP_FILE" 2>/dev/null || echo 0)
if [ "$ACTUAL_SIZE" -ne "$EXPECTED_SIZE" ]; then
    echo "Error: Downloaded file size ($ACTUAL_SIZE) does not match expected size ($EXPECTED_SIZE)!" >&2
    rm -f "$TMP_FILE"
    exit 1
fi

mv "$TMP_FILE" "$TARGET_FILE"
echo "Model successfully downloaded to: $TARGET_FILE"
