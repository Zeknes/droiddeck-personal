#!/usr/bin/env bash
set -euo pipefail
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=${1:?supply an output directory}
mkdir -p "$out"
protocols=$(pkg-config --variable=pkgdatadir wayland-protocols)
for spec in 'xdg:stable/xdg-shell/xdg-shell.xml' 'viewporter:stable/viewporter/viewporter.xml' 'presentation:stable/presentation-time/presentation-time.xml' 'pixel:staging/single-pixel-buffer/single-pixel-buffer-v1.xml'; do
  name=${spec%%:*}; xml="$protocols/${spec#*:}"
  wayland-scanner client-header "$xml" "$out/$name.h"
  wayland-scanner private-code "$xml" "$out/$name.c"
done
cc -I"$out" "$here/repro.c" "$out/xdg.c" "$out/viewporter.c" "$out/presentation.c" "$out/pixel.c" $(pkg-config --cflags --libs wayland-client) -o "$out/subsurface-repro"
