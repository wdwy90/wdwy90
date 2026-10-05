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
import java.net.URLEncoder

/** Thin client for Google Places API (New). */
class PlacesClient(private val apiKey: String) {

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
            val conn = (URL("$BASE/places:searchNearby").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", FIELD_MASK)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw IOException("Places API error $code: ${errorMessage(err)}")
                }
                PlacesParser.parseNearby(conn.inputStream.bufferedReader().readText(), lat, lng)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun photo(photoName: String, maxWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val url = "$BASE/$photoName/media?maxWidthPx=$maxWidthPx&key=" +
            URLEncoder.encode(apiKey, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
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
        private const val FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.shortFormattedAddress," +
                "places.location,places.rating,places.primaryTypeDisplayName,places.photos," +
                "places.websiteUri,places.googleMapsUri"
    }
}
