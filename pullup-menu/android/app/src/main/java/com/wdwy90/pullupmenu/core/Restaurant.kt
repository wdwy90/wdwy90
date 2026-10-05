package com.wdwy90.pullupmenu.core

data class Restaurant(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val rating: Double?,
    val category: String?,
    /** Places API photo resource names ("places/xxx/photos/yyy"). */
    val photoNames: List<String>,
    val websiteUri: String?,
    val mapsUri: String?,
    val distanceMeters: Double = 0.0,
    /** Official chain menu page, when this is a known chain. */
    val menuUrl: String? = null,
)
