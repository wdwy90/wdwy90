package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEndDetectorTest {
    private val lat = 40.0
    private val lng = -75.0
    private val min = 60_000L

    @Test fun nothingBeforeFirstSample() {
        assertFalse(DriveEndDetector().onTick(10 * 60 * min))
    }

    @Test fun stopsAfter15MinutesParked() {
        val d = DriveEndDetector()
        assertFalse(d.onSample(lat, lng, 0))
        assertFalse(d.onSample(lat + 0.0005, lng, 5 * min)) // ~55 m: still parked
        assertFalse(d.onTick(14 * min))
        assertTrue(d.onTick(15 * min))
    }

    @Test fun movingReanchors() {
        val d = DriveEndDetector()
        d.onSample(lat, lng, 0)
        assertFalse(d.onSample(lat + 0.01, lng, 10 * min)) // ~1.1 km: driving
        assertFalse(d.onTick(20 * min))
        assertTrue(d.onTick(25 * min))
    }

    @Test fun stopsAfter4Hours() {
        val d = DriveEndDetector()
        var t = 0L
        var i = 0
        while (t < 4 * 60 * min) {
            assertFalse(d.onSample(lat + i * 0.01, lng, t))
            t += 10 * min
            i++
        }
        assertTrue(d.onSample(lat + i * 0.01, lng, t))
    }

    @Test fun startedWithNoFixStopsAfter15Minutes() {
        val d = DriveEndDetector()
        d.start(0)
        assertFalse(d.onTick(14 * min))
        assertTrue(d.onTick(15 * min))
    }

    @Test fun firstFixAfterStartRestartsParkedTimer() {
        val d = DriveEndDetector()
        d.start(0)
        assertFalse(d.onSample(lat, lng, 10 * min))
        assertFalse(d.onTick(24 * min))
        assertTrue(d.onTick(25 * min))
    }
}
