package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedLightFilterTest {
    private val lat = 40.0
    private val lng = -75.0

    @Test fun fastDriveOffAfterTriggerIsRedLight() {
        val f = RedLightFilter()
        f.onTrigger(lat, lng, 0)
        assertFalse(f.onSample(lat, lng, 5_000, 0.0))
        assertTrue(f.onSample(lat + 0.0003, lng, 20_000, 9.0)) // green light, ~20 mph
        assertFalse(f.onSample(lat + 0.0006, lng, 22_000, 12.0)) // only reported once
        assertTrue(f.isIgnored(lat, lng))
        assertTrue(f.isIgnored(lat + 0.0003, lng)) // ~33 m away
        assertFalse(f.isIgnored(lat + 0.001, lng)) // ~111 m away
    }

    @Test fun slowCreepIsDriveThru() {
        val f = RedLightFilter()
        f.onTrigger(lat, lng, 0)
        assertFalse(f.onSample(lat, lng, 10_000, 2.0))
        assertFalse(f.onSample(lat, lng, 30_000, 4.0))
        assertFalse(f.isIgnored(lat, lng))
    }

    @Test fun fastAfterWindowIsNormalDriving() {
        val f = RedLightFilter()
        f.onTrigger(lat, lng, 0)
        assertFalse(f.onSample(lat, lng, 46_000, 15.0))
        assertFalse(f.isIgnored(lat, lng))
    }

    @Test fun nothingWithoutTrigger() {
        val f = RedLightFilter()
        assertFalse(f.onSample(lat, lng, 0, 20.0))
        assertFalse(f.isIgnored(lat, lng))
    }

    @Test fun derivesSpeedWhenGpsHasNone() {
        val f = RedLightFilter()
        f.onSample(lat, lng, 0, null)
        f.onTrigger(lat, lng, 0)
        // 0.001 deg lat ≈ 111 m in 10 s ≈ 11 m/s
        assertTrue(f.onSample(lat + 0.001, lng, 10_000, null))
    }

    @Test fun keepsAtMostMaxSpotsAndClears() {
        val f = RedLightFilter(maxSpots = 2)
        for (i in 0 until 3) {
            val spotLat = lat + i * 0.01
            f.onTrigger(spotLat, lng, i * 100_000L)
            assertTrue(f.onSample(spotLat, lng, i * 100_000L + 1_000, 10.0))
        }
        assertFalse(f.isIgnored(lat, lng)) // oldest dropped
        assertTrue(f.isIgnored(lat + 0.02, lng))
        f.clear()
        assertFalse(f.isIgnored(lat + 0.02, lng))
    }

    @Test fun newTriggerReplacesPendingOne() {
        val f = RedLightFilter()
        f.onTrigger(lat, lng, 0)
        f.onTrigger(lat + 0.01, lng, 1_000)
        assertTrue(f.onSample(lat + 0.01, lng, 2_000, 10.0))
        assertTrue(f.isIgnored(lat + 0.01, lng))
        assertFalse(f.isIgnored(lat, lng))
    }

    @Test fun sampleBeforeTriggerKeepsIt() {
        val f = RedLightFilter()
        f.onTrigger(lat, lng, 0)
        assertFalse(f.onSample(lat, lng, -1, 20.0))
        assertTrue(f.onSample(lat, lng, 1_000, 20.0))
        assertTrue(f.isIgnored(lat, lng))
    }
}
