import Foundation

/// Decides when the car is waiting in a drive-thru line: speed stays below `stopSpeedMps`
/// for `dwellSeconds`, at a spot we haven't already looked up. Mirrors the Android version.
public struct ArrivalDetector {
    public struct Sample {
        public let lat: Double, lng: Double
        public let time: TimeInterval
        /// Meters/second, or nil if the GPS didn't report one.
        public let speedMps: Double?
        public init(lat: Double, lng: Double, time: TimeInterval, speedMps: Double?) {
            self.lat = lat; self.lng = lng; self.time = time; self.speedMps = speedMps
        }
    }

    // Drive-thru lines creep forward a car length at a time, so "stopped" means
    // under ~6.7 mph rather than fully still.
    let stopSpeedMps: Double
    let dwellSeconds: TimeInterval
    let minMoveBetweenLookupsM: Double

    private var prev: Sample?
    private var stoppedSince: TimeInterval?
    private var lastLookup: (lat: Double, lng: Double)?

    public init(stopSpeedMps: Double = 3.0, dwellSeconds: TimeInterval = 15, minMoveBetweenLookupsM: Double = 100) {
        self.stopSpeedMps = stopSpeedMps
        self.dwellSeconds = dwellSeconds
        self.minMoveBetweenLookupsM = minMoveBetweenLookupsM
    }

    /// Returns true when a lookup should run for this sample's position.
    public mutating func onSample(_ s: Sample) -> Bool {
        var speed = s.speedMps.flatMap { $0 >= 0 ? $0 : nil }
        if speed == nil, let p = prev, s.time > p.time {
            speed = Geo.distanceMeters(p.lat, p.lng, s.lat, s.lng) / (s.time - p.time)
        }
        prev = s

        if (speed ?? 0) > stopSpeedMps {
            stoppedSince = nil
            return false
        }
        let since = stoppedSince ?? s.time
        stoppedSince = since
        if s.time - since < dwellSeconds { return false }

        if let l = lastLookup, Geo.distanceMeters(l.lat, l.lng, s.lat, s.lng) < minMoveBetweenLookupsM {
            return false
        }
        lastLookup = (s.lat, s.lng)
        return true
    }

    /// Forget the last lookup spot so the next stop triggers a fresh lookup.
    public mutating func reset() {
        stoppedSince = nil
        lastLookup = nil
    }
}
