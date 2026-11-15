#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
src=$root/app/src/main/cpp/waylandcomp
runtime=$(mktemp -d)
server=
cleanup() {
  [ -z "$server" ] || kill "$server" 2>/dev/null || true
  [ -z "$server" ] || wait "$server" 2>/dev/null || true
  rm -rf "$runtime"
}
trap cleanup EXIT
export XDG_RUNTIME_DIR=$runtime
cc -std=c11 -Wall -Wno-unused-parameter -I"$src/src" -I"$src/generated" \
  "$here/gnome-outer-text-probe.c" "$src/src/wl_text_input.c" \
  "$src/generated/text-input-unstable-v3-protocol.c" \
  -o "$runtime/server" $(pkg-config --cflags --libs wayland-server)
"$runtime/server" >"$runtime/server.log" 2>&1 & server=$!
for i in {1..100}; do [ -S "$runtime/outer-probe" ] && break; kill -0 "$server"; sleep .05; done
[ -S "$runtime/outer-probe" ]
python3 "$here/gnome-outer-text-probe.py"
wait "$server"
server=
cat "$runtime/server.log"
