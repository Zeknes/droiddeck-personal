#!/bin/bash
# Builds droiddeck-gnome-desktop_<version>_all.deb from this tree. The package
# installs the launcher, the extension toggle and the input bridge; the GNOME
# packages themselves come from the runtime's apt sources.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
out=$(realpath "${1:?output directory required}")
version=${2:-1.0+gnome.$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo local)}
stage="$out/droiddeck-gnome-desktop_${version}_all"

rm -rf "$stage"
mkdir -p "$stage/DEBIAN" "$stage/usr/local/bin" \
    "$stage/usr/local/share/droiddeck/gnome-input/extension" \
    "$stage/usr/local/share/doc/droiddeck-gnome-desktop"
install -m 755 "$here/droiddeck-gnome-desktop" "$stage/usr/local/bin/"
install -m 755 "$here/droiddeck-gnome-extension" "$stage/usr/local/bin/"
install -m 755 "$here/input-bridge/droiddeck-ime-bridge" "$stage/usr/local/share/droiddeck/gnome-input/"
install -m 644 "$here/input-bridge/droiddeck_ime_state.py" "$stage/usr/local/share/droiddeck/gnome-input/"
install -m 644 "$here/input-bridge/extension/extension.js" "$stage/usr/local/share/droiddeck/gnome-input/extension/"
install -m 644 "$here/input-bridge/extension/metadata.json" "$stage/usr/local/share/droiddeck/gnome-input/extension/"
install -m 644 "$here/README.md" "$stage/usr/local/share/doc/droiddeck-gnome-desktop/README.md"
sed "s/@VERSION@/$version/" "$here/package/control.in" >"$stage/DEBIAN/control"
chmod 644 "$stage/DEBIAN/control"
dpkg-deb --build --root-owner-group "$stage" "$out/droiddeck-gnome-desktop_${version}_all.deb"
sha256sum "$out/droiddeck-gnome-desktop_${version}_all.deb"
