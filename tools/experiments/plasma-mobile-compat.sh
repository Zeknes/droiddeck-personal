#!/bin/bash
set -euo pipefail
[ "$(uname -m)" = aarch64 ] || { echo 'requires an isolated Ubuntu arm64 runtime' >&2; exit 1; }
[ -f /usr/share/droiddeck/desktop/files.tsv.gz ] || { echo 'DroidDeck desktop pack is missing' >&2; exit 1; }
for package in kwin-wayland libkwin6; do
  version=$(dpkg-query -W -f='${Version}' "$package")
  [[ $version == *+droiddeck* ]] || { echo "$package is not the DroidDeck build" >&2; exit 1; }
  printf '%s %s\n' "$package" "$version"
done
apt-get -s --no-install-recommends install plasma-mobile plasma-mobile-tweaks
