import ActivityKit

/// Live Activity shown on the Lock Screen, Dynamic Island and the CarPlay Dashboard (iOS 26+).
struct DriveThruAttributes: ActivityAttributes {
    enum Phase: String, Codable, Hashable {
        case watching, found
    }

    struct ContentState: Codable, Hashable {
        var phase: Phase
        var title: String
        var detail: String
    }

    static let watching = ContentState(
        phase: .watching,
        title: "Watching for drive-thrus",
        detail: "Get in line — the menu will pop up"
    )
}
