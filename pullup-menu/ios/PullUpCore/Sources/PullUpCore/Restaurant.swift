import Foundation

/// A Google place photo and the credit Google asks us to show with it.
public struct PlacePhoto: Equatable, Sendable {
    /// Places API photo resource name ("places/xxx/photos/yyy").
    public let name: String
    public let authorName: String?
    public let authorUri: String?

    public init(name: String, authorName: String? = nil, authorUri: String? = nil) {
        self.name = name; self.authorName = authorName; self.authorUri = authorUri
    }
}

public struct Restaurant: Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let address: String
    public let lat: Double
    public let lng: Double
    public let rating: Double?
    public let category: String?
    public let photos: [PlacePhoto]
    /// Places API photo resource names ("places/xxx/photos/yyy").
    public var photoNames: [String] { photos.map(\.name) }
    public let websiteUri: String?
    public let mapsUri: String?
    public var distanceMeters: Double
    /// Official chain menu page, when this is a known chain.
    public var menuUrl: String?
    /// The chain's menu item list, when we have one.
    public var items: ItemList?

    public init(id: String, name: String, address: String, lat: Double, lng: Double,
                rating: Double?, category: String?, photos: [PlacePhoto],
                websiteUri: String?, mapsUri: String?, distanceMeters: Double = 0, menuUrl: String? = nil,
                items: ItemList? = nil) {
        self.id = id; self.name = name; self.address = address; self.lat = lat; self.lng = lng
        self.rating = rating; self.category = category; self.photos = photos
        self.websiteUri = websiteUri; self.mapsUri = mapsUri
        self.distanceMeters = distanceMeters; self.menuUrl = menuUrl; self.items = items
    }
}
