package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureDetectorTest {
    private val lat = 40.0
    private val lng = -75.0
    private val visit = "place@1000"

    // 0.001 deg of latitude ≈ 111 m.
    private fun DepartureDetector.at(dLat: Double, accuracy: Double? = 10.0, key: String = visit) =
        onSample(key, lat, lng, lat + dLat, lng, accuracy)

    @Test fun staysWhileInTheLaneOrLot() {
        val d = DepartureDetector()
        assertFalse(d.at(0.0))
        assertFalse(d.at(0.0008)) // ~89 m: the far end of a big lot
        assertFalse(d.at(0.0025)) // ~278 m: still under 300 m
        assertFalse(d.at(0.0025))
    }

    @Test fun endsOnceAfterTwoFixesWellAway() {
        val d = DepartureDetector()
        assertFalse(d.at(0.0))
        assertFalse(d.at(0.003)) // ~333 m, first fix away
        assertTrue(d.at(0.0035)) // second in a row: the visit is over
        assertFalse(d.at(0.004)) // reported only once
        assertFalse(d.at(0.01))
    }

    @Test fun oneStrayFixDoesNotEndTheVisit() {
        val d = DepartureDetector()
        assertFalse(d.at(0.005)) // a GPS jump
        assertFalse(d.at(0.0001)) // back at the restaurant: start over
        assertFalse(d.at(0.005))
        assertTrue(d.at(0.005))
    }

    @Test fun ignoresInaccurateFixes() {
        val d = DepartureDetector()
        assertFalse(d.at(0.01, accuracy = 250.0))
        assertFalse(d.at(0.01, accuracy = 250.0))
        assertFalse(d.at(0.01, accuracy = 250.0))
        assertFalse(d.at(0.01, accuracy = 20.0))
        assertTrue(d.at(0.01, accuracy = null)) // no accuracy reported: taken as is
    }

    @Test fun aGapInWatchingStartsCountingAgain() {
        val d = DepartureDetector()
        assertFalse(d.at(0.005)) // a stray fix just before the car disconnects
        d.reset() // watching stopped
        assertFalse(d.at(0.005)) // first fix after it starts again
        assertTrue(d.at(0.005))
    }

    @Test fun aNewVisitStartsCountingAgain() {
        val d = DepartureDetector()
        assertFalse(d.at(0.005))
        assertFalse(d.at(0.005, key = "other@2000")) // a different restaurant was found meanwhile
        assertTrue(d.at(0.005, key = "other@2000"))
    }
}
