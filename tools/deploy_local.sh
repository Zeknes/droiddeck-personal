#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
adb_bin=${ADB:-${sdk_dir}/platform-tools/adb}
version=$(sed -n 's/^ *versionName "\(.*\)"$/\1/p' "${repo_root}/app/build.gradle")
apk=${1:-"${repo_root}/app/build/outputs/apk/release/DroidDeck-Pure-${version}.apk"}

if [[ ! -x "${adb_bin}" ]]; then
    echo "adb not found at ${adb_bin}; set ADB or ANDROID_HOME." >&2
    exit 1
fi
if [[ ! -f "${apk}" ]]; then
    echo "APK not found at ${apk}; run tools/build_local.sh first." >&2
    exit 1
fi

serial=$(python3 "${repo_root}/tools/adb_device.py" --adb "${adb_bin}")

"${adb_bin}" -s "${serial}" install -r --no-incremental "${apk}"
installed_path=$("${adb_bin}" -s "${serial}" shell pm path com.zeknes.droiddeck.pure | tr -d '\r')
printf 'Installed on %s: %s\n' "${serial}" "${installed_path}"
