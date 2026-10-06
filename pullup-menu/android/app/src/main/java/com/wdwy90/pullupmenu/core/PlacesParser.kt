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
            val photoList = buildList {
                if (photos != null) for (j in 0 until photos.length()) {
                    val ph = photos.optJSONObject(j)
                    val name = ph?.str("name").orEmpty()
                    if (ph != null && name.isNotBlank()) {
                        // Google asks us to credit the first listed author with the photo.
                        val a = ph.optJSONArray("authorAttributions")?.optJSONObject(0)
                        add(
                            PlacePhoto(
                                name = name,
                                authorName = a?.str("displayName"),
                                authorUri = a?.str("uri")?.let(::absoluteUrl),
                                mapsUri = ph.str("googleMapsUri")?.let(::absoluteUrl),
                            )
                        )
                    }
                }
            }
            out += Restaurant(
                id = p.getString("id"),
                name = p.optJSONObject("displayName")?.optString("text").orEmpty()
                    .ifEmpty { "Unknown restaurant" },
                address = p.str("shortFormattedAddress") ?: p.str("formattedAddress").orEmpty(),
                lat = lat,
                lng = lng,
                rating = if (p.has("rating")) p.getDouble("rating") else null,
                category = p.optJSONObject("primaryTypeDisplayName")?.optString("text")
                    ?.takeIf { it.isNotEmpty() },
                photos = photoList,
                websiteUri = p.str("websiteUri"),
                mapsUri = p.str("googleMapsUri"),
                distanceMeters = Geo.distanceMeters(fromLat, fromLng, lat, lng),
            )
        }
        return out.sortedBy { it.distanceMeters }
    }

    /** Places sometimes returns protocol-relative links ("//maps.google.com/..."). */
    fun absoluteUrl(u: String): String = if (u.startsWith("//")) "https:$u" else u

    /**
     * Null for a missing, JSON-null or blank value. Android's org.json turns JSON null into
     * the text "null" in optString, unlike the JVM org.json used by unit tests.
     */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
