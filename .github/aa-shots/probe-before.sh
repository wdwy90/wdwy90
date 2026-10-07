#!/usr/bin/env bash
# The same scripted run against the shipped 1.9 (25) car screens, for a before/after comparison.
set -x
OUT=${OUT:-shots}; mkdir -p "$OUT"
P="python3 .github/aa-shots/drive.py"
d() { $P drive "$@"; }
s() { $P shot "$1"; }
adb install -r "$APK" > "$OUT/install.txt" 2>&1
adb shell pm grant --user current com.wdwy90.pullupmenu android.permission.ACCESS_FINE_LOCATION
adb shell pm grant --user current com.wdwy90.pullupmenu android.permission.ACCESS_COARSE_LOCATION
adb logcat -c
launch() {
  adb shell am start -W -n com.wdwy90.pullupmenu/androidx.car.app.activity.CarAppActivity >> "$OUT/start.txt" 2>&1
  sleep 20
}
launch
d dump
d auto off
d idle;                                   s 01-home-off
d searching;                              s 02-home-searching
d nothing;                                s 03-home-nothing
d error "No internet connection. Try again."; s 04-home-error-internet
d auto on
d idle;                                   s 08-home-ready
d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield"; s 09-card
d tap "Items";                            s 10-menu
d back;                                   s 13-card-after-back
d tap "Not here?";                        s 14-chooser
d back
d home;                                   s 15-home-last-stop
d found "Wendy's|0|0|0|3.9|4501 W Wabash Ave, Springfield"; s 17-card-closed-no-photo
d found "Steak 'n Shake|1|-|0|4.0|2955 South MacArthur Boulevard, Springfield, IL 62704"; s 19-card-long
d found "Joe's Burger Shack|0|1|0|4.6|88 Route 66, Springfield"; s 22-card-no-menu
d found "McDonald's|1|1|0|3.8|2700 S 6th St, Springfield"
d tap "Items";                            s 23-menu-mcd
d tap "Breakfast";                        s 24-mcd-breakfast
d found "Starbucks|1|1|0|4.3|1 Old State Capitol Plaza, Springfield"
d tap "Items";                            s 28-menu-starbucks
d home; d idle
d tap "Demo";                             s 34-demo-card
d tap "Items";                            s 35-demo-menu
size() {
  adb shell wm size "$1"; adb shell wm density "$2"; sleep 12
  d home; d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield"; s "$3-card"
  d tap "Items";                          s "$3-menu"
  d home; d error "No internet connection. Try again."; s "$3-home-error"
}
size 1920x720 160 40-wide
size 800x480 120 41-small
adb shell wm size reset; adb shell wm density reset
adb logcat -d > "$OUT/logcat-all.txt"
grep -E "PullUpDrive|AndroidRuntime|FATAL|CarApp|car.app" "$OUT/logcat-all.txt" | tail -600 > "$OUT/logcat.txt"
true
