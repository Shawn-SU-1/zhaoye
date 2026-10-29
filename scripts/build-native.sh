#!/usr/bin/env bash
# Cross-compiles the SpektraLab C++ engine for Android (arm64-v8a) into
# app/src/main/jniLibs/arm64-v8a/libspektralab.so.
#
# The engine sources are vendored in ./engine (a subset of upstream, see
# engine/README.md); only the JNI bridge and the portable rasteriser live in
# app/src/main/cpp. Run scripts/build-libraw.sh once before this.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

OUT="${ROOT}/app/src/main/jniLibs/${ABI}"
OBJ="${ROOT}/build/native/${ABI}"
mkdir -p "${OUT}" "${OBJ}"

FLAGS=(--target=aarch64-linux-android${API} -O2 -fPIC -fvisibility=hidden -DNDEBUG
       -std=c++20 -Wall -Wno-unused-function -Wno-unused-variable
       -I"${ENGINE}/include" -I"${ENGINE}/src" -I"${ENGINE}/src/core"
       -I"${ENGINE}/src/pipeline" -I"${ENGINE}/src/gpu" -I"${ENGINE}/src/io"
       -I"${ENGINE}/third_party/volk" -I"${ENGINE}/third_party/stb"
       -I"${ROOT}/app/src/main/cpp"
       # LIBRAW_NOTHREADS must match how libraw.a was built: it changes the
       # LibRaw class layout, so a mismatch here is a silent ABI break rather
       # than a link error.
       -DLIBRAW_NOTHREADS
       -I"${LIBRAW_SRC}" -I"${LIBRAW_SRC}/libraw")

CORE=(blob cam16 colour curves digital_intermediate hanatos json latitude_fit
      numeric params print_lut printing profile setup_cache spectral)
# overscan.cpp is compiled in: its CoreGraphics/CoreText/Accelerate calls are
# branched to spk_raster.hpp and its four Metal kernels have Vulkan
# translations, so the film edge and the date back work here. contrast_mask.cpp
# still needs Accelerate and libdispatch, so windows_unported.cpp keeps the
# stubs for that one only.
PIPELINE=(blur engine overscan pipeline scene_latitude windows_unported)

SOURCES=()
for f in "${CORE[@]}";        do SOURCES+=("${ENGINE}/src/core/$f.cpp"); done
for f in "${PIPELINE[@]}";    do SOURCES+=("${ENGINE}/src/pipeline/$f.cpp"); done
SOURCES+=("${ENGINE}/src/gpu/vulkan_gpu.cpp" "${ROOT}/app/src/main/cpp/jni_bridge.cpp"
          "${ROOT}/app/src/main/cpp/spk_raster.cpp")

echo "[volk] ${ENGINE}/third_party/volk/volk.c"
"${CC}" --target=aarch64-linux-android${API} -O2 -fPIC -DNDEBUG \
    -I"${ENGINE}/third_party/volk" -c "${ENGINE}/third_party/volk/volk.c" -o "${OBJ}/volk.o"

echo "[engine] ${#SOURCES[@]} translation units"
PIDS=()
for src in "${SOURCES[@]}"; do
  obj="${OBJ}/$(basename "${src}" .cpp).o"
  "${CXX}" "${FLAGS[@]}" -c "${src}" -o "${obj}" &
  PIDS+=($!)
done
fail=0
for pid in "${PIDS[@]}"; do wait "${pid}" || fail=1; done
if [ "${fail}" -ne 0 ]; then echo "compilation failed"; exit 1; fi

[ -f "${LIBRAW_LIB}" ] || { echo "missing ${LIBRAW_LIB}; run scripts/build-libraw.sh first"; exit 1; }

echo "[link]"
"${CXX}" --target=aarch64-linux-android${API} -shared -o "${OUT}/libspektralab.so" \
    "${OBJ}"/*.o "${LIBRAW_LIB}" -static-libstdc++ -llog -landroid \
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections

"${STRIP}" --strip-unneeded "${OUT}/libspektralab.so" 2>/dev/null || true
ls -lh "${OUT}/libspektralab.so"
