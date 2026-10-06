import CoreLocation
import PullUpCore

/// Streams GPS fixes into an `ArrivalDetector` and reports when the car is waiting
/// somewhere new, skipping spots the `RedLightFilter` has flagged. Keeps running in the
/// background while watching.
@MainActor
final class LocationWatcher {
    private let onArrival: @MainActor (Double, Double) -> Void
    private let onFalseAlarm: @MainActor () -> Void
    private var detector = ArrivalDetector()
    private var redLights = RedLightFilter()
    private var task: Task<Void, Never>?
    private var backgroundSession: CLBackgroundActivitySession?
    private(set) var last: CLLocation?

    init(onArrival: @escaping @MainActor (Double, Double) -> Void, onFalseAlarm: @escaping @MainActor () -> Void) {
        self.onArrival = onArrival
        self.onFalseAlarm = onFalseAlarm
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
        detector.reset()
        redLights.clear()
    }

    /// A manual lookup ran here, so auto-detect shouldn't repeat it.
    func markLookedUp(_ loc: CLLocation) {
        detector.markLookedUp(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
    }

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
        let lat = loc.coordinate.latitude, lng = loc.coordinate.longitude
        let time = loc.timestamp.timeIntervalSince1970
        let speed = loc.speed >= 0 ? loc.speed : nil
        if redLights.onSample(lat: lat, lng: lng, time: time, speedMps: speed) {
            onFalseAlarm()
        }
        let sample = ArrivalDetector.Sample(lat: lat, lng: lng, time: time, speedMps: speed)
        if detector.onSample(sample), !redLights.isIgnored(lat: lat, lng: lng) {
            redLights.onTrigger(lat: lat, lng: lng, time: time)
            onArrival(lat, lng)
        }
    }
}
