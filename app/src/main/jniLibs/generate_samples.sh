#!/usr/bin/env bash
set -euo pipefail

FPS="30"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v ffmpeg &> /dev/null; then
    echo "ERROR: host 'ffmpeg' command not found."
    echo "       Install with: sudo apt update && sudo apt install ffmpeg"
    exit 1
fi

generate_for_dir() {
    local out_dir="$1"
    mkdir -p "$out_dir"
    local out_file="$out_dir/hevc_720p_short_ac3.so"

    echo "Generating 720p sample fixture at $out_file..."
    ffmpeg -y \
        -f lavfi -i "testsrc=size=1280x720:rate=$FPS" \
        -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
        -t 0.5 -shortest \
        -c:v libx265 -preset ultrafast -pix_fmt yuv420p -b:v 2M \
        -c:a ac3 -b:a 192k \
        -f matroska "$out_file" > /dev/null 2>&1
    echo "    Generated successfully."
}

# Generate for all supported ABI directories under jniLibs
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    generate_for_dir "$SCRIPT_DIR/$abi"
done

echo "============================================================"
echo " All sample fixtures generated successfully."
echo "============================================================"
