#!/usr/bin/env bash
# Builds the device-side smoke test: same engine objects, no JNI, plain exe.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

OBJ="${ROOT}/build/native/${ABI}"
OUT="${ROOT}/build/probe"
mkdir -p "${OUT}"
# clang here is a Windows binary: hand it drive-letter paths, not MSYS ones.
OBJ_WIN="$(winpath "${OBJ}")"
OUT_WIN="$(winpath "${OUT}")"
SRC_WIN="$(winpath "${ROOT}/app/src/main/cpp")"
INC_ENGINE="$(winpath "${ENGINE}")"

"${BIN}/clang++${EXE}" --target=aarch64-linux-android${API} -O2 -DNDEBUG -std=c++20 \
    -I"${INC_ENGINE}/include" -I"${INC_ENGINE}/src" -I"${INC_ENGINE}/src/core" \
    -I"${INC_ENGINE}/src/pipeline" -I"${INC_ENGINE}/src/gpu" -I"${INC_ENGINE}/src/io" \
    -I"${INC_ENGINE}/third_party/volk" \
    "${SRC_WIN}/spk_probe.cpp" \
    "${OBJ_WIN}/blob.o" "${OBJ_WIN}/cam16.o" "${OBJ_WIN}/colour.o" "${OBJ_WIN}/curves.o" \
    "${OBJ_WIN}/digital_intermediate.o" "${OBJ_WIN}/hanatos.o" "${OBJ_WIN}/json.o" \
    "${OBJ_WIN}/latitude_fit.o" "${OBJ_WIN}/numeric.o" "${OBJ_WIN}/params.o" "${OBJ_WIN}/print_lut.o" \
    "${OBJ_WIN}/printing.o" "${OBJ_WIN}/profile.o" "${OBJ_WIN}/setup_cache.o" "${OBJ_WIN}/spectral.o" \
    "${OBJ_WIN}/blur.o" "${OBJ_WIN}/engine.o" "${OBJ_WIN}/pipeline.o" "${OBJ_WIN}/scene_latitude.o" \
    "${OBJ_WIN}/windows_unported.o" "${OBJ_WIN}/vulkan_gpu.o" "${OBJ_WIN}/volk.o" \
    -static-libstdc++ -llog -landroid -o "${OUT_WIN}/spk_probe"
"${STRIP}" --strip-unneeded "${OUT}/spk_probe" 2>/dev/null || true
ls -lh "${OUT}/spk_probe"
