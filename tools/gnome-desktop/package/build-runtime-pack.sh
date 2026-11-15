#!/bin/bash
# Builds the GNOME desktop pack on top of a DroidDeck Ubuntu runtime.
#
# Runs in an Ubuntu 26.04 arm64 container with the runtime and output on a
# Docker volume (not a host bind mount: dpkg needs normal ownership). It
# installs the GNOME packages and the droiddeck-gnome-desktop deb into the
# runtime, writes the pack metadata and produces <tag>-gnome-desktop.tar.zst
# plus a JSON file with its sha256 and size.
set -euo pipefail

runtime_tar=${1:?runtime tar.zst required}
deb=${2:?droiddeck-gnome-desktop deb required}
out=${3:?output directory required}
tag=${4:?pack tag required}
url=${5:?pack URL required for desktop-pack.json}

work=${DROIDDECK_PACK_WORK:-/work}
root="$work/runtime"
before="$work/runtime-before.tsv"
plain="$work/runtime-orig.tar"
mkdir -p "$work" "$out"
rm -rf "$root"
mkdir -p "$root"

tar --use-compress-program=unzstd -xf "$runtime_tar" -C "$root"
python3 /desktop-pack.py manifest "$root" "$before"
tar -cf "$plain" -C "$root" .

cp "$deb" "$root/tmp/droiddeck-gnome-desktop.deb"
for d in proc sys dev; do mount --rbind "/$d" "$root/$d" || true; done
cp /etc/resolv.conf "$root/etc/resolv.conf"
chroot "$root" /bin/bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends \
        /tmp/droiddeck-gnome-desktop.deb \
        gnome-terminal nautilus gnome-text-editor gnome-calculator \
        gnome-control-center gnome-settings-daemon xdg-desktop-portal-gnome gnome-keyring
'
for d in proc sys dev; do umount -R "$root/$d" 2>/dev/null || true; done
rm -f "$root/tmp/droiddeck-gnome-desktop.deb" "$root/tmp/gnome-install"*.log

mkdir -p "$root/usr/share/droiddeck"
printf '{"tag":"%s","url":"%s","sha256":"","size":0}\n' "$tag" "$url" \
    > "$root/usr/share/droiddeck/desktop-pack.json"

python3 /desktop-pack.py pack "$root" "$before" "$plain" "$work/desktop.tar" "$work/meta"
zstd -19 -T0 -f "$work/desktop.tar" -o "$out/$tag-gnome-desktop.tar.zst"
rm -f "$work/desktop.tar" "$plain"
sha=$(sha256sum "$out/$tag-gnome-desktop.tar.zst" | awk '{print $1}')
size=$(stat -c %s "$out/$tag-gnome-desktop.tar.zst")
printf '{"tag":"%s","url":"%s","sha256":"%s","size":%s}\n' "$tag" "$url" "$sha" "$size" \
    > "$out/$tag-gnome-desktop.json"
cat "$out/$tag-gnome-desktop.json"
