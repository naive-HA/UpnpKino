#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# build_android.sh  [NDK_VERSION]
#
# Cross-compiles the dependency chain for Android arm64-v8a.
# Purpose: re-encode locally available video files for Chromecast / Miracast /
# AirPlay playback. 
#
# License: LGPL 2.1+  (--enable-gpl is NOT set; libx264 is NOT included)
# Video encoding is provided exclusively by the device's MediaCodec hardware
# encoder (h264_mediacodec, hevc_mediacodec) via the Android NDK AMediaCodec
# API.
#
# Dependency chain:
#   zlib 1.3.2      → compressed-track MKV support
#   FreeType 2.14.3 → font rasterisation (required by libass)
#   HarfBuzz 14.2.0 → text shaping (required by libass)
#   FriBidi 1.0.16  → Unicode BiDi algorithm (required by libass)
#   libass 0.17.4   → subtitle burn-in
#   FFmpeg n8.1     → the binary
#
# NDK version
#   Detected automatically from ~/Android/Sdk/ndk/ (latest installed).
#   Pass an explicit version as the first argument to override:
#     ./build_android.sh 29.0.13113456
#
# Host prerequisites (install once):
#   sudo apt install cmake meson ninja-build pkg-config git
# =============================================================================

API_LEVEL=21
ABI="arm64-v8a"
ARCH="aarch64"
TRIPLE="${ARCH}-linux-android"

BASE_DIR="$HOME/Downloads/ffmpeg"
BUILD_DIR="$BASE_DIR/build"
OUTPUT_DIR="$BASE_DIR/out/$ABI"

NDK_BASE="$HOME/Android/Sdk/ndk"

# =============================================================================
# NDK version — CLI argument takes priority; otherwise auto-detect by picking
# the latest version installed under ~/Android/Sdk/ndk/.
# =============================================================================

if [ -n "${1:-}" ]; then
    NDK_VERSION="$1"
    echo ">>> NDK version (from argument): $NDK_VERSION"
else
    if [ ! -d "$NDK_BASE" ]; then
        echo "ERROR: Android NDK directory not found at $NDK_BASE"
        echo "       Install the NDK via Android Studio → SDK Manager → SDK Tools → NDK"
        exit 1
    fi

    NDK_VERSION=$(ls "$NDK_BASE" | sort -V | tail -1)

    if [ -z "$NDK_VERSION" ]; then
        echo "ERROR: No NDK versions found in $NDK_BASE"
        exit 1
    fi

    NDK_COUNT=$(ls "$NDK_BASE" | wc -l)
    if [ "$NDK_COUNT" -gt 1 ]; then
        echo ">>> Installed NDK versions: $(ls "$NDK_BASE" | sort -V | tr '\n' ' ')"
        echo ">>> Using latest: $NDK_VERSION  (pass a version as \$1 to override)"
    else
        echo ">>> Auto-detected NDK: $NDK_VERSION"
    fi
fi

NDK_ROOT="$NDK_BASE/$NDK_VERSION"

if [ ! -d "$NDK_ROOT" ]; then
    echo "ERROR: NDK not found at $NDK_ROOT"
    exit 1
fi

# =============================================================================
# Clean and recreate output directories
# =============================================================================

echo ""
echo ">>> Cleaning output directories..."
for dir in "$BUILD_DIR" "$OUTPUT_DIR"; do
    if [ -d "$dir" ]; then
        echo "    Deleting $dir"
        rm -rf "$dir"
    fi
done
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"
echo "    Created $BUILD_DIR"
echo "    Created $OUTPUT_DIR"

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"

export CC CXX AR RANLIB

echo "============================================================"
echo " Target ABI : $ABI"
echo " NDK        : $NDK_VERSION"
echo " API level  : $API_LEVEL"
echo " License    : LGPL 2.1+ (no GPL dependencies)"
echo " CC         : $CC"
echo " Output     : $OUTPUT_DIR"
echo "============================================================"

# =============================================================================
# Host prerequisite checks
# =============================================================================

echo ""
echo ">>> Checking host prerequisites..."
for tool in cmake meson ninja pkg-config git; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: Required host tool '$tool' not found."
        echo "       Install with: sudo apt install cmake meson ninja-build pkg-config git"
        exit 1
    fi
done

if [ ! -f "$CC" ]; then
    echo "ERROR: Clang not found at $CC"
    echo "       Check that NDK $NDK_VERSION is fully installed."
    exit 1
fi
echo "    All prerequisites present."

# Export PKG_CONFIG variables early so every library configure / meson build
# that invokes pkg-config internally finds our cross-compiled packages.
export PKG_CONFIG_LIBDIR="$OUTPUT_DIR/lib/pkgconfig"
export PKG_CONFIG_PATH="$OUTPUT_DIR/lib/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR=""

# =============================================================================
# Meson cross-compilation file (shared by HarfBuzz, FriBidi, and libass)
# =============================================================================

MESON_CROSS_FILE="$BUILD_DIR/android-arm64.ini"

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
cpu_family = 'aarch64'
cpu        = 'aarch64'
endian     = 'little'
EOF

echo ">>> Meson cross file: $MESON_CROSS_FILE"

# =============================================================================
# PART 1 — Cross-compile zlib
# =============================================================================

ZLIB_SRC="$BUILD_DIR/zlib"

if [ ! -d "$ZLIB_SRC" ]; then
    echo ""
    echo ">>> Cloning zlib (v1.3.2)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch v1.3.2 \
        https://github.com/madler/zlib.git "$ZLIB_SRC"
fi

echo ""
echo ">>> Building zlib..."
cd "$ZLIB_SRC"

CHOST="$TRIPLE" \
CC="$CC" \
AR="$AR" \
RANLIB="$RANLIB" \
CFLAGS="--sysroot=$SYSROOT -fPIC -Os" \
./configure \
    --prefix="$OUTPUT_DIR" \
    --static \
    --64

make -s -j"$(nproc)"
make -s install

[ -f "$OUTPUT_DIR/lib/libz.a" ] || { echo "ERROR: libz.a not found."; exit 1; }
echo ">>> zlib done: $OUTPUT_DIR/lib/libz.a"

# =============================================================================
# PART 2 — Cross-compile FreeType
#
# Font rasteriser required by both HarfBuzz and libass.  Built WITHOUT
# HarfBuzz to break the circular dependency: FreeType → HarfBuzz → FreeType.
# =============================================================================

FREETYPE_SRC="$BUILD_DIR/freetype"

if [ ! -d "$FREETYPE_SRC" ]; then
    echo ""
    echo ">>> Cloning FreeType (VER-2-14-3)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch VER-2-14-3 \
        https://gitlab.freedesktop.org/freetype/freetype.git "$FREETYPE_SRC"
fi

echo ""
echo ">>> Building FreeType..."

cmake -S "$FREETYPE_SRC" -B "$FREETYPE_SRC/build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
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

[ -f "$OUTPUT_DIR/lib/libfreetype.a" ] || { echo "ERROR: libfreetype.a not found."; exit 1; }
echo ">>> FreeType done: $OUTPUT_DIR/lib/libfreetype.a"

# =============================================================================
# PART 3 — Cross-compile HarfBuzz
# =============================================================================

HARFBUZZ_SRC="$BUILD_DIR/harfbuzz"

if [ ! -d "$HARFBUZZ_SRC" ]; then
    echo ""
    echo ">>> Cloning HarfBuzz (14.2.0)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch 14.2.0 \
        https://github.com/harfbuzz/harfbuzz.git "$HARFBUZZ_SRC"
fi

echo ""
echo ">>> Building HarfBuzz..."

meson setup "$HARFBUZZ_SRC/build-android" "$HARFBUZZ_SRC" \
    --cross-file "$MESON_CROSS_FILE" \
    --prefix "$OUTPUT_DIR" \
    --default-library static \
    --buildtype minsize \
    --wrap-mode nodownload \
    -Dfreetype=enabled \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dicu=disabled \
    -Dcairo=disabled \
    -Dcoretext=disabled \
    -Dwasm=disabled \
    -Dgraphite2=disabled \
    -Dintrospection=disabled \
    -Ddocs=disabled \
    -Dtests=disabled \
    -Dbenchmark=disabled

ninja -C "$HARFBUZZ_SRC/build-android" -j"$(nproc)"
ninja -C "$HARFBUZZ_SRC/build-android" install > /dev/null

[ -f "$OUTPUT_DIR/lib/libharfbuzz.a" ] || { echo "ERROR: libharfbuzz.a not found."; exit 1; }
echo ">>> HarfBuzz done: $OUTPUT_DIR/lib/libharfbuzz.a"

# =============================================================================
# PART 4 — Cross-compile FriBidi
# =============================================================================

FRIBIDI_SRC="$BUILD_DIR/fribidi"

if [ ! -d "$FRIBIDI_SRC" ]; then
    echo ""
    echo ">>> Cloning FriBidi (v1.0.16)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch v1.0.16 \
        https://github.com/fribidi/fribidi.git "$FRIBIDI_SRC"
fi

echo ""
echo ">>> Building FriBidi..."

meson setup "$FRIBIDI_SRC/build-android" "$FRIBIDI_SRC" \
    --cross-file "$MESON_CROSS_FILE" \
    --prefix "$OUTPUT_DIR" \
    --default-library static \
    --buildtype minsize \
    --wrap-mode nodownload \
    -Ddocs=false \
    -Dtests=false

ninja -C "$FRIBIDI_SRC/build-android" -j"$(nproc)"
ninja -C "$FRIBIDI_SRC/build-android" install > /dev/null

[ -f "$OUTPUT_DIR/lib/libfribidi.a" ] || { echo "ERROR: libfribidi.a not found."; exit 1; }
echo ">>> FriBidi done: $OUTPUT_DIR/lib/libfribidi.a"

# =============================================================================
# PART 5 — Cross-compile libass
# =============================================================================

LIBASS_SRC="$BUILD_DIR/libass"

if [ ! -d "$LIBASS_SRC" ]; then
    echo ""
    echo ">>> Cloning libass (0.17.4)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch 0.17.4 \
        https://github.com/libass/libass.git "$LIBASS_SRC"
fi

echo ""
echo ">>> Building libass..."

meson setup "$LIBASS_SRC/build-android" "$LIBASS_SRC" \
    --cross-file "$MESON_CROSS_FILE" \
    --prefix "$OUTPUT_DIR" \
    --default-library static \
    --buildtype minsize \
    --wrap-mode nodownload \
    -Dfontconfig=disabled \
    -Drequire-system-font-provider=false \
    -Dtest=disabled

ninja -C "$LIBASS_SRC/build-android" -j"$(nproc)"
ninja -C "$LIBASS_SRC/build-android" install > /dev/null

[ -f "$OUTPUT_DIR/lib/libass.a" ] || { echo "ERROR: libass.a not found."; exit 1; }
echo ">>> libass done: $OUTPUT_DIR/lib/libass.a"

# =============================================================================
# Verify all pkg-config packages before handing off to FFmpeg configure
# =============================================================================

echo ""
echo ">>> Verifying pkg-config packages..."
for pkg in freetype2 harfbuzz fribidi libass; do
    if pkg-config --exists "$pkg"; then
        echo "    $pkg $(pkg-config --modversion "$pkg") OK"
    else
        echo "ERROR: pkg-config cannot find '$pkg'."
        ls "$OUTPUT_DIR/lib/pkgconfig/"
        exit 1
    fi
done

# =============================================================================
# PART 6 — Cross-compile FFmpeg
# =============================================================================

FFMPEG_SRC="$BUILD_DIR/ffmpeg"

if [ ! -d "$FFMPEG_SRC" ]; then
    echo ""
    echo ">>> Cloning FFmpeg (n8.1)..."
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch n8.1 \
        https://git.ffmpeg.org/ffmpeg.git "$FFMPEG_SRC"
fi

echo ""
echo ">>> Configuring FFmpeg..."
cd "$FFMPEG_SRC"

./configure \
    --prefix="$OUTPUT_DIR" \
    --arch="$ARCH" \
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
    --disable-avdevice \
    --disable-devices \
    --disable-network \
    --disable-bzlib \
    --disable-iconv \
    --disable-ffprobe \
    --disable-ffplay \
    --enable-zlib \
    --enable-libass \
    --enable-static \
    --disable-shared \
    --enable-ffmpeg \
    --enable-small \
    --enable-swresample \
    --enable-swscale \
    \
    `# ---- Protocols (local I/O only) ----` \
    --enable-protocol=file \
    --enable-protocol=pipe \
    \
    `# ---- Input container demuxers ----` \
    --enable-demuxer=matroska \
    --enable-demuxer=mov \
    --enable-demuxer=avi \
    --enable-demuxer=asf \
    --enable-demuxer=mpegts \
    --enable-demuxer=srt \
    --enable-demuxer=ass \
    --enable-demuxer=webvtt \
    \
    `# ---- Video decoders ----` \
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
    \
    `# ---- MediaCodec hardware decoders ----` \
    --enable-mediacodec \
    --enable-jni \
    --enable-decoder=h264_mediacodec \
    --enable-decoder=hevc_mediacodec \
    --enable-decoder=vp8_mediacodec \
    --enable-decoder=vp9_mediacodec \
    --enable-decoder=av1_mediacodec \
    --enable-decoder=mpeg4_mediacodec \
    \
    `# ---- Audio decoders ----` \
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
    \
    `# ---- Subtitle decoders ----` \
    --enable-decoder=ass \
    --enable-decoder=ssa \
    --enable-decoder=subrip \
    --enable-decoder=webvtt \
    --enable-decoder=text \
    \
    `# ---- Video encoders (hardware only via MediaCodec) ----` \
    --enable-encoder=h264_mediacodec \
    --enable-encoder=hevc_mediacodec \
    \
    `# ---- Audio encoders ----` \
    --enable-encoder=aac \
    --enable-encoder=ac3 \
    \
    `# ---- Subtitle encoders ----` \
    --enable-encoder=ass \
    --enable-encoder=subrip \
    --enable-encoder=webvtt \
    --enable-encoder=text \
    \
    `# ---- Output muxers ----` \
    --enable-muxer=matroska \
    --enable-muxer=mp4 \
    --enable-muxer=mpegts \
    \
    `# ---- Parsers ----` \
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
    \
    `# ---- Bitstream filters ----` \
    --enable-bsf=aac_adtstoasc \
    --enable-bsf=h264_mp4toannexb \
    --enable-bsf=hevc_mp4toannexb \
    \
    `# ---- Filters ----` \
    --enable-filter=scale \
    --enable-filter=aresample \
    --enable-filter=subtitles \
    --enable-filter=ass \
    \
    --extra-cflags="-Os -fPIC -I$OUTPUT_DIR/include -Wno-deprecated-declarations -Wno-unused-function" \
    --extra-ldflags="-lm -L$OUTPUT_DIR/lib" \
    --extra-libs="-Wl,-Bstatic -lz -Wl,-Bdynamic -lass -lharfbuzz -lfreetype -lfribidi -lmediandk" \
    --extra-ldexeflags="-Wl,-z,max-page-size=16384"

echo ">>> Compiling FFmpeg..."
make -j"$(nproc)"
make install > /dev/null

# =============================================================================
# Strip and verify
# =============================================================================

BINARY="$OUTPUT_DIR/bin/ffmpeg"
"$STRIP" --strip-all "$BINARY"

echo ""
echo "============================================================"
echo " Build complete"
echo "============================================================"
ls -lh "$BINARY"

echo ""
echo "Shared library dependencies:"
echo "(expected: libc, libm, libdl, libandroid, libmediandk — no libz/libass/libharfbuzz)"
"$READELF" -d "$BINARY" | grep NEEDED

echo ""
echo "16 KB page alignment (all LOAD Align values must be 0x4000):"
"$READELF" -l "$BINARY" | grep -E "^\s*LOAD"

echo ""
echo "Binary info:"
"$READELF" -h "$BINARY" | grep -E "Machine|Class|Type"
echo "(ARM64 binary — deploy to Android device to run)"

# =============================================================================
# Deploy
# =============================================================================

DEPLOY_DEST="$HOME/Downloads/libffmpeg.so"
cp "$BINARY" "$DEPLOY_DEST"

echo ""
echo "============================================================"
echo " Deployed"
echo "============================================================"
ls -lh "$DEPLOY_DEST"
echo "  $DEPLOY_DEST"
echo "============================================================"

# =============================================================================
# Capability analysis
# =============================================================================

cat << 'ANALYSIS'

============================================================
 BINARY CAPABILITIES — ON-THE-FLY TRANSCODING
============================================================

LICENSE
  LGPL 2.1+  No GPL dependencies. libx264 excluded.

RUNTIME PROFILE
  Requires Android API 21+
  Runtime deps: libc, libm, libdl, libandroid, libmediandk
  All other libs statically linked: zlib, libass, HarfBuzz,
  FreeType, FriBidi
  16 KB page-aligned (Android 15+ / Play Store compliant)

------------------------------------------------------------
 INPUT
------------------------------------------------------------

Containers
  Matroska    .mkv .webm (incl. zlib-compressed tracks)
  MPEG-4      .mp4 .mov .m4v .m4a .3gp
  AVI         .avi
  ASF         .asf .wmv .wma
  MPEG-TS     .ts .m2ts
  Subtitles   .srt .ass .ssa .vtt (standalone files)

Protocols
  file://  pipe:

------------------------------------------------------------
 VIDEO DECODERS
------------------------------------------------------------

Software (CPU)
  H.264 / AVC           H.265 / HEVC
  AV1                   VP8 / VP9
  MPEG-2 Video          MPEG-4 Part 2  (DivX/Xvid)
  MS MPEG-4 v1/v2/v3    VC-1 / WMV1 / WMV2 / WMV3
  ProRes                DNxHD
  MJPEG

Hardware via MediaCodec (device on-chip decoder)
  H.264   H.265/HEVC   VP8   VP9   AV1   MPEG-4 Part 2

------------------------------------------------------------
 AUDIO DECODERS
------------------------------------------------------------

  AAC          AAC-LATM      MP3            MP2
  Opus         Vorbis        FLAC           ALAC
  AC-3         E-AC-3        MLP/TrueHD
  DTS / DTS-HD MA  (decoded to PCM, re-encoded to AAC/AC3)
  WMA v1/v2    WMA Pro       WMA Voice
  PCM s16/s24/s32/f32 (LE+BE)
  ADPCM MS     ADPCM IMA WAV

------------------------------------------------------------
 SUBTITLE DECODERS
------------------------------------------------------------

  ASS/SSA   — full burn-in via libass + HarfBuzz + FreeType
  SubRip    — full burn-in via libass
  WebVTT    — full burn-in via libass
  Text      — basic timed text

------------------------------------------------------------
 ENCODERS
------------------------------------------------------------

Video  (hardware only — MediaCodec)
  h264_mediacodec   H.264  primary target for all cast devices
  hevc_mediacodec   H.265  Chromecast Ultra / Google TV / AirPlay

Audio
  aac   universal — Chromecast, AirPlay, Miracast
  ac3   Dolby Digital 5.1 — preserves surround

Subtitle passthrough / remux
  ASS   SubRip   WebVTT   Text

------------------------------------------------------------
 OUTPUT CONTAINERS
------------------------------------------------------------

  mp4      Chromecast / AirPlay
             -movflags +faststart          progressive HTTP
             -movflags frag_keyframe+...   on-the-fly streaming
  mpegts   Miracast / Chromecast
             inherently streamable, no upfront header needed
  mkv      general purpose intermediate

------------------------------------------------------------
 FILTERS
------------------------------------------------------------

  scale       resize video  e.g. scale=1920:1080  scale=-2:720
  aresample   resample audio to a target sample rate
  subtitles   burn subtitle stream from container into video
                -vf subtitles=input.mkv
  ass         render external .ass file onto video
                -vf ass=subs.ass

Bitstream filters
  aac_adtstoasc       h264_mp4toannexb       hevc_mp4toannexb

============================================================
ANALYSIS
