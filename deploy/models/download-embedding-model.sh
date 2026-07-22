#!/bin/bash
#================================================================================
# Download bge-large-zh-v1.5 local Embedding model (ONNX format)
#
# Uses hf-mirror.com (China mirror) by default for faster access without VPN.
# Pass --official as $1 to download from huggingface.co directly (requires VPN in China).
#
# Usage:
#   ./download-embedding-model.sh                  # mirror, download to ./bge-large-zh-v1.5/ (same dir as script)
#   ./download-embedding-model.sh --official       # official huggingface.co (needs VPN)
#   ./download-embedding-model.sh /custom/path     # download to /custom/path/bge-large-zh-v1.5/
#
# Model: BAAI/bge-large-zh-v1.5 (Chinese Embedding, 1024 dims, ONNX Runtime, zero API calls)
# Size:  model.onnx ~1.3GB, tokenizer.json ~1.2MB
# ONNX output layer: last_hidden_state (config: scaffold.embedding.local.model-output-name)
#================================================================================

set -e

MODEL_NAME="bge-large-zh-v1.5"

# Parse args: --official flag + optional target dir
USE_OFFICIAL=0
TARGET_DIR_ARG=""
for arg in "$@"; do
    if [ "$arg" = "--official" ]; then
        USE_OFFICIAL=1
    else
        TARGET_DIR_ARG="$arg"
    fi
done

# China mirror (hf-mirror.com) by default, official huggingface.co if --official
if [ "$USE_OFFICIAL" -eq 1 ]; then
    BASE_HOST="huggingface.co"
else
    BASE_HOST="hf-mirror.com"
fi
BASE_URL="https://${BASE_HOST}/Xenova/bge-large-zh-v1.5/resolve/main"

# Resolve target: arg if provided, otherwise script's own directory (models/)
if [ -n "$TARGET_DIR_ARG" ]; then
    BASE_DIR="$TARGET_DIR_ARG"
else
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    BASE_DIR="${SCRIPT_DIR}"
fi
TARGET_DIR="${BASE_DIR}/${MODEL_NAME}"

echo "========================================"
echo "  Download ${MODEL_NAME} local Embedding model"
echo "========================================"
echo "Source: ${BASE_HOST}"
echo "Target: ${TARGET_DIR}"
echo ""

# Create base directory if it does not exist
if [ ! -d "${BASE_DIR}" ]; then
    mkdir -p "${BASE_DIR}"
    echo "Created base dir: ${BASE_DIR}"
fi

# Create model subdirectory
mkdir -p "${TARGET_DIR}"
echo "Created: ${TARGET_DIR}"

# Download function (supports resume)
download_file() {
    local file_name="$1"
    local file_url="$2"
    local file_path="${TARGET_DIR}/${file_name}"

    if [ -f "${file_path}" ] && [ -s "${file_path}" ]; then
        local existing_size=$(du -h "${file_path}" | cut -f1)
        echo "[SKIP] ${file_name} already exists (${existing_size})"
        return 0
    fi

    echo "[DOWN] ${file_name} ..."
    if command -v wget &> /dev/null; then
        wget -c -q --show-progress -O "${file_path}" "${file_url}"
    elif command -v curl &> /dev/null; then
        curl -L -C - -o "${file_path}" "${file_url}"
    else
        echo "[FAIL] ${file_name}: neither wget nor curl found"
        echo "  Manual download: ${file_url}"
        exit 1
    fi

    local file_size=$(du -h "${file_path}" | cut -f1)
    echo "[DONE] ${file_name} (${file_size})"
}

download_file "model.onnx" "${BASE_URL}/onnx/model.onnx"
download_file "tokenizer.json" "${BASE_URL}/tokenizer.json"

echo ""
echo "========================================"
echo "  Download complete!"
echo "========================================"
echo ""
echo "Files saved to: ${TARGET_DIR}"
echo ""
echo "After files are in place, restart chat-service to take effect."
