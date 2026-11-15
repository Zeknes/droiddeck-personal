#!/bin/bash
# Runs the bridge inside a real GNOME 50 session, as an unprivileged user, with
# no system bus or logind. The outer side is simulated over the bridge socket.
# Every process this run starts is reaped through its captured PID or the
# session's own process group; nothing is killed by name.
set -euo pipefail

here=$(cd "${0%/*}" && pwd)
out=${1:?evidence directory required}
python3 -c 'import os,sys; os.makedirs(sys.argv[1], exist_ok=True)' "$out"
out=$(cd "$out" && pwd)
runtime=${XDG_RUNTIME_DIR:-}
[ -n "$runtime" ] || { echo 'XDG_RUNTIME_DIR is required' >&2; exit 1; }
[ "$(id -u)" -ne 0 ] || { echo 'Run as an unprivileged user' >&2; exit 1; }
[ ! -S /run/dbus/system_bus_socket ] || { echo 'A host system bus must not be used' >&2; exit 1; }

python3 -c 'import os,sys; os.makedirs(sys.argv[1], exist_ok=True)' "$runtime"
python3 -c 'import os,sys; os.chmod(sys.argv[1], 0o700)' "$runtime"
export out here
dpkg -s libmutter-18-0 2>/dev/null | grep -E '^(Package|Version):' >"$out/mutter-version.log" || true
export IBUS_ADDRESS="unix:path=$runtime/ibus.sock"
export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$runtime/system-bus.sock"
dbus-daemon --session --address="$DBUS_SYSTEM_BUS_ADDRESS" --nofork --nopidfile >"$out/system-bus.log" 2>&1 &
system_bus_pid=$!
for _ in {1..100}; do [ -S "$runtime/system-bus.sock" ] && break; sleep .05; done
[ -S "$runtime/system-bus.sock" ] || { echo 'Private system bus failed' >&2; exit 1; }
export LIBGL_ALWAYS_SOFTWARE=1 XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=GNOME
export DROIDDECK_IME_SOCKET="$runtime/droiddeck-ime.sock"
export DROIDDECK_APP_SOCKET="$runtime/droiddeck-app.sock"
export DROIDDECK_APP_LOG="$out/app.log"
export DROIDDECK_IME_LOG="$out/bridge.log"
ulimit -c 0

weston --backend=headless-backend.so --renderer=pixman --socket=outer --idle-time=0 \
    --width=1280 --height=800 --log="$out/weston.log" &
weston_pid=$!
for _ in {1..100}; do [ -S "$runtime/outer" ] && break; kill -0 "$weston_pid"; sleep .1; done
[ -S "$runtime/outer" ] || { echo 'Weston failed to start' >&2; exit 1; }
export WAYLAND_DISPLAY=outer

tree=()
collect_tree() {
    local pid=$1 child
    for child in $(pgrep -P "$pid" 2>/dev/null); do collect_tree "$child"; done
    tree+=("$pid")
}
cleanup() {
    kill "${weston_pid:-}" "${system_bus_pid:-}" 2>/dev/null || true
    if [ -n "${session_pid:-}" ]; then
        tree=()
        collect_tree "$session_pid"
        for pid in "${tree[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
        for _ in {1..50}; do kill -0 "$session_pid" 2>/dev/null || break; sleep .1; done
        for pid in "${tree[@]}"; do kill -KILL "$pid" 2>/dev/null || true; done
        wait "$session_pid" 2>/dev/null || true
    fi
    wait "${weston_pid:-}" "${system_bus_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT

dbus-run-session -- bash -euo pipefail -c '
    cleanup() {
        kill "${app_pid:-}" "${shell_pid:-}" "${engine_pid:-}" \
             "${ibus_pid:-}" "${wire_pid:-}" "${pipe_pid:-}" 2>/dev/null || true
        wait 2>/dev/null || true
    }
    trap cleanup EXIT

    pipewire >"$out/pipewire.log" 2>&1 & pipe_pid=$!
    for _ in {1..100}; do [ -S "$XDG_RUNTIME_DIR/pipewire-0" ] && break; sleep .1; done
    [ -S "$XDG_RUNTIME_DIR/pipewire-0" ] || { echo "PipeWire failed" >&2; exit 1; }
    wireplumber >"$out/wireplumber.log" 2>&1 & wire_pid=$!

    WAYLAND_DISPLAY=inner ibus-daemon --single --emoji-extension=disable \
        --address="$IBUS_ADDRESS" >"$out/ibus.log" 2>&1 &
    ibus_pid=$!
    sleep 1

    WAYLAND_DISPLAY=inner DROIDDECK_IME_SOCKET="$DROIDDECK_IME_SOCKET" \
        DROIDDECK_IME_LOG="$DROIDDECK_IME_LOG" \
        python3 "$here/../input-bridge/droiddeck-ime-bridge" >"$out/bridge.stdout" 2>&1 &
    engine_pid=$!
    ext="$HOME/.local/share/gnome-shell/extensions/droiddeck-input@zeknes.com"
    mkdir -p "$ext"
    cp "$here/../input-bridge/extension/extension.js" "$here/../input-bridge/extension/metadata.json" "$ext/"
    gsettings set org.gnome.shell disable-extension-version-validation true
    gsettings set org.gnome.shell enabled-extensions "['\''droiddeck-input@zeknes.com'\'']"

    WAYLAND_DISPLAY=outer WAYLAND_DEBUG=1 gnome-shell --devkit --no-x11 --wayland-display=inner \
        --automation-script="$here/activate.js" >"$out/shell.log" 2>&1 &
    shell_pid=$!
    for _ in {1..150}; do [ -S "$XDG_RUNTIME_DIR/inner" ] && break; kill -0 "$shell_pid"; sleep .1; done
    [ -S "$XDG_RUNTIME_DIR/inner" ] || { echo "GNOME Shell failed" >&2; tail -50 "$out/shell.log" >&2; exit 1; }
    sleep 4

    for _ in {1..100}; do
        gdbus call --session --dest com.zeknes.DroidDeck.Input --object-path /com/zeknes/DroidDeck/Input \
            --method com.zeknes.DroidDeck.Input.GetFocusState >"$out/focus-state.log" 2>&1 && break
        sleep .1
    done
    grep -q "(" "$out/focus-state.log" || {
        echo "The Shell focus-state extension is missing" >&2; cat "$out/focus-state.log" >&2; exit 1; }

    WAYLAND_DISPLAY=inner WAYLAND_DEBUG=1 DROIDDECK_APP_SOCKET="$DROIDDECK_APP_SOCKET" \
        python3 "$here/gtk-input-app.py" >"$out/app.log" 2>&1 &
    app_pid=$!
    for _ in {1..100}; do grep -q APP_READY "$out/app.log" 2>/dev/null && break; sleep .1; done
    grep -q APP_READY "$out/app.log" || { echo "GTK app failed" >&2; exit 1; }

    for _ in {1..150}; do grep -q TEST_ACTIVATED "$out/shell.log" 2>/dev/null && break; sleep .1; done
    grep -q TEST_ACTIVATED "$out/shell.log" || { echo "The GTK window was never activated" >&2; exit 1; }
    sleep 1
    gdbus call --session --dest com.zeknes.DroidDeck.Input --object-path /com/zeknes/DroidDeck/Input \
        --method com.zeknes.DroidDeck.Input.GetFocusState >"$out/focus-window.log" 2>&1
    grep -q "true" "$out/focus-window.log" || { echo "The Shell reports no focused text field" >&2; cat "$out/focus-window.log" >&2; exit 1; }

    DROIDDECK_IME_SOCKET="$DROIDDECK_IME_SOCKET" DROIDDECK_APP_LOG="$DROIDDECK_APP_LOG" \
        DROIDDECK_APP_SOCKET="$DROIDDECK_APP_SOCKET" \
        python3 "$here/outer-sim.py" | tee "$out/outer-sim.log"
    echo INTEGRATION_PASS
' &
session_pid=$!
wait "$session_pid"
rc=$?
exit "$rc"
