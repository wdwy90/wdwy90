import PullUpCore
import SwiftUI

/// Full menu screen: links to the real menu plus every photo Google has.
struct RestaurantView: View {
    @EnvironmentObject private var store: MenuStore
    @Environment(\.dismiss) private var dismiss
    let restaurant: Restaurant

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(details).foregroundStyle(.secondary)

                if let url = restaurant.menuUrl.flatMap(URL.init(string:)) {
                    Link(destination: url) {
                        Label("Full menu (official site)", systemImage: "menucard")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.orange)
                }
                HStack {
                    if let url = restaurant.mapsUri.flatMap(URL.init(string:)) {
                        Link("Menu (Maps)", destination: url)
                    }
                    if let url = restaurant.websiteUri.flatMap(URL.init(string:)) {
                        Link("Website", destination: url)
                    }
                    if let url = searchURL { Link("Search menu", destination: url) }
                }
                .buttonStyle(.bordered)
                .font(.subheadline)

                if case let .found(_, others) = store.state, !others.isEmpty {
                    Menu("Not here?") {
                        ForEach(others) { r in
                            Button("\(r.name) (\(Int(r.distanceMeters * 3.281)) ft)") {
                                store.choose(r)
                                dismiss()
                            }
                        }
                    }
                }

                if restaurant.photoNames.isEmpty {
                    Text("No photos available for this place.").foregroundStyle(.secondary)
                }
                ForEach(restaurant.photoNames, id: \.self) { name in
                    AsyncImage(url: store.photoURL(name)) { phase in
                        switch phase {
                        case let .success(image):
                            image.resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 10))
                        case .failure:
                            EmptyView()
                        default:
                            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle(restaurant.name)
        .toolbar { Button("Done") { dismiss() } }
    }

    private var details: String {
        [restaurant.rating.map { String(format: "★ %.1f", $0) }, restaurant.address]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }

    private var searchURL: URL? {
        var c = URLComponents(string: "https://www.google.com/search")
        c?.queryItems = [URLQueryItem(name: "q", value: "\(restaurant.name) \(restaurant.address) menu")]
        return c?.url
    }
}
