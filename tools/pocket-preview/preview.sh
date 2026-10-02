#!/bin/bash
# The pocket room's whole frame, without a phone: tools/pocket-preview/README.md.
#
#   tools/pocket-preview/preview.sh <out-dir> [width samples]
#
# Renders the device on its desk from the room's camera (Cycles) and grades it as Filament
# does, into <out-dir>/frame.png. Run from the repository root.
set -e
OUT=$1
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${BPY_PYTHON:-python3}
mkdir -p "$OUT"
"$PY" "$HERE/frame.py" "$OUT/frame.npy" "${2:-720}" "${3:-64}" ${LCD_PNG:-}
python3 "$HERE/grade.py" "$OUT/frame.npy" "$OUT/frame.png"
