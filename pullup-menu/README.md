# Pull Up Menu — drive-thru menus for Android Auto & CarPlay

Get in line at a **fast-food drive-thru**. After ~15 seconds the app recognizes the restaurant and
shows its menu: in the car display, plus the full menu (official chain site + photos) on your phone.

| | Android | iPhone |
|---|---|---|
| Folder | [`android/`](android/README.md) | [`ios/`](ios/README.md) |
| In-car display | **Android Auto app**: photo grid of the restaurant's menu/food | **CarPlay Dashboard Live Activity** (iOS 26+): restaurant name, rating, "Full menu on iPhone" |
| Phone | Notification → full menu, photos | Pops up the menu screen; Lock Screen / Dynamic Island Live Activity |
| Install | Sideload APK (no Mac needed) | Build in Xcode on a Mac (free Apple ID works, re-sign every 7 days; $99/yr account avoids that) |

## Shared behavior
- **Fast food only:** Google Places type `fast_food_restaurant`, within 60 m (covers a drive-thru
  lane wrapped around the building).
- **"In line" detection:** under ~6.7 mph for 15 s at a spot not already checked. Creeping forward a
  car length doesn't reset the timer; driving off does.
- **Menus:** 33 major chains link straight to their official online menu (with pictures) —
  [`shared/chain_menus.json`](shared/chain_menus.json), used by both apps. Non-chains fall back to
  Google Maps' menu tab, the website, and Google photos.
- **"Not here?"** picks a neighbor when two drive-thrus share a lot.
- Needs a Google Places API key (Places API (New)); same key works for both apps.

## Why CarPlay is a Live Activity, not a CarPlay app
Apple only lets approved apps draw their own CarPlay screens, and the only fitting category
("Quick food ordering") is reserved for a restaurant's own ordering app and forbids showing a full
menu. Per Apple's [CarPlay Developer Guide](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf)
(June 2026), even the Xcode Simulator needs that entitlement. Live Activities, however, appear on the
CarPlay Dashboard for any app on iOS 26 with no approval — so that's what this uses. The full photo
menu is on the iPhone.
