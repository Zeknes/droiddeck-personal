#!/bin/bash
set -euo pipefail

for cmd in weston weston-simple-shm weston-screenshooter gnome-shell pipewire wireplumber dbus-run-session wayland-info; do
  command -v "$cmd" >/dev/null || { echo "Missing: $cmd" >&2; exit 1; }
done
[ -x /usr/libexec/mutter-devkit ] || { echo 'Missing mutter-dev-bin' >&2; exit 1; }
[ -S /run/dbus/system_bus_socket ] || { echo 'A working isolated system bus and logind are required for this experiment' >&2; exit 1; }

out=$(realpath "${1:?output directory required}")
mkdir -p "$out"
runtime=$(mktemp -d)
chmod 700 "$runtime"
export XDG_RUNTIME_DIR=$runtime XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=GNOME
export LIBGL_ALWAYS_SOFTWARE=1 WAYLAND_DISPLAY=outer
ulimit -c 0
cleanup() {
  [ -z "${weston_pid:-}" ] || kill "$weston_pid" 2>/dev/null || true
  [ -z "${weston_pid:-}" ] || wait "$weston_pid" 2>/dev/null || true
  rm -rf "$runtime"
}
trap cleanup EXIT
weston --debug --backend=headless-backend.so --renderer=pixman --socket=outer --idle-time=0 --width=1280 --height=800 --log="$out/weston.log" &
weston_pid=$!
for i in {1..100}; do [ -S "$runtime/outer" ] && break; kill -0 "$weston_pid"; sleep .1; done
[ -S "$runtime/outer" ] || { echo 'Weston failed to start' >&2; exit 1; }
export out
dbus-run-session -- bash -euo pipefail -c '
  cleanup() {
    kill "${client_pid:-}" "${shell_pid:-}" "${wire_pid:-}" "${pipe_pid:-}" 2>/dev/null || true
    wait 2>/dev/null || true
  }
  trap cleanup EXIT
  pipewire >"$out/pipewire.log" 2>&1 & pipe_pid=$!
  for i in {1..100}; do [ -S "$XDG_RUNTIME_DIR/pipewire-0" ] && break; kill -0 "$pipe_pid"; sleep .1; done
  [ -S "$XDG_RUNTIME_DIR/pipewire-0" ]
  wireplumber >"$out/wireplumber.log" 2>&1 & wire_pid=$!
  WAYLAND_DEBUG=1 gnome-shell --devkit --no-x11 --wayland-display=inner >"$out/shell.log" 2>&1 & shell_pid=$!
  for i in {1..100}; do [ -S "$XDG_RUNTIME_DIR/inner" ] && break; kill -0 "$shell_pid"; sleep .1; done
  [ -S "$XDG_RUNTIME_DIR/inner" ]
  sleep 3
  WAYLAND_DISPLAY=inner WAYLAND_DEBUG=1 weston-simple-shm >"$out/client.log" 2>&1 & client_pid=$!
  sleep 5
  kill -0 "$shell_pid"; kill -0 "$client_pid"
  WAYLAND_DISPLAY=inner timeout 5 wayland-info >"$out/inner.log" 2>&1
  cd "$out"
  WAYLAND_DISPLAY=outer weston-screenshooter >"$out/screenshot.log" 2>&1
  grep -q "wl_surface.*attach" "$out/client.log"
  grep -q "wl_surface.*attach" "$out/shell.log"
  compgen -G "$out/wayland-screenshot-*.png" >/dev/null
  printf "Protocol smoke passed; inspect the screenshot in %s\n" "$out"
'
