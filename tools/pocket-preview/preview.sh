#!/bin/bash
# The pocket room's whole frame, without a phone: tools/pocket-preview/README.md.
#
#   tools/pocket-preview/preview.sh <out-dir> [travelled metres...]
#
# Renders the device once (slow: Cycles) and the window at each distance along the line (fast),
# and grades each pair into <out-dir>/frame_<metres>.png. Run from the repository root.
set -e
OUT=$1; shift
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${BPY_PYTHON:-python3}
mkdir -p "$OUT"
[ -f "$OUT/device.npy" ] || "$PY" "$HERE/frame_device.py" "$OUT/device.npy" 720 64 ${LCD_PNG:-}
for M in "${@:-400 1500 3100}"; do
  node "$HERE/render_window.js" "$OUT/window_$M.f32" "$M"
  python3 "$HERE/grade.py" "$OUT/window_$M.f32" "$OUT/device.npy" "$OUT/frame_$M.png"
done
