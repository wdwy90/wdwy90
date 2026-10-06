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
)
