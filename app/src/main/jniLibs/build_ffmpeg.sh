#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# build_ffmpeg.sh [TARGET_ABI] [NDK_VERSION_OR_PATH]
#
# Cross-compiles the FFmpeg dependency chain for Android ABIs:
#   x86, x86_64, arm64-v8a, armeabi-v7a (or 'all' / default)
#
# Usage:
#   ./build_ffmpeg.sh x86
#   ./build_ffmpeg.sh x86_64
#   ./build_ffmpeg.sh arm64-v8a
#   ./build_ffmpeg.sh armeabi-v7a
#   ./build_ffmpeg.sh all
#
# License: LGPL 2.1+  (--enable-gpl is NOT set; libx264 is NOT included)
# Video encoding is provided exclusively by the device's MediaCodec hardware
# encoder (h264_mediacodec, hevc_mediacodec) via the Android NDK AMediaCodec
# API. ffprobe shares the same libavformat/libavcodec build as ffmpeg, so it
# inherits the same LGPL-only licensing — no separate GPL exposure from
# enabling it.
#
# Dependency chain:
#   zlib 1.3.2      → compressed-track MKV support
#   FreeType 2.14.3 → font rasterisation (required by libass)
#   HarfBuzz 14.4.0 → text shaping (required by libass)
#   FriBidi 1.0.16  → Unicode BiDi algorithm (required by libass)
#   libass 0.17.5   → subtitle burn-in (0.17.4 -> 0.17.5: fixes GHSA-pjjp-65r7-ppgm
#                     and GHSA-5gf7-wjfm-vmvm, both OOB reads in code paths that
#                     parse untrusted subtitle/Matroska data -- relevant here since
#                     libass parses whatever track a user's .mkv happens to contain)
#   FFmpeg n9.0.1   → the binary (n9.0.1 "Lei" branch: adds native VVC
#                     decoding, significant AV1 enhancements, and extensive
#                     AMF hardware memory mapping improvements)
#
# NDK version
#   Detected automatically from ~/Android/Sdk/ndk/ (latest installed).
#   Pass an explicit version as the first argument to override:
#     ./build_android.sh 29.0.13113456
#
# Host prerequisites (install once):
#   sudo apt install cmake meson ninja-build pkg-config git
# =============================================================================

NDK_BASE="$HOME/Android/Sdk/ndk"

if [ -n "${2:-}" ]; then
    if [ -d "$2" ]; then
        NDK_ROOT="$2"
    else
        NDK_ROOT="$NDK_BASE/$2"
    fi
else
    if [ ! -d "$NDK_BASE" ]; then
        echo "ERROR: Android NDK directory not found at $NDK_BASE"
        exit 1
    fi
    NDK_VERSION=$(ls "$NDK_BASE" | sort -V | tail -1)
    NDK_ROOT="$NDK_BASE/$NDK_VERSION"
fi

if [ ! -d "$NDK_ROOT" ]; then
    echo "ERROR: NDK not found at $NDK_ROOT"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
REPO_FFMPEG_DIR="$PROJECT_ROOT/ffmpeg"

build_abi() {
    local RAW_ABI="$1"

    case "$RAW_ABI" in
        arm64-v8a|arm64|aarch64)
            TARGET_ABI="arm64-v8a"
            ;;
        armeabi-v7a|armv7|armv7a)
            TARGET_ABI="armeabi-v7a"
            ;;
        x86|x86_64)
            TARGET_ABI="$RAW_ABI"
            ;;
        *)
            echo "ERROR: Unsupported ABI '$RAW_ABI'. Choose from: x86, x86_64, arm64-v8a, armeabi-v7a, all"
            exit 1
            ;;
    esac

    # Match original script BASE_DIR for arm64-v8a to ensure identical build path DWARF/symbols
    if [ "$TARGET_ABI" = "arm64-v8a" ]; then
        BASE_DIR="$HOME/Downloads/ffmpeg"
    else
        BASE_DIR="$HOME/Downloads/ffmpeg_$TARGET_ABI"
    fi

    BUILD_DIR="$BASE_DIR/build"
    OUTPUT_DIR="$BASE_DIR/out/$TARGET_ABI"
    DEST_JNI_DIR="$SCRIPT_DIR/$TARGET_ABI"

    case "$TARGET_ABI" in
        x86)
            ARCH="i686"
            TRIPLE="i686-linux-android"
            MESON_CPU_FAMILY="x86"
            MESON_CPU="i686"
            FFMPEG_ARCH="x86"
            API_LEVEL=29
            FFMPEG_EXTRA_FLAGS="--disable-asm"
            FFMPEG_EXTRA_LDFLAGS="-lm -L$OUTPUT_DIR/lib -Wl,-z,max-page-size=16384"
            ZLIB_64=""
            ;;
        x86_64)
            ARCH="x86_64"
            TRIPLE="x86_64-linux-android"
            MESON_CPU_FAMILY="x86_64"
            MESON_CPU="x86_64"
            FFMPEG_ARCH="x86_64"
            API_LEVEL=29
            FFMPEG_EXTRA_FLAGS="--disable-x86asm"
            FFMPEG_EXTRA_LDFLAGS="-lm -L$OUTPUT_DIR/lib -Wl,-z,max-page-size=16384"
            ZLIB_64="--64"
            ;;
        arm64-v8a)
            ARCH="aarch64"
            TRIPLE="aarch64-linux-android"
            MESON_CPU_FAMILY="aarch64"
            MESON_CPU="aarch64"
            FFMPEG_ARCH="aarch64"
            API_LEVEL=29
            FFMPEG_EXTRA_FLAGS=""
            FFMPEG_EXTRA_LDFLAGS="-lm -L$OUTPUT_DIR/lib"
            ZLIB_64="--64"
            ;;
        armeabi-v7a)
            ARCH="armv7a"
            TRIPLE="armv7a-linux-androideabi"
            MESON_CPU_FAMILY="arm"
            MESON_CPU="armv7-a"
            FFMPEG_ARCH="arm"
            API_LEVEL=29
            FFMPEG_EXTRA_FLAGS="--cpu=armv7-a"
            FFMPEG_EXTRA_LDFLAGS="-lm -L$OUTPUT_DIR/lib -Wl,-z,max-page-size=16384"
            ZLIB_64=""
            ;;
    esac

    echo "============================================================"
    echo " Building for Target ABI : $TARGET_ABI"
    echo " Arch                   : $ARCH"
    echo " Triple                 : $TRIPLE"
    echo " API Level              : $API_LEVEL"
    echo " Output JNI Dir         : $DEST_JNI_DIR"
    echo "============================================================"

    rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
    mkdir -p "$BUILD_DIR" "$OUTPUT_DIR" "$DEST_JNI_DIR"

    TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
    SYSROOT="$TOOLCHAIN/sysroot"

    CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
    CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
    AR="$TOOLCHAIN/bin/llvm-ar"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    STRIP="$TOOLCHAIN/bin/llvm-strip"
    READELF="$TOOLCHAIN/bin/llvm-readelf"

    export CC CXX AR RANLIB
    export PKG_CONFIG_LIBDIR="$OUTPUT_DIR/lib/pkgconfig"
    export PKG_CONFIG_PATH="$OUTPUT_DIR/lib/pkgconfig"
    export PKG_CONFIG_SYSROOT_DIR=""

    # Helper function for source acquisition
    get_source() {
        local name="$1"
        local archive="$REPO_FFMPEG_DIR/${name}.7z"
        local dest="$BUILD_DIR/$name"
        local git_url="$2"
        local git_branch="$3"

        if [ -f "$archive" ] && command -v 7z >/dev/null 2>&1; then
            echo ">>> Extracting $name from $archive..."
            mkdir -p "$dest"
            7z x -y -o"$dest" "$archive" >/dev/null
        else
            echo ">>> Cloning $name ($git_branch)..."
            git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$git_branch" "$git_url" "$dest"
        fi
    }

    # Meson cross file
    MESON_CROSS_FILE="$BUILD_DIR/android-$TARGET_ABI.ini"
    cat > "$MESON_CROSS_FILE" << EOF
[binaries]
c          = '$CC'
cpp        = '$CXX'
ar         = '$AR'
strip      = '$STRIP'
pkg-config = 'pkg-config'

[built-in options]
c_args        = ['--sysroot=$SYSROOT', '-fPIC', '-Os']
cpp_args      = ['--sysroot=$SYSROOT', '-fPIC', '-Os']
c_link_args   = ['--sysroot=$SYSROOT']
cpp_link_args = ['--sysroot=$SYSROOT']

[properties]
pkg_config_libdir = ['$OUTPUT_DIR/lib/pkgconfig']

[host_machine]
system     = 'android'
cpu_family = '$MESON_CPU_FAMILY'
cpu        = '$MESON_CPU'
endian     = 'little'
EOF

    # 1. zlib
    ZLIB_SRC="$BUILD_DIR/zlib"
    get_source "zlib" "https://github.com/madler/zlib.git" "v1.3.2"
    cd "$ZLIB_SRC"
    CHOST="$TRIPLE" CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="--sysroot=$SYSROOT -fPIC -Os" \
    ./configure --prefix="$OUTPUT_DIR" --static $ZLIB_64
    make -s -j"$(nproc)"
    make -s install

    # 2. FreeType
    FREETYPE_SRC="$BUILD_DIR/freetype"
    get_source "freetype" "https://gitlab.freedesktop.org/freetype/freetype.git" "VER-2-14-3"
    cmake -S "$FREETYPE_SRC" -B "$FREETYPE_SRC/build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$TARGET_ABI" \
        -DANDROID_PLATFORM="android-$API_LEVEL" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DFT_DISABLE_HARFBUZZ=ON \
        -DFT_DISABLE_BROTLI=ON \
        -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_ZLIB=ON \
        -DCMAKE_INSTALL_PREFIX="$OUTPUT_DIR"
    cmake --build "$FREETYPE_SRC/build" -j"$(nproc)" > /dev/null
    cmake --install "$FREETYPE_SRC/build" > /dev/null

    # 3. HarfBuzz
    HARFBUZZ_SRC="$BUILD_DIR/harfbuzz"
    get_source "harfbuzz" "https://github.com/harfbuzz/harfbuzz.git" "14.4.0"
    meson setup "$HARFBUZZ_SRC/build-android" "$HARFBUZZ_SRC" \
        --cross-file "$MESON_CROSS_FILE" --prefix "$OUTPUT_DIR" \
        --default-library static --buildtype minsize --wrap-mode nodownload \
        -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dicu=disabled \
        -Dcairo=disabled -Dcoretext=disabled -Dwasm=disabled -Dgraphite2=disabled \
        -Dintrospection=disabled -Ddocs=disabled -Dtests=disabled -Dbenchmark=disabled
    ninja -C "$HARFBUZZ_SRC/build-android" -j"$(nproc)"
    ninja -C "$HARFBUZZ_SRC/build-android" install > /dev/null

    # 4. FriBidi
    FRIBIDI_SRC="$BUILD_DIR/fribidi"
    get_source "fribidi" "https://github.com/fribidi/fribidi.git" "v1.0.16"
    meson setup "$FRIBIDI_SRC/build-android" "$FRIBIDI_SRC" \
        --cross-file "$MESON_CROSS_FILE" --prefix "$OUTPUT_DIR" \
        --default-library static --buildtype minsize --wrap-mode nodownload \
        -Ddocs=false -Dtests=false
    ninja -C "$FRIBIDI_SRC/build-android" -j"$(nproc)"
    ninja -C "$FRIBIDI_SRC/build-android" install > /dev/null

    # 5. libass
    LIBASS_SRC="$BUILD_DIR/libass"
    get_source "libass" "https://github.com/libass/libass.git" "0.17.5"

    LIBASS_ASM_OPT=""
    if [ "$TARGET_ABI" = "x86" ] || [ "$TARGET_ABI" = "x86_64" ]; then
        LIBASS_ASM_OPT="-Dasm=disabled"
    fi

    meson setup "$LIBASS_SRC/build-android" "$LIBASS_SRC" \
        --cross-file "$MESON_CROSS_FILE" --prefix "$OUTPUT_DIR" \
        --default-library static --buildtype minsize --wrap-mode nodownload \
        -Dfontconfig=disabled -Drequire-system-font-provider=false $LIBASS_ASM_OPT -Dtest=disabled
    ninja -C "$LIBASS_SRC/build-android" -j"$(nproc)"
    ninja -C "$LIBASS_SRC/build-android" install > /dev/null

    # 6. FFmpeg
    FFMPEG_SRC="$BUILD_DIR/ffmpeg"
    get_source "ffmpeg" "https://git.ffmpeg.org/ffmpeg.git" "n9.0.1"
    cd "$FFMPEG_SRC"

    EXTRA_LDEXEF_ARGS=""
    if [ "$TARGET_ABI" = "arm64-v8a" ] || [ "$TARGET_ABI" = "x86_64" ]; then
        EXTRA_LDEXEF_ARGS='--extra-ldexeflags=-Wl,-z,max-page-size=16384'
    fi

    ./configure \
        --prefix="$OUTPUT_DIR" \
        --arch="$FFMPEG_ARCH" \
        --target-os=android \
        --enable-cross-compile \
        --cross-prefix="$TOOLCHAIN/bin/llvm-" \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --sysroot="$SYSROOT" \
        --pkg-config=pkg-config \
        --pkg-config-flags="--static" \
        --disable-everything \
        --disable-doc \
        --disable-debug \
        --enable-avdevice \
        --disable-outdevs \
        --disable-network \
        --disable-bzlib \
        --disable-iconv \
        --disable-ffplay \
        --enable-ffprobe \
        --enable-zlib \
        --enable-libass \
        --enable-static \
        --disable-shared \
        --enable-ffmpeg \
        --enable-small \
        --enable-swresample \
        --enable-swscale \
        --enable-protocol=file \
        --enable-protocol=pipe \
        --enable-demuxer=matroska \
        --enable-demuxer=mov \
        --enable-demuxer=avi \
        --enable-demuxer=asf \
        --enable-demuxer=mpegts \
        --enable-demuxer=srt \
        --enable-demuxer=ass \
        --enable-demuxer=webvtt \
        --enable-demuxer=concat \
        --enable-indev=lavfi \
        --enable-decoder=h264 \
        --enable-decoder=hevc \
        --enable-decoder=mpeg2video \
        --enable-decoder=mpeg4 \
        --enable-decoder=msmpeg4v1 \
        --enable-decoder=msmpeg4v2 \
        --enable-decoder=msmpeg4v3 \
        --enable-decoder=vc1 \
        --enable-decoder=wmv1 \
        --enable-decoder=wmv2 \
        --enable-decoder=wmv3 \
        --enable-decoder=mjpeg \
        --enable-decoder=vp8 \
        --enable-decoder=vp9 \
        --enable-decoder=av1 \
        --enable-decoder=prores \
        --enable-decoder=dnxhd \
        --enable-decoder=wrapped_avframe \
        --enable-mediacodec \
        --enable-jni \
        --enable-decoder=h264_mediacodec \
        --enable-decoder=hevc_mediacodec \
        --enable-decoder=vp8_mediacodec \
        --enable-decoder=vp9_mediacodec \
        --enable-decoder=av1_mediacodec \
        --enable-decoder=mpeg4_mediacodec \
        --enable-decoder=dca \
        --enable-decoder=ac3 \
        --enable-decoder=eac3 \
        --enable-decoder=mlp \
        --enable-decoder=truehd \
        --enable-decoder=wmav1 \
        --enable-decoder=wmav2 \
        --enable-decoder=wmapro \
        --enable-decoder=wmavoice \
        --enable-decoder=aac \
        --enable-decoder=aac_latm \
        --enable-decoder=mp3 \
        --enable-decoder=mp2 \
        --enable-decoder=opus \
        --enable-decoder=vorbis \
        --enable-decoder=flac \
        --enable-decoder=alac \
        --enable-decoder=pcm_s16le \
        --enable-decoder=pcm_s16be \
        --enable-decoder=pcm_s24le \
        --enable-decoder=pcm_s24be \
        --enable-decoder=pcm_s32le \
        --enable-decoder=pcm_f32le \
        --enable-decoder=adpcm_ms \
        --enable-decoder=adpcm_ima_wav \
        --enable-decoder=ass \
        --enable-decoder=ssa \
        --enable-decoder=subrip \
        --enable-decoder=webvtt \
        --enable-decoder=text \
        --enable-encoder=h264_mediacodec \
        --enable-encoder=hevc_mediacodec \
        --enable-encoder=rawvideo \
        --enable-encoder=aac \
        --enable-encoder=ac3 \
        --enable-encoder=ass \
        --enable-encoder=subrip \
        --enable-encoder=webvtt \
        --enable-encoder=text \
        --enable-muxer=matroska \
        --enable-muxer=mp4 \
        --enable-muxer=mpegts \
        --enable-muxer=null \
        --enable-muxer=rawvideo \
        --enable-muxer=yuv4mpegpipe \
        --enable-parser=h264 \
        --enable-parser=hevc \
        --enable-parser=av1 \
        --enable-parser=mpeg4video \
        --enable-parser=mpegvideo \
        --enable-parser=vc1 \
        --enable-parser=vp8 \
        --enable-parser=vp9 \
        --enable-parser=mjpeg \
        --enable-parser=dca \
        --enable-parser=aac \
        --enable-parser=ac3 \
        --enable-parser=mpegaudio \
        --enable-bsf=aac_adtstoasc \
        --enable-bsf=h264_mp4toannexb \
        --enable-bsf=hevc_mp4toannexb \
        --enable-filter=scale \
        --enable-filter=aresample \
        --enable-filter=subtitles \
        --enable-filter=ass \
        --enable-filter=pad \
        --enable-filter=testsrc \
        --enable-filter=sine \
        --enable-filter=format \
        --enable-filter=hwupload \
        --enable-hwaccel=h264_mediacodec \
        --enable-hwaccel=hevc_mediacodec \
        $FFMPEG_EXTRA_FLAGS \
        --extra-cflags="-Os -fPIC -I$OUTPUT_DIR/include -Wno-deprecated-declarations -Wno-unused-function" \
        --extra-ldflags="$FFMPEG_EXTRA_LDFLAGS" \
        --extra-libs="-Wl,-Bstatic -lz -Wl,-Bdynamic -lass -lharfbuzz -lfreetype -lfribidi -lmediandk" \
        $EXTRA_LDEXEF_ARGS

    make -j"$(nproc)"
    make install > /dev/null

    FFMPEG_BIN="$OUTPUT_DIR/bin/ffmpeg"
    FFPROBE_BIN="$OUTPUT_DIR/bin/ffprobe"

    "$STRIP" --strip-all "$FFMPEG_BIN"
    "$STRIP" --strip-all "$FFPROBE_BIN"

    echo ""
    echo "Shared library dependencies for ffmpeg ($TARGET_ABI):"
    "$READELF" -d "$FFMPEG_BIN" | grep NEEDED

    echo ""
    echo "Segment alignment for ffmpeg ($TARGET_ABI):"
    "$READELF" -l "$FFMPEG_BIN" | grep -E "^\s*LOAD"
    echo ""

    cp "$FFMPEG_BIN" "$DEST_JNI_DIR/libffmpeg.so"
    cp "$FFPROBE_BIN" "$DEST_JNI_DIR/libffprobe.so"

    # Compress each folder in build folder to 7z with maximum compression (-mx=9)
    # and move into ~/Downloads/ffmpeg/$TARGET_ABI/
    ARCHIVE_DEST="$HOME/Downloads/ffmpeg/$TARGET_ABI"
    mkdir -p "$ARCHIVE_DEST"

    echo ">>> Packaging build folders into 7z archives for $TARGET_ABI..."
    for dir in "$BUILD_DIR"/*/; do
        if [ -d "$dir" ]; then
            dirname=$(basename "$dir")
            archive_path="$ARCHIVE_DEST/${dirname}.7z"
            echo "    Component: $dirname -> $archive_path"
            rm -f "$archive_path"
            7z a -t7z -mx=9 "$archive_path" "$dir" > /dev/null
        fi
    done
    echo ">>> Archives successfully created at $ARCHIVE_DEST"

    echo ">>> Successfully generated libffmpeg.so and libffprobe.so for $TARGET_ABI at $DEST_JNI_DIR"
}

TARGET_INPUT="${1:-all}"

if [ "$TARGET_INPUT" = "all" ]; then
    echo "============================================================"
    echo " Building for ALL ABIs: arm64-v8a, armeabi-v7a, x86, x86_64"
    echo "============================================================"
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        build_abi "$abi"
    done
    echo "============================================================"
    echo " All platforms built successfully!"
    echo "============================================================"
else
    build_abi "$TARGET_INPUT"
fi
