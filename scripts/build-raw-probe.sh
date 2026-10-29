#!/usr/bin/env bash
# Cross-compiles the RAW decode probe for arm64-v8a.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
OUT="${ROOT}/build/probe"
mkdir -p "${OUT}"
"${BIN}/clang++${EXE}" --target=aarch64-linux-android${API} -O2 -fPIE -pie -std=c++17 \
    -I"${LIBRAW_SRC}" -I"${LIBRAW_SRC}/libraw" \
    "${ROOT}/app/src/main/cpp/spk_raw_probe.cpp" \
    "${ROOT}/build/libraw/arm64-v8a/libraw.a" \
    -static-libstdc++ -o "${OUT}/spk_raw_probe"
"${STRIP}" --strip-unneeded "${OUT}/spk_raw_probe" 2>/dev/null || true
ls -lh "${OUT}/spk_raw_probe"
