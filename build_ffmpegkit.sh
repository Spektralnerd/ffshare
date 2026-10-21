#!/bin/sh

OUTPUT="$(dirname "$0")/app/libs"
if [ -n "$1" ]; then
  OUTPUT="$1"
fi

FFMPEG_KIT_TAG_VERSION=v8.1.1

rm -rf "./ffmpeg-kit-next"

git clone --branch $FFMPEG_KIT_TAG_VERSION --depth=1 "https://github.com/arthenica/ffmpeg-kit-next"


# fork: libaom = software AV1 encoder; android-media-codec = hardware encoders via MediaCodec
# (av1_mediacodec, hevc_mediacodec, h264_mediacodec) - off by default in ffmpeg-kit-next
# fork: 32-bit ARM only as arm-v7a-neon (the original FFShare builds plain arm-v7a without NEON:
# libaom, NL-means etc. then run as pure C, several times slower). Every ARMv7 CPU that runs
# Android 7+ has NEON; the AAR then holds libffmpegkit_armv7a_neon.so + lib*_neon.so for
# armeabi-v7a, which ffmpeg-kit's NativeLoader loads first.

cd ffmpeg-kit-next
./nix-android.sh -p android-r27d \
  --jobs=$(nproc) \
  --disable-x86 --disable-x86-64 --disable-arm-v7a \
  --enable-android-media-codec \
  --enable-dav1d \
  --enable-libaom \
  --enable-fontconfig \
  --enable-freetype \
  --enable-fribidi \
  --enable-gmp \
  --enable-kvazaar \
  --enable-lame \
  --enable-libass \
  --enable-libiconv \
  --enable-libilbc \
  --enable-libtheora \
  --enable-libvorbis \
  --enable-libvpx \
  --enable-libwebp \
  --enable-libxml2 \
  --enable-opencore-amr \
  --enable-opus \
  --enable-shine \
  --enable-snappy \
  --enable-soxr \
  --enable-speex \
  --enable-twolame \
  --enable-vo-amrwbenc \
  --enable-vvenc \
  --enable-zimg \
  --enable-gpl \
  --enable-libvidstab \
  --enable-x264 \
  --enable-x265 \
  --enable-xvidcore \
  --enable-libjxl
cd ..

mv ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/8.1.1/ffmpeg-kit-next-8.1.1.aar "$OUTPUT"
