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
        val d = ArrivalDetector() // defaults: <3 m/s for 20 s
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 8_000, 2.5))) // pull forward one car
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 15_000, 0.0))) // 15 s is not enough now
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 20_000, 0.0)))
    }

    @Test fun markLookedUpSkipsThatSpot() {
        val d = ArrivalDetector(dwellMs = 0)
        d.markLookedUp(lat, lng)
        assertTrue(d.wasLookedUpNear(lat + 0.0003, lng)) // ~33 m away
        assertFalse(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
        assertTrue(d.onSample(ArrivalDetector.Sample(lat + 0.01, lng, 5_000, 0.0)))
    }

    @Test fun haversine() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test fun resetForgetsLastLookup() {
        val d = ArrivalDetector(dwellMs = 0)
        d.markLookedUp(lat, lng)
        d.reset()
        assertFalse(d.wasLookedUpNear(lat, lng))
        assertTrue(d.onSample(ArrivalDetector.Sample(lat, lng, 0, 0.0)))
    }

    @Test fun lookedUpNearEndsAtTheSearchRadius() {
        val d = ArrivalDetector(dwellMs = 0)
        d.markLookedUp(lat, lng)
        assertTrue(d.wasLookedUpNear(lat + M40, lng))
        assertFalse(d.wasLookedUpNear(lat + M50, lng))
    }

    // A red light (or a parking-lot stop) just before the lane: nothing there, then the lane.

    @Test fun stopPastTheSearchCircleAfterNothingNearbyIsLookedUp() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.nothingFound(d.lookupId)
        assertTrue(d.onSample(stop(M50 + M10, 5_000))) // ~60 m on
    }

    @Test fun stopInsideTheSearchCircleAfterNothingNearbyIsNot() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.nothingFound(d.lookupId)
        assertFalse(d.onSample(stop(M30, 5_000)))
    }

    @Test fun stillRunningCoversItsCircle() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        assertFalse(d.onSample(stop(M30, 5_000)))
        assertTrue(d.onSample(stop(M50, 10_000)))
    }

    // A place found: the line creeping toward it is the same visit; the place next door is not.

    @Test fun creepingTowardTheFoundPlaceIsNotLookedUpAgain() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.found(d.lookupId, lat + M40, lng) // the place is 40 m ahead
        assertFalse(d.onSample(stop(M10, 5_000)))
        assertFalse(d.onSample(stop(M30, 10_000)))
        assertFalse(d.onSample(stop(M40, 15_000))) // at the window
    }

    @Test fun pullingAwayFromTheFoundPlaceIsLookedUpAgain() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.found(d.lookupId, lat + M10, lng) // the place is 10 m ahead
        assertFalse(d.onSample(stop(-M10, 5_000))) // GPS wander: 20 m from it, within the slack
        assertTrue(d.onSample(stop(-M30, 10_000))) // 40 m from it: the drive-thru next door
    }

    @Test fun leavingTheSearchCircleIsLookedUpEvenTowardTheFoundPlace() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.found(d.lookupId, lat + M40, lng)
        assertTrue(d.onSample(stop(M50 + M10, 5_000))) // past the place, 60 m from the first stop
    }

    // No answer: try again while stopped, a few times.

    @Test fun failedLookupIsRetriedWhileStopped() {
        val d = ArrivalDetector() // 20 s dwell; retries after 15 s, 30 s, 60 s
        assertFalse(d.onSample(stop(0.0, 0)))
        assertTrue(d.onSample(stop(0.0, 20_000)))
        d.failed(d.lookupId, retryable = true, nowMs = 20_500)
        assertFalse(d.onSample(stop(0.0, 30_000))) // a new dwell has to pass first
        assertTrue(d.onSample(stop(0.0, 40_000))) // and the 15 s wait has
        d.failed(d.lookupId, retryable = true, nowMs = 40_500)
        assertFalse(d.onSample(stop(0.0, 65_000))) // 30 s wait: until 70.5 s
        assertTrue(d.onSample(stop(0.0, 71_000)))
        d.failed(d.lookupId, retryable = true, nowMs = 71_000)
        assertFalse(d.onSample(stop(0.0, 120_000))) // 60 s wait
        assertTrue(d.onSample(stop(0.0, 131_000)))
        d.failed(d.lookupId, retryable = true, nowMs = 131_000)
        assertFalse(d.onSample(stop(0.0, 400_000))) // three retries, then this spot is done
        assertTrue(d.onSample(stop(M50, 420_000))) // a new spot is still looked up
    }

    @Test fun drivingOnStartsTheRetriesOver() {
        val d = ArrivalDetector(dwellMs = 0)
        repeat(4) { i ->
            assertTrue(d.onSample(stop(0.0, i * 100_000L)))
            d.failed(d.lookupId, retryable = true, nowMs = i * 100_000L)
        }
        d.onSample(ArrivalDetector.Sample(lat + M50 + M50, lng, 500_000, 12.0)) // drove on
        assertTrue(d.onSample(stop(M50 + M50, 505_000)))
        d.failed(d.lookupId, retryable = true, nowMs = 505_000)
        assertTrue(d.onSample(stop(M50 + M50, 525_000))) // retried, not given up
    }

    @Test fun failureThatWontGoAwayIsNotRetried() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.failed(d.lookupId, retryable = false, nowMs = 0) // a rejected key, say
        assertFalse(d.onSample(stop(0.0, 100_000)))
    }

    @Test fun outcomeOfAReplacedLookupIsIgnored() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        val first = d.lookupId
        val manual = d.markLookedUp(lat + 0.01, lng) // "Check now" somewhere else meanwhile
        d.failed(first, retryable = true, nowMs = 1_000)
        assertTrue(manual != first)
        assertTrue(d.wasLookedUpNear(lat + 0.01, lng))
    }

    // GPS accuracy

    @Test fun poorFixWaitsForABetterOne() {
        val d = ArrivalDetector() // 20 s dwell, 50 m accuracy, 60 s fallback
        assertFalse(d.onSample(stop(0.0, 0, accuracy = 80.0)))
        assertFalse(d.onSample(stop(0.0, 20_000, accuracy = 80.0)))
        assertFalse(d.onSample(stop(0.0, 40_000, accuracy = 80.0)))
        assertTrue(d.onSample(stop(0.0, 45_000, accuracy = 12.0)))
        assertEquals(45.0, d.radiusM, 0.001)
    }

    @Test fun onlyPoorFixesLookUpAfterAMinuteOverAWiderCircle() {
        val d = ArrivalDetector()
        assertFalse(d.onSample(stop(0.0, 0, accuracy = 80.0)))
        assertFalse(d.onSample(stop(0.0, 59_000, accuracy = 80.0)))
        assertTrue(d.onSample(stop(0.0, 60_000, accuracy = 80.0)))
        assertEquals(125.0, d.radiusM, 0.001) // 45 m + the fix's 80 m

        val worse = ArrivalDetector()
        worse.onSample(stop(0.0, 0, accuracy = 400.0))
        assertTrue(worse.onSample(stop(0.0, 60_000, accuracy = 400.0)))
        assertEquals(150.0, worse.radiusM, 0.001) // capped
    }

    @Test fun forgetAfterARedLight() {
        val d = ArrivalDetector(dwellMs = 0)
        assertTrue(d.onSample(stop(0.0, 0)))
        d.nothingFound(d.lookupId)
        d.forget() // the car drove off fast: it was a red light
        assertTrue(d.onSample(stop(M30, 5_000)))
    }

    /** Stopped [north] degrees of latitude north of the test spot (see the M constants). */
    private fun stop(north: Double, timeMs: Long, accuracy: Double? = null) =
        ArrivalDetector.Sample(lat + north, lng, timeMs, 0.0, accuracy)

    private companion object {
        // Degrees of latitude for a distance north (1 degree ≈ 111.2 km).
        const val M10 = 10 / 111_195.0
        const val M30 = 30 / 111_195.0
        const val M40 = 40 / 111_195.0
        const val M50 = 50 / 111_195.0
    }
}
