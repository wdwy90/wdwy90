import Foundation

public struct Restaurant: Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let address: String
    public let lat: Double
    public let lng: Double
    public let rating: Double?
    public let category: String?
    /// Places API photo resource names ("places/xxx/photos/yyy").
    public let photoNames: [String]
    public let websiteUri: String?
    public let mapsUri: String?
    public var distanceMeters: Double
    /// Official chain menu page, when this is a known chain.
    public var menuUrl: String?

    public init(id: String, name: String, address: String, lat: Double, lng: Double,
                rating: Double?, category: String?, photoNames: [String],
                websiteUri: String?, mapsUri: String?, distanceMeters: Double = 0, menuUrl: String? = nil) {
        self.id = id; self.name = name; self.address = address; self.lat = lat; self.lng = lng
        self.rating = rating; self.category = category; self.photoNames = photoNames
        self.websiteUri = websiteUri; self.mapsUri = mapsUri
        self.distanceMeters = distanceMeters; self.menuUrl = menuUrl
    }
}
