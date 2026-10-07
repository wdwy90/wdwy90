package com.wdwy90.pullupmenu.core

import com.wdwy90.pullupmenu.core.StatusModel.Action
import com.wdwy90.pullupmenu.core.StatusModel.Input
import com.wdwy90.pullupmenu.core.StatusModel.Phase
import com.wdwy90.pullupmenu.core.StatusModel.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusModelTest {
    private val ready = Input(Phase.IDLE, hasLocation = true, hasKey = true, autoDetect = false, watching = false)

    @Test fun permissionComesFirst() {
        val s = StatusModel.of(ready.copy(hasLocation = false, phase = Phase.FOUND, restaurantName = "X"))
        assertEquals("Location permission required", s.title)
        assertEquals(Action.GRANT_LOCATION, s.action)
        assertEquals(Tone.WARNING, s.tone)
    }

    @Test fun missingKeyPointsToSettings() {
        assertEquals(Action.OPEN_SETTINGS, StatusModel.of(ready.copy(hasKey = false)).action)
    }

    @Test fun readyToDetect() {
        val s = StatusModel.of(ready)
        assertEquals("Ready to detect", s.title)
        assertFalse(s.animated)
    }

    @Test fun watchingOnlyWhenSomethingIsWatching() {
        assertEquals("Ready to detect", StatusModel.of(ready.copy(autoDetect = true)).title)
        val watching = StatusModel.of(ready.copy(autoDetect = true, watching = true))
        assertEquals("Watching for drive-thrus", watching.title)
        assertTrue(watching.animated)
        // GPS running without auto-detect (a manual check) must not claim watching.
        assertEquals("Ready to detect", StatusModel.of(ready.copy(watching = true)).title)
    }

    @Test fun searchingAnimates() {
        val s = StatusModel.of(ready.copy(phase = Phase.SEARCHING))
        assertEquals("Looking for a drive-thru", s.title)
        assertTrue(s.animated)
    }

    @Test fun foundWithMenuIsMenuReady() {
        val s = StatusModel.of(ready.copy(phase = Phase.FOUND, restaurantName = "Burger King", hasMenu = true))
        assertEquals("Menu ready", s.pill)
        assertEquals("Burger King", s.title)
        assertEquals(Action.OPEN_MENU, s.action)
    }

    @Test fun foundWithoutMenuIsDetected() {
        val s = StatusModel.of(ready.copy(phase = Phase.FOUND, restaurantName = "Local Diner"))
        assertEquals("Detected", s.pill)
    }

    @Test fun errorShowsMessage() {
        val s = StatusModel.of(ready.copy(phase = Phase.ERROR, errorMessage = "No internet connection. Try again."))
        assertEquals("No internet connection. Try again.", s.body)
        assertEquals(Action.DETECT, s.action)
    }

    @Test fun nothingNearbyUsesRadius() {
        val s = StatusModel.of(ready.copy(phase = Phase.NOTHING_NEARBY, radiusMeters = 45))
        assertTrue(s.body.contains("45 m"))
    }

    @Test fun ago() {
        val now = 10_000_000_000L
        assertEquals("just now", StatusModel.ago(now - 30_000, now))
        assertEquals("12 min ago", StatusModel.ago(now - 12 * 60_000, now))
        assertEquals("3 h ago", StatusModel.ago(now - 3 * 3_600_000, now))
        assertEquals("yesterday", StatusModel.ago(now - 30 * 3_600_000L, now))
        assertEquals("3 days ago", StatusModel.ago(now - 72 * 3_600_000L, now))
        assertEquals("just now", StatusModel.ago(now + 5_000, now))
    }
}
