import CoreLocation
import PullUpCore

/// Streams GPS fixes into an `ArrivalDetector` and reports when the car is waiting
/// somewhere new. Keeps running in the background while watching.
@MainActor
final class LocationWatcher {
    private let onArrival: (Double, Double) -> Void
    private var detector = ArrivalDetector()
    private var task: Task<Void, Never>?
    private var backgroundSession: CLBackgroundActivitySession?
    private(set) var last: CLLocation?

    init(onArrival: @escaping (Double, Double) -> Void) {
        self.onArrival = onArrival
    }

    func start() {
        guard task == nil else { return }
        // Keeps location flowing when the app is in the background (e.g. phone locked on CarPlay).
        backgroundSession = CLBackgroundActivitySession()
        task = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.automotiveNavigation) {
                    guard let self else { return }
                    if Task.isCancelled { return }
                    if let loc = update.location { self.handle(loc) }
                }
            } catch {}
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        backgroundSession?.invalidate()
        backgroundSession = nil
    }

    func resetDetector() { detector.reset() }

    func currentLocation() async -> CLLocation? {
        if let last, -last.timestamp.timeIntervalSinceNow < 30 { return last }
        do {
            for try await update in CLLocationUpdate.liveUpdates() {
                if let loc = update.location { last = loc; return loc }
            }
        } catch {}
        return nil
    }

    private func handle(_ loc: CLLocation) {
        last = loc
        let sample = ArrivalDetector.Sample(
            lat: loc.coordinate.latitude, lng: loc.coordinate.longitude,
            time: loc.timestamp.timeIntervalSince1970,
            speedMps: loc.speed >= 0 ? loc.speed : nil
        )
        if detector.onSample(sample) {
            onArrival(loc.coordinate.latitude, loc.coordinate.longitude)
        }
    }
}
