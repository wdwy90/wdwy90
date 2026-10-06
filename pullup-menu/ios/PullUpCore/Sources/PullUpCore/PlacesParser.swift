import Foundation

/// Parses Google Places API (New) searchNearby responses.
public enum PlacesParser {
    private struct Response: Decodable { let places: [Place]? }
    private struct Text: Decodable { let text: String? }
    private struct Location: Decodable { let latitude: Double; let longitude: Double }
    private struct Author: Decodable { let displayName: String?; let uri: String? }
    private struct Photo: Decodable { let name: String?; let authorAttributions: [Author]? }
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
                photos: (p.photos ?? []).compactMap { ph -> PlacePhoto? in
                    guard let name = nonEmpty(ph.name) else { return nil }
                    // Google asks us to credit the first listed author with the photo.
                    let a = ph.authorAttributions?.first
                    return PlacePhoto(name: name, authorName: nonEmpty(a?.displayName),
                                      authorUri: nonEmpty(a?.uri).map(absoluteUrl))
                },
                websiteUri: nonEmpty(p.websiteUri),
                mapsUri: nonEmpty(p.googleMapsUri),
                distanceMeters: Geo.distanceMeters(fromLat, fromLng, loc.latitude, loc.longitude)
            )
        }.sorted { $0.distanceMeters < $1.distanceMeters }
    }

    /// Attribution links can come back as "//maps.google.com/...".
    static func absoluteUrl(_ s: String) -> String { s.hasPrefix("//") ? "https:" + s : s }

    private static func nonEmpty(_ s: String?) -> String? {
        guard let s, !s.isEmpty else { return nil }
        return s
    }
}
