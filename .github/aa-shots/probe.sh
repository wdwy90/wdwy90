#!/usr/bin/env bash
# First look: does the emulator have Google's templates host, and does our car app render in it?
set -x
OUT=${OUT:-shots}; mkdir -p "$OUT"
D="python3 .github/aa-shots/drive.py"
adb shell pm list packages -f | sort > "$OUT/packages.txt"
grep -i -E "templat|car\.|automotive" "$OUT/packages.txt" > "$OUT/packages-car.txt"
adb shell getprop > "$OUT/getprop.txt"
adb shell wm size > "$OUT/wm.txt"; adb shell wm density >> "$OUT/wm.txt"
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
adb shell pm grant com.wdwy90.pullupmenu android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.wdwy90.pullupmenu android.permission.ACCESS_COARSE_LOCATION
adb logcat -c
adb shell am start -W -n com.wdwy90.pullupmenu/androidx.car.app.activity.CarAppActivity > "$OUT/start.txt" 2>&1
sleep 25
$D shot 01-home
$D tap Demo; sleep 4
$D shot 02-demo-card
$D tap Items; sleep 4
$D shot 03-items
$D tap Burgers || $D tap "1 of"; sleep 3
$D shot 04-category
adb shell cmd car_service day-night-mode night > "$OUT/night.txt" 2>&1
adb shell cmd uimode night yes >> "$OUT/night.txt" 2>&1
sleep 6
$D shot 05-category-night
adb shell cmd car_service day-night-mode day >> "$OUT/night.txt" 2>&1
adb shell cmd uimode night no >> "$OUT/night.txt" 2>&1
adb shell wm size 1920x720; adb shell wm density 160; sleep 10
$D shot 06-wide
adb shell wm size 800x480; adb shell wm density 120; sleep 10
$D shot 07-small
adb shell wm size reset; adb shell wm density reset
adb logcat -d > "$OUT/logcat-all.txt"
grep -i -E "pullup|car\.app|CarApp|templat|AndroidRuntime|FATAL" "$OUT/logcat-all.txt" | tail -400 > "$OUT/logcat.txt"
true
