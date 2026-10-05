import ActivityKit
import Foundation

@MainActor
enum LiveActivityManager {
    private static var current: Activity<DriveThruAttributes>? {
        Activity<DriveThruAttributes>.activities.first
    }

    /// Must be called while the app is in the foreground, or from a LiveActivityIntent.
    static func start() {
        guard ActivityAuthorizationInfo().areActivitiesEnabled, current == nil else { return }
        _ = try? Activity.request(
            attributes: DriveThruAttributes(),
            content: ActivityContent(state: DriveThruAttributes.watching, staleDate: nil),
            pushType: nil
        )
    }

    /// Updates are allowed from the background. `alert` lights up the screen / shows a
    /// banner (on CarPlay it appears as a notification).
    static func update(_ state: DriveThruAttributes.ContentState, alert: Bool) async {
        guard let activity = current else { return }
        let content = ActivityContent(state: state, staleDate: nil)
        if alert {
            await activity.update(content, alertConfiguration: AlertConfiguration(
                title: LocalizedStringResource(stringLiteral: state.title),
                body: LocalizedStringResource(stringLiteral: state.detail),
                sound: .default
            ))
        } else {
            await activity.update(content)
        }
    }

    static func end() async {
        for activity in Activity<DriveThruAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}
