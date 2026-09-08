#!/bin/bash

FPS="30"
OUT_DIR="."

if ! command -v ffmpeg &> /dev/null; then
    echo "ffmpeg could not be found. Please install it: sudo apt update && sudo apt install ffmpeg libx265-dev"
    exit 1
fi

generate() {
    local out_file="$OUT_DIR/$1"
    local dur="$2"
    local audio_mode="$3"
    local size="$4"

    echo "Generating $1 (${size}, ${dur}s, video $([ "$audio_mode" = "ac3" ] && echo "+ AC3 audio" || echo "only"))..."

    if [ "$audio_mode" = "ac3" ]; then
        ffmpeg -y \
            -f lavfi -i "testsrc=size=$size:rate=$FPS" \
            -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
            -t "$dur" -shortest \
            -c:v libx265 -preset ultrafast -pix_fmt yuv420p -b:v 2M \
            -c:a ac3 -b:a 192k \
            -f matroska "$out_file"
    else
        ffmpeg -y \
            -f lavfi -i "testsrc=size=$size:rate=$FPS" \
            -t "$dur" \
            -an \
            -c:v libx265 -preset ultrafast -pix_fmt yuv420p -b:v 2M \
            -f matroska "$out_file"
    fi
}

generate "hevc_720p_short_ac3.so"     0.5 "ac3"     "1280x720"

echo "------------------------------------------------"
echo "Samples generated:"
ls -lh "$OUT_DIR"/hevc_*.so
