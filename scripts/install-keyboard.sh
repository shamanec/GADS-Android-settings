#!/usr/bin/env bash
# Build the release APK, install it on a connected Android device,
# and enable + select the GADS Remote Keyboard IME so it can be tested.
#
# Usage:
#   scripts/install-keyboard.sh            # uses the only connected device
#   scripts/install-keyboard.sh <serial>   # targets a specific adb serial

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

APK_PATH="app/build/outputs/apk/release/gads-settings.apk"
PACKAGE="com.gads.settings"
IME_ID="$PACKAGE/.GADSKeyboardIME"

ADB_ARGS=()
if [[ $# -ge 1 ]]; then
    ADB_ARGS=(-s "$1")
fi

resolve_device() {
    if [[ ${#ADB_ARGS[@]} -gt 0 ]]; then
        return
    fi
    local count
    count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
    if [[ "$count" -eq 0 ]]; then
        echo "No connected adb devices found." >&2
        exit 1
    fi
    if [[ "$count" -gt 1 ]]; then
        echo "Multiple devices connected. Pass a serial: scripts/install-keyboard.sh <serial>" >&2
        adb devices >&2
        exit 1
    fi
}

resolve_device

echo "==> Building release APK"
./gradlew assembleRelease

if [[ ! -f "$APK_PATH" ]]; then
    echo "Expected APK not found at $APK_PATH" >&2
    exit 1
fi

echo "==> Installing $APK_PATH"
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} install -r "$APK_PATH"

echo "==> Waiting for system to register the IME"
for _ in $(seq 1 20); do
    if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ime list -a 2>/dev/null | grep -q "$IME_ID"; then
        break
    fi
    sleep 0.5
done

echo "==> Enabling IME $IME_ID"
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ime enable "$IME_ID"

echo "==> Setting $IME_ID as the active IME"
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ime set "$IME_ID"

echo "==> Active IME on device:"
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell settings get secure default_input_method
