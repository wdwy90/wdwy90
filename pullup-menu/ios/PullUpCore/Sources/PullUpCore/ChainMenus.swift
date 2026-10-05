import Foundation

/// Maps fast-food chain names to their official online menu (shared/chain_menus.json).
public struct ChainMenus: Sendable {
    private struct File: Decodable { let chains: [Chain] }
    private struct Chain: Decodable, Sendable { let match: [String]; let menuUrl: String }

    private let chains: [Chain]

    public init(json: Data) throws {
        chains = try JSONDecoder().decode(File.self, from: json).chains
    }

    public func menuUrl(for placeName: String) -> String? {
        let padded = " \(Self.normalize(placeName)) "
        return chains.first { c in c.match.contains { padded.contains(" \($0) ") } }?.menuUrl
    }

    /// "McDonald's" -> "mcdonalds", "Chick-fil-A #123" -> "chickfila 123".
    public static func normalize(_ s: String) -> String {
        let dropped: Set<Character> = ["'", "’", ".", "-"]
        var out = ""
        var lastWasSpace = true
        for ch in s.lowercased() where !dropped.contains(ch) {
            if ch.isASCII && (ch.isLetter || ch.isNumber) {
                out.append(ch)
                lastWasSpace = false
            } else if !lastWasSpace {
                out.append(" ")
                lastWasSpace = true
            }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }
}
