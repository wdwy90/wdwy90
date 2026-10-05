package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDetectorTest {
    private val lat = 40.0
    private val lng = -75.0

    @Test fun triggersAfterDwell() {
        val d = ArrivalDetector(dwellMs = 20_000)
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 10_000, 0.5)))
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 20_000, 0.0)))
    }

    @Test fun movingResetsDwell() {
        val d = ArrivalDetector(dwellMs = 20_000)
        d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0))
        d.onSample(ArrivalDetector.Sample(lat, lng, 15_000, 10.0)) // drove off
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 25_000, 0.0)))
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 45_000, 0.0)))
    }

    @Test fun onlyOneLookupPerSpot() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 5_000, 0.0)))
        // ~1.1 km north: new spot
        assertTrue(d.onSample(ArrivalDetector.Sample(lat + 0.01, lng, 10_000, 0.0)))
    }

    @Test fun derivesSpeedWhenGpsHasNone() {
        val d = ArrivalDetector(dwellMs = 0)
        d.onSample(ArrivalDetector.Sample(lat, lng, 0, 20.0))
        // 0.001 deg lat ≈ 111 m in 5 s ≈ 22 m/s -> still moving
        assertFalse(d.onSample(ArrivalDetector.Sample(lat + 0.001, lng, 5_000, null)))
        assertTrue(d.onSample(ArrivalDetector.Sample(lat + 0.001, lng, 10_000, null)))
    }

    @Test fun driveThruCreepCountsAsStopped() {
        val d = ArrivalDetector() // defaults: <3 m/s for 15 s
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 8_000, 2.5))) // pull forward one car
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 15_000, 0.0)))
    }

    @Test fun haversine() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 50.0)
    }
}
