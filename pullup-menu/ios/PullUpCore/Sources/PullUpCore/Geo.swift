import Foundation

public enum Geo {
    private static let earthRadiusM = 6_371_000.0

    public static func distanceMeters(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = pow(sin(dLat / 2), 2) +
            cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * pow(sin(dLng / 2), 2)
        return 2 * earthRadiusM * asin(sqrt(a))
    }
}
