#!/usr/bin/env bash
# Discover Harmony/Huawei vehicle signal API on the car head unit.
# Run on Mac with USB adb to the HU: ./scripts/discover_vehicle_api.sh

set -euo pipefail

OUT="${1:-/tmp/hud_vehicle_api_report.txt}"
: >"$OUT"

log() { echo "$*" | tee -a "$OUT"; }

log "=== HUD Vehicle API discovery ==="
log "date: $(date)"
log ""

log "--- adb device ---"
adb devices -l | tee -a "$OUT" || true
log ""

log "--- packages (vehicle/harmony/car/huawei auto) ---"
adb shell pm list packages 2>/dev/null | grep -iE 'vehicle|harmony|hmsauto|automotive|vhal|car\.service' | tee -a "$OUT" || true
log ""

log "--- framework jars (vehicle/car) ---"
adb shell "ls /system/framework 2>/dev/null | grep -iE 'vehicle|car|auto|harmony'" | tee -a "$OUT" || true
log ""

log "--- search VehicleBodyManager in framework ---"
adb shell "grep -r 'VehicleBodyManager' /system/framework 2>/dev/null | head -20" | tee -a "$OUT" || true
log ""

log "--- search indicator signal names ---"
adb shell "grep -r 'IsLeftIndicatorOn\|IsRightIndicatorOn\|TURN_SIGNAL' /system 2>/dev/null | head -30" | tee -a "$OUT" || true
log ""

log "--- dumpsys car (if AAOS) ---"
adb shell dumpsys car_service 2>/dev/null | head -40 | tee -a "$OUT" || log "(no car_service)"
log ""

log "--- logcat turn-signal / vehicle (last 200 lines) ---"
adb logcat -d -t 200 2>/dev/null | grep -iE 'VehicleBody|turn.signal|indicator|Vehicle\.Body' | tee -a "$OUT" || true
log ""

log "--- HUD app permissions ---"
adb shell dumpsys package com.hud.extension 2>/dev/null | grep -A2 'requested permissions\|granted permissions' | head -40 | tee -a "$OUT" || true
log ""

log "Report saved: $OUT"
log ""
log "=== While running: blink LEFT then RIGHT turn signal, then re-run: ==="
log "adb logcat -d -t 300 | grep -iE 'Indicator|TurnSignal|Vehicle\.Body'"
log "Send $OUT plus logcat output to tune auto-detection."
