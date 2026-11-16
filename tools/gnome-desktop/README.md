# DroidDeck GNOME input bridge

The GNOME edition runs the desktop nested in DroidDeck's compositor. Android's
soft keyboard commits text in the outer session, while the focused GTK app
lives inside GNOME. This module carries that text to the focused field and
keeps password/PIN fields out of it.

## Why the Shell, not an IBus engine

An IBus engine was the first candidate, and it works for the first focus. On a
field switch, however, GNOME 50 does not deliver a second engine `FocusIn`
(`focus_out` is delivered, then only the Shell's input method is re-entered).
The engine therefore cannot know that the focus moved and stays closed. The
Shell's own `Clutter.InputMethod` is already the owner of that focus: its
`commit()` and `set_preedit_text()` reach the focused GTK entry through
`zwp_text_input_v3`, and its content purpose/hints are updated by Mutter after
the client's `set_content_type` commit. A small extension exposes that input
method, so no Mutter change and no IBus engine is needed.

## Files

- `input-bridge/droiddeck-ime-bridge`: socket peer for the outer compositor and
  D-Bus client of the extension. It tracks the outer focus and reports the
  state the outer side uses before sending text.
- `input-bridge/droiddeck_ime_state.py`: the focus gate. Pure Python, no GI
  imports, unit tested on the development host.
- `input-bridge/extension/`: GNOME Shell extension
  `droiddeck-input@zeknes.com`. It owns `com.zeknes.DroidDeck.Input` and
  provides:
  - `GetFocusState() -> (epoch, purpose, hints, focused)`: live field state
    after Mutter committed it.
  - `Commit(text) -> bool`: commits into the current focus, refusing password,
    PIN, sensitive-data and hidden-text fields at the commit itself.
  - `Preedit(text, cursor) -> bool`: same checks, then sets preedit.
  - `ClearPreedit()`: clears the preedit on blur/disconnect.
- `droiddeck-gnome-extension`: enable/disable/status for the extension for the
  current user, preserving the other enabled extensions.
- `tests/`: the real GNOME 50 integration test, its GTK app, outer simulator
  and Shell automation script.
- `package/`: the `droiddeck-gnome-desktop` deb, its install verification and
  the package control template.

## Wire protocol

The outer compositor connects to `$XDG_RUNTIME_DIR/droiddeck-ime.sock` (or the
inherited `DROIDDECK_IME_FD`) and exchanges newline-delimited JSON.

Bridge to outer:

```json
{"type":"state","generation":7,"connected":true,"inner":true,"outer":false,"sensitive":false,"shell":true,"ready":false}
```

Outer to bridge, with the generation from the latest state:

```json
{"type":"focus","generation":7}
{"type":"blur","generation":7}
{"type":"preedit","generation":7,"text":"中文","cursor":2}
{"type":"commit","generation":7,"text":"中文"}
```

`generation` changes whenever focus, sensitivity, connection or Shell
confirmation changes. Text with an older generation is dropped, as is text
while the inner field is sensitive or the Shell cannot confirm the focus.

## Focus and sensitivity

A commit is delivered only when all of these hold:

1. The outer compositor attached (socket alive) and focused the current
   generation.
2. The Shell reports a focused text field for the same focus epoch.
3. The field's purpose is not `PASSWORD` and neither the sensitive-data nor
   hidden-text hints are set.

The bridge checks 2 and 3 before every message, and the extension checks them
again inside `Commit`/`Preedit`. A stale bridge state therefore cannot deliver
text into a sensitive field.

## Running the tests

Host unit tests:

```sh
python3 -m unittest tools/tests/test_gnome_ime_bridge.py
```

Real session (Ubuntu 26.04 arm64 image, GNOME Shell 50.1). The base image is
the project's `droiddeck-ubuntu-base:26.04.1` rootfs:

```sh
docker build -t gnome-work-20261116-base tools/gnome-desktop/tests
docker run --rm -e XDG_RUNTIME_DIR=/tmp/runtime \
  -v "$PWD:/repo:ro" -v "$PWD/evidence:/out" gnome-work-20261116-base \
  bash /repo/tools/gnome-desktop/tests/run-integration.sh /out
```

The run starts Weston as the outer display, a private session bus, a private
`DBUS_SYSTEM_BUS_ADDRESS` bus (GNOME Shell 50 creates the login1 proxy at
startup even when no logind service exists), PipeWire, IBus, the bridge and
GNOME Shell in devkit mode. It runs as the image's `tester` user and refuses to
run as root or with a host system bus socket. The IBus daemon is part of
GNOME's input-method infrastructure; the bridge does not register an engine.

## App session wiring

The GNOME build sets `DROIDDECK_DESKTOP=gnome` in the guest environment.
`droiddeck-session` then runs `/usr/local/bin/droiddeck-gnome-desktop` instead
of the Plasma script. In desktop mode `SessionActivity` opens
`files/.wayland-rt/droiddeck-ime.sock` (the host side of the guest's
`/run/droiddeck`), passes the generation/ready state to `GnomeImeProtocol`, and
drives the Android keyboard:

- `setComposingText` -> bridge preedit;
- `commitText` with composing or non-ASCII text -> bridge commit;
- plain ASCII keeps the existing evdev key path;
- `desktopTextField` (the keyboard button) appears only for a confirmed,
  non-sensitive field. Password/PIN fields stay out of the bridge, so they need
  a physical keyboard.

The bridge and the extension both keep the generation check; the extension
rechecks purpose and hints inside `Commit`/`Preedit`, so a stale app state
cannot deliver text into a sensitive field.

## Package

`package/build-deb.sh OUT [VERSION]` builds
`droiddeck-gnome-desktop_<version>_all.deb` with the launcher, the extension
toggle and the input bridge. It depends on the Ubuntu GNOME packages and is
installed with apt in the runtime:

```sh
tools/gnome-desktop/package/build-deb.sh /tmp/gnome-deb
sudo apt-get install /tmp/gnome-deb/droiddeck-gnome-desktop_*_all.deb
droiddeck-gnome-desktop --check
droiddeck-gnome-extension enable|disable|status
```

`package/verify-deb.sh DEB [IMAGE] [EVIDENCE]` installs the deb in the
unprivileged test image, runs `--check`, exercises the extension toggle and
removes the package again.

`package/build-runtime-pack.sh RUNTIME_TAR DEB OUT TAG URL` builds the desktop
pack the app installs: it extracts a DroidDeck Ubuntu runtime, installs the
GNOME packages and the deb, writes `desktop-pack.json`, and packs only the
changed/added files with `tools/ubuntu/desktop-pack.py`. The app reads
`asset://gnome-r1-gnome-desktop.tar.zst`, copies the asset into its normal
download slot, verifies the pinned sha256 and extracts it through the same
idempotent desktop install/remove path. Stage the pack into an APK with:

```sh
DROIDDECK_GNOME_PACK=/path/to/gnome-r1-gnome-desktop.tar.zst \
DROIDDECK_GNOME_TAG=gnome-r1 \
DROIDDECK_GNOME_URL=asset://gnome-r1-gnome-desktop.tar.zst \
DROIDDECK_GNOME_SHA256=<pack sha256> \
DROIDDECK_GNOME_SIZE=<pack size> \
./gradlew :app:assembleDebug
```

The pack is not committed; `stageGnomePack` copies it into the APK build
directory. Without `DROIDDECK_GNOME_PACK` the regular KDE catalog is used and
the existing tests keep their behavior.

A test release APK built from this tree carries:

- `assets/gnome-r2-gnome-desktop.tar.zst` (sha256
  `5cc0233df319455a927127350bebc11adb73a164125519270b5e256b6086761b`,
  244622641 bytes) from the `gnome-r2` build, whose launcher starts a session
  bus before GNOME Shell;
- the `proot` and `directaudio` addon packs from the Components releases,
  staged into `assets/packs`;
- `assets/linuxfs/usr/local/bin/droiddeck-session` with the
  `DROIDDECK_DESKTOP=gnome` branch.

Its GNOME catalog entry ignores the runtime's pre-existing KDE
`desktop-pack.json` and always installs the embedded GNOME pack. The test APK
is built with the standard `tools/build_local.sh` runtime-input preparation,
`assembleRelease`, `check_session_assets.py`, `runtime_inputs.py apk`,
`check_android_libraries.py` and the audio-bundle check; it is signed
v1+v2+v3 with the project's personal key.

## Launch stages the app reports

The pack's launcher writes every step it reaches to
`$BL_DEBUG_DIR/gnome-stage.jsonl` as `{"stage":"...","exit":"..."}` (older
packs also print `== GNOME STAGE <name> exit=<value>` on `session.log`). The
app cannot see the guest any other way, so `GnomeLaunchReport` reads that file
from the session folder and `SessionService` records each change in
`events.jsonl`:

- `gnome.launch.env` before the guest runs: proot or native launch, the Linux
  driver and Vulkan ICD, the pack tag, any file the desktop pack should have
  installed but did not, and the stale guest sockets the app had to remove;
- `gnome.launch.stage` per stage change, `gnome.launch.shell` once the shell's
  own `inner` display socket exists, and `gnome.launch.fault` for the lines the
  shell writes when a subsystem is missing: `renderer-surfaceless`
  ("Created surfaceless renderer without GPU"), `pipewire-context`
  ("Failed to connect pipewire context"), `shell-shutdown`, `ibus-refused`,
  `gdm-missing`;
- `gnome.launch.service_logs` and a copy of `pipewire.log`,
  `wireplumber.log`, `ibus.log`, `session-bus.log` and `droiddeck-ime.log`
  from the runtime dir into the session folder (each bounded to 256 KiB, head
  kept), because those services write outside the folder the log browser
  exports;
- `gnome.launch.stalled` and a `GNOME_LAUNCH_STALLED` failure when, after
  180 s, nothing has been drawn, no `inner` socket exists and no stage
  reported a failure. The session then ends on the end screen, which names
  the last stage and its missing sockets, instead of leaving a black screen.

A non-zero `shell` exit code or a stage with `exit=failed` is named on the end
screen too, and a session that wrote no stage at all is named that way when the
pack files are missing or the launcher exited 66. A shell that exits 0 before
the first frame (what mutter-devkit does when PipeWire is unreachable) still
gets an end-screen reason from those faults, because the exit status says
nothing there. All of it is non-secret: stage names, exit codes, socket names
and the guest services' own logs, no tokens or user data.

A session also starts by removing the guest service sockets (`pipewire-0`,
`pipewire-0.lock`, `ibus.sock`, `session-bus.sock`, `system-bus.sock`) left in
`XDG_RUNTIME_DIR` by an earlier one. The launcher starts each of those services
only when its socket is absent (`[ ! -S ... ]`), so a dead socket from a failed
session makes the next one skip PipeWire and die in mutter-devkit with "Failed
to connect pipewire context" - which is the loop a device showed after one
failed run. The compositor's `wayland-0` and the app's `droiddeck-ime.sock` are
left alone.

## Frame callbacks while the scene is idle

The compositor used to answer `wl_callback.done` only from a render, and a
render only happened when the scene had damage. A nested compositor waits for
that callback before it paints its next frame, so once a GNOME session settled
(no cursor, no window moving) its frame clock never completed, and the shell
tore the session down a moment later - the device showed the shell exiting 0, 1
or 137 within 0.2-0.7 s of its first frame, and staying alive for 38-48 s while
something kept drawing. `fire_waiting_frames()` now answers every pending
callback on each vsync tick whether or not a scene was drawn, which is what an
output does, and the pacing line counts those answers (`N idle frame answers`)
so a log shows the change took effect.

`events.jsonl` also records `session.exit` with the exit status, the signal it
decodes to, and how long before the exit the last frame and the last input
were, so a stopped guest and a starved one can be told apart without guessing.


## Unprivileged start

`droiddeck-gnome-desktop` starts the same services and then GNOME Shell in
devkit mode under the app user: private buses in `$XDG_RUNTIME_DIR`, no root,
no host D-Bus, no logind. It must be invoked inside the session bus that the
desktop script creates, like `droiddeck-desktop` for KDE.

The integration script was also run under PRoot, the Android runtime's
unprivileged chroot:

```sh
proot -r / -b /repo -b /out -b /tmp/runtime -w / /bin/bash \
  /repo/tools/gnome-desktop/tests/run-integration.sh /out
```

Do not set `PROOT_NO_SECCOMP=1` in this image: it makes the Rust coreutils
multicall see proot's trampoline name as its program and fail. The run reaches
`OUTER_TO_SHELL_TO_GTK PASS`, prints `INTEGRATION_PASS` and exits 0; the
harness reaps only the PIDs it started (recursively), never processes by name.

## State that outlives a session

The compositor and the app process outlive a desktop session, and two pieces of
state from the previous one used to reach the next:

- the compositor kept the desktop surface, the IME-click surface, the grab, the
  keyboard target, the cursor surface and the seat focus resources of a session
  that had ended. `droiddeck_session_reset()` drops them when a session starts,
  so input can never be routed through a reference the new session does not own.
- `teardown(prootPid)` ran on a thread with no start time, so a pid the new
  session had already reused could be signalled by the previous session's
  cleanup. The guest's start time is captured when it starts and every teardown
  now checks it before signalling anything.

The session log capture was also being killed by the session start itself:
`OrphanReaper.reap("session starting")` ends every process of this app's uid it
does not know, and the `logcat` that writes `app.log` was one of them, which is
why bundles held only the first seconds of it. It is kept while it runs.

The first 24 pointer events of a session log their target pid and serial
(`event N: button|motion -> pid N serial S`), so a client that goes away right
after an event can be tied to the event it received.
