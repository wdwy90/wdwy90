import PullUpCore
import SwiftUI
import WebKit

/// Phone screen: the chain's item list, its menu page in-app, and Google photos with credits.
struct RestaurantView: View {
    enum Tab: String, CaseIterable, Identifiable {
        case items = "Items", menu = "Menu", photos = "Photos"
        var id: Self { self }
    }

    @EnvironmentObject private var store: MenuStore
    @Environment(\.dismiss) private var dismiss
    let restaurant: Restaurant
    @State private var tab: Tab

    init(restaurant: Restaurant) {
        self.restaurant = restaurant
        _tab = State(initialValue: restaurant.items != nil ? .items : .menu)
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                if !details.isEmpty { Text(details).font(.subheadline).foregroundStyle(.secondary) }
                if case let .found(_, others) = store.state, !others.isEmpty {
                    Menu("Not here?") {
                        ForEach(others) { r in
                            Button("\(r.name) (\(Int(r.distanceMeters * 3.281)) ft)") {
                                store.choose(r)
                                dismiss()
                            }
                        }
                    }
                    .font(.subheadline)
                }
                Picker("View", selection: $tab) {
                    ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
            .padding([.horizontal, .top])
            .padding(.bottom, 8)

            switch tab {
            case .items: ItemsTab(list: restaurant.items)
            case .menu: MenuTab(url: menuURL)
            case .photos: PhotosTab(restaurant: restaurant)
            }
        }
        .navigationTitle(restaurant.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("Done") { dismiss() } }
    }

    private var details: String {
        [restaurant.rating.map { String(format: "★ %.1f", $0) }, restaurant.address]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }

    /// Official chain menu, else the restaurant's website, else a web search.
    private var menuURL: URL? {
        if let u = (restaurant.menuUrl ?? restaurant.websiteUri).flatMap(URL.init(string:)) { return u }
        var c = URLComponents(string: "https://www.google.com/search")
        c?.queryItems = [URLQueryItem(name: "q", value: "\(restaurant.name) \(restaurant.address) menu")]
        return c?.url
    }
}

private struct ItemsTab: View {
    let list: ItemList?

    var body: some View {
        if let list {
            List {
                ForEach(Array(sections(list.items).enumerated()), id: \.offset) { _, section in
                    Section(section.title) {
                        ForEach(section.items, id: \.name) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                if let note = item.note {
                                    Text(note).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                Section {
                    Text(list.disclaimer).font(.footnote).foregroundStyle(.secondary)
                    if let url = URL(string: list.sourceUrl) {
                        Link("Source: \(list.sourceName)", destination: url).font(.footnote)
                    }
                }
            }
            .listStyle(.insetGrouped)
        } else {
            ContentUnavailableView("No item list yet", systemImage: "list.bullet",
                                   description: Text("Try the Menu tab for this restaurant."))
        }
    }

    private struct ItemSection { let title: String; let items: [MenuItem] }

    /// Consecutive items with the same category form one section, keeping the file's order.
    private func sections(_ items: [MenuItem]) -> [ItemSection] {
        var out: [ItemSection] = []
        for item in items {
            let title = item.category ?? "Menu"
            if let last = out.last, last.title == title {
                out[out.count - 1] = ItemSection(title: title, items: last.items + [item])
            } else {
                out.append(ItemSection(title: title, items: [item]))
            }
        }
        return out
    }
}

private struct MenuTab: View {
    let url: URL?

    var body: some View {
        if let url {
            WebView(url: url).ignoresSafeArea(edges: .bottom)
        } else {
            ContentUnavailableView("No menu page", systemImage: "menucard")
        }
    }
}

/// The chain's menu page inside the app.
private struct WebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.allowsBackForwardNavigationGestures = true
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}

private struct PhotosTab: View {
    @EnvironmentObject private var store: MenuStore
    let restaurant: Restaurant

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if restaurant.photos.isEmpty {
                    Text("No photos available for this place.").foregroundStyle(.secondary)
                }
                ForEach(restaurant.photos, id: \.name) { photo in
                    VStack(alignment: .leading, spacing: 4) {
                        AsyncImage(url: store.photoURL(photo.name)) { phase in
                            switch phase {
                            case let .success(image):
                                image.resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 10))
                            case .failure:
                                EmptyView()
                            default:
                                ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                            }
                        }
                        if let author = photo.authorName {
                            if let url = photo.authorUri.flatMap(URL.init(string:)) {
                                Link("Photo: \(author)", destination: url).font(.caption)
                            } else {
                                Text("Photo: \(author)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Text("Photos from Google").font(.caption).foregroundStyle(.secondary)
            }
            .padding()
        }
    }
}
