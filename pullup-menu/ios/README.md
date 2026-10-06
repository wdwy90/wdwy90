# Pull Up Menu — iPhone + CarPlay

SwiftUI app + Live Activity widget. Core logic (detection, Places parsing, chain menus) is in the
`PullUpCore` Swift package and unit tested (`swift test --package-path PullUpCore`).

## What you see
- **CarPlay Dashboard (iOS 26+):** "Watching for drive-thrus" → "McDonald's · ★ 3.9 · Menu items on iPhone",
  with an alert when it finds one.
- **iPhone:** the restaurant screen pops up with three tabs, same as Android: **Items** (the chain's
  menu item list from `shared/chain_prices.json`, names only, with seasonal/limited notes), **Menu**
  (the chain's menu page inside the app) and **Photos** (Google photos with photographer credits).
  Lock Screen / Dynamic Island show the same Live Activity. **Try a demo** shows a sample restaurant.
- **Auto-detect:** 20 s stopped within 45 m of a fast-food place. If the car speeds off within
  45 s (a red light, not a drive-thru line) the card goes away and that spot is ignored for the drive.

## Requirements
- A **Mac with Xcode 16+** and an iPhone on iOS 18+ (iOS 26 for the CarPlay Dashboard part).
- An Apple ID. Free works, but the app expires every 7 days and must be re-installed from Xcode.
  A paid Apple Developer account ($99/yr) gives 1-year installs or TestFlight.
- A Google Places API key (see the Android README, step 1 — same key). To build it into the app,
  create `Config/Secrets.xcconfig` (ignored by git) containing `PLACES_API_KEY = AIza...`, then
  re-run `xcodegen generate`. Without it, the app asks for the key on screen. The app sends
  `X-Ios-Bundle-Identifier`, so the key can be restricted to iOS apps (`com.wdwy90.pullupmenu`) in
  Google Cloud — but use a separate key for iOS, since one key can't have Android and iOS restrictions.

## Build & install
```bash
brew install xcodegen
cd pullup-menu/ios
xcodegen generate
open PullUpMenu.xcodeproj
```
1. In Xcode, select the **PullUpMenu** target → Signing & Capabilities → pick your Team. Do the same
   for **PullUpWidget**. If Xcode says the bundle ID is taken, change `com.wdwy90` in `project.yml`
   to something unique, re-run `xcodegen generate`.
2. Plug in your iPhone, select it as the run destination, press **Run**.
3. On the phone: Settings → General → VPN & Device Management → trust your developer certificate
   (first time only). Also turn on Developer Mode if prompted.
4. In the app: paste the API key → **Save key** (skipped if it's built in), allow location (choose **Always** for auto-start)
   and notifications.

## Auto-start when CarPlay connects (recommended)
Shortcuts app → **Automation → + → CarPlay → Connects → Run Immediately** → add action
**Start watching** (Pull Up Menu). Add a second automation for **Disconnects → Stop watching**.
Without this, open the app and flip **Watch for drive-thrus** before you drive.

## Limits
- CarPlay shows a small glanceable card, not photos — Apple doesn't allow full app screens for this
  kind of app (see `../README.md`).
- iOS may stop the app if the phone is under heavy memory pressure; the Live Activity would then stop
  updating. Re-run the shortcut or reopen the app.
- Live Activities end after 8 hours; restart watching for long trips.
