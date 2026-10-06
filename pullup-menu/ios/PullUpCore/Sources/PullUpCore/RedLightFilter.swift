import Foundation

/// Catches false alarms from auto-detect: a red light next to a fast-food place looks like a
/// drive-thru stop for a while. A real drive-thru line never reaches `driveOffSpeedMps` (~13 mph)
/// within `window` of the card appearing; a green light does. Such spots are remembered (in
/// memory only, at most `maxSpots`) and ignored for the rest of the drive. Mirrors the Android version.
public struct RedLightFilter {
    private struct Point { let lat: Double, lng: Double, time: TimeInterval }

    let driveOffSpeedMps: Double
    let window: TimeInterval
    let ignoreRadiusM: Double
    let maxSpots: Int

    private var trigger: Point?
    private var prev: Point?
    private var spots: [Point] = []

    public init(driveOffSpeedMps: Double = 6.0, window: TimeInterval = 45,
                ignoreRadiusM: Double = 40, maxSpots: Int = 50) {
        self.driveOffSpeedMps = driveOffSpeedMps
        self.window = window
        self.ignoreRadiusM = ignoreRadiusM
        self.maxSpots = maxSpots
    }

    /// An automatic lookup just ran at this spot.
    public mutating func onTrigger(lat: Double, lng: Double, time: TimeInterval) {
        trigger = Point(lat: lat, lng: lng, time: time)
    }

    /// Returns true once when the car drives off fast within `window` after a trigger.
    /// The trigger spot is then ignored from now on.
    public mutating func onSample(lat: Double, lng: Double, time: TimeInterval, speedMps: Double?) -> Bool {
        var speed = speedMps.flatMap { $0 >= 0 ? $0 : nil }
        if speed == nil, let p = prev, time > p.time {
            speed = Geo.distanceMeters(p.lat, p.lng, lat, lng) / (time - p.time)
        }
        prev = Point(lat: lat, lng: lng, time: time)

        guard let t = trigger else { return false }
        let elapsed = time - t.time
        if elapsed > window {
            trigger = nil
            return false
        }
        guard elapsed >= 0, let speed, speed > driveOffSpeedMps else { return false }

        trigger = nil
        if spots.count >= maxSpots { spots.removeFirst() }
        spots.append(t)
        return true
    }

    public func isIgnored(lat: Double, lng: Double) -> Bool {
        spots.contains { Geo.distanceMeters($0.lat, $0.lng, lat, lng) <= ignoreRadiusM }
    }

    public mutating func clear() {
        trigger = nil
        prev = nil
        spots.removeAll()
    }
}
