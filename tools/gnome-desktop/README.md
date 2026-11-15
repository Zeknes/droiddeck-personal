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
