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

    /// How close (meters) the restaurant must be; covers a drive-thru lane wrapping the building.
    let radiusMeters = 60.0

    private lazy var watcher = LocationWatcher { [weak self] lat, lng in
        Task { await self?.lookup(lat: lat, lng: lng) }
    }

    private let chainMenus: ChainMenus? = Bundle.main.url(forResource: "chain_menus", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }
        .flatMap { try? ChainMenus(json: $0) }

    var apiKey: String {
        get { UserDefaults.standard.string(forKey: "placesApiKey") ?? "" }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "placesApiKey") }
    }

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
            watcher.resetDetector()
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
            let results = try await PlacesClient(apiKey: apiKey)
                .nearbyFastFood(lat: lat, lng: lng, radiusM: radiusMeters)
                .map { r -> Restaurant in
                    var r = r
                    r.menuUrl = chainMenus?.menuUrl(for: r.name)
                    return r
                }
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
        PlacesClient(apiKey: apiKey).photoURL(photoName, maxWidthPx: maxWidthPx)
    }

    private func show(_ r: Restaurant, others: [Restaurant], alert: Bool) {
        state = .found(r, others: others)
        let detail = [
            r.rating.map { String(format: "★ %.1f", $0) },
            r.menuUrl != nil ? "Full menu on iPhone" : "Photos on iPhone",
        ].compactMap { $0 }.joined(separator: " · ")
        Task {
            await LiveActivityManager.update(.init(phase: .found, title: r.name, detail: detail), alert: alert)
        }
        if alert { notify(r) }
    }

    private func notify(_ r: Restaurant) {
        let content = UNMutableNotificationContent()
        content.title = "You're at \(r.name)"
        content.body = r.menuUrl != nil ? "Tap for the full menu" : "Tap to see menu photos"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "arrival", content: content, trigger: nil))
    }
}
