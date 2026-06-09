#!/usr/bin/env bash
# Install HUD extension on Harmony car HU (adb install often fails with -110).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

cd "$ROOT"
./gradlew assembleDebug

echo "Pushing APK..."
adb push "$APK" /data/local/tmp/app-debug.apk

echo "Installing via Huawei car installer..."
if adb shell pm install -r -d -g -i com.huawei.appinstaller.car /data/local/tmp/app-debug.apk; then
    echo "OK: installed $(adb shell dumpsys package com.hud.extension | grep versionName | head -1)"
    exit 0
fi

echo "Fallback: pm install -r ..."
adb shell pm install -r /data/local/tmp/app-debug.apk
