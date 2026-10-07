package com.wdwy90.pullupmenu.core

data class PlacePhoto(
    /** Places API photo resource name ("places/xxx/photos/yyy"). */
    val name: String,
    /** Google requires the photographer's name (and link) to be shown with the photo. */
    val authorName: String?,
    val authorUri: String?,
    /** This photo's own page on Google Maps (Google asks that each photo link to it). */
    val mapsUri: String? = null,
)

data class Restaurant(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val rating: Double?,
    val category: String?,
    val photos: List<PlacePhoto>,
    val websiteUri: String?,
    val mapsUri: String?,
    val distanceMeters: Double = 0.0,
    /** Official chain menu page, when this is a known chain. */
    val menuUrl: String? = null,
    /** Typical prices for this chain, when we have them. */
    val prices: PriceList? = null,
    /** True for the built-in demo card (not a real place). */
    val isDemo: Boolean = false,
    /** Google's "open now" at lookup time; null when the place has no hours. */
    val openNow: Boolean? = null,
    /** Google's businessStatus ("OPERATIONAL", "CLOSED_TEMPORARILY", "CLOSED_PERMANENTLY"); null when not given. */
    val businessStatus: String? = null,
) {
    /**
     * Short status for the header: "Open now", "Closed now", "Temporarily closed", "Permanently closed",
     * or null when Google gave no hours. Never guessed.
     */
    val openStatus: String?
        get() = when (businessStatus) {
            "CLOSED_TEMPORARILY" -> "Temporarily closed"
            "CLOSED_PERMANENTLY" -> "Permanently closed"
            else -> when (openNow) {
                true -> "Open now"
                false -> "Closed now"
                null -> null
            }
        }
}
