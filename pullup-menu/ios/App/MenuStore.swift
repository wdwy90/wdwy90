import CoreLocation
import Foundation
import PullUpCore
import UserNotifications

/// App-wide state: watching, lookups, the Live Activity and notifications.
@MainActor
final class MenuStore: ObservableObject {
    static let shared = MenuStore()

    enum State: Equatable {
        case idle
        case searching
        case found(Restaurant, others: [Restaurant])
        case nothingNearby
        case error(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var isWatching = false

    /// How close (meters) the restaurant must be; matches the Android app.
    let radiusMeters = 45.0

    private lazy var watcher = LocationWatcher(
        onArrival: { [weak self] lat, lng in
            Task { await self?.lookup(lat: lat, lng: lng) }
        },
        onFalseAlarm: { [weak self] in self?.falseAlarm() }
    )

    private let chainMenus: ChainMenus? = Bundle.main.url(forResource: "chain_menus", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }
        .flatMap { try? ChainMenus(json: $0) }

    private let itemLists: ChainItemLists? = Bundle.main.url(forResource: "chain_prices", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }
        .flatMap { try? ChainItemLists(json: $0) }

    /// Key built into the app (PLACES_API_KEY build setting), or empty.
    let builtInKey = (Bundle.main.object(forInfoDictionaryKey: "PlacesApiKey") as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

    var hasBuiltInKey: Bool { !builtInKey.isEmpty }

    /// A key pasted in the app wins over the built-in one.
    var apiKey: String {
        get { UserDefaults.standard.string(forKey: "placesApiKey").flatMap { $0.isEmpty ? nil : $0 } ?? builtInKey }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "placesApiKey") }
    }

    private var client: PlacesClient { PlacesClient(apiKey: apiKey, bundleId: Bundle.main.bundleIdentifier) }

    private let locationManager = CLLocationManager()

    private var hasLocationPermission: Bool {
        [.authorizedAlways, .authorizedWhenInUse].contains(locationManager.authorizationStatus)
    }

    func startWatching() {
        guard !isWatching else { return }
        if locationManager.authorizationStatus == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
        watcher.start()
        isWatching = true
        LiveActivityManager.start()
    }

    func stopWatching() {
        watcher.stop()
        isWatching = false
        Task { await LiveActivityManager.end() }
    }

    /// Manual "what drive-thru am I at?".
    func checkNow() {
        guard hasLocationPermission else {
            state = .error("Allow location access first.")
            return
        }
        Task {
            guard let loc = await watcher.currentLocation() else {
                state = .error("Couldn't get your location.")
                return
            }
            watcher.markLookedUp(loc)
            await lookup(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
    }

    func lookup(lat: Double, lng: Double) async {
        guard !apiKey.isEmpty else {
            state = .error("Add your Google Places API key first.")
            return
        }
        state = .searching
        do {
            let results = try await client
                .nearbyFastFood(lat: lat, lng: lng, radiusM: radiusMeters)
                .map(withChainInfo)
            if let first = results.first {
                show(first, others: Array(results.dropFirst()), alert: true)
            } else {
                state = .nothingNearby
                await LiveActivityManager.update(.init(
                    phase: .watching, title: "Watching for drive-thrus",
                    detail: "No fast food at your last stop"), alert: false)
            }
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// User picked a different nearby place ("Not here?").
    func choose(_ r: Restaurant) {
        guard case let .found(current, others) = state else { return }
        show(r, others: ([current] + others).filter { $0.id != r.id }, alert: false)
    }

    func photoURL(_ photoName: String, maxWidthPx: Int = 1200) -> URL? {
        client.photoURL(photoName, maxWidthPx: maxWidthPx)
    }

    /// Shows a sample restaurant without driving or a location fix.
    func showDemo() {
        let chain = itemLists?.demoChainName ?? "McDonald's"
        let r = withChainInfo(Restaurant(
            id: "demo", name: "\(chain) (demo)", address: "Sample restaurant",
            lat: 0, lng: 0, rating: nil, category: "Fast Food Restaurant", photos: [],
            websiteUri: nil, mapsUri: nil))
        show(r, others: [], alert: false)
    }

    private func withChainInfo(_ r: Restaurant) -> Restaurant {
        var r = r
        r.menuUrl = chainMenus?.menuUrl(for: r.name)
        r.items = itemLists?.list(for: r.name)
        return r
    }

    /// The car drove off quickly after an automatic match: it was a red light, not a drive-thru.
    private func falseAlarm() {
        guard case .found = state else { return }
        state = .idle
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["arrival"])
        Task { await LiveActivityManager.update(DriveThruAttributes.watching, alert: false) }
    }

    private func show(_ r: Restaurant, others: [Restaurant], alert: Bool) {
        state = .found(r, others: others)
        let detail = [
            r.rating.map { String(format: "★ %.1f", $0) },
            r.items != nil ? "Menu items on iPhone" : r.menuUrl != nil ? "Menu on iPhone" : "Photos on iPhone",
        ].compactMap { $0 }.joined(separator: " · ")
        Task {
            await LiveActivityManager.update(.init(phase: .found, title: r.name, detail: detail), alert: alert)
        }
        if alert { notify(r) }
    }

    private func notify(_ r: Restaurant) {
        let content = UNMutableNotificationContent()
        content.title = "You're at \(r.name)"
        content.body = r.items != nil || r.menuUrl != nil ? "Tap for the menu" : "Tap to see photos"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "arrival", content: content, trigger: nil))
    }
}
