package com.wdwy90.pullupmenu.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for Google Places API (New). [identityHeaders] (X-Android-Package, X-Android-Cert,
 * see [AppIdentity]) let the API key be restricted to this app.
 */
class PlacesClient(
    private val apiKey: String,
    private val identityHeaders: Map<String, String> = emptyMap(),
    /** Opens each request's connection (tests pass a fake one). */
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    /** Google answered with an error status ([code]), e.g. a rejected key or too many requests. */
    class HttpException(val code: Int, message: String) : IOException(message) {
        /** Too many requests or a server error: the same request may work later. */
        val retryable: Boolean get() = code == 429 || code >= 500
    }

    suspend fun nearbyRestaurants(lat: Double, lng: Double, radiusM: Double): List<Restaurant> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("includedTypes", JSONArray(FOOD_TYPES))
                .put("maxResultCount", 10)
                .put("rankPreference", "DISTANCE")
                .put(
                    "locationRestriction", JSONObject().put(
                        "circle", JSONObject()
                            .put("center", JSONObject().put("latitude", lat).put("longitude", lng))
                            .put("radius", radiusM)
                    )
                )
            val conn = open(URL("$BASE/places:searchNearby")).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
                identityHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw HttpException(code, "Places API error $code: ${errorMessage(err)}")
                }
                PlacesParser.parseNearby(conn.inputStream.bufferedReader().readText(), lat, lng)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun photo(photoName: String, maxWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        // The key goes in a header, as for the search: a URL can end up in logs and error messages.
        val conn = open(URL("$BASE/$photoName/media?maxWidthPx=$maxWidthPx")).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("X-Goog-Api-Key", apiKey)
            identityHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(body: String): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        body.take(200)
    }

    companion object {
        private const val BASE = "https://places.googleapis.com/v1"
        // Fast food only: this is what drive-thrus are tagged as in Google Places.
        private val FOOD_TYPES = listOf("fast_food_restaurant")
        // businessStatus is a Pro-tier field and currentOpeningHours Enterprise, the tier rating and
        // websiteUri already put every request in, so neither changes the SKU.
        private const val FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.shortFormattedAddress," +
                "places.location,places.rating,places.primaryTypeDisplayName,places.photos," +
                "places.websiteUri,places.googleMapsUri,places.currentOpeningHours,places.businessStatus"
    }
}
