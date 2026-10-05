import Foundation

/// Parses Google Places API (New) searchNearby responses.
public enum PlacesParser {
    private struct Response: Decodable { let places: [Place]? }
    private struct Text: Decodable { let text: String? }
    private struct Location: Decodable { let latitude: Double; let longitude: Double }
    private struct Photo: Decodable { let name: String? }
    private struct Place: Decodable {
        let id: String
        let displayName: Text?
        let formattedAddress: String?
        let shortFormattedAddress: String?
        let location: Location?
        let rating: Double?
        let primaryTypeDisplayName: Text?
        let photos: [Photo]?
        let websiteUri: String?
        let googleMapsUri: String?
    }

    /// Returns places nearest first.
    public static func parseNearby(_ data: Data, fromLat: Double, fromLng: Double) throws -> [Restaurant] {
        let places = try JSONDecoder().decode(Response.self, from: data).places ?? []
        return places.compactMap { p -> Restaurant? in
            guard let loc = p.location else { return nil }
            let name = p.displayName?.text ?? ""
            return Restaurant(
                id: p.id,
                name: name.isEmpty ? "Unknown restaurant" : name,
                address: nonEmpty(p.shortFormattedAddress) ?? p.formattedAddress ?? "",
                lat: loc.latitude, lng: loc.longitude,
                rating: p.rating,
                category: nonEmpty(p.primaryTypeDisplayName?.text),
                photoNames: (p.photos ?? []).compactMap { nonEmpty($0.name) },
                websiteUri: nonEmpty(p.websiteUri),
                mapsUri: nonEmpty(p.googleMapsUri),
                distanceMeters: Geo.distanceMeters(fromLat, fromLng, loc.latitude, loc.longitude)
            )
        }.sorted { $0.distanceMeters < $1.distanceMeters }
    }

    private static func nonEmpty(_ s: String?) -> String? {
        guard let s, !s.isEmpty else { return nil }
        return s
    }
}
