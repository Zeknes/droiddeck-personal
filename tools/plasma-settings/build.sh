#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
[ "$(uname -m)" = aarch64 ] || { echo 'requires an isolated Ubuntu 26.04 arm64 builder' >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get build-dep -y --no-install-recommends plasma-settings
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
echo 'bc286f2df392f377dbbacaa6e5629e8538500c3ed24ec6f45e6838cc6df62971  '"$here/source.tar.xz" | sha256sum -c -
tar -xf "$here/source.tar.xz" -C "$work"
source="$work/plasma-settings-25.12.0"
patch -d "$source" -p1 < "$here/null-menu-item.patch"
cmake -S "$source" -B "$work/out" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr -DBUILD_TESTING=OFF
cmake --build "$work/out" --parallel 4
cp "$here/null-probe.cpp" "$source/src/null-probe.cpp"
python3 - "$source/src/CMakeLists.txt" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
links = s[s.index('target_link_libraries(plasma-settings PRIVATE'):s.index('install(TARGETS')]
s += '\nadd_executable(null-probe null-probe.cpp modulesproxymodel.cpp modulesproxymodel.h modulesmodel.cpp modulesmodel.h menuitem.cpp menuitem.h)\n'
s += links.replace('target_link_libraries(plasma-settings', 'target_link_libraries(null-probe')
p.write_text(s)
PY
cmake -S "$source" -B "$work/out" -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build "$work/out" --target null-probe --parallel 4
"$work/out/bin/null-probe"
install -m755 "$work/out/bin/plasma-settings" "$here/plasma-settings.arm64"
strip --strip-unneeded "$here/plasma-settings.arm64"
sha256sum "$here/plasma-settings.arm64"
