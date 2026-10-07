# Pull Up Menu (Android + Android Auto)

Get in line at a fast-food drive-thru. The car screen shows a restaurant card (name, rating,
address, one photo); **Items** lists that chain's menu items by section, names only (no prices).
The official menu page and photos are on your phone. See `../README.md` for the iPhone/CarPlay
version.

## What you see

| Where | What it shows |
|---|---|
| Car screen (Android Auto) | Restaurant card: name, rating and category, address, at most one Google photo with its credit. **Items** (known chains) opens the item names by menu section. The lists take the card's place and go at most three deep, as Android Auto allows; Back on the first list brings the card back. **Menu on phone** works only while parked. **Not here?** picks a neighbor (strip malls). No food-photo grid. |
| Phone | **Items** tab: item names for known chains (no prices), with a search box, the chain's disclaimer and source. **Menu** tab: the chain's official menu page shown inside the app (links to other sites open in the browser), plus Maps, website and search buttons. **Photos** tab: up to 4 Google photos, each with the photographer's name. **Not here?** next to Navigate picks another place Google found nearby. |

## Auto-detect drive-thrus (Settings on the phone, default off)

- **Off:** nothing watches your location. Tap **Check now** on the car screen, or **Detect My
  Restaurant** on the phone, when you are in line.
- **On, in the car:** the app watches while it is open on the car screen, and the card appears by itself.
  An arrival banner can also show over Maps (setting "Car banner (experimental)", default on).
- **On, phone only (Drive Mode):** watching starts from the **Drive Mode** button on the phone's home
  screen, the "Drive Mode on this phone" setting, or the home-screen widget. It stops by itself after
  15 minutes parked or 4 hours, or when you tap **Stop** in its notification.

| Piece | Detail |
|---|---|
| "In line" detection | GPS speed under ~6.7 mph for 20 s → lookup, unless the last lookup already covered this spot. Creeping forward doesn't reset the timer. A spot counts as covered inside the 45 m circle that lookup searched; once a place is found, only stops no farther from it (the line moving up) are covered, so pulling into the drive-thru next door is looked up again |
| GPS accuracy | Fixes worse than 50 m wait for a better one; after a minute stopped the lookup runs anyway over a wider circle (up to 150 m). Needs precise location |
| Lookup | Google Places API (New) Nearby Search, `fast_food_restaurant` only, within 45 m, nearest first. A lookup that fails (no signal, Google busy) is tried again after 15, 30 and 60 s while you stay stopped |
| Red-light filter | If you drive off above ~13 mph within 45 s of an auto-detected card, it was a red light: the card is withdrawn and that spot is ignored until watching stops |
| Location | Used only while watching or checking. Never saved to disk |

**About menus:** no public API returns item-by-item menus for every restaurant. Known chains open
their official online menu (`../shared/chain_menus.json`). Item lists come from a list we keep by
hand (`../shared/chain_prices.json`; it also records advertised prices, which the app doesn't show).
Other places link to Google Maps' Menu tab and the website.

## Try it without a drive-thru

Phone: **Try a demo**. Car screen: **Demo** in the action strip. Both show a sample restaurant with
its item list; the phone also shows that chain's menu. No location or Places lookups.

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
3. Open **Pull Up Menu** on the phone and tap **Allow location** (also under Settings → Permissions).
   Choose **Precise**: approximate location can't tell neighboring drive-thrus apart.
4. Phone **Settings → Android Auto → Customize launcher**: make sure Pull Up Menu is checked.
5. In the car, open Pull Up Menu from the Android Auto launcher.

## Builds

- **Release bundle for Play:** GitHub → **Actions → Build Pull Up Menu release bundle → Run workflow**
  (also runs on every push to this branch). Download the `pullup-menu-play-bundle` artifact, a zip
  holding `PullUpMenu-<versionName>-<versionCode>.aab` (`PullUpMenu-1.8.1-…` for this version). It
  needs the repository secrets `UPLOAD_KEY_ZIP_BASE64` (base64 of the upload-key backup zip: keystore
  + `keystore.properties`) and `PLACES_API_KEY`; without the key zip it skips. versionCode is the run
  number + 3, so every upload is higher than the last. The job fails if the bundle would be signed
  with the debug key.
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
- Item lists are kept by hand from each chain's own sites and press releases. What your store sells
  varies by location and changes over time; the app shows no prices.

## Code map
- `core/DriveWatcher.kt` – one GPS stream shared by car and phone; runs detection and lookups
- `core/ArrivalDetector.kt` – "did we pull up?" logic (unit tested)
- `core/RedLightFilter.kt` – withdraws a card when you drive off quickly (unit tested)
- `core/DriveEndDetector.kt` – stops phone watching after 15 min parked or 4 h (unit tested)
- `core/PlacesClient.kt`, `core/PlacesParser.kt` – Places API calls/parsing, photo credits (unit tested)
- `core/AppIdentity.kt` – package name and signing SHA-1 headers for key restriction
- `core/ChainMenus.kt`, `core/ChainPrices.kt` – official menu links and item lists from `../shared/`
- `core/ItemGroups.kt` – groups items by menu section and pages them for the car lists (unit tested)
- `core/VisitCheck.kt` – whether a re-check while stopped still points at the place on screen (unit tested)
- `core/MenuRepository.kt` – shared state between car screen and phone, demo mode
- `core/Prefs.kt`, `core/Notifier.kt` – settings, arrival and watching notifications
- `car/` – Android Auto screens: `HomeScreen`, `RestaurantScreen` (card), `ItemListScreen` (full item list by menu section), `ChooserScreen`
- `phone/` – home screen (`MainActivity`), `SettingsActivity`, items/menu/photos (`RestaurantActivity`), phone watching
  (`ArrivalService`), home-screen widget (`DriveWidget`, `StartWatchingActivity`)
