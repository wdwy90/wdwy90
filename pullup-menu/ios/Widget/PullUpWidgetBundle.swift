import ActivityKit
import SwiftUI
import WidgetKit

@main
struct PullUpWidgetBundle: WidgetBundle {
    var body: some Widget {
        DriveThruLiveActivity()
    }
}

struct DriveThruLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DriveThruAttributes.self) { context in
            DriveThruView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: icon(context.state)).font(.title2).foregroundStyle(.orange)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.title).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
            } compactLeading: {
                Image(systemName: icon(context.state)).foregroundStyle(.orange)
            } compactTrailing: {
                Text(context.state.phase == .found ? "Menu" : "…").font(.caption2)
            } minimal: {
                Image(systemName: icon(context.state)).foregroundStyle(.orange)
            }
        }
        // .small is the size CarPlay Dashboard (and Apple Watch) uses.
        .supplementalActivityFamilies([.small, .medium])
    }
}

private func icon(_ s: DriveThruAttributes.ContentState) -> String {
    s.phase == .found ? "takeoutbag.and.cup.and.straw.fill" : "car.fill"
}

struct DriveThruView: View {
    @Environment(\.activityFamily) private var family
    let state: DriveThruAttributes.ContentState

    var body: some View {
        switch family {
        case .small:
            // CarPlay Dashboard / Watch: keep it to a glance.
            VStack(alignment: .leading, spacing: 2) {
                Label(state.phase == .found ? "Drive-thru" : "Pull Up Menu", systemImage: icon(state))
                    .font(.caption2).foregroundStyle(.orange)
                Text(state.title).font(.headline).lineLimit(2).minimumScaleFactor(0.7)
                Text(state.detail).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            HStack(spacing: 12) {
                Image(systemName: icon(state)).font(.largeTitle).foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 4) {
                    Text(state.title).font(.headline).foregroundStyle(.white).lineLimit(1)
                    Text(state.detail).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .padding()
        }
    }
}
