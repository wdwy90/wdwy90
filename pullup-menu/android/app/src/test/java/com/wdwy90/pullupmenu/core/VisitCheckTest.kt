package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitCheckTest {
    private val bk = place("bk")
    private val tb = place("tb")
    private val kfc = place("kfc")

    @Test fun nothingAroundKeepsTheVisit() {
        assertTrue(VisitCheck.staysAt(bk, chosen = false, found = emptyList()))
    }

    @Test fun stillTheNearestKeepsTheVisit() {
        assertTrue(VisitCheck.staysAt(bk, chosen = false, found = listOf(bk, tb)))
    }

    @Test fun anotherPlaceNearerIsTheNewVisit() {
        // Pulled into the drive-thru next door: it's nearest now, even though the visit is still close.
        assertFalse(VisitCheck.staysAt(bk, chosen = false, found = listOf(tb, bk)))
        assertFalse(VisitCheck.staysAt(bk, chosen = false, found = listOf(tb)))
    }

    @Test fun aPlacePickedFromTheListStaysWhileItIsNearby() {
        assertTrue(VisitCheck.staysAt(bk, chosen = true, found = listOf(tb, kfc, bk)))
        assertFalse(VisitCheck.staysAt(bk, chosen = true, found = listOf(tb, kfc)))
    }

    private fun place(id: String) = Restaurant(
        id = id, name = id, address = "", lat = 0.0, lng = 0.0, rating = null, category = null,
        photos = emptyList(), websiteUri = null, mapsUri = null,
    )
}
