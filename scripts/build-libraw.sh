#!/usr/bin/env bash
# Cross-compiles LibRaw 0.22.2 for Android (arm64-v8a) into a static archive.
#
# The engine decompresses nothing itself: `spk_open` takes pixels. LibRaw is how
# those pixels are got from a .ARW/.NEF/.CR3, on the host side of the ABI, so it
# links into libspektralab.so alongside the engine rather than into it.
#
# No CMake and no make on this machine, so the object list comes from LibRaw's
# own Makefile.dist (`LIB_OBJECTS`) and the paths are resolved by basename. The
# two `*_glue` units are compiled too: they are empty unless LIBRAW_USE_RAWCORE
# or LIBRAW_USE_DNGSDK is defined, and leaving them out would only break a
# later attempt to turn either on.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

OUT="${ROOT}/build/libraw/${ABI}"
OBJ="${OUT}/obj"
mkdir -p "${OBJ}"

[ -d "${LIBRAW_SRC}" ] || { echo "LibRaw source not found at ${LIBRAW_SRC}"; exit 1; }

# -fvisibility=hidden matters: the archive is linked into the same .so as the
# engine, and LibRaw exports a pile of one-word C++ symbols that collide.
FLAGS=(--target=aarch64-linux-android${API} -O2 -fPIC -fvisibility=hidden -DNDEBUG
       -std=c++17 -w
       -DLIBRAW_NODLL -DLIBRAW_NOTHREADS
       -I"${LIBRAW_SRC}" -I"${LIBRAW_SRC}/libraw")

# Object list -> source paths, by basename.
mapfile -t SOURCES < <(python - "${LIBRAW_SRC}" <<'PY' | tr -d '\r'
import os, re, sys
root = sys.argv[1]
txt = open(os.path.join(root, 'Makefile.dist'), encoding='utf-8', errors='ignore').read()
m = re.search(r'^LIB_OBJECTS=(.*?)(?=^\w|\Z)', txt, re.S | re.M)
names = re.findall(r'object/([A-Za-z0-9_]+)\.o', m.group(1))
index = {}
for dirpath, _, files in os.walk(root):
    if os.sep + 'samples' in dirpath:
        continue
    for f in files:
        if f.endswith('.cpp'):
            # Forward slashes throughout: these strings are consumed by bash and
            # by clang, and os.walk hands back backslashes on Windows.
            index.setdefault(f[:-4], []).append(os.path.join(dirpath, f).replace(os.sep, '/'))
missing, chosen = [], []
for n in names:
    hits = index.get(n) or []
    if not hits:
        missing.append(n)
    else:
        # Prefer the shallowest path: the integration/ copies of a name that
        # also exists elsewhere are the ones guarded by an integration flag.
        chosen.append(sorted(hits, key=lambda p: p.count('/'))[0])
if missing:
    sys.exit('missing sources for: ' + ', '.join(missing))
print('\n'.join(chosen))
PY
)


echo "[libraw] ${#SOURCES[@]} translation units from ${LIBRAW_SRC}"
PIDS=()
FAILED=0
for src in "${SOURCES[@]}"; do
  obj="${OBJ}/$(basename "${src}" .cpp).o"
  if [ -f "${obj}" ] && [ "${obj}" -nt "${src}" ]; then continue; fi
  ( "${CXX}" "${FLAGS[@]}" -c "${src}" -o "${obj}" || { echo "FAILED: ${src}"; exit 1; } ) &
  PIDS+=($!)
  while [ "$(jobs -rp | wc -l)" -ge 12 ]; do sleep 0.2; done
done
for pid in "${PIDS[@]}"; do wait "${pid}" || FAILED=1; done
[ "${FAILED}" -eq 0 ] || { echo "compilation failed"; exit 1; }

echo "[archive]"
rm -f "${OUT}/libraw.a"
"${AR}" rcs "${OUT}/libraw.a" "${OBJ}"/*.o
ls -lh "${OUT}/libraw.a"
