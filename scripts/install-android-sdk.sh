#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs into $ANDROID_HOME (default ~/android-sdk)
# and writes local.properties. Needs network access to dl.google.com.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CLT_ZIP="commandlinetools-linux-16111833_latest.zip"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -sSfL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"

echo "sdk.dir=$SDK" > "$ROOT/local.properties"
echo "Android SDK ready at $SDK"
