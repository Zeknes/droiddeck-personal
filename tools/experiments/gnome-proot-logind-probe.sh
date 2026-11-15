#!/bin/bash
set -euo pipefail

proot_bin=${PROOT_BIN:-proot}
config=${1:?path to DroidDeck system bus config}
out=$(realpath "${2:?output directory required}")
mkdir -p "$out"
config=$(realpath "$config")
[ "$(id -u)" -ne 0 ] || { echo 'Run as an unprivileged user' >&2; exit 1; }
run=$(mktemp -d)
trap 'rm -rf "$run"' EXIT
mkdir -p "$run/dbus" "$run/systemd"
export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
export PROOT_NO_SECCOMP=1
"$proot_bin" --kill-on-exit -r / -b "$run:/run" /usr/bin/python3 - "$config" "$out" <<'PY'
import os
import pathlib
import subprocess
import sys
import time

config, out = sys.argv[1:]
out = pathlib.Path(out)
env = os.environ.copy()
processes = []
try:
    with (out / 'bus.log').open('w') as bus_log, (out / 'logind.log').open('w') as login_log:
        bus = subprocess.Popen(['dbus-daemon', f'--config-file={config}', '--nofork', '--nopidfile'], stdout=bus_log, stderr=subprocess.STDOUT, env=env)
        processes.append(bus)
        for _ in range(100):
            if os.path.exists('/run/dbus/system_bus_socket'):
                break
            if bus.poll() is not None:
                raise RuntimeError('isolated system bus exited')
            time.sleep(.05)
        else:
            raise RuntimeError('isolated system bus did not start')
        logind = subprocess.Popen(['/usr/lib/systemd/systemd-logind'], stdout=login_log, stderr=subprocess.STDOUT, env=env)
        processes.append(logind)
        for _ in range(100):
            result = subprocess.run(['busctl', '--system', 'status', 'org.freedesktop.login1'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
            if result.returncode == 0:
                break
            if logind.poll() is not None:
                raise RuntimeError('logind exited before owning its bus name')
            time.sleep(.05)
        else:
            raise RuntimeError('logind did not own its bus name')
        (out / 'status.log').write_text(result.stdout)
        result = subprocess.run(['busctl', '--system', 'call', 'org.freedesktop.login1', '/org/freedesktop/login1', 'org.freedesktop.login1.Manager', 'GetUser', 'u', str(os.getuid())], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
        (out / 'get-user.log').write_text(result.stdout)
        print(f'GetUser exit={result.returncode}: {result.stdout.strip()}')
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
    for process in reversed(processes):
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
PY
