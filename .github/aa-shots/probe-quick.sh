#!/usr/bin/env bash
# A shorter run over the screens the review fixes touched (accent, card credits, menu source row, search notes).
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
d auto on
d idle;                                   s 01-home-ready
d error "No internet connection. Try again."; s 02-home-error
d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield"; s 03-card
d tap "View menu";                        s 04-menu
d tap "Burgers";                          s 05-burgers
d found "Taco Bell|1|0|0|4.0|2500 Wabash Ave, Springfield|Christopher Montgomery-Alexander Photography"; s 06-card-long-credit
d found "Whataburger|0|1|0|4.5|3700 S 6th St, Springfield"; s 07-card-whataburger
d tap "View menu";                        s 08-menu-whataburger
d found "Starbucks|1|1|0|4.3|1 Old State Capitol Plaza, Springfield"
d tap "View menu";                        s 09-menu-starbucks
d tap search
d search "pumpkin";                       s 10-search-notes
d found "Joe's Burger Shack|0|1|0|4.6|88 Route 66, Springfield"; s 11-card-no-menu
d limit 6
d found "McDonald's|0|1|0|3.8|2700 S 6th St, Springfield"
d tap "View menu";                        s 12-limit6-menu-mcd
d found "Burger King|0|1|0|4.1|1200 N Main St, Springfield"
d tap "View menu";                        s 13-limit6-menu-bk
d limit 0
d home; d idle
d tap "Demo";                             s 14-demo-card
d tap "View menu";                        s 15-demo-menu
size() {
  adb shell wm size "$1"; adb shell wm density "$2"; sleep 12
  d home; d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield"; s "$3-card"
  d tap "View menu";                      s "$3-menu"
}
size 800x480 120 41-small
size 1920x720 160 40-wide
adb shell wm size reset; adb shell wm density reset
adb logcat -d > "$OUT/logcat-all.txt"
grep -E "PullUpDrive|AndroidRuntime|FATAL|CarApp|car.app" "$OUT/logcat-all.txt" | tail -600 > "$OUT/logcat.txt"
true
