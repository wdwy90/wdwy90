package com.wdwy90.pullupmenu.core

/**
 * What the phone dashboard says about detection. Pure, so it can be unit tested; the activity maps
 * [MenuRepository.State] to [Phase] and renders the result.
 */
object StatusModel {

    enum class Phase { IDLE, SEARCHING, FOUND, DEMO, NOTHING_NEARBY, ERROR }
    enum class Tone { NEUTRAL, ACTIVE, SUCCESS, WARNING }
    enum class Action { GRANT_LOCATION, OPEN_SETTINGS, OPEN_MENU, DETECT }

    data class Input(
        val phase: Phase,
        val hasLocation: Boolean,
        val hasKey: Boolean,
        val autoDetect: Boolean,
        /** GPS watching is running, for the car app or Drive Mode ([DriveWatcher.watching]). */
        val watching: Boolean,
        val restaurantName: String? = null,
        /** True when the found place has an item list or an in-app menu page. */
        val hasMenu: Boolean = false,
        val errorMessage: String? = null,
        val radiusMeters: Int = 45,
    )

    data class Status(
        val pill: String,
        val title: String,
        val body: String,
        val tone: Tone,
        /** Pulse the indicator: something is actively happening. */
        val animated: Boolean,
        val action: Action?,
        val actionLabel: String?,
    )

    fun of(i: Input): Status {
        if (!i.hasLocation) return Status(
            "Permission needed", "Location permission required",
            "Pull Up Menu uses your location only to recognize the drive-thru you're in.",
            Tone.WARNING, false, Action.GRANT_LOCATION, "Allow location",
        )
        if (!i.hasKey) return Status(
            "Setup needed", "Add a Places API key",
            "Detection needs a Google Places API key. Add one in Settings.",
            Tone.WARNING, false, Action.OPEN_SETTINGS, "Open Settings",
        )
        val watching = i.autoDetect && i.watching
        return when (i.phase) {
            Phase.SEARCHING -> Status(
                "Searching", "Looking for a drive-thru", "Checking what's right around you.",
                Tone.ACTIVE, true, null, null,
            )
            Phase.FOUND -> {
                val name = i.restaurantName ?: "Restaurant"
                if (i.hasMenu) Status(
                    "Menu ready", name, "Restaurant detected. The menu is ready.",
                    Tone.SUCCESS, false, Action.OPEN_MENU, "Open menu",
                ) else Status(
                    "Detected", name, "Restaurant detected. No menu list for this place yet, but Maps and search links are.",
                    Tone.SUCCESS, false, Action.OPEN_MENU, "View details",
                )
            }
            Phase.DEMO -> Status(
                "Demo", i.restaurantName ?: "Demo", "Sample menu. Not a real location.",
                Tone.NEUTRAL, false, Action.OPEN_MENU, "Open menu",
            )
            Phase.NOTHING_NEARBY -> Status(
                if (watching) "Watching" else "Ready", "No drive-thru here",
                "No fast food within ${i.radiusMeters} m. Try again from the drive-thru lane.",
                if (watching) Tone.ACTIVE else Tone.NEUTRAL, watching, Action.DETECT, "Try again",
            )
            Phase.ERROR -> Status(
                "Attention", "Couldn't check", i.errorMessage ?: "Something went wrong. Try again.",
                Tone.WARNING, false, Action.DETECT, "Try again",
            )
            Phase.IDLE -> when {
                watching -> Status(
                    "Watching", "Watching for drive-thrus",
                    "Pull into a drive-thru lane and the menu opens by itself.",
                    Tone.ACTIVE, true, null, null,
                )
                i.autoDetect -> Status(
                    "Ready", "Ready to detect",
                    "Auto-detect runs while Android Auto is connected or Drive Mode is on.",
                    Tone.NEUTRAL, false, null, null,
                )
                else -> Status(
                    "Ready", "Ready to detect",
                    "In the drive-thru lane, tap Detect My Restaurant.",
                    Tone.NEUTRAL, false, null, null,
                )
            }
        }
    }

    /** "just now", "12 min ago", "3 h ago", "2 days ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val min = ((nowMs - thenMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
