#!/usr/bin/env bash
# Renders every car screen and state in Google's templates host, at several display sizes.
# States are set and rows tapped through the debug-only DebugDriver (see DebugDriver.kt).
set -x
OUT=${OUT:-shots}; mkdir -p "$OUT"
P="python3 .github/aa-shots/drive.py"
d() { $P drive "$@"; }
s() { $P shot "$1"; }
adb shell pm list packages -f | sort > "$OUT/packages.txt"
grep -i -E "templat|car\.|automotive" "$OUT/packages.txt" > "$OUT/packages-car.txt"
adb shell wm size > "$OUT/wm.txt"; adb shell wm density >> "$OUT/wm.txt"
adb install -r "$APK" > "$OUT/install.txt" 2>&1
adb shell pm grant com.wdwy90.pullupmenu android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.wdwy90.pullupmenu android.permission.ACCESS_COARSE_LOCATION
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
d error "No internet connection. Try again.";               s 04-home-error-internet
d error "Couldn't get your location. Try again in a moment."; s 05-home-error-location
d error "Add your Google Places API key in the phone app.";  s 06-home-error-key
d perm on;                                s 07-home-permission
d perm off
d auto on 6
d idle;                                   s 08-home-ready
d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield" 4; s 09-card
d tap "View menu";                        s 10-menu
d tap "Burgers";                          s 11-burgers
d back;                                   s 12-menu-after-back
d back 4;                                 s 13-card-after-back
d tap "Not here?";                        s 14-chooser
d back
d home;                                   s 15-home-last-stop
d tap "Show restaurant" 4;                s 16-card-from-home
d found "Wendy's|0|0|0|3.9|4501 W Wabash Ave, Springfield" 4; s 17-card-closed-no-photo
d tap "View menu";                        s 18-menu-wendys
d found "Steak 'n Shake|1|-|0|4.0|2955 South MacArthur Boulevard, Springfield, IL 62704" 4; s 19-card-long
d found "Subway|0|1|0|4.2|3001 Freedom Dr, Springfield" 4
d tap "View menu";                        s 20-menu-subway
d tap "Hot Honey Signature Swicy Collection"; s 21-subway-long-category
d found "Joe's Burger Shack|0|1|0|4.6|88 Route 66, Springfield" 4; s 22-card-no-menu
d found "McDonald's|1|1|0|3.8|2700 S 6th St, Springfield" 4
d tap "View menu";                        s 23-menu-mcd
d tap "Breakfast";                        s 24-mcd-breakfast
d back
d tap search 3;                           s 25-search-empty
d search "chicken";                       s 26-search-chicken
d search "pizza";                         s 27-search-none
d back
d found "Starbucks|1|1|0|4.3|1 Old State Capitol Plaza, Springfield" 4
d tap "View menu";                        s 28-menu-starbucks
# A host that allows only 6 rows per list (the least Android Auto guarantees).
d limit 6
d found "McDonald's|0|1|0|3.8|2700 S 6th St, Springfield" 4
d tap "View menu";                        s 29-limit6-menu-mcd
d tap "Burgers, Chicken & Fish Sandwiches"; s 30-limit6-combined
d back
d tap "Breakfast";                        s 31-limit6-parts
d tap "Breakfast 1 of";                   s 32-limit6-part-items
d found "Burger King|0|1|0|4.1|1200 N Main St, Springfield" 4
d tap "View menu";                        s 33-limit6-menu-bk
d limit 0
d home; d idle
d tap "Demo" 4;                           s 34-demo-card
d tap "View menu";                        s 35-demo-menu
# Day and night: what the templates host does with each.
adb shell cmd car_service day-night-mode night > "$OUT/night.txt" 2>&1
adb shell cmd uimode night yes >> "$OUT/night.txt" 2>&1
sleep 6;                                  s 36-night
adb shell cmd car_service day-night-mode day >> "$OUT/night.txt" 2>&1
adb shell cmd uimode night no >> "$OUT/night.txt" 2>&1
sleep 6;                                  s 37-day
# Restart the host in day mode, in case it only reads the mode when it starts.
adb shell am force-stop com.wdwy90.pullupmenu
adb shell am force-stop com.google.android.apps.automotive.templates.host
launch
d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield" 4; s 38-day-restart-card
# Display sizes and densities.
size() {
  adb shell wm size "$1"; adb shell wm density "$2"; sleep 10
  d home; d found "Burger King|1|1|2|4.1|1200 N Main St, Springfield" 5; s "$3-card"
  d tap "View menu";                      s "$3-menu"
  d tap "Chicken & Fish";                 s "$3-items"
  d home; d error "No internet connection. Try again."; s "$3-home-error"
}
size 1920x720 160 40-wide
size 800x480 120 41-small
size 1280x720 213 42-dense
size 768x1024 160 43-portrait
adb shell wm size reset; adb shell wm density reset
adb logcat -d > "$OUT/logcat-all.txt"
grep -E "PullUpDrive|AndroidRuntime|FATAL|CarApp|car.app" "$OUT/logcat-all.txt" | tail -600 > "$OUT/logcat.txt"
true
