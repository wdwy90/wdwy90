package com.wdwy90.pullupmenu.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the car screens say, worked out from plain data so it can be unit tested; the screens in
 * the car package only draw it. Everything shown comes from the lookup, Google's answer or the
 * bundled menu: nothing is guessed.
 */
object CarModel {

    /** Tells the driver to keep their eyes on the road (Android Auto quality rule VI-1). */
    const val PHONE_WHEN_PARKED = "Only look at your phone when parked."

    /** Google's open/closed answer is shown for this long after Google gave it. */
    const val STATUS_FRESH_MS = 30 * 60 * 1000L

    // ---- Home ----

    /** Where the last lookup stands, as the home screen tells it. */
    sealed interface Lookup {
        data object Idle : Lookup
        data object Searching : Lookup
        data class Found(val name: String, val fromGoogle: Boolean) : Lookup
        data object NothingNearby : Lookup
        data class Failed(val message: String) : Lookup
    }

    /** The icon next to the home status. */
    enum class Mark { READY, OFF, SEARCHING, FOUND, NOTHING, PROBLEM }

    data class Home(
        /** Changes only with the mode, so status updates are template refreshes, not new screens. */
        val title: String,
        /** One or two short lines. */
        val lines: List<String>,
        val mark: Mark,
        /** The check button's label. */
        val check: String,
        val showRestaurant: Boolean,
    )

    fun home(permissionMissing: Boolean, autoDetect: Boolean, lookup: Lookup): Home {
        val title = when {
            permissionMissing -> "Location permission needed"
            autoDetect -> "Ready for your next drive-thru"
            else -> "Auto-detect is off"
        }
        if (permissionMissing) {
            return Home(title, listOf("Open Pull Up Menu on your phone to allow location.", PHONE_WHEN_PARKED), Mark.PROBLEM, "Check now", false)
        }
        return when (lookup) {
            Lookup.Searching -> Home(title, listOf("Finding restaurant…"), Mark.SEARCHING, "Check now", false)
            is Lookup.Found -> Home(
                title,
                listOfNotNull("Last stop: ${lookup.name}", if (lookup.fromGoogle) "Info from Google Maps" else null),
                Mark.FOUND, "Check now", true,
            )
            Lookup.NothingNearby -> Home(
                title,
                listOfNotNull("No restaurant found here.", if (autoDetect) "I'll look again at your next stop." else null),
                Mark.NOTHING, "Check again", false,
            )
            is Lookup.Failed -> Home(title, problem(lookup.message), Mark.PROBLEM, "Try again", false)
            Lookup.Idle -> if (autoDetect) {
                Home(title, listOf("Pull into a drive-thru and the restaurant appears here."), Mark.READY, "Check now", false)
            } else {
                Home(title, listOf("In a drive-thru lane, tap Check now."), Mark.OFF, "Check now", false)
            }
        }
    }

    /**
     * A lookup error as the car says it: the button already says "Try again", and anything that
     * needs the phone gets the look-only-when-parked line.
     */
    fun problem(message: String): List<String> {
        val m = message.trim()
        return when {
            m.startsWith("Couldn't get your location") -> listOf("Location unavailable.", "Try again in a moment.")
            m.startsWith("Location permission needed") ->
                listOf("Open Pull Up Menu on your phone to allow location.", PHONE_WHEN_PARKED)
            m.contains("phone") -> listOf(m, PHONE_WHEN_PARKED)
            else -> listOf(m.removeSuffix(" Try again.").ifBlank { "Something went wrong." })
        }
    }

    // ---- Restaurant card ----

    enum class Tone { PLAIN, GOOD, BAD }

    data class Part(val text: String, val tone: Tone = Tone.PLAIN)

    /** One line of row text; parts with a tone are coloured (only row text may be). */
    data class Line(val parts: List<Part>) {
        constructor(text: String) : this(listOf(Part(text)))
        val text: String get() = parts.joinToString("") { it.text }
    }

    enum class Icon { PLACE, STAR, RESTAURANT, MENU, NO_MENU }

    /** A card row. Its [title] stays put while the card is up, so updates to [lines] are refreshes. */
    data class CardRow(val title: String, val lines: List<Line>, val icon: Icon)

    /**
     * The restaurant card's rows: where it is (and whether it's open), what it is (rating, type and
     * the Google credit), and whether its menu is here. [checkedMs] is when Google last answered for
     * it; open/closed shows only while that answer is fresh. [showDistance] when other places are
     * close by, so the driver can tell which one this is. [photoShown] adds the photographer's credit.
     */
    fun card(
        r: Restaurant,
        checkedMs: Long?,
        nowMs: Long,
        showDistance: Boolean,
        photoShown: Boolean,
        photoAuthor: String?,
    ): List<CardRow> {
        val status = r.openStatus?.takeIf { checkedMs != null && nowMs - checkedMs in 0..STATUS_FRESH_MS }
        val where = buildList {
            if (status != null) add(Part(status, if (status == "Open now") Tone.GOOD else Tone.BAD))
            if (showDistance && !r.isDemo) {
                if (isNotEmpty()) add(Part(" · "))
                add(Part("${distance(r.distanceMeters)} away"))
            }
        }
        val place = CardRow(
            r.address.ifBlank { "Address not available" },
            if (where.isEmpty()) emptyList() else listOf(Line(where)),
            Icon.PLACE,
        )

        val kind = r.category?.ifBlank { null } ?: "Restaurant"
        val rating = r.rating?.let { String.format(Locale.US, "%.1f", it) }
        val credit = when {
            r.isDemo -> listOf(Line("Sample restaurant for the demo"))
            photoShown -> listOf(
                Line("Info from Google Maps"),
                Line(photoAuthor?.ifBlank { null }?.let { "Photo: $it" } ?: "Photo from Google Maps"),
            )
            else -> listOf(Line("Info from Google Maps"))
        }
        val about = CardRow(listOfNotNull(rating, kind).joinToString(" · "), credit, if (rating != null) Icon.STAR else Icon.RESTAURANT)

        val prices = r.prices
        val menu = if (prices != null && prices.items.isNotEmpty()) {
            val categories = ItemGroups.byCategory(prices.items).size
            val size = if (categories > 1) "$categories categories · ${items(prices.items.size)}" else items(prices.items.size)
            CardRow("Menu ready", listOf(Line(size), Line("Availability varies by location")), Icon.MENU)
        } else {
            CardRow("Menu unavailable", listOf(Line("No menu for this restaurant yet.")), Icon.NO_MENU)
        }
        return listOf(place, about, menu)
    }

    /** "60 ft", "990 ft", "0.3 mi": feet to the nearest 10 below 1,000 ft. */
    fun distance(meters: Double): String {
        val feet = meters * 3.28084
        return if (feet < 995) "${((feet / 10).roundToInt() * 10).coerceAtLeast(10)} ft"
        else String.format(Locale.US, "%.1f mi", meters / 1609.344)
    }

    // ---- Menu ----

    fun items(n: Int): String = if (n == 1) "1 item" else "$n items"

    /** The text line under a menu row: how much it holds, and for a part where it starts and ends. */
    fun entryText(e: CarMenu.Entry): String = when (e.kind) {
        CarMenu.Kind.CATEGORY -> items(e.itemCount)
        CarMenu.Kind.PART -> {
            val all = e.groups.flatMap { it.items }
            "${items(all.size)} · ${all.first().name} to ${all.last().name}"
        }
        CarMenu.Kind.COMBINED -> "${e.groups.size} categories · ${items(e.itemCount)}"
    }

    /** Search results: what fits on one list, and how many more matched. */
    data class Results(val items: List<PriceItem>, val more: Int)

    /**
     * Items matching [query], in menu order, as many as [rows] rows hold (the last row says how many
     * more matched when they don't all fit).
     */
    fun search(groups: List<ItemGroup>, query: String, rows: Int): Results {
        if (ItemSearch.isBlank(query)) return Results(emptyList(), 0)
        val all = ItemSearch.filter(groups, query).flatMap { g -> g.items.map { it.copy(category = it.category ?: g.title) } }
        if (all.size <= rows) return Results(all, 0)
        val shown = all.take((rows - 1).coerceAtLeast(1))
        return Results(shown, all.size - shown.size)
    }
}
