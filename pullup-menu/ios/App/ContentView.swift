import CoreLocation
import PullUpCore
import SwiftUI
import UserNotifications

struct ContentView: View {
    @EnvironmentObject private var store: MenuStore
    @StateObject private var permissions = Permissions()
    @State private var keyDraft = ""
    @State private var presented: Restaurant?
    @State private var lastPresentedId: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Get in line at a fast-food drive-thru. After about 20 seconds the menu pops up here, and the restaurant shows on your Lock Screen and CarPlay Dashboard.")
                        .font(.callout)
                    Button("Try a demo") {
                        store.showDemo()
                        if case let .found(r, _) = store.state { presented = r }
                    }
                }

                if !store.hasBuiltInKey {
                    Section("Google Places API key") {
                        TextField("AIza…", text: $keyDraft)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.system(.body, design: .monospaced))
                        Button("Save key") { store.apiKey = keyDraft }
                            .disabled(keyDraft == store.apiKey)
                    }
                }

                Section("Permissions") {
                    LabeledContent("Location", value: permissions.locationLabel)
                    if permissions.location == .notDetermined {
                        Button("Allow location") { permissions.requestWhenInUse() }
                    } else if permissions.location == .authorizedWhenInUse {
                        Button("Allow “Always” (needed for CarPlay auto-start)") { permissions.requestAlways() }
                    }
                    Button("Allow notifications") { permissions.requestNotifications() }
                }

                Section {
                    Toggle("Auto-detect drive-thrus", isOn: Binding(
                        get: { store.isWatching },
                        set: { $0 ? store.startWatching() : store.stopWatching() }
                    ))
                    Button("What drive-thru am I at?") { store.checkNow() }
                    Text(statusText).foregroundStyle(.secondary)
                    if case let .found(r, _) = store.state {
                        Button("Show \(r.name) menu") { presented = r }
                    }
                } header: {
                    Text("Use it")
                } footer: {
                    Text("To start automatically in the car: Shortcuts app → Automation → New → CarPlay → Connects → Run Immediately → “Start watching”. Add a second automation for Disconnects → “Stop watching”.")
                }
            }
            .navigationTitle("Pull Up Menu")
            .onAppear { if !store.hasBuiltInKey { keyDraft = store.apiKey } }
            .onReceive(store.$state) { s in
                if case let .found(r, _) = s, r.id != lastPresentedId {
                    lastPresentedId = r.id
                    presented = r
                }
            }
            .sheet(item: $presented) { r in
                NavigationStack { RestaurantView(restaurant: r) }
            }
        }
    }

    private var statusText: String {
        switch store.state {
        case .idle: return store.isWatching ? "Watching…" : "Ready."
        case .searching: return "Looking up where you are…"
        case let .found(r, _): return "You're at \(r.name)."
        case .nothingNearby: return "No fast food within \(Int(store.radiusMeters * 3.281)) ft."
        case let .error(msg): return msg
        }
    }
}

/// Location + notification permission state.
final class Permissions: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published var location: CLAuthorizationStatus

    override init() {
        location = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    var locationLabel: String {
        switch location {
        case .authorizedAlways: return "Always"
        case .authorizedWhenInUse: return "While using"
        case .denied, .restricted: return "Denied — enable in Settings"
        default: return "Not set"
        }
    }

    func requestWhenInUse() { manager.requestWhenInUseAuthorization() }
    func requestAlways() { manager.requestAlwaysAuthorization() }

    func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        DispatchQueue.main.async { self.location = status }
    }
}
