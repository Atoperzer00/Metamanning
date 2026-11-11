#!/usr/bin/env bash
# Read-only hardware preflight. Run on the computer whose ADB can reach the Quest.
set -euo pipefail
ADB_BIN="${ADB_BIN:-adb}"
PACKAGE="com.metamanning.spatial"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo 'BLOCKED: ADB is unavailable. Install Android platform-tools first.' >&2
  exit 2
fi
mapfile -t device_rows < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')
if [[ ${#device_rows[@]} -ne 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  echo 'BLOCKED: connect and authorize exactly one Quest 3, or set ANDROID_SERIAL for the chosen device.' >&2
  "$ADB_BIN" devices -l
  exit 2
fi
if ! "$ADB_BIN" get-state >/dev/null 2>&1; then
  echo 'BLOCKED: the selected headset is unavailable or USB debugging is unauthorized.' >&2
  exit 2
fi
MODEL="$($ADB_BIN shell getprop ro.product.model | tr -d '\r')"
SDK_LEVEL="$($ADB_BIN shell getprop ro.build.version.sdk | tr -d '\r')"
BUILD_ID="$($ADB_BIN shell getprop ro.build.display.id | tr -d '\r')"
printf 'Device: %s\nAndroid API: %s\nBuild: %s\n' "$MODEL" "$SDK_LEVEL" "$BUILD_ID"
if [[ "$MODEL" != *"Quest 3"* || "$MODEL" == *"Quest 3S"* ]]; then
  echo 'BLOCKED: this release gate requires the specified Meta Quest 3.' >&2
  exit 2
fi
if [[ ! "$SDK_LEVEL" =~ ^[0-9]+$ || "$SDK_LEVEL" -lt 34 ]]; then
  echo 'BLOCKED: the Spatial SDK build requires Android API 34 or later.' >&2
  exit 2
fi
if ! "$ADB_BIN" shell pm path "$PACKAGE" | tr -d '\r' | awk '/^package:/{found=1} END{exit !found}'; then
  echo 'BLOCKED: Metamanning Spatial is not installed. Install the development build for device verification.' >&2
  exit 2
fi
"$ADB_BIN" shell dumpsys package "$PACKAGE" | awk '/versionName=|versionCode=|android.permission.CAMERA:|horizonos.permission.HEADSET_CAMERA:|android.permission.RECORD_AUDIO:|com.oculus.permission.USE_SCENE:/ { print }'
echo 'PREFLIGHT ONLY: room relocalization, hands, sign-in, voice, uploads and tools still require the on-headset acceptance tests.'
