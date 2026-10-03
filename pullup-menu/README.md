# Pull Up Menu (Android + Android Auto)

Pull up to a restaurant, stop for ~20 seconds, and the car screen shows that restaurant's
food/menu photos. Your phone gets a notification that opens the full photo gallery and a link to
the actual menu.

## How it works

| Piece | What it does |
|---|---|
| Arrival detection | GPS speed under ~4.5 mph for 20 s at a spot it hasn't checked yet → lookup |
| Lookup | Google Places API (New) Nearby Search, food places within 75 m, nearest first |
| Car screen (Android Auto) | Restaurant name, category, rating, and a grid of photos. "Not here?" lets you pick a neighbor (strip malls) |
| Phone | Notification → full-size photo gallery, plus **Menu (Maps)**, **Website**, **Search menu** buttons |
| Phone-only drive mode | Same detection without Android Auto, via a foreground location service |

**About "menu with pictures":** no public API returns item-by-item menus for every restaurant.
The app shows the restaurant's Google photos (mostly dishes and menu boards, uploaded by Google
users) and links to Google Maps' Menu tab and the restaurant's website for the full menu.

## Setup

### 1. Get a Google Places API key (~5 min)
1. Go to <https://console.cloud.google.com/>, create a project.
2. **APIs & Services → Library →** enable **Places API (New)**.
3. **APIs & Services → Credentials → Create credentials → API key.**
4. Restrict the key to *Places API (New)* (recommended).

Billing must be enabled on the project. Google's free monthly usage covers personal use; each
stop is 1 Nearby Search plus up to ~10 photo loads. Check current pricing on Google's
Places pricing page before relying on that.

### 2. Get the APK
- **Easiest:** GitHub → **Actions → Build Pull Up Menu APK** → latest run → download
  `pullup-menu-apk` (a zip containing `app-debug.apk`).
- **Or build locally:** `./gradlew assembleDebug` (needs the Android SDK; output in
  `app/build/outputs/apk/debug/`).

### 3. Install on your phone
1. Copy `app-debug.apk` to the phone and open it; allow "Install unknown apps" when asked.
2. Open **Pull Up Menu**, paste the API key, tap **Save key**, then **Grant location &
   notifications**.
3. Tap **What restaurant am I at?** to test.

### 4. Enable it in Android Auto (required — it isn't from the Play Store)
1. Phone **Settings → search "Android Auto"** → open Android Auto settings.
2. Scroll to the bottom and tap **Version** ~10 times → accept the developer-mode prompt.
3. Top-right **⋮ → Developer settings → check "Unknown sources"**.
4. Back in Android Auto settings → **Customize launcher** → make sure **Pull Up Menu** is checked.
5. In the car, open Pull Up Menu from the Android Auto launcher. It watches while it's open.

## Limits to know
- **Android Auto restricts what's on screen while moving.** Photo grids are capped (usually 6
  items) and the car may block interaction while driving. Since this triggers when you're
  stopped, that's normally fine.
- **Play Store:** Google only allows a few app categories on Android Auto. This is built as a
  "points of interest" app and is meant for personal sideloading; it would likely not pass Play
  review as-is.
- **Red lights / drive-thru lines:** a 20 s stop within 75 m of a restaurant triggers a lookup.
  Tune `dwellMs` in `ArrivalDetector.kt` or the radius in `Prefs.kt`.
- The app only watches while the Android Auto screen is open or phone-only drive mode is on.

## Code map
- `core/ArrivalDetector.kt` – "did we pull up?" logic (unit tested)
- `core/PlacesClient.kt`, `core/PlacesParser.kt` – Places API calls/parsing (parser unit tested)
- `core/MenuRepository.kt` – shared state between car screen and phone
- `car/` – Android Auto screens (Car App Library templates)
- `phone/` – setup screen, menu/photo screen, phone-only drive service
