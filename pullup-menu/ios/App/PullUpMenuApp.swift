import SwiftUI

@main
struct PullUpMenuApp: App {
    @StateObject private var store = MenuStore.shared

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(store)
        }
    }
}
