# Pull Up Menu (Android + Android Auto)

Get in line at a fast-food drive-thru. The car screen shows a restaurant card (name, rating,
address, one photo) with a short **Prices** list. The full menu, typical prices and photos are on
your phone. See `../README.md` for the iPhone/CarPlay version.

## What you see

| Where | What it shows |
|---|---|
| Car screen (Android Auto) | Restaurant card: name, rating and category, address, at most one Google photo with its credit. **Prices** opens a short text list (up to 6 rows) when we have typical prices for that chain. **Menu on phone** works only while parked. **Not here?** picks a neighbor (strip malls). No food-photo grid. |
| Phone | **Prices** tab: typical prices for known chains, marked "Typical prices. They vary by location." **Menu** tab: the chain's official menu page shown inside the app, plus Maps, website and search buttons. **Photos** tab: up to 4 Google photos, each with the photographer's name. |

## Auto-detect drive-thrus (switch on the phone, default off)

- **Off:** nothing watches your location. Tap **Check now** on the car screen or on the phone when you
  are in line.
- **On, in the car:** the app watches while it is open on the car screen, and the card appears by itself.
  An arrival banner can also show over Maps (setting "Car banner (experimental)", default on).
- **On, phone only:** watching starts when you flip the switch, tap **Start watching on this phone**, or tap
  the home-screen widget. It stops by itself after 15 minutes parked or 4 hours, or when you tap **Stop**.

| Piece | Detail |
|---|---|
| "In line" detection | GPS speed under ~6.7 mph for 20 s at a spot it hasn't checked yet → lookup. Creeping forward doesn't reset it |
| Lookup | Google Places API (New) Nearby Search, `fast_food_restaurant` only, within 45 m, nearest first |
| Red-light filter | If you drive off above ~13 mph within 45 s of an auto-detected card, it was a red light: the card is withdrawn and that spot is ignored until watching stops |
| Location | Used only while watching or checking. Never saved to disk |

**About menus:** no public API returns item-by-item menus for every restaurant. Known chains open
their official online menu (`../shared/chain_menus.json`). Typical prices come from a list we keep
by hand (`../shared/chain_prices.json`). Other places link to Google Maps' Menu tab and the website.

## Try it without a drive-thru

Phone: **Try a demo**. Car screen: **Demo** in the action strip. Both show a sample restaurant with
typical prices; the phone also shows that chain's menu. No location or Places lookups.

## Places API key

Builds from the release workflow have the key built in, so users never see a key screen.

- **Local builds:** put `PLACES_API_KEY=...` in `pullup-menu/android/local.properties` (never committed).
- **GitHub builds:** add the repository secret `PLACES_API_KEY`.
- A build without a key asks for one on the phone.

To make a key: <https://console.cloud.google.com/> → create a project → enable **Places API (New)** →
**Credentials → Create credentials → API key**. Billing must be enabled; Google's free monthly usage
covers light use (each stop is 1 Nearby Search plus a few photo loads). Check Google's Places pricing
page and set a budget alert. The app sends its package name and signing-certificate SHA-1 with every
request, so the key can be restricted to **Android apps**: package `com.wdwy90.pullupmenu` plus the
SHA-1 of the "App signing key certificate" from Play Console → Test and release → App integrity.

## Install (Google Play Internal testing)

Android Auto only shows this kind of app (Car App Library) in a real car when it was installed from
Google Play. Android Auto's "Unknown sources" setting does not apply to Car App Library apps
([Google: Test Android apps for cars](https://developer.android.com/training/cars/testing)), so a
sideloaded APK will not appear in the car.

1. Build a signed bundle (below) and upload it in Play Console → **Test and release → Testing →
   Internal testing → Create new release**. Internal testing has no Android Auto review.
2. Add your Google account on the **Testers** tab, open the opt-in link on the phone, and install from
   the Play Store. Uninstall any sideloaded copy first (different signing key).
3. Open **Pull Up Menu** on the phone and tap **Grant location & notifications**.
4. Phone **Settings → Android Auto → Customize launcher**: make sure Pull Up Menu is checked.
5. In the car, open Pull Up Menu from the Android Auto launcher.

## Builds

- **Release bundle for Play:** GitHub → **Actions → Build Pull Up Menu release bundle → Run workflow**
  (also runs on every push to this branch). Download the `pullup-menu-play-bundle` artifact, a zip
  holding `PullUpMenu-1.3-<versionCode>.aab`. It needs the repository secrets
  `UPLOAD_KEY_ZIP_BASE64` (base64 of the upload-key backup zip: keystore + `keystore.properties`) and
  `PLACES_API_KEY`; without the key zip it skips. versionCode is the run number + 3, so every
  upload is higher than the last. The job fails if the bundle would be signed with the debug key.
- **Debug APK:** **Actions → Build Pull Up Menu APK** (`pullup-menu-apk` artifact). Runs tests and lint.
  No built-in key. Good for the phone and the Desktop Head Unit, not for a real car.
- **Locally:** `cd pullup-menu/android && ./gradlew assembleDebug` (needs the Android SDK). For
  `bundleRelease`, put the upload keystore and `keystore.properties` (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`) in `pullup-menu/android/`. Neither is ever committed.

## Limits to know

- **Android Auto limits the car screen:** about 6 rows, text cut short while driving, and the phone
  menu button works only while parked.
- **Red lights:** the red-light filter removes most false alarms, but a long stop within 45 m of a
  fast-food place can still pop a card. Tune `dwellMs` in `ArrivalDetector.kt`, `RedLightFilter`, or
  the radius in `Prefs.kt`.
- Drive-thru-only places that Google doesn't tag "fast food" (some coffee shops) won't trigger.
- Prices are typical, not your store's. They vary by location and change over time.

## Code map
- `core/DriveWatcher.kt` – one GPS stream shared by car and phone; runs detection and lookups
- `core/ArrivalDetector.kt` – "did we pull up?" logic (unit tested)
- `core/RedLightFilter.kt` – withdraws a card when you drive off quickly (unit tested)
- `core/DriveEndDetector.kt` – stops phone watching after 15 min parked or 4 h (unit tested)
- `core/PlacesClient.kt`, `core/PlacesParser.kt` – Places API calls/parsing, photo credits (parser unit tested)
- `core/AppIdentity.kt` – package name and signing SHA-1 headers for key restriction
- `core/ChainMenus.kt`, `core/ChainPrices.kt` – official menu links and typical prices from `../shared/`
- `core/MenuRepository.kt` – shared state between car screen and phone, demo mode
- `core/Prefs.kt`, `core/Notifier.kt` – settings, arrival and watching notifications
- `car/` – Android Auto screens: `HomeScreen`, `RestaurantScreen` (card), `ItemListScreen` (full item list by menu section), `ChooserScreen`
- `phone/` – setup screen (`MainActivity`), menu/prices/photos (`RestaurantActivity`), phone watching
  (`ArrivalService`), home-screen widget (`DriveWidget`, `StartWatchingActivity`)
