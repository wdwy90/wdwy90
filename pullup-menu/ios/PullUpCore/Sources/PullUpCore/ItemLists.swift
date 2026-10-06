import Foundation

/// One menu item on the Items tab. Names only: prices vary by store, so none are shown.
public struct MenuItem: Equatable, Sendable {
    public let name: String
    /// Menu section like "Burgers".
    public let category: String?
    /// Caveat like "Seasonal, may not be available now".
    public let note: String?

    public init(name: String, category: String? = nil, note: String? = nil) {
        self.name = name; self.category = category; self.note = note
    }
}

/// A chain's menu item list (shared/chain_prices.json), same rules as the Android app.
public struct ItemList: Equatable, Sendable {
    public let chain: String
    public let items: [MenuItem]
    /// Display date, e.g. "Oct 6, 2026".
    public let checked: String
    public let sourceName: String
    public let sourceUrl: String

    public var disclaimer: String {
        "Menu items from \(sourceName). Availability varies by location. Checked \(checked)."
    }
}

/// Looks up a chain's item list by place name, matching like `ChainMenus`.
public struct ChainItemLists: Sendable {
    static let maxItems = 150

    private struct Chain: Sendable { let match: [String]; let list: ItemList }
    private let chains: [Chain]

    public init(json: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              let arr = root["chains"] as? [[String: Any]] else {
            throw CocoaError(.coderReadCorrupt)
        }
        let checked = root["checked"] as? String ?? ""
        chains = arr.compactMap { c in
            guard let chain = c["chain"] as? String, let match = c["match"] as? [String] else { return nil }
            // The full menu list; advertised deals are used only when a chain has no menu list.
            var raw = Self.items(c["menuItems"])
            if raw.isEmpty { raw = Self.items(c["items"]) }
            var seen = Set<String>()
            let items = raw.filter { seen.insert(ChainMenus.normalize($0.name)).inserted }
            guard !items.isEmpty else { return nil }
            let source = Self.text(c["menuSourceName"]) ?? Self.text(c["sourceName"]) ?? ""
            let url = Self.text(c["menuSourceUrl"]) ?? Self.text(c["sourceUrl"]) ?? ""
            return Chain(match: match, list: ItemList(
                chain: chain,
                items: Array(items.prefix(Self.maxItems)),
                checked: Self.text(c["checked"]) ?? checked,
                sourceName: source,
                sourceUrl: url
            ))
        }
    }

    public func list(for placeName: String) -> ItemList? {
        let padded = " \(ChainMenus.normalize(placeName)) "
        return chains.first { c in c.match.contains { padded.contains(" \($0) ") } }?.list
    }

    /// Chain used by demo mode.
    public var demoChainName: String? { chains.first?.list.chain }

    private static func items(_ value: Any?) -> [MenuItem] {
        (value as? [[String: Any]] ?? []).compactMap { o in
            guard let name = text(o["name"]) else { return nil }
            return MenuItem(name: name, category: text(o["category"]), note: text(o["note"]))
        }
    }

    private static func text(_ value: Any?) -> String? {
        guard let s = value as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return s
    }
}
