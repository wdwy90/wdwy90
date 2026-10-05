import AppIntents

/// Lets a Shortcuts automation ("When CarPlay connects") start watching automatically.
/// LiveActivityIntent allows starting the Live Activity without opening the app.
struct StartWatchingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Start watching for drive-thrus"

    @MainActor
    func perform() async throws -> some IntentResult {
        MenuStore.shared.startWatching()
        return .result()
    }
}

struct StopWatchingIntent: AppIntent {
    static let title: LocalizedStringResource = "Stop watching for drive-thrus"

    @MainActor
    func perform() async throws -> some IntentResult {
        MenuStore.shared.stopWatching()
        return .result()
    }
}

struct PullUpShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartWatchingIntent(),
            phrases: ["Start \(.applicationName)", "Watch for drive-thrus with \(.applicationName)"],
            shortTitle: "Start watching",
            systemImageName: "car.fill"
        )
        AppShortcut(
            intent: StopWatchingIntent(),
            phrases: ["Stop \(.applicationName)"],
            shortTitle: "Stop watching",
            systemImageName: "stop.circle"
        )
    }
}
