package com.wdwy90.pullupmenu.core

import org.json.JSONObject

object PlacesParser {
    /** Parses a Places API (New) searchNearby response, sorted nearest first. */
    fun parseNearby(json: String, fromLat: Double, fromLng: Double): List<Restaurant> {
        val places = JSONObject(json).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Restaurant>(places.length())
        for (i in 0 until places.length()) {
            val p = places.getJSONObject(i)
            val loc = p.optJSONObject("location") ?: continue
            val lat = loc.getDouble("latitude")
            val lng = loc.getDouble("longitude")
            val photos = p.optJSONArray("photos")
            val photoNames = buildList {
                if (photos != null) for (j in 0 until photos.length()) {
                    photos.getJSONObject(j).optString("name").takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            out += Restaurant(
                id = p.getString("id"),
                name = p.optJSONObject("displayName")?.optString("text").orEmpty()
                    .ifEmpty { "Unknown restaurant" },
                address = p.optString("shortFormattedAddress")
                    .ifEmpty { p.optString("formattedAddress") },
                lat = lat,
                lng = lng,
                rating = if (p.has("rating")) p.getDouble("rating") else null,
                category = p.optJSONObject("primaryTypeDisplayName")?.optString("text")
                    ?.takeIf { it.isNotEmpty() },
                photoNames = photoNames,
                websiteUri = p.optString("websiteUri").takeIf { it.isNotEmpty() },
                mapsUri = p.optString("googleMapsUri").takeIf { it.isNotEmpty() },
                distanceMeters = Geo.distanceMeters(fromLat, fromLng, lat, lng),
            )
        }
        return out.sortedBy { it.distanceMeters }
    }
}
