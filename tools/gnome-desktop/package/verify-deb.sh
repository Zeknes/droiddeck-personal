#!/bin/bash
# Installs the built deb into the unprivileged GNOME 50 test image, checks the
# installed launcher and extension toggle, then removes the package again.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
deb=$(realpath "${1:?droiddeck-gnome-desktop .deb required}")
image=${2:-gnome-work-20261116-base}
evidence=$(realpath "${3:-$(mktemp -d)}")
mkdir -p "$evidence"

docker run --rm --user root -v "$deb:/pkg.deb:ro" -v "$evidence:/out" "$image" bash -c '
    set -euo pipefail
    dpkg -i /pkg.deb >/out/dpkg.log 2>&1
    dpkg -L droiddeck-gnome-desktop >/out/files.log
    runuser -u tester -- env HOME=/home/tester droiddeck-gnome-desktop --check | tee /out/check.log
    runuser -u tester -- env HOME=/home/tester dbus-run-session -- bash -c "
        droiddeck-gnome-extension enable | tee /out/extension-enable.log
        droiddeck-gnome-extension status | tee /out/extension-status.log
        droiddeck-gnome-extension disable | tee /out/extension-disable.log
    "
    dpkg -r droiddeck-gnome-desktop >/out/dpkg-remove.log 2>&1
    test ! -e /usr/local/bin/droiddeck-gnome-desktop
    echo PACKAGE_INSTALL_PASS
'
