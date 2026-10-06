import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Thin client for Google Places API (New).
public struct PlacesClient: Sendable {
    public struct APIError: LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    private static let base = "https://places.googleapis.com/v1"
    /// Fast food only: this is what drive-thrus are tagged as in Google Places.
    static let foodTypes = ["fast_food_restaurant"]
    static let fieldMask =
        "places.id,places.displayName,places.formattedAddress,places.shortFormattedAddress," +
        "places.location,places.rating,places.primaryTypeDisplayName,places.photos," +
        "places.websiteUri,places.googleMapsUri"

    let apiKey: String
    /// Sent as X-Ios-Bundle-Identifier so the key can be restricted to this app in Google Cloud.
    let bundleId: String?
    public init(apiKey: String, bundleId: String? = nil) { self.apiKey = apiKey; self.bundleId = bundleId }

    public func nearbyFastFood(lat: Double, lng: Double, radiusM: Double) async throws -> [Restaurant] {
        var req = URLRequest(url: URL(string: "\(Self.base)/places:searchNearby")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(apiKey, forHTTPHeaderField: "X-Goog-Api-Key")
        req.setValue(Self.fieldMask, forHTTPHeaderField: "X-Goog-FieldMask")
        if let bundleId { req.setValue(bundleId, forHTTPHeaderField: "X-Ios-Bundle-Identifier") }
        let body: [String: Any] = [
            "includedTypes": Self.foodTypes,
            "maxResultCount": 10,
            "rankPreference": "DISTANCE",
            "locationRestriction": ["circle": [
                "center": ["latitude": lat, "longitude": lng],
                "radius": radiusM,
            ]],
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
                .flatMap { $0["error"] as? [String: Any] }?["message"] as? String
            throw APIError(message: "Places API error \(code): \(msg ?? "unknown")")
        }
        return try PlacesParser.parseNearby(data, fromLat: lat, fromLng: lng)
    }

    /// Direct image URL for a place photo (the endpoint redirects to the image).
    public func photoURL(_ photoName: String, maxWidthPx: Int) -> URL? {
        var c = URLComponents(string: "\(Self.base)/\(photoName)/media")
        c?.queryItems = [
            URLQueryItem(name: "maxWidthPx", value: String(maxWidthPx)),
            URLQueryItem(name: "key", value: apiKey),
        ]
        return c?.url
    }
}
