#!/usr/bin/env bash
# Shared path and toolchain resolution for the build scripts. Source it, do not
# run it:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
#
# It exports:
#   ROOT        repo root
#   ENGINE      vendored engine sources (<repo>/engine)
#   NDK         Android NDK r28c or newer
#   BIN         NDK prebuilt binaries for this host
#   EXE         "" or ".exe"
#   LIBRAW_SRC  LibRaw 0.22.2 sources (not vendored)
#   LIBRAW_LIB  the static archive built by scripts/build-libraw.sh
#   API / ABI   android-26, arm64-v8a
#
# Overrides: ANDROID_NDK_ROOT, LIBRAW_SRC.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE="${ROOT}/engine"
API=26
ABI=arm64-v8a
LIBRAW_LIB="${ROOT}/build/libraw/${ABI}/libraw.a"

find_first_dir() {
  for cand in "$@"; do
    if [ -n "${cand}" ] && [ -d "${cand}" ]; then printf '%s\n' "${cand}"; return 0; fi
  done
  return 1
}

# ../deps is the layout this project was developed in; third_party/ is the
# convention for a clean checkout.
NDK="$(find_first_dir "${ANDROID_NDK_ROOT-}" "${ROOT}/../deps/android-ndk-r28c" \
                      "${ROOT}/third_party/android-ndk-r28c")" \
  || { echo "NDK not found: set ANDROID_NDK_ROOT (r28c or newer)" >&2; exit 1; }

LIBRAW_SRC="$(find_first_dir "${LIBRAW_SRC-}" "${ROOT}/../deps/LibRaw-0.22.2" \
                              "${ROOT}/third_party/LibRaw-0.22.2")" \
  || { echo "LibRaw source not found: set LIBRAW_SRC to a LibRaw-0.22.2 checkout" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  MINGW* | MSYS* | CYGWIN*) HOST_TAG=windows-x86_64 ;;
  *) HOST_TAG=linux-x86_64 ;;
esac
BIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"

EXE=""
[ -f "${BIN}/clang++.exe" ] && EXE=".exe"
CXX="${BIN}/clang++${EXE}"
CC="${BIN}/clang${EXE}"
AR="${BIN}/llvm-ar${EXE}"
STRIP="${BIN}/llvm-strip${EXE}"

# clang on Windows is a native binary: it wants drive-letter paths, not the
# /c/... form MSYS hands out. Everywhere else the plain path is already right.
winpath() {
  case "${HOST_TAG}" in
    windows-*) (cd "$1" && pwd -W) ;;
    *) printf '%s\n' "$1" ;;
  esac
}
