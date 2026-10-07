package com.wdwy90.pullupmenu.core

import com.wdwy90.pullupmenu.core.CarModel.Lookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarModelTest {
    private val now = 1_800_000_000_000L

    private fun place(
        openNow: Boolean? = true,
        businessStatus: String? = null,
        rating: Double? = 4.3,
        prices: PriceList? = PriceList("Burger King", items("Burgers", 3) + items("Sides", 2), "Oct 6, 2026", "Burger King", ""),
        isDemo: Boolean = false,
        address: String = "1200 Main St",
    ) = Restaurant(
        id = "p1", name = "Burger King", address = address, lat = 0.0, lng = 0.0, rating = rating,
        category = "Fast food restaurant", photos = emptyList(), websiteUri = null, mapsUri = null,
        distanceMeters = 46.0, prices = prices, isDemo = isDemo, openNow = openNow, businessStatus = businessStatus,
    )

    private fun items(category: String, n: Int) = (1..n).map { PriceItem("$category $it", null, category = category) }

    // ---- Home ----

    @Test fun homeTitleOnlyChangesWithTheMode() {
        // Android Auto counts a changed row title as a new screen; the status lives in the text.
        val lookups = listOf(
            Lookup.Idle, Lookup.Searching, Lookup.Found("Wendy's", true), Lookup.NothingNearby,
            Lookup.Failed("No internet connection. Try again."),
        )
        for (auto in listOf(true, false)) {
            assertEquals(1, lookups.map { CarModel.home(false, auto, it).title }.distinct().size)
        }
        assertEquals("Ready for your next drive-thru", CarModel.home(false, true, Lookup.Idle).title)
        assertEquals("Auto-detect is off", CarModel.home(false, false, Lookup.Idle).title)
        assertEquals("Location permission needed", CarModel.home(true, true, Lookup.Idle).title)
    }

    @Test fun homeLinesFitTheRowAndSayWhatHappens() {
        val all = listOf(
            CarModel.home(false, true, Lookup.Searching),
            CarModel.home(false, true, Lookup.Found("Taco Bell", true)),
            CarModel.home(false, true, Lookup.NothingNearby),
            CarModel.home(false, false, Lookup.NothingNearby),
            CarModel.home(true, true, Lookup.Idle),
            CarModel.home(false, true, Lookup.Failed("Add your Google Places API key in the phone app.")),
        )
        for (h in all) assertTrue(h.toString(), h.lines.size in 1..2)
        assertEquals(listOf("Finding restaurant…"), all[0].lines)
        assertEquals(listOf("Last stop: Taco Bell", "Info from Google Maps"), all[1].lines)
        assertTrue(all[1].showRestaurant)
        assertEquals("Check again", all[2].check)
        assertEquals(listOf("No restaurant found here."), all[3].lines)
    }

    @Test fun theDemoNeedsNoGoogleCredit() {
        assertEquals(listOf("Last stop: McDonald's (demo)"), CarModel.home(false, false, Lookup.Found("McDonald's (demo)", false)).lines)
    }

    @Test fun anythingThatNeedsThePhoneSaysOnlyWhenParked() {
        assertEquals(CarModel.PHONE_WHEN_PARKED, CarModel.home(true, true, Lookup.Idle).lines.last())
        for (m in listOf("Add your Google Places API key in the phone app.", "Location permission needed. Open Pull Up Menu on your phone.")) {
            assertEquals(CarModel.PHONE_WHEN_PARKED, CarModel.problem(m).last())
        }
    }

    @Test fun errorsAreShortAndTheButtonRetries() {
        val h = CarModel.home(false, true, Lookup.Failed("No internet connection. Try again."))
        assertEquals(listOf("No internet connection."), h.lines)
        assertEquals("Try again", h.check)
        assertEquals(CarModel.Mark.PROBLEM, h.mark)
        assertEquals(listOf("Location unavailable.", "Try again in a moment."), CarModel.problem("Couldn't get your location. Try again in a moment."))
        assertEquals(listOf("Google Maps took too long."), CarModel.problem("Google Maps took too long. Try again."))
    }

    // ---- Card ----

    @Test fun cardSaysWhereWhatAndWhetherTheMenuIsHere() {
        val rows = CarModel.card(place(), now - 1000, now, showDistance = false, photoShown = false, photoAuthor = null)
        assertEquals(listOf("1200 Main St", "4.3 · Fast food restaurant", "Menu ready"), rows.map { it.title })
        assertEquals("Open now", rows[0].lines.single().text)
        assertEquals(CarModel.Tone.GOOD, rows[0].lines.single().parts[0].tone)
        assertEquals(listOf("Info from Google Maps"), rows[1].lines.map { it.text })
        assertEquals(listOf("2 categories · 5 items", "Availability varies by location"), rows[2].lines.map { it.text })
    }

    @Test fun openStatusIsOnlyShownWhileGooglesAnswerIsFresh() {
        val stale = CarModel.card(place(), now - CarModel.STATUS_FRESH_MS - 1, now, false, false, null)
        assertTrue(stale[0].lines.isEmpty())
        val unknown = CarModel.card(place(openNow = null), now, now, false, false, null)
        assertTrue(unknown[0].lines.isEmpty())
        val closed = CarModel.card(place(openNow = false), now, now, false, false, null)
        assertEquals(CarModel.Tone.BAD, closed[0].lines.single().parts[0].tone)
        assertEquals("Closed now", closed[0].lines.single().text)
        val gone = CarModel.card(place(businessStatus = "CLOSED_TEMPORARILY"), now, now, false, false, null)
        assertEquals("Temporarily closed", gone[0].lines.single().text)
    }

    @Test fun rowTitlesDoNotMoveWhenTheStatusOrPhotoChanges() {
        val a = CarModel.card(place(openNow = null), now, now, false, false, null).map { it.title }
        val b = CarModel.card(place(openNow = false), now, now, true, true, "Jo").map { it.title }
        assertEquals(a, b)
    }

    @Test fun distanceOnlyWhenOtherPlacesAreClose() {
        val rows = CarModel.card(place(), now, now, showDistance = true, photoShown = false, photoAuthor = null)
        assertEquals("Open now · 150 ft away", rows[0].lines.single().text)
    }

    @Test fun photoCreditIsItsOwnLine() {
        val rows = CarModel.card(place(), now, now, false, photoShown = true, photoAuthor = "Jordan Lee")
        assertEquals(listOf("Info from Google Maps", "Photo: Jordan Lee"), rows[1].lines.map { it.text })
        val anonymous = CarModel.card(place(), now, now, false, photoShown = true, photoAuthor = null)
        assertEquals("Photo from Google Maps", anonymous[1].lines[1].text)
    }

    @Test fun noRatingNoMenuNoInventedData() {
        val rows = CarModel.card(place(rating = null, prices = null, address = ""), now, now, false, false, null)
        assertEquals(listOf("Address not available", "Fast food restaurant", "Menu unavailable"), rows.map { it.title })
        assertEquals(CarModel.Icon.RESTAURANT, rows[1].icon)
        assertEquals(CarModel.Icon.NO_MENU, rows[2].icon)
    }

    @Test fun theDemoIsLabelledAsSample() {
        val rows = CarModel.card(place(isDemo = true, rating = null, openNow = null), now, now, true, false, null)
        assertEquals("Sample restaurant for the demo", rows[1].lines.single().text)
        assertTrue(rows[0].lines.isEmpty()) // no distance for the demo
    }

    @Test fun distances() {
        assertEquals("10 ft", CarModel.distance(0.0))
        assertEquals("150 ft", CarModel.distance(46.0))
        assertEquals("990 ft", CarModel.distance(301.0))
        assertEquals("0.2 mi", CarModel.distance(305.0))
        assertEquals("1.2 mi", CarModel.distance(1931.0))
    }

    // ---- Menu ----

    @Test fun menuRowText() {
        val burgers = ItemGroup("Burgers", items("Burgers", 12))
        assertEquals("12 items", CarModel.entryText(CarMenu.Entry("Burgers", listOf(burgers), CarMenu.Kind.CATEGORY)))
        assertEquals("1 item", CarModel.items(1))
        val part = CarMenu.Entry("Burgers 1 of 3", listOf(ItemGroup("Burgers", burgers.items.take(4))), CarMenu.Kind.PART)
        assertEquals("4 items · Burgers 1 to Burgers 4", CarModel.entryText(part))
        val combined = CarMenu.Entry("Sides, Drinks", listOf(ItemGroup("Sides", items("Sides", 2)), ItemGroup("Drinks", items("Drinks", 3))), CarMenu.Kind.COMBINED)
        assertEquals("2 categories · 5 items", CarModel.entryText(combined))
    }

    @Test fun searchFindsItemsInMenuOrderWithinTheRowLimit() {
        val groups = ItemGroups.byCategory(items("Burgers", 9) + items("Sides", 3))
        assertTrue(CarModel.search(groups, "  ", 6).items.isEmpty())
        val sides = CarModel.search(groups, "sides", 6)
        assertEquals(listOf("Sides 1", "Sides 2", "Sides 3"), sides.items.map { it.name })
        assertEquals(0, sides.more)
        val burgers = CarModel.search(groups, "burgers", 6)
        assertEquals(5, burgers.items.size) // and a sixth row: "4 more"
        assertEquals(4, burgers.more)
        assertEquals("Burgers", burgers.items[0].category)
        assertFalse(CarModel.search(groups, "pizza", 6).items.isNotEmpty())
    }
}
