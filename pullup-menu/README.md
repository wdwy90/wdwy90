# Pull Up Menu: drive-thru menus for Android Auto & CarPlay

Get in line at a **fast-food drive-thru** and tap **Check now** (or turn on auto-detect and the app recognizes the restaurant after a short wait):
the car display shows a restaurant card, and your phone shows the full menu (official chain site + photos).

| | Android | iPhone |
|---|---|---|
| Folder | [`android/`](android/README.md) | [`ios/`](ios/README.md) |
| In-car display | **Android Auto app**: restaurant card (name, address, open or closed, rating, one photo) + the chain's menu categories, then item names (no prices) | **CarPlay Dashboard Live Activity** (iOS 26+): restaurant name, rating, "Full menu on iPhone" |
| Phone | Menu shown inside the app, item names for known chains (no prices), photos with credits; auto-detect switch (off by default) and home-screen widget | Pops up the menu screen; Lock Screen / Dynamic Island Live Activity |
| Install | Google Play testing track (Internal testing). Sideloaded copies don't show in the car | Build in Xcode on a Mac (free Apple ID works, re-sign every 7 days; $99/yr account avoids that) |

## Shared behavior
- **Fast food only:** Google Places type `fast_food_restaurant`, within 60 m (covers a drive-thru
  lane wrapped around the building). Android uses 45 m.
- **"In line" detection:** under ~6.7 mph for 15 s at a spot not already checked. Creeping forward a
  car length doesn't reset the timer; driving off does. Android waits 20 s and withdraws the card if
  you drive off above ~13 mph within 45 s (a red light, not a drive-thru line).
- **Menus:** 33 major chains link straight to their official online menu (with pictures):
  [`shared/chain_menus.json`](shared/chain_menus.json), used by both apps. Non-chains fall back to
  Google Maps' menu tab, the website, and Google photos.
- **"Not here?"** picks a neighbor when two drive-thrus share a lot.
- Needs a Google Places API key (Places API (New)). Android release builds have one built in; once
  that key is restricted to the Android app, the iPhone app needs its own key.

## Why CarPlay is a Live Activity, not a CarPlay app
Apple only lets approved apps draw their own CarPlay screens, and the only fitting category
("Quick food ordering") is reserved for a restaurant's own ordering app and forbids showing a full
menu. Per Apple's [CarPlay Developer Guide](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf)
(June 2026), even the Xcode Simulator needs that entitlement. Live Activities, however, appear on the
CarPlay Dashboard for any app on iOS 26 with no approval. That's what this uses. The full photo
menu is on the iPhone.
